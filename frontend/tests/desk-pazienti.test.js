import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { adminStore } from '../src/admin/store.js'
import { fetchPazienti, putPasswordPaziente } from '../src/api/pazientiDesk.js'
import { ProtocolError } from '../src/api/http.js'
import { utente, installFetch, routedFetch, meOk, jsonResponse } from './helpers.js'
const desk = () => utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' })
const paziente = (id = 15, extra = {}) => ({ idPaziente: id, nome: 'Paziente', cognome: 'Sintetico', email: `p${id}@example.test`, medico: false, gestionale: false, statoAccesso: 'ATTIVO', ...extra })
const pagina = (rows = [paziente()], cursor = null) => jsonResponse(200, { risultati: rows, prossimoDopoId: cursor })
const PUT = 'PUT /api/pazienti/15/password', GET = 'GET /api/pazienti'
const PASSWORD = ' password fittizia '
let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.restoreAllMocks() })
async function avvia(user = desk()) {
  net.on('GET /api/auth/me', meOk(user))
  const session = createSession(), router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push('/desk/pazienti'); await router.isReady()
  wrapper = mount(App, { attachTo: document.body, global: { plugins: [router], provide: { [SESSION_KEY]: session } } })
  await flushPromises()
  return { session, router, store: adminStore(session) }
}
const btn = text => wrapper.findAll('button').find(b => b.text() === text)
async function apri() { await btn('Reimposta password').trigger('click'); await flushPromises() }
async function prepara() {
  await apri(); await wrapper.get('#paziente-password').setValue(PASSWORD); await wrapper.get('#paziente-ripeti').setValue(PASSWORD)
  await wrapper.get('.reset-paziente form').trigger('submit'); await flushPromises()
}
const posts = () => net.calls.filter(x => x.method === 'PUT')

describe('API dedicata e parser', () => {
  it('preserva ordine, null, ID e proiezioni miste senza aggiungere dati clinici', async () => {
    net.on(GET, pagina([paziente(2), paziente(3, { medico: true })], 3))
    const s = createSession(); net.on('GET /api/auth/me', meOk(desk())); await s.ensureBootstrap()
    expect((await fetchPazienti(s.protectedRequest)).prossimoDopoId).toBe(3)
    for (const rows of [[paziente(3), paziente(2)], [paziente(), paziente()], [paziente(1, { medico: null })]]) {
      net.on(GET, pagina(rows)); await expect(fetchPazienti(s.protectedRequest)).rejects.toBeInstanceOf(ProtocolError)
    }
  })
  it.each([202, 204, 200])('risposta reset non conforme (%s) non dichiara successo', async status => {
    const r = status === 204 ? new Response(null, { status }) : jsonResponse(status, { idPaziente: 15, message: '', sessioniInvalidate: true })
    net.on(PUT, r)
    const s = createSession(); net.on('GET /api/auth/me', meOk(desk())); await s.ensureBootstrap()
    await expect(putPasswordPaziente(s.protectedRequest, 15, PASSWORD)).rejects.toBeInstanceOf(ProtocolError)
  })
})

