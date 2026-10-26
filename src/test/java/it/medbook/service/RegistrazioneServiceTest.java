package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.repository.RegistrazioneRepository;
import it.medbook.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneServiceTest {
    private final Fake repository=new Fake();
    private final PasswordHasher hasher=new PasswordHasher();
    private final RegistrazioneService service=new RegistrazioneService(repository,hasher,Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),ZoneOffset.UTC));
    private RegistrazionePazienteRequest request(String key,Object override){
        var values=new HashMap<String,Object>();values.put("nome"," Fixture ");values.put("cognome"," Sintetico ");values.put("email"," Fixture@Example.invalid ");
        values.put("password","Solo-password-di-test");values.put("codiceFiscale","tsttst90a01a000q");values.put("dataNascita",LocalDate.of(1990,1,1));values.put("telefono",null);
        if(key!=null){values.put(key,override);}String password=(String)values.get("password");
        return new RegistrazionePazienteRequest((String)values.get("nome"),(String)values.get("cognome"),(String)values.get("email"),password==null?null:password.toCharArray(),
            (String)values.get("codiceFiscale"),(LocalDate)values.get("dataNascita"),(String)values.get("telefono"));
    }
    @Test void validatesNormalizesHashesAndDelegatesOnce() throws Exception {
        try(var input=request(null,null)){
            service.registra(input);assertEquals(1,repository.calls);assertEquals("Fixture",repository.nome);assertEquals("Sintetico",repository.cognome);
            assertEquals("fixture@example.invalid",repository.email);assertEquals("TSTTST90A01A000Q",repository.cf);assertNull(repository.phone);
            assertTrue(hasher.verify(input.getPassword(),repository.hash));assertNotEquals(new String(input.getPassword()),repository.hash);
            assertTrue(repository.hash.length()<=255);assertEquals(LocalDate.of(1990,1,1),repository.date);
        }
    }
    @Test void acceptsOmocodiaAndOptionalPhone() throws Exception {
        try(var input=new RegistrazionePazienteRequest("Fixture","Sintetico","fixture@example.invalid","Solo-test".toCharArray(),"TSTTST90A01A00LT",LocalDate.of(1990,1,1)," +39 000-000 ")){
            service.registra(input);assertEquals("TSTTST90A01A00LT",repository.cf);assertEquals("+39 000-000",repository.phone);
        }
    }
    static Stream<Arguments> invalid(){return Stream.of(
        Arguments.of("nome",null),Arguments.of("nome"," "),Arguments.of("nome","x".repeat(101)),Arguments.of("nome","a\nb"),
        Arguments.of("cognome",null),Arguments.of("cognome",""),Arguments.of("cognome","x".repeat(101)),
        Arguments.of("email",null),Arguments.of("email","bad"),Arguments.of("email","a@@example.invalid"),Arguments.of("email","a b@example.invalid"),
        Arguments.of("email",".a@example.invalid"),Arguments.of("email","a..b@example.invalid"),Arguments.of("email","a.@example.invalid"),Arguments.of("email","x".repeat(65)+"@example.invalid"),
        Arguments.of("password",null),Arguments.of("password",""),Arguments.of("password","x".repeat(1025)),
        /* Almeno otto caratteri Java, con rifiuto delle surrogate isolate. */
        Arguments.of("password","x"),Arguments.of("password","x".repeat(7)),Arguments.of("password","abcde\uD83D\uDE00"),
        Arguments.of("password","abcdefg\uD800"),Arguments.of("password","\uDC00abcdefgh"),
        Arguments.of("codiceFiscale",null),Arguments.of("codiceFiscale","TSTTST90A01A000A"),
        Arguments.of("dataNascita",null),Arguments.of("dataNascita",LocalDate.of(2030,1,1)),Arguments.of("dataNascita",LocalDate.of(999,1,1)),
        Arguments.of("telefono","abc"),Arguments.of("telefono","+"),Arguments.of("telefono","1".repeat(21))
    );}
    @ParameterizedTest @MethodSource("invalid") void invalidInputNeverReachesRepository(String key,Object value){
        try(var input=request(key,value)){assertThrows(IllegalArgumentException.class,()->service.registra(input));assertEquals(0,repository.calls);}
    }
    @Test void passwordBoundariesAcceptedAtEightAndAtTheMaximum() throws Exception {
        for(String password:List.of("x".repeat(8),"x".repeat(1024),"abcdef\uD83D\uDE00","        ")){
            try(var input=request("password",password)){service.registra(input);}
        }
        assertEquals(4,repository.calls);
    }
    @Test void nullRequestIsRejected(){assertThrows(IllegalArgumentException.class,()->service.registra(null));}
    @Test void sqlErrorIsPropagated() throws Exception {
        repository.error=new SQLException("Errore simulato");try(var input=request(null,null)){assertSame(repository.error,assertThrows(SQLException.class,()->service.registra(input)));assertEquals(1,repository.calls);}
    }
    @Test void requestClearsPasswordOnClose(){var input=request(null,null);char[] password=input.getPassword();input.close();for(char c:password){assertEquals('\0',c);}}
    private static class Fake extends RegistrazioneRepository {
        int calls;String nome,cognome,email,hash,cf,phone;LocalDate date;SQLException error;
        Fake(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        @Override public Integer registraPaziente(String n,String c,String e,String h,String f,LocalDate d,String p) throws SQLException {
            calls++;nome=n;cognome=c;email=e;hash=h;cf=f;date=d;phone=p;if(error!=null){throw error;}return 7;
        }
    }
}
