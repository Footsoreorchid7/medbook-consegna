package it.medbook.payment;

import it.medbook.model.StatoPagamento;
import java.math.BigDecimal;

/** Solo simulazione interna: nessuna chiamata bancaria, rete o callback. */
@FunctionalInterface
public interface GatewayPagamento {
    StatoPagamento paga(String providerPaymentId, BigDecimal importo);
}
