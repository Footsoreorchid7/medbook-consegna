import { isLocalDate, parseLocalDateTime } from '../utils/datetime.js'
import { parseDisponibilita } from './disponibilitaMedico.js'
import { ProtocolError } from './http.js'
import { MODALITA, STATI_PAGAMENTO, importoValido } from './pagamenti.js'
import { STATI_RIMBORSO } from './rimborsi.js'

/**
 * API dell'operatore desk : richieste di prenotazione, segnalazioni di indisponibilità,
 * rimborsi da decidere, configurazione delle disponibilità e consultazione dei medici.
 * `idOperatore` è sempre l'identità della sessione; il backend lo confronta con la sessione.
 * Le decisioni sui rimborsi non hanno ID attore: l'operatore è quello della sessione.
 * Nessun nuovo invio automatico.
 */

const MAX_INT = 2147483647
export const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const isAmount = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0
const vuoto = (v) => v === null || v === undefined || v === ''
const opzionale = (v, check) => v === null || v === undefined || check(v)

function elenco(result, parse, chiave) {
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  const voci = result.data.map(parse)
  if (chiave && new Set(voci.map(chiave)).size !== voci.length) throw new ProtocolError('Voci duplicate')
  return Object.freeze(voci)
}

/** Data accettata come filtro: anno 1000–9999 e limite superiore rappresentabile. */
export const dataFiltroValida = (v) => isLocalDate(v) && Number(v.slice(0, 4)) >= 1000 && v !== '9999-12-31'

const persona = (id, nome, cognome) => {
  if (id == null) {
    if (nome != null || cognome != null) throw new ProtocolError('Persona non coerente')
    return null
  }
  if (!isId(id) || !isText(nome) || !isText(cognome)) throw new ProtocolError('Persona non valida')
  return Object.freeze({ id, nome, cognome })
}

// ---------------------------------------------------------------- richieste di prenotazione

/**
 * Query di GET /api/prenotazioni/richieste: solo filtri valorizzati. `data` esclude
 * l'intervallo; `dataDa`/`dataA` possono comparire anche da soli, ordinati se entrambi.
 */
export const STATI_RICHIESTE = Object.freeze(['RICHIESTA', 'CONFERMATA', 'RIFIUTATA', 'ANNULLATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA'])
export function richiestePath({ stato = null, medicoId = null, prestazioneId = null, data = null, dataDa = null, dataA = null } = {}) {
  const q = new URLSearchParams()
  if (!vuoto(stato)) { if (![...STATI_RICHIESTE, 'TUTTE'].includes(stato)) throw new TypeError('Stato non valido.'); q.set('stato', stato) }
  if (!vuoto(medicoId)) { if (!isId(medicoId)) throw new TypeError('Medico non valido.'); q.set('medicoId', String(medicoId)) }
  if (!vuoto(prestazioneId)) { if (!isId(prestazioneId)) throw new TypeError('Prestazione non valida.'); q.set('prestazioneId', String(prestazioneId)) }
  if (!vuoto(data) && (!vuoto(dataDa) || !vuoto(dataA))) throw new TypeError('Data singola e intervallo sono alternativi.')
  for (const [k, v] of [['data', data], ['dataDa', dataDa], ['dataA', dataA]]) {
    if (!vuoto(v)) { if (!dataFiltroValida(v)) throw new TypeError('Data non valida.'); q.set(k, v) }
  }
  if (!vuoto(dataDa) && !vuoto(dataA) && dataDa > dataA) throw new TypeError('Intervallo non ordinato.')
  const s = q.toString()
  return `/api/prenotazioni/richieste${s ? `?${s}` : ''}`
}

/** Voce di RichiestaPrenotazioneResponse: sempre RICHIESTA, medico = titolare dello slot. */
export function parseRichiesta(d) {
  if (d?.stato !== 'RICHIESTA') throw new ProtocolError('Richiesta pendente attesa')
  return parseRichiestaConsultazione(d)
}

