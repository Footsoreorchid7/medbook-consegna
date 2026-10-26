import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { deskStore } from '../src/desk/store.js'
import { parseStoricoRimborsoDesk, parseRimborsoDesk, fetchStoricoRimborsiDesk } from '../src/api/desk.js'
import { desk, rimborsoDesk, decisione, RIMBORSI, DECIDI } from './b7-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })
const storico = stato => `GET /api/rimborsi/desk?stato=${stato}`
const deciso = (stato = 'APPROVATO') => rimborsoDesk(5, { stato, idOperatore: 3, nomeOperatore: 'Dario', cognomeOperatore: 'Sintetico', verificatoIl: '2026-10-20T10:00:00' })
async function monta(path = '/desk/rimborsi?stato=RICHIESTO') {
  net.on('GET /api/auth/me', meOk(desk()))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push(path); await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { session, router, store: deskStore(session) }
}
const card = () => wrapper.get('[data-rimborso-desk]')
const bottone = (w, testo) => w.findAll('button').find(b => b.text().startsWith(testo))
async function clic(w, testo) { await bottone(w, testo).trigger('click'); await flushPromises() }
async function filtro(stato) { await wrapper.get('#filtro-rimborsi-desk').setValue(stato); await flushPromises() }

it.each(['RICHIESTO', 'APPROVATO', 'RESPINTO', 'IN_ELABORAZIONE', 'RIMBORSATO', 'FALLITO'])('parser storico accetta %s senza inventare metadati mancanti', stato => {
  const r = parseStoricoRimborsoDesk(rimborsoDesk(5, { stato }))
  expect(r.stato).toBe(stato); expect(r.operatore).toBeNull(); expect(r.verificato).toBeNull()
})
it.each([
  { stato: 'INVENTATO' }, { idOperatore: null }, { verificatoIl: null },
  { idOperatore: -1, verificatoIl: '2026-10-20T10:00:00' }, { verificatoIl: 'ieri' },
  { nomeOperatore: 'Nome senza ID', idOperatore: null, verificatoIl: null }, { stato: 'RICHIESTO' },
])('parser rifiuta storico incoerente %j', extra => {
  expect(() => parseStoricoRimborsoDesk({ ...deciso(), ...extra })).toThrow()
})
it('filtri invalidi non inviano fetch e la lista rifiuta duplicati', async () => {
  const fn = vi.fn()
  await expect(fetchStoricoRimborsiDesk(fn, 'BOH')).rejects.toThrow(); expect(fn).not.toHaveBeenCalled()
  await expect(fetchStoricoRimborsiDesk(async () => ({ status: 200, hasBody: true, data: [deciso(), deciso()] }), 'TUTTI')).rejects.toThrow()
})
it('parte dai pendenti; filtri riletti dal server, date e operatore visibili, decisioni solo pendenti', async () => {
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
  await monta(); expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('RICHIESTO')
  expect(bottone(card(), 'Approva')).toBeTruthy()
  for (const stato of ['APPROVATO', 'RESPINTO', 'TUTTI']) {
    net.on(storico(stato), jsonResponse(200, [deciso(stato === 'RESPINTO' ? stato : 'APPROVATO')]))
    await filtro(stato)
    expect(card().text()).toContain('Rimborso n. 5'); expect(card().text()).toContain('Dario Sintetico')
    expect(card().text()).toContain('20/10/2026'); expect(card().text()).toContain('n. 25')
    expect(bottone(card(), 'Approva')).toBeUndefined(); expect(bottone(card(), 'Respingi')).toBeUndefined()
    if (stato !== 'RESPINTO') expect(card().text()).toContain('non significa che il denaro sia già stato restituito')
  }
  expect(net.calls.filter(c => c.method === 'POST')).toHaveLength(0)
})
it('storico resta consultabile dopo nuova istanza senza cache locale', async () => {
  net.on(RIMBORSI, jsonResponse(200, []), jsonResponse(200, []))
  net.on(storico('APPROVATO'), jsonResponse(200, [deciso()]), jsonResponse(200, [deciso()]))
  await monta(); await filtro('APPROVATO'); expect(card().text()).toContain('Approvato')
  wrapper.unmount(); wrapper = null
  await monta(); await filtro('APPROVATO'); expect(card().text()).toContain('Approvato')
  expect(net.callsTo(storico('APPROVATO'))).toHaveLength(2)
})
it.each(['approva', 'respingi'])('dopo %s mantiene card aggiornata fuori filtro, senza nuove azioni; aggiornamento la rimuove', async tipo => {
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]), jsonResponse(200, []), jsonResponse(200, []))
  net.on(DECIDI(5, tipo), jsonResponse(200, decisione(5, tipo === 'approva' ? 'APPROVATO' : 'RESPINTO')))
  await monta(); const testo = tipo === 'approva' ? 'Approva' : 'Respingi'
  await clic(card(), testo); expect(net.callsTo(DECIDI(5, tipo))).toHaveLength(0)
  await clic(card().get('[data-conferma]'), testo)
  expect(card().text()).toContain('non corrisponde più al filtro'); expect(card().text()).toContain('Operatore n. 3')
  expect(bottone(card(), testo)).toBeUndefined(); expect(net.callsTo(DECIDI(5, tipo))).toHaveLength(1)
  await clic(wrapper, "Aggiorna l'elenco")
  expect(wrapper.find('[data-rimborso-desk]').exists()).toBe(false)
})
it('errore nella rilettura non perde decisione certa né abilita azioni', async () => {
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]), jsonResponse(500, { error: 'errore' }), jsonResponse(200, []))
  net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO')))
  await monta(); await clic(card(), 'Approva'); await clic(card().get('[data-conferma]'), 'Approva')
  expect(card().text()).toContain('Approvato'); expect(bottone(card(), 'Approva')).toBeUndefined()
  await clic(wrapper, 'Riprova'); expect(wrapper.find('[data-rimborso-desk]').exists()).toBe(false)
})
it('cambio filtro invalida conferma e risposte fuori ordine A → B → A', async () => {
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
  await monta(); await clic(card(), 'Approva')
  const a = net.trattieni(storico('APPROVATO')); const b = net.trattieni(storico('RESPINTO'))
  net.on(storico('APPROVATO'), jsonResponse(200, [deciso()]))
  await filtro('APPROVATO'); expect(wrapper.find('[data-conferma]').exists()).toBe(false)
  await filtro('RESPINTO'); await filtro('APPROVATO')
  b.resolve(jsonResponse(200, [deciso('RESPINTO')])); a.resolve(jsonResponse(200, [])); await flushPromises()
  expect(card().text()).toContain('Approvato'); expect(wrapper.findAll('[data-rimborso-desk]')).toHaveLength(1)
  expect(net.calls.filter(c => c.method === 'POST')).toHaveLength(0)
})
it('barriera condivisa invalida storico già mostrato, ma non quello letto dopo; deciso rifiutato prima di fetch', async () => {
  net.on(RIMBORSI, jsonResponse(200, [])); const { store } = await monta()
  const prima = store.inizioLettura()
  expect(store.applica('rimborsi-storico:TUTTI', [], prima, true)).toBe(true)
  net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO')))
  expect((await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5)))).kind).toBe('eseguita')
  expect(store.valida('rimborsi-storico:TUTTI', prima)).toBe(false)
  const dopo = store.inizioLettura(); expect(store.applica('rimborsi-storico:TUTTI', [], dopo, true)).toBe(true)
  expect(store.valida('rimborsi-storico:TUTTI', dopo)).toBe(true)
  expect((await store.decidiRimborso('respingi', parseStoricoRimborsoDesk(deciso()))).kind).toBe('non-pendente')
  expect(net.callsTo(DECIDI(5, 'respingi'))).toHaveLength(0)
})

