import { isLocalDate, parseLocalDateTime } from '../utils/datetime.js'
import { ProtocolError } from './http.js'

/**
 * API del paziente per le proprie prenotazioni . Ogni funzione riceve la funzione di
 * richiesta protetta della sessione: identità, CSRF ed epoca arrivano da lì, mai dalla URL
 * o da dati inseriti dall'utente. Nessun nuovo invio automatico.
 */

export const STATI = Object.freeze(['RICHIESTA', 'CONFERMATA', 'RIFIUTATA', 'ANNULLATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA'])

/** Stati per cui il backend accetta `statoAtteso` nell'annullamento del paziente. */
export const STATI_ANNULLABILI = Object.freeze(['RICHIESTA', 'CONFERMATA'])

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isAmount = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/**
 * ID di prenotazione da un parametro di rotta: solo cifre decimali canoniche (nessuno zero
 * iniziale, segno o codifica) entro l'Integer Java. Restituisce il numero oppure null.
 */
export function parseIdRisorsa(raw) {
  if (typeof raw !== 'string' || !/^[1-9][0-9]{0,9}$/.test(raw)) return null
  const id = Number(raw)
  return isId(id) ? id : null
}

/** Data accettata dal filtro del backend: anno 1000–9999 e limite superiore rappresentabile. */
function dataFiltroValida(value) {
  return isLocalDate(value) && Number(value.slice(0, 4)) >= 1000 && value !== '9999-12-31'
}

const vuoto = (v) => v === null || v === undefined || v === ''

/**
 * Query di GET /api/prenotazioni/paziente: idPaziente della sessione e soli filtri valorizzati.
 * `data` esclude l'intervallo; `dataDa` e `dataA` vanno insieme e ordinati. Combinazioni
 * incompatibili o valori non validi sono rifiutati prima della richiesta.
 */
export function prenotazioniPazientePath({ idPaziente, stato = null, data = null, dataDa = null, dataA = null } = {}) {
  if (!isId(idPaziente)) throw new TypeError('Paziente non valido.')
  const query = new URLSearchParams({ idPaziente: String(idPaziente) })
  if (!vuoto(stato)) {
    if (!STATI.includes(stato)) throw new TypeError('Stato non valido.')
    query.set('stato', stato)
  }
  if (!vuoto(data) && (!vuoto(dataDa) || !vuoto(dataA))) throw new TypeError('Data singola e intervallo sono alternativi.')
  if (vuoto(dataDa) !== vuoto(dataA)) throw new TypeError("L'intervallo richiede entrambe le date.")
  if (!vuoto(data)) {
    if (!dataFiltroValida(data)) throw new TypeError('Data non valida.')
    query.set('data', data)
  }
  if (!vuoto(dataDa)) {
    if (!dataFiltroValida(dataDa) || !dataFiltroValida(dataA)) throw new TypeError('Data non valida.')
    if (dataDa > dataA) throw new TypeError('Intervallo non ordinato.')
    query.set('dataDa', dataDa)
    query.set('dataA', dataA)
  }
  return `/api/prenotazioni/paziente?${query.toString()}`
}

function nomeOpzionale(id, nome, cognome) {
  if (id === null || id === undefined) {
    if (!(nome == null && cognome == null)) throw new ProtocolError('Medico non coerente')
    return null
  }
  if (!isId(id) || !isText(nome) || !isText(cognome)) throw new ProtocolError('Medico non valido')
  return Object.freeze({ id, nome, cognome })
}

/**
 * Voce di AppuntamentoPazienteResponse. Prestazione, stanza e medico effettivo possono
 * mancare (null): il backend li legge con LEFT JOIN o li assegna solo alla conferma.
 */
