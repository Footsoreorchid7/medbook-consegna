package it.medbook.security;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;

/** Verifica formale, non verifica dell'attribuzione presso l'Anagrafe tributaria.
 * DM 23/12/1976, articoli 5, 6 e 7: omocodia solo nelle sette posizioni numeriche;
 * il checksum usa i caratteri originali omocodici, non le cifre decodificate.
 */
public final class CodiceFiscaleValidator {
    private static final String OMOCODIA="LMNPQRSTUV";
    private static final String MESI="ABCDEHLMPRST";
    private static final int[] DISPARI={1,0,5,7,9,13,15,17,19,21,2,4,18,20,11,3,6,8,12,14,16,10,22,25,24,23};
    private CodiceFiscaleValidator(){ }

    public static String normalizzaEValida(String value) {
        if(value==null){throw invalid();}
        String input=value.strip();
        if(!input.matches("[A-Za-z0-9]{16}")){throw invalid();}
        String cf=input.toUpperCase(Locale.ROOT);
        if(!cf.matches("[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]")){throw invalid();}
        int anno=cifra(cf.charAt(6))*10+cifra(cf.charAt(7));
        int giorno=cifra(cf.charAt(9))*10+cifra(cf.charAt(10));
        if(giorno>=41 && giorno<=71){giorno-=40;}
        else if(giorno<1 || giorno>31){throw invalid();}
        // Il secolo non è codificato: usa un secolo bisestile per l'anno 00.
        try{LocalDate.of(2000+anno,MESI.indexOf(cf.charAt(8))+1,giorno);}
        catch(DateTimeException e){throw invalid();}
        int somma=0;
        for(int i=0;i<15;i++){
            char c=cf.charAt(i);int numero=c>='0'&&c<='9'?c-'0':c-'A';
            somma+=(i%2==0)?DISPARI[numero]:numero;
        }
        if(cf.charAt(15)!=(char)('A'+somma%26)){throw invalid();}
        return cf;
    }
    private static int cifra(char c){return c>='0'&&c<='9'?c-'0':OMOCODIA.indexOf(c);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Codice fiscale non valido.");}
}
