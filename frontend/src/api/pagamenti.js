import { parseLocalDateTime } from '../utils/datetime.js'
import { ProtocolError } from './http.js'

/**
 * Pagamenti del paziente (UC-08). L'identità è il Principal della sessione: il backend non
 * accetta un ID paziente nel corpo. Il pagamento online è simulato: nessun denaro reale.
 */

export const MODALITA = Object.freeze(['ONLINE', 'DESK_POS', 'DESK_CONTANTI'])
export const STATI_PAGAMENTO = Object.freeze(['IN_ATTESA', 'PAGATO', 'FALLITO'])
/** Stati che, secondo il backend, impediscono un nuovo pagamento online. */
export const STATI_BLOCCANTI = Object.freeze(['IN_ATTESA', 'PAGATO'])

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/** Importo accettato come `importoAtteso`: positivo, al massimo due decimali, entro DECIMAL(10,2). */
export function importoValido(v) {
  return typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 99999999.99 && Number(v.toFixed(2)) === v
}

function idPrenotazioneValido(id) {
  if (!isId(id)) throw new TypeError('Prenotazione non valida.')
  return id
}

/**
 * Voce di PagamentoPubblicoResponse, coerente con i vincoli dello schema: importo positivo,
 * `pagatoIl` presente se e solo se lo stato è PAGATO, stessa prenotazione richiesta.
 */
export function parsePagamento(d, idPrenotazione) {
  if (!isObject(d)) throw new ProtocolError('Pagamento non valido')
  const creato = parseLocalDateTime(d.creatoIl)
  const pagato = d.pagatoIl == null ? null : parseLocalDateTime(d.pagatoIl)
  if (!isId(d.idPagamento) || d.idPrenotazione !== idPrenotazione || !MODALITA.includes(d.modalita)
      || !STATI_PAGAMENTO.includes(d.stato) || typeof d.importo !== 'number' || !Number.isFinite(d.importo) || d.importo <= 0
      || !creato || (d.pagatoIl != null && !pagato) || (d.stato === 'PAGATO') !== (pagato !== null)) {
    throw new ProtocolError('Pagamento non valido')
  }
  return Object.freeze({
    idPagamento: d.idPagamento, idPrenotazione: d.idPrenotazione, modalita: d.modalita,
    importo: d.importo, stato: d.stato, creato, pagato,
  })
}

/** GET /api/prenotazioni/{id}/pagamenti: elenco nell'ordine del backend (dal più recente). */
export async function fetchPagamenti(requestFn, idPrenotazione) {
  const id = idPrenotazioneValido(idPrenotazione)
  const result = await requestFn({ path: `/api/prenotazioni/${id}/pagamenti` })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  return Object.freeze(result.data.map((d) => parsePagamento(d, id)))
}

/** POST /api/prenotazioni/{id}/pagamento-online con il solo `importoAtteso` (prezzo storico). */
export function postPagamentoOnline(requestFn, idPrenotazione, importoAtteso) {
  const id = idPrenotazioneValido(idPrenotazione)
  if (!importoValido(importoAtteso)) throw new TypeError('Importo non valido.')
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${id}/pagamento-online`, json: { importoAtteso } })
}

/**
 * Esito del pagamento simulato. Un 201 non equivale a un pagamento riuscito: lo stato può
 * essere PAGATO o FALLITO. Si richiedono 201, stessa prenotazione, modalità ONLINE, importo
 * uguale a quello atteso e uno dei due stati terminali; ogni difformità è un esito incerto.
 */
export function parsePagamentoOnline(result, idPrenotazione, importoAtteso) {
  if (result?.status !== 201 || !result.hasBody || !isObject(result.data)) throw new ProtocolError('Esito del pagamento non conforme')
  const p = parsePagamento(result.data, idPrenotazione)
  if (p.modalita !== 'ONLINE' || p.importo !== importoAtteso || (p.stato !== 'PAGATO' && p.stato !== 'FALLITO')) {
    throw new ProtocolError('Esito del pagamento non conforme')
  }
  return p
}
