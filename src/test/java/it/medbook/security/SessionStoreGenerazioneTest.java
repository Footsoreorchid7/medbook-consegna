package it.medbook.security;

import it.medbook.support.MutableClock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/* Sessioni associate alla generazione e revoca delle generazioni numericamente precedenti. */
class SessionStoreGenerazioneTest {
    final SessionStore store = new SessionStore(new MutableClock(), 100);

    @Test void sessionKeepsTheCommittedGenerationAndRejectsOutOfDomainValues() {
        assertEquals(0, store.create(7, 0).generazione());
        assertEquals(Long.MAX_VALUE, store.create(7, Long.MAX_VALUE).generazione());
        assertThrows(IllegalArgumentException.class, () -> store.create(7, -1));
        assertThrows(IllegalArgumentException.class, () -> store.create(7, Long.MIN_VALUE));
        assertEquals(2, store.size());
    }

    @Test void revokeStaleRemovesOnlyStrictlyOlderSessionsOfThatUser() {
        var nove = store.create(7, 9); var dieci = store.create(7, 10); var undici = store.create(7, 11);
        var altroUtente = store.create(8, 1);
        store.revokeStale(7, 10);
        assertTrue(store.find(nove.id()).isEmpty(), "9 < 10: rimossa");
        assertTrue(store.find(dieci.id()).isPresent(), "stessa generazione: conservata");
        assertTrue(store.find(undici.id()).isPresent(), "generazione successiva: conservata");
        assertTrue(store.find(altroUtente.id()).isPresent(), "altro utente: conservata");
    }

    @Test void comparisonIsNumericNearLongMaxValue() {
        var quasiMassimo = store.create(7, Long.MAX_VALUE - 1); var massimo = store.create(7, Long.MAX_VALUE);
        store.revokeStale(7, Long.MAX_VALUE);
        assertTrue(store.find(quasiMassimo.id()).isEmpty()); assertTrue(store.find(massimo.id()).isPresent());
        /* Un chiamante tardivo con generazione inferiore non rimuove la sessione nuova. */
        store.revokeStale(7, 1);
        assertTrue(store.find(massimo.id()).isPresent());
    }
}
