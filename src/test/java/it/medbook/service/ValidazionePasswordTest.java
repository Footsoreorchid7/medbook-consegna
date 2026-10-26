package it.medbook.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* Politiche password: pazienti 8–1024, account interni 12–1024 caratteri Java. */
class ValidazionePasswordTest {
    static boolean accetta(Runnable r) { try { r.run(); return true; } catch (IllegalArgumentException e) { return false; } }

    static List<char[]> casi() {
        List<char[]> casi = new ArrayList<>(); casi.add(null);
        for (int n : new int[]{0, 1, 2, 11, 12, 13, 1023, 1024, 1025, 2048}) { char[] c = new char[n]; Arrays.fill(c, 'x'); casi.add(c); }
        casi.add("\uD800".toCharArray()); casi.add(("abcdefghijk" + "\uD800").toCharArray()); casi.add(("abcdefghijk" + "\uDC00").toCharArray());
        casi.add(("abcdefghij" + "😀").toCharArray()); casi.add("  spazi  iniziali  ".toCharArray()); casi.add("\u0000\u0001\u0002".toCharArray());
        return casi;
    }

    /* Password paziente: 8–1024 caratteri Java, surrogate isolate rifiutate. */
    @Test void patientPolicyBoundariesAndUnicode() {
        Map<String, Boolean> casi = new LinkedHashMap<>();
        casi.put("x".repeat(7), false); casi.put("x".repeat(8), true); casi.put("x".repeat(1024), true); casi.put("x".repeat(1025), false);
        casi.put("", false); casi.put("        ", true); // 8 spaces: no trim, no composition rules
        casi.put("abcdef\uD83D\uDE00", true);   // 6 + one pair = 8 Java chars
        casi.put("abcde\uD83D\uDE00", false);   // 5 + one pair = 7 Java chars
        casi.put("abcdefg\uD800", false); casi.put("\uDC00abcdefg", false); casi.put("abcdefgh\uD800", false);
        casi.put("abc\uDE00\uD83Ddefg", false);  // reversed pair: two isolated surrogates
        casi.put("\u00e0\u00e8\u00ec\u00f2\u00f9\u00e9\u00c0\u00c8", true); // 8 BMP accented chars
        casi.put("x".repeat(1022) + "\uD83D\uDE00", true); casi.put("x".repeat(1023) + "\uD83D\uDE00", false);
        casi.forEach((p, atteso) -> assertEquals(atteso, accetta(() -> ValidazioneAccount.passwordPaziente(p.toCharArray())),
                p.length() + " char: " + p.codePoints().limit(12).mapToObj(Integer::toHexString).toList()));
        assertFalse(accetta(() -> ValidazioneAccount.passwordPaziente(null)));
        for (String rifiutata : List.of("x".repeat(7), "abcdefg\uD800")) {
            var e = assertThrows(IllegalArgumentException.class, () -> ValidazioneAccount.passwordPaziente(rifiutata.toCharArray()));
            assertEquals("Password non valida.", e.getMessage(), "stesso messaggio generico per lunghezza e Unicode");
        }
    }

    @Test void charArrayInternalRuleIsEquivalentToTheStringRule() {
        for (char[] p : casi()) {
            boolean stringa = p != null && accetta(() -> ValidazioneAccount.password(new String(p)));
            assertEquals(p != null && stringa, accetta(() -> ValidazioneAccount.password(p)), p == null ? "null" : new String(p).length() + " char");
        }
        assertFalse(accetta(() -> ValidazioneAccount.password((char[]) null)));
    }

    /*
     * PBKDF2 può codificare una surrogate isolata come punto interrogativo, producendo collisioni.
     * Le nuove password sono quindi validate prima dell'hashing; la verifica delle password
     * correnti resta invariata.
     */
    @Test void isolatedSurrogatesNeverReachHashing() {
        var hasher = new it.medbook.security.PasswordHasher();
        assertTrue(hasher.verify("?".toCharArray(), hasher.hash("\uD800".toCharArray())), "collisione documentata");
        assertTrue(hasher.verify("abcdefg?".toCharArray(), hasher.hash("abcdefg\uD800".toCharArray())));
        for (String p : List.of("abcdefg\uD800", "\uDC00abcdefg", "abcdefgh\uDBFF", "abc\uDE00\uD83Ddefg")) {
            assertFalse(accetta(() -> ValidazioneAccount.passwordPaziente(p.toCharArray())), "paziente " + p.length());
            assertFalse(accetta(() -> ValidazioneAccount.password((p + "1234").toCharArray())), "interno char[]");
            assertFalse(accetta(() -> ValidazioneAccount.password(p + "1234")), "interno String");
        }
    }
}
