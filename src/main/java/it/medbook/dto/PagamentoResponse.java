package it.medbook.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import it.medbook.model.ModalitaPagamento;
import it.medbook.model.StatoPagamento;

public class PagamentoResponse {
    private Integer idPagamento;
    private Integer idPrenotazione;
    private ModalitaPagamento modalita;
    private BigDecimal importo;
    private StatoPagamento stato;
    private Integer idOperatore;
    private LocalDateTime creatoIl;
    private LocalDateTime pagatoIl;
    public Integer getIdPagamento() { return idPagamento; }
    public void setIdPagamento(Integer value) { idPagamento = value; }
    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer value) { idPrenotazione = value; }
    public ModalitaPagamento getModalita() { return modalita; }
    public void setModalita(ModalitaPagamento value) { modalita = value; }
    public BigDecimal getImporto() { return importo; }
    public void setImporto(BigDecimal value) { importo = value; }
    public StatoPagamento getStato() { return stato; }
    public void setStato(StatoPagamento value) { stato = value; }
    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }
    public LocalDateTime getCreatoIl() { return creatoIl; }
    public void setCreatoIl(LocalDateTime value) { creatoIl = value; }
    public LocalDateTime getPagatoIl() { return pagatoIl; }
    public void setPagatoIl(LocalDateTime value) { pagatoIl = value; }
}