export function parseRichiestaConsultazione(d) {
  if (!isObject(d)) throw new ProtocolError('Richiesta non valida')
  const inizio = parseLocalDateTime(d.dataOraInizio)
  const fine = parseLocalDateTime(d.dataOraFine)
  const richiesta = parseLocalDateTime(d.dataOraRichiesta)
  if (!isId(d.idPrenotazione) || !STATI_RICHIESTE.includes(d.stato) || !inizio || !fine || !richiesta || !isAmount(d.prezzo)
      || typeof d.indisponibilitaSegnalata !== 'boolean'
      || !opzionale(d.idPrestazione, isId) || !opzionale(d.nomePrestazione, isText)
      || !opzionale(d.durataPrestazione, (v) => Number.isSafeInteger(v) && v > 0)
      || !opzionale(d.idStanza, isId) || !opzionale(d.nomeStanza, isText)) {
    throw new ProtocolError('Richiesta non valida')
  }
  const paziente = persona(d.idPaziente, d.nomePaziente, d.cognomePaziente)
  const medico = persona(d.idMedico, d.nomeMedico, d.cognomeMedico)
  if (!paziente || !medico) throw new ProtocolError('Richiesta non valida')
  return Object.freeze({
    idPrenotazione: d.idPrenotazione, stato: d.stato, inizio, fine, richiesta, prezzo: d.prezzo,
    indisponibilitaSegnalata: d.indisponibilitaSegnalata, paziente, medico,
    prestazione: d.idPrestazione == null ? null : Object.freeze({ id: d.idPrestazione, nome: d.nomePrestazione ?? null, durata: d.durataPrestazione ?? null }),
    stanza: d.idStanza == null ? null : Object.freeze({ id: d.idStanza, nome: d.nomeStanza ?? null }),
  })
}

export async function fetchRichieste(requestFn, filtri = {}) {
  return elenco(await requestFn({ path: richiestePath(filtri) }), parseRichiesta, (v) => v.idPrenotazione)
}

/** Consultazione storica: non usata per riconciliare l'assenza dall'elenco pendente . */
export async function fetchRichiesteConsultazione(requestFn, filtri = {}) {
  const voci = elenco(await requestFn({ path: richiestePath(filtri) }), parseRichiestaConsultazione, v => v.idPrenotazione)
  const stato = filtri.stato ?? 'RICHIESTA'
  if (stato !== 'TUTTE' && voci.some(v => v.stato !== stato)) throw new ProtocolError('Stato non coerente con il filtro')
  return voci
}

const corpoOperatore = (idOperatore, extra = {}) => {
  if (!isId(idOperatore)) throw new TypeError('Operatore non valido.')
  return { idOperatore, ...extra }
}

