// Dati sintetici per i test nessuna identità reale.
export const appuntamento = (id, extra = {}) => ({
  idPrenotazione: id, stato: 'RICHIESTA', dataOraRichiesta: '2026-10-15T20:10:11.123456', prezzo: 80, idDisponibilita: 10,
  dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idPrestazione: 1, nomePrestazione: 'Visita cardiologica',
  durataPrestazione: 30, idMedicoTitolare: 4, nomeMedicoTitolare: 'Elena', cognomeMedicoTitolare: 'Sintetica',
  idMedicoEffettivo: null, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: null, idStanza: null, nomeStanza: null, ...extra,
})
