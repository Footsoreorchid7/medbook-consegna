import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { disponibilitaStore } from '../src/medico/disponibilita.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { ELENCO, ELIMINA, MEDICO, ORARIO, configurato, medico, slot, vuota } from './b6-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Elenco scartato non adottato; riletture dopo HTTP 400. Dati sintetici.
const ME = 'GET /api/auth/me'
const A = '2026-11-02T09:00:00'
const S8 = '2026-11-02T10:00:00'
const S9 = '2026-11-02T11:00:00'

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function apri(voci) {
  net.on(ME, meOk(medico()))
  net.on(ELENCO, jsonResponse(200, voci))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push('/medico/disponibilita?vista=elenco')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store: disponibilitaStore(session) }
}
const ids = () => wrapper.findAll('[data-disponibilita]').map((w) => w.attributes('data-id'))
const riga = (id) => wrapper.get(`[data-disponibilita][data-id="${id}"]`)
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const scritture = () => net.calls.filter((c) => ['POST', 'PUT', 'DELETE'].includes(c.method) && !c.url.startsWith('/api/auth')).length
const avviso = () => wrapper.get('[data-avviso-disponibilita]').text()
/** Uscita e rientro con la nuova lettura trattenuta. */
async function esciERientra(router) {
  await router.push('/area')
  await flushPromises()
  const h = net.trattieni(ELENCO)
  await router.push('/medico/disponibilita?vista=elenco')
  await flushPromises()
  return h
}
/** Esito certo di un'altra istanza (stessa sessione): barriera per le letture avviate prima. */
async function eliminazioneCerta(store, id, inizio) {
  net.on(ELIMINA(id), vuota())
  expect((await store.elimina(parseDisponibilita(slot(id, inizio), MEDICO))).kind).toBe('eliminata')
}

describe('1. lettura scartata dallo store', () => {
  it('eliminazione pendente → uscita/rientro → GET avviato → eliminazione certa → vecchio GET: slot non riabilitato', async () => {
    const { router } = await apri([slot(7, A), slot(8, S8)])
    await click(bottoneIn(riga(7), 'Elimina'))
    const del = net.trattieni(ELIMINA(7))
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    const get = await esciERientra(router)
    del.resolve(vuota())
    await flushPromises()
    const n = scritture()
    get.resolve(jsonResponse(200, [slot(7, A), slot(8, S8)]))
    await flushPromises()
    expect(ids()).toEqual([])
    expect(wrapper.get('[data-elenco-superato]').text()).toContain('superato da operazioni più recenti')
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(false)
    expect(wrapper.get('main form button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(scritture()).toBe(n)
    // Recupero manuale: la nuova lettura è applicata.
    net.on(ELENCO, jsonResponse(200, [slot(8, S8)]))
    await click(bottoneIn(wrapper.get('[data-elenco-superato]'), "Aggiorna l'elenco"))
    expect(ids()).toEqual(['8'])
    expect(bottoneIn(riga(8), 'Elimina').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
  })

  it('stesso scenario con modifica certa: l\'orario precedente non viene adottato', async () => {
    const { router } = await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await wrapper.get('#orario-7').setValue('2026-11-03T08:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    const put = net.trattieni(ORARIO(7))
    await click(bottoneIn(riga(7), 'Conferma nuovo orario'))
    const get = await esciERientra(router)
    put.resolve(jsonResponse(200, slot(7, '2026-11-03T08:00:00')))
    await flushPromises()
    get.resolve(jsonResponse(200, [slot(7, A)]))
    await flushPromises()
    expect(ids()).toEqual([])
    expect(wrapper.find('[data-elenco-superato]').exists()).toBe(true)
    expect(net.callsTo(ORARIO(7))).toHaveLength(1)
    net.on(ELENCO, jsonResponse(200, [slot(7, '2026-11-03T08:00:00')]))
    await click(bottoneIn(wrapper.get('[data-elenco-superato]'), "Aggiorna l'elenco"))
    expect(riga(7).get('[data-campo="quando"]').text()).toBe('martedì 03/11/2026, 08:00')
  })

  it('con dati precedenti: restano visibili, non aggiornati e senza azioni; l\'aggiornamento manuale riabilita solo le azioni lecite', async () => {
    const { store } = await apri([slot(7, A), configurato(8, S8), slot(9, S9)])
    // Rilettura dopo un 409 sulla 7, trattenuta; nel frattempo un esito certo di un'altra istanza.
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), jsonResponse(409, { error: 'Disponibilità con prenotazioni associate.' }))
    const get = net.trattieni(ELENCO)
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    await eliminazioneCerta(store, 9, S9)
    get.resolve(jsonResponse(200, [slot(7, A), configurato(8, S8), slot(9, S9)]))
    await flushPromises()
    expect(ids()).toEqual(['7', '8', '9'])
    expect(wrapper.get('[data-elenco-superato]').text()).toContain('non è aggiornato')
    expect(bottoneIn(riga(9), 'Elimina').attributes('disabled')).toBeDefined()
    expect(bottoneIn(riga(7), 'Modifica orario').attributes('disabled')).toBeDefined()
    await click(bottoneIn(riga(7), 'Modifica orario'))
    expect(wrapper.find('[data-modulo-orario]').exists()).toBe(false)
    net.on(ELENCO, jsonResponse(200, [slot(7, A), configurato(8, S8)]))
    await click(bottoneIn(wrapper.get('[data-elenco-superato]'), "Aggiorna l'elenco"))
    expect(wrapper.find('[data-elenco-superato]').exists()).toBe(false)
    expect(ids()).toEqual(['7', '8'])
    expect(bottoneIn(riga(7), 'Elimina').attributes('disabled')).toBeUndefined()
    expect(bottoneIn(riga(8), 'Elimina')).toBeUndefined()
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
  })

  it('cambio di contesto: una lettura valida del contesto precedente non viene adottata', async () => {
    const { session } = await apri([slot(7, A)])
    const vecchia = net.trattieni(ELENCO)
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), jsonResponse(409, { error: 'x' }))
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    net.on('PUT /api/auth/password', jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': 'B'.repeat(43) }))
    net.on(ELENCO, jsonResponse(200, [slot(8, S8)]))
    await session.changePassword('a', 'b')
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [slot(7, A)]))
    await flushPromises()
    expect(ids()).toEqual(['8'])
    expect(wrapper.find('[data-elenco-superato]').exists()).toBe(false)
  })
})

