import { effectScope, readonly, shallowReactive, watch } from 'vue'
import { parseIdRisorsa } from '../api/prenotazioni.js'
import { STATO } from '../session/session.js'
import { isLocalDate } from '../utils/datetime.js'

export const dataRicercaValida = (d) => isLocalDate(d) && d >= '1000-01-01' && d <= '9999-12-30'
export const idParametro = (raw) => parseIdRisorsa(raw)
const stores = new WeakMap()

/** Solo memoria della SPA: nessun dato clinico, prezzo, nome, cookie o credenziale. */
export function sceltaStore(session) {
  if (stores.has(session)) return stores.get(session)
  const state = shallowReactive({ scelta: null, ultimaCreata: null })
  let owner = null
  const cancella = () => { state.scelta = null }
  const scope = effectScope(true)
  scope.run(() => watch(() => [session.state.status, session.state.user?.idUtente], () => {
    const id = session.state.user?.idUtente ?? null
    if (owner !== null && (id !== owner || session.state.status !== STATO.AUTENTICATO)) {
      cancella(); state.ultimaCreata = null; owner = null
    }
    if (session.state.status === STATO.AUTENTICATO && id !== null) {
      if (!session.capabilities.value.paziente) { cancella(); state.ultimaCreata = null; owner = null }
      else owner = id
    } else if ([STATO.DISCONNESSIONE, STATO.LOGOUT_NON_CONFERMATO].includes(session.state.status)) {
      cancella(); state.ultimaCreata = null
    }
  }, { flush: 'sync', immediate: true }))
  function scegli({ idDisponibilita, prestazioneId = null, data = null }) {
    if (!idParametro(String(idDisponibilita)) || (prestazioneId !== null && !idParametro(String(prestazioneId)))
        || (data !== null && !dataRicercaValida(data))) return false
    if (session.state.status === STATO.AUTENTICATO && !session.capabilities.value.paziente) return false
    // Anche con login incerto si conserva soltanto una scelta pubblica: l'accesso
    // passa dal recupero e non autorizza alcuna scrittura.
    if (![STATO.ANONIMO, STATO.AUTENTICATO].includes(session.state.status)) return false
    state.scelta = Object.freeze({ idDisponibilita, prestazioneId, data })
    return true
  }
  function creata(scelta, id, epoca, paziente) {
    if (session.state.epoch !== epoca || session.state.status !== STATO.AUTENTICATO
        || session.state.user?.idUtente !== paziente || !session.capabilities.value.paziente) return
    state.ultimaCreata = id
    if (state.scelta === scelta) cancella()
  }
  const store = { state: readonly(state), scegli, cancella, creata }
  stores.set(session, store)
  return store
}
