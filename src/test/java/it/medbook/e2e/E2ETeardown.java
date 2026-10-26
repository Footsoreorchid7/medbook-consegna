package it.medbook.e2e;

import java.util.Arrays;

/*
 * Pulizia ordinata: ogni passo viene tentato una volta anche dopo eccezioni o Error. Il primo
 * Throwable viene rilanciato con tipo originale dopo tutti i tentativi; successivi soppressi in
 * ordine, senza duplicati o autosoppressione. Cattura Throwable solo qui per non interrompere la
 * pulizia a metà.
 */
final class E2ETeardown {
    @FunctionalInterface interface Passo { void esegui() throws Exception; }

    private E2ETeardown() { }

    static void esegui(Passo... passi) throws Exception {
        Throwable primo = null;
        for (Passo p : passi) {
            try { p.esegui(); }
            catch (Throwable t) {
                if (primo == null) { primo = t; }
                else if (t != primo && Arrays.stream(primo.getSuppressed()).noneMatch(s -> s == t)) { primo.addSuppressed(t); }
            }
        }
        if (primo instanceof Error e) { throw e; }
        if (primo instanceof Exception e) { throw e; }
        if (primo != null) {
            /*
             * Caso di Throwable non classificabile come Exception o Error, non esprimibile
             * normalmente dall'interfaccia Passo.
             */
            throw new IllegalStateException("Throwable non standard nel teardown.", primo);
        }
    }
}