it('risposta incompatibile col filtro non diventa elenco pronto e offre recupero', async () => {
  net.on(RIMBORSI, jsonResponse(200, []))
  net.on(storico('RESPINTO'), jsonResponse(200, [deciso()]), jsonResponse(200, [deciso('RESPINTO')]))
  await monta(); await filtro('RESPINTO')
  expect(wrapper.find('[data-rimborso-desk]').exists()).toBe(false)
  await clic(wrapper, 'Riprova'); expect(card().text()).toContain('Respinto')
})
it('decisione tardiva dopo cambio filtro resta nello store, senza card o messaggi del vecchio contesto', async () => {
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
  const { store } = await monta(); await clic(card(), 'Approva')
  const post = net.trattieni(DECIDI(5, 'approva'))
  await clic(card().get('[data-conferma]'), 'Approva')
  net.on(storico('RESPINTO'), jsonResponse(200, [])); await filtro('RESPINTO')
  post.resolve(jsonResponse(200, decisione(5, 'APPROVATO'))); await flushPromises()
  expect(wrapper.find('[data-rimborso-desk]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('Decisione confermata in questa vista')
  expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
  expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
  expect(net.callsTo(DECIDI(5, 'approva'))).toHaveLength(1)
})


it('menu apre Tutti, dashboard esplicita i pendenti e navigazione nella stessa istanza riapplica il filtro', async () => {
  net.on(storico('TUTTI'), jsonResponse(200, [deciso()]), jsonResponse(200, [deciso()]))
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(7)]))
  const { router } = await monta('/desk/rimborsi')
  expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('TUTTI')
  expect(card().text()).toContain('Approvato')
  await router.push('/desk/rimborsi?stato=RICHIESTO'); await flushPromises()
  expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('RICHIESTO')
  expect(card().text()).toContain('Rimborso n. 7')
  await router.push('/desk/rimborsi'); await flushPromises()
  expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('TUTTI')
  expect(card().text()).toContain('Approvato')
  expect(net.calls.filter(c => c.method === 'POST')).toHaveLength(0)
})
it.each(['SCONOSCIUTO', ['APPROVATO', 'RESPINTO']])('parametro non valido %j ripiega su Tutti', async stato => {
  net.on(storico('TUTTI'), jsonResponse(200, [deciso()]))
  await monta({ path: '/desk/rimborsi', query: { stato } })
  expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('TUTTI')
  expect(card().text()).toContain('Approvato')
})

it('il menu riapre Tutti anche dopo una scelta manuale sulla stessa pagina', async () => {
  net.on(storico('TUTTI'), jsonResponse(200, [deciso()]), jsonResponse(200, [deciso()]))
  net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(7)]))
  const { router } = await monta('/desk/rimborsi')
  await filtro('RICHIESTO')
  expect(router.currentRoute.value.query.stato).toBe('RICHIESTO')
  expect(card().text()).toContain('Rimborso n. 7')
  await router.push('/desk/rimborsi'); await flushPromises()
  expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('TUTTI')
  expect(card().text()).toContain('Approvato')
})
