package it.medbook.service;

import it.medbook.model.Disponibilita;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.repository.DisponibilitaRepository;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DisponibilitaService {
    /** SEC-01: maximum number of one-hour slots materialized by a single creation request. */
    public static final int MAX_SLOT_PER_RICHIESTA = 1000;
    private final DisponibilitaRepository repository;
    private final Clock clock;

    public DisponibilitaService(DisponibilitaRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    public DisponibilitaService(DisponibilitaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = java.util.Objects.requireNonNull(clock, "Clock obbligatorio.");
    }

    public List<Disponibilita> getDisponibilitaPrenotabili(Integer idMedico, Integer idPrestazione,
                                                         LocalDate data) throws SQLException {
        if (idMedico != null) { validaId(idMedico, "medico"); }
        if (idPrestazione != null) { validaId(idPrestazione, "prestazione"); }
        LocalDateTime adesso = LocalDateTime.now(clock);
        return repository.findPrenotabili(idMedico, idPrestazione, data, adesso);
    }

    public List<Disponibilita> getDisponibilitaByMedico(Integer idMedico) throws SQLException {
        validaId(idMedico, "medico");
        return repository.findByMedico(idMedico);
    }

    public Optional<Disponibilita> getDisponibilitaById(Integer idDisponibilita) throws SQLException {
        validaId(idDisponibilita, "disponibilità");
        return repository.findById(idDisponibilita);
    }

    public List<Disponibilita> creaDisponibilita(Integer idMedico, LocalDateTime dataOraInizio,
                                               LocalDateTime dataOraFine) throws SQLException {
        validaId(idMedico, "medico");
        if (dataOraInizio == null || dataOraFine == null) {
            throw new IllegalArgumentException("Data e ora di inizio e fine sono obbligatorie.");
        }
        /* DATETIME con secondi interi e anni 1000–9999, verificato prima dell'aritmetica */
        /* sull'intervallo. Frazioni rifiutate, mai troncate o arrotondate, anche se uguali agli estremi. */
        if (dataOraInizio.getNano() != 0 || dataOraFine.getNano() != 0
                || dataOraInizio.getYear() < 1000 || dataOraInizio.getYear() > 9999
                || dataOraFine.getYear() < 1000 || dataOraFine.getYear() > 9999) {
            throw new IllegalArgumentException("Date non rappresentabili da MySQL DATETIME.");
        }
        if (!dataOraFine.isAfter(dataOraInizio)) {
            throw new IllegalArgumentException("La fine deve essere successiva all'inizio.");
        }
        Duration durata = Duration.between(dataOraInizio, dataOraFine);
        if (durata.getSeconds() % 3600 != 0 || durata.getNano() != 0) {
            throw new IllegalArgumentException("L'intervallo deve essere un multiplo esatto di 60 minuti.");
        }
        /* Il conteggio slot deriva dalla durata, mai dall'iterazione. Nell'intervallo DATETIME */
        /* è inferiore a 9000 × 366 × 24 ore: l'aritmetica long non può traboccare. */
        long numeroSlot = durata.getSeconds() / 3600;
        if (numeroSlot > MAX_SLOT_PER_RICHIESTA) {
            throw new IllegalArgumentException("Intervallo troppo ampio: massimo " + MAX_SLOT_PER_RICHIESTA + " slot per richiesta.");
        }
        /* Inizio strettamente successivo all'orologio del servizio nella sua zona, */
        /* senza troncamento: un inizio già passato nello stesso secondo è rifiutato. */
        /* L'intervallo può attraversare più giorni, entro il solo limite slot; il repository */
        /* ripete il controllo dopo il mutex medico e prima del primo INSERT. */
        DisponibilitaRepository.richiediInizioFuturo(dataOraInizio, clock);

        List<Disponibilita> slots = new ArrayList<>((int) numeroSlot);
        LocalDateTime inizioSlot = dataOraInizio;
        while (inizioSlot.isBefore(dataOraFine)) {
            LocalDateTime fineSlot = inizioSlot.plusHours(1);
            Disponibilita slot = new Disponibilita();
            slot.setDataOraInizio(inizioSlot);
            slot.setDataOraFine(fineSlot);
            slots.add(slot);
            inizioSlot = fineSlot;
        }
        return repository.saveAllAtomically(idMedico, slots);
    }

    public Disponibilita configuraDisponibilita(Integer idDisponibilita, Integer idPrestazione,
                                                Integer idStanza) throws SQLException {
        validaId(idDisponibilita, "disponibilità");
        validaId(idPrestazione, "prestazione");
        validaId(idStanza, "stanza");
        return repository.configuraAtomically(idDisponibilita, idPrestazione, idStanza);
    }

    public Disponibilita modificaOrario(Integer idDisponibilita, Integer idMedico,
            LocalDateTime inizio, LocalDateTime fine) throws SQLException {
        validaId(idDisponibilita, "disponibilità");
        validaId(idMedico, "medico");
        if (inizio == null || fine == null || !Duration.between(inizio, fine).equals(Duration.ofHours(1))) {
            throw new IllegalArgumentException("Inizio e fine devono definire esattamente 60 minuti.");
        }
        if (inizio.getNano() != 0 || fine.getNano() != 0
                || inizio.getYear() < 1000 || fine.getYear() > 9999) {
            throw new IllegalArgumentException("Date non rappresentabili da MySQL DATETIME.");
        }
        if (!inizio.isAfter(LocalDateTime.now(clock))) {
            throw new ConflittoDisponibilitaException("Il nuovo orario deve essere futuro.");
        }
        // Il Repository ripete il controllo temporale dopo aver acquisito i lock.
        return repository.modificaOrarioAtomically(idDisponibilita, idMedico, inizio, fine);
    }

    public void eliminaDisponibilita(Integer idDisponibilita, Integer idMedico) throws SQLException {
        validaId(idDisponibilita, "disponibilità");
        validaId(idMedico, "medico");
        repository.eliminaAtomically(idDisponibilita, idMedico);
    }

    private static void validaId(Integer id, String entita) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("L'ID di " + entita + " deve essere maggiore di zero.");
        }
    }
}
