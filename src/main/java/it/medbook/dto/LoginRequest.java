package it.medbook.dto;

import java.util.Arrays;

/** Input interno: non serializzare né registrare nei log. */
public final class LoginRequest implements AutoCloseable {
    private final String email;
    private final char[] password;

    public LoginRequest(String email, char[] password) {
        this.email = email;
        this.password = password;
    }
    public String getEmail() { return email; }
    public char[] getPassword() { return password; }
    @Override public void close() { Arrays.fill(password, '\0'); }
}
