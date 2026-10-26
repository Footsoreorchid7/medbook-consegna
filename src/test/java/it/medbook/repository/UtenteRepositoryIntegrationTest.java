package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fixture sintetiche soltanto sul catalogo di test verificato, mai su medbook. */
class UtenteRepositoryIntegrationTest {
    private ConnectionFactory factory;
    private UtenteRepository repository;
    private int id;
    private String email,encoded;
    private char[] password;
    @BeforeEach void fixture() throws Exception {
        String uuid=System.getenv("MEDBOOK_TEST_DB_SERVER_UUID");
        assumeTrue(uuid!=null && !uuid.isBlank(),"Richiesto UUID esplicito dell'istanza di test.");
        factory=TestDatabaseConnections.isolatedFactory(); repository=new UtenteRepository(factory);
        email="auth-"+UUID.randomUUID()+"@example.invalid";
        password=UUID.randomUUID().toString().toCharArray(); encoded=new PasswordHasher().hash(password);
        try(Connection connection=factory.getConnection(); PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1,"Fixture"); statement.setString(2,"UC02"); statement.setString(3,email); statement.setString(4,encoded);
            assertEquals(1,statement.executeUpdate());
            try(ResultSet keys=statement.getGeneratedKeys()) { assertTrue(keys.next()); id=keys.getInt(1); }
        }
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if(factory!=null && id>0) {
                try(Connection connection=factory.getConnection(); PreparedStatement statement=connection.prepareStatement("DELETE FROM utente WHERE id_utente=?")) {
                    statement.setInt(1,id); statement.executeUpdate();
                }
            }
        } finally { if(password!=null) { Arrays.fill(password,'\0'); } }
    }
    private void role(String role) throws SQLException {
        try(Connection connection=factory.getConnection(); PreparedStatement statement=connection.prepareStatement("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?")) {
            statement.setString(1,role); statement.setInt(2,id); assertEquals(1,statement.executeUpdate());
        }
    }
    private void profiles() throws SQLException {
        try(Connection connection=factory.getConnection();
            PreparedStatement medico=connection.prepareStatement("INSERT INTO medico(id_utente,specializzazione,numero_iscrizione_ordine) VALUES(?,?,?)");
            PreparedStatement paziente=connection.prepareStatement("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,?)")) {
            medico.setInt(1,id); medico.setString(2,"Test UC02"); medico.setString(3,"AUTH-"+UUID.randomUUID()); medico.executeUpdate();
            paziente.setInt(1,id); paziente.setString(2,UUID.randomUUID().toString().replace("-","").substring(0,16));
            paziente.setDate(3,java.sql.Date.valueOf("2000-01-01")); paziente.executeUpdate();
        }
    }
    @Test void readsCredentialsByEmailUsingExactSchema() throws Exception {
        var account=repository.findByEmail(email).orElseThrow();
        assertEquals(id,account.identita().idUtente()); assertEquals(encoded,account.passwordHash());
        assertEquals("Fixture",account.identita().nome()); assertEquals("UC02",account.identita().cognome());
        assertNull(account.identita().ruoloGestionale()); assertFalse(account.identita().medico()); assertFalse(account.identita().paziente());
    }
    @Test void readsSafeIdentityByIdWithoutHashOrSensitiveProfileData() throws Exception {
        var identity=repository.findById(id).orElseThrow(); String json=new ObjectMapper().writeValueAsString(identity);
        assertEquals(id,identity.idUtente());
        for(String forbidden:List.of("password","email","codiceFiscale","dataNascita","telefono",encoded)) { assertFalse(json.contains(forbidden)); }
    }
    @Test void databaseEmailCollationIsRespected() throws Exception {
        assertEquals(id,repository.findByEmail(email.toUpperCase(Locale.ROOT)).orElseThrow().identita().idUtente());
    }
    @Test void nonexistentUserAndSqlPayloadReturnEmpty() throws Exception {
        assertTrue(repository.findByEmail("missing-"+UUID.randomUUID()+"@example.invalid").isEmpty());
        assertTrue(repository.findByEmail("' OR 1=1 --").isEmpty()); assertTrue(repository.findById(0).isEmpty());
    }
    @Test void overlappingProfilesAndIndependentRolesAreMappedCorrectly() throws Exception {
        profiles();
        for(String value:new String[]{null,"AMMINISTRATORE","OPERATORE_DESK"}) {
            role(value); var identity=repository.findById(id).orElseThrow();
            assertTrue(identity.paziente()); assertTrue(identity.medico());
            assertEquals(value==null?null:RuoloGestionale.valueOf(value),identity.ruoloGestionale());
        }
    }
    @Test void nonCanonicalRoleFailsClosedAsInternalDatabaseError() throws Exception {
        // La collation ci del CHECK SQL accetta anche minuscole; il dominio usa valori canonici.
        role("operatore_desk");
        assertThrows(SQLException.class, () -> repository.findById(id));
        assertThrows(SQLException.class, () -> repository.findByEmail(email));
    }
    @Test void serviceAuthenticatesRealHashAndReloadsCurrentRole() throws Exception {
        SessionStore sessions=new SessionStore(Clock.systemUTC(),10);
        var service=new AutenticazioneService(repository,new PasswordHasher(),sessions);
        var session=service.login(email,password).orElseThrow(); assertEquals(id,session.idUtente());
        role("AMMINISTRATORE"); assertEquals(RuoloGestionale.AMMINISTRATORE,service.identity(session).orElseThrow().ruoloGestionale());
        role("OPERATORE_DESK"); assertEquals(RuoloGestionale.OPERATORE_DESK,service.identity(session).orElseThrow().ruoloGestionale());
        assertTrue(service.login(email,"Sintetica errata".toCharArray()).isEmpty());
    }
    @Test void existingFakeHashCannotAuthenticate() throws Exception {
        try(Connection connection=factory.getConnection(); PreparedStatement statement=connection.prepareStatement("UPDATE utente SET password_hash='hash-fittizio' WHERE id_utente=?")) {
            statement.setInt(1,id); statement.executeUpdate();
        }
        var sessions=new SessionStore(Clock.systemUTC(),10);
        var service=new AutenticazioneService(repository,new PasswordHasher(),sessions);
        assertTrue(service.login(email,"hash-fittizio".toCharArray()).isEmpty()); assertEquals(0,sessions.size());
    }
    @Test void lookupsDoNotModifyAccountAndMissingUserRevokesSession() throws Exception {
        repository.findByEmail(email); repository.findById(id);
        assertEquals(encoded,repository.findByEmail(email).orElseThrow().passwordHash());
        var sessions=new SessionStore(Clock.systemUTC(),10); var session=sessions.create(id, 0L);
        var service=new AutenticazioneService(repository,new PasswordHasher(),sessions);
        try(Connection connection=factory.getConnection(); PreparedStatement statement=connection.prepareStatement("DELETE FROM utente WHERE id_utente=?")) {
            statement.setInt(1,id); assertEquals(1,statement.executeUpdate());
        }
        assertTrue(service.identity(session).isEmpty()); assertTrue(sessions.find(session.id()).isEmpty());
    }
}
