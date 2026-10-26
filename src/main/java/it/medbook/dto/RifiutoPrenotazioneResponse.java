package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;

public class RifiutoPrenotazioneResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private Integer idOperatore;
    private LocalDateTime dataOraGestione;

    public Integer getIdPrenotazione() {
        return idPrenotazione;
    }

    public void setIdPrenotazione(Integer idPrenotazione) {
        this.idPrenotazione = idPrenotazione;
    }

    public StatoPrenotazione getStato() {
        return stato;
    }

    public void setStato(StatoPrenotazione stato) {
        this.stato = stato;
    }

    public Integer getIdOperatore() {
        return idOperatore;
    }

    public void setIdOperatore(Integer idOperatore) {
        this.idOperatore = idOperatore;
    }

    public LocalDateTime getDataOraGestione() {
        return dataOraGestione;
    }

    public void setDataOraGestione(LocalDateTime dataOraGestione) {
        this.dataOraGestione = dataOraGestione;
    }

}
