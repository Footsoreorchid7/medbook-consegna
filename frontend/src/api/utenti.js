import { ProtocolError } from './http.js'

/**
 * Gestione amministrativa degli utenti . Contratti verificati in UtenteController,
 * UtenteService, ValidazioneAccount, ValidazioneRicercaUtenti e UtenteRepository.
 *
 * Due proiezioni distinte:
 * - account gestionale (GET /api/utenti, POST /api/utenti, PUT /ruolo): 8 campi, con i profili
 *   paziente/medico e senza codice fiscale; ruolo sempre presente;
 * - vista amministrativa (GET /api/utenti/ricerca, PUT /stato): 7 campi, con codice fiscale e
 *   ruolo eventualmente nulli, senza profili. I profili assenti non vengono mai dedotti.
 *
 * I controlli locali sono di cortesia: il backend resta autorevole e un 400 è sempre possibile.
 */

const MAX_INT = 2147483647
export const RUOLI = Object.freeze(['AMMINISTRATORE', 'OPERATORE_DESK'])
export const STATI_ACCESSO = Object.freeze(['ATTIVO', 'DISABILITATO'])
export const RUOLI_RICERCA = Object.freeze([...RUOLI, 'NESSUNO'])
export const LIMITE_PAGINA = 20

export const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const stesseChiavi = (d, chiavi) => {
  const k = Object.keys(d)
  return k.length === chiavi.length && chiavi.every((c) => Object.hasOwn(d, c))
}

// ---------------------------------------------------------------- normalizzazioni come il backend

/** Spazi rimossi da String.strip() di Java (Character.isWhitespace): non lo spazio non separabile. */
const SPAZIO_JAVA = '[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]'
const BORDI = new RegExp(`^${SPAZIO_JAVA}+|${SPAZIO_JAVA}+$`, 'gu')
export const strip = (v) => v.replace(BORDI, '')
/**
 * Forma di confronto di un'email, come la normalizzazione del backend (ValidazioneAccount.email):
 * spazi esterni rimossi come String.strip() e minuscolo indipendente dalla lingua. Unica regola per
 * chiavi, confronti esatti, prefissi e valori restituiti dalle API; il valore da mostrare resta
 * quello originale.
 */
export const emailConfronto = (v) => strip(String(v)).toLowerCase()
/** Caratteri di controllo ISO (Character.isISOControl). */
const CONTROLLO = /[\u0000-\u001F\u007F-\u009F]/u
const SURROGATO_ISOLATO = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/
const codePoint = (v) => [...v].length
const EMAIL = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/

/** Email come ValidazioneAccount.email: strip, minuscolo indipendente dalla lingua, sintassi. Null se non valida. */
export function normalizzaEmail(value) {
  if (!isText(value) || SURROGATO_ISOLATO.test(value)) return null
  const s = strip(value)
  if (s === '' || codePoint(s) > 255 || CONTROLLO.test(s)) return null
  const email = s.toLowerCase()
  if (!EMAIL.test(email)) return null
  const local = email.slice(0, email.indexOf('@'))
  if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..')) return null
  return email
}

/** Nome o cognome come ValidazioneAccount.testo(…, 100). Null se non valido. */
export function normalizzaNome(value) {
  if (!isText(value) || SURROGATO_ISOLATO.test(value)) return null
  const s = strip(value)
  return s === '' || codePoint(s) > 100 || CONTROLLO.test(s) ? null : s
}

/** Password interna: 12–1024 unità UTF-16, nessun surrogato isolato, mai trim. */
export function erroreNuovaPassword(password) {
  if (!isText(password) || password.length < 12) return 'Usa almeno 12 caratteri.'
  if (password.length > 1024) return 'Usa al massimo 1024 caratteri.'
  if (SURROGATO_ISOLATO.test(password)) return 'La password contiene caratteri non validi.'
  return null
}

const OMOCODIA = 'LMNPQRSTUV'
const MESI = 'ABCDEHLMPRST'
const DISPARI = [1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23]
const cifra = (c) => (c >= '0' && c <= '9' ? c.charCodeAt(0) - 48 : OMOCODIA.indexOf(c))

