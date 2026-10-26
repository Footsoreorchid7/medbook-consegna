package it.medbook.model;

public class Medico {
    private Utente utente;
    private String specializzazione;
    private String numeroIscrizioneOrdine;

    public Utente getUtente() {
        return utente;
    }

    public void setUtente(Utente utente) {
        this.utente = utente;
    }

    public String getSpecializzazione() {
        return specializzazione;
    }

    public void setSpecializzazione(String specializzazione) {
        this.specializzazione = specializzazione;
    }

    public String getNumeroIscrizioneOrdine() {
        return numeroIscrizioneOrdine;
    }

    public void setNumeroIscrizioneOrdine(String numeroIscrizioneOrdine) {
        this.numeroIscrizioneOrdine = numeroIscrizioneOrdine;
    }
}
