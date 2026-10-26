package it.medbook.dto;

public class StanzaResponse {
    private Integer idStanza;
    private String nome;
    private String descrizione;
    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer value) { idStanza = value; }
    public String getNome() { return nome; }
    public void setNome(String value) { nome = value; }
    public String getDescrizione() { return descrizione; }
    public void setDescrizione(String value) { descrizione = value; }
}
