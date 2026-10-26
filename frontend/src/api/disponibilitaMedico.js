import { ProtocolError } from './http.js'

/**
 * Disponibilità del medico : elenco personale, creazione, modifica dell'orario ed
 * eliminazione. `medicoId`/`idMedico` è sempre l'identità della sessione; il backend lo
 * confronta con la sessione. La configurazione (prestazione e stanza) è attività desk e non
 * è esposta qui.
 *
 * Orari: LocalDateTime della struttura, senza fuso. Sono trattati come testo normalizzato
 * "yyyy-mm-ddThh:mm:ss" e confrontati con aritmetica pura di calendario (UTC solo come
 * calendario astratto, come fa il backend con LocalDateTime): nessuna conversione dal fuso del
 * browser. Che l'inizio sia futuro lo decide il backend con il proprio orologio.
 */

/** SEC-01: massimo numero di slot da 60 minuti per richiesta di creazione. */
export const MAX_SLOT = 1000
export const ORA = 3600

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

// Con secondi facoltativi; la frazione è ammessa solo se nulla (il backend rifiuta i nanosecondi).
const DATA_ORA = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/
const pad = (n, l = 2) => String(n).padStart(l, '0')

/**
 * Data e ora locali normalizzate a "yyyy-mm-ddThh:mm:ss", oppure null se non valide o non
 * rappresentabili dal backend (anni 1000–9999, nessuna frazione di secondo).
 */
export function normalizzaDataOra(value) {
  if (typeof value !== 'string') return null
  const m = DATA_ORA.exec(value)
  if (!m) return null
  if (m[7] !== undefined && !/^0+$/.test(m[7])) return null
  const [y, mo, d, h, mi] = m.slice(1, 6).map(Number)
  const s = m[6] === undefined ? 0 : Number(m[6])
  if (y < 1000 || y > 9999 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 59) return null
  if (d > new Date(Date.UTC(y, mo, 0)).getUTCDate()) return null
  return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${pad(s)}`
}

/** Secondi di calendario di un orario normalizzato (nessun fuso: solo per differenze e somme). */
export function secondi(norm) {
  const [y, mo, d, h, mi, s] = norm.split(/[-T:]/).map(Number)
  const date = new Date(0)
  date.setUTCFullYear(y, mo - 1, d)
  date.setUTCHours(h, mi, s, 0)
  return date.getTime() / 1000
}

/** Orario normalizzato dai secondi di calendario. */
export function daSecondi(n) {
  const d = new Date(n * 1000)
  return `${pad(d.getUTCFullYear(), 4)}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
}

/** Fine di uno slot: inizio + 60 minuti, oppure null se non rappresentabile. */
export const fineSlot = (inizio) => normalizzaDataOra(daSecondi(secondi(inizio) + ORA))

/**
 * Controlli locali della creazione (formato, ordine, durata multipla esatta di 60 minuti,
 * massimo 1000 slot). Il backend resta autorevole, anche sulla relazione con l'ora corrente.
 * @returns {{errore: string|null, inizio?: string, fine?: string, slot?: number}}
 */
export function validaIntervallo(inizioRaw, fineRaw) {
  const inizio = normalizzaDataOra(inizioRaw)
  const fine = normalizzaDataOra(fineRaw)
  if (!inizio) return { errore: 'inizio' }
  if (!fine) return { errore: 'fine' }
  const durata = secondi(fine) - secondi(inizio)
  if (durata <= 0) return { errore: 'ordine' }
  if (durata % ORA !== 0) return { errore: 'durata' }
  if (durata / ORA > MAX_SLOT) return { errore: 'troppi' }
  return { errore: null, inizio, fine, slot: durata / ORA }
}

/** Orari attesi degli slot di un intervallo valido, in ordine. */
export function slotAttesi(inizio, slot) {
  const base = secondi(inizio)
  return Array.from({ length: slot }, (_, k) => ({ inizio: daSecondi(base + k * ORA), fine: daSecondi(base + (k + 1) * ORA) }))
}

// ---------------------------------------------------------------- elenco

export function elencoPath(medicoId) {
  if (!isId(medicoId)) throw new TypeError('Medico non valido.')
  return `/api/disponibilita?medicoId=${medicoId}`
}

/**
 * Voce di DisponibilitaResponse del medico `medicoId`. Prestazione e stanza possono mancare;
 * `configurata` è vera solo con entrambe. Nessuno stato di occupazione è esposto.
 */
