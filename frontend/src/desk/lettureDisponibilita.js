import { fetchDisponibilita } from '../api/disponibilitaMedico.js'

/** Copertura completa per ciascun medico, tutte le date. Nessun totale parziale. */
export async function leggiDisponibilitaMedici(request, medici, corrente = () => true) {
  const gruppi = new Array(medici.length)
  let prossimo = 0, errore = null
  async function lavoratore() {
    while (!errore && corrente() && prossimo < medici.length) {
      const i = prossimo++
      try { gruppi[i] = { medico: medici[i], voci: await fetchDisponibilita(request, medici[i].idUtente) } }
      catch (e) { errore = e }
    }
  }
  await Promise.all(Array.from({ length: Math.min(3, medici.length) }, lavoratore))
  if (errore) throw errore
  if (!corrente()) throw new Error('Lettura superata')
  return gruppi
}
