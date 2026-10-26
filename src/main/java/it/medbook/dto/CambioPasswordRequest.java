package it.medbook.dto;

import java.util.Arrays;

/* Input interno: mai serializzare o registrare. Entrambi gli array vengono puliti alla chiusura. */
public final class CambioPasswordRequest implements AutoCloseable {
    private final char[] passwordAttuale;
    private final char[] nuovaPassword;

    public CambioPasswordRequest(char[] passwordAttuale, char[] nuovaPassword) {
        this.passwordAttuale = passwordAttuale;
        this.nuovaPassword = nuovaPassword;
    }
    public char[] getPasswordAttuale() { return passwordAttuale; }
    public char[] getNuovaPassword() { return nuovaPassword; }
    @Override public void close() { Arrays.fill(passwordAttuale, '\0'); Arrays.fill(nuovaPassword, '\0'); }
}
