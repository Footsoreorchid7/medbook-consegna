import { ProtocolError } from './http.js'
import { RUOLI, emailConfronto, erroreNuovaPassword, isId, normalizzaEmail, normalizzaNome, strip } from './utenti.js'

/**
 * Gestione amministrativa dei medici . Contratti verificati in MedicoController,
 * MedicoService e MedicoRepository.
 *
 * Proiezione amministrativa (MedicoResponse), distinta da quella desk: 9 campi con email, profilo
 * paziente e ruolo gestionale; `stato` è lo stato operativo del medico (ATTIVO/INATTIVO), restituito
 * grezzo: un valore anomalo resta anomalo e non viene normalizzato. Lo stato di accesso dell'account
 * non fa parte di questa proiezione e non viene dedotto.
 *
 * Ogni conflitto è un 409 generico («Operazione in conflitto.»): la causa non è indicata dal server.
 */

export const STATI_MEDICO = Object.freeze(['ATTIVO', 'INATTIVO'])
const MAX_SPECIALIZZAZIONE = 150
const MAX_NUMERO = 50
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const stesseChiavi = (d, chiavi) => {
  const k = Object.keys(d)
  return k.length === chiavi.length && chiavi.every((c) => Object.hasOwn(d, c))
}
const isAmount = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0
const CONTROLLO = /[\u0000-\u001F\u007F-\u009F]/u
const SURROGATO_ISOLATO = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/

/** Testo come ValidazioneAccount.testo(valore, massimo): strip, 1..massimo code point, niente controllo. */
export function normalizzaTesto(value, massimo) {
  if (!isText(value) || SURROGATO_ISOLATO.test(value)) return null
  const s = strip(value)
  return s === '' || [...s].length > massimo || CONTROLLO.test(s) ? null : s
}

// ---------------------------------------------------------------- proiezioni

/** MedicoResponse amministrativa: esattamente 9 campi. `statoAnomalo` se lo stato non è canonico. */
export function parseMedicoAdmin(d) {
  if (!isObject(d) || !stesseChiavi(d, ['idUtente', 'nome', 'cognome', 'email', 'specializzazione', 'numeroIscrizioneOrdine', 'stato', 'paziente', 'ruoloGestionale'])
      || !isId(d.idUtente) || !isText(d.nome) || !isText(d.cognome) || !isText(d.email)
      || !isText(d.specializzazione) || !isText(d.numeroIscrizioneOrdine) || !isText(d.stato)
      || typeof d.paziente !== 'boolean' || !(d.ruoloGestionale === null || RUOLI.includes(d.ruoloGestionale))) {
    throw new ProtocolError('Medico non valido')
  }
  return Object.freeze({
    proiezione: 'medico-admin', idUtente: d.idUtente, nome: d.nome, cognome: d.cognome, email: d.email,
    specializzazione: d.specializzazione, numeroIscrizioneOrdine: d.numeroIscrizioneOrdine,
    stato: d.stato, statoAnomalo: !STATI_MEDICO.includes(d.stato), paziente: d.paziente, ruolo: d.ruoloGestionale,
  })
}

export function parsePrestazioneMedico(p) {
  if (!isObject(p) || !isId(p.idPrestazione) || !isText(p.nome) || !(p.descrizione === null || isText(p.descrizione))
      || !Number.isSafeInteger(p.durata) || p.durata <= 0 || !isAmount(p.prezzo)) {
    throw new ProtocolError('Prestazione non valida')
  }
  return Object.freeze({ idPrestazione: p.idPrestazione, nome: p.nome, descrizione: p.descrizione, durata: p.durata, prezzo: p.prezzo })
}

// ---------------------------------------------------------------- letture

/** Percorso dell'elenco: filtri facoltativi stato (ATTIVO/INATTIVO) e idPrestazione. */
export function mediciAdminPath({ stato = '', idPrestazione = null } = {}) {
  const q = []
  if (stato) {
    if (!STATI_MEDICO.includes(stato)) throw new TypeError('Stato non valido.')
    q.push(`stato=${stato}`)
  }
  if (idPrestazione !== null) {
    if (!isId(idPrestazione)) throw new TypeError('Prestazione non valida.')
    q.push(`idPrestazione=${idPrestazione}`)
  }
  return q.length ? `/api/medici?${q.join('&')}` : '/api/medici'
}

