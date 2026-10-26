package it.medbook.payment;

import it.medbook.model.StatoPagamento;
import java.math.BigDecimal;

/** Esito immutabile scelto dal server, mai dal payload del paziente. */
public final class GatewayPagamentoSimulato implements GatewayPagamento {
    private final StatoPagamento esito;

    public GatewayPagamentoSimulato() { this(StatoPagamento.PAGATO); }
    public GatewayPagamentoSimulato(StatoPagamento esito) {
        if (esito != StatoPagamento.PAGATO && esito != StatoPagamento.FALLITO) {
            throw new IllegalArgumentException("Il simulatore richiede un esito terminale.");
        }
        this.esito = esito;
    }
    public static GatewayPagamentoSimulato fromEnvironment() {
        String value = System.getenv("MEDBOOK_SIMULATED_PAYMENT_OUTCOME");
        return new GatewayPagamentoSimulato(value == null ? StatoPagamento.PAGATO : StatoPagamento.valueOf(value));
    }
    @Override public StatoPagamento paga(String providerPaymentId, BigDecimal importo) {
        return esito;
    }
}
