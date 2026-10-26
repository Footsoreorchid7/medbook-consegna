package it.medbook.service;

import it.medbook.security.CodiceFiscaleValidator;
import java.time.Clock;
import java.time.LocalDate;

/** Stesse regole UC-01, condivise da registrazione e collegamento a identità esistente. */
final class ValidazioneProfiloPaziente {
    record Dati(String codiceFiscale, LocalDate dataNascita, String telefono) { }
    private ValidazioneProfiloPaziente() { }
    static Dati valida(String cf, LocalDate nascita, String telefono, Clock clock) {
        String codice = CodiceFiscaleValidator.normalizzaEValida(cf);
        if(nascita==null || nascita.getYear()<1000 || nascita.isAfter(LocalDate.now(clock)))
            throw new IllegalArgumentException("Data di nascita non valida.");
        if(telefono!=null) {
            telefono=telefono.strip();
            if(telefono.isEmpty())telefono=null;
            else if(telefono.length()>20 || !telefono.matches("[+0-9 ()-]+") || !telefono.matches(".*[0-9].*"))
                throw new IllegalArgumentException("Telefono non valido.");
        }
        return new Dati(codice,nascita,telefono);
    }
}
