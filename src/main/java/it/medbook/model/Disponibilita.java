package it.medbook.model;

import java.time.LocalDateTime;

public class Disponibilita {
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Medico medico;
    private Prestazione prestazione;
    private Stanza stanza;

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

    public Medico getMedico() {
        return medico;
    }

    public void setMedico(Medico medico) {
        this.medico = medico;
    }

    public Prestazione getPrestazione() {
        return prestazione;
    }

    public void setPrestazione(Prestazione prestazione) {
        this.prestazione = prestazione;
    }

    public Stanza getStanza() {
        return stanza;
    }

    public void setStanza(Stanza stanza) {
        this.stanza = stanza;
    }

    public void configura(Prestazione prestazione, Stanza stanza) {
        this.prestazione = prestazione;
        this.stanza = stanza;
    }

    public boolean isConfigurata() {
        return prestazione != null && stanza != null;
    }
}
