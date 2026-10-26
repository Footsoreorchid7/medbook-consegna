package it.medbook.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/*
 * Skip esplicito per metodo, non abort di BeforeAll con zero test. Il contenitore resta abilitato
 * per fallire su configurazioni errate o obbligatorie; solo assenza facoltativa disabilita i metodi
 * con motivazione.
 */
public final class E2EAbilitazione implements ExecutionCondition {
    @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (context.getTestMethod().isEmpty()) { return ConditionEvaluationResult.enabled("Contenitore E2E"); }
        if (E2EConfigurazione.daAmbiente() instanceof E2EConfigurazione.Assente a && !a.obbligatoria()) {
            return ConditionEvaluationResult.disabled("Ambiente E2E assente (modalità facoltativa): configurare MEDBOOK_E2E_* "
                    + "oppure MEDBOOK_E2E_MODE=obbligatoria per renderlo necessario.");
        }
        return ConditionEvaluationResult.enabled("Ambiente E2E configurato o obbligatorio");
    }
}
