package it.medbook.model;

import java.time.LocalDateTime;

public class Referto {
    private Integer idReferto;
    private Prenotazione prenotazione;
    private Medico medicoCaricamento;
    private String nomeFile;
    private String percorsoFile;
    private LocalDateTime caricatoIl;

    public Integer getIdReferto() {
        return idReferto;
    }

    public void setIdReferto(Integer idReferto) {
        this.idReferto = idReferto;
    }

    public Prenotazione getPrenotazione() {
        return prenotazione;
    }

    public void setPrenotazione(Prenotazione prenotazione) {
        this.prenotazione = prenotazione;
    }

    public Medico getMedicoCaricamento() {
        return medicoCaricamento;
    }

    public void setMedicoCaricamento(Medico medicoCaricamento) {
        this.medicoCaricamento = medicoCaricamento;
    }

    public String getNomeFile() {
        return nomeFile;
    }

    public void setNomeFile(String nomeFile) {
        this.nomeFile = nomeFile;
    }

    public String getPercorsoFile() {
        return percorsoFile;
    }

    public void setPercorsoFile(String percorsoFile) {
        this.percorsoFile = percorsoFile;
    }

    public LocalDateTime getCaricatoIl() {
        return caricatoIl;
    }

    public void setCaricatoIl(LocalDateTime caricatoIl) {
        this.caricatoIl = caricatoIl;
    }
}
