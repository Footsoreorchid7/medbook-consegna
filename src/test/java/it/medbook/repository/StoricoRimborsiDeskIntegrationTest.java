package it.medbook.repository;

import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.StatoRimborso;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StoricoRimborsiDeskIntegrationTest {
    final RimborsoRepositoryIntegrationTest f = new RimborsoRepositoryIntegrationTest();
    @BeforeEach void setup() throws Exception { f.setup(); }
    @AfterEach void cleanup() throws Exception { f.cleanup(); }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void decisionIsPersistentAndFilterableWithoutEconomicChanges(boolean approva)throws Exception {
        int id=f.request();
        var prenotazioni=f.snapshot("prenotazione");var pagamenti=f.snapshot("pagamento");
        assertTrue(f.repo().findStoricoDesk(f.desk,"RICHIESTO").stream().anyMatch(r->r.getIdRimborso()==id));
        f.repo().decidiAtomically(id,f.desk,approva);
        String stato=approva?"APPROVATO":"RESPINTO";
        var righe=f.repo().findStoricoDesk(f.desk,stato).stream().filter(r->r.getIdRimborso()==id).toList();
        assertEquals(1,righe.size());var r=righe.getFirst();
        assertEquals(StatoRimborso.valueOf(stato),r.getStato());assertEquals(f.desk,r.getIdOperatore());
        assertEquals(RimborsoRepositoryIntegrationTest.NOW,r.getVerificatoIl());
        assertEquals("FixtureUC16",r.getNomeOperatore());assertEquals(f.patient,r.getIdPaziente());
        assertEquals(RimborsoRepositoryIntegrationTest.MOTIVE,r.getMotivazione());
        assertTrue(f.repo().findRichieste().stream().noneMatch(x->x.getIdRimborso()==id));
        assertTrue(f.repo().findStoricoDesk(f.desk,"TUTTI").stream().anyMatch(x->x.getIdRimborso()==id));
        assertEquals(prenotazioni,f.snapshot("prenotazione"));assertEquals(pagamenti,f.snapshot("pagamento"));
    }
    @ParameterizedTest @ValueSource(strings={"APPROVATO","RESPINTO","IN_ELABORAZIONE","RIMBORSATO","FALLITO"})
    void allIncludesHistoricalStatesWithUnavailableDecisionMetadata(String stato)throws Exception {
        int id=f.request();f.execute("UPDATE rimborso SET stato=?,rimborsato_il=? WHERE id_rimborso=?",stato,stato.equals("RIMBORSATO")?RimborsoRepositoryIntegrationTest.NOW:null,id);
        var r=f.repo().findStoricoDesk(f.desk,"TUTTI").stream().filter(x->x.getIdRimborso()==id).findFirst().orElseThrow();
        assertEquals(stato,r.getStato().name());assertNull(r.getIdOperatore());assertNull(r.getVerificatoIl());
    }
    @Test void orderingIsStableAndOnlyActiveDeskCanRead()throws Exception {
        int id=f.request();int secondo=f.pay("ONLINE","FALLITO",new java.math.BigDecimal("80"));
        int altro=f.insert("INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione) VALUES (?,'RESPINTO',80,?,'test')",secondo,RimborsoRepositoryIntegrationTest.NOW);
        var righe=f.repo().findStoricoDesk(f.desk,"TUTTI").stream().filter(r->r.getIdRimborso()==id||r.getIdRimborso()==altro).toList();
        assertEquals(java.util.List.of(id,altro),righe.stream().map(r->r.getIdRimborso()).toList());
        for(String ruolo:new String[]{null,"AMMINISTRATORE"}) {
            f.execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",ruolo,f.desk);
            assertThrows(OperazioneNonAutorizzataException.class,()->f.repo().findStoricoDesk(f.desk,"TUTTI"));
        }
        f.execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK',stato_accesso='DISABILITATO' WHERE id_utente=?",f.desk);
        assertThrows(OperazioneNonAutorizzataException.class,()->f.repo().findStoricoDesk(f.desk,"TUTTI"));
    }
}
