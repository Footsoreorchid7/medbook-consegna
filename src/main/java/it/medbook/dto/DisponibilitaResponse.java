package it.medbook.dto;

import java.time.LocalDateTime;

public class DisponibilitaResponse {
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idMedico;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer idStanza;
    private String nomeStanza;
    private boolean configurata;

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

    public boolean isConfigurata() {
        return configurata;
    }

    public void setConfigurata(boolean configurata) {
        this.configurata = configurata;
    }
}