/** POST /api/prenotazioni/{id}/conferma | rifiuta | annulla con l'operatore della sessione. */
export function postDecisionePrenotazione(requestFn, tipo, idPrenotazione, idOperatore, statoAtteso = null) {
  if (!isId(idPrenotazione) || !['conferma', 'rifiuta', 'annulla'].includes(tipo)) throw new TypeError('Operazione non valida.')
  if (tipo === 'annulla' && !['RICHIESTA', 'CONFERMATA'].includes(statoAtteso)) throw new TypeError('Stato atteso non valido.')
  const json = corpoOperatore(idOperatore, tipo === 'annulla' ? { statoAtteso } : {})
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/${tipo}`, json })
}

const STATO_FINALE = { conferma: 'CONFERMATA', rifiuta: 'RIFIUTATA', annulla: 'ANNULLATA' }

/**
 * Risposta 200: stessa prenotazione, stato atteso per l'operazione, operatore della sessione e
 * data di gestione. Per la conferma, il medico effettivo è il titolare letto nella richiesta.
 */
export function parseDecisionePrenotazione(result, tipo, idPrenotazione, idOperatore, idMedicoAtteso = null) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idPrenotazione !== idPrenotazione || d.stato !== STATO_FINALE[tipo]
      || d.idOperatore !== idOperatore || !parseLocalDateTime(d.dataOraGestione)
      || (tipo === 'conferma' && (!isId(d.idMedicoEffettivo) || (idMedicoAtteso !== null && d.idMedicoEffettivo !== idMedicoAtteso)))) {
    throw new ProtocolError("Esito dell'operazione non conforme")
  }
  return Object.freeze({ idPrenotazione, stato: d.stato, idMedicoEffettivo: d.idMedicoEffettivo ?? null })
}

// ---------------------------------------------------------------- segnalazioni di indisponibilità

/** Voce di SegnalazionePendenteResponse: RICHIESTA del titolare o CONFERMATA con effettivo, flag attivo. */
export function parseSegnalazione(d) {
  if (!isObject(d)) throw new ProtocolError('Segnalazione non valida')
  const inizio = parseLocalDateTime(d.dataOraInizio)
  const fine = parseLocalDateTime(d.dataOraFine)
  if (!isId(d.idPrenotazione) || !['RICHIESTA', 'CONFERMATA'].includes(d.stato) || !isId(d.idDisponibilita) || !inizio || !fine
      || d.indisponibilitaSegnalata !== true || !opzionale(d.idPrestazione, isId) || !opzionale(d.nomePrestazione, isText)
      || !opzionale(d.idStanza, isId) || !opzionale(d.nomeStanza, isText)) {
    throw new ProtocolError('Segnalazione non valida')
  }
  const paziente = persona(d.idPaziente, d.nomePaziente, d.cognomePaziente)
  if (!paziente) throw new ProtocolError('Paziente della segnalazione non valido')
  const titolare = persona(d.idMedicoTitolare, d.nomeMedicoTitolare, d.cognomeMedicoTitolare)
  const effettivo = persona(d.idMedicoEffettivo, d.nomeMedicoEffettivo, d.cognomeMedicoEffettivo)
  if (!titolare || (d.stato === 'RICHIESTA') !== (effettivo === null)) throw new ProtocolError('Segnalazione non valida')
  return Object.freeze({
    idPrenotazione: d.idPrenotazione, stato: d.stato, idDisponibilita: d.idDisponibilita, inizio, fine, titolare, effettivo, paziente,
    prestazione: d.idPrestazione == null ? null : Object.freeze({ id: d.idPrestazione, nome: d.nomePrestazione ?? null }),
    stanza: d.idStanza == null ? null : Object.freeze({ id: d.idStanza, nome: d.nomeStanza ?? null }),
    // Medico indisponibile: il titolare per una RICHIESTA, l'effettivo corrente per una CONFERMATA.
    indisponibile: d.stato === 'RICHIESTA' ? titolare : effettivo,
  })
}

export async function fetchSegnalazioni(requestFn) {
  return elenco(await requestFn({ path: '/api/prenotazioni/segnalazioni-indisponibilita' }), parseSegnalazione, (v) => v.idPrenotazione)
}

/** POST /api/prenotazioni/{id}/sostituisci-medico con stato e medico indisponibile della risorsa letta. */
export function postSostituzione(requestFn, segnalazione, idOperatore, idMedicoSostituto) {
  if (!isId(idMedicoSostituto) || idMedicoSostituto === segnalazione.indisponibile.id) throw new TypeError('Sostituto non valido.')
  const json = corpoOperatore(idOperatore, {
    idMedicoSostituto, statoAtteso: segnalazione.stato, idMedicoIndisponibile: segnalazione.indisponibile.id,
  })
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${segnalazione.idPrenotazione}/sostituisci-medico`, json })
}

/** Risposta 200: CONFERMATA con il sostituto come effettivo, segnalazione risolta, operatore della sessione. */
export function parseSostituzione(result, idPrenotazione, idOperatore, idMedicoSostituto) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idPrenotazione !== idPrenotazione || d.stato !== 'CONFERMATA'
      || d.idMedicoEffettivo !== idMedicoSostituto || d.indisponibilitaSegnalata !== false || d.idOperatore !== idOperatore
      || !parseLocalDateTime(d.dataOraGestione)) {
    throw new ProtocolError('Esito della sostituzione non conforme')
  }
  return Object.freeze({ idPrenotazione, stato: d.stato, idMedicoEffettivo: d.idMedicoEffettivo })
}

// ---------------------------------------------------------------- rimborsi

