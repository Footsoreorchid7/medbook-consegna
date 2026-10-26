package it.medbook.repository;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.controller.MedicoController;
import it.medbook.dto.MedicoResponse;
import it.medbook.security.*;
import it.medbook.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/* Stack HTTP e MySQL reali per autorizzazioni, header duplicati e guasti dopo commit. */
class MedicoHttpResiduiIntegrationTest {
    MedicoAbilitazioniIntegrationTest.F f;HttpServer server;HttpClient client;String origin;SessionStore store;
    SessionStore.Session admin,desk;AuthConfig config;final ObjectMapper mapper=new ObjectMapper();
    /* Conta ogni modifica che raggiunge il servizio. */
    static final class CountingService extends MedicoService {
        final AtomicInteger calls=new AtomicInteger();
        CountingService(MedicoRepository r){super(r,new PasswordHasher());}
        @Override public MedicoResponse crea(Integer a,it.medbook.dto.NuovoMedicoRequest r)throws SQLException{calls.incrementAndGet();return super.crea(a,r);}
        @Override public MedicoResponse cambiaStatoMedico(Integer a,Integer m,String s)throws SQLException{calls.incrementAndGet();return super.cambiaStatoMedico(a,m,s);}
        @Override public MedicoResponse aggiornaDatiPersonali(Integer a,Integer m,it.medbook.dto.DatiPersonaliMedicoRequest r)throws SQLException{calls.incrementAndGet();return super.aggiornaDatiPersonali(a,m,r);}
    }
    CountingService service;

