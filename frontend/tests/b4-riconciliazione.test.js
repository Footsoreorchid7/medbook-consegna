import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { confermata, fallito, pagamento, rimborso } from './b4-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// l'esito testuale della riconciliazione non deriva da una risposta scartata dall'ordinamento
// autorevole. Dati sintetici.
const ME = 'GET /api/auth/me'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = 'GET /api/prenotazioni/12/pagamenti'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'
const RIMB = 'GET /api/rimborsi/paziente'
const CHIEDI = 'POST /api/prenotazioni/12/rimborso'

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountApp(path) {
  net.on(ME, meOk(utente()))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store: prenotazioniStore(session) }
}
const vai = async (router, path) => { await router.push(path); await flushPromises() }
const bottone = (testo) => wrapper.findAll('main button').find((b) => b.text() === testo)
const testoIncerto = (sel) => wrapper.get(sel).text()

/** Pagamento incerto sulla 12, verifica A trattenuta, poi smontaggio e nuova istanza con lettura B trattenuta. */
async function verificaSovrappostaPagamento() {
  net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG, jsonResponse(200, []))
  const ctx = await mountApp('/paziente/prenotazioni/12')
  await bottone('Paga online (simulato)').trigger('click')
  await flushPromises()
  net.on(PAGA, 'rete')
  await bottone('Conferma pagamento simulato').trigger('click')
  await flushPromises()
  const a = net.trattieni(PAG)
  await bottone('Verifica nello storico pagamenti').trigger('click')
  await flushPromises()
  net.on(LISTA, jsonResponse(200, [confermata(12)]))
  net.on(PAG, jsonResponse(200, []))
  await vai(ctx.router, '/paziente/prenotazioni')
  net.on(LISTA, jsonResponse(200, [confermata(12)]))
  const b = net.trattieni(PAG)
  await vai(ctx.router, '/paziente/prenotazioni/12')
  return { ...ctx, a, b }
}

