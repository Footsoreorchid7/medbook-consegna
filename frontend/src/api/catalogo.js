import { isLocalDate, parseLocalDateTime } from '../utils/datetime.js'
import { ProtocolError, request } from './http.js'

const isId = (v) => Number.isSafeInteger(v) && v > 0
const isText = (v) => typeof v === 'string'
const isAmount = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0

function list(result, parse) {
  if (!result.hasBody || !Array.isArray(result.data)) throw new ProtocolError('Elenco atteso')
  return result.data.map(parse)
}

/** GET /api/prestazioni (pubblico): idPrestazione, nome, descrizione facoltativa, durata in minuti, prezzo. */
export async function fetchPrestazioni(options = {}) {
  return list(await request({ path: '/api/prestazioni', ...options }), (p) => {
    if (!p || !isId(p.idPrestazione) || !isText(p.nome) || !(p.descrizione == null || isText(p.descrizione))
        || !Number.isSafeInteger(p.durata) || p.durata <= 0 || !isAmount(p.prezzo)) {
      throw new ProtocolError('Prestazione non valida')
    }
    return Object.freeze({
      idPrestazione: p.idPrestazione, nome: p.nome, descrizione: p.descrizione ?? null, durata: p.durata, prezzo: p.prezzo,
    })
  })
}

/**
 * Query di GET /api/disponibilita/prenotabili con i soli filtri valorizzati e ammessi
 * (prestazioneId, data). Nessun parametro vuoto: il backend lo rifiuterebbe.
 */
export function prenotabiliPath({ prestazioneId = null, data = null } = {}) {
  const query = new URLSearchParams()
  if (prestazioneId !== null && prestazioneId !== undefined && prestazioneId !== '') {
    if (!isId(prestazioneId)) throw new TypeError('Prestazione non valida.')
    query.set('prestazioneId', String(prestazioneId))
  }
  if (data !== null && data !== undefined && data !== '') {
    if (!isLocalDate(data)) throw new TypeError('Data non valida.')
    query.set('data', data)
  }
  const text = query.toString()
  return text ? `/api/disponibilita/prenotabili?${text}` : '/api/disponibilita/prenotabili'
}

/** GET /api/disponibilita/prenotabili (pubblico). Gli orari restano stringhe locali validate. */
export async function fetchDisponibilitaPrenotabili(filters, options = {}) {
  return list(await request({ path: prenotabiliPath(filters), ...options }), (d) => {
    const inizio = parseLocalDateTime(d?.dataOraInizio)
    const fine = parseLocalDateTime(d?.dataOraFine)
    if (!d || !isId(d.idDisponibilita) || !inizio || !fine || !isId(d.idMedico) || !isText(d.nomeMedico)
        || !isText(d.cognomeMedico) || !isId(d.idPrestazione) || !isText(d.nomePrestazione) || !isAmount(d.prezzoPrestazione)) {
      throw new ProtocolError('Disponibilità non valida')
    }
    return Object.freeze({
      idDisponibilita: d.idDisponibilita,
      idPrestazione: d.idPrestazione,
      idMedico: d.idMedico,
      inizio,
      fine,
      nomeMedico: d.nomeMedico,
      cognomeMedico: d.cognomeMedico,
      nomePrestazione: d.nomePrestazione,
      durata: Number.isSafeInteger(d.durataPrestazione) ? d.durataPrestazione : null,
      prezzo: d.prezzoPrestazione,
    })
  })
}
