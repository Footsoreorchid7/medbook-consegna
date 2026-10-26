package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;

// ID dichiarato solo per sviluppo/test: non autentica il chiamante.
public class AnnullamentoPazienteRequest {
    private Integer idPaziente;
    private StatoPrenotazione statoAtteso;

    public Integer getIdPaziente() {
        return idPaziente;
    }

    public void setIdPaziente(Integer idPaziente) {
        this.idPaziente = idPaziente;
    }

    public StatoPrenotazione getStatoAtteso() {
        return statoAtteso;
    }

    public void setStatoAtteso(StatoPrenotazione statoAtteso) {
        this.statoAtteso = statoAtteso;
    }
}
