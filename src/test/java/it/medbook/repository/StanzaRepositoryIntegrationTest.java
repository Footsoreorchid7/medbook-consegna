package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.model.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.sql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static it.medbook.repository.PrenotazioneIndisponibilitaIntegrationTest.observeQueries;

class StanzaRepositoryIntegrationTest {
    static Stanza value(String name,String description){var s=new Stanza();s.setNome(name);s.setDescrizione(description);return s;}
    static class Fixture extends PrenotazioneIndisponibilitaIntegrationTest.Fixture {
        final List<Integer> extraRooms=new ArrayList<>();
        Fixture()throws Exception{super();}
        StanzaRepository repo(){return new StanzaRepository(factory);}
        Stanza create(String name,String description)throws Exception{var s=repo().create(value(name,description));extraRooms.add(s.getIdStanza());return s;}
        Stanza read(int id)throws Exception{return repo().findAll().stream().filter(s->s.getIdStanza()==id).findFirst().orElseThrow();}
        int freeSlot(LocalDateTime start)throws Exception{int id=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",owner,start,start.plusHours(1));slots.add(id);return id;}
        List<Object> slotSnapshot(int id)throws Exception{try(var c=factory.getConnection();var s=c.prepareStatement("SELECT * FROM disponibilita WHERE id_disponibilita=?")){s.setInt(1,id);try(var r=s.executeQuery()){assertTrue(r.next());var v=new ArrayList<Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)v.add(r.getObject(i));return v;}}}
        @Override public void close()throws SQLException{for(int id:extraRooms)execute("DELETE FROM stanza WHERE id_stanza=?",id);super.close();}
    }
    @Test void adminHttpPostAndPutUseDatabaseRoleAndPersistMetadata()throws Exception {
        try(var f=new Fixture()) {
            f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",f.operator);
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var sessions=new it.medbook.security.SessionStore(Clock.systemUTC(),10);var session=sessions.create(f.operator, 0L);
            var users=new UtenteRepository(f.factory);
            var auth=new it.medbook.service.AutenticazioneService(users,new it.medbook.security.PasswordHasher(),sessions);
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            String origin="http://127.0.0.1:"+server.getAddress().getPort();var config=new AuthConfig("local-http",origin);
            server.createContext("/api/stanze",new it.medbook.security.AutorizzazioneHandler(
                    new it.medbook.controller.StanzaController(new it.medbook.service.StanzaService(f.repo()),mapper),auth,sessions,config,mapper));
            server.start();
            try(var client=java.net.http.HttpClient.newHttpClient()) {
                String name="UC25-HTTP-"+UUID.randomUUID();Integer room=null;
                for(String method:List.of("POST","PUT")) {
                    String path=method.equals("POST")?"/api/stanze":"/api/stanze/"+room;
                    String updated=method.equals("POST")?name:name+"-updated";
                    String body=mapper.writeValueAsString(Map.of("nome",updated,"descrizione","HTTP metadata"));
                    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+path)).timeout(Duration.ofSeconds(10))
                            .header("Cookie",config.cookieName()+"="+session.id()).header("Origin",origin)
                            .header("X-CSRF-Token",session.csrfToken()).header("Content-Type","application/json")
                            .method(method,java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                    var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
                    var json=mapper.readTree(response.body());
                    if(method.equals("POST")&&json.has("idStanza")){room=json.get("idStanza").intValue();f.extraRooms.add(room);}
                    assertEquals(method.equals("POST")?201:200,response.statusCode());
                    assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
                    assertEquals(3,json.size());assertEquals(room,json.get("idStanza").intValue());
                    assertEquals(updated,json.get("nome").textValue());assertEquals("HTTP metadata",json.get("descrizione").textValue());
                    var persisted=f.read(room);assertEquals(updated,persisted.getNome());assertEquals("HTTP metadata",persisted.getDescrizione());
                }
                f.execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?",f.operator);
                var denied=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/stanze/"+room))
                        .header("Cookie",config.cookieName()+"="+session.id()).header("Origin",origin)
                        .header("X-CSRF-Token",session.csrfToken()).header("Content-Type","application/json")
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{\"nome\":\"forbidden\"}")).build();
                assertEquals(403,client.send(denied,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(name+"-updated",f.read(room).getNome());
            }finally{server.stop(0);}
        }
    }
    @Test void generatedKeysNullEmptyDescriptionsAndOrderedReads()throws Exception{
        try(var f=new Fixture()){for(String d:new String[]{null,"","  text  "}){var s=f.create("UC25-"+UUID.randomUUID(),d);assertTrue(s.getIdStanza()>0);assertEquals(d,f.read(s.getIdStanza()).getDescrizione());}
            var rows=f.repo().findAll();var ids=rows.stream().map(Stanza::getIdStanza).toList();assertEquals(ids.stream().sorted().toList(),ids);}
    }
    @Test void supplementaryCharactersFitRealVarcharLimits()throws Exception{
        try(var f=new Fixture()){String emoji=new String(Character.toChars(0x1f600));var s=f.create(emoji.repeat(100),emoji.repeat(255));assertEquals(emoji.repeat(100),f.read(s.getIdStanza()).getNome());assertEquals(emoji.repeat(255),f.read(s.getIdStanza()).getDescrizione());
            assertThrows(SQLException.class,()->f.repo().updateAtomically(s.getIdStanza(),value(emoji.repeat(101),null)));assertEquals(emoji.repeat(100),f.read(s.getIdStanza()).getNome());
            assertThrows(SQLException.class,()->f.repo().updateAtomically(s.getIdStanza(),value("x",emoji.repeat(256))));assertEquals(emoji.repeat(255),f.read(s.getIdStanza()).getDescrizione());}
    }
    @Test void duplicateCaseAccentAndUpdateAreRealNameConflicts()throws Exception{
        try(var f=new Fixture()){String suffix=UUID.randomUUID().toString();var a=f.create("Sála-"+suffix,null);var b=f.create("other-"+suffix,"original");
            for(String name:List.of("Sála-"+suffix,"sala-"+suffix,"SALA-"+suffix)){
                assertThrows(StanzaRepository.NomeDuplicatoException.class,()->f.repo().create(value(name,null)));
                assertThrows(StanzaRepository.NomeDuplicatoException.class,()->f.repo().updateAtomically(b.getIdStanza(),value(name,null)));}
            assertEquals("other-"+suffix,f.read(b.getIdStanza()).getNome());assertEquals("original",f.read(b.getIdStanza()).getDescrizione());}
    }
    @ParameterizedTest @EnumSource(StatoPrenotazione.class)
    void referencedRoomUpdatesOnlyMetadataAndPreservesEveryBookingState(StatoPrenotazione state)throws Exception{
        try(var f=new Fixture()){int booking=f.booking(state,LocalDateTime.of(2030, 1, 15, 8, 0).minusDays(1));var before=f.snapshot(booking);var slot=f.slotSnapshot(f.slots.getFirst());
            String name="UC25-"+UUID.randomUUID();var result=f.repo().updateAtomically(f.stanza,value(name,null));assertEquals(f.stanza,result.getIdStanza());assertEquals(name,f.read(f.stanza).getNome());assertNull(f.read(f.stanza).getDescrizione());
            assertEquals(before,f.snapshot(booking));assertEquals(slot,f.slotSnapshot(f.slots.getFirst()));assertEquals(f.stanza,new DisponibilitaRepository(f.factory).findById(f.slots.getFirst()).orElseThrow().getStanza().getIdStanza());
            assertEquals(name,new DisponibilitaRepository(f.factory).findById(f.slots.getFirst()).orElseThrow().getStanza().getNome());}
    }
    @Test void identicalPutAndMissingId()throws Exception{try(var f=new Fixture()){var old=f.read(f.stanza);f.repo().updateAtomically(f.stanza,value(old.getNome(),old.getDescrizione()));assertThrows(RisorsaNonTrovataException.class,()->f.repo().updateAtomically(Integer.MAX_VALUE,value("none",null)));}}
    @Test void injectedCommitFailureRollsBackRealUpdateAndInsert()throws Exception{
        try(var f=new Fixture()){ConnectionFactory broken=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{var c=f.factory.getConnection();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(m.getName().equals("commit"))throw new SQLException("injected commit");try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});}};
            String old=f.read(f.stanza).getNome();String name="UC25-fail-"+UUID.randomUUID();var repo=new StanzaRepository(broken);
            assertThrows(SQLException.class,()->repo.updateAtomically(f.stanza,value(name,null)));assertEquals(old,f.read(f.stanza).getNome());assertThrows(SQLException.class,()->repo.create(value(name,null)));assertTrue(f.repo().findAll().stream().noneMatch(s->s.getNome().equals(name)));}
    }
    @Test void concurrentSameNameCreatesExactlyOneRow()throws Exception{
        try(var f=new Fixture()){String name="UC25-race-"+UUID.randomUUID();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{Callable<Object> task=()->{start.await();try{return f.repo().create(value(name,null));}catch(StanzaRepository.NomeDuplicatoException e){return e;}};
                var a=pool.submit(task);var b=pool.submit(task);start.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Stanza.class::isInstance).count());assertEquals(1,results.stream().filter(StanzaRepository.NomeDuplicatoException.class::isInstance).count());
                var rows=f.repo().findAll().stream().filter(s->s.getNome().equals(name)).toList();assertEquals(1,rows.size());f.extraRooms.add(rows.getFirst().getIdStanza());
            }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void realConnectionPropertiesAndCloseAfterSuccessOrRollback(boolean fail)throws Exception {
        try(var f=new Fixture()) {
            var events=new ArrayList<String>();
            var wrapped=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection()throws SQLException {
                    var c=f.factory.getConnection();int isolation=c.getTransactionIsolation();boolean auto=c.getAutoCommit();
                    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                        if(fail && m.getName().equals("commit"))throw new SQLException("injected commit");
                        if(m.getName().equals("close")) {assertEquals(isolation,c.getTransactionIsolation());assertEquals(auto,c.getAutoCommit());events.add("restored");}
                        try{var result=m.invoke(c,a);if(m.getName().equals("close")){assertTrue(c.isClosed());events.add("closed");}return result;}
                        catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            var repo=new StanzaRepository(wrapped);String old=f.read(f.stanza).getNome();String name="UC25-properties-"+UUID.randomUUID();
            if(fail){assertEquals("injected commit",assertThrows(SQLException.class,()->repo.updateAtomically(f.stanza,value(name,null))).getMessage());assertEquals(old,f.read(f.stanza).getNome());}
            else {repo.updateAtomically(f.stanza,value(name,null));assertEquals(name,f.read(f.stanza).getNome());}
            assertEquals(List.of("restored","closed"),events);
        }
    }
    static void await(CountDownLatch latch)throws Exception{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("Interleaving timeout");}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void putAndConfigurationSerializeInBothPrecedences(boolean putFirst)throws Exception{
        try(var f=new Fixture()){var start=LocalDateTime.of(2030, 1, 15, 8, 0).plusHours(1);int slot=f.freeSlot(start);var before=f.slotSnapshot(slot);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var firstFactory=observeQueries(f.factory,(sql,id,after)->{if(after&&sql.contains("FROM stanza")&&sql.contains("FOR UPDATE")){held.countDown();await(release);}});
            var secondFactory=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.contains("FROM stanza")&&sql.contains("FOR UPDATE"))attempting.countDown();});
            String name="UC25-conf-"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);
            try{var first=pool.submit(()->putFirst?new StanzaRepository(firstFactory).updateAtomically(f.stanza,value(name,"new")):new DisponibilitaRepository(firstFactory).configuraAtomically(slot,f.prestazione,f.stanza));await(held);
                var second=pool.submit(()->putFirst?new DisponibilitaRepository(secondFactory).configuraAtomically(slot,f.prestazione,f.stanza):new StanzaRepository(secondFactory).updateAtomically(f.stanza,value(name,"new")));await(attempting);
                assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));release.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
                var configured=new DisponibilitaRepository(f.factory).findById(slot).orElseThrow();assertEquals(f.stanza,configured.getStanza().getIdStanza());assertEquals(name,configured.getStanza().getNome());assertEquals(start,configured.getDataOraInizio());assertEquals(start.plusHours(1),configured.getDataOraFine());
                var after=f.slotSnapshot(slot);
                assertEquals(6,before.size());assertEquals(before.size(),after.size());
                /* La stanza cambia solo metadati; la configurazione cambia solo le due chiavi esterne. */
                assertEquals(before.subList(0,4),after.subList(0,4),"ID, start, end and owner must all remain unchanged");
                assertEquals(f.prestazione,((Number)after.get(4)).intValue());
                assertEquals(f.stanza,((Number)after.get(5)).intValue());
                int overlap=f.freeSlot(start.plusMinutes(30));assertThrows(ConflittoConfigurazioneException.class,()->new DisponibilitaRepository(f.factory).configuraAtomically(overlap,f.prestazione,f.stanza));
                int adjacent=f.freeSlot(start.plusHours(1));assertTrue(new DisponibilitaRepository(f.factory).configuraAtomically(adjacent,f.prestazione,f.stanza).isConfigurata());
            }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}}
    }
    @Test void twoPutsSerializeAndKeepNameDescriptionFromSameWinner()throws Exception{
        try(var f=new Fixture()){var held=new CountDownLatch(1);var release=new CountDownLatch(1);var trying=new CountDownLatch(1);
            var first=observeQueries(f.factory,(sql,id,after)->{if(after&&sql.contains("FOR UPDATE")){held.countDown();await(release);}});
            var second=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.contains("FOR UPDATE"))trying.countDown();});
            var pool=Executors.newFixedThreadPool(2);String a="UC25-a-"+UUID.randomUUID(),b="UC25-b-"+UUID.randomUUID();
            try{var x=pool.submit(()->new StanzaRepository(first).updateAtomically(f.stanza,value(a,"A")));await(held);var y=pool.submit(()->new StanzaRepository(second).updateAtomically(f.stanza,value(b,"B")));await(trying);assertThrows(TimeoutException.class,()->y.get(150,TimeUnit.MILLISECONDS));release.countDown();x.get(15,TimeUnit.SECONDS);y.get(15,TimeUnit.SECONDS);assertEquals(b,f.read(f.stanza).getNome());assertEquals("B",f.read(f.stanza).getDescrizione());}
            finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}}
    }
}
