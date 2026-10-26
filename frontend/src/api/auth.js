import { ProtocolError, request } from './http.js'

const RUOLI = new Set(['AMMINISTRATORE', 'OPERATORE_DESK'])
const CSRF = /^[A-Za-z0-9_-]{43}$/

export function fetchMe(options = {}) {
  return request({ path: '/api/auth/me', ...options })
}

/** Invia soltanto email e password; nessun vincolo di lunghezza lato client. */
export function postLogin(email, password, options = {}) {
  return request({ method: 'POST', path: '/api/auth/login', json: { email, password }, ...options })
}

/** POST senza corpo: il backend rifiuta un logout con corpo non vuoto. */
export function postLogout(csrfToken, options = {}) {
  return request({ method: 'POST', path: '/api/auth/logout', csrfToken, ...options })
}

/**
 * Struttura minima di una risposta 200 di login o /auth/me: identità valida e
 * header X-CSRF-Token. Qualsiasi difformità è un errore di protocollo.
 */
export function parseAuthenticated(result) {
  const data = result?.data
  if (!result?.hasBody || !data || typeof data !== 'object' || Array.isArray(data)) {
    throw new ProtocolError('Identità assente')
  }
  const { idUtente, nome, cognome, paziente, medico, ruoloGestionale } = data
  if (!Number.isSafeInteger(idUtente) || idUtente <= 0
      || typeof nome !== 'string' || typeof cognome !== 'string'
      || typeof paziente !== 'boolean' || typeof medico !== 'boolean'
      || !(ruoloGestionale === null || RUOLI.has(ruoloGestionale))) {
    throw new ProtocolError('Identità non valida')
  }
  const csrfToken = result.headers?.get('X-CSRF-Token')
  if (typeof csrfToken !== 'string' || !CSRF.test(csrfToken)) {
    throw new ProtocolError('Token CSRF assente o non valido')
  }
  return {
    user: Object.freeze({ idUtente, nome, cognome, paziente, medico, ruoloGestionale }),
    csrfToken,
  }
}

const isMessage = (data) => data && typeof data === 'object' && !Array.isArray(data)
  && typeof data.message === 'string' && data.message.trim() !== ''

/**
 * POST /api/auth/register: solo i campi ammessi dal backend. `telefono` è inviato solo se
 * valorizzato. La password non viene trasformata; la conferma non esce mai dal form.
 * Successo solo con 201 e un oggetto con `message` non vuoto (testo non vincolante);
 * ogni altro esito 2xx è un errore di protocollo, cioè un esito incerto.
 */
export async function postRegister({ nome, cognome, email, password, codiceFiscale, dataNascita, telefono }, options = {}) {
  const json = { nome, cognome, email, password, codiceFiscale, dataNascita }
  if (typeof telefono === 'string' && telefono.trim() !== '') json.telefono = telefono
  const result = await request({ method: 'POST', path: '/api/auth/register', json, ...options })
  if (result.status !== 201 || !result.hasBody || !isMessage(result.data)) {
    throw new ProtocolError('Esito della registrazione non conforme')
  }
  return result
}

/** PUT /api/auth/password con il CSRF della sessione corrente. */
export function putPassword(csrfToken, passwordAttuale, nuovaPassword, options = {}) {
  return request({ method: 'PUT', path: '/api/auth/password', json: { passwordAttuale, nuovaPassword }, csrfToken, ...options })
}

/**
 * Risposta del cambio password, validata per intero prima di toccare la sessione:
 * - status esattamente 200, `message` stringa non vuota (testo non vincolante),
 *   `nuovoAccessoRichiesto` booleano;
 * - false: X-CSRF-Token valido della sessione ruotata;
 * - true: nessun X-CSRF-Token (il backend lo rimuove), altrimenti la risposta è contraddittoria.
 * Ogni difformità, compreso un 2xx diverso da 200, è un errore di protocollo.
 */
export function parsePasswordChange(result) {
  const data = result?.data
  if (result?.status !== 200 || !result.hasBody || !isMessage(data) || typeof data.nuovoAccessoRichiesto !== 'boolean') {
    throw new ProtocolError('Esito del cambio password non valido')
  }
  const csrfToken = result.headers?.get('X-CSRF-Token') ?? null
  if (data.nuovoAccessoRichiesto) {
    if (csrfToken !== null) throw new ProtocolError('Token CSRF inatteso')
    return { nuovoAccessoRichiesto: true, csrfToken: null }
  }
  if (typeof csrfToken !== 'string' || !CSRF.test(csrfToken)) throw new ProtocolError('Token CSRF assente o non valido')
  return { nuovoAccessoRichiesto: false, csrfToken }
}
