package it.medbook.security;

import org.junit.jupiter.api.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private static final PasswordHasher HASHER = new PasswordHasher();
    private static String encoded;
    private static final char[] PASSWORD = "Password esclusivamente sintetica à!".toCharArray();
    @BeforeAll static void prepare() { encoded = HASHER.hash(PASSWORD); }
    @Test void correctPasswordIsVerified() { assertTrue(HASHER.verify(PASSWORD, encoded)); }
    @Test void wrongPasswordIsRejected() { assertFalse(HASHER.verify("Altra password".toCharArray(), encoded)); }
    @Test void saltIsRandomAndFormatFitsDatabase() {
        String second = HASHER.hash(PASSWORD);
        assertNotEquals(encoded.split("\\$")[3], second.split("\\$")[3]);
        assertNotEquals(encoded, second);
        assertTrue(second.length() <= 255);
        assertTrue(second.startsWith("pbkdf2-sha256$v1$600000$"));
        assertTrue(HASHER.verify(PASSWORD, second));
    }
    @Test void unknownPlaintextAndFakeHashesAreRejected() {
        for (String value : new String[]{null, "", "hash-fittizio", new String(PASSWORD), "$2a$unknown"}) {
            assertFalse(HASHER.isSupported(value)); assertFalse(HASHER.verify(PASSWORD, value));
        }
    }
    @Test void malformedVersionsParametersAndBase64AreRejectedWithoutUnboundedKdf() {
        for (String value : new String[]{encoded.replace("v1", "v2"), encoded.replace("600000", "999999999"),
                encoded + "$extra", encoded + "=", encoded.replace("$v1$", "$v1$$"),
                "pbkdf2-sha256$v1$600000$!$!", "x".repeat(256),
                "pbkdf2-sha256$v1$600000$AA$AA"}) {
            assertFalse(HASHER.isSupported(value)); assertFalse(HASHER.verify(PASSWORD, value));
        }
    }
    @Test void invalidPasswordsAreRejected() {
        for (char[] value : new char[][]{null, new char[0], new char[1025]}) {
            assertThrows(IllegalArgumentException.class, () -> HASHER.hash(value));
            assertFalse(HASHER.verify(value, encoded));
        }
    }
    @Test void verificationDoesNotModifyCallerPassword() {
        char[] copy = Arrays.copyOf(PASSWORD, PASSWORD.length);
        HASHER.verify(copy, encoded); assertArrayEquals(PASSWORD, copy);
    }
    @Test void whitespaceIsPartOfPassword() {
        String hash = HASHER.hash(" password ".toCharArray());
        assertTrue(HASHER.verify(" password ".toCharArray(), hash));
        assertFalse(HASHER.verify("password".toCharArray(), hash));
    }
}