/** Voce di RichiestaRimborsoDeskResponse: solo richieste RICHIESTO. */
export function parseRimborsoDesk(d) {
  if (!isObject(d)) throw new ProtocolError('Rimborso non valido')
  const richiesto = parseLocalDateTime(d.richiestoIl)
  const inizio = d.dataOraInizio == null ? null : parseLocalDateTime(d.dataOraInizio)
  const fine = d.dataOraFine == null ? null : parseLocalDateTime(d.dataOraFine)
  if (!isId(d.idRimborso) || !isId(d.idPrenotazione) || !isId(d.idPagamento) || d.stato !== 'RICHIESTO'
      || typeof d.importo !== 'number' || !Number.isFinite(d.importo) || d.importo <= 0 || !isText(d.motivazione) || !richiesto
      || d.verificatoIl != null || (d.dataOraInizio != null && !inizio) || (d.dataOraFine != null && !fine)
      || !opzionale(d.idPrestazione, isId) || !opzionale(d.nomePrestazione, isText) || !opzionale(d.modalitaPagamento, isText)) {
    throw new ProtocolError('Rimborso non valido')
  }
  const paziente = persona(d.idPaziente, d.nomePaziente, d.cognomePaziente)
  if (!paziente) throw new ProtocolError('Rimborso non valido')
  return Object.freeze({
    idRimborso: d.idRimborso, idPrenotazione: d.idPrenotazione, idPagamento: d.idPagamento, stato: d.stato, importo: d.importo,
    motivazione: d.motivazione, richiesto, paziente, inizio, fine, modalita: d.modalitaPagamento ?? null,
    prestazione: d.idPrestazione == null ? null : Object.freeze({ id: d.idPrestazione, nome: d.nomePrestazione ?? null }),
  })
}

export async function fetchRimborsiDesk(requestFn) {
  return elenco(await requestFn({ path: '/api/rimborsi/richieste' }), parseRimborsoDesk, (v) => v.idRimborso)
}

export const FILTRI_RIMBORSI_DESK = Object.freeze({ RICHIESTO: 'Da decidere', APPROVATO: 'Approvati', RESPINTO: 'Respinti', TUTTI: 'Tutti' })

export function parseStoricoRimborsoDesk(d) {
  if (!isObject(d) || !['RICHIESTO', 'APPROVATO', 'RESPINTO', 'IN_ELABORAZIONE', 'RIMBORSATO', 'FALLITO'].includes(d.stato)) throw new ProtocolError('Stato rimborso non valido')
  const verificato = d.verificatoIl == null ? null : parseLocalDateTime(d.verificatoIl)
  if ((d.verificatoIl != null && !verificato) || (verificato === null) !== (d.idOperatore == null)
      || !opzionale(d.idOperatore, isId) || !opzionale(d.nomeOperatore, isText) || !opzionale(d.cognomeOperatore, isText)
      || (d.idOperatore == null && (d.nomeOperatore != null || d.cognomeOperatore != null))
      || (d.stato === 'RICHIESTO' && verificato)) throw new ProtocolError('Decisione rimborso non valida')
  const voce = parseRimborsoDesk({ ...d, stato: 'RICHIESTO', verificatoIl: null })
  return Object.freeze({ ...voce, stato: d.stato, verificato, operatore: d.idOperatore == null ? null : Object.freeze({ id: d.idOperatore, nome: d.nomeOperatore, cognome: d.cognomeOperatore }) })
}

export async function fetchStoricoRimborsiDesk(requestFn, stato) {
  if (!Object.hasOwn(FILTRI_RIMBORSI_DESK, stato)) throw new TypeError('Filtro non valido')
  return elenco(await requestFn({ path: `/api/rimborsi/desk?stato=${stato}` }), d => {
    const r = parseStoricoRimborsoDesk(d)
    if (stato !== 'TUTTI' && r.stato !== stato) throw new ProtocolError('Rimborso estraneo al filtro')
    return r
  }, v => v.idRimborso)
}

/** POST /api/rimborsi/{id}/approva | respingi con corpo esattamente `{}`. */
export function postDecisioneRimborso(requestFn, tipo, idRimborso) {
  if (!isId(idRimborso) || !['approva', 'respingi'].includes(tipo)) throw new TypeError('Decisione non valida.')
  return requestFn({ method: 'POST', path: `/api/rimborsi/${idRimborso}/${tipo}`, json: {} })
}

/** Risposta 200: stesso rimborso, APPROVATO o RESPINTO, operatore della sessione e data di verifica. */
export function parseDecisioneRimborso(result, tipo, idRimborso, idOperatore) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idRimborso !== idRimborso
      || d.stato !== (tipo === 'approva' ? 'APPROVATO' : 'RESPINTO') || d.idOperatore !== idOperatore || !parseLocalDateTime(d.verificatoIl)) {
    throw new ProtocolError('Esito della decisione non conforme')
  }
  return Object.freeze({ idRimborso, stato: d.stato, idOperatore: d.idOperatore, verificato: parseLocalDateTime(d.verificatoIl) })
}

// ---------------------------------------------------------------- medici, stanze, configurazione

export const STATI_MEDICO = Object.freeze(['ATTIVO', 'INATTIVO'])

