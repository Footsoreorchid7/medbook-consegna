package it.medbook.service;

import it.medbook.dto.SostituzioneMedicoResponse;

import it.medbook.model.Prenotazione;
import it.medbook.dto.SegnalazioneIndisponibilitaResponse;
import it.medbook.dto.SegnalazionePendenteResponse;
import it.medbook.repository.PrenotazioneRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import it.medbook.dto.AppuntamentoPazienteResponse;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import it.medbook.dto.AppuntamentoAgendaResponse;
import java.util.List;
import it.medbook.dto.RichiestaPrenotazioneResponse;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.model.StatoPrenotazione;

import it.medbook.dto.VerificaMancataErogazioneResponse;
import it.medbook.dto.RegistrazioneVisitaResponse;
import it.medbook.dto.PrenotazioneDeskResponse;

public class PrenotazioneService {
    private final PrenotazioneRepository repository;
    private final Clock clock;

    public PrenotazioneService(PrenotazioneRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    public PrenotazioneService(PrenotazioneRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = Objects.requireNonNull(clock, "Clock obbligatorio.");
    }

    // ID dichiarativo confrontato dal confine HTTP con l’identità autenticata del medico.
    public SegnalazioneIndisponibilitaResponse segnalaIndisponibilita(Integer medicoId,
            List<Integer> idPrenotazioni) throws SQLException {
        validaSegnalazione(medicoId, idPrenotazioni);
        return repository.segnalaIndisponibilitaAtomically(medicoId, List.copyOf(idPrenotazioni));
    }

    public List<SegnalazionePendenteResponse> getSegnalazioniPendenti() throws SQLException {
        return repository.findSegnalazioniPendenti();
    }

    public static void validaSegnalazione(Integer medicoId, List<Integer> ids) {
        validaId(medicoId, "medico");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Selezionare almeno una prenotazione.");
        }
        java.util.Set<Integer> unici = new java.util.HashSet<>();
        for (Integer id : ids) {
            validaId(id, "prenotazione");
            if (!unici.add(id)) { throw new IllegalArgumentException("Prenotazioni duplicate."); }
        }
    }

    public Prenotazione richiediAppuntamento(Integer idPaziente, Integer idDisponibilita)
            throws SQLException {
        validaId(idPaziente, "paziente");
        validaId(idDisponibilita, "disponibilità");
        return repository.creaRichiestaAtomically(idPaziente, idDisponibilita);
    }

    public List<RichiestaPrenotazioneResponse> getRichieste(Integer idMedico, Integer idPrestazione,
                                                         LocalDate dataDa, LocalDate dataA)
            throws SQLException {
        if (idMedico != null) { validaId(idMedico, "medico"); }
        if (idPrestazione != null) { validaId(idPrestazione, "prestazione"); }
        if (dataDa != null && dataA != null && dataDa.isAfter(dataA)) {
            throw new IllegalArgumentException("dataDa deve precedere o coincidere con dataA.");
        }
        return repository.findRichieste(idMedico, idPrestazione, dataDa, dataA);
    }

    /** Consultazione desk per stato; TUTTE comprende tutti gli stati, senza cambiare transizioni. */
    public List<RichiestaPrenotazioneResponse> getRichieste(Integer idMedico, Integer idPrestazione,
            LocalDate dataDa, LocalDate dataA, String stato) throws SQLException {
        if (idMedico != null) { validaId(idMedico, "medico"); }
        if (idPrestazione != null) { validaId(idPrestazione, "prestazione"); }
        if (dataDa != null && dataA != null && dataDa.isAfter(dataA)) {
            throw new IllegalArgumentException("dataDa deve precedere o coincidere con dataA.");
        }
        if (stato == null) { throw new IllegalArgumentException("Stato obbligatorio."); }
        StatoPrenotazione filtro = "TUTTE".equals(stato) ? null : StatoPrenotazione.valueOf(stato);
        return repository.findRichieste(idMedico, idPrestazione, dataDa, dataA, filtro);
    }

