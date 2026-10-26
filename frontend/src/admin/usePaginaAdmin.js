import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { HttpError, NetworkError, ProtocolError } from '../api/http.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'
import { useAdmin } from './store.js'

/**
 * Meccanica comune delle pagine amministrative:
 * - contesto locale nuovo a ogni cambio di epoca o di risorsa; dopo ogni await messaggi, focus e
 *   dati si applicano solo nel contesto d'origine e se la vista è ancora montata;
 * - attesa Retry-After dello store: sospende le scritture, non chiarisce esiti;
 * - una sola conferma aperta, chiusa appena i dati su cui si basa non sono più idonei; annullando,
 *   il focus torna al controllo d'origine.
 * @param {{ azzera: () => void, ricarica: () => void, titoloRiserva: () => HTMLElement|null }} hooks
 */
export function usePaginaAdmin({ azzera, ricarica, titoloRiserva }) {
  const store = useAdmin()
  let contesto = {}
  const avviso = ref(null)
  const avvisoBox = ref(null)
  const titoloConferma = ref(null)
  const impostaTitolo = (el) => { titoloConferma.value = el }
  /** Conferma aperta: { tipo, fase: 'conferma'|'invio', idonea: () => boolean, ... }. */
  const azione = ref(null)
  let origine = null

  function nuovoContesto() {
    contesto = {}
    azione.value = null
    origine = null
    avviso.value = null
    azzera()
  }
  const { session, idUtente: idAdmin, smontato } = useLetturaProfilo('admin', { azzera: nuovoContesto, ricarica })
  const contestoCorrente = () => contesto
  const corrente = (ctx) => ctx === contesto && !smontato()

  async function mostra(ctx, tipo, testo) {
    if (!corrente(ctx)) return
    avviso.value = { tipo, testo }
    await nextTick()
    if (tipo !== 'successo' && corrente(ctx)) avvisoBox.value?.focus()
  }
  function messaggioLettura(error) {
    if (error instanceof HttpError && error.status === 403) return 'Accesso non autorizzato: il tuo account potrebbe non essere più un amministratore attivo.'
    if (error instanceof HttpError && error.status === 400) return 'Richiesta non accettata dal server.'
    if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
    if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
    return 'Il server ha restituito un errore.'
  }

  // Attesa chiesta dal server: vale per tutte le scritture amministrative.
  const ora = ref(Date.now())
  let timer = null
  const attesa = computed(() => store.attesaResidua(ora.value))
  watch(() => store.state.attesaFino, (fino) => {
    clearInterval(timer)
    timer = null
    ora.value = Date.now()
    if (fino === null) return
    timer = setInterval(() => {
      ora.value = Date.now()
      if (store.attesaResidua(ora.value) === 0) { clearInterval(timer); timer = null }
    }, 1000)
  }, { immediate: true })
  onBeforeUnmount(() => clearInterval(timer))

  const puoScrivere = computed(() => idAdmin() !== null && !session.state.passwordUncertain && !session.state.busy && attesa.value === 0)
  const libero = computed(() => azione.value === null)

  // Conferma su dati non più idonei (rilettura, esito certo altrove, attesa, sessione): si chiude subito.
  watch(() => {
    const a = azione.value
    return !!a && a.fase !== 'invio' && (!puoScrivere.value || !a.idonea())
  }, (chiudere) => {
    if (!chiudere) return
    const dentro = (e) => e === document.body || !e?.isConnected || e.closest?.('[data-conferma]') !== null
    const daSpostare = dentro(document.activeElement)
    azione.value = null
    origine = null
    nextTick(() => { if (!smontato() && daSpostare && dentro(document.activeElement)) titoloRiserva()?.focus() })
  }, { flush: 'sync' })

  async function apri(nuova, chiaveOrigine) {
    if (!libero.value || !puoScrivere.value || !nuova.idonea()) return false
    origine = chiaveOrigine
    avviso.value = null
    azione.value = { ...nuova, fase: 'conferma' }
    await nextTick()
    if (!smontato()) titoloConferma.value?.focus()
    return true
  }
  async function annulla() {
    if (azione.value?.fase === 'invio') return
    azione.value = null
    const o = origine
    origine = null
    await nextTick()
    if (smontato()) return
    const b = o ? document.querySelector(`[data-origine="${o}"]`) : null
    ;(b && !b.disabled ? b : titoloRiserva())?.focus()
  }
  function chiudi() {
    azione.value = null
    origine = null
  }

  return {
    store, session, idAdmin, smontato, contestoCorrente, corrente, nuovoContesto, avviso, avvisoBox, mostra, messaggioLettura,
    attesa, puoScrivere, azione, libero, apri, annulla, chiudi, titoloConferma, impostaTitolo,
  }
}