/** Codice fiscale come CodiceFiscaleValidator.normalizzaEValida (forma, data, checksum). Null se non valido. */
export function normalizzaCodiceFiscale(value) {
  if (!isText(value)) return null
  const input = strip(value)
  if (!/^[A-Za-z0-9]{16}$/.test(input)) return null
  const cf = input.toUpperCase()
  if (!/^[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]$/.test(cf)) return null
  const anno = cifra(cf[6]) * 10 + cifra(cf[7])
  let giorno = cifra(cf[9]) * 10 + cifra(cf[10])
  if (giorno >= 41 && giorno <= 71) giorno -= 40
  else if (giorno < 1 || giorno > 31) return null
  const mese = MESI.indexOf(cf[8])
  const data = new Date(Date.UTC(2000 + anno, mese, giorno))
  if (data.getUTCMonth() !== mese || data.getUTCDate() !== giorno) return null
  let somma = 0
  for (let i = 0; i < 15; i++) {
    const c = cf[i]
    const n = c >= '0' && c <= '9' ? c.charCodeAt(0) - 48 : c.charCodeAt(0) - 65
    somma += i % 2 === 0 ? DISPARI[n] : n
  }
  return cf[15] === String.fromCharCode(65 + (somma % 26)) ? cf : null
}

/** Prefisso di ricerca: strip, minuscolo opzionale, lunghezza in code point, niente controllo. */
function prefisso(value, minimo, massimo, minuscolo) {
  if (SURROGATO_ISOLATO.test(value)) return null
  let v = strip(value)
  if (minuscolo) v = v.toLowerCase()
  const n = codePoint(v)
  return n < minimo || n > massimo || CONTROLLO.test(v) ? null : v
}

// ---------------------------------------------------------------- proiezioni

/** AccountInternoResponse: account gestionale con i profili. */
export function parseAccount(d) {
  if (!isObject(d) || !stesseChiavi(d, ['idUtente', 'nome', 'cognome', 'email', 'ruoloGestionale', 'paziente', 'medico', 'statoAccesso'])
      || !isId(d.idUtente) || !isText(d.nome) || !isText(d.cognome) || !isText(d.email) || !RUOLI.includes(d.ruoloGestionale)
      || typeof d.paziente !== 'boolean' || typeof d.medico !== 'boolean' || !STATI_ACCESSO.includes(d.statoAccesso)) {
    throw new ProtocolError('Account gestionale non valido')
  }
  return Object.freeze({
    proiezione: 'gestionale', idUtente: d.idUtente, nome: d.nome, cognome: d.cognome, email: d.email,
    ruolo: d.ruoloGestionale, statoAccesso: d.statoAccesso, paziente: d.paziente, medico: d.medico,
  })
}

/** UtenteAmministrativoResponse: vista amministrativa senza profili (mai dedotti). */
export function parseUtenteAmministrativo(d) {
  if (!isObject(d) || !stesseChiavi(d, ['idUtente', 'nome', 'cognome', 'codiceFiscale', 'email', 'ruoloGestionale', 'statoAccesso'])
      || !isId(d.idUtente) || !isText(d.nome) || !isText(d.cognome) || !isText(d.email)
      || !(d.codiceFiscale === null || isText(d.codiceFiscale)) || !(d.ruoloGestionale === null || RUOLI.includes(d.ruoloGestionale))
      || !STATI_ACCESSO.includes(d.statoAccesso)) {
    throw new ProtocolError('Utente non valido')
  }
  return Object.freeze({
    proiezione: 'amministrativa', idUtente: d.idUtente, nome: d.nome, cognome: d.cognome, email: d.email,
    codiceFiscale: d.codiceFiscale, ruolo: d.ruoloGestionale, statoAccesso: d.statoAccesso,
  })
}

// ---------------------------------------------------------------- letture

/** GET /api/utenti: tutti gli account gestionali (anche disabilitati), per ID crescente. Nessuna query. */
export async function fetchGestionali(requestFn) {
  const result = await requestFn({ path: '/api/utenti' })
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  const voci = result.data.map(parseAccount)
  if (voci.some((v, i) => i > 0 && v.idUtente <= voci[i - 1].idUtente)) throw new ProtocolError('Ordine non valido')
  return Object.freeze(voci)
}

