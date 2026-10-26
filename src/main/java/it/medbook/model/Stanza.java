package it.medbook.model;

public class Stanza {
    private Integer idStanza;
    private String nome;
    private String descrizione;

    public Integer getIdStanza() {
        return idStanza;
    }

    public void setIdStanza(Integer idStanza) {
        this.idStanza = idStanza;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }
}