    @BeforeEach void fixture()throws Exception{f=new MedicoAbilitazioniIntegrationTest.F();store=new SessionStore(Clock.systemUTC(),20);admin=store.create(f.admin, 0L);desk=store.create(f.operator, 0L);}
    void start(ConnectionFactory medici)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+server.getAddress().getPort();config=new AuthConfig("local-http",origin);
        var users=new UtenteRepository(f.factory);var hasher=new PasswordHasher();service=new CountingService(new MedicoRepository(medici));
        server.createContext("/api/medici",new AutorizzazioneHandler(new MedicoController(service,users,mapper),new AutenticazioneService(users,hasher,store),store,config,mapper));
        server.start();client=HttpClient.newHttpClient();
    }
    @AfterEach void stop()throws Exception{try{if(client!=null)client.close();if(server!=null)server.stop(0);}finally{if(f!=null)f.close();}}
    HttpRequest.Builder request(SessionStore.Session session,String path){
        return HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15)).header("Cookie",config.cookieName()+"="+session.id()).header("Origin",origin).header("X-CSRF-Token",session.csrfToken());
    }
    HttpResponse<String> send(HttpRequest.Builder b)throws Exception{return client.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> json(SessionStore.Session session,String method,String path,String body)throws Exception{
        return send(request(session,path).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)));
    }
    Map<?,?> error(HttpResponse<String> r)throws Exception{
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).forType(Map.class).readValue(r.body());
    }
    String med(int id){return "/api/medici/"+id;}
    static ConnectionFactory counting(ConnectionFactory delegate,AtomicInteger connections){
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{connections.incrementAndGet();return delegate.getConnection();}
        };
    }

    /* Content-Type duplicato: 415 senza delega o persistenza. */
    @Test void duplicateContentTypeIs415WithoutDelegationOrPersistence()throws Exception{
        var connections=new AtomicInteger();start(counting(f.factory,connections));
        int m=f.doctor();var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m);
        var personaBefore=MedicoEmailConcorrenzaIntegrationTest.anagrafica(f.factory,m);
        String email="f9e2-"+UUID.randomUUID()+"@example.invalid";
        Map<String,String> bodies=new LinkedHashMap<>();
        bodies.put(med(m)+"/stato","{\"stato\":\"INATTIVO\"}");
        bodies.put(med(m),"{\"tipo\":\"PERSONALI\",\"nome\":\"X\",\"cognome\":\"Y\",\"email\":\""+email+"\"}");
        bodies.put("/api/medici",mapper.writeValueAsString(Map.of("nome","N","cognome","C","email",email,"password","password di prova F9","specializzazione","S","numeroIscrizioneOrdine","F9-"+UUID.randomUUID())));
        for(var e:bodies.entrySet()) {
            String method=e.getKey().equals("/api/medici")?"POST":"PUT";
            var r=send(request(admin,e.getKey()).header("Content-Type","application/json").header("Content-Type","application/json")
                    .method(method,HttpRequest.BodyPublishers.ofString(e.getValue())));
            assertEquals(415,r.statusCode(),e.getKey());assertEquals(Map.of("error","Content-Type non supportato."),error(r));
        }
        assertEquals(0,service.calls.get(),"nessuna delega al service");assertEquals(0,connections.get(),"nessun accesso del repository");
        assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m));
        assertEquals(personaBefore,MedicoEmailConcorrenzaIntegrationTest.anagrafica(f.factory,m));
        assertEquals(0,MedicoConcorrenzaIntegrationTest.contaEmail(f,email));
        /* La stessa richiesta con un solo header è accettata. */
        assertEquals(200,json(admin,"PUT",med(m)+"/stato","{\"stato\":\"INATTIVO\"}").statusCode());assertEquals(1,service.calls.get());
    }

    /* L'attore passa da desk ad amministratore durante la lettura del dettaglio desk. */
    @Test void deskPromotedToAdminDuringDetailGets403WithoutAdministrativeData()throws Exception{
        int m=f.doctor();String doctorEmail=MedicoEmailConcorrenzaIntegrationTest.anagrafica(f.factory,m).get(2);
        List<String> queries=Collections.synchronizedList(new ArrayList<>());var promoted=new AtomicBoolean();
        start(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
            if(!after)return;queries.add(sql.replaceAll("\\s+"," ").trim());
            if(sql.contains("FROM medico m JOIN utente u")&&sql.contains("WHERE m.id_utente=?")&&promoted.compareAndSet(false,true))
                f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",f.operator);
        }));
        var r=json(desk,"GET",med(m),null);
        assertTrue(promoted.get());assertEquals(403,r.statusCode(),r.body());
        assertEquals(Map.of("error","Operazione non autorizzata."),error(r));assertFalse(r.body().contains(doctorEmail));
        /* Una proiezione desk, poi le capacità: la SELECT amministrativa con email non viene eseguita. */
        assertEquals(1,queries.stream().filter(q->q.startsWith("SELECT u.id_utente,u.nome,u.cognome,m.specializzazione")).count(),queries.toString());
        assertTrue(queries.stream().noneMatch(q->q.contains("u.email")),queries.toString());
        /* La promozione vale dalla richiesta successiva ed è l'unica causa del 403. */
        var next=json(desk,"GET",med(m),null);assertEquals(200,next.statusCode());assertEquals(doctorEmail,mapper.readTree(next.body()).get("medico").get("email").asText());
    }

    /*
     * Fa fallire una volta RELEASE_LOCK o ripristino autocommit dopo il commit reale; conta commit
     * e aggiornamenti.
     */
    static ConnectionFactory failingAfterCommit(ConnectionFactory delegate,String mode,AtomicBoolean armed,AtomicInteger commits,AtomicInteger updates,AtomicInteger connections){
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{
                connections.incrementAndGet();Connection c=delegate.getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(a[0])&&mode.equals("AUTOCOMMIT")&&commits.get()>0&&armed.compareAndSet(true,false))
                        throw new SQLException("secret autocommit restore failure","08S01");
                    Object value;
                    try{value=m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                    if(m.getName().equals("commit"))commits.incrementAndGet();
                    if(!m.getName().equals("prepareStatement"))return value;
                    String sql=(String)a[0];PreparedStatement s=(PreparedStatement)value;
                    return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{
                        if(n.getName().equals("executeQuery")&&sql.equals("SELECT RELEASE_LOCK(?)")&&mode.equals("RELEASE")&&armed.compareAndSet(true,false))
                            throw new SQLException("secret RELEASE_LOCK failure","08S01");
                        if(n.getName().equals("executeUpdate")&&sql.startsWith("UPDATE medico SET stato"))updates.incrementAndGet();
                        try{return n.invoke(s,b);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                });
            }
        };
    }

    /*
     * Errore JDBC dopo commit: un solo 500 generico, senza retry o dettagli SQL, benché la
     * scrittura sia persistita.
     */
    @ParameterizedTest @ValueSource(strings={"RELEASE","AUTOCOMMIT"})
    void cleanupFailureAfterCommitIsGeneric500AndWriteIsPersisted(String mode)throws Exception{
        var armed=new AtomicBoolean(true);var commits=new AtomicInteger();var updates=new AtomicInteger();var connections=new AtomicInteger();
        start(failingAfterCommit(f.factory,mode,armed,commits,updates,connections));
        int m=f.doctor();assertEquals("ATTIVO",MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m).get(3));
        var r=json(admin,"PUT",med(m)+"/stato","{\"stato\":\"INATTIVO\"}");
        assertFalse(armed.get(),"il guasto è stato iniettato");
        assertEquals(500,r.statusCode());assertEquals(Map.of("error","Errore interno del server."),error(r));
        for(String secret:List.of("secret","RELEASE_LOCK","autocommit","SQL","medbook.accounts"))assertFalse(r.body().contains(secret),secret);
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertEquals(1,service.calls.get(),"una sola delega");assertEquals(1,connections.get(),"nessun nuovo tentativo");
        assertEquals(1,commits.get());assertEquals(1,updates.get());
        assertEquals("INATTIVO",MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m).get(3),"scrittura già persistita");
        /* La connessione dedicata chiusa libera il lock nominato; l'operazione successiva procede. */
        assertTrue(MedicoEmailConcorrenzaIntegrationTest.mutexLibero(f.factory));
        var again=json(admin,"PUT",med(m)+"/stato","{\"stato\":\"INATTIVO\"}");
        assertEquals(200,again.statusCode());assertEquals("INATTIVO",mapper.readTree(again.body()).get("stato").asText());
        assertEquals(1,updates.get(),"stato già INATTIVO: nessuna nuova scrittura");
    }
}
