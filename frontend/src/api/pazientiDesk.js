import { ProtocolError } from './http.js'
import { isId } from './utenti.js'
const object = d => d && typeof d === 'object' && !Array.isArray(d)
export function errorePasswordPaziente(p) {
  if (typeof p !== 'string' || p.length < 8 || p.length > 1024) return 'Usa da 8 a 1024 caratteri.'
  if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(p)) return 'Caratteri non validi.'
  return null
}
export async function fetchPazienti(request, filtri = {}, dopoId = null) {
  const q = new URLSearchParams()
  for (const k of ['idPaziente', 'nome', 'cognome', 'email']) if (filtri[k]) q.set(k, filtri[k])
  if (dopoId !== null) { if (!isId(dopoId)) throw new TypeError(); q.set('dopoId', dopoId) }
  const r = await request({ path: '/api/pazienti' + (q.size ? '?' + q : '') })
  const d = r.data
  if (r.status !== 200 || !r.hasBody || !object(d) || !Array.isArray(d.risultati) || d.risultati.length > 20
      || !(d.prossimoDopoId === null || isId(d.prossimoDopoId))) throw new ProtocolError()
  let last = dopoId ?? 0
  const risultati = d.risultati.map(p => {
    if (!object(p) || !isId(p.idPaziente) || p.idPaziente <= last || !['nome','cognome','email'].every(k => typeof p[k] === 'string' && p[k].trim())
        || typeof p.medico !== 'boolean' || typeof p.gestionale !== 'boolean' || !['ATTIVO','DISABILITATO'].includes(p.statoAccesso)) throw new ProtocolError()
    last = p.idPaziente
    return { idPaziente: p.idPaziente, nome: p.nome, cognome: p.cognome, email: p.email, medico: p.medico, gestionale: p.gestionale, statoAccesso: p.statoAccesso }
  })
  if (d.prossimoDopoId !== null && (!risultati.length || d.prossimoDopoId !== last)) throw new ProtocolError()
  return { risultati, prossimoDopoId: d.prossimoDopoId }
}
export async function putPasswordPaziente(request, id, password) {
  if (!isId(id) || errorePasswordPaziente(password)) throw new TypeError()
  const r = await request({ method: 'PUT', path: `/api/pazienti/${id}/password`, json: { nuovaPassword: password } })
  const d = r.data
  if (r.status !== 200 || !r.hasBody || !object(d) || Object.keys(d).length !== 3 || d.idPaziente !== id
      || typeof d.message !== 'string' || !d.message.trim() || d.sessioniInvalidate !== true) throw new ProtocolError()
  return d
}

/** Solo identificativi e flag nella risposta: nessun dato paziente aggiuntivo nel dettaglio medico. */
export async function postCollegamentoPaziente(request, id, dati) {
  if (!isId(id)) throw new TypeError()
  const json = { codiceFiscale: dati.codiceFiscale, dataNascita: dati.dataNascita }
  if (typeof dati.telefono === 'string' && dati.telefono !== '') json.telefono = dati.telefono
  const r = await request({ method: 'POST', path: `/api/pazienti/${id}/collegamento`, json })
  const d = r.data
  if (r.status !== 201 || !r.hasBody || !object(d) || Object.keys(d).length !== 3 || d.idPaziente !== id
      || d.paziente !== true || typeof d.message !== 'string' || !d.message.trim()) throw new ProtocolError()
  return { idPaziente: id, paziente: true, message: d.message }
}
