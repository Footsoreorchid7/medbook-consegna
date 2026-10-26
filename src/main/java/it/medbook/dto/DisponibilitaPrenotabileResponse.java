package it.medbook.dto;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public class DisponibilitaPrenotabileResponse {
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idMedico;
    private String nomeMedico;
    private String cognomeMedico;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer durataPrestazione;
    private BigDecimal prezzoPrestazione;

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

    public BigDecimal getPrezzoPrestazione() {
        return prezzoPrestazione;
    }

    public void setPrezzoPrestazione(BigDecimal prezzoPrestazione) {
        this.prezzoPrestazione = prezzoPrestazione;
    }
}
