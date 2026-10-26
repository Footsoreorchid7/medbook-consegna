package it.medbook.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Formato: pbkdf2-sha256$v1$600000$saltBase64Url$hashBase64Url. */
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();

    public String hash(char[] password) {
        if (password == null || password.length == 0 || password.length > 1024) {
            throw new IllegalArgumentException("Password non valida.");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt);
        try {
            return "pbkdf2-sha256$v1$" + ITERATIONS + "$" + encode(salt) + "$" + encode(derived);
        } finally { Arrays.fill(derived, (byte) 0); }
    }

    /** Accetta soltanto il formato canonico e i parametri approvati, senza eseguire la KDF. */
    public boolean isSupported(String encoded) {
        return decode(encoded) != null;
    }

    public boolean verify(char[] password, String encoded) {
        if (password == null || password.length == 0 || password.length > 1024 || encoded == null
                || encoded.length() > 255) { return false; }
        byte[][] fields = decode(encoded);
        if (fields == null) { return false; }
        byte[] actual = derive(password, fields[0]);
        try { return MessageDigest.isEqual(fields[1], actual); }
        finally { Arrays.fill(actual, (byte) 0); }
    }

    private byte[][] decode(String encoded) {
        if (encoded == null || encoded.length() > 255) { return null; }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 5 || !parts[0].equals("pbkdf2-sha256") || !parts[1].equals("v1")
                    || !parts[2].equals(Integer.toString(ITERATIONS))) { return null; }
            byte[] salt = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[4]);
            if (salt.length != 16 || expected.length != 32 || !encode(salt).equals(parts[3])
                    || !encode(expected).equals(parts[4])) { return null; }
            return new byte[][] {salt, expected};
        } catch (IllegalArgumentException e) { return null; }
    }

    private byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hashing password non disponibile.", e);
        } finally { specification.clearPassword(); }
    }
    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
