package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;


// Con autenticazione l’operatore dovrà derivare dall’identità autenticata.
public class AnnullamentoPrenotazioneRequest {
    private Integer idOperatore;
    private StatoPrenotazione statoAtteso;

    public Integer getIdOperatore() {
        return idOperatore;
    }

    public void setIdOperatore(Integer idOperatore) {
        this.idOperatore = idOperatore;
    }
    public StatoPrenotazione getStatoAtteso() {
        return statoAtteso;
    }

    public void setStatoAtteso(StatoPrenotazione statoAtteso) {
        this.statoAtteso = statoAtteso;
    }
}
