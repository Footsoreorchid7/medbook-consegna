import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import CalendarioAgenda from '../src/components/CalendarioAgenda.vue'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { createAppRouter } from '../src/router/index.js'
import { ELENCO, ELIMINA, configurato, medico, slot, vuota } from './b6-fixtures.js'
import { CSRF_B, installFetch, routedFetch, jsonResponse, meOk } from './helpers.js'
let wrapper, net
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 24, 12))
  net = installFetch(routedFetch())
})
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers() })
const dati = () => [slot(7, '2026-10-25T09:00:30'), configurato(4, '2026-10-25T10:00:00'), slot(9, '2026-11-01T08:00:00')]
async function apri(voci) {
  net.on('GET /api/auth/me', meOk(medico()))
  if (voci) net.on(ELENCO, jsonResponse(200, voci))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push('/medico/disponibilita')
  await router.isReady()
  wrapper = mount(App, { attachTo: document.body, global: { plugins: [router], provide: { [SESSION_KEY]: session } } })
  await flushPromises()
  return { session, router }
}
const click = async b => { await b.trigger('click'); await flushPromises() }
const giorno = data => wrapper.get(`[data-giorno="${data}"]`)
const ids = () => wrapper.findAll('[data-disponibilita]').map(x => Number(x.attributes('data-id')))
const avanti = () => wrapper.get('[aria-label="Mese successivo"]')
const indietro = () => wrapper.get('[aria-label="Mese precedente"]')
const azione = (id, testo) => wrapper.get(`[data-disponibilita][data-id="${id}"]`).findAll('button').find(x => x.text().startsWith(testo))
const scritture = () => net.calls.filter(x => !['GET', 'HEAD'].includes(x.method) && !x.url.startsWith('/api/auth')).length

it('mese corrente, conteggi completi senza stati inventati; giorno e alternativa elenco mantengono ordine e azioni', async () => {
  await apri(dati())
  expect(wrapper.get('#mese-disponibilita-input').element.value).toBe('2026-10')
  expect(ids()).toEqual([])
  expect(giorno('2026-10-25').attributes('aria-label')).toContain('2 disponibilità')
  expect(wrapper.find('.calendario .legenda').exists()).toBe(false)
  await click(giorno('2026-10-25'))
  expect(ids()).toEqual([7, 4])
  expect(azione(7, 'Modifica orario')).toBeDefined()
  expect(azione(4, 'Modifica orario')).toBeUndefined()
  expect(wrapper.get('[data-id="7"] [data-campo="quando"]').text()).toContain('09:00:30')
  await click(wrapper.get('[data-vista="elenco"]'))
  expect(ids()).toEqual([7, 4, 9])
  expect(wrapper.find('.calendario').exists()).toBe(false)
  expect(net.callsTo(ELENCO)).toHaveLength(1)
  expect(scritture()).toBe(0)
})

it('giorno vuoto selezionabile, aggiunta precompila solo data e sposta focus al campo ora; cambio mese azzera la selezione', async () => {
  await apri(dati())
  await click(giorno('2026-10-25'))
  await click(giorno('2026-10-31'))
  expect(ids()).toEqual([])
  expect(wrapper.get('[data-giorno-selezionato]').text()).toContain('Nessuna disponibilità')
  await click(wrapper.get('[data-giorno-selezionato] button'))
  expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('2026-10-31')
  expect(wrapper.get('#disponibilita-data-fine').element.value).toBe('2026-10-31')
  expect(wrapper.get('#disponibilita-inizio').element.value).toBe('')
  expect(document.activeElement).toBe(wrapper.get('#disponibilita-inizio').element)
  await click(avanti())
  expect(wrapper.find('[data-giorno-selezionato]').exists()).toBe(false)
  expect(giorno('2026-11-01').attributes('aria-label')).toContain('1 disponibilità')
  await click(giorno('2026-11-01'))
  expect(ids()).toEqual([9])
  expect(net.callsTo(ELENCO)).toHaveLength(1)
  expect(scritture()).toBe(0)
})

