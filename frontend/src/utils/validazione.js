import { isLocalDate, todayLocal } from './datetime.js'

/**
 * Controlli di cortesia allineati ai limiti verificati nel backend
 * (RegistrazioneService, ValidazioneAccount, CodiceFiscaleValidator). Il backend resta
 * autorevole: un 400 è sempre possibile. Il codice fiscale è controllato solo nella forma
 * (16 caratteri alfanumerici), senza duplicarne il validatore.
 */

const EMAIL = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/
const CONTROLLO = /\p{Cc}/u

/** Surrogato UTF-16 isolato: rifiutato dal backend nelle password. */
export function hasLoneSurrogate(value) {
  return /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value)
}

/** Lunghezza della password in unità UTF-16, come `char[]` Java; mai trim. */
export function passwordError(password, min) {
  if (password.length < min) return `Usa almeno ${min} caratteri.`
  if (password.length > 1024) return 'Usa al massimo 1024 caratteri.'
  if (hasLoneSurrogate(password)) return 'La password contiene caratteri non validi.'
  return null
}

function testo(value, max, etichetta) {
  const t = value.trim()
  if (t === '') return `Indica ${etichetta}.`
  if ([...t].length > max) return `Usa al massimo ${max} caratteri.`
  if (CONTROLLO.test(t)) return 'Contiene caratteri non ammessi.'
  return null
}

/**
 * Stesso ordine del backend: strip, limite di 255 code point e caratteri di controllo sul
 * testo originale, poi minuscolo indipendente dalla lingua (come Locale.ROOT; mai
 * toLocaleLowerCase) e solo dopo la sintassi. Esempio: il simbolo Kelvin «K» diventa «k».
 * L'email viene comunque inviata come scritta: normalizza il backend, che resta autorevole.
 */
export function emailError(value) {
  const stripped = value.trim()
  if (stripped === '') return "Indica l'indirizzo email."
  if ([...stripped].length > 255 || CONTROLLO.test(stripped)) return 'Indirizzo email non valido.'
  const email = stripped.toLowerCase()
  if (!EMAIL.test(email)) return 'Indirizzo email non valido.'
  const local = email.slice(0, email.indexOf('@'))
  if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..')) return 'Indirizzo email non valido.'
  return null
}

/** @returns {Record<string,string>} errori per campo, vuoto se tutto è plausibile */
export function validaRegistrazione(f, oggi = todayLocal()) {
  const errori = {}
  const set = (campo, errore) => { if (errore) errori[campo] = errore }
  set('nome', testo(f.nome, 100, 'il nome'))
  set('cognome', testo(f.cognome, 100, 'il cognome'))
  set('email', emailError(f.email))
  set('password', passwordError(f.password, 8))
  if (!errori.password && f.conferma !== f.password) errori.conferma = 'Le due password non coincidono.'
  Object.assign(errori, validaProfiloPaziente(f, oggi))
  return errori
}

/** Minimo della nuova password: 12 per medico o ruolo gestionale, 8 per gli altri account. */
export function minimoNuovaPassword(user) {
  return user?.medico === true || (user?.ruoloGestionale ?? null) !== null ? 12 : 8
}

/** Dati UC-01 riusati nel collegamento; il futuro è deciso dal server, se manca una data server. */
export function validaProfiloPaziente(f, oggi = null) {
  const errori = {}
  if (!/^[A-Za-z0-9]{16}$/.test(f.codiceFiscale.trim())) errori.codiceFiscale = 'Il codice fiscale deve avere 16 lettere o cifre.'
  if (!isLocalDate(f.dataNascita) || Number(f.dataNascita.slice(0, 4)) < 1000) errori.dataNascita = 'Indica una data di nascita valida.'
  else if (oggi !== null && f.dataNascita > oggi) errori.dataNascita = 'La data di nascita non può essere futura.'
  const tel = f.telefono.trim()
  if (tel !== '' && (tel.length > 20 || !/^[+0-9 ()-]+$/.test(tel) || !/[0-9]/.test(tel))) {
    errori.telefono = 'Usa al massimo 20 caratteri tra cifre, spazi, +, - e parentesi.'
  }
  return errori
}
