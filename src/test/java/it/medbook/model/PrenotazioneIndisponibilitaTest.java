package it.medbook.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrenotazioneIndisponibilitaTest {
    @Test
    void pendingReportPreventsVisitAndOrdinaryConfirmation() {
        Prenotazione booking = new Prenotazione();
        booking.setStato(StatoPrenotazione.CONFERMATA);
        booking.setIndisponibilitaSegnalata(true);
        assertThrows(IllegalStateException.class, booking::registraVisita);
        assertThrows(IllegalStateException.class, booking::registraAssenzaPaziente);
        assertThrows(IllegalStateException.class, booking::registraMancataErogazione);
        assertEquals(StatoPrenotazione.CONFERMATA, booking.getStato());
        assertTrue(booking.isIndisponibilitaSegnalata());
        booking.setStato(StatoPrenotazione.RICHIESTA);
        assertThrows(IllegalStateException.class, booking::conferma);
        assertEquals(StatoPrenotazione.RICHIESTA, booking.getStato());
    }

    @Test
    void terminalActionsResolveReportWhileNormalVisitStillWorks() {
        Prenotazione booking = new Prenotazione();
        booking.setStato(StatoPrenotazione.RICHIESTA); booking.setIndisponibilitaSegnalata(true);
        booking.rifiuta(); assertFalse(booking.isIndisponibilitaSegnalata());
        booking.setStato(StatoPrenotazione.CONFERMATA); booking.setIndisponibilitaSegnalata(true);
        booking.annulla(); assertFalse(booking.isIndisponibilitaSegnalata());
        booking.setStato(StatoPrenotazione.CONFERMATA);
        booking.registraVisita(); assertEquals(StatoPrenotazione.EFFETTUATA, booking.getStato());
        booking.setStato(StatoPrenotazione.CONFERMATA);
        booking.registraAssenzaPaziente(); assertEquals(StatoPrenotazione.PAZIENTE_ASSENTE, booking.getStato());
        booking.setStato(StatoPrenotazione.CONFERMATA);
        booking.registraMancataErogazione(); assertEquals(StatoPrenotazione.NON_EROGATA, booking.getStato());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void administrativeNonDeliveryResolvesOnlyTheReport(boolean pending) {
        Prenotazione booking = new Prenotazione();
        booking.setStato(StatoPrenotazione.CONFERMATA); booking.setIndisponibilitaSegnalata(pending);
        var doctor = new it.medbook.model.Medico(); var patient = new it.medbook.model.Paziente();
        var slot = new it.medbook.model.Disponibilita(); var operator = new it.medbook.model.Utente();
        var time = java.time.LocalDateTime.of(2030, 1, 1, 12, 0);
        booking.setMedicoEffettivo(doctor); booking.setPaziente(patient); booking.setDisponibilita(slot);
        booking.setGestitaDa(operator); booking.setDataOraGestione(time); booking.setDataOraRichiesta(time.minusDays(1));
        booking.setPrezzo(java.math.BigDecimal.TEN);
        booking.registraMancataErogazioneAmministrativa();
        assertEquals(StatoPrenotazione.NON_EROGATA, booking.getStato()); assertFalse(booking.isIndisponibilitaSegnalata());
        assertSame(doctor, booking.getMedicoEffettivo()); assertSame(patient, booking.getPaziente());
        assertSame(slot, booking.getDisponibilita()); assertSame(operator, booking.getGestitaDa());
        assertEquals(time, booking.getDataOraGestione()); assertEquals(time.minusDays(1), booking.getDataOraRichiesta());
        assertEquals(java.math.BigDecimal.TEN, booking.getPrezzo());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = StatoPrenotazione.class, names = {"CONFERMATA"}, mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void administrativeNonDeliveryRejectsOtherStatesWithoutClearingFlag(StatoPrenotazione state) {
        Prenotazione booking = new Prenotazione(); booking.setStato(state); booking.setIndisponibilitaSegnalata(true);
        assertThrows(IllegalStateException.class, booking::registraMancataErogazioneAmministrativa);
        assertEquals(state, booking.getStato()); assertTrue(booking.isIndisponibilitaSegnalata());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA"},mode=org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void visitRejectsEveryOtherState(StatoPrenotazione state) {
        Prenotazione booking=new Prenotazione();booking.setStato(state);
        assertThrows(IllegalStateException.class,booking::registraVisita);assertEquals(state,booking.getStato());
    }
    @Test void visitPreservesAllOtherAttributes() {
        Prenotazione booking=new Prenotazione();booking.setIdPrenotazione(3);booking.setStato(StatoPrenotazione.CONFERMATA);
        var doctor=new Medico();var patient=new Paziente();var slot=new Disponibilita();var operator=new Utente();
        var now=java.time.LocalDateTime.of(2030,1,1,12,0);
        booking.setMedicoEffettivo(doctor);booking.setPaziente(patient);booking.setDisponibilita(slot);
        booking.setGestitaDa(operator);booking.setDataOraGestione(now);booking.setDataOraRichiesta(now.minusDays(1));booking.setPrezzo(java.math.BigDecimal.TEN);
        booking.registraVisita();assertEquals(StatoPrenotazione.EFFETTUATA,booking.getStato());
        assertEquals(3,booking.getIdPrenotazione());assertSame(doctor,booking.getMedicoEffettivo());assertSame(patient,booking.getPaziente());
        assertSame(slot,booking.getDisponibilita());assertSame(operator,booking.getGestitaDa());assertFalse(booking.isIndisponibilitaSegnalata());
        assertEquals(now,booking.getDataOraGestione());assertEquals(now.minusDays(1),booking.getDataOraRichiesta());assertEquals(java.math.BigDecimal.TEN,booking.getPrezzo());
    }

}
