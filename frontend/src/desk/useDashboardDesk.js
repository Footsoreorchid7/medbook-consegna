import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { fetchRichieste, fetchSegnalazioni, fetchRimborsiDesk, fetchMedici } from '../api/desk.js'
import { configurazionePresente } from '../api/disponibilitaMedico.js'
import { deskStore, listaDisponibilita } from './store.js'
import { leggiDisponibilitaMedici } from './lettureDisponibilita.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

export const CARD_DESK = Object.freeze([
  { id: 'richieste', titolo: 'Richieste da gestire', to: '/desk/richieste', tono: 'ambra', descrizione: 'Prenotazioni in attesa di conferma o rifiuto.', copertura: 'Tutte le date · stato Richiesta' },
  { id: 'segnalazioni', titolo: 'Indisponibilità da risolvere', to: '/desk/segnalazioni', tono: 'rosso', descrizione: 'Segnalazioni di indisponibilità pendenti.', copertura: 'Tutte le date · segnalazioni pendenti' },
  { id: 'rimborsi', titolo: 'Rimborsi da decidere', to: '/desk/rimborsi', destinazione: { path: '/desk/rimborsi', query: { stato: 'RICHIESTO' } }, tono: 'blu', descrizione: 'Richieste di rimborso in attesa di decisione.', copertura: 'Tutte le date · stato Richiesto' },
  { id: 'disponibilita', titolo: 'Disponibilità da configurare', to: '/desk/disponibilita', tono: 'viola', descrizione: 'Slot senza prestazione né stanza assegnate.', copertura: 'Tutti i medici · tutte le date, anche passate' },
])

/** Conteggi soltanto informativi: non adottano elenchi nello store e non riconciliano scritture. */
export function useDashboardDesk() {
  const session = useSession()
  const store = deskStore(session)
  const letture = reactive(Object.fromEntries(CARD_DESK.map(c => [c.id, { stato: 'iniziale', valore: null, aggiornato: null, riscontri: [] }])))
  const inCorso = ref(false)
  const ultimoControllo = ref(null)
  const autorizzato = () => session.state.status === STATO.AUTENTICATO && session.capabilities.value.desk
  let montato = false, timer = null, attiva = null, daRiavviare = false
  const visibile = () => document.visibilityState !== 'hidden'
  const valido = r => montato && attiva === r && !r.annullata && autorizzato() && visibile()
    && session.state.epoch === r.epoca && session.state.user?.idUtente === r.owner
  const riscontriValidi = r => r.every(x => store.valida(x.lista, x.ticket))
  const risultati = computed(() => Object.fromEntries(CARD_DESK.map(c => {
    const s = letture[c.id]
    return [c.id, { ...s, disponibile: s.stato === 'pronto' && riscontriValidi(s.riscontri) }]
  })))
  function fermaTimer() { if (timer !== null) clearTimeout(timer); timer = null }
  function annulla() {
    fermaTimer()
    if (attiva) { attiva.annullata = true; attiva.controller.abort() }
  }
  function programma() {
    fermaTimer()
    if (montato && autorizzato() && visibile()) timer = setTimeout(aggiorna, 60_000)
  }
  async function contaLista(r, lista, leggi) {
    const ticket = store.inizioLettura()
    const voci = await leggi(r.request)
    return { valore: voci.length, riscontri: [{ lista, ticket }] }
  }
  async function contaDisponibilita(r) {
    // Nessun filtro ATTIVO: il conteggio non deve omettere medici o date storiche.
    const medici = await fetchMedici(r.request)
    const ticket = store.inizioLettura()
    const gruppi = await leggiDisponibilitaMedici(r.request, medici, () => valido(r))
    return { valore: gruppi.reduce((n, g) => n + g.voci.filter(s => !configurazionePresente(s)).length, 0),
      riscontri: gruppi.map(g => ({ lista: listaDisponibilita(g.medico.idUtente), ticket })) }
  }

  async function aggiorna() {
    if (!montato || !autorizzato() || !visibile()) return
    if (attiva) { if (attiva.annullata) daRiavviare = true; return }
    fermaTimer()
    const r = { epoca: session.state.epoch, owner: session.state.user.idUtente, controller: new AbortController(), annullata: false }
    r.request = op => session.protectedRequest({ ...op, signal: r.controller.signal })
    attiva = r
    inCorso.value = true
    for (const s of Object.values(letture)) if (s.stato !== 'pronto') s.stato = 'caricamento'
    const esiti = await Promise.allSettled([
      contaLista(r, 'richieste', fetchRichieste),
      contaLista(r, 'segnalazioni', fetchSegnalazioni),
      contaLista(r, 'rimborsi', fetchRimborsiDesk),
      contaDisponibilita(r),
    ])
    if (valido(r)) {
      const ora = Date.now()
      ultimoControllo.value = ora
      esiti.forEach((x, i) => {
        const s = letture[CARD_DESK[i].id]
        if (x.status === 'fulfilled' && riscontriValidi(x.value.riscontri)) {
          Object.assign(s, x.value, { stato: 'pronto', aggiornato: ora })
        } else Object.assign(s, { stato: x.status === 'fulfilled' ? 'superata' : 'errore', valore: null, riscontri: [] })
      })
    }
    // Un ciclo annullato resta occupato finché le sue richieste si sono effettivamente concluse.
    if (attiva === r) {
      attiva = null
      inCorso.value = false
      if (daRiavviare) { daRiavviare = false; queueMicrotask(aggiorna) }
      else programma()
    }
  }
  function visibilita() {
    if (!visibile()) annulla()
    else aggiorna()
  }
  watch(() => [session.state.epoch, session.state.user?.idUtente, session.state.status, session.capabilities.value.desk], () => {
    annulla()
    ultimoControllo.value = null
    for (const s of Object.values(letture)) Object.assign(s, { stato: 'iniziale', valore: null, aggiornato: null, riscontri: [] })
    if (montato) queueMicrotask(aggiorna)
  }, { flush: 'sync' })
  onMounted(() => { montato = true; document.addEventListener('visibilitychange', visibilita); aggiorna() })
  onBeforeUnmount(() => { montato = false; annulla(); document.removeEventListener('visibilitychange', visibilita) })
  return { risultati, inCorso, ultimoControllo, aggiorna }
}
