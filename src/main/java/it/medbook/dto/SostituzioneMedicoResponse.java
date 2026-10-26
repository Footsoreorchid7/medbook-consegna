package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;

public class SostituzioneMedicoResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private Integer idMedicoEffettivo;
    private boolean indisponibilitaSegnalata;
    private Integer idOperatore;
    private LocalDateTime dataOraGestione;

    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer value) { idPrenotazione = value; }

    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione value) { stato = value; }

    public Integer getIdMedicoEffettivo() { return idMedicoEffettivo; }
    public void setIdMedicoEffettivo(Integer value) { idMedicoEffettivo = value; }

    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }
    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }

    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }

    public LocalDateTime getDataOraGestione() { return dataOraGestione; }
    public void setDataOraGestione(LocalDateTime value) { dataOraGestione = value; }
}
