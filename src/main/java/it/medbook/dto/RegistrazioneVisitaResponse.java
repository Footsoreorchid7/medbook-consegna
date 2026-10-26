package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;

public class RegistrazioneVisitaResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;

    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer idPrenotazione) { this.idPrenotazione = idPrenotazione; }
    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione stato) { this.stato = stato; }
}
