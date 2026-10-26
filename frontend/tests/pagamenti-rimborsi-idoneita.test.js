import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { parsePagamento } from '../src/api/pagamenti.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { confermata, fallito, pagamento, rimborso } from './pagamenti-rimborsi-fixtures.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Idoneità durante riletture in background e blocchi condivisi.
// dopo la riconciliazione. Dati sintetici.
const ME = 'GET /api/auth/me'
const LOGIN = 'POST /api/auth/login'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = 'GET /api/prenotazioni/12/pagamenti'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'
const ANNULLA = 'POST /api/prenotazioni/12/annulla-paziente'
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
const nonErogata = () => confermata(12, { stato: 'NON_EROGATA' })

async function dettaglio(prenotazione = confermata(12), storico = [], extra = () => {}) {
  net.on(LISTA, jsonResponse(200, [prenotazione]))
  net.on(PAG, jsonResponse(200, storico))
  extra()
  return mountApp('/paziente/prenotazioni/12')
}

async function apriConfermaPagamento() {
  await bottone('Paga online (simulato)').trigger('click')
  await flushPromises()
}

describe('idoneità separata dai dati visualizzati', () => {
  it('201/PAGATO con riletture trattenute: secondo POST impossibile in ogni istante', async () => {
    const { store } = await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    const rilettura = net.trattieni(LISTA)
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    // Rilettura della prenotazione in corso: dati vecchi visibili (storico vuoto), azioni sospese.
    expect(wrapper.get('[data-sezione="pagamenti"]').text()).toContain('Nessun pagamento registrato.')
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(true)
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(bottone('Conferma pagamento simulato')).toBeUndefined()
    // Anche una chiamata diretta allo store è rifiutata prima di fetch.
    expect(await store.pagaOnline(parseAppuntamento(confermata(12)), [])).toEqual({ kind: 'bloccata' })
    const storico = net.trattieni(PAG)
    rilettura.resolve(jsonResponse(200, [confermata(12)]))
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    storico.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('201/FALLITO: nuovo tentativo proposto solo dopo l\'aggiornamento riuscito e con nuova conferma', async () => {
    await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, jsonResponse(201, fallito(9)))
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    const storico = net.trattieni(PAG)
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    storico.resolve(jsonResponse(200, [fallito(9)]))
    await flushPromises()
    expect(bottone('Paga online (simulato)').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(PAGA)).toHaveLength(1)
    await apriConfermaPagamento()
    expect(net.callsTo(PAGA)).toHaveLength(1)
    net.on(PAGA, jsonResponse(201, pagamento(10)))
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG, jsonResponse(200, [pagamento(10), fallito(9)]))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(net.callsTo(PAGA)).toHaveLength(2)
  })

  it('rilettura fallita: azioni bloccate e recupero manuale con Riprova', async () => {
    await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, jsonResponse(201, fallito(9)))
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG, jsonResponse(500, { error: 'x' }))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    const allarmi = wrapper.findAll('[data-sezione="pagamenti"] [role="alert"]').map((a) => a.text())
    expect(allarmi.some((t) => t.includes('Non è stato possibile caricare i pagamenti'))).toBe(true)
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    net.on(PAG, jsonResponse(200, [fallito(9)]))
    await wrapper.findAll('[data-sezione="pagamenti"] button').find((b) => b.text() === 'Riprova').trigger('click')
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeDefined()
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('conferma di pagamento aperta prima dell\'invalidazione: chiusa e non utilizzabile, anche dal gestore', async () => {
    const { store } = await dettaglio()
    await apriConfermaPagamento()
    const confermaVecchia = bottone('Conferma pagamento simulato').element
    // Un'altra istanza annulla con esito certo; il dato già mostrato viene invalidato.
    net.on(ANNULLA, jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    expect((await store.annulla(parseAppuntamento(confermata(12)))).kind).toBe('annullata')
    await flushPromises()
    const rilettura = net.trattieni(LISTA)
    await bottone('Aggiorna prenotazione').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-conferma-pagamento]').exists()).toBe(false)
    // Il gestore ricontrolla l'idoneità: il vecchio pulsante non invia nulla.
    confermaVecchia.click()
    await flushPromises()
    expect(net.callsTo(PAGA)).toHaveLength(0)
    rilettura.resolve(jsonResponse(200, [confermata(12)]))
    net.on(PAG, jsonResponse(200, []))
    await flushPromises()
    expect(wrapper.find('[data-conferma-pagamento]').exists()).toBe(false)
    expect(bottone('Paga online (simulato)')).toBeDefined()
    expect(store.state.pagamentiInCorso).toEqual([])
  })

  it('conferma di annullamento aperta prima dell\'invalidazione: chiusa dal pagamento riuscito', async () => {
    const { store } = await dettaglio()
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    const annullaVecchio = bottone('Conferma annullamento').element
    // Lo stesso tentativo avviato da un'altra istanza invalida la conferma locale.
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    expect((await store.pagaOnline(parseAppuntamento(confermata(12)), [])).kind).toBe('pagato')
    await flushPromises()
    const rilettura = net.trattieni(LISTA)
    await bottone('Aggiorna prenotazione').trigger('click')
    await flushPromises()
    expect(bottone('Conferma annullamento')).toBeUndefined()
    annullaVecchio.click()
    await flushPromises()
    expect(net.callsTo(ANNULLA)).toHaveLength(0)
    net.on(PAG, jsonResponse(200, [pagamento(9)]))
    rilettura.resolve(jsonResponse(200, [confermata(12)]))
    await flushPromises()
    expect(bottone('Conferma annullamento')).toBeUndefined()
  })

  it('201 di rimborso con riletture trattenute: duplicato impossibile', async () => {
    const { store } = await dettaglio(nonErogata(), [pagamento(5)], () => net.on(RIMB, jsonResponse(200, [])))
    await wrapper.get('#motivazione-rimborso').setValue('Visita non erogata')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    net.on(CHIEDI, jsonResponse(201, rimborso(31)))
    const rilettura = net.trattieni(LISTA)
    await bottone('Invia richiesta di rimborso').trigger('click')
    await flushPromises()
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    expect(bottone('Prosegui')).toBeUndefined()
    expect(await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Ancora')).toEqual({ kind: 'bloccata' })
    // Rilettura completata con un elenco rimborsi ancora vecchio (vuoto): il duplicato resta impossibile.
    net.on(PAG, jsonResponse(200, [pagamento(5)]))
    const elenco = net.trattieni(RIMB)
    rilettura.resolve(jsonResponse(200, [nonErogata()]))
    await flushPromises()
    expect(bottone('Prosegui')).toBeUndefined()
    elenco.resolve(jsonResponse(200, [rimborso(31)]))
    await flushPromises()
    expect(bottone('Prosegui')).toBeUndefined()
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(1)
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
  })
})

describe('blocco condiviso dopo la riconciliazione', () => {
  it('verifica pendente, smontaggio, nuova istanza: PAGATO mantiene il blocco; uno storico vecchio non lo rimuove', async () => {
    const { router, store } = await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, 'rete')
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    // Storico vecchio: una lettura avviata prima della verifica (ad esempio di un'altra istanza).
    const storicoVecchio = store.inizioLettura()
    const verifica = net.trattieni(PAG)
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    // Smontaggio della vista originaria con la verifica ancora pendente.
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    await vai(router, '/paziente/prenotazioni')
    // Nuova istanza: la sua lettura parte dopo la verifica ed è quindi più recente.
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    const storicoNuovaIstanza = net.trattieni(PAG)
    await vai(router, '/paziente/prenotazioni/12')
    verifica.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    // La risposta vecchia, arrivata dopo, non rimuove il riscontro della verifica.
    store.registraStorico(12, [], storicoVecchio)
    expect(store.pagamentoBloccato(12)).toBe(true)
    storicoNuovaIstanza.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(await store.pagaOnline(parseAppuntamento(confermata(12)), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('nuova istanza con storico vecchio rispetto alla verifica: il riscontro resta e si recupera con una lettura più recente', async () => {
    const { router, store } = await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, 'rete')
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    await vai(router, '/paziente/prenotazioni')
    // La nuova istanza avvia la propria lettura (vecchia) prima della verifica.
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    const storicoNuovaIstanza = net.trattieni(PAG)
    await vai(router, '/paziente/prenotazioni/12')
    net.on(PAG, jsonResponse(200, [pagamento(9)]))
    const rilettura = net.trattieni(PAG)
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    storicoNuovaIstanza.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    rilettura.resolve(jsonResponse(200, [pagamento(9)]))
    await flushPromises()
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('verifica con IN_ATTESA in sede: blocco condiviso, nessun POST anche con chiamata diretta', async () => {
    const { store } = await dettaglio()
    await apriConfermaPagamento()
    net.on(PAGA, jsonResponse(503, { error: 'x' }))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    net.on(PAG, jsonResponse(200, [pagamento(3, { modalita: 'DESK_POS', stato: 'IN_ATTESA', pagatoIl: null })]),
      jsonResponse(200, [pagamento(3, { modalita: 'DESK_POS', stato: 'IN_ATTESA', pagatoIl: null })]))
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('un nuovo pagamento non è possibile')
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    expect(await store.pagaOnline(parseAppuntamento(confermata(12)), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('verifica senza pagamenti attivi: nuovo tentativo solo con dati aggiornati e conferma', async () => {
    await dettaglio(confermata(12), [fallito(4)])
    await apriConfermaPagamento()
    net.on(PAGA, 'rete')
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    net.on(PAG, jsonResponse(200, [fallito(4)]))
    const rilettura = net.trattieni(PAG)
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    // Verifica riuscita ma rilettura dello storico mostrato ancora in corso: nessuna proposta.
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('non compare un nuovo pagamento')
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
    rilettura.resolve(jsonResponse(200, [fallito(4)]))
    await flushPromises()
    await bottone('Nuovo tentativo di pagamento simulato').trigger('click')
    await flushPromises()
    expect(net.callsTo(PAGA)).toHaveLength(1)
    expect(wrapper.find('[data-conferma-pagamento]').exists()).toBe(true)
  })

  it('aggiornamento autorevole senza pagamenti attivi: il blocco condiviso si rimuove (nessun blocco permanente)', async () => {
    const { store } = await dettaglio()
    store.registraStorico(12, [parsePagamento(pagamento(9), 12)], store.inizioLettura())
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(wrapper.find('[data-attivo-non-mostrato]').exists()).toBe(true)
    net.on(PAG, jsonResponse(200, []))
    await bottone('Aggiorna lo storico').trigger('click')
    await flushPromises()
    expect(store.pagamentoBloccato(12)).toBe(false)
    expect(bottone('Paga online (simulato)')).toBeDefined()
  })
})

describe('store: ordine delle letture, chiamate dirette e identità', () => {
  async function autenticata() {
    net.on(ME, meOk(utente(), CSRF_A))
    const session = createSession()
    await session.ensureBootstrap()
    return { session, store: prenotazioniStore(session) }
  }
  const pren = () => parseAppuntamento(confermata(12))
  const pag = (x) => parsePagamento(x, 12)

  it('una risposta vecchia non rimuove un blocco più recente; una più recente sì', async () => {
    const { store } = await autenticata()
    const vecchia = store.inizioLettura()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    await store.pagaOnline(pren(), [])
    store.registraStorico(12, [], vecchia)
    expect(store.pagamentoBloccato(12)).toBe(true)
    store.registraStorico(12, [], store.inizioLettura())
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('lettura avviata prima del riscontro della verifica: non sblocca anche se risponde dopo', async () => {
    const { store } = await autenticata()
    net.on(PAGA, 'rete')
    await store.pagaOnline(pren(), [pag(fallito(4))])
    const precedente = store.inizioLettura()
    net.on(PAG, jsonResponse(200, [fallito(4), pagamento(9)]))
    expect((await store.verificaPagamento(12)).esito).toBe('attivo')
    store.registraStorico(12, [pag(fallito(4))], precedente)
    expect(store.pagamentoBloccato(12)).toBe(true)
  })

  it('intero storico valutato: pagamento attivo già noto prima del tentativo blocca comunque', async () => {
    const { store } = await autenticata()
    net.on(PAGA, 'rete')
    await store.pagaOnline(pren(), [pag(fallito(3))])
    // Il pagamento 3 risulta ora IN_ATTESA: nessun ID nuovo, ma un pagamento attivo incompatibile.
    net.on(PAG, jsonResponse(200, [pagamento(3, { modalita: 'DESK_POS', stato: 'IN_ATTESA', pagatoIl: null })]))
    const esito = await store.verificaPagamento(12)
    expect(esito.esito).toBe('attivo')
    expect(store.pagamentoIncerto(12).nuovi).toEqual([])
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('chiamata diretta con storico contenente un pagamento attivo: rifiuto prima di fetch, nessuna mutazione condivisa', async () => {
    const { store } = await autenticata()
    expect(await store.pagaOnline(pren(), [pag(pagamento(9))])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(0)
    // Il dato locale riguarda solo quella chiamata: lo stato condiviso resta invariato.
    expect(store.state.ambitiPagamento).toEqual([])
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('rimborso: richiesta nota blocca il duplicato prima di fetch; risposta vecchia non la rimuove', async () => {
    const { store } = await autenticata()
    const vecchia = store.inizioLettura()
    net.on(CHIEDI, jsonResponse(201, rimborso(31)))
    expect((await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Visita non erogata')).kind).toBe('richiesto')
    expect(await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'Visita non erogata')).toEqual({ kind: 'bloccata' })
    store.registraRimborsi([], vecchia)
    expect(store.rimborsoNoto(5)).toBe(true)
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
    store.registraRimborsi([], store.inizioLettura())
    expect(store.rimborsoNoto(5)).toBe(false)
  })

  it('ticket di un\'identità precedente ignorato; riscontri eliminati al cambio di utente', async () => {
    const { session, store } = await autenticata()
    const ticket = store.inizioLettura()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    await store.pagaOnline(pren(), [])
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    expect(store.state.ambitiPagamento).toEqual([])
    store.registraStorico(12, [pag(pagamento(9))], ticket)
    expect(store.state.ambitiPagamento).toEqual([])
  })
})
