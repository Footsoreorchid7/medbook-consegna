// Dati sintetici per i test nessuna identità reale.
import { OPERATORE } from './b7-fixtures.js'

export const GIORNO = '2026-11-05'

/** Voce di GET /api/prenotazioni/desk: CONFERMATA gestita dall'operatore, medico effettivo = titolare 4. */
export const prenotazioneDesk = (id, extra = {}) => ({
  idPrenotazione: id,
  stato: 'CONFERMATA',
  indisponibilitaSegnalata: false,
  prezzo: 80,
  idDisponibilita: 200 + id,
  dataOraInizio: `${GIORNO}T09:00:00`,
  dataOraFine: `${GIORNO}T10:00:00`,
  idPaziente: 70 + id,
  nomePaziente: 'Paola',
  cognomePaziente: `Prova${id}`,
  idMedicoTitolare: 4,
  nomeMedicoTitolare: 'Elena',
  cognomeMedicoTitolare: 'Sintetica',
  idMedicoEffettivo: 4,
  nomeMedicoEffettivo: 'Elena',
  cognomeMedicoEffettivo: 'Sintetica',
  idPrestazione: 2,
  nomePrestazione: 'Visita sintetica',
  idStanza: 3,
  nomeStanza: 'Ambulatorio 1',
  gestitaDa: OPERATORE,
  dataOraGestione: '2026-10-20T10:00:00',
  pagamenti: [],
  rimborsi: [],
  ...extra,
})

/** ANNULLATA da una RICHIESTA: nessun medico effettivo né gestione. */
export const annullataDaRichiesta = (id, extra = {}) => prenotazioneDesk(id, {
  stato: 'ANNULLATA', idMedicoEffettivo: null, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: null,
  gestitaDa: null, dataOraGestione: null, ...extra,
})

export const pagamentoDesk = (id, extra = {}) => ({
  idPagamento: id, modalita: 'ONLINE', importo: 80, stato: 'FALLITO', creatoIl: '2026-10-21T08:00:00', pagatoIl: null, registratoDa: null, ...extra,
})
export const pagatoOnline = (id, extra = {}) => pagamentoDesk(id, { stato: 'PAGATO', pagatoIl: '2026-10-21T08:00:00', ...extra })
export const pagatoSede = (id, extra = {}) => pagamentoDesk(id, {
  modalita: 'DESK_CONTANTI', stato: 'PAGATO', pagatoIl: '2026-11-05T08:50:00', creatoIl: '2026-11-05T08:50:00', registratoDa: OPERATORE, ...extra,
})
export const rimborsoAnnidato = (id, idPagamento, extra = {}) => ({
  idRimborso: id, idPagamento, stato: 'RICHIESTO', importo: 80, richiestoIl: '2026-11-06T09:00:00', verificatoIl: null, verificatoDa: null, ...extra,
})

/** Risposta 201 di POST pagamento-sede. */
export const incasso = (idPrenotazione, extra = {}) => ({
  idPagamento: 900, idPrenotazione, modalita: 'DESK_POS', importo: 80, stato: 'PAGATO', idOperatore: OPERATORE,
  creatoIl: '2026-11-05T08:55:00', pagatoIl: '2026-11-05T08:55:00', ...extra,
})

export const ELENCO = (query = `data=${GIORNO}`) => `GET /api/prenotazioni/desk?${query}`
export const INCASSO = (id) => `POST /api/prenotazioni/${id}/pagamento-sede`
export const ESITO = (id) => `POST /api/prenotazioni/${id}/verifica-mancata-erogazione`