/** Proiezione desk di un medico: nessun dato di account o credenziale. */
export function parseMedicoDesk(d) {
  if (!isObject(d) || !isId(d.idUtente) || !isText(d.nome) || !isText(d.cognome) || !opzionale(d.specializzazione, isText)
      || !opzionale(d.numeroIscrizioneOrdine, isText) || !STATI_MEDICO.includes(d.stato)) {
    throw new ProtocolError('Medico non valido')
  }
  return Object.freeze({
    idUtente: d.idUtente, nome: d.nome, cognome: d.cognome, specializzazione: d.specializzazione ?? null,
    numeroIscrizioneOrdine: d.numeroIscrizioneOrdine ?? null, stato: d.stato,
  })
}

export function mediciPath({ stato = null, idPrestazione = null } = {}) {
  const q = new URLSearchParams()
  if (!vuoto(stato)) { if (!STATI_MEDICO.includes(stato)) throw new TypeError('Stato non valido.'); q.set('stato', stato) }
  if (!vuoto(idPrestazione)) { if (!isId(idPrestazione)) throw new TypeError('Prestazione non valida.'); q.set('idPrestazione', String(idPrestazione)) }
  const s = q.toString()
  return `/api/medici${s ? `?${s}` : ''}`
}

export async function fetchMedici(requestFn, filtri = {}) {
  return elenco(await requestFn({ path: mediciPath(filtri) }), parseMedicoDesk, (v) => v.idUtente)
}

function parsePrestazione(p) {
  if (!isObject(p) || !isId(p.idPrestazione) || !isText(p.nome) || !opzionale(p.descrizione, isText)
      || !Number.isSafeInteger(p.durata) || p.durata <= 0 || !isAmount(p.prezzo)) {
    throw new ProtocolError('Prestazione non valida')
  }
  return Object.freeze({ idPrestazione: p.idPrestazione, nome: p.nome, descrizione: p.descrizione ?? null, durata: p.durata, prezzo: p.prezzo })
}

/** GET /api/medici/{id}: proiezione desk e prestazioni per cui il medico è abilitato. */
export async function fetchMedicoDesk(requestFn, idMedico) {
  if (!isId(idMedico)) throw new TypeError('Medico non valido.')
  const result = await requestFn({ path: `/api/medici/${idMedico}` })
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || !Array.isArray(d.prestazioniAutorizzate) || typeof d.paziente !== 'boolean') throw new ProtocolError('Medico atteso')
  const medico = parseMedicoDesk(d.medico)
  if (medico.idUtente !== idMedico) throw new ProtocolError('Medico non corrispondente')
  const prestazioni = d.prestazioniAutorizzate.map(parsePrestazione)
  if (new Set(prestazioni.map((p) => p.idPrestazione)).size !== prestazioni.length) throw new ProtocolError('Prestazioni duplicate')
  return Object.freeze({ medico, prestazioni: Object.freeze(prestazioni), paziente: d.paziente })
}

export async function fetchStanze(requestFn) {
  return elenco(await requestFn({ path: '/api/stanze' }), (s) => {
    if (!isObject(s) || !isId(s.idStanza) || !isText(s.nome) || !opzionale(s.descrizione, isText)) throw new ProtocolError('Stanza non valida')
    return Object.freeze({ idStanza: s.idStanza, nome: s.nome, descrizione: s.descrizione ?? null })
  }, (s) => s.idStanza)
}

/** PUT /api/disponibilita/{id}/configurazione con `{idPrestazione, idStanza}`. */
export function putConfigurazione(requestFn, idDisponibilita, idPrestazione, idStanza) {
  if (!isId(idDisponibilita) || !isId(idPrestazione) || !isId(idStanza)) throw new TypeError('Configurazione non valida.')
  return requestFn({ method: 'PUT', path: `/api/disponibilita/${idDisponibilita}/configurazione`, json: { idPrestazione, idStanza } })
}

/**
 * Risposta 200: stessa disponibilità dello stesso medico, orari invariati, configurata con la
 * prestazione e la stanza richieste.
 */
export function parseConfigurazione(result, slot, idMedico, idPrestazione, idStanza) {
  if (result?.status !== 200 || !result.hasBody) throw new ProtocolError('Esito della configurazione non conforme')
  const s = parseDisponibilita(result.data, idMedico)
  if (s.idDisponibilita !== slot.idDisponibilita || s.inizio !== slot.inizio || s.fine !== slot.fine || !s.configurata
      || s.prestazione.id !== idPrestazione || s.stanza.id !== idStanza) {
    throw new ProtocolError('Esito della configurazione non conforme')
  }
  return s
}

