import { ProtocolError } from './http.js'

/**
 * Cataloghi amministrativi : stanze e prestazioni. Contratti verificati in StanzaController,
 * StanzaService, StanzaRepository, PrestazioneController, PrestazioneService, PrestazioneRepository
 * e nello schema operativo.
 *
 * - Stanza: { idStanza, nome, descrizione|null }. Nome con trim() di Java, 1–100 code point, unico
 *   (vincolo uq_stanza_nome; il confronto dipende dalla collation e non viene dedotto qui);
 *   descrizione facoltativa, non trimmata, al massimo 255 code point.
 * - Prestazione: { idPrestazione, nome, descrizione|null, durata, prezzo }. Nome con trim(), 1–150
 *   code point, non unico; descrizione non trimmata, al massimo 65535 byte UTF-8; durata intera
 *   1–60 minuti; prezzo 0–99999999.99 con al più due decimali (mai arrotondato dal backend:
 *   più decimali sono un 400).
 * - Corpo JSON al massimo 8192 byte (UTF-8), solo i campi previsti.
 * Nessun DELETE né stato attivo/inattivo: non esistono nel contratto.
 */

const MAX_INT = 2147483647
const MAX_CORPO = 8192
export const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isText = (v) => typeof v === 'string'
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const stesseChiavi = (d, chiavi) => {
  const k = Object.keys(d)
  return k.length === chiavi.length && chiavi.every((c) => Object.hasOwn(d, c))
}
const SURROGATO_ISOLATO = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/
const codePoint = (v) => [...v].length
const byteUtf8 = (v) => new TextEncoder().encode(v).length
/** String.trim() di Java: rimuove ai bordi i caratteri fino a U+0020 (spazi e controlli), non altri spazi Unicode. */
export const trimJava = (v) => v.replace(/^[\u0000- ]+|[\u0000- ]+$/g, '')
/** Dimensione del corpo JSON che verrebbe inviato. */
export const byteCorpo = (json) => byteUtf8(JSON.stringify(json))

// ---------------------------------------------------------------- prezzo (decimale esatto)

/**
 * Prezzo inserito in italiano: cifre, con al più un separatore decimale, virgola oppure punto, e
 * al più due decimali. Nessun separatore delle migliaia, segno o esponente: «1.234,50» è rifiutato
 * (ambiguo), «80,555» è rifiutato (non rappresentabile, mai arrotondato). Il valore è gestito in
 * centesimi interi; il numero JSON inviato è la conversione diretta della stringa decimale
 * normalizzata (es. «80.5»), senza calcoli in virgola mobile.
 * @returns {{ centesimi: number, numero: number, testo: string } | { errore: string }}
 */
export function parsePrezzo(input) {
  const s = (input ?? '').trim()
  if (s === '') return { errore: 'Indica il prezzo.' }
  const m = /^(\d+)(?:[.,](\d+))?$/.exec(s)
  if (!m) return { errore: 'Usa solo cifre e, per i decimali, una virgola o un punto (senza separatore delle migliaia).' }
  const [, interi, decimali = ''] = m
  if (decimali.length > 2) return { errore: 'Il prezzo ammette al massimo due decimali: non viene arrotondato.' }
  const intero = interi.replace(/^0+(?=\d)/, '')
  if (intero.length > 8) return { errore: 'Il prezzo massimo è 99.999.999,99 €.' }
  const dec = decimali.padEnd(2, '0')
  const centesimi = Number(intero) * 100 + Number(dec)
  const testo = `${intero}.${dec}`
  return { centesimi, numero: Number(testo), testo }
}

/** Prezzo di una risposta (numero JSON) in centesimi, senza arrotondamenti: null se non conforme. */
export function centesimiDa(numero) {
  if (typeof numero !== 'number' || !Number.isFinite(numero) || numero < 0) return null
  const s = String(numero)
  const m = /^(\d+)(?:\.(\d{1,2}))?$/.exec(s)
  if (!m || m[1].length > 8) return null
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'))
}
/** Centesimi in testo italiano per i campi del modulo (es. 8050 → «80,50»). */
export const prezzoTesto = (c) => `${Math.trunc(c / 100)},${String(c % 100).padStart(2, '0')}`

