package it.medbook.dto;


// Con autenticazione l’operatore dovrà derivare dall’identità autenticata.
public class RifiutoPrenotazioneRequest {
    private Integer idOperatore;

    public Integer getIdOperatore() {
        return idOperatore;
    }

    public void setIdOperatore(Integer idOperatore) {
        this.idOperatore = idOperatore;
    }
}
