package it.medbook.dto;

import java.math.BigDecimal;

public class PrestazioneRequest {
    private String nome;
    private String descrizione;
    private Integer durata;
    private BigDecimal prezzo;
    public String getNome() { return nome; }
    public void setNome(String value) { this.nome = value; }
    public String getDescrizione() { return descrizione; }
    public void setDescrizione(String value) { this.descrizione = value; }
    public Integer getDurata() { return durata; }
    public void setDurata(Integer value) { this.durata = value; }
    public BigDecimal getPrezzo() { return prezzo; }
    public void setPrezzo(BigDecimal value) { this.prezzo = value; }
}
