package it.medbook.dto;

import java.time.LocalDateTime;

public class CaricamentoRefertoResponse {
    private Integer idReferto;
    private Integer idPrenotazione;
    private String nomeFile;
    private LocalDateTime caricatoIl;
    public Integer getIdReferto() { return idReferto; }
    public void setIdReferto(Integer value) { idReferto = value; }
    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer value) { idPrenotazione = value; }
    public String getNomeFile() { return nomeFile; }
    public void setNomeFile(String value) { nomeFile = value; }
    public LocalDateTime getCaricatoIl() { return caricatoIl; }
    public void setCaricatoIl(LocalDateTime value) { caricatoIl = value; }
}
