package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public class PrenotazioneResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private LocalDateTime dataOraRichiesta;
    private BigDecimal prezzo;
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer idMedico;
    private String nomeMedico;
    private String cognomeMedico;

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

    public BigDecimal getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(BigDecimal prezzo) {
        this.prezzo = prezzo;
    }

    public Integer getIdDisponibilita() {
        return idDisponibilita;
    }

    public void setIdDisponibilita(Integer idDisponibilita) {
        this.idDisponibilita = idDisponibilita;
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
}
