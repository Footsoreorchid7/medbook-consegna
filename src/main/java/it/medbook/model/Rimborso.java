package it.medbook.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Rimborso {
    private Integer idRimborso;
    private Pagamento pagamento;
    private Utente verificatoDa;
    private StatoRimborso stato;
    private BigDecimal importo;
    private LocalDateTime richiestoIl;
    private LocalDateTime verificatoIl;
    private String motivazione;
    private String providerRefundId;
    private LocalDateTime rimborsatoIl;

    public Integer getIdRimborso() {
        return idRimborso;
    }

    public void setIdRimborso(Integer idRimborso) {
        this.idRimborso = idRimborso;
    }

    public Pagamento getPagamento() {
        return pagamento;
    }

    public void setPagamento(Pagamento pagamento) {
        this.pagamento = pagamento;
    }

    public Utente getVerificatoDa() {
        return verificatoDa;
    }

    public void setVerificatoDa(Utente verificatoDa) {
        this.verificatoDa = verificatoDa;
    }

    public StatoRimborso getStato() {
        return stato;
    }

    public void setStato(StatoRimborso stato) {
        this.stato = stato;
    }

    public BigDecimal getImporto() {
        return importo;
    }

    public void setImporto(BigDecimal importo) {
        this.importo = importo;
    }

    public LocalDateTime getRichiestoIl() {
        return richiestoIl;
    }

    public void setRichiestoIl(LocalDateTime richiestoIl) {
        this.richiestoIl = richiestoIl;
    }

    public LocalDateTime getVerificatoIl() {
        return verificatoIl;
    }

    public void setVerificatoIl(LocalDateTime verificatoIl) {
        this.verificatoIl = verificatoIl;
    }

    public String getMotivazione() {
        return motivazione;
    }

    public void setMotivazione(String motivazione) {
        this.motivazione = motivazione;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public void setProviderRefundId(String providerRefundId) {
        this.providerRefundId = providerRefundId;
    }

    public LocalDateTime getRimborsatoIl() {
        return rimborsatoIl;
    }

    public void setRimborsatoIl(LocalDateTime rimborsatoIl) {
        this.rimborsatoIl = rimborsatoIl;
    }

    public void approva() {
        if (stato != StatoRimborso.RICHIESTO) {
            throw new IllegalStateException("Impossibile eseguire approva: stato attuale " + stato
                    + "; stati consentiti: RICHIESTO.");
        }
        stato = StatoRimborso.APPROVATO;
        verificatoIl = LocalDateTime.now();
    }

    public void respingi() {
        if (stato != StatoRimborso.RICHIESTO) {
            throw new IllegalStateException("Impossibile eseguire respingi: stato attuale " + stato
                    + "; stati consentiti: RICHIESTO.");
        }
        stato = StatoRimborso.RESPINTO;
        verificatoIl = LocalDateTime.now();
    }

    public void avviaElaborazione() {
        if (stato != StatoRimborso.APPROVATO) {
            throw new IllegalStateException("Impossibile eseguire avviaElaborazione: stato attuale " + stato
                    + "; stati consentiti: APPROVATO.");
        }
        stato = StatoRimborso.IN_ELABORAZIONE;
    }

    public void segnaRimborsato() {
        if (stato != StatoRimborso.IN_ELABORAZIONE) {
            throw new IllegalStateException("Impossibile eseguire segnaRimborsato: stato attuale " + stato
                    + "; stati consentiti: IN_ELABORAZIONE.");
        }
        stato = StatoRimborso.RIMBORSATO;
        rimborsatoIl = LocalDateTime.now();
    }

    public void segnaFallito() {
        if (stato != StatoRimborso.IN_ELABORAZIONE) {
            throw new IllegalStateException("Impossibile eseguire segnaFallito: stato attuale " + stato
                    + "; stati consentiti: IN_ELABORAZIONE.");
        }
        stato = StatoRimborso.FALLITO;
    }
}
