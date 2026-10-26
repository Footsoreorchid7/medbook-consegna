package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public class RichiestaPrenotazioneResponse {
    private boolean indisponibilitaSegnalata;
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private LocalDateTime dataOraRichiesta;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private BigDecimal prezzo;
    private Integer idPaziente;
    private String nomePaziente;
    private String cognomePaziente;
    private Integer idMedico;
    private String nomeMedico;
    private String cognomeMedico;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer durataPrestazione;
    private Integer idStanza;
    private String nomeStanza;

    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }

    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }

    public Integer getIdPrenotazione() {
        return idPrenotazione;
    }

    public void setIdPrenotazione(Integer idPrenotazione) {
        this.idPrenotazione = idPrenotazione;
    }

    public StatoPrenotazione getStato() {
        return stato;
    }

    public void setStato(StatoPrenotazione stato) {
        this.stato = stato;
    }

    public LocalDateTime getDataOraRichiesta() {
        return dataOraRichiesta;
    }

    public void setDataOraRichiesta(LocalDateTime dataOraRichiesta) {
        this.dataOraRichiesta = dataOraRichiesta;
    }

    public LocalDateTime getDataOraInizio() {
        return dataOraInizio;
    }

    public void setDataOraInizio(LocalDateTime dataOraInizio) {
        this.dataOraInizio = dataOraInizio;
    }

    public LocalDateTime getDataOraFine() {
        return dataOraFine;
    }

    public void setDataOraFine(LocalDateTime dataOraFine) {
        this.dataOraFine = dataOraFine;
    }

    public BigDecimal getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(BigDecimal prezzo) {
        this.prezzo = prezzo;
    }

    public Integer getIdPaziente() {
        return idPaziente;
    }

    public void setIdPaziente(Integer idPaziente) {
        this.idPaziente = idPaziente;
    }

    public String getNomePaziente() {
        return nomePaziente;
    }

    public void setNomePaziente(String nomePaziente) {
        this.nomePaziente = nomePaziente;
    }

    public String getCognomePaziente() {
        return cognomePaziente;
    }

    public void setCognomePaziente(String cognomePaziente) {
        this.cognomePaziente = cognomePaziente;
    }

    public Integer getIdMedico() {
        return idMedico;
    }

    public void setIdMedico(Integer idMedico) {
        this.idMedico = idMedico;
    }

    public String getNomeMedico() {
        return nomeMedico;
    }

    public void setNomeMedico(String nomeMedico) {
        this.nomeMedico = nomeMedico;
    }

    public String getCognomeMedico() {
        return cognomeMedico;
    }

    public void setCognomeMedico(String cognomeMedico) {
        this.cognomeMedico = cognomeMedico;
    }

    public Integer getIdPrestazione() {
        return idPrestazione;
    }

    public void setIdPrestazione(Integer idPrestazione) {
        this.idPrestazione = idPrestazione;
    }

    public String getNomePrestazione() {
        return nomePrestazione;
    }

    public void setNomePrestazione(String nomePrestazione) {
        this.nomePrestazione = nomePrestazione;
    }

    public Integer getDurataPrestazione() {
        return durataPrestazione;
    }

    public void setDurataPrestazione(Integer durataPrestazione) {
        this.durataPrestazione = durataPrestazione;
    }

    public Integer getIdStanza() {
        return idStanza;
    }

    public void setIdStanza(Integer idStanza) {
        this.idStanza = idStanza;
    }

    public String getNomeStanza() {
        return nomeStanza;
    }

    public void setNomeStanza(String nomeStanza) {
        this.nomeStanza = nomeStanza;
    }
}
