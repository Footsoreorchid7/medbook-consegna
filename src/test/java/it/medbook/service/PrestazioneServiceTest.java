package it.medbook.service;

import it.medbook.model.Prestazione;
import it.medbook.dto.PrestazioneRequest;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import it.medbook.repository.PrestazioneRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrestazioneServiceTest {
    private final FakePrestazioneRepository repository = new FakePrestazioneRepository();
    private final PrestazioneService service = new PrestazioneService(repository);

    @Test
    void getAllPrestazioniReturnsRepositoryResults() throws SQLException {
        repository.prestazioni = List.of(new Prestazione(), new Prestazione());

        assertSame(repository.prestazioni, service.getAllPrestazioni());
        assertTrue(repository.findAllCalled);
    }

    @Test
    void getPrestazioneByIdReturnsRepositoryResultForValidId() throws SQLException {
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(7);
        repository.prestazione = Optional.of(prestazione);

        assertSame(repository.prestazione, service.getPrestazioneById(7));
        assertEquals(Integer.valueOf(7), repository.requestedId);
        assertTrue(repository.findByIdCalled);
    }

    @Test
    void getPrestazioneByIdReturnsEmptyWhenNotFound() throws SQLException {
        assertTrue(service.getPrestazioneById(1).isEmpty());
        assertEquals(Integer.valueOf(1), repository.requestedId);
    }

    @Test
    void getPrestazioneByIdRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> service.getPrestazioneById(null));
        assertFalse(repository.findByIdCalled);
    }

    @Test
    void getPrestazioneByIdRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> service.getPrestazioneById(0));
        assertFalse(repository.findByIdCalled);
    }

    @Test
    void getPrestazioneByIdRejectsNegativeId() {
        assertThrows(IllegalArgumentException.class, () -> service.getPrestazioneById(-1));
        assertFalse(repository.findByIdCalled);
    }

    @Test
    void getAllPrestazioniPropagatesRepositoryError() {
        repository.error = new SQLException("Errore di lettura simulato.");

        assertSame(repository.error, assertThrows(SQLException.class, service::getAllPrestazioni));
    }

    @Test
    void getPrestazioneByIdPropagatesRepositoryError() {
        repository.error = new SQLException("Errore di lettura simulato.");

        assertSame(repository.error,
                assertThrows(SQLException.class, () -> service.getPrestazioneById(1)));
    }


    private PrestazioneRequest request() {
        var r=new PrestazioneRequest();r.setNome("  Visita  cardiologica  ");r.setDurata(30);r.setPrezzo(new BigDecimal("85.000"));return r;
    }
    @Test void createAndUpdateNormalizeWithoutChangingInnerSpaces() throws Exception {
        var result=service.creaPrestazione(request());assertEquals("Visita  cardiologica",result.getNome());
        assertEquals(new BigDecimal("85.00"),result.getPrezzo());assertEquals(1,repository.writes);
        var r=request();r.setDescrizione("  testo  ");result=service.aggiornaPrestazione(7,r);
        assertEquals(7,result.getIdPrestazione());assertEquals("  testo  ",result.getDescrizione());assertEquals(2,repository.writes);
    }
    @Test void descriptionsNullEmptyAndTextArePreserved() throws Exception {
        for(String value:new String[]{null,"","testo"}) {var r=request();r.setDescrizione(value);assertEquals(value,service.creaPrestazione(r).getDescrizione());}
    }
    @ParameterizedTest @ValueSource(ints={1,60}) void durationBoundaries(int value) throws Exception {
        var r=request();r.setDurata(value);assertEquals(value,service.creaPrestazione(r).getDurata());
    }
    @ParameterizedTest @ValueSource(strings={"0","99999999.99","85.000"}) void validPrices(String value) throws Exception {
        var r=request();r.setPrezzo(new BigDecimal(value));assertEquals(new BigDecimal(value).setScale(2),service.creaPrestazione(r).getPrezzo());
    }
    @Test void invalidFieldsNeverCallRepository() {
        for(String name:new String[]{null,"","   ","x".repeat(151)}) {var r=request();r.setNome(name);assertThrows(IllegalArgumentException.class,()->service.creaPrestazione(r));}
        for(Integer duration:new Integer[]{null,0,61}) {var r=request();r.setDurata(duration);assertThrows(IllegalArgumentException.class,()->service.creaPrestazione(r));}
        for(String price:new String[]{null,"-0.01","100000000","85.001"}) {var r=request();r.setPrezzo(price==null?null:new BigDecimal(price));assertThrows(IllegalArgumentException.class,()->service.creaPrestazione(r));}
        var r=request();r.setDescrizione("é".repeat(32768));assertThrows(IllegalArgumentException.class,()->service.creaPrestazione(r));
        assertThrows(IllegalArgumentException.class,()->service.creaPrestazione(null));
        for(Integer id:new Integer[]{null,0,-1}) {assertThrows(IllegalArgumentException.class,()->service.aggiornaPrestazione(id,request()));}
        assertEquals(0,repository.writes);
    }
    @Test void writeErrorsArePropagated() {
        repository.error=new SQLException("synthetic");assertSame(repository.error,assertThrows(SQLException.class,()->service.creaPrestazione(request())));
        assertSame(repository.error,assertThrows(SQLException.class,()->service.aggiornaPrestazione(7,request())));
    }
    @ParameterizedTest @ValueSource(strings={"POST", "PUT"})
    void malformedUnicodeNeverReachesRepository(String method) {
        for (char surrogate : new char[]{(char)0xd800, (char)0xdc00}) {
            for (boolean name : new boolean[]{true, false}) {
                var r=request();
                String malformed="test" + surrogate;
                if (name) r.setNome(malformed); else r.setDescrizione(malformed);
                assertThrows(IllegalArgumentException.class, () -> {
                    if (method.equals("POST")) service.creaPrestazione(r); else service.aggiornaPrestazione(7,r);
                });
            }
        }
        assertEquals(0,repository.writes);
    }
    @ParameterizedTest @ValueSource(strings={"POST", "PUT"})
    void validUnicodeIsPreserved(String method) throws Exception {
        for (String text : new String[]{"À漢字", new String(Character.toChars(0x1f600)),
                "BMP é " + new String(Character.toChars(0x10400)) + " 😀"}) {
            var r=request();r.setNome("  " + text + "  ");r.setDescrizione("  " + text + "  ");
            var result=method.equals("POST")?service.creaPrestazione(r):service.aggiornaPrestazione(7,r);
            assertEquals(text,result.getNome());assertEquals("  " + text + "  ",result.getDescrizione());
        }
        assertEquals(3,repository.writes);
    }

    private static class FakePrestazioneRepository extends PrestazioneRepository {
        private List<Prestazione> prestazioni = List.of();
        private Optional<Prestazione> prestazione = Optional.empty();
        private Integer requestedId;
        private boolean findAllCalled;
        private boolean findByIdCalled;
        private SQLException error;
        private int writes;
        @Override public Prestazione create(Prestazione value) throws SQLException { writes++;if(error!=null)throw error;return value; }
        @Override public Prestazione updateAtomically(Integer id,Prestazione value) throws SQLException {value.setIdPrestazione(id);return create(value);}

        FakePrestazioneRepository() {
            // I metodi sono sostituiti dal fake: nessuna ConnectionFactory viene utilizzata.
            super(null);
        }

        @Override
        public List<Prestazione> findAll() throws SQLException {
            findAllCalled = true;
            if (error != null) {
                throw error;
            }
            return prestazioni;
        }

        @Override
        public Optional<Prestazione> findById(Integer idPrestazione) throws SQLException {
            findByIdCalled = true;
            requestedId = idPrestazione;
            if (error != null) {
                throw error;
            }
            return prestazione;
        }
    }
}