export function parseDisponibilita(d, medicoId) {
  if (!isObject(d)) throw new ProtocolError('Disponibilità non valida')
  const inizio = normalizzaDataOra(d.dataOraInizio)
  const fine = normalizzaDataOra(d.dataOraFine)
  if (!isId(d.idDisponibilita) || !inizio || !fine || secondi(fine) <= secondi(inizio) || d.idMedico !== medicoId
      || !(d.idPrestazione == null || isId(d.idPrestazione)) || !(d.nomePrestazione == null || isText(d.nomePrestazione))
      || !(d.idStanza == null || isId(d.idStanza)) || !(d.nomeStanza == null || isText(d.nomeStanza))
      || typeof d.configurata !== 'boolean' || d.configurata !== (d.idPrestazione != null && d.idStanza != null)) {
    throw new ProtocolError('Disponibilità non valida')
  }
  return Object.freeze({
    idDisponibilita: d.idDisponibilita,
    inizio,
    fine,
    prestazione: d.idPrestazione == null ? null : Object.freeze({ id: d.idPrestazione, nome: d.nomePrestazione ?? null }),
    stanza: d.idStanza == null ? null : Object.freeze({ id: d.idStanza, nome: d.nomeStanza ?? null }),
    configurata: d.configurata,
  })
}

/** Configurazione presente, anche solo parziale: il backend rifiuta modifica ed eliminazione. */
export const configurazionePresente = (s) => s.prestazione !== null || s.stanza !== null

/** GET /api/disponibilita?medicoId: elenco nell'ordine del backend, voci validate, ID unici. */
export async function fetchDisponibilita(requestFn, medicoId) {
  const result = await requestFn({ path: elencoPath(medicoId) })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  const voci = result.data.map((d) => parseDisponibilita(d, medicoId))
  if (new Set(voci.map((v) => v.idDisponibilita)).size !== voci.length) throw new ProtocolError('Disponibilità duplicate')
  return Object.freeze(voci)
}

// ---------------------------------------------------------------- creazione

/** POST /api/disponibilita con `{idMedico, dataOraInizio, dataOraFine}` già validati. */
export function postCreazione(requestFn, medicoId, inizio, fine) {
  const v = validaIntervallo(inizio, fine)
  if (!isId(medicoId) || v.errore || v.inizio !== inizio || v.fine !== fine) throw new TypeError('Creazione non valida.')
  return requestFn({ method: 'POST', path: '/api/disponibilita', json: { idMedico: medicoId, dataOraInizio: inizio, dataOraFine: fine } })
}

/**
 * Risposta 201: un array con esattamente gli slot dell'intervallo, consecutivi da 60 minuti,
 * del medico della sessione, non configurati e con ID distinti. Ogni difformità è un esito
 * non conforme (incerto).
 */
export function parseCreazione(result, medicoId, inizio, fine) {
  const v = validaIntervallo(inizio, fine)
  if (result?.status !== 201 || !result.hasBody || !Array.isArray(result.data) || v.errore || result.data.length !== v.slot) {
    throw new ProtocolError('Esito della creazione non conforme')
  }
  const attesi = slotAttesi(inizio, v.slot)
  const slot = result.data.map((d) => parseDisponibilita(d, medicoId))
  if (new Set(slot.map((s) => s.idDisponibilita)).size !== slot.length
      || slot.some((s, k) => s.inizio !== attesi[k].inizio || s.fine !== attesi[k].fine || configurazionePresente(s))) {
    throw new ProtocolError('Esito della creazione non conforme')
  }
  return Object.freeze(slot)
}

// ---------------------------------------------------------------- modifica dell'orario

/** PUT /api/disponibilita/{id}/orario con `{idMedico, dataOraInizio, dataOraFine}` di 60 minuti esatti. */
export function putOrario(requestFn, idDisponibilita, medicoId, inizio) {
  const norm = normalizzaDataOra(inizio)
  const fine = norm && fineSlot(norm)
  if (!isId(idDisponibilita) || !isId(medicoId) || norm !== inizio || !fine) throw new TypeError('Modifica non valida.')
  return requestFn({
    method: 'PUT', path: `/api/disponibilita/${idDisponibilita}/orario`, json: { idMedico: medicoId, dataOraInizio: inizio, dataOraFine: fine },
  })
}

/** Risposta 200: stessa disponibilità, stesso medico, esattamente il nuovo orario. */
export function parseOrario(result, idDisponibilita, medicoId, inizio) {
  if (result?.status !== 200 || !result.hasBody) throw new ProtocolError('Esito della modifica non conforme')
  const s = parseDisponibilita(result.data, medicoId)
  if (s.idDisponibilita !== idDisponibilita || s.inizio !== inizio || s.fine !== fineSlot(inizio)) {
    throw new ProtocolError('Esito della modifica non conforme')
  }
  return s
}

// ---------------------------------------------------------------- eliminazione

/** DELETE /api/disponibilita/{id}?medicoId. */
export function deleteDisponibilita(requestFn, idDisponibilita, medicoId) {
  if (!isId(idDisponibilita) || !isId(medicoId)) throw new TypeError('Eliminazione non valida.')
  return requestFn({ method: 'DELETE', path: `/api/disponibilita/${idDisponibilita}?medicoId=${medicoId}` })
}

/** Successo solo con 204 senza corpo. */
export function parseEliminazione(result) {
  if (result?.status !== 204 || result.hasBody) throw new ProtocolError("Esito dell'eliminazione non conforme")
  return true
}
