import { isLocalDate, parseLocalDateTime } from '../utils/datetime.js'
import { ProtocolError } from './http.js'

/**
 * API del medico : agenda, registrazione della visita, segnalazione di indisponibilità e
 * caricamento del referto. Ogni funzione riceve la richiesta protetta della sessione: identità,
 * CSRF ed epoca arrivano da lì. `medicoId` è sempre l'identità della sessione, mai un dato
 * inserito dall'utente; il backend lo confronta con la sessione. Nessun nuovo invio automatico.
 */

/** Stati che possono comparire nell'agenda (RICHIESTA del titolare, gli altri dell'effettivo). */
export const STATI_AGENDA = Object.freeze(['RICHIESTA', 'CONFERMATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA'])

/** Periodo massimo dell'agenda: 31 giorni inclusi (dataA − dataDa ≤ 30 giorni). */
export const MAX_GIORNI_AGENDA = 31

/** Limite del backend per il referto: 10 MiB. */
export const MAX_BYTES_REFERTO = 10_485_760

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const vuoto = (v) => v === null || v === undefined || v === ''

/** Data accettata dal backend come filtro: anno 1000–9999 e limite superiore rappresentabile. */
export function dataAgendaValida(value) {
  return isLocalDate(value) && Number(value.slice(0, 4)) >= 1000 && value !== '9999-12-31'
}

/** Giorni di calendario tra due date ISO valide (aritmetica pura sul calendario, nessun fuso). */
export function giorniTra(da, a) {
  const utc = (d) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)))
  return Math.round((utc(a) - utc(da)) / 86_400_000)
}

/**
 * Query di GET /api/prenotazioni/agenda. Senza date il backend usa la propria giornata
 * corrente: il frontend non la calcola. `data` esclude l'intervallo; `dataDa` e `dataA` vanno
 * insieme, ordinati, entro 31 giorni inclusi. Le combinazioni non ammesse sono rifiutate
 * prima della richiesta.
 */
export function agendaPath({ medicoId, data = null, dataDa = null, dataA = null } = {}) {
  if (!isId(medicoId)) throw new TypeError('Medico non valido.')
  const query = new URLSearchParams({ medicoId: String(medicoId) })
  if (!vuoto(data) && (!vuoto(dataDa) || !vuoto(dataA))) throw new TypeError('Data singola e intervallo sono alternativi.')
  if (vuoto(dataDa) !== vuoto(dataA)) throw new TypeError("L'intervallo richiede entrambe le date.")
  if (!vuoto(data)) {
    if (!dataAgendaValida(data)) throw new TypeError('Data non valida.')
    query.set('data', data)
  }
  if (!vuoto(dataDa)) {
    if (!dataAgendaValida(dataDa) || !dataAgendaValida(dataA)) throw new TypeError('Data non valida.')
    if (dataDa > dataA) throw new TypeError('Intervallo non ordinato.')
    if (giorniTra(dataDa, dataA) >= MAX_GIORNI_AGENDA) throw new TypeError('Intervallo oltre 31 giorni.')
    query.set('dataDa', dataDa)
    query.set('dataA', dataA)
  }
  return `/api/prenotazioni/agenda?${query.toString()}`
}

/**
 * Voce di AppuntamentoAgendaResponse per il medico `medicoId`. Prestazione e stanza possono
 * mancare (LEFT JOIN). Il medico effettivo manca solo per una RICHIESTA, che appartiene al
 * titolare; ogni altro stato appartiene all'effettivo. Una voce che non riguarda il medico
 * richiedente o incoerente è un errore di protocollo.
 */
export function parseAppuntamentoAgenda(d, medicoId) {
  if (!isObject(d)) throw new ProtocolError('Appuntamento non valido')
  const inizio = parseLocalDateTime(d.dataOraInizio)
  const fine = parseLocalDateTime(d.dataOraFine)
  if (!isId(d.idPrenotazione) || !isId(d.idDisponibilita) || !STATI_AGENDA.includes(d.stato) || !inizio || !fine
      || typeof d.indisponibilitaSegnalata !== 'boolean' || typeof d.refertoPresente !== 'boolean' || !isId(d.idMedicoTitolare)
      || !(d.idMedicoEffettivo === null || isId(d.idMedicoEffettivo))
      || !isId(d.idPaziente) || !isText(d.nomePaziente) || !isText(d.cognomePaziente)
      || !(d.idPrestazione == null || isId(d.idPrestazione))
      || !(d.nomePrestazione == null || isText(d.nomePrestazione))
      || !(d.durataPrestazione == null || (Number.isSafeInteger(d.durataPrestazione) && d.durataPrestazione > 0))
      || !(d.idStanza == null || isId(d.idStanza))
      || !(d.nomeStanza == null || isText(d.nomeStanza))) {
    throw new ProtocolError('Appuntamento non valido')
  }
  const richiesta = d.stato === 'RICHIESTA'
  if (richiesta !== (d.idMedicoEffettivo === null)) throw new ProtocolError('Medico effettivo non coerente')
  if ((richiesta ? d.idMedicoTitolare : d.idMedicoEffettivo) !== medicoId) throw new ProtocolError('Appuntamento di un altro medico')
  return Object.freeze({
    idPrenotazione: d.idPrenotazione,
    idDisponibilita: d.idDisponibilita,
    stato: d.stato,
    inizio,
    fine,
    indisponibilitaSegnalata: d.indisponibilitaSegnalata,
    refertoPresente: d.refertoPresente,
    idMedicoTitolare: d.idMedicoTitolare,
    idMedicoEffettivo: d.idMedicoEffettivo,
    paziente: Object.freeze({ id: d.idPaziente, nome: d.nomePaziente, cognome: d.cognomePaziente }),
    nomePrestazione: d.nomePrestazione ?? null,
    durata: d.durataPrestazione ?? null,
    stanza: d.idStanza == null ? null : Object.freeze({ id: d.idStanza, nome: d.nomeStanza ?? null }),
  })
}

