package it.medbook.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CodiceFiscaleValidatorTest {
    @Test void ordinaryCodeIsNormalized(){assertEquals("TSTTST90A01A000Q",CodiceFiscaleValidator.normalizzaEValida(" tsttst90a01a000q "));}
    @ParameterizedTest @ValueSource(strings={"TSTTST90A01A00LT","TSTTSTVLALMALLLK","TSTTST00B29A000S"})
    void acceptsOmocodiaAndLeapDay(String cf){assertEquals(cf,CodiceFiscaleValidator.normalizzaEValida(cf));}
    @ParameterizedTest @ValueSource(strings={"","12345678901","TSTTST90A01A000A","TSTTST90A01A00LQ","TSTTST90B31A000S",
        "TSTTST90A00A000Q","TSTTST90A40A000Q","TSTTST90A72A000Q","TSTTST90F01A000Q","TSTTSTW0A01A000Q",
        "TSTTST90A01A000Qextra","TSTTST90A01A000!","TSTTST90A01A00 Q","TSTTST90A01A00éQ"})
    void rejectsInvalidShapeCalendarOrChecksum(String cf){assertThrows(IllegalArgumentException.class,()->CodiceFiscaleValidator.normalizzaEValida(cf));}
    @Test void rejectsNullWithoutEchoingInput(){var e=assertThrows(IllegalArgumentException.class,()->CodiceFiscaleValidator.normalizzaEValida(null));assertEquals("Codice fiscale non valido.",e.getMessage());}
}