describe('pagina pazienti desk', () => {
  it('esclude admin, medico e paziente senza interrogare elenco o reset', async () => {
    for (const user of [utente(), utente({ medico: true }), utente({ ruoloGestionale: 'AMMINISTRATORE' })]) {
      const { router } = await avvia(user)
      expect(router.currentRoute.value.name).toBe('accesso-negato'); wrapper.unmount(); wrapper = null
    }
    expect(net.calls.some(x => x.url.startsWith('/api/pazienti'))).toBe(false)
  })
  it('elenco iniziale, paginazione e ricerca con nuovi filtri senza fusione', async () => {
    net.on(GET, pagina([paziente()], 15)).on(GET + '?dopoId=15', pagina([paziente(16)]))
    await avvia(); await btn('Carica altri pazienti').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('Paziente n. 16')
    await wrapper.get('#pazienti-nome').setValue('Nina')
    expect(btn('Reimposta password').attributes('disabled')).toBeDefined()
    net.on(GET + '?nome=Nina', pagina([paziente(20, { nome: 'Nina' })]))
    await wrapper.get('.ricerca-pazienti').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Paziente n. 20'); expect(wrapper.text()).not.toContain('Paziente n. 16')
  })
  it('una pagina trattenuta non si aggiunge dopo modifica filtri', async () => {
    net.on(GET, pagina([paziente()], 15)); const pending = net.trattieni(GET + '?dopoId=15')
    await avvia(); await btn('Carica altri pazienti').trigger('click')
    await wrapper.get('#pazienti-cognome').setValue('Altro')
    pending.resolve(pagina([paziente(16)])); await flushPromises()
    expect(wrapper.text()).not.toContain('Paziente n. 16'); expect(btn('Reimposta password').attributes('disabled')).toBeDefined()
  })
  it('nessun modulo reset per account misto o gestionale', async () => {
    net.on(GET, pagina([paziente(15, { medico: true }), paziente(16, { gestionale: true })])); await avvia()
    expect(btn('Reimposta password')).toBeUndefined(); expect(wrapper.text()).toContain('riservato all’amministratore')
    expect(posts()).toHaveLength(0)
  })
  it('focus, riepilogo senza segreto, conferma esplicita, pulizia immediata e successo', async () => {
    net.on(GET, pagina()); const pending = net.trattieni(PUT)
    await avvia(); await prepara()
    expect(document.activeElement.id).toBe('conferma-reset-paziente'); expect(wrapper.get('.confirm').text()).not.toContain(PASSWORD)
    expect(posts()).toHaveLength(0)
    net.on(GET, pagina()); await btn('Conferma reset password').trigger('click'); await flushPromises()
    expect(wrapper.get('#paziente-password').element.value).toBe(''); expect(wrapper.get('#paziente-ripeti').element.value).toBe('')
    expect(posts()).toHaveLength(1); expect(JSON.parse(posts()[0].body)).toEqual({ nuovaPassword: PASSWORD })
    expect(posts()[0].headers.has('X-CSRF-Token')).toBe(true)
    pending.resolve(jsonResponse(200, { idPaziente: 15, message: 'Completato', sessioniInvalidate: true })); await flushPromises()
    expect(wrapper.text()).toContain('Password di Paziente Sintetico reimpostata'); expect(wrapper.find('#paziente-password').exists()).toBe(false)
  })
  it('chiusura cancella password e restituisce focus; ripetizione errata non invia', async () => {
    net.on(GET, pagina()); await avvia(); await apri()
    await wrapper.get('#paziente-password').setValue(PASSWORD); await wrapper.get('#paziente-ripeti').setValue('diversa password')
    await wrapper.get('.reset-paziente form').trigger('submit'); expect(wrapper.text()).toContain('non coincidono')
    await btn('Chiudi').trigger('click'); await flushPromises(); expect(document.activeElement.textContent).toBe('Reimposta password')
    await apri(); expect(wrapper.get('#paziente-password').element.value).toBe(''); expect(posts()).toHaveLength(0)
  })
  it.each([429, 503])('HTTP %s mantiene incertezza, attesa separata e blocco attraverso navigazione', async status => {
    net.on(GET, pagina()).on(PUT, jsonResponse(status, { error: 'Non eseguito' }, { 'Retry-After': '1' }))
    const { store, router } = await avvia(); await prepara(); await btn('Conferma reset password').trigger('click'); await flushPromises()
    expect(store.bloccata('utente:15')).toBe(true); expect(wrapper.text()).toContain('Esito incerto')
    const prima = net.calls.length
    expect((await store.verifica('utente:15')).kind).toBe('non-verificabile')
    expect(net.calls).toHaveLength(prima)
    expect(JSON.stringify(store.state)).not.toContain(PASSWORD)
    await router.push('/prestazioni'); await flushPromises(); net.on(GET, pagina()); await router.push('/desk/pazienti'); await flushPromises()
    expect(wrapper.find('[data-reset-incerto]').exists()).toBe(true)
    const result = await store.reimpostaPasswordPaziente(paziente(), PASSWORD)
    expect(['attesa', 'bloccata']).toContain(result.kind); expect(posts()).toHaveLength(1)
  })
  it('lettura fallita non mostra falso vuoto né abilita reset; recupero manuale', async () => {
    net.on(GET, jsonResponse(500, { error: 'tecnico' })); await avvia()
    expect(wrapper.text()).not.toContain('Nessun paziente corrisponde'); expect(btn('Reimposta password')).toBeUndefined()
    net.on(GET, pagina()); await btn('Riprova / Aggiorna elenco').trigger('click'); await flushPromises()
    expect(btn('Reimposta password').attributes('disabled')).toBeUndefined()
  })
  it('lettura durante scrittura di altra istanza invalidata al successo, prima del secondo invio', async () => {
    net.on(GET, pagina());const pending = net.trattieni(PUT)
    const { store, router } = await avvia(); const op = store.reimpostaPasswordPaziente(paziente(), PASSWORD)
    await router.push('/prestazioni'); await flushPromises(); net.on(GET, pagina()); await router.push('/desk/pazienti'); await flushPromises()
    expect(btn('Reimposta password').attributes('disabled')).toBeDefined()
    pending.resolve(jsonResponse(200, { idPaziente: 15, message: 'Completato', sessioniInvalidate: true })); await op; await flushPromises()
    expect(btn('Reimposta password').attributes('disabled')).toBeDefined(); expect(wrapper.text()).toContain('azioni sono sospese')
  })
})

