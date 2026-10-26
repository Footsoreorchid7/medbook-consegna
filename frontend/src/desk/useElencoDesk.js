import { computed, nextTick, reactive, ref, shallowRef, watch } from 'vue'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'
import { useDesk } from './store.js'

/**
 * Meccanica comune delle pagine desk , con le stesse regole di * - contesto locale nuovo a ogni nuova ricerca o selezione e a ogni cambio di epoca; dopo ogni
 *   await messaggi, focus e reset si applicano solo nel contesto d'origine;
 * - solo una lettura accettata dallo store diventa l'elenco su cui autorizzare le azioni; una
 *   lettura scartata lascia i dati precedenti visibili come non aggiornati, con rilettura manuale;
 * - durante una rilettura le azioni sono sospese e le conferme aperte vengono chiuse;
 * - la lettura mostrata resta idonea solo finché nessun esito certo successivo al suo avvio
 *   tocca lo stesso elenco (store.valida, reattivo): altrimenti azioni sospese e conferme chiuse
 *   subito, dati visibili con avviso e aggiornamento manuale;
 * - annullando una conferma il focus torna al controllo d'origine;
 * - un messaggio o una lettura manuale spostano il focus solo se il controllo usato è scomparso e
 *   il focus è rimasto perso sul documento: uno spostamento volontario non viene sottratto.
 *
 * @param {{ azzera?: () => void, ricarica: () => void }} hooks
 */
