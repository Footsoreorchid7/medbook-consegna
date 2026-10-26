package it.medbook.model;

import java.math.BigDecimal;

public class Prestazione {
    private Integer idPrestazione;
    private String nome;
    private String descrizione;
    private Integer durata;
    private BigDecimal prezzo;

    public Integer getIdPrestazione() {
        return idPrestazione;
    }

    public void setIdPrestazione(Integer idPrestazione) {
        this.idPrestazione = idPrestazione;
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

    public Integer getDurata() {
        return durata;
    }

    public void setDurata(Integer durata) {
        this.durata = durata;
    }

    public BigDecimal getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(BigDecimal prezzo) {
        this.prezzo = prezzo;
    }
}