/** GET /api/medici con proiezione amministrativa, per ID crescente. */
export async function fetchMediciAdmin(requestFn, filtri = {}) {
  const result = await requestFn({ path: mediciAdminPath(filtri) })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  const voci = result.data.map(parseMedicoAdmin)
  if (voci.some((v, i) => i > 0 && v.idUtente <= voci[i - 1].idUtente)) throw new ProtocolError('Ordine non valido')
  return Object.freeze(voci)
}

/** GET /api/medici/{id}: { medico, prestazioniAutorizzate } con proiezione amministrativa. */
export async function fetchMedicoAdmin(requestFn, idMedico) {
  if (!isId(idMedico)) throw new TypeError('Medico non valido.')
  const result = await requestFn({ path: `/api/medici/${idMedico}` })
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || !stesseChiavi(d, ['medico', 'prestazioniAutorizzate']) || !Array.isArray(d.prestazioniAutorizzate)) {
    throw new ProtocolError('Medico atteso')
  }
  const medico = parseMedicoAdmin(d.medico)
  if (medico.idUtente !== idMedico) throw new ProtocolError('Medico non corrispondente')
  const prestazioni = d.prestazioniAutorizzate.map(parsePrestazioneMedico)
  if (prestazioni.some((p, i) => i > 0 && p.idPrestazione <= prestazioni[i - 1].idPrestazione)) throw new ProtocolError('Prestazioni non ordinate')
  return Object.freeze({ medico, prestazioni: Object.freeze(prestazioni) })
}

// ---------------------------------------------------------------- validazioni

function professionali(f, errori) {
  const specializzazione = normalizzaTesto(f.specializzazione ?? '', MAX_SPECIALIZZAZIONE)
  const numero = normalizzaTesto(f.numeroIscrizioneOrdine ?? '', MAX_NUMERO)
  if (specializzazione === null) errori.specializzazione = `Indica la specializzazione (al massimo ${MAX_SPECIALIZZAZIONE} caratteri).`
  if (numero === null) errori.numeroIscrizioneOrdine = `Indica il numero di iscrizione all'ordine (al massimo ${MAX_NUMERO} caratteri).`
  return { specializzazione, numeroIscrizioneOrdine: numero }
}
function personali(f, errori) {
  const nome = normalizzaNome(f.nome ?? '')
  const cognome = normalizzaNome(f.cognome ?? '')
  const email = normalizzaEmail(f.email ?? '')
  if (nome === null) errori.nome = 'Indica il nome (al massimo 100 caratteri, senza caratteri di controllo).'
  if (cognome === null) errori.cognome = 'Indica il cognome (al massimo 100 caratteri, senza caratteri di controllo).'
  if (email === null) errori.email = 'Indirizzo email non valido.'
  return { nome, cognome, email }
}
const esito = (errori, dati) => ({ errori, dati: Object.keys(errori).length ? null : Object.freeze(dati) })

/** Nuovo medico: dati normalizzati come il backend; la password non è mai normalizzata. */
export function validaNuovoMedico(f) {
  const errori = {}
  const dati = { ...personali(f, errori), ...professionali(f, errori) }
  const p = erroreNuovaPassword(f.password ?? '')
  if (p) errori.password = p
  return esito(errori, dati)
}
export const validaCollegamento = (f) => { const errori = {}; return esito(errori, professionali(f, errori)) }
export const validaPersonali = (f) => { const errori = {}; return esito(errori, personali(f, errori)) }
export const validaProfessionali = (f) => { const errori = {}; return esito(errori, professionali(f, errori)) }

// ---------------------------------------------------------------- scritture

/** POST /api/medici con i soli sei campi del contratto. */
export function postMedico(requestFn, dati, password) {
  if (erroreNuovaPassword(password)) throw new TypeError('Password non valida.')
  return requestFn({
    method: 'POST', path: '/api/medici',
    json: { nome: dati.nome, cognome: dati.cognome, email: dati.email, password, specializzazione: dati.specializzazione, numeroIscrizioneOrdine: dati.numeroIscrizioneOrdine },
  })
}