it('cambio mese A → B → A chiude la conferma precedente e non la riattiva', async () => {
  await apri(dati())
  await click(giorno('2026-10-25'))
  await click(azione(7, 'Elimina'))
  expect(wrapper.find('[data-conferma="elimina"]').exists()).toBe(true)
  await click(avanti())
  await click(indietro())
  await click(giorno('2026-10-25'))
  expect(wrapper.find('[data-conferma]').exists()).toBe(false)
  expect(azione(7, 'Elimina').element.disabled).toBe(false)
  expect(scritture()).toBe(0)
})

it('scrittura pendente: navigazione locale sospesa; dopo risposta certa rilettura prima delle nuove azioni', async () => {
  await apri(dati())
  await click(giorno('2026-10-25'))
  await click(azione(7, 'Elimina'))
  const invio = net.trattieni(ELIMINA(7))
  await click(azione(7, 'Elimina disponibilità'))
  expect(avanti().element.disabled).toBe(true)
  expect(wrapper.get('[data-vista="elenco"]').element.disabled).toBe(true)
  const rilettura = net.trattieni(ELENCO)
  invio.resolve(vuota())
  await flushPromises()
  expect(giorno('2026-10-25').element.disabled).toBe(true)
  rilettura.resolve(jsonResponse(200, [configurato(4, '2026-10-25T10:00:00')]))
  await flushPromises()
  expect(ids()).toEqual([4])
  expect(giorno('2026-10-25').attributes('aria-label')).toContain('1 disponibilità')
  expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
})

it('lettura vecchia trattenuta e cambio mese: risposta superata ignorata senza loading sospeso', async () => {
  const vecchia = net.trattieni(ELENCO)
  await apri()
  net.on(ELENCO, jsonResponse(200, [slot(9, '2026-11-01T08:00:00')]))
  await click(avanti())
  await click(giorno('2026-11-01'))
  expect(ids()).toEqual([9])
  vecchia.resolve(jsonResponse(200, dati()))
  await flushPromises()
  expect(ids()).toEqual([9])
  expect(wrapper.get('#mese-disponibilita-input').element.value).toBe('2026-11')
  expect(giorno('2026-11-01').element.disabled).toBe(false)
  expect(net.callsTo(ELENCO)).toHaveLength(2)
  expect(scritture()).toBe(0)
})

it('errore leggibile e recupero manuale; epoca nuova rimuove selezione e dati privati precedenti', async () => {
  net.on(ELENCO, jsonResponse(500, { error: 'Errore sintetico' }))
  const { session } = await apri()
  expect(wrapper.get('main [role="alert"]').text()).toContain('Non è stato possibile caricare')
  expect(giorno('2026-10-25').attributes('aria-label')).toContain('dati non aggiornati')
  net.on(ELENCO, jsonResponse(200, dati()))
  await click(wrapper.findAll('button').find(x => x.text()==='Riprova'))
  await click(giorno('2026-10-25'))
  const nuova = net.trattieni(ELENCO)
  net.on('PUT /api/auth/password', jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
  expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
  await flushPromises()
  expect(ids()).toEqual([])
  expect(wrapper.find('[data-giorno-selezionato]').exists()).toBe(false)
  nuova.resolve(jsonResponse(200, []))
  await flushPromises()
  await click(giorno('2026-10-25'))
  expect(wrapper.get('[data-giorno-selezionato]').text()).toContain('Nessuna disponibilità')
  expect(scritture()).toBe(0)
})

it('componente condiviso: conteggio disponibilità, nome completo del giorno e tastiera senza legenda appuntamenti', async () => {
  wrapper = mount(CalendarioAgenda, { props: { tipo: 'disponibilita', mese: '2026-10', aggiornato: true, voci: [{ inizio: { date: '2026-10-25' } }] }, attachTo: document.body })
  expect(giorno('2026-10-25').attributes('aria-label')).toContain('domenica 25/10/2026, 1 disponibilità')
  expect(giorno('2026-10-25').text()).toBe('251')
  expect(wrapper.text()).not.toContain('Richiesta significa')
  await giorno('2026-10-25').trigger('keydown', { key: 'ArrowRight' })
  expect(document.activeElement).toBe(giorno('2026-10-26').element)
  await click(giorno('2026-10-26'))
  expect(wrapper.emitted('giorno')).toEqual([['2026-10-26']])
})
