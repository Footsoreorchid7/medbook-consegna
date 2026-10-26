package it.medbook.dto;

import java.util.List;

public class SegnalaIndisponibilitaRequest {
    private Integer medicoId;
    private List<Integer> idPrenotazioni;

    public Integer getMedicoId() { return medicoId; }
    public void setMedicoId(Integer value) { medicoId = value; }
    public List<Integer> getIdPrenotazioni() { return idPrenotazioni; }
    public void setIdPrenotazioni(List<Integer> value) { idPrenotazioni = value; }
}
