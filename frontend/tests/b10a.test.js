import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import PagamentiSezione from '../src/components/PagamentiSezione.vue'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { adminStore } from '../src/admin/store.js'
import { parseStanza } from '../src/api/catalogoAdmin.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { confermata, pagamento, rimborso } from './b4-fixtures.js'
import { CREA_STANZA, STANZA, STANZE, admin, stanza } from './b9c-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Focus dopo «Aggiorna l'elenco»/«Riprova» (rimborsi, storico pagamenti) e testi neutri.
// Dati sintetici.
const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const RIMB = 'GET /api/rimborsi/paziente'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = (id = 12) => `GET /api/prenotazioni/${id}/pagamenti`
const r12 = () => rimborso(31, { idPrenotazione: 12, idPagamento: 5 })

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.querySelectorAll('[data-sentinella]').forEach((e) => e.remove())
})

async function mountApp(path, user = utente(), prima = () => {}) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  await prima(session)
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router }
}
const bottone = (radice, testo) => wrapper.get(radice).findAll('button').find((b) => b.text() === testo)
/** Clic con il focus sul pulsante, come da tastiera o mouse. */
async function premi(b) {
  b.element.focus()
  expect(document.activeElement).toBe(b.element)
  await b.trigger('click')
}
function sentinella() {
  const s = document.createElement('button')
  s.dataset.sentinella = ''
  document.body.append(s)
  return s
}
const ruota = async (session) => {
  net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
  expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
}

// ---------------------------------------------------------------- rimborsi

