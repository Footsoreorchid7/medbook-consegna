package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;

public class AppuntamentoAgendaResponse {
    private boolean indisponibilitaSegnalata;
    private boolean refertoPresente;
    private Integer idPrenotazione;
    private Integer idDisponibilita;
    private StatoPrenotazione stato;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idMedicoTitolare;
    private Integer idMedicoEffettivo;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer durataPrestazione;
    private Integer idStanza;
    private String nomeStanza;
    private Integer idPaziente;
    private String nomePaziente;
    private String cognomePaziente;

    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }
    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }

    public boolean isRefertoPresente() { return refertoPresente; }
    public void setRefertoPresente(boolean value) { refertoPresente = value; }

    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer idPrenotazione) { this.idPrenotazione = idPrenotazione; }

    public Integer getIdDisponibilita() { return idDisponibilita; }
    public void setIdDisponibilita(Integer idDisponibilita) { this.idDisponibilita = idDisponibilita; }

    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione stato) { this.stato = stato; }

    public LocalDateTime getDataOraInizio() { return dataOraInizio; }
    public void setDataOraInizio(LocalDateTime dataOraInizio) { this.dataOraInizio = dataOraInizio; }

    public LocalDateTime getDataOraFine() { return dataOraFine; }
    public void setDataOraFine(LocalDateTime dataOraFine) { this.dataOraFine = dataOraFine; }

    public Integer getIdMedicoTitolare() { return idMedicoTitolare; }
    public void setIdMedicoTitolare(Integer idMedicoTitolare) { this.idMedicoTitolare = idMedicoTitolare; }

    public Integer getIdMedicoEffettivo() { return idMedicoEffettivo; }
    public void setIdMedicoEffettivo(Integer idMedicoEffettivo) { this.idMedicoEffettivo = idMedicoEffettivo; }

    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer idPrestazione) { this.idPrestazione = idPrestazione; }

    public String getNomePrestazione() { return nomePrestazione; }
    public void setNomePrestazione(String nomePrestazione) { this.nomePrestazione = nomePrestazione; }

    public Integer getDurataPrestazione() { return durataPrestazione; }
    public void setDurataPrestazione(Integer durataPrestazione) { this.durataPrestazione = durataPrestazione; }

    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer idStanza) { this.idStanza = idStanza; }

    public String getNomeStanza() { return nomeStanza; }
    public void setNomeStanza(String nomeStanza) { this.nomeStanza = nomeStanza; }

    public Integer getIdPaziente() { return idPaziente; }
    public void setIdPaziente(Integer idPaziente) { this.idPaziente = idPaziente; }

    public String getNomePaziente() { return nomePaziente; }
    public void setNomePaziente(String nomePaziente) { this.nomePaziente = nomePaziente; }

    public String getCognomePaziente() { return cognomePaziente; }
    public void setCognomePaziente(String cognomePaziente) { this.cognomePaziente = cognomePaziente; }
}
