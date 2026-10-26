package it.medbook.dto;

import java.time.LocalDate;
import java.util.Arrays;

/** Solo input interno: mai serializzare o registrare nei log. */
public final class RegistrazionePazienteRequest implements AutoCloseable {
    private final String nome, cognome, email, codiceFiscale, telefono;
    private final char[] password;
    private final LocalDate dataNascita;

    public RegistrazionePazienteRequest(String nome, String cognome, String email, char[] password,
                                       String codiceFiscale, LocalDate dataNascita, String telefono) {
        this.nome=nome; this.cognome=cognome; this.email=email; this.password=password;
        this.codiceFiscale=codiceFiscale; this.dataNascita=dataNascita; this.telefono=telefono;
    }
    public String getNome(){return nome;}
    public String getCognome(){return cognome;}
    public String getEmail(){return email;}
    public char[] getPassword(){return password;}
    public String getCodiceFiscale(){return codiceFiscale;}
    public LocalDate getDataNascita(){return dataNascita;}
    public String getTelefono(){return telefono;}
    @Override public void close(){if(password!=null){Arrays.fill(password,'\0');}}
}