describe('D1 pagamenti: esito della verifica superata', () => {
  it('B recente con PAGATO applicata, A tardiva senza pagamenti attivi: messaggio coerente col riscontro autorevole, blocco prima di fetch', async () => {
    const { a, b, store } = await verificaSovrappostaPagamento()
    b.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    a.resolve(jsonResponse(200, []))
    await flushPromises()
    const avviso = testoIncerto('[data-pagamento-incerto]')
    expect(avviso).not.toContain('non compare un nuovo pagamento')
    expect(avviso).not.toContain('Un nuovo tentativo è possibile')
    expect(avviso).toContain('risulta un pagamento pagato o in attesa')
    expect(avviso).toContain('Non è possibile stabilire con certezza se corrisponda al tuo tentativo')
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(await store.pagaOnline(parseAppuntamento(confermata(12)), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
    // Nessuna rilettura automatica dopo la verifica superata.
    expect(net.callsTo(PAG)).toHaveLength(4)
  })

  it('ordine inverso: B recente negativa applicata, A tardiva positiva ignorata; esito neutro, blocco e recupero manuale', async () => {
    const { a, b, store } = await verificaSovrappostaPagamento()
    b.resolve(jsonResponse(200, []))
    await flushPromises()
    a.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    const avviso = testoIncerto('[data-pagamento-incerto]')
    expect(avviso).not.toContain('risulta un pagamento pagato o in attesa')
    expect(avviso).not.toContain('non compare un nuovo pagamento')
    expect(wrapper.get('[data-verifica-superata]').text()).toContain('Ripeti la verifica')
    // Il riscontro condiviso non viene ricreato dalla risposta vecchia.
    expect(store.pagamentoAttivoNoto(12)).toBe(false)
    expect(wrapper.find('[data-attivo-non-mostrato]').exists()).toBe(false)
    // Possibilità effettiva coerente: il tentativo incerto resta bloccato fino a una nuova verifica.
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    expect(await store.pagaOnline(parseAppuntamento(confermata(12)), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
    // Recupero manuale: una nuova verifica applicata conclude la riconciliazione.
    net.on(PAG, jsonResponse(200, [fallito(4)]), jsonResponse(200, [fallito(4)]))
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    expect(testoIncerto('[data-pagamento-incerto]')).toContain('Potrebbe corrispondere al tuo tentativo, ma non è possibile stabilirlo con certezza')
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeDefined()
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })
})

describe('D1 rimborsi: esito della verifica superata', () => {
  const nonErogata = () => confermata(12, { stato: 'NON_EROGATA' })

  /** Rimborso incerto sulla 12, verifica A trattenuta, poi smontaggio e nuova istanza con lettura B trattenuta. */
  async function verificaSovrappostaRimborso() {
    net.on(LISTA, jsonResponse(200, [nonErogata()])).on(PAG, jsonResponse(200, [pagamento(5)])).on(RIMB, jsonResponse(200, []))
    const ctx = await mountApp('/paziente/prenotazioni/12')
    await wrapper.get('#motivazione-rimborso').setValue('Visita non erogata')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    net.on(CHIEDI, 'rete')
    await bottone('Invia richiesta di rimborso').trigger('click')
    await flushPromises()
    const a = net.trattieni(RIMB)
    await bottone('Verifica nei miei rimborsi').trigger('click')
    await flushPromises()
    net.on(LISTA, jsonResponse(200, [nonErogata()]))
    await vai(ctx.router, '/paziente/prenotazioni')
    net.on(LISTA, jsonResponse(200, [nonErogata()])).on(PAG, jsonResponse(200, [pagamento(5)]))
    const b = net.trattieni(RIMB)
    await vai(ctx.router, '/paziente/prenotazioni/12')
    return { ...ctx, a, b }
  }

  it('B recente con la richiesta applicata, A tardiva senza: esito dal riscontro autorevole, nessun modulo, rifiuto prima di fetch', async () => {
    const { a, b, store } = await verificaSovrappostaRimborso()
    b.resolve(jsonResponse(200, [rimborso(31)]))
    await flushPromises()
    a.resolve(jsonResponse(200, []))
    await flushPromises()
    // Incertezza risolta dal riscontro autorevole corrente; la vista che aveva avviato A è smontata
    // e non mostra nulla, la nuova istanza mostra la richiesta letta da B.
    expect(wrapper.find('[data-rimborso-incerto]').exists()).toBe(false)
    expect(wrapper.find('[data-avviso-rimborso]').exists()).toBe(false)
    expect(wrapper.get('[data-rimborso] [data-campo="stato-rimborso"]').text()).toBe('Richiesto, in attesa di decisione')
    expect(store.rimborsoIncerto(12)).toBeNull()
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    expect(await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Ancora')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
  })

  it('ordine inverso: B recente senza richiesta applicata, A tardiva con richiesta ignorata; esito neutro e blocco', async () => {
    const { a, b, store } = await verificaSovrappostaRimborso()
    b.resolve(jsonResponse(200, []))
    await flushPromises()
    a.resolve(jsonResponse(200, [rimborso(31)]))
    await flushPromises()
    expect(wrapper.find('[data-avviso-rimborso]').exists()).toBe(false)
    expect(wrapper.get('[data-rimborso-incerto] [data-verifica-superata]').text()).toContain('Ripeti la verifica')
    expect(store.rimborsoNoto(5)).toBe(false)
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    expect(await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Ancora')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
    // Recupero manuale con una verifica applicata.
    net.on(RIMB, jsonResponse(200, []), jsonResponse(200, []))
    await bottone('Verifica nei miei rimborsi').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-rimborso-incerto]').text()).toContain('non risulta una richiesta per questo pagamento')
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(true)
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
  })
})

describe('isolamento fra identità', () => {
  it('verifica tardiva di un\'identità precedente: nessun esito né riscontro per il nuovo utente', async () => {
    net.on(ME, meOk(utente()))
    const session = createSession()
    await session.ensureBootstrap()
    const store = prenotazioniStore(session)
    net.on(PAGA, 'rete')
    await store.pagaOnline(parseAppuntamento(confermata(12)), [])
    const a = net.trattieni(PAG)
    const verifica = store.verificaPagamento(12)
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    a.resolve(jsonResponse(200, []))
    expect((await verifica).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, pagamentiIncerti: [], ambitiPagamento: [] })
  })
})