describe('sessione, contesto e blocchi condivisi', () => {
  it('pending comune impedisce doppi invii diretti e nuova istanza; nessun segreto nello store', async () => {
    net.on(GET, pagina());const held = net.trattieni(PUT)
    const { store, router } = await avvia();const op = store.reimpostaPasswordPaziente(paziente(), PASSWORD)
    expect((await store.reimpostaPasswordPaziente(paziente(), PASSWORD)).kind).toBe('occupato')
    await router.push('/prestazioni'); await flushPromises();net.on(GET, pagina());await router.push('/desk/pazienti');await flushPromises()
    expect(btn('Reimposta password').attributes('disabled')).toBeDefined();expect(JSON.stringify(store.state)).not.toContain(PASSWORD)
    held.resolve(jsonResponse(400, { error: 'Richiesta non valida' }));await op;expect(posts()).toHaveLength(1)
  })
  it('incertezza persiste dopo rotazione, GET e scadenza; nuovo reset richiede scelta esplicita', async () => {
    net.on(GET, pagina()).on(PUT, jsonResponse(503, { error: 'tecnico' }, { 'Retry-After': '10' }))
    const { store, session } = await avvia()
    expect((await store.reimpostaPasswordPaziente(paziente(), PASSWORD)).kind).toBe('incerto')
    net.on('PUT /api/auth/password', jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': 'B'.repeat(43) })).on(GET, pagina())
    expect((await session.changePassword('vecchia', 'nuova')).kind).toBe('aggiornata');await flushPromises()
    expect(store.bloccata('utente:15')).toBe(true)
    expect(store.attesaResidua(Date.now()+20000)).toBe(0);expect(store.bloccata('utente:15')).toBe(true)
    expect(store.consentiNuovoResetPaziente(15)).toBe(true);expect(store.attesaResidua()).toBeGreaterThan(0)
    expect((await store.reimpostaPasswordPaziente(paziente(), PASSWORD)).kind).toBe('attesa');expect(posts()).toHaveLength(2) // reset + cambio personale
  })
  it('password personale incerta blocca prima di fetch', async () => {
    net.on(GET, pagina()).on('PUT /api/auth/password', 'rete')
    const { session, store } = await avvia();await session.changePassword('vecchia', 'nuova')
    expect((await store.reimpostaPasswordPaziente(paziente(), PASSWORD)).kind).toBe('password-incerta')
    expect(net.callsTo(PUT)).toHaveLength(0)
  })
  it('smontaggio prima del successo conserva esito nello store senza messaggi o riletture tardive', async () => {
    net.on(GET, pagina());const held = net.trattieni(PUT)
    const { store, router } = await avvia();await prepara();await btn('Conferma reset password').trigger('click')
    await router.push('/prestazioni');await flushPromises()
    held.resolve(jsonResponse(200, { idPaziente: 15, message: 'ok', sessioniInvalidate: true }));await flushPromises()
    expect(store.bloccata('utente:15')).toBe(false);expect(net.callsTo(GET)).toHaveLength(1)
    expect(wrapper.text()).not.toContain('Password di Paziente Sintetico reimpostata')
  })
  it('logout/cambio identità impedisce adozione di risposta tardiva e trasferimento di blocchi', async () => {
    net.on(GET, pagina());const held = net.trattieni(PUT)
    const { session, store } = await avvia();const op = store.reimpostaPasswordPaziente(paziente(), PASSWORD)
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }));await session.logout();await flushPromises()
    held.resolve(jsonResponse(503, { error: 'tecnico' }, { 'Retry-After': '100' }));expect((await op).kind).toBe('obsoleto')
    expect(store.state.owner).toBe(null);expect(store.state.incerte).toHaveLength(0);expect(store.attesaResidua()).toBe(0)
  })
  it('risposta GET tardiva che ignora abort non viene adottata dopo nuova ricerca', async () => {
    let resolve
    net.on(GET, () => new Promise(r => { resolve = r }))
    await avvia();await wrapper.get('#pazienti-nome').setValue('Nuovo')
    net.on(GET+'?nome=Nuovo', pagina([paziente(19, { nome: 'Nuovo' })]));await wrapper.get('.ricerca-pazienti').trigger('submit');await flushPromises()
    resolve(pagina([paziente(15)]));await flushPromises()
    expect(wrapper.text()).toContain('Paziente n. 19');expect(wrapper.text()).not.toContain('Paziente n. 15')
  })
})
