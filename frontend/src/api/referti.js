import { parseLocalDateTime } from '../utils/datetime.js'
import { HttpError, ProtocolError } from './http.js'

/**
 * Referti del paziente proprietario (UC-09): metadati e documento PDF. Il caricamento
 * appartiene al medico e non è esposto qui.
 */

const MAX_INT = 2147483647
const isId = (v) => Number.isSafeInteger(v) && v > 0 && v <= MAX_INT
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

function idValido(id) {
  if (!isId(id)) throw new TypeError('Prenotazione non valida.')
  return id
}

/**
 * GET /api/prenotazioni/{id}/referto. Un 404 significa referto non disponibile (assenza o
 * risorsa non propria, indistinguibili per scelta del backend) e restituisce null; ogni altro
 * errore viene propagato come errore tecnico.
 */
export async function fetchReferto(requestFn, idPrenotazione) {
  const id = idValido(idPrenotazione)
  let result
  try {
    result = await requestFn({ path: `/api/prenotazioni/${id}/referto` })
  } catch (error) {
    if (error instanceof HttpError && error.status === 404) return null
    throw error
  }
  const d = result?.data
  const caricato = parseLocalDateTime(d?.caricatoIl)
  if (result?.status !== 200 || !result.hasBody || !isObject(d) || !isId(d.idReferto) || d.idPrenotazione !== id
      || typeof d.nomeFile !== 'string' || !caricato) {
    throw new ProtocolError('Referto non valido')
  }
  return Object.freeze({ idReferto: d.idReferto, idPrenotazione: id, nomeFile: d.nomeFile, caricato })
}

/** Firma iniziale di un PDF ("%PDF-"). */
async function iniziaComePdf(blob) {
  const testa = blob.slice(0, 5)
  const buffer = typeof testa.arrayBuffer === 'function'
    ? await testa.arrayBuffer()
    : await new Response(testa).arrayBuffer()
  return new TextDecoder('latin1').decode(buffer) === '%PDF-'
}

/**
 * GET /api/prenotazioni/{id}/referto/documento con il client autenticato. Il wrapper analizza
 * gli errori HTTP prima di leggere il corpo e verifica Content-Type `application/pdf` e corpo
 * non vuoto; qui si controllano anche status 200, Content-Length (se presente) e firma PDF.
 */
export async function scaricaDocumentoReferto(requestFn, idPrenotazione) {
  const id = idValido(idPrenotazione)
  const result = await requestFn({
    path: `/api/prenotazioni/${id}/referto/documento`, responseType: 'blob', expectedType: 'application/pdf',
  })
  const blob = result?.data
  // Verifica strutturale: il Blob di fetch può provenire da un realm diverso da quello globale.
  const isBlob = blob !== null && typeof blob === 'object' && Number.isSafeInteger(blob.size) && typeof blob.slice === 'function'
  if (result?.status !== 200 || !result.hasBody || !isBlob || blob.size === 0) throw new ProtocolError('Documento non valido')
  const lunghezza = result.headers?.get?.('Content-Length')
  if (lunghezza != null && (!/^\d+$/.test(lunghezza) || Number(lunghezza) !== blob.size)) throw new ProtocolError('Documento incompleto')
  if (!(await iniziaComePdf(blob))) throw new ProtocolError('Documento non PDF')
  return blob
}

const RISERVATI = /[\u0000-\u001f\u007f-\u009f<>:"/\\|?*\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]/g

/**
 * Nome file sicuro per il download: niente percorsi, caratteri riservati o di controllo,
 * niente nomi nascosti o solo punti, lunghezza limitata, estensione .pdf. Se il nome
 * dichiarato non è utilizzabile si usa `referto-<id>.pdf`.
 */
export function nomeFileSicuro(nomeFile, idPrenotazione) {
  const ripiego = `referto-${idValido(idPrenotazione)}.pdf`
  if (typeof nomeFile !== 'string') return ripiego
  const ultimo = nomeFile.split(/[\\/]/).pop()
  let nome = ultimo.normalize('NFC').replace(RISERVATI, '_').replace(/\s+/g, ' ').trim()
  if (nome.toLowerCase().endsWith('.pdf')) nome = nome.slice(0, -4)
  nome = nome.replace(/^[.\s]+|[.\s]+$/g, '')
  nome = [...nome].slice(0, 100).join('')
  if (nome === '' || /^_+$/.test(nome)) return ripiego
  return `${nome}.pdf`
}

/**
 * Avvia il download del blob con un object URL temporaneo e lo revoca dopo l'avvio, con un
 * ritardo che lascia al browser il tempo di iniziare a leggerlo. Il timer non dipende dalla
 * vista: l'URL viene comunque rilasciato. Nessuna persistenza nel browser.
 * @returns {{ revoca: () => void }} revoca anticipata (idempotente)
 */
export function avviaDownload(blob, nome, { documento = globalThis.document, ritardoMs = 10000 } = {}) {
  const url = URL.createObjectURL(blob)
  let revocato = false
  const revoca = () => {
    if (revocato) return
    revocato = true
    clearTimeout(timer)
    URL.revokeObjectURL(url)
  }
  const timer = setTimeout(revoca, ritardoMs)
  try {
    const link = documento.createElement('a')
    link.href = url
    link.download = nome
    link.rel = 'noopener'
    link.hidden = true
    documento.body.appendChild(link)
    try {
      link.click()
    } finally {
      link.remove()
    }
  } catch (error) {
    revoca()
    throw error
  }
  return { revoca }
}
