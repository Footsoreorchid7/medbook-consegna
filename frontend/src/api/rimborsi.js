import { parseLocalDateTime } from '../utils/datetime.js'
import { ProtocolError } from './http.js'

/**
 * Rimborsi del paziente (UC-16). Identità dal Principal: il corpo contiene solo la motivazione.
 * Il paziente può soltanto richiedere; decisione ed esecuzione non sono funzioni del paziente.
 */

export const STATI_RIMBORSO = Object.freeze(['RICHIESTO', 'APPROVATO', 'RESPINTO', 'IN_ELABORAZIONE', 'RIMBORSATO', 'FALLITO'])
export const MAX_MOTIVAZIONE = 500

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

// Character.isWhitespace di Java: esclude gli spazi non separabili (U+00A0, U+2007, U+202F).
const SOLO_SPAZI_JAVA = /^[\t\n\u000B\f\r\u001C-\u001F \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/
// Character.isISOControl, salvo tabulazione e a capo ammessi dal backend.
const CONTROLLO = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/
const SURROGATO_ISOLATO = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/

/**
 * Stesse regole di RimborsoService.validaMotivazione: non vuota né di soli spazi, al massimo
 * 500 code point, nessun carattere di controllo (salvo \t \r \n) né surrogato isolato.
 * Il testo viene inviato com'è scritto, senza trasformazioni. Il backend resta autorevole.
 * @returns {string|null} messaggio di errore oppure null
 */
export function motivazioneErrore(testo) {
  if (typeof testo !== 'string' || SOLO_SPAZI_JAVA.test(testo)) return 'Indica la motivazione.'
  if ([...testo].length > MAX_MOTIVAZIONE) return `Usa al massimo ${MAX_MOTIVAZIONE} caratteri.`
  if (CONTROLLO.test(testo) || SURROGATO_ISOLATO.test(testo)) return 'La motivazione contiene caratteri non ammessi.'
  return null
}

export const lunghezzaMotivazione = (testo) => [...testo].length

/** Voce di RimborsoResponse: idRimborso, idPagamento e idPrenotazione restano distinti. */
export function parseRimborso(d) {
  if (!isObject(d)) throw new ProtocolError('Rimborso non valido')
  const richiesto = parseLocalDateTime(d.richiestoIl)
  const verificato = d.verificatoIl == null ? null : parseLocalDateTime(d.verificatoIl)
  if (!isId(d.idRimborso) || !isId(d.idPrenotazione) || !isId(d.idPagamento) || !STATI_RIMBORSO.includes(d.stato)
      || typeof d.importo !== 'number' || !Number.isFinite(d.importo) || d.importo <= 0 || typeof d.motivazione !== 'string'
      || !richiesto || (d.verificatoIl != null && !verificato)) {
    throw new ProtocolError('Rimborso non valido')
  }
  return Object.freeze({
    idRimborso: d.idRimborso, idPrenotazione: d.idPrenotazione, idPagamento: d.idPagamento, stato: d.stato,
    importo: d.importo, motivazione: d.motivazione, richiesto, verificato, richiestoChiave: chiaveIstante(d.richiestoIl),
  })
}

/** Istante locale già validato in forma confrontabile: secondi e frazione espliciti (9 cifre). */
function chiaveIstante(valore) {
  const [, minuti, secondi = '00', frazione = ''] = /^(.{16})(?::(\d{2})(?:\.(\d{1,9}))?)?$/.exec(valore)
  return `${minuti}:${secondi}.${frazione.padEnd(9, '0')}`
}

/**
 * Ordine del contratto di GET /api/rimborsi/paziente: richiestoIl decrescente, poi idRimborso
 * decrescente. Usa solo i campi della risposta, mai lo stato della richiesta.
 */
export function confrontaRimborsi(a, b) {
  if (a.richiestoChiave !== b.richiestoChiave) return a.richiestoChiave < b.richiestoChiave ? 1 : -1
  return b.idRimborso - a.idRimborso
}

/** GET /api/rimborsi/paziente: proprie richieste nell'ordine del backend (dalla più recente). */
export async function fetchRimborsi(requestFn) {
  const result = await requestFn({ path: '/api/rimborsi/paziente' })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  return Object.freeze(result.data.map(parseRimborso))
}

/** POST /api/prenotazioni/{id}/rimborso con la sola motivazione. */
export function postRimborso(requestFn, idPrenotazione, motivazione) {
  if (!isId(idPrenotazione) || motivazioneErrore(motivazione) !== null) throw new TypeError('Richiesta di rimborso non valida.')
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/rimborso`, json: { motivazione } })
}

/**
 * Risposta di creazione: 201, stato RICHIESTO, stessa prenotazione, lo stesso pagamento e
 * importo individuati prima dell'invio, motivazione identica e nessuna verifica registrata.
 */
export function parseRimborsoCreato(result, { idPrenotazione, idPagamento, importo, motivazione }) {
  if (result?.status !== 201 || !result.hasBody) throw new ProtocolError('Esito della richiesta di rimborso non conforme')
  const r = parseRimborso(result.data)
  if (r.idPrenotazione !== idPrenotazione || r.idPagamento !== idPagamento || r.importo !== importo
      || r.stato !== 'RICHIESTO' || r.motivazione !== motivazione || r.verificato !== null) {
    throw new ProtocolError('Esito della richiesta di rimborso non conforme')
  }
  return r
}