// ---------------------------------------------------------------- prenotazioni gestite

/** Stati restituiti da GET /api/prenotazioni/desk: le prenotazioni successive alla gestione desk. */
export const STATI_DESK = Object.freeze(['CONFERMATA', 'ANNULLATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA'])
export const MODALITA_SEDE = Object.freeze(['DESK_POS', 'DESK_CONTANTI'])
export const ESITI_MANCATA_EROGAZIONE = Object.freeze(['PAZIENTE_ASSENTE', 'NON_EROGATA'])
/** Periodo massimo della lettura, giorni inclusi. */
export const GIORNI_MASSIMI = 31

/** Giorni di calendario tra due date yyyy-mm-dd già valide (calendario puro, nessun fuso). */
function giorniTra(da, a) {
  const utc = (d) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)))
  return Math.round((utc(a) - utc(da)) / 86400000)
}

/**
 * Copertura esplicita di una lettura desk: periodo obbligatorio (giorni inclusi) e medico
 * effettivo facoltativo. `chiave` identifica la lettura per ordinamento e validità.
 * @returns {{dataDa:string, dataA:string, medicoId:number|null, chiave:string}}
 */
export function coperturaDesk({ data = null, dataDa = null, dataA = null, medicoId = null } = {}) {
  if (!vuoto(data)) {
    if (!vuoto(dataDa) || !vuoto(dataA)) throw new TypeError('Data singola e intervallo sono alternativi.')
    dataDa = data
    dataA = data
  }
  if (vuoto(dataDa) || vuoto(dataA)) throw new TypeError('Periodo obbligatorio.')
  if (!dataFiltroValida(dataDa) || !dataFiltroValida(dataA)) throw new TypeError('Data non valida.')
  const giorni = giorniTra(dataDa, dataA)
  if (giorni < 0 || giorni >= GIORNI_MASSIMI) throw new TypeError('Periodo non ordinato o oltre 31 giorni.')
  if (!vuoto(medicoId) && !isId(medicoId)) throw new TypeError('Medico non valido.')
  const medico = vuoto(medicoId) ? null : medicoId
  return Object.freeze({ dataDa, dataA, medicoId: medico, chiave: `prenotazioni-desk:${dataDa}:${dataA}:${medico ?? '*'}` })
}

/** Query di GET /api/prenotazioni/desk per una copertura: `data` se il periodo è di un giorno. */
export function prenotazioniDeskPath(copertura) {
  const q = new URLSearchParams()
  if (copertura.dataDa === copertura.dataA) q.set('data', copertura.dataDa)
  else { q.set('dataDa', copertura.dataDa); q.set('dataA', copertura.dataA) }
  if (copertura.medicoId !== null) q.set('medicoId', String(copertura.medicoId))
  return `/api/prenotazioni/desk?${q}`
}

/** Istante LocalDateTime del backend: parti di calendario, testo originale e chiave ordinabile. */
function istante(valore) {
  const parsed = parseLocalDateTime(valore)
  if (!parsed) return null
  const [, minuti, secondi = '00', frazione = ''] = /^(.{16})(?::(\d{2})(?:\.(\d{1,9}))?)?$/.exec(valore)
  return Object.freeze({ ...parsed, testo: valore, chiave: `${minuti}:${secondi}.${frazione.padEnd(9, '0')}` })
}
const istanteOpzionale = (v) => (v == null ? null : istante(v))
/** Importo DECIMAL(10,2) letto dal backend: finito, non negativo, al massimo due decimali. */
const decimale = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 99999999.99 && Number(v.toFixed(2)) === v
const positivo = (v) => decimale(v) && v > 0

/** Coppia id/nome di un LEFT JOIN: entrambi presenti o entrambi null. */
function riferimento(id, nome) {
  if (id == null && nome == null) return null
  if (!isId(id) || !isText(nome)) throw new ProtocolError('Riferimento non valido')
  return Object.freeze({ id, nome })
}

/**
 * Tentativo di pagamento annidato, coerente con i vincoli dello schema: importo positivo,
 * `pagatoIl` presente se e solo se PAGATO, registrante presente se e solo se la modalità è in sede.
 */
