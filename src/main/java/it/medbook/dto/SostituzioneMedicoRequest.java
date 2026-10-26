package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;

public class SostituzioneMedicoRequest {
    private Integer idOperatore;
    private Integer idMedicoSostituto;
    private StatoPrenotazione statoAtteso;
    private Integer idMedicoIndisponibile;

    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }

    public Integer getIdMedicoSostituto() { return idMedicoSostituto; }
    public void setIdMedicoSostituto(Integer value) { idMedicoSostituto = value; }

    public StatoPrenotazione getStatoAtteso() { return statoAtteso; }
    public void setStatoAtteso(StatoPrenotazione value) { statoAtteso = value; }

    public Integer getIdMedicoIndisponibile() { return idMedicoIndisponibile; }
    public void setIdMedicoIndisponibile(Integer value) { idMedicoIndisponibile = value; }
}
