package it.medbook.security;

import it.medbook.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    private final MutableClock clock = new MutableClock();
    private final SessionStore store = new SessionStore(clock, 100);
    @Test void tokensAreIndependentRandom256BitValues() {
        var first = store.create(1, 0L); var second = store.create(1, 0L);
        assertEquals(32, Base64.getUrlDecoder().decode(first.id()).length);
        assertEquals(32, Base64.getUrlDecoder().decode(first.csrfToken()).length);
        assertNotEquals(first.id(), second.id()); assertNotEquals(first.id(), first.csrfToken());
        assertNotEquals(first.csrfToken(), second.csrfToken());
        assertEquals(1, store.find(first.id()).orElseThrow().idUtente());
    }
    @Test void idleExpiresAtThirtyMinutes() {
        var session = store.create(1, 0L); clock.advance(Duration.ofMinutes(30));
        assertTrue(store.find(session.id()).isEmpty()); assertEquals(0, store.size());
    }
    @Test void validActivityExtendsIdleTimeout() {
        var session = store.create(1, 0L); clock.advance(Duration.ofMinutes(29)); assertTrue(store.touch(session));
        clock.advance(Duration.ofMinutes(29)); assertTrue(store.find(session.id()).isPresent());
        clock.advance(Duration.ofMinutes(1)); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void lookupWithoutSuccessfulAuthenticationDoesNotRefreshSession() {
        var session = store.create(1, 0L); clock.advance(Duration.ofMinutes(29)); store.find(session.id());
        clock.advance(Duration.ofMinutes(1)); assertFalse(store.touch(session));
    }
    @Test void absoluteExpiryCannotBeExtendedByActivity() {
        var session = store.create(1, 0L);
        for (int i=0; i<16; i++) { clock.advance(Duration.ofMinutes(29)); assertTrue(store.touch(session)); }
        clock.advance(Duration.ofMinutes(16)); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void revocationIsImmediateAndTouchCannotResurrectIt() {
        var session = store.create(1, 0L); store.revoke(session.id());
        assertTrue(store.find(session.id()).isEmpty()); assertFalse(store.touch(session));
    }
    @Test void restartingStoreInvalidatesSessions() {
        var session = store.create(1, 0L); assertTrue(new SessionStore(clock,100).find(session.id()).isEmpty());
    }
    @Test void capacityDoesNotEvictActiveSessionsAndExpiredEntriesAreCleaned() {
        var bounded = new SessionStore(clock,1); var first = bounded.create(1, 0L);
        assertThrows(SessionStore.CapacityException.class, () -> bounded.create(2, 0L));
        assertTrue(bounded.find(first.id()).isPresent());
        clock.advance(Duration.ofMinutes(30)); assertNotNull(bounded.create(2, 0L)); assertEquals(1,bounded.size());
    }
    @Test void rejectsInvalidConfigurationAndUsers() {
        assertThrows(IllegalArgumentException.class, () -> new SessionStore(clock,0));
        assertThrows(IllegalArgumentException.class, () -> store.create(0, 0L));
        assertThrows(IllegalArgumentException.class, () -> store.create(-1, 0L));
    }
    @Test void concurrentCapacityAndIdentifiersRemainConsistent() throws Exception {
        var bounded = new SessionStore(clock,10);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i=0;i<100;i++) { jobs.add(() -> {
                try { return bounded.create(1, 0L).id(); } catch (SessionStore.CapacityException e) { return null; }
            }); }
            Set<String> ids = new HashSet<>(); int rejected=0;
            for (var result : executor.invokeAll(jobs)) {
                String id = result.get(); if (id == null) { rejected++; } else { assertTrue(ids.add(id)); }
            }
            assertEquals(10,ids.size()); assertEquals(90,rejected); assertEquals(10,bounded.size());
        }
    }
    @Test void concurrentRevocationCannotRestoreSession() throws Exception {
        var session = store.create(1, 0L);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = List.<Callable<Void>>of(() -> { store.revoke(session.id()); return null; },
                    () -> { for(int i=0;i<100;i++) { store.touch(session); } return null; });
            for (var result : executor.invokeAll(tasks)) { result.get(); }
        }
        assertTrue(store.find(session.id()).isEmpty()); assertFalse(store.touch(session));
    }
}
