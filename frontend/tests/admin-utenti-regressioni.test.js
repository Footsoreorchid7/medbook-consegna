import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseAccount, parseUtenteAmministrativo } from '../src/api/utenti.js'
import { adminStore } from '../src/admin/store.js'
import { testoEsito } from '../src/admin/formato.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CREA, GESTIONALI, PASSWORD, RICERCA, STATO, account, admin, amministrativo, pagina } from './admin-utenti-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Esiti 429/503 incerti con attesa separata e riscontri ordinati per identità.

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const DATI = Object.freeze({ nome: 'Nina', cognome: 'Nuova', email: 'nina@example.test', ruolo: 'OPERATORE_DESK' })
const g = (id, extra) => parseAccount(account(id, extra))
const a = (id, extra) => parseUtenteAmministrativo(amministrativo(id, extra))
const pag = (righe, prossimo = null) => ({ risultati: righe, prossimoDopoId: prossimo })
const qId = (id) => RICERCA(`idUtente=${id}&limite=1`)
const qEmail = (dopo = 0) => RICERCA(`email=nina%40example.test${dopo ? `&dopoId=${dopo}` : ''}&limite=50`)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.restoreAllMocks()
})

async function avvia(user = admin()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: adminStore(session) }
}
/** Stato di 5 da ATTIVO a DISABILITATO con esito incerto. */
async function statoIncerto(store, risposta = 'rete') {
  net.on(STATO(5), risposta)
  return store.cambiaStato(g(5), 'DISABILITATO')
}