/**
 * Filtri di ricerca validati come ValidazioneRicercaUtenti. Il frontend richiede almeno un filtro
 * identificativo (ID, email, cognome o codice fiscale): ruolo e stato da soli restituirebbero un
 * elenco generale (il backend li accetta, la pagina no). Restituisce { filtri, errori }.
 * @param {{idUtente?:string, email?:string, cognome?:string, codiceFiscale?:string, ruolo?:string, statoAccesso?:string}} f
 */
export function validaFiltri(f) {
  const filtri = {}
  const errori = {}
  const id = (f.idUtente ?? '').trim()
  if (id !== '') {
    if (/^[1-9][0-9]{0,9}$/.test(id) && Number(id) <= MAX_INT) filtri.idUtente = id
    else errori.idUtente = 'Indica un numero intero positivo, senza zeri iniziali.'
  }
  if ((f.email ?? '') !== '') {
    const v = prefisso(f.email, 3, 255, true)
    if (v === null) errori.email = "Indica da 3 a 255 caratteri dell'inizio dell'email."
    else filtri.email = v
  }
  if ((f.cognome ?? '') !== '') {
    const v = prefisso(f.cognome, 2, 100, false)
    if (v === null) errori.cognome = "Indica da 2 a 100 caratteri dell'inizio del cognome."
    else filtri.cognome = v
  }
  if ((f.codiceFiscale ?? '') !== '') {
    const v = normalizzaCodiceFiscale(f.codiceFiscale)
    if (v === null) errori.codiceFiscale = 'Codice fiscale non valido.'
    else filtri.codiceFiscale = v
  }
  if (f.ruolo) {
    if (RUOLI_RICERCA.includes(f.ruolo)) filtri.ruolo = f.ruolo
    else errori.ruolo = 'Ruolo non valido.'
  }
  if (f.statoAccesso) {
    if (STATI_ACCESSO.includes(f.statoAccesso)) filtri.statoAccesso = f.statoAccesso
    else errori.statoAccesso = 'Stato non valido.'
  }
  const identificativi = ['idUtente', 'email', 'cognome', 'codiceFiscale']
  if (Object.keys(errori).length === 0 && !identificativi.some((k) => k in filtri)) {
    errori.generale = 'Indica almeno uno tra ID, email, cognome e codice fiscale. Ruolo e stato servono solo a restringere la ricerca.'
  }
  return { filtri: Object.freeze(filtri), errori }
}

const ORDINE_FILTRI = ['idUtente', 'email', 'cognome', 'codiceFiscale', 'ruolo', 'statoAccesso']

/** Percorso di GET /api/utenti/ricerca con filtri validati e cursore keyset. */
export function ricercaPath(filtri, dopoId = 0, limite = LIMITE_PAGINA) {
  if (!Number.isSafeInteger(dopoId) || dopoId < 0 || dopoId > MAX_INT || !Number.isSafeInteger(limite) || limite < 1 || limite > 50) {
    throw new TypeError('Paginazione non valida.')
  }
  const q = ORDINE_FILTRI.filter((k) => filtri[k] !== undefined).map((k) => `${k}=${encodeURIComponent(filtri[k])}`)
  if (q.length === 0) throw new TypeError('Almeno un filtro è obbligatorio.')
  if (dopoId > 0) q.push(`dopoId=${dopoId}`)
  q.push(`limite=${limite}`)
  return `/api/utenti/ricerca?${q.join('&')}`
}

/**
 * Una pagina della ricerca: risultati per ID crescente, tutti oltre il cursore, al più `limite`;
 * prossimoDopoId è l'ultimo ID di una pagina piena quando esistono altri risultati, altrimenti null.
 */
export async function fetchRicerca(requestFn, filtri, dopoId = 0, limite = LIMITE_PAGINA) {
  const result = await requestFn({ path: ricercaPath(filtri, dopoId, limite) })
  const d = result?.data
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || !stesseChiavi(d, ['risultati', 'prossimoDopoId']) || !Array.isArray(d.risultati)) {
    throw new ProtocolError('Ricerca non valida')
  }
  const risultati = d.risultati.map(parseUtenteAmministrativo)
  if (risultati.length > limite || risultati.some((v, i) => v.idUtente <= (i === 0 ? dopoId : risultati[i - 1].idUtente))) {
    throw new ProtocolError('Pagina non valida')
  }
  const prossimo = d.prossimoDopoId
  if (prossimo !== null && (!isId(prossimo) || risultati.length !== limite || prossimo !== risultati.at(-1).idUtente)) {
    throw new ProtocolError('Cursore non valido')
  }
  return Object.freeze({ risultati: Object.freeze(risultati), prossimoDopoId: prossimo })
}

