package it.medbook.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Pagamento {
    private Integer idPagamento;
    private Prenotazione prenotazione;
    private Utente registratoDa;
    private ModalitaPagamento modalita;
    private BigDecimal importo;
    private StatoPagamento stato;
    private String providerPaymentId;
    private LocalDateTime creatoIl;
    private LocalDateTime pagatoIl;

    public Integer getIdPagamento() {
        return idPagamento;
    }

    public void setIdPagamento(Integer idPagamento) {
        this.idPagamento = idPagamento;
    }

    public Prenotazione getPrenotazione() {
        return prenotazione;
    }

    public void setPrenotazione(Prenotazione prenotazione) {
        this.prenotazione = prenotazione;
    }

    public Utente getRegistratoDa() {
        return registratoDa;
    }

    public void setRegistratoDa(Utente registratoDa) {
        this.registratoDa = registratoDa;
    }

    public ModalitaPagamento getModalita() {
        return modalita;
    }

    public void setModalita(ModalitaPagamento modalita) {
        this.modalita = modalita;
    }

    public BigDecimal getImporto() {
        return importo;
    }

    public void setImporto(BigDecimal importo) {
        this.importo = importo;
    }

    public StatoPagamento getStato() {
        return stato;
    }

    public void setStato(StatoPagamento stato) {
        this.stato = stato;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public void setProviderPaymentId(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
    }

    public LocalDateTime getCreatoIl() {
        return creatoIl;
    }

    public void setCreatoIl(LocalDateTime creatoIl) {
        this.creatoIl = creatoIl;
    }

    public LocalDateTime getPagatoIl() {
        return pagatoIl;
    }

    public void setPagatoIl(LocalDateTime pagatoIl) {
        this.pagatoIl = pagatoIl;
    }

    public void segnaPagato() {
        if (stato != StatoPagamento.IN_ATTESA) {
            throw new IllegalStateException("Impossibile eseguire segnaPagato: stato attuale " + stato
                    + "; stati consentiti: IN_ATTESA.");
        }
        stato = StatoPagamento.PAGATO;
        pagatoIl = LocalDateTime.now();
    }

    public void segnaFallito() {
        if (stato != StatoPagamento.IN_ATTESA) {
            throw new IllegalStateException("Impossibile eseguire segnaFallito: stato attuale " + stato
                    + "; stati consentiti: IN_ATTESA.");
        }
        stato = StatoPagamento.FALLITO;
    }
}
