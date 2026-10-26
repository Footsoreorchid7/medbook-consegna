package it.medbook.dto;

import java.util.List;

public class SegnalazioneIndisponibilitaResponse {
    private List<Integer> idPrenotazioni;
    private boolean indisponibilitaSegnalata;

    public List<Integer> getIdPrenotazioni() { return idPrenotazioni; }
    public void setIdPrenotazioni(List<Integer> value) { idPrenotazioni = value; }
    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }
    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }
}
