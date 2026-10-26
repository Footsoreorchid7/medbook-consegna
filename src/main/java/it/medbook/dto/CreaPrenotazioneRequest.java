package it.medbook.dto;


// Con autenticazione idPaziente dovrà derivare dall’utente autenticato.
public class CreaPrenotazioneRequest {
    private Integer idPaziente;
    private Integer idDisponibilita;

    public Integer getIdPaziente() {
        return idPaziente;
    }

    public void setIdPaziente(Integer idPaziente) {
        this.idPaziente = idPaziente;
    }

    public Integer getIdDisponibilita() {
        return idDisponibilita;
    }

    public void setIdDisponibilita(Integer idDisponibilita) {
        this.idDisponibilita = idDisponibilita;
    }
}