/** GET /api/prenotazioni/agenda: elenco nell'ordine del backend, ogni voce validata, ID unici. */
export async function fetchAgenda(requestFn, filtri) {
  const result = await requestFn({ path: agendaPath(filtri) })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Agenda attesa')
  const voci = result.data.map((d) => parseAppuntamentoAgenda(d, filtri.medicoId))
  if (new Set(voci.map((v) => v.idPrenotazione)).size !== voci.length) throw new ProtocolError('Appuntamenti duplicati')
  return Object.freeze(voci)
}

// ---------------------------------------------------------------- registrazione della visita

/** POST /api/prenotazioni/{id}/registra-visita con corpo esattamente `{}`. */
export function postRegistraVisita(requestFn, idPrenotazione) {
  if (!isId(idPrenotazione)) throw new TypeError('Prenotazione non valida.')
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/registra-visita`, json: {} })
}

/** Risposta: 200, stessa prenotazione, stato EFFETTUATA. Ogni difformità è un esito incerto. */
export function parseVisitaRegistrata(result, idPrenotazione) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idPrenotazione !== idPrenotazione || d.stato !== 'EFFETTUATA') {
    throw new ProtocolError('Esito della registrazione non conforme')
  }
  return Object.freeze({ idPrenotazione: d.idPrenotazione, stato: d.stato })
}

// ---------------------------------------------------------------- segnalazione di indisponibilità

/** POST /api/prenotazioni/segnala-indisponibilita con `{medicoId, idPrenotazioni}` senza duplicati. */
export function postSegnalazione(requestFn, medicoId, idPrenotazioni) {
  if (!isId(medicoId) || !Array.isArray(idPrenotazioni) || idPrenotazioni.length === 0
      || !idPrenotazioni.every(isId) || new Set(idPrenotazioni).size !== idPrenotazioni.length) {
    throw new TypeError('Segnalazione non valida.')
  }
  return requestFn({ method: 'POST', path: '/api/prenotazioni/segnala-indisponibilita', json: { medicoId, idPrenotazioni: [...idPrenotazioni] } })
}

/**
 * Risposta: 200, `indisponibilitaSegnalata` true e lo stesso insieme di ID inviati, senza
 * duplicati. L'operazione è atomica: non esistono successi parziali da interpretare.
 */
export function parseSegnalazione(result, idPrenotazioni) {
  const d = result?.data
  const ids = isObject(d) && Array.isArray(d.idPrenotazioni) ? d.idPrenotazioni : null
  const attesi = new Set(idPrenotazioni)
  if (result?.status !== 200 || !result.hasBody || !ids || d.indisponibilitaSegnalata !== true
      || ids.length !== attesi.size || new Set(ids).size !== ids.length || !ids.every((id) => isId(id) && attesi.has(id))) {
    throw new ProtocolError('Esito della segnalazione non conforme')
  }
  return Object.freeze({ idPrenotazioni: Object.freeze([...ids]), indisponibilitaSegnalata: true })
}

// ---------------------------------------------------------------- referto

/** Oggetto con l'interfaccia di un Blob (anche di un altro realm): dimensione e slice. */
const isBlobLike = (f) => f !== null && typeof f === 'object' && Number.isSafeInteger(f.size) && typeof f.slice === 'function'

async function primiByte(blob, n) {
  const testa = blob.slice(0, n)
  const buffer = typeof testa.arrayBuffer === 'function' ? await testa.arrayBuffer() : await new Response(testa).arrayBuffer()
  return new TextDecoder('latin1').decode(buffer)
}

/**
 * Controlli locali essenziali sul file scelto: presente, non vuoto, entro 10 MiB e con la firma
 * `%PDF-`. Non sostituiscono la validazione documentale del backend.
 * @returns {Promise<null|'assente'|'vuoto'|'troppo-grande'|'non-pdf'|'illeggibile'>}
 */
export async function controllaPdf(file) {
  if (!isBlobLike(file)) return 'assente'
  if (file.size === 0) return 'vuoto'
  if (file.size > MAX_BYTES_REFERTO) return 'troppo-grande'
  try {
    return (await primiByte(file, 5)) === '%PDF-' ? null : 'non-pdf'
  } catch {
    return 'illeggibile'
  }
}

/**
 * POST /api/prenotazioni/{id}/referto: il file come corpo binario con Content-Type esattamente
 * `application/pdf`, senza multipart né JSON, senza nome file o ID attore.
 */
export function postReferto(requestFn, idPrenotazione, file) {
  if (!isId(idPrenotazione) || !isBlobLike(file) || file.size === 0 || file.size > MAX_BYTES_REFERTO) {
    throw new TypeError('Caricamento non valido.')
  }
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/referto`, body: file, contentType: 'application/pdf' })
}

/** Risposta: 201 con idReferto, stessa prenotazione, nome generato dal server e data di caricamento. */
export function parseRefertoCaricato(result, idPrenotazione) {
  const d = result?.data
  const caricato = parseLocalDateTime(d?.caricatoIl)
  if (result?.status !== 201 || !result.hasBody || !isObject(d) || !isId(d.idReferto) || d.idPrenotazione !== idPrenotazione
      || !isText(d.nomeFile) || d.nomeFile.length === 0 || !caricato) {
    throw new ProtocolError('Esito del caricamento non conforme')
  }
  return Object.freeze({ idReferto: d.idReferto, idPrenotazione, nomeFile: d.nomeFile, caricato })
}