describe('2. HTTP 400 su modifica ed eliminazione', () => {
  it('PUT 400: una sola rilettura, nessuna ripetizione, messaggio senza falsa conferma', async () => {
    await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await wrapper.get('#orario-7').setValue('2026-11-03T08:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    net.on(ORARIO(7), jsonResponse(400, { error: 'Richiesta non valida.' })).on(ELENCO, jsonResponse(200, [slot(7, A)]))
    await click(bottoneIn(riga(7), 'Conferma nuovo orario'))
    expect(net.callsTo(ELENCO)).toHaveLength(2)
    expect(net.callsTo(ORARIO(7))).toHaveLength(1)
    expect(avviso()).toContain('Richiesta non valida.')
    expect(avviso()).toContain("L'elenco viene riletto")
    expect(avviso()).not.toContain('è stato riletto')
  })

  it('DELETE 400: una sola rilettura e nessuna ripetizione', async () => {
    await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), jsonResponse(400, { error: 'Richiesta non valida.' })).on(ELENCO, jsonResponse(200, [slot(7, A)]))
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    expect(net.callsTo(ELENCO)).toHaveLength(2)
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
    expect(bottoneIn(riga(7), 'Elimina').attributes('disabled')).toBeUndefined()
  })

  it('rilettura dopo un 400 fallita: errore con Riprova, nessuna conferma di aggiornamento', async () => {
    await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), jsonResponse(400, { error: 'Richiesta non valida.' })).on(ELENCO, 'rete')
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    expect(wrapper.text()).toContain('Non è stato possibile caricare le disponibilità')
    expect(bottoneIn(wrapper.get('main'), 'Riprova')).toBeDefined()
    expect(avviso()).not.toContain('è stato riletto')
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
  })

  it('rilettura dopo un 400 scartata: dati non aggiornati e recupero manuale', async () => {
    const { store } = await apri([slot(7, A), slot(9, S9)])
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), jsonResponse(400, { error: 'Richiesta non valida.' }))
    const get = net.trattieni(ELENCO)
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    await eliminazioneCerta(store, 9, S9)
    get.resolve(jsonResponse(200, [slot(7, A), slot(9, S9)]))
    await flushPromises()
    expect(wrapper.get('[data-elenco-superato]').text()).toContain('non è aggiornato')
    expect(avviso()).not.toContain('è stato riletto')
    expect(bottoneIn(riga(7), 'Elimina').attributes('disabled')).toBeDefined()
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
  })
})