function parsePagamentoDesk(d) {
  if (!isObject(d)) throw new ProtocolError('Pagamento non valido')
  const creato = istante(d.creatoIl)
  const pagato = istanteOpzionale(d.pagatoIl)
  if (!isId(d.idPagamento) || !MODALITA.includes(d.modalita) || !STATI_PAGAMENTO.includes(d.stato) || !positivo(d.importo)
      || !creato || (d.pagatoIl != null && !pagato) || (d.stato === 'PAGATO') !== (pagato !== null)
      || (d.modalita === 'ONLINE' ? d.registratoDa !== null : !isId(d.registratoDa))) {
    throw new ProtocolError('Pagamento non valido')
  }
  return Object.freeze({
    idPagamento: d.idPagamento, modalita: d.modalita, importo: d.importo, stato: d.stato, creato, pagato, registratoDa: d.registratoDa,
  })
}

/** Rimborso annidato: verificatore e data di verifica entrambi presenti o entrambi null. */
function parseRimborsoAnnidato(d) {
  if (!isObject(d)) throw new ProtocolError('Rimborso non valido')
  const richiesto = istante(d.richiestoIl)
  const verificato = istanteOpzionale(d.verificatoIl)
  if (!isId(d.idRimborso) || !isId(d.idPagamento) || !STATI_RIMBORSO.includes(d.stato) || !positivo(d.importo) || !richiesto
      || (d.verificatoIl != null && !verificato) || (verificato === null) !== (d.verificatoDa === null)
      || (d.verificatoDa !== null && !isId(d.verificatoDa))) {
    throw new ProtocolError('Rimborso non valido')
  }
  return Object.freeze({
    idRimborso: d.idRimborso, idPagamento: d.idPagamento, stato: d.stato, importo: d.importo, richiesto, verificato, verificatoDa: d.verificatoDa,
  })
}

const crescente = (voci, id) => voci.every((v, i) => i === 0 || id(voci[i - 1]) < id(v))

/**
 * Voce di PrenotazioneDeskResponse . Stati derivati da una conferma: medico effettivo e
 * gestione obbligatori; ANNULLATA può derivare da una RICHIESTA e averli nulli. Pagamenti e
 * rimborsi ordinati per ID senza duplicati; ogni rimborso riguarda un pagamento della voce, al
 * più uno per pagamento. Gli orari restano LocalDateTime della struttura, senza conversioni.
 */
export function parsePrenotazioneDesk(d) {
  if (!isObject(d)) throw new ProtocolError('Prenotazione non valida')
  const inizio = istante(d.dataOraInizio)
  const fine = istante(d.dataOraFine)
  const gestione = istanteOpzionale(d.dataOraGestione)
  if (!isId(d.idPrenotazione) || !STATI_DESK.includes(d.stato) || typeof d.indisponibilitaSegnalata !== 'boolean' || !decimale(d.prezzo)
      || !isId(d.idDisponibilita) || !inizio || !fine || fine.chiave <= inizio.chiave
      || (d.dataOraGestione != null && !gestione) || (gestione === null) !== (d.gestitaDa === null)
      || (d.gestitaDa !== null && !isId(d.gestitaDa)) || !Array.isArray(d.pagamenti) || !Array.isArray(d.rimborsi)) {
    throw new ProtocolError('Prenotazione non valida')
  }
  const paziente = persona(d.idPaziente, d.nomePaziente, d.cognomePaziente)
  const titolare = persona(d.idMedicoTitolare, d.nomeMedicoTitolare, d.cognomeMedicoTitolare)
  const effettivo = persona(d.idMedicoEffettivo, d.nomeMedicoEffettivo, d.cognomeMedicoEffettivo)
  if (!paziente || !titolare || (d.stato !== 'ANNULLATA' && (effettivo === null || gestione === null))) {
    throw new ProtocolError('Prenotazione non valida')
  }
  const pagamenti = d.pagamenti.map(parsePagamentoDesk)
  const rimborsi = d.rimborsi.map(parseRimborsoAnnidato)
  const idPagamenti = new Set(pagamenti.map((p) => p.idPagamento))
  if (!crescente(pagamenti, (p) => p.idPagamento) || !crescente(rimborsi, (r) => r.idRimborso)
      || rimborsi.some((r) => !idPagamenti.has(r.idPagamento)) || new Set(rimborsi.map((r) => r.idPagamento)).size !== rimborsi.length) {
    throw new ProtocolError('Prenotazione non valida')
  }
  return Object.freeze({
    idPrenotazione: d.idPrenotazione, stato: d.stato, indisponibilitaSegnalata: d.indisponibilitaSegnalata, prezzo: d.prezzo,
    idDisponibilita: d.idDisponibilita, inizio, fine, paziente, titolare, effettivo,
    prestazione: riferimento(d.idPrestazione, d.nomePrestazione), stanza: riferimento(d.idStanza, d.nomeStanza),
    gestitaDa: d.gestitaDa, gestione, pagamenti: Object.freeze(pagamenti), rimborsi: Object.freeze(rimborsi),
  })
}