/** POST /api/medici/{idUtente}/collegamento con i soli dati professionali. */
export function postCollegamento(requestFn, idUtente, dati) {
  if (!isId(idUtente)) throw new TypeError('Utente non valido.')
  return requestFn({
    method: 'POST', path: `/api/medici/${idUtente}/collegamento`,
    json: { specializzazione: dati.specializzazione, numeroIscrizioneOrdine: dati.numeroIscrizioneOrdine },
  })
}

/** PUT /api/medici/{id}: variante completa ed esclusiva (PERSONALI oppure PROFESSIONALI). */
export function putDatiMedico(requestFn, idMedico, tipo, dati) {
  if (!isId(idMedico)) throw new TypeError('Medico non valido.')
  let json
  if (tipo === 'PERSONALI') json = { tipo, nome: dati.nome, cognome: dati.cognome, email: dati.email }
  else if (tipo === 'PROFESSIONALI') json = { tipo, specializzazione: dati.specializzazione, numeroIscrizioneOrdine: dati.numeroIscrizioneOrdine }
  else throw new TypeError('Variante non valida.')
  return requestFn({ method: 'PUT', path: `/api/medici/${idMedico}`, json })
}

export function putStatoMedico(requestFn, idMedico, stato) {
  if (!isId(idMedico) || !STATI_MEDICO.includes(stato)) throw new TypeError('Dati non validi.')
  return requestFn({ method: 'PUT', path: `/api/medici/${idMedico}/stato`, json: { stato } })
}

export function putAbilitazione(requestFn, idMedico, operazione, idPrestazione) {
  if (!isId(idMedico) || !isId(idPrestazione) || !['ASSEGNA', 'RIMUOVI'].includes(operazione)) throw new TypeError('Dati non validi.')
  return requestFn({ method: 'PUT', path: `/api/medici/${idMedico}/prestazioni`, json: { operazione, idPrestazione } })
}

// ---------------------------------------------------------------- esiti delle scritture

/** Risposta MedicoResponse con stato atteso e verifica dei campi indicati (email nella forma di confronto). */
export function parseEsitoMedico(result, status, idMedico, atteso = {}) {
  if (result?.status !== status || !result.hasBody) throw new ProtocolError('Esito non conforme')
  const m = parseMedicoAdmin(result.data)
  if (idMedico !== null && m.idUtente !== idMedico) throw new ProtocolError('Esito non conforme')
  for (const [k, v] of Object.entries(atteso)) {
    const ok = k === 'email' ? emailConfronto(m.email) === emailConfronto(v) : m[k] === v
    if (!ok) throw new ProtocolError('Esito non conforme')
  }
  return m
}

/** 200 {idMedico, idPrestazione, operazione} coerente con la richiesta. */
export function parseEsitoAbilitazione(result, idMedico, operazione, idPrestazione) {
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || !stesseChiavi(d, ['idMedico', 'idPrestazione', 'operazione'])
      || d.idMedico !== idMedico || d.idPrestazione !== idPrestazione || d.operazione !== operazione) {
    throw new ProtocolError('Esito non conforme')
  }
  return Object.freeze({ idMedico, idPrestazione, operazione })
}

/** Reset amministrativo, distinto dal cambio password personale. */
export async function putPasswordMedico(requestFn, idMedico, password) {
  if (!isId(idMedico) || erroreNuovaPassword(password)) throw new TypeError('Password non valida.')
  const r = await requestFn({ method: 'PUT', path: `/api/medici/${idMedico}/password`, json: { nuovaPassword: password } })
  const d = r?.data
  if (r?.status !== 200 || !r.hasBody || !isObject(d) || !stesseChiavi(d, ['idMedico','message','sessioniInvalidate']) || d.idMedico !== idMedico
      || typeof d.message !== 'string' || !d.message.trim() || d.sessioniInvalidate !== true)
    throw new ProtocolError('Esito reset non conforme')
  return { idMedico, message: d.message, sessioniInvalidate: true }
}
