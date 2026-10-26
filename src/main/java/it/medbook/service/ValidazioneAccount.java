package it.medbook.service;

import it.medbook.security.PasswordHasher;
import java.util.Arrays;
import java.util.Locale;

/*
 * Regole comuni per account interni e medici; ogni violazione produce IllegalArgumentException
 * senza dettagli, mappata dai chiamanti a 400.
 */
final class ValidazioneAccount {
    private ValidazioneAccount() { }

    static void unicode(CharSequence value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i)))throw new IllegalArgumentException();
            }else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();
        }
    }
    /*
     * Stesse regole di strip, code point, controlli ed email della registrazione; UTF-16 malformato
     * rifiutato esplicitamente.
     */
    static String testo(String value,int max) {
        if(value==null)throw new IllegalArgumentException();unicode(value);String r=value.strip();
        if(r.isEmpty() || r.codePointCount(0,r.length())>max || r.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return r;
    }
    static void id(Integer id){if(id==null || id<=0)throw new IllegalArgumentException();}
    /* Email con strip, minuscolo Locale.ROOT e verifica sintattica. */
    static String email(String value) {
        String email=testo(value,255).toLowerCase(Locale.ROOT);
        if(!email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+"))throw new IllegalArgumentException();
        String local=email.substring(0,email.indexOf('@'));
        if(local.length()>64 || local.startsWith(".") || local.endsWith(".") || local.contains(".."))throw new IllegalArgumentException();
        return email;
    }
    /* Account interni: 12–1024 caratteri Java, senza trim o requisiti di composizione. */
    static String password(String password) {
        if(password==null || password.length()<12 || password.length()>1024)throw new IllegalArgumentException();unicode(password);
        return password;
    }
    /* Stessa regola 12–1024 su password char[], mai convertita in String. */
    static void password(char[] password) {
        if(password==null || password.length<12 || password.length>1024)throw new IllegalArgumentException();
        unicode(java.nio.CharBuffer.wrap(password));
    }
    /*
     * Nuove password paziente: 8–1024 caratteri Java, coppia surrogate conta due; isolate
     * rifiutate, nessun trim o requisito di composizione. Login invariato per password storiche più
     * corte.
     */
    static void passwordPaziente(char[] password) {
        if(password==null || password.length<8 || password.length>1024){throw new IllegalArgumentException("Password non valida.");}
        try{unicode(java.nio.CharBuffer.wrap(password));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Password non valida.");}
    }
    /* Hash PBKDF2; l'array temporaneo viene sempre pulito. */
    static String hash(PasswordHasher hasher,String password) {
        char[] chars=password.toCharArray();try{return hasher.hash(chars);}finally{Arrays.fill(chars,'\0');}
    }
}