/**
 * GET /api/prenotazioni/desk per una copertura. Oltre alle voci verifica il contratto
 * dell'elenco: ordine per inizio e ID senza duplicati, inizio nel periodo, medico effettivo
 * uguale al filtro.
 */
export async function fetchPrenotazioniDesk(requestFn, copertura) {
  const voci = elenco(await requestFn({ path: prenotazioniDeskPath(copertura) }), parsePrenotazioneDesk, (v) => v.idPrenotazione)
  const ordinata = voci.every((v, i) => {
    if (i === 0) return true
    const p = voci[i - 1]
    return p.inizio.chiave < v.inizio.chiave || (p.inizio.chiave === v.inizio.chiave && p.idPrenotazione < v.idPrenotazione)
  })
  if (!ordinata || voci.some((v) => v.inizio.date < copertura.dataDa || v.inizio.date > copertura.dataA
      || (copertura.medicoId !== null && v.effettivo?.id !== copertura.medicoId))) {
    throw new ProtocolError('Elenco fuori dalla copertura richiesta')
  }
  return voci
}

/** POST /api/prenotazioni/{id}/pagamento-sede: modalità in sede e importo atteso uguale al prezzo letto. */
export function postPagamentoSede(requestFn, idPrenotazione, idOperatore, modalita, importoAtteso) {
  if (!isId(idPrenotazione) || !MODALITA_SEDE.includes(modalita) || !importoValido(importoAtteso)) throw new TypeError('Incasso non valido.')
  return requestFn({ method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/pagamento-sede`, json: corpoOperatore(idOperatore, { modalita, importoAtteso }) })
}

/**
 * Risposta 201: pagamento PAGATO della stessa prenotazione, con modalità e importo inviati,
 * registrato dall'operatore della sessione, con creazione e pagamento valorizzati.
 */
export function parsePagamentoSede(result, idPrenotazione, idOperatore, modalita, importoAtteso) {
  const d = result?.data
  if (result?.status !== 201 || !result.hasBody || !isObject(d) || !isId(d.idPagamento) || d.idPrenotazione !== idPrenotazione
      || d.modalita !== modalita || d.importo !== importoAtteso || d.stato !== 'PAGATO' || d.idOperatore !== idOperatore
      || !istante(d.creatoIl) || !istante(d.pagatoIl)) {
    throw new ProtocolError("Esito dell'incasso non conforme")
  }
  return Object.freeze({ idPagamento: d.idPagamento, idPrenotazione, modalita, importo: d.importo, stato: d.stato })
}

/** POST /api/prenotazioni/{id}/verifica-mancata-erogazione da CONFERMATA. */
export function postMancataErogazione(requestFn, idPrenotazione, idOperatore, esito) {
  if (!isId(idPrenotazione) || !ESITI_MANCATA_EROGAZIONE.includes(esito)) throw new TypeError('Esito non valido.')
  return requestFn({
    method: 'POST', path: `/api/prenotazioni/${idPrenotazione}/verifica-mancata-erogazione`,
    json: corpoOperatore(idOperatore, { statoAtteso: 'CONFERMATA', esito }),
  })
}

/** Risposta 200: stessa prenotazione, stato uguale all'esito, operatore della sessione e data di gestione. */
export function parseMancataErogazione(result, idPrenotazione, idOperatore, esito) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || d.idPrenotazione !== idPrenotazione || d.stato !== esito
      || d.idOperatore !== idOperatore || !istante(d.dataOraGestione)) {
    throw new ProtocolError("Esito della registrazione non conforme")
  }
  return Object.freeze({ idPrenotazione, stato: d.stato })
}