    public SostituzioneMedicoResponse sostituisciMedico(Integer idPrenotazione,
            Integer idOperatore, Integer idSostituto, StatoPrenotazione statoAtteso, Integer idIndisponibile)
            throws SQLException {
        validaId(idPrenotazione, "prenotazione"); validaId(idOperatore, "operatore");
        validaId(idSostituto, "sostituto"); validaId(idIndisponibile, "medico indisponibile");
        if (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA) {
            throw new IllegalArgumentException("statoAtteso deve essere RICHIESTA o CONFERMATA.");
        }
        return repository.sostituisciMedicoAtomically(idPrenotazione, idOperatore, idSostituto, statoAtteso, idIndisponibile);
    }


    public RegistrazioneVisitaResponse registraVisita(Integer idPrenotazione, Integer idMedico) throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idMedico == null || idMedico <= 0) {
            throw new IllegalArgumentException("Identificativi non validi.");
        }
        return repository.registraVisitaAtomically(idPrenotazione, idMedico);
    }

    public VerificaMancataErogazioneResponse verificaMancataErogazione(Integer idPrenotazione,
            Integer idOperatore, StatoPrenotazione statoAtteso, StatoPrenotazione esito) throws SQLException {
        validaVerificaMancataErogazione(idPrenotazione, idOperatore, statoAtteso, esito);
        return repository.verificaMancataErogazioneAtomically(idPrenotazione, idOperatore, statoAtteso, esito);
    }

    public static void validaVerificaMancataErogazione(Integer idPrenotazione, Integer idOperatore,
            StatoPrenotazione statoAtteso, StatoPrenotazione esito) {
        validaId(idPrenotazione, "prenotazione");
        validaId(idOperatore, "operatore");
        if (statoAtteso != StatoPrenotazione.CONFERMATA
                || (esito != StatoPrenotazione.PAZIENTE_ASSENTE && esito != StatoPrenotazione.NON_EROGATA)) {
            throw new IllegalArgumentException("Stato atteso o esito non valido.");
        }
    }

    public ConfermaPrenotazioneResponse confermaAppuntamento(Integer idPrenotazione, Integer idOperatore)
            throws SQLException {
        validaId(idPrenotazione, "prenotazione");
        validaId(idOperatore, "operatore");
        return repository.confermaAtomically(idPrenotazione, idOperatore);
    }

    public RifiutoPrenotazioneResponse rifiutaAppuntamento(Integer idPrenotazione, Integer idOperatore)
            throws SQLException {
        validaId(idPrenotazione, "prenotazione");
        validaId(idOperatore, "operatore");
        return repository.rifiutaAtomically(idPrenotazione, idOperatore);
    }

    public AnnullamentoPrenotazioneResponse annullaAppuntamento(Integer idPrenotazione, Integer idOperatore,
                                                              StatoPrenotazione statoAtteso) throws SQLException {
        validaId(idPrenotazione, "prenotazione");
        validaId(idOperatore, "operatore");
        if (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA) {
            throw new IllegalArgumentException("statoAtteso deve essere RICHIESTA o CONFERMATA.");
        }
        return repository.annullaAtomically(idPrenotazione, idOperatore, statoAtteso);
    }

    public AnnullamentoPazienteResponse annullaAppuntamentoPaziente(Integer idPrenotazione, Integer idPaziente,
                                                              StatoPrenotazione statoAtteso) throws SQLException {
        validaId(idPrenotazione, "prenotazione");
        validaId(idPaziente, "paziente");
        if (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA) {
            throw new IllegalArgumentException("statoAtteso deve essere RICHIESTA o CONFERMATA.");
        }
        return repository.annullaPazienteAtomically(idPrenotazione, idPaziente, statoAtteso);
    }

    public List<AppuntamentoAgendaResponse> getAgenda(Integer idMedico, LocalDate data,
                                                    LocalDate dataDa, LocalDate dataA) throws SQLException {
        validaId(idMedico, "medico");
        if (data != null && (dataDa != null || dataA != null)) {
            throw new IllegalArgumentException("data non è compatibile con dataDa/dataA.");
        }
        if ((dataDa == null) != (dataA == null)) {
            throw new IllegalArgumentException("dataDa e dataA devono essere presenti insieme.");
        }
        if (data != null) { dataDa = data; dataA = data; }
        else if (dataDa == null) { dataDa = LocalDate.now(clock); dataA = dataDa; }
        if (dataDa.isAfter(dataA) || ChronoUnit.DAYS.between(dataDa, dataA) >= 31) {
            throw new IllegalArgumentException("Il periodo deve essere ordinato e contenere al massimo 31 giorni.");
        }
        // Il limite esclusivo deve essere rappresentabile da MySQL DATETIME.
        if (dataDa.getYear() < 1000 || dataA.getYear() > 9999
                || dataA.equals(LocalDate.of(9999, 12, 31))) {
            throw new IllegalArgumentException("Periodo non rappresentabile da MySQL DATETIME.");
        }
        return repository.findAgenda(idMedico, dataDa, dataA);
    }

    /**
     * periodo obbligatorio (data oppure dataDa+dataA), al massimo 31 giorni inclusi come UC-18.
     * medicoId facoltativo filtra il medico effettivo.
     */
    public List<PrenotazioneDeskResponse> getPrenotazioniDesk(Integer idMedico, LocalDate data,
            LocalDate dataDa, LocalDate dataA) throws SQLException {
        if (idMedico != null) { validaId(idMedico, "medico"); }
        if (data != null && (dataDa != null || dataA != null)) {
            throw new IllegalArgumentException("data non è compatibile con dataDa/dataA.");
        }
        if ((dataDa == null) != (dataA == null)) {
            throw new IllegalArgumentException("dataDa e dataA devono essere presenti insieme.");
        }
        if (data != null) { dataDa = data; dataA = data; }
        if (dataDa == null) { throw new IllegalArgumentException("Il periodo è obbligatorio."); }
        if (dataDa.isAfter(dataA) || ChronoUnit.DAYS.between(dataDa, dataA) >= 31) {
            throw new IllegalArgumentException("Il periodo deve essere ordinato e contenere al massimo 31 giorni.");
        }
        // Il limite esclusivo deve essere rappresentabile da MySQL DATETIME.
        if (dataDa.getYear() < 1000 || dataA.getYear() > 9999
                || dataA.equals(LocalDate.of(9999, 12, 31))) {
            throw new IllegalArgumentException("Periodo non rappresentabile da MySQL DATETIME.");
        }
        return repository.findPrenotazioniDesk(idMedico, dataDa, dataA);
    }

    public List<AppuntamentoPazienteResponse> getAppuntamentiPaziente(Integer idPaziente,
            StatoPrenotazione stato, LocalDate data, LocalDate dataDa, LocalDate dataA) throws SQLException {
        validaId(idPaziente, "paziente");
        // Un enum non nullo rappresenta uno dei sette stati; null significa nessun filtro.
        if (data != null && (dataDa != null || dataA != null)) {
            throw new IllegalArgumentException("data non è compatibile con dataDa/dataA.");
        }
        if ((dataDa == null) != (dataA == null)) {
            throw new IllegalArgumentException("dataDa e dataA devono essere presenti insieme.");
        }
        if (data != null) { dataDa = data; dataA = data; }
        if (dataDa != null) {
            if (dataDa.isAfter(dataA)) {
                throw new IllegalArgumentException("dataDa deve precedere o coincidere con dataA.");
            }
            // Previene date fuori dal range MySQL e un limite superiore non rappresentabile.
            if (dataDa.getYear() < 1000 || dataA.getYear() > 9999
                    || dataA.equals(LocalDate.of(9999, 12, 31))) {
                throw new IllegalArgumentException("Periodo non rappresentabile da MySQL DATETIME.");
            }
        }
        LocalDateTime adesso = LocalDateTime.now(clock);
        return repository.findByPaziente(idPaziente, stato, dataDa, dataA, adesso);
    }

    private static void validaId(Integer id, String entita) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("L'ID di " + entita + " deve essere maggiore di zero.");
        }
    }
}
