import { reactive } from 'vue'

/**
 * Modulo di modifica di una risorsa con tre dati distinti:
 * - valori correnti del modulo (quello che l'utente sta scrivendo);
 * - base di ogni campo: il valore letto da cui è partita la modifica;
 * - snapshot del tentativo (istantanea), immutabile, presa all'invio.
 * Ogni apertura su una risorsa crea un contesto nuovo: un esito o una rilettura di un contesto
 * precedente (altra risorsa, A → B → A, chiusura) non tocca il modulo corrente.
 * @param {string[]} campi
 * @param {(risorsa) => Record<string,string>} daRisorsa valori del modulo per una risorsa letta
 */
export function useModuloModifica(campi, daRisorsa) {
  const vuoti = () => Object.fromEntries(campi.map((k) => [k, '']))
  const valori = reactive(vuoti())
  const stato = reactive({ id: null, contesto: null, base: null })

  function apri(id, risorsa) {
    const v = daRisorsa(risorsa)
    stato.id = id
    stato.contesto = {}
    stato.base = { ...v }
    Object.assign(valori, v)
  }
  function chiudi() {
    stato.id = null
    stato.contesto = null
    stato.base = null
    Object.assign(valori, vuoti())
  }
  const aperto = (id) => stato.id === id
  /** Campo modificato rispetto al valore letto da cui è partita la modifica. */
  const modificato = (k) => stato.base !== null && valori[k] !== stato.base[k]

  /** Rilettura: aggiorna i campi non modificati, conserva gli altri con la loro base. */
  function precompila(risorsa) {
    if (stato.base === null || !risorsa) return
    const m = daRisorsa(risorsa)
    for (const k of campi) {
      if (valori[k] !== stato.base[k] && valori[k] !== m[k]) continue
      valori[k] = m[k]
      stato.base[k] = m[k]
    }
  }
  /**
   * Campi modificati il cui valore sul server è cambiato dopo l'inizio della modifica: errore per
   * campo e base riallineata, così una nuova «Prosegui» conferma esplicitamente.
   */
  function conflitti(risorsa, testo) {
    const errori = {}
    if (stato.base === null || !risorsa) return errori
    const m = daRisorsa(risorsa)
    for (const k of campi) {
      if (!modificato(k) || stato.base[k] === m[k]) continue
      errori[k] = testo(m[k])
      stato.base[k] = m[k]
    }
    return errori
  }
  const istantanea = () => Object.freeze({ contesto: stato.contesto, valori: Object.freeze({ ...valori }) })
  /** Dopo un salvataggio riuscito nello stesso contesto: base = valori salvati; aggiorna solo i campi ancora uguali a quelli inviati. */
  function riallinea(snap, salvato) {
    if (snap.contesto === null || snap.contesto !== stato.contesto) return false
    const m = daRisorsa(salvato)
    for (const k of campi) {
      stato.base[k] = m[k]
      if (valori[k] === snap.valori[k]) valori[k] = m[k]
    }
    return true
  }
  const contesto = () => stato.contesto

  return { valori, stato, apri, chiudi, aperto, modificato, precompila, conflitti, istantanea, riallinea, contesto }
}