export function useElencoDesk({ azzera = () => {}, ricarica }) {
  const store = useDesk()
  const elenco = reactive({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false })
  /** Conferma aperta: { tipo, id, fase: 'conferma'|'invio', ... }. */
  const azione = ref(null)
  const avviso = ref(null)
  const avvisoBox = ref(null)
  const titoloElenco = ref(null)
  const titoloConferma = ref(null)
  /** Lettura applicata all'elenco mostrato: { lista, ticket }. */
  const mostrata = shallowRef(null)
  const impostaTitolo = (el) => { titoloConferma.value = el }
  let contesto = {}
  let origine = null

  function nuovoContesto() {
    contesto = {}
    elenco.voci = []
    elenco.stato = 'caricamento'
    elenco.errore = ''
    elenco.aggiornata = false
    elenco.superata = false
    mostrata.value = null
    azione.value = null
    avviso.value = null
    origine = null
    azzera()
  }

  const { session, idUtente, nuovaLettura, attuale, smontato } = useLetturaProfilo('desk', { azzera: nuovoContesto, ricarica })
  const contestoCorrente = () => contesto
  const corrente = (ctx) => ctx === contesto && !smontato()

  function messaggio(error) {
    if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
    if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
    return 'Il server ha restituito un errore.'
  }

  /** Focus perso: sul documento o su un elemento rimosso dal DOM. */
  const focusPerso = () => {
    const a = document.activeElement
    return !a || a === document.body || !a.isConnected
  }

  /**
   * Messaggio nel contesto d'origine. I successi semplici non ricevono focus; per gli altri esiti, dopo l'aggiornamento
   * del DOM il focus si sposta solo se contesto (epoca compresa) e montaggio sono quelli d'origine ed è
   * perso, cioè il controllo usato è scomparso; altrimenti il messaggio resta annunciato dal suo ruolo.
   */
  async function mostra(ctx, tipo, testo) {
    if (!corrente(ctx)) return
    avviso.value = { tipo, testo }
    await nextTick()
    if (tipo !== 'successo' && corrente(ctx) && focusPerso()) avvisoBox.value?.focus()
  }

  /**
   * Lettura dell'elenco nel contesto corrente. `applica` sostituisce l'applicazione predefinita
   * allo store (per le letture desk copertura e riscontri per prenotazione).
   * `valida` verifica le barriere di tutti gli ambiti rappresentati da una lettura aggregata;
   * senza callback il contratto resta quello di un singolo elenco.
   * @param {{ lista: string, completa: boolean, leggi: () => Promise<Array>, applica?: (voci, ticket) => boolean }} op
   */
  async function leggi({ lista, completa, leggi: carica, applica = (voci, ticket) => store.applica(lista, voci, ticket, completa), valida = ticket => store.valida(lista, ticket) }) {
    if (idUtente() === null) return null
    const ctx = contesto
    const mia = nuovaLettura()
    elenco.aggiornata = false
    elenco.superata = false
    elenco.errore = ''
    if (elenco.stato !== 'pronto') elenco.stato = 'caricamento'
    const ticket = store.inizioLettura()
    try {
      const voci = await carica()
      if (!attuale(mia) || !corrente(ctx)) return null
      if (!applica(voci, ticket)) {
        elenco.superata = true
        if (elenco.stato !== 'pronto') elenco.stato = 'superata'
        return { mia, esito: 'scartata' }
      }
      elenco.voci = voci
      mostrata.value = { lista, ticket, valida }
      elenco.stato = 'pronto'
      elenco.aggiornata = true
      return { mia, esito: 'pronto' }
    } catch (error) {
      if (!attuale(mia) || !corrente(ctx) || error instanceof StaleResponseError) return null
      elenco.errore = messaggio(error)
      elenco.stato = 'errore'
      return { mia, esito: 'errore' }
    }
  }

  /**
   * Lettura manuale («Riprova», «Aggiorna l'elenco»): il recupero del focus è legato
   * alla lettura avviata dal clic. Se il pulsante è scomparso e il focus è perso va al titolo
   * dell'elenco dopo un successo, al nuovo «Riprova» dopo un errore, ad «Aggiorna l'elenco» dopo
   * una lettura scartata. Nessuno spostamento se nel frattempo un'altra lettura l'ha sostituita,
   * se cambiano contesto o epoca, dopo lo smontaggio o se l'utente ha portato il focus altrove;
   * gli stessi controlli sono ripetuti dopo l'aggiornamento del DOM.
   * @param {() => Promise<{mia:number, esito:string}|null>} avvia lettura dell'elenco della pagina
   */
  async function recupera(avvia) {
    const ctx = contesto
    const r = await avvia()
    const valida = () => r && attuale(r.mia) && corrente(ctx)
    if (!valida()) return
    await nextTick()
    if (!valida() || !focusPerso()) return
    const destinazione = r.esito === 'pronto' ? titoloElenco.value
      : document.querySelector(r.esito === 'errore' ? '[data-riprova]' : '[data-aggiorna-elenco]') ?? titoloElenco.value
    destinazione?.focus()
  }

  /** Lettura mostrata ancora valida rispetto alle barriere dello store (reattivo). */
  const valida = computed(() => mostrata.value !== null && mostrata.value.valida(mostrata.value.ticket))
  /** Elenco aggiornato ma superato da un esito certo successivo: dati visibili, azioni sospese. */
  const invalidata = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && !valida.value)
  const puoAgire = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && valida.value && idUtente() !== null
    && !session.state.passwordUncertain && !session.state.busy)
  const inAggiornamento = computed(() => elenco.stato === 'pronto' && !elenco.aggiornata && !elenco.superata)
  const libero = computed(() => azione.value === null)

  watch(puoAgire, (valido) => {
    if (valido || !azione.value || azione.value.fase === 'invio') return
    azione.value = null
    origine = null
    nextTick(() => { if (!smontato() && document.activeElement === document.body) titoloElenco.value?.focus() })
  }, { flush: 'sync' })

  async function apri(nuova, chiaveOrigine) {
    if (!puoAgire.value || !libero.value) return false
    origine = chiaveOrigine
    avviso.value = null
    azione.value = nuova
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
    ;(b && !b.disabled ? b : titoloElenco.value)?.focus()
  }

  /** Chiude la conferma dopo un esito, senza spostare il focus sui successi informativi. */
  function chiudi() {
    azione.value = null
    origine = null
  }

  return {
    store, session, idOperatore: idUtente, smontato, elenco, azione, avviso, avvisoBox, titoloElenco, titoloConferma, impostaTitolo,
    contestoCorrente, corrente, nuovoContesto, mostra, leggi, recupera, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi,
  }
}

/** Messaggi comuni degli esiti delle scritture desk. */
export const ESITI_COMUNI = Object.freeze({
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  occupato: "Un'operazione su questa risorsa è già in corso.",
  bloccata: "L'esito di un'operazione precedente su questa risorsa è ancora da verificare.",
})
