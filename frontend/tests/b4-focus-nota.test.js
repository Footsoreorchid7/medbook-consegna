import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import PagamentiSezione from '../src/components/PagamentiSezione.vue'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { confermata } from './b4-fixtures.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { rimborso } from './b4-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Riconciliazione dei rimborsi limitata a una prenotazione: la vista aggiorna soltanto le righe
// coperte e validate, senza adottare l'intero elenco. Dati sintetici.
const ME = 'GET /api/auth/me'
const LOGOUT = 'POST /api/auth/logout'
const RIMB = 'GET /api/rimborsi/paziente'
const chiedi = (id) => `POST /api/prenotazioni/${id}/rimborso`

// Prenotazione 12 → pagamento 5, prenotazione 14 → pagamento 6.
const r12 = (extra = {}) => rimborso(31, { idPrenotazione: 12, idPagamento: 5, ...extra })
const r14 = (extra = {}) => rimborso(41, { idPrenotazione: 14, idPagamento: 6, richiestoIl: '2026-10-16T08:00:00', ...extra })

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

/** Richieste incerte per le prenotazioni indicate, poi apertura di «I miei rimborsi». */
async function apriConIncerti(prenotazioni, iniziale) {
  net.on(ME, meOk(utente()))
  const session = createSession()
  await session.ensureBootstrap()
  const store = prenotazioniStore(session)
  for (const [idPrenotazione, idPagamento] of prenotazioni) {
    net.on(chiedi(idPrenotazione), 'rete')
    expect((await store.richiediRimborso(idPrenotazione, { idPagamento, importo: 80 }, 'Visita non erogata')).kind).toBe('incerto')
  }
  const tenuta = iniziale === 'trattieni' ? net.trattieni(RIMB) : null
  if (!tenuta) net.on(RIMB, jsonResponse(200, iniziale))
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push('/paziente/rimborsi')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store, tenuta }
}

const verificaDi = (id) => wrapper.findAll('[data-rimborso-incerto]')
  .find((w) => w.text().includes(`prenotazione n. ${id}`)).findAll('button').find((b) => b.text() === "Verifica nell'elenco")
const avviso = () => wrapper.find('[role="status"].notice, [role="alert"].notice')


async function avvisoSuperato() {
  const ctx = await apriConIncerti([[12, 5]], [])
  const a = net.trattieni(RIMB)
  await verificaDi(12).trigger('click')
  ctx.store.registraRimborsi([], ctx.store.inizioLettura())
  a.resolve(jsonResponse(200, [r12()]))
  await flushPromises()
  expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
  return ctx
}

describe('D2: aggiornamento della nota elenco', () => {
  it('lettura completa riuscita elimina solo la nota e conserva la verifica specifica', async () => {
    const { store } = await avvisoSuperato()
    net.on(RIMB, jsonResponse(200, []))
    await wrapper.get('[data-aggiorna-elenco] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(false)
    expect(avviso().text()).toContain('Ripeti la verifica')
    expect(store.rimborsoIncerto(12).verifica).toBe('superata')
    expect(verificaDi(12).exists()).toBe(true)
  })
  it('errore conserva la nota e il pulsante per riprovare', async () => {
    await avvisoSuperato()
    net.on(RIMB, 'rete')
    await wrapper.get('[data-aggiorna-elenco] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
  })
  it('risposta scartata non elimina la nota', async () => {
    const { store } = await avvisoSuperato()
    const a = net.trattieni(RIMB)
    await wrapper.get('[data-aggiorna-elenco] button').trigger('click')
    store.registraRimborsi([], store.inizioLettura())
    a.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
  })
  it('un nuovo avviso durante il caricamento non viene cancellato', async () => {
    const { store } = await avvisoSuperato()
    const lettura = net.trattieni(RIMB)
    await wrapper.get('[data-aggiorna-elenco] button').trigger('click')
    const verifica = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    store.registraRimborsi([], store.inizioLettura())
    verifica.resolve(jsonResponse(200, [r12()]))
    await flushPromises()
    const testo = avviso().text()
    lettura.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(avviso().text()).toBe(testo)
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
  })
})

async function pagamentoIncerto() {
  net.on(ME, meOk(utente()))
  const session = createSession()
  await session.ensureBootstrap()
  const store = prenotazioniStore(session)
  net.on('POST /api/prenotazioni/12/pagamento-online', 'rete')
  await store.pagaOnline(parseAppuntamento(confermata(12)), [])
  wrapper = mount(PagamentiSezione, {
    props: { prenotazione: parseAppuntamento(confermata(12)), prenotazioneAggiornata: true,
      storico: { stato: 'pronto', aggiornata: true, voci: [] } },
    global: { provide: { [SESSION_KEY]: session }, stubs: { RouterLink: true } }, attachTo: document.body,
  })
  return { session, store }
}
const PAG = 'GET /api/prenotazioni/12/pagamenti'
describe('D1: focus dopo verifica pagamento', () => {
  it.each(['verificata', 'errore', 'superata'])('focus sul messaggio per %s', async (esito) => {
    const { store } = await pagamentoIncerto()
    const a = net.trattieni(PAG)
    await wrapper.get('[data-pagamento-incerto] button').trigger('click')
    if (esito === 'superata') store.registraStorico(12, [], store.inizioLettura())
    a.resolve(esito === 'errore' ? jsonResponse(500, {}) : jsonResponse(200, []))
    await flushPromises()
    const sel = esito === 'errore' ? '[data-avviso-pagamento]' : '[data-pagamento-incerto]'
    expect(document.activeElement).toBe(wrapper.get(sel).element)
    expect(wrapper.emitted('rileggi')?.length ?? 0).toBe(esito === 'verificata' ? 1 : 0)
  })
  it.each(['smontaggio', 'contesto', 'ritorno al contesto', 'epoca'])('nessun focus tardivo dopo %s', async (cambio) => {
    const { session } = await pagamentoIncerto()
    const a = net.trattieni(PAG)
    await wrapper.get('[data-pagamento-incerto] button').trigger('click')
    const sentinel = document.createElement('button')
    document.body.append(sentinel)
    if (cambio === 'smontaggio') wrapper.unmount()
    if (cambio === 'contesto' || cambio === 'ritorno al contesto') {
      await wrapper.setProps({ prenotazione: parseAppuntamento(confermata(14)) })
      if (cambio === 'ritorno al contesto') await wrapper.setProps({ prenotazione: parseAppuntamento(confermata(12)) })
    }
    if (cambio === 'epoca') {
      net.on(LOGOUT, new Response(null, { status: 204 }))
      await session.logout()
    }
    sentinel.focus()
    a.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(document.activeElement).toBe(sentinel)
    sentinel.remove()
  })
})
