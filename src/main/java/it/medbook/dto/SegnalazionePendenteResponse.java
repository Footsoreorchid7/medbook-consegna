package it.medbook.dto;

import java.time.LocalDateTime;
import it.medbook.model.StatoPrenotazione;

public class SegnalazionePendenteResponse {
    private Integer idPrenotazione;
    private Integer idPaziente;
    private String nomePaziente;
    private String cognomePaziente;
    private StatoPrenotazione stato;
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idMedicoTitolare;
    private String nomeMedicoTitolare;
    private String cognomeMedicoTitolare;
    private Integer idMedicoEffettivo;
    private String nomeMedicoEffettivo;
    private String cognomeMedicoEffettivo;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer idStanza;
    private String nomeStanza;
    private boolean indisponibilitaSegnalata;

    public Integer getIdPaziente() { return idPaziente; }
    public void setIdPaziente(Integer value) { idPaziente = value; }
    public String getNomePaziente() { return nomePaziente; }
    public void setNomePaziente(String value) { nomePaziente = value; }
    public String getCognomePaziente() { return cognomePaziente; }
    public void setCognomePaziente(String value) { cognomePaziente = value; }
    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer value) { idPrenotazione = value; }
    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione value) { stato = value; }
    public Integer getIdDisponibilita() { return idDisponibilita; }
    public void setIdDisponibilita(Integer value) { idDisponibilita = value; }
    public LocalDateTime getDataOraInizio() { return dataOraInizio; }
    public void setDataOraInizio(LocalDateTime value) { dataOraInizio = value; }
    public LocalDateTime getDataOraFine() { return dataOraFine; }
    public void setDataOraFine(LocalDateTime value) { dataOraFine = value; }
    public Integer getIdMedicoTitolare() { return idMedicoTitolare; }
    public void setIdMedicoTitolare(Integer value) { idMedicoTitolare = value; }
    public String getNomeMedicoTitolare() { return nomeMedicoTitolare; }
    public void setNomeMedicoTitolare(String value) { nomeMedicoTitolare = value; }
    public String getCognomeMedicoTitolare() { return cognomeMedicoTitolare; }
    public void setCognomeMedicoTitolare(String value) { cognomeMedicoTitolare = value; }
    public Integer getIdMedicoEffettivo() { return idMedicoEffettivo; }
    public void setIdMedicoEffettivo(Integer value) { idMedicoEffettivo = value; }
    public String getNomeMedicoEffettivo() { return nomeMedicoEffettivo; }
    public void setNomeMedicoEffettivo(String value) { nomeMedicoEffettivo = value; }
    public String getCognomeMedicoEffettivo() { return cognomeMedicoEffettivo; }
    public void setCognomeMedicoEffettivo(String value) { cognomeMedicoEffettivo = value; }
    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer value) { idPrestazione = value; }
    public String getNomePrestazione() { return nomePrestazione; }
    public void setNomePrestazione(String value) { nomePrestazione = value; }
    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer value) { idStanza = value; }
    public String getNomeStanza() { return nomeStanza; }
    public void setNomeStanza(String value) { nomeStanza = value; }
    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }
    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }
}
