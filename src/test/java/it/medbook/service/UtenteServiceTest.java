package it.medbook.service;
import it.medbook.dto.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.config.*;
import it.medbook.security.PasswordHasher;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class UtenteServiceTest {
    public static class Repo extends UtenteRepository {
        public int calls;public String hash,email;public Integer actor,target;public Throwable failure;
        public Repo(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        void fail()throws SQLException{if(failure instanceof SQLException e)throw e;if(failure instanceof RuntimeException e)throw e;}
        @Override public List<AccountInternoResponse> findGestionali()throws SQLException{calls++;fail();return List.of();}
        @Override public AccountInternoResponse createInterno(Integer a,String n,String c,String e,String h,RuoloGestionale r)throws SQLException {
            calls++;fail();actor=a;email=e;hash=h;return new AccountInternoResponse(9,n,c,e,r,false,false,"ATTIVO");
        }
        @Override public AccountInternoResponse updateRuolo(Integer a,Integer t,RuoloGestionale r)throws SQLException {
            calls++;fail();actor=a;target=t;return new AccountInternoResponse(t,"Nome","Cognome","e@example.invalid",r,false,false,"ATTIVO");
        }
    }
    /* Password interna valida con spazi iniziali e finali significativi. */
    static final String PASSWORD=" pass phrase x ";
    @Test void creationUsesUc01NormalizationAndRealHasher()throws Exception {
        for(var role:RuoloGestionale.values()) {
            var repo=new Repo();var hasher=new PasswordHasher();var service=new UtenteService(repo,hasher);
            var result=service.crea(7,new AccountInternoRequest(" Nome "," Cognome "," User@Example.invalid ",PASSWORD,role));
            assertEquals("Nome",result.nome());assertEquals("Cognome",result.cognome());assertEquals("user@example.invalid",repo.email);
            assertTrue(hasher.verify(PASSWORD.toCharArray(),repo.hash));assertFalse(hasher.verify(PASSWORD.strip().toCharArray(),repo.hash));assertEquals(1,repo.calls);
            assertFalse(result.medico());assertFalse(result.paziente());
        }
    }
    @Test void invalidInputsDoNotDelegate() {
        String surrogate=String.valueOf((char)0xd800);
        for(var request:Arrays.asList(null,new AccountInternoRequest("","C","e@example.invalid",PASSWORD,RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest(surrogate,"C","e@example.invalid",PASSWORD,RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N",surrogate,"e@example.invalid",PASSWORD,RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N","C","..e@example.invalid",PASSWORD,RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N","C","e@example.invalid","",RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N","C","e@example.invalid",surrogate,RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N","C","e@example.invalid","p".repeat(1025),RuoloGestionale.AMMINISTRATORE),
                new AccountInternoRequest("N","C","e@example.invalid",PASSWORD,null))) {
            var repo=new Repo();assertThrows(IllegalArgumentException.class,()->new UtenteService(repo,new PasswordHasher()).crea(7,request));assertEquals(0,repo.calls);
        }
    }
    @Test void readAndRoleDelegateOnceAndPropagate()throws Exception {
        var repo=new Repo();var service=new UtenteService(repo,new PasswordHasher());assertTrue(service.getGestionali().isEmpty());
        for(var role:RuoloGestionale.values()){var result=service.aggiornaRuolo(7,9,new RuoloUtenteRequest(role));assertEquals(role,result.ruoloGestionale());assertEquals(7,repo.actor);assertEquals(9,repo.target);}
        assertEquals(3,repo.calls);var error=new SQLException("fixture");repo.failure=error;assertSame(error,assertThrows(SQLException.class,service::getGestionali));
        assertThrows(IllegalArgumentException.class,()->service.aggiornaRuolo(7,0,new RuoloUtenteRequest(RuoloGestionale.AMMINISTRATORE)));
        assertThrows(IllegalArgumentException.class,()->service.aggiornaRuolo(7,9,new RuoloUtenteRequest(null)));
    }
    @Test void internalPasswordIsTwelveToTenTwentyFourJavaCharsWithoutTrim()throws Exception {
        for(String password:List.of("a".repeat(11),"           ","          b","\uD83D\uDE00".repeat(5)+"a","a".repeat(1025))) {
            var repo=new Repo();assertThrows(IllegalArgumentException.class,()->new UtenteService(repo,new PasswordHasher()).crea(7,
                    new AccountInternoRequest("N","C","e@example.invalid",password,RuoloGestionale.OPERATORE_DESK)));assertEquals(0,repo.calls);
        }
        var hasher=new PasswordHasher();
        // Dodici caratteri Java: sei coppie surrogate, pari a sei code point, sono ammesse.
        for(String password:List.of("a".repeat(12),"a".repeat(1024),"     ab     ","\uD83D\uDE00".repeat(6),"            ")) {
            var repo=new Repo();new UtenteService(repo,hasher).crea(7,new AccountInternoRequest("N","C","e@example.invalid",password,RuoloGestionale.AMMINISTRATORE));
            assertEquals(1,repo.calls);assertTrue(hasher.verify(password.toCharArray(),repo.hash));
            if(!password.isBlank() && !password.equals(password.strip()))assertFalse(hasher.verify(password.strip().toCharArray(),repo.hash));
            assertFalse(repo.hash.contains(password));
        }
    }
    @Test void internalPasswordWithLoneSurrogateIsRejectedAtValidLength() {
        /* La lunghezza è ammessa: solo la validazione Unicode può rifiutare l'input. */
        for(String password:List.of("a".repeat(11)+"\uD800","\uDC00"+"a".repeat(11))) {
            assertEquals(12,password.length());
            var repo=new Repo();assertThrows(IllegalArgumentException.class,()->new UtenteService(repo,new PasswordHasher()).crea(7,
                    new AccountInternoRequest("N","C","e@example.invalid",password,RuoloGestionale.AMMINISTRATORE)));assertEquals(0,repo.calls);
        }
    }
    @Test void patientRegistrationKeepsItsOwnPolicy()throws Exception {
        /* La politica paziente è distinta da quella degli account interni: */
        /* 8–1024 caratteri Java; le password da 8 a 11 sono ammesse solo per pazienti. */
        for(String password:List.of("a".repeat(8),"a".repeat(11))) {
            int[] calls={0};
            var registrazione=new RegistrazioneService(new it.medbook.repository.RegistrazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())){
                @Override public Integer registraPaziente(String n,String c,String e,String h,String f,java.time.LocalDate d,String p){calls[0]++;return 7;}
            },new PasswordHasher(),java.time.Clock.fixed(java.time.Instant.parse("2026-10-10T12:00:00Z"),java.time.ZoneOffset.UTC));
            try(var input=new RegistrazionePazienteRequest("Fixture","Sintetico","fixture@example.invalid",password.toCharArray(),"TSTTST90A01A000Q",java.time.LocalDate.of(1990,1,1),null)){
                registrazione.registra(input);
            }
            assertEquals(1,calls[0]);
        }
    }
}
