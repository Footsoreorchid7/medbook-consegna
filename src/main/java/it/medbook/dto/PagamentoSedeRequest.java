package it.medbook.dto;

import java.math.BigDecimal;
import it.medbook.model.ModalitaPagamento;

public class PagamentoSedeRequest {
    private Integer idOperatore;
    private ModalitaPagamento modalita;
    private BigDecimal importoAtteso;
    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }
    public ModalitaPagamento getModalita() { return modalita; }
    public void setModalita(ModalitaPagamento value) { modalita = value; }
    public BigDecimal getImportoAtteso() { return importoAtteso; }
    public void setImportoAtteso(BigDecimal value) { importoAtteso = value; }
}