// ---------------------------------------------------------------- proiezioni

export function parseStanza(d) {
  if (!isObject(d) || !stesseChiavi(d, ['idStanza', 'nome', 'descrizione']) || !isId(d.idStanza) || !isText(d.nome)
      || !(d.descrizione === null || isText(d.descrizione))) {
    throw new ProtocolError('Stanza non valida')
  }
  return Object.freeze({ idStanza: d.idStanza, nome: d.nome, descrizione: d.descrizione })
}

export function parsePrestazioneAdmin(d) {
  if (!isObject(d) || !stesseChiavi(d, ['idPrestazione', 'nome', 'descrizione', 'durata', 'prezzo']) || !isId(d.idPrestazione)
      || !isText(d.nome) || !(d.descrizione === null || isText(d.descrizione)) || !Number.isSafeInteger(d.durata) || d.durata < 1 || d.durata > 60) {
    throw new ProtocolError('Prestazione non valida')
  }
  const centesimi = centesimiDa(d.prezzo)
  if (centesimi === null) throw new ProtocolError('Prezzo non valido')
  return Object.freeze({ idPrestazione: d.idPrestazione, nome: d.nome, descrizione: d.descrizione, durata: d.durata, prezzo: d.prezzo, centesimi })
}

function elenco(result, parse, chiave) {
  if (result?.status !== 200 || !result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  const voci = result.data.map(parse)
  if (voci.some((v, i) => i > 0 && chiave(v) <= chiave(voci[i - 1]))) throw new ProtocolError('Ordine non valido')
  return Object.freeze(voci)
}
/** GET /api/stanze: elenco completo per ID crescente (nessun filtro né lettura singola). */
export async function fetchStanzeAdmin(requestFn) {
  return elenco(await requestFn({ path: '/api/stanze' }), parseStanza, (s) => s.idStanza)
}
/** GET /api/prestazioni: catalogo completo per ID crescente. */
export async function fetchPrestazioniAdmin(requestFn) {
  return elenco(await requestFn({ path: '/api/prestazioni' }), parsePrestazioneAdmin, (p) => p.idPrestazione)
}

// ---------------------------------------------------------------- validazioni

/** Descrizione dal modulo: campo vuoto = null (nessuna descrizione); altrimenti il testo com'è, mai trimmato. */
const descrizioneDa = (v) => (v === '' ? null : v)

function nomeValido(v, massimo, errori) {
  if (SURROGATO_ISOLATO.test(v ?? '')) { errori.nome = 'Il nome contiene caratteri non validi.'; return null }
  const n = trimJava(v ?? '')
  if (n === '' || codePoint(n) > massimo) { errori.nome = `Indica il nome (da 1 a ${massimo} caratteri).`; return null }
  return n
}

/** Stanza: { dati: { nome, descrizione }, errori }. Il nome inviato è quello già normalizzato. */
export function validaStanza(f) {
  const errori = {}
  const nome = nomeValido(f.nome, 100, errori)
  const descrizione = descrizioneDa(f.descrizione ?? '')
  if (descrizione !== null && SURROGATO_ISOLATO.test(descrizione)) errori.descrizione = 'La descrizione contiene caratteri non validi.'
  else if (descrizione !== null && codePoint(descrizione) > 255) errori.descrizione = 'La descrizione ammette al massimo 255 caratteri.'
  const dati = { nome, descrizione }
  if (!Object.keys(errori).length && byteCorpo(dati) > MAX_CORPO) errori.descrizione = 'Dati troppo lunghi per il server.'
  return { errori, dati: Object.keys(errori).length ? null : Object.freeze(dati) }
}

/** Prestazione: { dati: { nome, descrizione, durata, prezzo, centesimi }, errori }. */
export function validaPrestazione(f) {
  const errori = {}
  const nome = nomeValido(f.nome, 150, errori)
  const descrizione = descrizioneDa(f.descrizione ?? '')
  if (descrizione !== null && SURROGATO_ISOLATO.test(descrizione)) errori.descrizione = 'La descrizione contiene caratteri non validi.'
  else if (descrizione !== null && byteUtf8(descrizione) > 65535) errori.descrizione = 'La descrizione è troppo lunga.'
  const d = (f.durata ?? '').trim()
  const durata = /^\d{1,2}$/.test(d) ? Number(d) : null
  if (durata === null || durata < 1 || durata > 60) errori.durata = 'Indica una durata intera da 1 a 60 minuti.'
  const p = parsePrezzo(f.prezzo)
  if (p.errore) errori.prezzo = p.errore
  if (Object.keys(errori).length) return { errori, dati: null }
  const dati = { nome, descrizione, durata, prezzo: p.numero, centesimi: p.centesimi }
  if (byteCorpo(corpoPrestazione(dati)) > MAX_CORPO) {
    errori.descrizione = `Dati troppo lunghi: il server accetta richieste fino a ${MAX_CORPO} byte. Accorcia la descrizione.`
    return { errori, dati: null }
  }
  return { errori, dati: Object.freeze(dati) }
}

// ---------------------------------------------------------------- scritture

const corpoStanza = (d) => ({ nome: d.nome, descrizione: d.descrizione })
const corpoPrestazione = (d) => ({ nome: d.nome, descrizione: d.descrizione, durata: d.durata, prezzo: d.prezzo })

/** POST /api/stanze oppure PUT /api/stanze/{id}: corpo completo (nome e descrizione, anche null). */
export function scriviStanza(requestFn, idStanza, dati) {
  if (idStanza !== null && !isId(idStanza)) throw new TypeError('Stanza non valida.')
  return requestFn(idStanza === null
    ? { method: 'POST', path: '/api/stanze', json: corpoStanza(dati) }
    : { method: 'PUT', path: `/api/stanze/${idStanza}`, json: corpoStanza(dati) })
}
/** POST /api/prestazioni oppure PUT /api/prestazioni/{id}: corpo completo dei quattro campi. */
export function scriviPrestazione(requestFn, idPrestazione, dati) {
  if (idPrestazione !== null && !isId(idPrestazione)) throw new TypeError('Prestazione non valida.')
  return requestFn(idPrestazione === null
    ? { method: 'POST', path: '/api/prestazioni', json: corpoPrestazione(dati) }
    : { method: 'PUT', path: `/api/prestazioni/${idPrestazione}`, json: corpoPrestazione(dati) })
}

/** 201/200 con la stanza normalizzata coerente con i dati inviati (e con l'ID, per la PUT). */
export function parseEsitoStanza(result, idStanza, dati) {
  if (result?.status !== (idStanza === null ? 201 : 200) || !result.hasBody) throw new ProtocolError('Esito non conforme')
  const s = parseStanza(result.data)
  if ((idStanza !== null && s.idStanza !== idStanza) || s.nome !== dati.nome || s.descrizione !== dati.descrizione) throw new ProtocolError('Esito non conforme')
  return s
}
/** 201/200 con la prestazione normalizzata; prezzo confrontato in centesimi. */
export function parseEsitoPrestazione(result, idPrestazione, dati) {
  if (result?.status !== (idPrestazione === null ? 201 : 200) || !result.hasBody) throw new ProtocolError('Esito non conforme')
  const p = parsePrestazioneAdmin(result.data)
  if ((idPrestazione !== null && p.idPrestazione !== idPrestazione) || p.nome !== dati.nome || p.descrizione !== dati.descrizione
      || p.durata !== dati.durata || p.centesimi !== dati.centesimi) {
    throw new ProtocolError('Esito non conforme')
  }
  return p
}
