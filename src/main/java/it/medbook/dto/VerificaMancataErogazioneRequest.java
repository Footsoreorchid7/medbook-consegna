package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;

public class VerificaMancataErogazioneRequest {
    private Integer idOperatore;
    private StatoPrenotazione statoAtteso;
    private StatoPrenotazione esito;
    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }
    public StatoPrenotazione getStatoAtteso() { return statoAtteso; }
    public void setStatoAtteso(StatoPrenotazione value) { statoAtteso = value; }
    public StatoPrenotazione getEsito() { return esito; }
    public void setEsito(StatoPrenotazione value) { esito = value; }
}