export function parseAppuntamento(d) {
  if (!isObject(d)) throw new ProtocolError('Prenotazione non valida')
  const inizio = parseLocalDateTime(d.dataOraInizio)
  const fine = parseLocalDateTime(d.dataOraFine)
  const richiesta = parseLocalDateTime(d.dataOraRichiesta)
  if (!isId(d.idPrenotazione) || !STATI.includes(d.stato) || !inizio || !fine || !richiesta
      || !isAmount(d.prezzo) || !isId(d.idDisponibilita)
      || !(d.idPrestazione == null || isId(d.idPrestazione))
      || !(d.nomePrestazione == null || isText(d.nomePrestazione))
      || !(d.durataPrestazione == null || (Number.isSafeInteger(d.durataPrestazione) && d.durataPrestazione > 0))
      || !(d.idStanza == null || isId(d.idStanza))
      || !(d.nomeStanza == null || isText(d.nomeStanza))) {
    throw new ProtocolError('Prenotazione non valida')
  }
  const titolare = nomeOpzionale(d.idMedicoTitolare, d.nomeMedicoTitolare, d.cognomeMedicoTitolare)
  if (!titolare) throw new ProtocolError('Medico titolare assente')
  return Object.freeze({
    idPrenotazione: d.idPrenotazione,
    stato: d.stato,
    richiesta,
    prezzo: d.prezzo,
    idDisponibilita: d.idDisponibilita,
    inizio,
    fine,
    nomePrestazione: d.nomePrestazione ?? null,
    durata: d.durataPrestazione ?? null,
    titolare,
    effettivo: nomeOpzionale(d.idMedicoEffettivo, d.nomeMedicoEffettivo, d.cognomeMedicoEffettivo),
    stanza: d.idStanza == null ? null : Object.freeze({ id: d.idStanza, nome: d.nomeStanza ?? null }),
  })
}

/** GET /api/prenotazioni/paziente: elenco nell'ordine del backend, ogni voce validata. */
export async function fetchPrenotazioniPaziente(requestFn, filtri) {
  const result = await requestFn({ path: prenotazioniPazientePath(filtri) })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  return Object.freeze(result.data.map(parseAppuntamento))
}

/** POST /api/prenotazioni con l'identità della sessione e lo slot scelto. */
export function postPrenotazione(requestFn, idPaziente, idDisponibilita) {
  if (!isId(idPaziente) || !isId(idDisponibilita)) throw new TypeError('Identificativi non validi.')
  return requestFn({ method: 'POST', path: '/api/prenotazioni', json: { idPaziente, idDisponibilita } })
}

/**
 * Risposta di creazione: status esattamente 201, stato RICHIESTA, stesso slot richiesto e
 * dati minimi del riepilogo. Ogni difformità è un errore di protocollo (esito incerto).
 */
export function parsePrenotazioneCreata(result, idDisponibilita) {
  const d = result?.data
  if (result?.status !== 201 || !result.hasBody || !isObject(d)) throw new ProtocolError('Esito della richiesta non conforme')
  const inizio = parseLocalDateTime(d.dataOraInizio)
  const fine = parseLocalDateTime(d.dataOraFine)
  if (!isId(d.idPrenotazione) || d.stato !== 'RICHIESTA' || d.idDisponibilita !== idDisponibilita || !inizio || !fine
      || !isAmount(d.prezzo) || !isText(d.nomePrestazione) || !isText(d.nomeMedico) || !isText(d.cognomeMedico)) {
    throw new ProtocolError('Esito della richiesta non conforme')
  }
  return Object.freeze({
    idPrenotazione: d.idPrenotazione,
    stato: d.stato,
    idDisponibilita: d.idDisponibilita,
    inizio,
    fine,
    prezzo: d.prezzo,
    nomePrestazione: d.nomePrestazione,
    nomeMedico: d.nomeMedico,
    cognomeMedico: d.cognomeMedico,
  })
}

/** POST /api/prenotazioni/{id}/annulla-paziente con lo stato letto dalla risorsa. */
export function postAnnullamento(requestFn, idPrenotazione, idPaziente, statoAtteso) {
  if (!isId(idPrenotazione) || !isId(idPaziente) || !STATI_ANNULLABILI.includes(statoAtteso)) {
    throw new TypeError('Annullamento non valido.')
  }
  return requestFn({
    method: 'POST',
    path: `/api/prenotazioni/${idPrenotazione}/annulla-paziente`,
    json: { idPaziente, statoAtteso },
  })
}

/** Risposta di annullamento: 200, stessa prenotazione, stato ANNULLATA. */
export function parseAnnullamento(result, idPrenotazione) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idPrenotazione !== idPrenotazione || d.stato !== 'ANNULLATA') {
    throw new ProtocolError("Esito dell'annullamento non conforme")
  }
  return Object.freeze({ idPrenotazione: d.idPrenotazione, stato: d.stato })
}