describe('«I miei rimborsi»: focus dopo l\'aggiornamento manuale', () => {
  /** Avviso «superata» con «Aggiorna l'elenco», come nella verifica di una richiesta incerta. */
  async function conAggiornaElenco() {
    const ctx = await mountApp('/paziente/rimborsi', utente(), async (session) => {
      net.on('POST /api/prenotazioni/12/rimborso', 'rete')
      await prenotazioniStore(session).richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Visita non erogata')
      net.on(RIMB, jsonResponse(200, []))
    })
    const store = prenotazioniStore(ctx.session)
    const a = net.trattieni(RIMB)
    await wrapper.findAll('[data-rimborso-incerto] button').find((b) => b.text() === "Verifica nell'elenco").trigger('click')
    store.registraRimborsi([], store.inizioLettura())
    a.resolve(jsonResponse(200, [r12()]))
    await flushPromises()
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
    return ctx
  }
  const titolo = () => wrapper.get('#titolo-rimborsi').element

  it('«Aggiorna l\'elenco» riuscito: il pulsante scompare e il focus va al titolo dell\'elenco', async () => {
    await conAggiornaElenco()
    const h = net.trattieni(RIMB)
    await premi(bottone('[data-aggiorna-elenco]', "Aggiorna l'elenco"))
    h.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(false)
    expect(document.activeElement).toBe(titolo())
  })

  it('«Aggiorna l\'elenco» con errore: il pulsante resta e conserva il focus; «Riprova» è disponibile', async () => {
    await conAggiornaElenco()
    net.on(RIMB, 'rete')
    const b = bottone('[data-aggiorna-elenco]', "Aggiorna l'elenco")
    await premi(b)
    await flushPromises()
    expect(document.activeElement).toBe(b.element)
    expect(bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova')).toBeDefined()
  })

  it('«Riprova» dopo un errore: nuovo errore → focus sul nuovo «Riprova»; poi successo → titolo', async () => {
    net.on(RIMB, 'rete')
    await mountApp('/paziente/rimborsi')
    const h = net.trattieni(RIMB)
    await premi(bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova'))
    expect(document.activeElement).toBe(document.body)
    h.resolve(new Response('x', { status: 500 }))
    await flushPromises()
    const nuovo = bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova')
    expect(document.activeElement).toBe(nuovo.element)
    net.on(RIMB, jsonResponse(200, [r12()]))
    await premi(nuovo)
    await flushPromises()
    expect(document.activeElement).toBe(titolo())
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(1)
  })

  it('focus portato altrove dall\'utente durante la lettura: non viene sottratto', async () => {
    net.on(RIMB, 'rete')
    await mountApp('/paziente/rimborsi')
    const h = net.trattieni(RIMB)
    await premi(bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova'))
    const s = sentinella()
    s.focus()
    h.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(s)
  })

  it('lettura superata da una più recente: decide solo l\'ultima', async () => {
    await conAggiornaElenco()
    const vecchia = net.trattieni(RIMB)
    await premi(bottone('[data-aggiorna-elenco]', "Aggiorna l'elenco"))
    const nuova = net.trattieni(RIMB)
    await premi(bottone('[data-aggiorna-elenco]', "Aggiorna l'elenco"))
    vecchia.resolve(jsonResponse(200, []))
    await flushPromises()
    // La lettura vecchia è scartata: la nota resta e il focus resta sul pulsante.
    expect(document.activeElement).toBe(bottone('[data-aggiorna-elenco]', "Aggiorna l'elenco").element)
    nuova.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(titolo())
  })

  it('cambio di epoca durante la lettura: nessun focus tardivo, nemmeno dalla rilettura automatica', async () => {
    net.on(RIMB, 'rete')
    const { session } = await mountApp('/paziente/rimborsi')
    const h = net.trattieni(RIMB)
    await premi(bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova'))
    net.on(RIMB, jsonResponse(200, []))
    await ruota(session)
    await flushPromises()
    h.resolve(jsonResponse(200, [r12()]))
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(0)
  })

  it('smontaggio durante la lettura: nessun effetto', async () => {
    net.on(RIMB, 'rete')
    const { router } = await mountApp('/paziente/rimborsi')
    const h = net.trattieni(RIMB)
    await premi(bottone('section[aria-labelledby="titolo-rimborsi"]', 'Riprova'))
    await router.push('/area')
    await flushPromises()
    // La navigazione porta il focus sul contenuto principale: la lettura tardiva non lo sposta.
    const dopoNavigazione = document.activeElement
    h.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(dopoNavigazione)
  })
})

// ---------------------------------------------------------------- storico pagamenti

describe('dettaglio prenotazione: focus dopo «Riprova» dello storico pagamenti', () => {
  async function conErrore(id = 12) {
    net.on(LISTA, jsonResponse(200, [confermata(12), confermata(14)])).on(PAG(id), 'rete')
    const ctx = await mountApp(`/paziente/prenotazioni/${id}`)
    expect(riprova()).toBeDefined()
    return ctx
  }
  const sezione = () => wrapper.get('[data-sezione="pagamenti"]')
  const riprova = () => sezione().findAll('button').find((b) => b.text() === 'Riprova')
  const titolo = () => wrapper.get('#titolo-pagamenti').element

  it('successo: il pulsante scompare e il focus va al titolo della sezione', async () => {
    await conErrore()
    const h = net.trattieni(PAG())
    await premi(riprova())
    expect(document.activeElement).toBe(document.body)
    h.resolve(jsonResponse(200, [pagamento(5)]))
    await flushPromises()
    expect(sezione().findAll('[data-pagamento]')).toHaveLength(1)
    expect(document.activeElement).toBe(titolo())
  })

  it('nuovo errore: focus sul nuovo «Riprova», che resta utilizzabile', async () => {
    await conErrore()
    net.on(PAG(), new Response('x', { status: 503 }))
    await premi(riprova())
    await flushPromises()
    expect(document.activeElement).toBe(riprova().element)
    net.on(PAG(), jsonResponse(200, []))
    await premi(riprova())
    await flushPromises()
    expect(document.activeElement).toBe(titolo())
  })

  it('focus portato altrove dall\'utente durante la lettura: non viene sottratto', async () => {
    await conErrore()
    const h = net.trattieni(PAG())
    await premi(riprova())
    const s = sentinella()
    s.focus()
    h.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(s)
  })

  it('cambio di prenotazione durante la lettura (12 → 14): nessun focus tardivo nel nuovo contesto', async () => {
    const { router } = await conErrore()
    const h = net.trattieni(PAG())
    await premi(riprova())
    net.on(LISTA, jsonResponse(200, [confermata(12), confermata(14)])).on(PAG(14), jsonResponse(200, []))
    await router.push('/paziente/prenotazioni/14')
    await flushPromises()
    const dopoNavigazione = document.activeElement
    h.resolve(jsonResponse(200, [pagamento(5)]))
    await flushPromises()
    expect(document.activeElement).toBe(dopoNavigazione)
    expect(document.activeElement).not.toBe(titolo())
  })

  it('cambio di epoca durante la lettura: nessun focus tardivo dopo la rilettura automatica', async () => {
    const { session } = await conErrore()
    const h = net.trattieni(PAG())
    await premi(riprova())
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(), jsonResponse(200, []))
    await ruota(session)
    await flushPromises()
    h.resolve(jsonResponse(200, [pagamento(5)]))
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
  })

  it('smontaggio durante la lettura: nessun effetto', async () => {
    const { router } = await conErrore()
    const h = net.trattieni(PAG())
    await premi(riprova())
    await router.push('/area')
    await flushPromises()
    // La navigazione porta il focus sul contenuto principale: la lettura tardiva non lo sposta.
    const dopoNavigazione = document.activeElement
    h.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(dopoNavigazione)
  })
})

describe('storico pagamenti: recupero del focus associato alla lettura richiesta', () => {
  /**
   * Sezione montata con uno storico reattivo gestito come dalla vista: ogni lettura riceve un
   * identificativo (`richiesta`), il completamento applicato è esplicito (`completata`).
   */
  async function sezione() {
    net.on(ME, meOk(utente()))
    const session = createSession()
    await session.ensureBootstrap()
    const storico = reactive({ stato: 'errore', voci: [], errore: 'x', aggiornata: false, richiesta: 1, completata: 1 })
    const vista = {
      avvia() { storico.richiesta += 1; storico.stato = 'caricamento'; return storico.richiesta },
      completa(id, stato = 'pronto') { if (id !== storico.richiesta) return; storico.stato = stato; storico.aggiornata = stato === 'pronto'; storico.completata = id },
    }
    wrapper = mount(PagamentiSezione, {
      props: { prenotazione: parseAppuntamento(confermata(12)), prenotazioneAggiornata: true, storico, onRileggi: () => vista.avvia() },
      global: { provide: { [SESSION_KEY]: session }, stubs: { RouterLink: true } }, attachTo: document.body,
    })
    await flushPromises()
    return { storico, vista }
  }
  const riprova = () => wrapper.findAll('button').find((b) => b.text() === 'Riprova')
  const titolo = () => wrapper.get('#titolo-pagamenti').element
  async function premiRiprova(storico) {
    await premi(riprova())
    await flushPromises()
    return storico.richiesta
  }

  it('Riprova A pendente, risultato estraneo B: nessun focus e nessun completamento di A', async () => {
    const { storico, vista } = await sezione()
    const a = await premiRiprova(storico)
    // Lettura B avviata altrove (per esempio dopo un esito certo): sostituisce A e si completa.
    const b = vista.avvia()
    vista.completa(b)
    await flushPromises()
    expect(storico.completata).toBe(b)
    expect(document.activeElement).toBe(document.body)
    // A, ormai sostituita, non può completarsi.
    vista.completa(a)
    await flushPromises()
    expect(storico.completata).toBe(b)
    expect(document.activeElement).toBe(document.body)
  })

  it('A sostituita e B in errore: il vecchio recupero non agisce; un nuovo «Riprova» funziona', async () => {
    const { storico, vista } = await sezione()
    await premiRiprova(storico)
    const b = vista.avvia()
    vista.completa(b, 'errore')
    await flushPromises()
    expect(riprova()).toBeDefined()
    expect(document.activeElement).toBe(document.body)
    const c = await premiRiprova(storico)
    vista.completa(c)
    await flushPromises()
    expect(document.activeElement).toBe(titolo())
  })

  it('completamento pertinente: titolo dopo un successo, nuovo «Riprova» dopo un errore', async () => {
    const { storico, vista } = await sezione()
    let a = await premiRiprova(storico)
    vista.completa(a, 'errore')
    await flushPromises()
    expect(document.activeElement).toBe(riprova().element)
    a = await premiRiprova(storico)
    vista.completa(a)
    await flushPromises()
    expect(document.activeElement).toBe(titolo())
  })

  it('sostituzione tra il completamento e il DOM aggiornato: nessun focus', async () => {
    const { storico, vista } = await sezione()
    const a = await premiRiprova(storico)
    vista.completa(a)
    // Nello stesso passo, prima di nextTick, parte un'altra lettura.
    vista.avvia()
    await nextTick()
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
  })

  it('focus spostato volontariamente durante la lettura: non viene sottratto', async () => {
    const { storico, vista } = await sezione()
    const a = await premiRiprova(storico)
    const s = sentinella()
    s.focus()
    vista.completa(a)
    await flushPromises()
    expect(document.activeElement).toBe(s)
  })
})

// ---------------------------------------------------------------- testi

describe('testo della verifica superata (amministrazione)', () => {
  it('stanze: verifica superata da un elenco successivo a una scrittura certa → «dati più recenti», blocco mantenuto', async () => {
    net.on(STANZE, jsonResponse(200, [stanza(2, { nome: 'Altra' })]))
    const { session } = await mountApp('/admin/stanze', admin())
    const store = adminStore(session)
    net.on(CREA_STANZA, 'rete')
    expect((await store.creaStanza({ nome: 'Sala richiesta', descrizione: null })).kind).toBe('incerto')
    await flushPromises()
    const a = net.trattieni(STANZE)
    await wrapper.get('[data-esito-incerto] button').trigger('click')
    net.on(STANZA(2), jsonResponse(200, stanza(2, { nome: 'Sala richiesta' })))
    expect((await store.aggiornaStanza(parseStanza(stanza(2, { nome: 'Altra' })), { nome: 'Sala richiesta', descrizione: null })).kind).toBe('eseguita')
    a.resolve(jsonResponse(200, [stanza(2, { nome: 'Altra' })]))
    await flushPromises()
    const testo = wrapper.get('[data-avviso-admin]').text()
    expect(testo).toBe('Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Il blocco resta: ripeti la verifica.')
    expect(testo).not.toContain('operazione')
    expect(store.bloccata('stanza-nuova:sala richiesta')).toBe(true)
  })
})
