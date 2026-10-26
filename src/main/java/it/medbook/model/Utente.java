package it.medbook.model;

public class Utente {
    private Integer idUtente;
    private String nome;
    private String cognome;
    private String email;
    private String passwordHash;
    private RuoloGestionale ruoloGestionale;

    public Integer getIdUtente() {
        return idUtente;
    }

    public void setIdUtente(Integer idUtente) {
        this.idUtente = idUtente;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCognome() {
        return cognome;
    }

    public void setCognome(String cognome) {
        this.cognome = cognome;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public RuoloGestionale getRuoloGestionale() {
        return ruoloGestionale;
    }

    public void setRuoloGestionale(RuoloGestionale ruoloGestionale) {
        this.ruoloGestionale = ruoloGestionale;
    }
}
