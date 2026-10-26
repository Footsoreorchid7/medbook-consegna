package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.*;
import it.medbook.controller.*;
import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Nessun fallback applicativo: URL/catalogo/UUID del database isolato sono verificati. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrazioneIntegrationTest {
    private ConnectionFactory factory;
    private final PasswordHasher hasher=new PasswordHasher();
    private final Queue<String> emails=new ConcurrentLinkedQueue<>();
    private final AtomicInteger sequence=new AtomicInteger();
    private RegistrazioneService service;
    @BeforeAll void setup(){
        String uuid=System.getenv("MEDBOOK_TEST_DB_SERVER_UUID");
        if(uuid==null || uuid.isBlank()){return;}
        factory=TestDatabaseConnections.isolatedFactory();service=new RegistrazioneService(new RegistrazioneRepository(factory),hasher);
    }
    @BeforeEach void requireTestDatabase(){assumeTrue(factory!=null,"Richiesta configurazione MySQL isolata con UUID esplicito.");}
    @AfterEach void cleanup() throws Exception {
        if(factory==null){return;}
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement("DELETE FROM utente WHERE email=?")){
            for(String email:emails){statement.setString(1,email);statement.executeUpdate();}
        }emails.clear();
    }
    private String email(){String email="uc01-"+UUID.randomUUID()+"@example.invalid";emails.add(email);return email;}
    private String cf(){
        int n=sequence.getAndIncrement();String first="ZZZ"+(char)('A'+n/676%26)+(char)('A'+n/26%26)+(char)('A'+n%26)+"90A01A000";
        // Fixture sintetiche indipendenti dal validatore di produzione.
        int[] odd={1,0,5,7,9,13,15,17,19,21,2,4,18,20,11,3,6,8,12,14,16,10,22,25,24,23};int sum=0;
        for(int i=0;i<15;i++){char c=first.charAt(i);int value=Character.isDigit(c)?c-'0':c-'A';sum+=i%2==0?odd[value]:value;}
        return first+(char)('A'+sum%26);
    }
    private void register(String email,String cf) throws Exception {
        try(var input=new RegistrazionePazienteRequest("Fixture","Sintetico",email,"Solo-password-sintetica".toCharArray(),cf,LocalDate.of(1990,1,1),null)){service.registra(input);}
    }
    private long count(String table) throws SQLException {
        assertTrue(Set.of("utente","paziente").contains(table));
        String sql=table.equals("utente")?"SELECT COUNT(*) FROM utente WHERE email=?":"SELECT COUNT(*) FROM paziente p JOIN utente u ON u.id_utente=p.id_utente WHERE u.email=?";
        long count=0;
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement(sql)){
            for(String email:new HashSet<>(emails)){statement.setString(1,email);try(ResultSet row=statement.executeQuery()){assertTrue(row.next());count+=row.getLong(1);}}
        }return count;
    }
    @Test void patientPasswordBoundariesOnTheRealDatabase() throws Exception {
        /* Password paziente 8–1024 caratteri Java; surrogate isolate rifiutate senza scritture. */
        for(String rifiutata:List.of("x".repeat(7),"x".repeat(1025),"abcdefg\uD800")){
            String email=email();
            try(var input=new RegistrazionePazienteRequest("Fixture","Sintetico",email,rifiutata.toCharArray(),cf(),LocalDate.of(1990,1,1),null)){
                assertThrows(IllegalArgumentException.class,()->service.registra(input));
            }
        }
        assertEquals(0,count("utente"));
        for(String ammessa:List.of("x".repeat(8),"y".repeat(1024))){
            try(var input=new RegistrazionePazienteRequest("Fixture","Sintetico",email(),ammessa.toCharArray(),cf(),LocalDate.of(1990,1,1),null)){service.registra(input);}
        }
        assertEquals(2,count("utente"));assertEquals(2,count("paziente"));
    }
    /* Controller e servizio reali: limiti e caratteri Unicode tramite escape JSON. */
    @Test void httpPasswordBoundariesAndMalformedUnicodeEscapes() throws Exception {
        var mapper=new ObjectMapper();var config=new AuthConfig("local-http","http://localhost:8080");
        var controller=new RegistrazioneController(service,new LoginThrottle(Clock.systemUTC(),100,100),config,mapper);
        java.util.function.Function<String,TestHttpExchange> post=passwordJson->{
            String body="{\"nome\":\"Fixture\",\"cognome\":\"Sintetico\",\"email\":\""+email()+"\",\"password\":"+passwordJson
                    +",\"codiceFiscale\":\""+cf()+"\",\"dataNascita\":\"1990-01-01\"}";
            var x=new TestHttpExchange("POST","/api/auth/register",body);x.requestHeaders.set("Origin",config.origin());
            try{controller.handle(x);}catch(Exception e){throw new AssertionError(e);}return x;
        };
        List<String> rifiutate=List.of("\""+"x".repeat(7)+"\"","\""+"x".repeat(1025)+"\"",
                "\"abcdefg\\uD800\"","\"\\uDC00abcdefgh\"","\"abc\\uDE00\\uD83Ddefg\"","\"abcde\\uD83D\\uDE00\"",
                "\"abcdefgh\\uD8\"","\"abcdefgh\\uZZZZ\"","\"abcdefgh\\u\"");
        for(String password:rifiutate){var r=post.apply(password);assertEquals(400,r.status,password.length()>40?"1025 caratteri":password);}
        assertEquals(0,count("utente"),"nessun utente creato dai rifiuti");
        for(String password:List.of("\""+"x".repeat(8)+"\"","\""+"y".repeat(1024)+"\"","\"abcdef\\uD83D\\uDE00\"")){
            assertEquals(201,post.apply(password).status,password.length()>40?"1024 caratteri":password);
        }
        assertEquals(3,count("utente"));
    }
    @Test void createsPatientWithSafeHashAndNoOtherPrivileges() throws Exception {
        String email=email(),cf=cf();register(email,cf.toLowerCase(Locale.ROOT));
        var repo=new UtenteRepository(factory);var account=repo.findByEmail(email).orElseThrow();
        assertTrue(account.identita().paziente());assertFalse(account.identita().medico());assertNull(account.identita().ruoloGestionale());
        assertTrue(hasher.verify("Solo-password-sintetica".toCharArray(),account.passwordHash()));
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT p.id_utente,p.codice_fiscale,p.data_nascita,p.telefono FROM paziente p JOIN utente u ON u.id_utente=p.id_utente WHERE u.email=?")){
            statement.setString(1,email);try(ResultSet row=statement.executeQuery()){assertTrue(row.next());assertEquals(account.identita().idUtente(),row.getInt(1));assertEquals(cf,row.getString(2));assertEquals(LocalDate.of(1990,1,1),row.getObject(3,LocalDate.class));assertNull(row.getString(4));}
        }
    }
    @Test void duplicateEmailCannotAddOrChangeProfiles() throws Exception {
        String email=email();register(email,cf());assertThrows(RegistrazioneDuplicataException.class,()->register(email.toUpperCase(Locale.ROOT),cf()));
        assertEquals(1,count("utente"));assertEquals(1,count("paziente"));
    }
    @Test void duplicateFiscalCodeRollsBackNewUser() throws Exception {
        String first=email(),second=email(),cf=cf();register(first,cf);assertThrows(RegistrazioneDuplicataException.class,()->register(second,cf));
        assertTrue(new UtenteRepository(factory).findByEmail(second).isEmpty());assertEquals(1,count("utente"));assertEquals(1,count("paziente"));
    }
    @Test void patientInsertSqlFailureRollsBackUser() throws Exception {
        String email=email();var failing=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection() throws SQLException {
                Connection real=factory.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")&&((String)args[0]).startsWith("INSERT INTO paziente")){throw new SQLException("Errore simulato dopo primo INSERT","45000",1644);}
                    try{return method.invoke(real,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        var repo=new RegistrazioneRepository(failing);
        assertThrows(SQLException.class,()->repo.registraPaziente("Fixture","Sintetico",email,"hash-sintetico",cf(),LocalDate.of(1990,1,1),null));
        assertTrue(new UtenteRepository(factory).findByEmail(email).isEmpty());assertEquals(0,count("paziente"));
    }
    private void concurrent(boolean sameEmail,boolean sameCf) throws Exception {
        String a=email(),b=sameEmail?a:email(),one=cf(),two=sameCf?one:cf();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> first=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));try{register(a,one);return true;}catch(RegistrazioneDuplicataException e){return false;}};
            Callable<Boolean> second=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));try{register(b,two);return true;}catch(RegistrazioneDuplicataException e){return false;}};
            var fa=pool.submit(first);var fb=pool.submit(second);try{assertTrue(ready.await(5,TimeUnit.SECONDS));}finally{start.countDown();}
            int successes=(fa.get(15,TimeUnit.SECONDS)?1:0)+(fb.get(15,TimeUnit.SECONDS)?1:0);
            assertEquals(sameEmail||sameCf?1:2,successes);assertEquals(successes,count("utente"));assertEquals(successes,count("paziente"));
        }
    }
    @Test void concurrentSameEmailHasOneWinner() throws Exception {concurrent(true,false);}
    @Test void concurrentSameFiscalCodeHasOneWinner() throws Exception {concurrent(false,true);}
    @Test void concurrentDistinctPatientsBothSucceed() throws Exception {concurrent(false,false);}
    @Test void registrationDoesNotCreateSessionAndLaterLoginWorks() throws Exception {
        String email=email(),cf=cf();var sessions=new SessionStore(Clock.systemUTC(),10);var config=new AuthConfig("local-http","http://localhost:8080");var mapper=new ObjectMapper();
        var registerController=new RegistrazioneController(service,new LoginThrottle(Clock.systemUTC(),10,10),config,mapper);
        var request=new TestHttpExchange("POST","/api/auth/register",mapper.writeValueAsString(Map.of("nome","Fixture","cognome","Sintetico","email",email,"password","Solo-password-sintetica","codiceFiscale",cf,"dataNascita","1990-01-01")));
        request.requestHeaders.set("Origin",config.origin());registerController.handle(request);assertEquals(201,request.status);assertEquals(0,sessions.size());assertNull(request.responseHeaders.getFirst("Set-Cookie"));
        var auth=new AutenticazioneService(new UtenteRepository(factory),hasher,sessions);
        var controller=new AutenticazioneController(auth,sessions,new LoginThrottle(Clock.systemUTC(),10,10),config,mapper);
        var login=new TestHttpExchange("POST","/api/auth/login",mapper.writeValueAsString(Map.of("email",email,"password","Solo-password-sintetica")));
        login.requestHeaders.set("Origin",config.origin());controller.handle(login);assertEquals(200,login.status);assertEquals(1,sessions.size());
        var identity=mapper.readTree(login.body());assertTrue(identity.get("paziente").asBoolean());assertFalse(identity.get("medico").asBoolean());assertTrue(identity.get("ruoloGestionale").isNull());
        assertFalse(login.body().contains(cf));assertFalse(login.body().contains("password"));
    }
    @Test void schemaAlreadyContainsRequiredFiscalCodeAndUniqueIndex() throws Exception {
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='paziente' AND COLUMN_NAME='codice_fiscale'")){
            try(ResultSet row=statement.executeQuery()){assertTrue(row.next());assertEquals("char",row.getString(1));assertEquals(16,row.getInt(2));assertEquals("NO",row.getString(3));}
            try(PreparedStatement index=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='paziente' AND INDEX_NAME='uq_paziente_codice_fiscale' AND COLUMN_NAME='codice_fiscale' AND NON_UNIQUE=0");ResultSet row=index.executeQuery()){
                assertTrue(row.next());assertEquals(1,row.getInt(1));
            }
        }
    }
}
