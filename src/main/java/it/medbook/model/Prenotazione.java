package it.medbook.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Prenotazione {
    private Integer idPrenotazione;
    private Paziente paziente;
    private Disponibilita disponibilita;
    private Medico medicoEffettivo;
    private Utente gestitaDa;
    private StatoPrenotazione stato;
    private LocalDateTime dataOraRichiesta;
    private LocalDateTime dataOraGestione;
    private BigDecimal prezzo;
    private boolean indisponibilitaSegnalata;

    public Integer getIdPrenotazione() {
        return idPrenotazione;
    }

    public void setIdPrenotazione(Integer idPrenotazione) {
        this.idPrenotazione = idPrenotazione;
    }

    public Paziente getPaziente() {
        return paziente;
    }

    public void setPaziente(Paziente paziente) {
        this.paziente = paziente;
    }

    public Disponibilita getDisponibilita() {
        return disponibilita;
    }

    public void setDisponibilita(Disponibilita disponibilita) {
        this.disponibilita = disponibilita;
    }

    public Medico getMedicoEffettivo() {
        return medicoEffettivo;
    }

    public void setMedicoEffettivo(Medico medicoEffettivo) {
        this.medicoEffettivo = medicoEffettivo;
    }

    public Utente getGestitaDa() {
        return gestitaDa;
    }

    public void setGestitaDa(Utente gestitaDa) {
        this.gestitaDa = gestitaDa;
    }

    public StatoPrenotazione getStato() {
        return stato;
    }

    public void setStato(StatoPrenotazione stato) {
        this.stato = stato;
    }

    public LocalDateTime getDataOraRichiesta() {
        return dataOraRichiesta;
    }

    public void setDataOraRichiesta(LocalDateTime dataOraRichiesta) {
        this.dataOraRichiesta = dataOraRichiesta;
    }

    public LocalDateTime getDataOraGestione() {
        return dataOraGestione;
    }

    public void setDataOraGestione(LocalDateTime dataOraGestione) {
        this.dataOraGestione = dataOraGestione;
    }

    public BigDecimal getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(BigDecimal prezzo) {
        this.prezzo = prezzo;
    }

    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }

    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }

    public void conferma() {
        if (indisponibilitaSegnalata) {
            throw new IllegalStateException("Indisponibilità ancora da gestire prima della conferma.");
        }
        if (stato != StatoPrenotazione.RICHIESTA) {
            throw new IllegalStateException("Impossibile eseguire conferma: stato attuale " + stato
                    + "; stati consentiti: RICHIESTA.");
        }
        stato = StatoPrenotazione.CONFERMATA;
    }

    public void rifiuta() {
        if (stato != StatoPrenotazione.RICHIESTA) {
            throw new IllegalStateException("Impossibile eseguire rifiuta: stato attuale " + stato
                    + "; stati consentiti: RICHIESTA.");
        }
        stato = StatoPrenotazione.RIFIUTATA;
        indisponibilitaSegnalata = false;
    }

    public void annulla() {
        if (stato != StatoPrenotazione.RICHIESTA && stato != StatoPrenotazione.CONFERMATA) {
            throw new IllegalStateException("Impossibile eseguire annulla: stato attuale " + stato
                    + "; stati consentiti: RICHIESTA, CONFERMATA.");
        }
        stato = StatoPrenotazione.ANNULLATA;
        indisponibilitaSegnalata = false;
    }

    public void registraVisita() {
        if (indisponibilitaSegnalata) {
            throw new IllegalStateException("Impossibile registrare la visita: indisponibilità ancora da gestire.");
        }
        if (stato != StatoPrenotazione.CONFERMATA) {
            throw new IllegalStateException("Impossibile eseguire registraVisita: stato attuale " + stato
                    + "; stati consentiti: CONFERMATA.");
        }
        stato = StatoPrenotazione.EFFETTUATA;
    }

    public void registraAssenzaPaziente() {
        if (indisponibilitaSegnalata) {
            throw new IllegalStateException("Impossibile registrare l’esito: indisponibilità ancora da gestire.");
        }
        if (stato != StatoPrenotazione.CONFERMATA) {
            throw new IllegalStateException("Impossibile eseguire registraAssenzaPaziente: stato attuale " + stato
                    + "; stati consentiti: CONFERMATA.");
        }
        stato = StatoPrenotazione.PAZIENTE_ASSENTE;
    }

    public void registraMancataErogazione() {
        if (indisponibilitaSegnalata) {
            throw new IllegalStateException("Impossibile registrare l’esito: indisponibilità ancora da gestire.");
        }
        if (stato != StatoPrenotazione.CONFERMATA) {
            throw new IllegalStateException("Impossibile eseguire registraMancataErogazione: stato attuale " + stato
                    + "; stati consentiti: CONFERMATA.");
        }
        stato = StatoPrenotazione.NON_EROGATA;
    }
    /** Accertamento amministrativo UC-15; tempo, ruolo e pagamenti sono verificati dal Repository. */
    public void registraMancataErogazioneAmministrativa() {
        if (stato != StatoPrenotazione.CONFERMATA) {
            throw new IllegalStateException("Accertamento consentito solo per una prenotazione CONFERMATA.");
        }
        stato = StatoPrenotazione.NON_EROGATA;
        indisponibilitaSegnalata = false;
    }

}
