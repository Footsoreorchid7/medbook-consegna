// Dati sintetici per i test nessuna identità reale.
import { MEDICO } from './b5-fixtures.js'

export { MEDICO, medico } from './b5-fixtures.js'

const ora = (inizio, ore = 1) => {
  const [d, t] = inizio.split('T')
  const [h, m, s = '00'] = t.split(':')
  const fine = new Date(Date.UTC(...d.split('-').map((x, i) => Number(x) - (i === 1 ? 1 : 0)), Number(h) + ore, Number(m), Number(s)))
  const p = (n) => String(n).padStart(2, '0')
  return `${fine.getUTCFullYear()}-${p(fine.getUTCMonth() + 1)}-${p(fine.getUTCDate())}T${p(fine.getUTCHours())}:${p(fine.getUTCMinutes())}:${p(fine.getUTCSeconds())}`
}

/** Voce di DisponibilitaResponse da 60 minuti, per default non configurata. */
export const slot = (id, inizio, extra = {}) => ({
  idDisponibilita: id,
  dataOraInizio: inizio,
  dataOraFine: ora(inizio),
  idMedico: MEDICO,
  idPrestazione: null,
  nomePrestazione: null,
  idStanza: null,
  nomeStanza: null,
  configurata: false,
  ...extra,
})

export const configurato = (id, inizio, extra = {}) => slot(id, inizio, {
  idPrestazione: 2, nomePrestazione: 'Visita sintetica', idStanza: 3, nomeStanza: 'Ambulatorio 1', configurata: true, ...extra,
})

/** Slot consecutivi da `inizio`, con ID a partire da `primo`. */
export const serie = (primo, inizio, n) => Array.from({ length: n }, (_, k) => slot(primo + k, ora(inizio, k)))

export const ELENCO = `GET /api/disponibilita?medicoId=${MEDICO}`
export const CREA = 'POST /api/disponibilita'
export const ORARIO = (id) => `PUT /api/disponibilita/${id}/orario`
export const ELIMINA = (id) => `DELETE /api/disponibilita/${id}?medicoId=${MEDICO}`
export const vuota = () => new Response(null, { status: 204 })
