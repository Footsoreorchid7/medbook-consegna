package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.model.Prestazione;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Connection;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import it.medbook.model.StatoPrenotazione;
import it.medbook.exception.*;
import it.medbook.config.TestDatabaseConnections;
import static org.junit.jupiter.api.Assertions.*;
import static it.medbook.repository.PrenotazioneIndisponibilitaIntegrationTest.observeQueries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrestazioneRepositoryIntegrationTest {
    @Test
    void readsAllPrestazioniAndFindsFirstById() throws SQLException {

        PrestazioneRepository repository = new PrestazioneRepository(it.medbook.config.TestDatabaseConnections.isolatedFactory());
        List<Prestazione> prestazioni = repository.findAll();
        assertNotNull(prestazioni);

        if (!prestazioni.isEmpty()) {
            Integer idPrestazione = prestazioni.getFirst().getIdPrestazione();
            Optional<Prestazione> trovata = repository.findById(idPrestazione);
            assertTrue(trovata.isPresent());
            assertEquals(idPrestazione, trovata.get().getIdPrestazione());
        }
    }

    static final LocalDateTime NOW=LocalDateTime.of(2030,1,15,8,0);
    static final Clock CLOCK=Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
    static Prestazione value(int duration,String price) {
        var p=new Prestazione();p.setNome("Catalogo UC24");p.setDescrizione(null);p.setDurata(duration);p.setPrezzo(new BigDecimal(price));return p;
    }
    static class Fixture extends PrenotazioneIndisponibilitaIntegrationTest.Fixture {
        Fixture()throws Exception{super();}
        PrestazioneRepository catalog(){return new PrestazioneRepository(factory);}
        int slot(boolean configured) throws Exception {
            int id=insert("INSERT INTO disponibilita (id_medico,id_prestazione,id_stanza,data_ora_inizio,data_ora_fine) VALUES (?,?,?,?,?)",owner,configured?prestazione:null,configured?stanza:null,NOW.plusHours(1),NOW.plusHours(2));slots.add(id);return id;
        }
    }
    @Test void createUsesGeneratedKeyAndNullEmptyDescriptions() throws Exception {
        try(Fixture f=new Fixture()) {
            for(String description:new String[]{null,"","  descrizione  "}) {
                var input=value(1,"0.00");input.setDescrizione(description);var created=f.catalog().create(input);
                try {assertTrue(created.getIdPrestazione()>0);var persisted=f.catalog().findById(created.getIdPrestazione()).orElseThrow();
                    assertEquals(description,persisted.getDescrizione());assertEquals(1,persisted.getDurata());assertEquals(new BigDecimal("0.00"),persisted.getPrezzo());}
                finally{f.execute("DELETE FROM prestazione WHERE id_prestazione=?",created.getIdPrestazione());}
            }
        }
    }
    @Test void durationCanChangeWithOnlyMedicalQualificationsAndIdenticalPutSucceeds() throws Exception {
        try(Fixture f=new Fixture()) {for(int duration:new int[]{30,60,1,1}) {
            var result=f.catalog().updateAtomically(f.prestazione,value(duration,"85.00"));assertEquals(duration,result.getDurata());
            assertEquals(duration,f.catalog().findById(f.prestazione).orElseThrow().getDurata());
        }}
    }
    @Test void freeConfiguredSlotBlocksDurationChangeButAllowsOtherFields()throws Exception {
        try(Fixture f=new Fixture()) {f.slot(true);
            assertThrows(ConflittoConfigurazioneException.class,()->f.catalog().updateAtomically(f.prestazione,value(30,"85.00")));
            assertEquals(new BigDecimal("50.00"),f.catalog().findById(f.prestazione).orElseThrow().getPrezzo());
            var changed=f.catalog().updateAtomically(f.prestazione,value(60,"85.00"));assertEquals("Catalogo UC24",changed.getNome());assertNull(changed.getDescrizione());
        }
    }
    @ParameterizedTest @EnumSource(StatoPrenotazione.class)
    void everyBookingStateBlocksDurationAndKeepsHistoricalPrice(StatoPrenotazione state)throws Exception {
        try(Fixture f=new Fixture()) {int booking=f.booking(state,NOW.minusDays(1));var before=f.snapshot(booking);
            assertThrows(ConflittoConfigurazioneException.class,()->f.catalog().updateAtomically(f.prestazione,value(30,"90.00")));
            assertEquals(60,f.catalog().findById(f.prestazione).orElseThrow().getDurata());
            f.catalog().updateAtomically(f.prestazione,value(60,"90.00"));assertEquals(before,f.snapshot(booking));
            assertEquals(new BigDecimal("90.00"),f.catalog().findById(f.prestazione).orElseThrow().getPrezzo());
        }
    }
    @Test void futureBookingAlsoBlocksDurationAndMissingIdIsNotFound()throws Exception {
        try(Fixture f=new Fixture()) {f.booking(StatoPrenotazione.RICHIESTA,NOW.plusDays(1));
            assertThrows(ConflittoConfigurazioneException.class,()->f.catalog().updateAtomically(f.prestazione,value(30,"50.00")));
            assertThrows(RisorsaNonTrovataException.class,()->f.catalog().updateAtomically(Integer.MAX_VALUE,value(30,"50.00")));
        }
    }
    @Test void sqlChecksRejectInvalidDirectRepositoryInputsWithoutPartialWrite()throws Exception {
        try(Fixture f=new Fixture()) {for(var v:List.of(value(0,"10.00"),value(61,"10.00"),value(30,"-1.00"))) {
            assertThrows(SQLException.class,()->f.catalog().create(v));
            assertThrows(SQLException.class,()->f.catalog().updateAtomically(f.prestazione,v));
            assertEquals(new BigDecimal("50.00"),f.catalog().findById(f.prestazione).orElseThrow().getPrezzo());
        }}
    }
    @Test void commitFailureReallyRollsBackCatalog()throws Exception {
        try(Fixture f=new Fixture()) {
            var broken=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection()throws SQLException {
                    var connection=f.factory.getConnection();return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("commit"))throw new SQLException("synthetic commit failure");
                        try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            assertThrows(SQLException.class,()->new PrestazioneRepository(broken).updateAtomically(f.prestazione,value(30,"85.00")));
            var after=f.catalog().findById(f.prestazione).orElseThrow();assertEquals(60,after.getDurata());assertEquals(new BigDecimal("50.00"),after.getPrezzo());
        }
    }
    private static void await(CountDownLatch latch)throws SQLException {
        try{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("Controlled interleaving timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}
    }
    private static void blocked(Future<?> result)throws Exception {
        assertThrows(TimeoutException.class,()->result.get(150,TimeUnit.MILLISECONDS));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void configurationAndPutSerializeInBothOrders(boolean putFirst)throws Exception {
        try(Fixture f=new Fixture()) {int slot=f.slot(false);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var firstFactory=observeQueries(f.factory,(sql,id,after)->{
                if(after&&sql.equals(putFirst?"SELECT durata FROM prestazione WHERE id_prestazione = ? FOR UPDATE":"SELECT durata FROM prestazione WHERE id_prestazione = ? FOR SHARE")){held.countDown();await(release);}
            });
            var secondFactory=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.contains("FROM prestazione")&&sql.contains("FOR "))attempting.countDown();});
            var workers=Executors.newFixedThreadPool(2);
            try{
                var first=workers.submit(()->putFirst?new PrestazioneRepository(firstFactory).updateAtomically(f.prestazione,value(30,"50.00")):new DisponibilitaRepository(firstFactory).configuraAtomically(slot,f.prestazione,f.stanza));
                assertTrue(held.await(10,TimeUnit.SECONDS));
                var second=workers.submit(()->putFirst?new DisponibilitaRepository(secondFactory).configuraAtomically(slot,f.prestazione,f.stanza):new PrestazioneRepository(secondFactory).updateAtomically(f.prestazione,value(30,"85.00")));
                assertTrue(attempting.await(10,TimeUnit.SECONDS));blocked(second);release.countDown();first.get(15,TimeUnit.SECONDS);
                if(putFirst)second.get(15,TimeUnit.SECONDS);else assertInstanceOf(ConflittoConfigurazioneException.class,assertThrows(ExecutionException.class,()->second.get(15,TimeUnit.SECONDS)).getCause());
                assertEquals(putFirst?30:60,f.catalog().findById(f.prestazione).orElseThrow().getDurata());assertEquals(f.prestazione,new DisponibilitaRepository(f.factory).findById(slot).orElseThrow().getPrestazione().getIdPrestazione());
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void pricePutAgainstBookingProducesCoherentSnapshot(boolean putFirst)throws Exception {
        try(Fixture f=new Fixture()) {int slot=f.slot(true);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var firstFactory=observeQueries(f.factory,(sql,id,after)->{if(after&&sql.contains("FROM prestazione")&&sql.contains("FOR ")){held.countDown();await(release);}});
            var secondFactory=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.contains("FROM prestazione")&&sql.contains("FOR "))attempting.countDown();});
            var workers=Executors.newFixedThreadPool(2);
            try {
                Future<?> first=workers.submit(()->putFirst?new PrestazioneRepository(firstFactory).updateAtomically(f.prestazione,value(60,"85.00")):new PrenotazioneRepository(firstFactory,CLOCK).creaRichiestaAtomically(f.patient,slot));
                assertTrue(held.await(10,TimeUnit.SECONDS));
                Future<?> second=workers.submit(()->putFirst?new PrenotazioneRepository(secondFactory,CLOCK).creaRichiestaAtomically(f.patient,slot):new PrestazioneRepository(secondFactory).updateAtomically(f.prestazione,value(60,"85.00")));
                assertTrue(attempting.await(10,TimeUnit.SECONDS));blocked(second);release.countDown();Object a=first.get(15,TimeUnit.SECONDS),b=second.get(15,TimeUnit.SECONDS);
                var booking=(it.medbook.model.Prenotazione)(putFirst?b:a);f.bookings.add(booking.getIdPrenotazione());
                assertEquals(new BigDecimal(putFirst?"85.00":"50.00"),booking.getPrezzo());
                try(var c=f.factory.getConnection();var ps=c.prepareStatement("SELECT prezzo FROM prenotazione WHERE id_prenotazione=?")) {
                    ps.setInt(1,booking.getIdPrenotazione());try(var rows=ps.executeQuery()) {
                        assertTrue(rows.next());assertEquals(new BigDecimal(putFirst?"85.00":"50.00"),rows.getBigDecimal("prezzo"));assertFalse(rows.next());
                    }
                }
                assertEquals(new BigDecimal("85.00"),f.catalog().findById(f.prestazione).orElseThrow().getPrezzo());
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }
    @Test void durationPutAgainstBookingConflictsWithoutLockInversion()throws Exception {
        try(Fixture f=new Fixture()) {int slot=f.slot(true);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var putFactory=observeQueries(f.factory,(sql,id,after)->{if(after&&sql.endsWith("FOR UPDATE")&&sql.contains("FROM prestazione")){held.countDown();await(release);}});
            var bookingFactory=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.contains("FROM prestazione")&&sql.endsWith("FOR SHARE"))attempting.countDown();});
            var workers=Executors.newFixedThreadPool(2);
            try {var put=workers.submit(()->new PrestazioneRepository(putFactory).updateAtomically(f.prestazione,value(30,"85.00")));assertTrue(held.await(10,TimeUnit.SECONDS));
                var booking=workers.submit(()->new PrenotazioneRepository(bookingFactory,CLOCK).creaRichiestaAtomically(f.patient,slot));assertTrue(attempting.await(10,TimeUnit.SECONDS));blocked(booking);release.countDown();
                assertInstanceOf(ConflittoConfigurazioneException.class,assertThrows(ExecutionException.class,()->put.get(15,TimeUnit.SECONDS)).getCause());var result=booking.get(15,TimeUnit.SECONDS);f.bookings.add(result.getIdPrenotazione());assertEquals(new BigDecimal("50.00"),result.getPrezzo());
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }
    @Test void putAgainstReplacementPreservesClinicalAssignment()throws Exception {
        try(Fixture f=new Fixture()) {int id=f.booking(StatoPrenotazione.RICHIESTA,NOW.plusHours(1));f.execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",id);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var putFactory=observeQueries(f.factory,(sql,key,after)->{if(after&&sql.endsWith("FOR UPDATE")&&sql.contains("FROM prestazione")){held.countDown();await(release);}});
            var replaceFactory=observeQueries(f.factory,(sql,key,after)->{if(!after&&sql.contains("FROM prestazione")&&sql.contains("FOR SHARE"))attempting.countDown();});
            var workers=Executors.newFixedThreadPool(2);
            try {var put=workers.submit(()->new PrestazioneRepository(putFactory).updateAtomically(f.prestazione,value(60,"85.00")));assertTrue(held.await(10,TimeUnit.SECONDS));
                var replacement=workers.submit(()->new PrenotazioneRepository(replaceFactory,CLOCK).sostituisciMedicoAtomically(id,f.operator,f.effective,StatoPrenotazione.RICHIESTA,f.owner));
                assertTrue(attempting.await(10,TimeUnit.SECONDS));blocked(replacement);release.countDown();put.get(15,TimeUnit.SECONDS);var result=replacement.get(15,TimeUnit.SECONDS);
                assertEquals(f.effective,result.getIdMedicoEffettivo());assertFalse(result.isIndisponibilitaSegnalata());
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }
    @Test void simultaneousPutsSerializeAndLastCompleteReplacementWins()throws Exception {
        try(Fixture f=new Fixture()) {var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
            var firstFactory=observeQueries(f.factory,(sql,id,after)->{if(after&&sql.endsWith("FOR UPDATE")){held.countDown();await(release);}});
            var secondFactory=observeQueries(f.factory,(sql,id,after)->{if(!after&&sql.endsWith("FOR UPDATE"))attempting.countDown();});
            var workers=Executors.newFixedThreadPool(2);
            try{var first=workers.submit(()->new PrestazioneRepository(firstFactory).updateAtomically(f.prestazione,value(30,"85.00")));assertTrue(held.await(10,TimeUnit.SECONDS));
                var second=workers.submit(()->new PrestazioneRepository(secondFactory).updateAtomically(f.prestazione,value(45,"95.00")));assertTrue(attempting.await(10,TimeUnit.SECONDS));blocked(second);release.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
                var result=f.catalog().findById(f.prestazione).orElseThrow();assertEquals(45,result.getDurata());assertEquals(new BigDecimal("95.00"),result.getPrezzo());
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }
}