// ---------------------------------------------------------------- scritture

/** Dati di creazione validati: { dati, errori }. La password non viene mai normalizzata. */
export function validaNuovoAccount(f) {
  const errori = {}
  const nome = normalizzaNome(f.nome ?? '')
  const cognome = normalizzaNome(f.cognome ?? '')
  const email = normalizzaEmail(f.email ?? '')
  if (nome === null) errori.nome = 'Indica il nome (al massimo 100 caratteri, senza caratteri di controllo).'
  if (cognome === null) errori.cognome = 'Indica il cognome (al massimo 100 caratteri, senza caratteri di controllo).'
  if (email === null) errori.email = 'Indirizzo email non valido.'
  const p = erroreNuovaPassword(f.password ?? '')
  if (p) errori.password = p
  if (!RUOLI.includes(f.ruolo)) errori.ruolo = 'Scegli il ruolo.'
  return { errori, dati: Object.keys(errori).length ? null : Object.freeze({ nome, cognome, email, ruolo: f.ruolo }) }
}

/** POST /api/utenti con i soli cinque campi previsti; nome, cognome ed email già normalizzati. */
export function postAccount(requestFn, dati, password) {
  if (!RUOLI.includes(dati?.ruolo) || erroreNuovaPassword(password)) throw new TypeError('Dati non validi.')
  return requestFn({
    method: 'POST', path: '/api/utenti',
    json: { nome: dati.nome, cognome: dati.cognome, email: dati.email, password, ruoloGestionale: dati.ruolo },
  })
}

/** 201 con un account gestionale nuovo, attivo, senza profili e con i dati inviati. */
export function parseCreazione(result, dati) {
  if (result?.status !== 201 || !result.hasBody) throw new ProtocolError('Creazione non conforme')
  const a = parseAccount(result.data)
  if (a.nome !== dati.nome || a.cognome !== dati.cognome || emailConfronto(a.email) !== emailConfronto(dati.email) || a.ruolo !== dati.ruolo
      || a.statoAccesso !== 'ATTIVO' || a.paziente || a.medico) {
    throw new ProtocolError('Creazione non conforme')
  }
  return a
}

/** PUT /api/utenti/{id}/ruolo: solo i due ruoli gestionali (nessuna rimozione con null). */
export function putRuolo(requestFn, idUtente, ruolo) {
  if (!isId(idUtente) || !RUOLI.includes(ruolo)) throw new TypeError('Dati non validi.')
  return requestFn({ method: 'PUT', path: `/api/utenti/${idUtente}/ruolo`, json: { ruoloGestionale: ruolo } })
}

export function parseRuolo(result, idUtente, ruolo) {
  if (result?.status !== 200 || !result.hasBody) throw new ProtocolError('Esito del ruolo non conforme')
  const a = parseAccount(result.data)
  if (a.idUtente !== idUtente || a.ruolo !== ruolo) throw new ProtocolError('Esito del ruolo non conforme')
  return a
}

/** PUT /api/utenti/{id}/stato: stato di accesso dell'account (non lo stato operativo del medico). */
export function putStato(requestFn, idUtente, stato) {
  if (!isId(idUtente) || !STATI_ACCESSO.includes(stato)) throw new TypeError('Dati non validi.')
  return requestFn({ method: 'PUT', path: `/api/utenti/${idUtente}/stato`, json: { statoAccesso: stato } })
}

/** 200 con la vista amministrativa (7 campi, senza profili). */
export function parseStato(result, idUtente, stato) {
  if (result?.status !== 200 || !result.hasBody) throw new ProtocolError('Esito dello stato non conforme')
  const u = parseUtenteAmministrativo(result.data)
  if (u.idUtente !== idUtente || u.statoAccesso !== stato) throw new ProtocolError('Esito dello stato non conforme')
  return u
}