describe('429/503: esito incerto e attesa separata', () => {
  it('503 con Retry-After e JSON arbitrario, 429 generico: incerti, nessuna certezza dedotta da testo, JSON o header', async () => {
    const { store } = await avvia()
    expect(await statoIncerto(store, jsonResponse(503, { qualsiasi: true }, { 'Retry-After': '7' }))).toMatchObject({ kind: 'incerto', retryAfter: 7 })
    expect(store.bloccata('utente:5')).toBe(true)
    expect(store.attesaResidua()).toBeGreaterThan(5)
    const { store: s2 } = await avvia(admin({ idUtente: 2 }))
    net.on(STATO(6), new Response('Too Many Requests', { status: 429 }))
    expect(await s2.cambiaStato(g(6), 'DISABILITATO')).toMatchObject({ kind: 'incerto', retryAfter: null })
    expect(testoEsito({ kind: 'incerto', retryAfter: 7 })).not.toMatch(/non (è stata )?eseguita/)
    expect(testoEsito({ kind: 'incerto', retryAfter: 7 })).toContain('la scadenza non chiarisce')
  })

  it('riconciliazione durante l\'attesa: la verifica chiude l\'incertezza ma non anticipa alcun invio', async () => {
    const { store } = await avvia()
    let ora = 1_000_000
    vi.spyOn(Date, 'now').mockImplementation(() => ora)
    await statoIncerto(store, jsonResponse(503, { error: 'x' }, { 'Retry-After': '30' }))
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    expect(await store.verifica('utente:5')).toMatchObject({ kind: 'verificata', esito: 'precedente' })
    expect(store.bloccata('utente:5')).toBe(false)
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toEqual({ kind: 'attesa', retryAfter: 30 })
    ora += 30_000
    net.on(STATO(5), jsonResponse(200, amministrativo(5, { statoAccesso: 'DISABILITATO' })))
    expect((await store.cambiaStato(g(5), 'DISABILITATO')).kind).toBe('eseguita')
    expect(net.callsTo(STATO(5))).toHaveLength(2)
  })

  it('attesa: conservata alla rotazione dello stesso utente, isolata tra identità', async () => {
    const { session, store } = await avvia()
    await statoIncerto(store, jsonResponse(503, { error: 'x' }, { 'Retry-After': '60' }))
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    expect(store.attesaResidua()).toBeGreaterThan(50)
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(admin({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    expect(store.attesaResidua()).toBe(0)
    expect(store.state.incerte).toEqual([])
  })
})

describe('riscontri ordinati per ID', () => {
  it('verifica A (ATTIVO) avviata prima, ricerca B più recente (DISABILITATO) applicata prima: vale B', async () => {
    const { store } = await avvia()
    await statoIncerto(store)
    const h = net.trattieni(qId(5))
    const lettura = store.leggiVerifica('utente:5')
    const b = store.inizioLettura()
    store.registraRicerca({ idUtente: '5' }, 0, pag([a(5, { statoAccesso: 'DISABILITATO' })]), b)
    h.resolve(jsonResponse(200, pagina([amministrativo(5)])))
    const l = await lettura
    expect(store.esitoVerifica('utente:5', l)).toMatchObject({ kind: 'verificata', esito: 'come-richiesto', fonte: 'successiva', voce: { statoAccesso: 'DISABILITATO' } })
  })

  it('ordine informativo inverso: B avviata prima e arrivata dopo A non sostituisce A', async () => {
    const { store } = await avvia()
    await statoIncerto(store)
    const b = store.inizioLettura()
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    const l = await store.leggiVerifica('utente:5')
    store.registraRicerca({ idUtente: '5' }, 0, pag([a(5, { statoAccesso: 'DISABILITATO' })]), b)
    expect(store.esitoVerifica('utente:5', l)).toMatchObject({ esito: 'precedente', fonte: 'verifica' })
  })

  it('A applicata → B applicata → costruzione dell\'esito di A: vale B; elenco gestionale come B; riscontri di altri utenti ininfluenti', async () => {
    const { store } = await avvia()
    await statoIncerto(store)
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    const l = await store.leggiVerifica('utente:5')
    store.registraElenco([g(9), g(5, { statoAccesso: 'DISABILITATO' })], store.inizioLettura())
    const e = store.esitoVerifica('utente:5', l)
    expect(e).toMatchObject({ esito: 'come-richiesto', fonte: 'successiva' })
    // Proiezione dell'elenco conservata: profili presenti perché restituiti, mai inventati altrove.
    expect(e.voce).toMatchObject({ proiezione: 'gestionale', paziente: false })
  })

  it('assenza per ID coperta solo dal filtro ID da solo; con altri filtri nessuna copertura', async () => {
    const { store } = await avvia()
    await statoIncerto(store)
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    const l = await store.leggiVerifica('utente:5')
    store.registraRicerca({ idUtente: '5', statoAccesso: 'ATTIVO' }, 0, pag([]), store.inizioLettura())
    expect(store.esitoVerifica('utente:5', l).esito).toBe('precedente')
    await statoIncerto(store)
    const l2 = await (net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)]))), store.leggiVerifica('utente:5'))
    store.registraRicerca({ idUtente: '5' }, 0, pag([]), store.inizioLettura())
    expect(store.esitoVerifica('utente:5', l2).esito).toBe('assente')
  })

  it('A superata prima della costruzione (cambio di epoca): esito neutro, blocco mantenuto, nessuna rilettura', async () => {
    const { session, store } = await avvia()
    await statoIncerto(store)
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    const l = await store.leggiVerifica('utente:5')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    expect(store.esitoVerifica('utente:5', l)).toEqual({ kind: 'obsoleto' })
    expect(store.bloccata('utente:5')).toBe(true)
    // Dopo la rotazione i riscontri precedenti non valgono: una nuova lettura applicata sì.
    const vecchio = l.ticket
    store.registraRicerca({ idUtente: '5' }, 0, pag([a(5, { statoAccesso: 'DISABILITATO' })]), vecchio)
    net.on(qId(5), jsonResponse(200, pagina([amministrativo(5)])))
    expect((await store.verifica('utente:5')).esito).toBe('precedente')
    expect(net.callsTo(qId(5))).toHaveLength(2)
  })

  it('identità diversa durante la lettura: risposta vecchia scartata, nessun riscontro trasferito', async () => {
    const { session, store } = await avvia()
    await statoIncerto(store)
    const h = net.trattieni(qId(5))
    const lettura = store.leggiVerifica('utente:5')
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(admin({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve?.(jsonResponse(200, pagina([amministrativo(5)])))
    expect((await lettura).kind).toBe('obsoleto')
    expect(store.state.incerte).toEqual([])
  })
})

describe('riscontri ordinati per email', () => {
  it('pagina senza corrispondenza esatta non prova l\'assenza; l\'esaurimento sì; 10 pagine: non conclusiva', async () => {
    const { store } = await avvia()
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    const piena = (base) => pagina(Array.from({ length: 50 }, (_, i) => amministrativo(base + i + 1, { email: `nina@example.test.z${i}` })), base + 50)
    net.on(qEmail(0), jsonResponse(200, piena(0))).on(qEmail(50), jsonResponse(200, pagina([amministrativo(51, { email: 'nina@example.test.y' })])))
    expect(await store.verifica('email:nina@example.test')).toMatchObject({ kind: 'verificata', esito: 'assente' })
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    for (let i = 0; i < 10; i++) net.on(qEmail(i * 50), jsonResponse(200, piena(i * 50)))
    expect(await store.verifica('email:nina@example.test')).toEqual({ kind: 'non-conclusiva' })
    expect(store.bloccata('email:nina@example.test')).toBe(true)
  })

  it('dopo una lettura non conclusiva vale solo un riscontro più recente sufficiente: prefisso esaurito sì, filtri aggiuntivi o elenco gestionale no', async () => {
    const { store } = await avvia()
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    const piena = (base) => pagina(Array.from({ length: 50 }, (_, i) => amministrativo(base + i + 1, { email: `nina@example.test.z${i}` })), base + 50)
    for (let i = 0; i < 10; i++) net.on(qEmail(i * 50), jsonResponse(200, piena(i * 50)))
    const l = await store.leggiVerifica('email:nina@example.test')
    expect(l).toMatchObject({ kind: 'letta', conclusiva: false })
    store.registraElenco([g(3)], store.inizioLettura())
    store.registraRicerca({ email: 'nina@', ruolo: 'NESSUNO' }, 0, pag([]), store.inizioLettura())
    store.registraRicerca({ email: 'nina@' }, 0, pag([], 9), store.inizioLettura())
    expect(store.esitoVerifica('email:nina@example.test', l)).toEqual({ kind: 'non-conclusiva' })
    store.registraRicerca({ email: 'nina@' }, 0, pag([a(4, { email: 'nina@example.other' })]), store.inizioLettura())
    expect(store.esitoVerifica('email:nina@example.test', l)).toMatchObject({ kind: 'verificata', esito: 'assente', fonte: 'successiva' })
  })

  it('assenza coperta A poi presenza B più recente (elenco): vale B, non attribuita al tentativo', async () => {
    const { store } = await avvia()
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    net.on(qEmail(0), jsonResponse(200, pagina([])))
    const l = await store.leggiVerifica('email:nina@example.test')
    store.registraElenco([g(20, { email: 'nina@example.test' })], store.inizioLettura())
    expect(store.esitoVerifica('email:nina@example.test', l)).toMatchObject({ esito: 'presente', voce: { idUtente: 20 }, fonte: 'successiva' })
    expect(scritture()).toBe(1)
  })
})

describe('vista: messaggio costruito sul riscontro corrente', () => {
  async function mountApp() {
    net.on(ME, meOk(admin())).on(GESTIONALI, jsonResponse(200, [account(1, { ruoloGestionale: 'AMMINISTRATORE' }), account(5)]))
    const session = createSession()
    const router = createAppRouter({ session, history: createMemoryHistory() })
    router.push('/admin/utenti')
    await router.isReady()
    wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
    await flushPromises()
    return { store: adminStore(session) }
  }
  const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
  const click = async (b) => { await b.trigger('click'); await flushPromises() }

  it('verifica A trattenuta, ricerca B mostrata (DISABILITATO), poi A (ATTIVO): messaggio e righe coerenti con B', async () => {
    await mountApp()
    const r5 = () => wrapper.get('[data-gestionale][data-id="5"]')
    await click(bottoneIn(r5(), 'Disabilita accesso'))
    net.on(STATO(5), jsonResponse(502, { error: 'x' }))
    await click(bottoneIn(r5(), 'Disabilita accesso'))
    const h = net.trattieni(qId(5))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    net.on(RICERCA('idUtente=5&limite=20'), jsonResponse(200, pagina([amministrativo(5, { statoAccesso: 'DISABILITATO' })])))
    await wrapper.get('#ricerca-id').setValue('5')
    await wrapper.findAll('form').at(0).trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-risultato][data-id="5"] [data-campo="stato"]').text()).toBe('Disabilitato')
    net.on(GESTIONALI, jsonResponse(200, [account(1), account(5, { statoAccesso: 'DISABILITATO' })]))
      .on(RICERCA('idUtente=5&limite=20'), jsonResponse(200, pagina([amministrativo(5, { statoAccesso: 'DISABILITATO' })])))
    h.resolve(jsonResponse(200, pagina([amministrativo(5)])))
    await flushPromises()
    const testo = wrapper.get('[data-avviso-admin]').text()
    expect(testo).toContain('è «Disabilitato», come richiesto')
    expect(testo).not.toContain('come prima del tentativo')
    expect(wrapper.find('[data-esito-incerto]').exists()).toBe(false)
    expect(net.callsTo(STATO(5))).toHaveLength(1)
  })
})
