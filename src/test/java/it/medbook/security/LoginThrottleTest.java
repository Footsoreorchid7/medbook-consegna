package it.medbook.security;

import it.medbook.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {
    private final MutableClock clock = new MutableClock();
    @Test void limitsAttemptsPerPeer() {
        var throttle = new LoginThrottle(clock,100,2);
        assertTrue(throttle.allow("peer")); assertTrue(throttle.allow("peer")); assertFalse(throttle.allow("peer"));
    }
    @Test void differentPeersHaveIndependentCounters() {
        var throttle = new LoginThrottle(clock,100,1);
        assertTrue(throttle.allow("a")); assertFalse(throttle.allow("a")); assertTrue(throttle.allow("b"));
    }
    @Test void windowResetsAtFiveMinutes() {
        var throttle = new LoginThrottle(clock,100,1); throttle.allow("a");
        clock.advance(Duration.ofMinutes(5)); assertTrue(throttle.allow("a"));
    }
    @Test void fullMapFailsClosedAndExpiredEntriesAreRemoved() {
        var throttle = new LoginThrottle(clock,1,2); assertTrue(throttle.allow("a")); assertFalse(throttle.allow("b"));
        clock.advance(Duration.ofMinutes(5)); assertTrue(throttle.allow("b"));
    }
    @Test void concurrentAttemptsCannotExceedLimit() throws Exception {
        var throttle = new LoginThrottle(clock,100,10);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for(int i=0;i<100;i++) { jobs.add(() -> throttle.allow("a")); }
            int accepted=0;
            for(var result : executor.invokeAll(jobs)) { if(result.get()) { accepted++; } }
            assertEquals(10,accepted);
        }
    }
    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle(clock,0,1));
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle(clock,1,0));
    }
}
