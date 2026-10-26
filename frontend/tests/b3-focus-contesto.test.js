import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import PrenotaView from '../src/views/PrenotaView.vue'
import PrenotazioneDettaglioView from '../src/views/PrenotazioneDettaglioView.vue'
import { appuntamento } from './b3-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// un cambio di query o di ID durante l'attesa del DOM non deve spostare il focus nel
// nuovo contesto. La rotta reattiva permette di cambiarla in modo sincrono, nello stesso
// ciclo di aggiornamento in cui il ripristino del focus è in attesa.
const rotta = reactive({ name: 'prenota', params: {}, query: {}, fullPath: '/' })
vi.mock('vue-router', async (originale) => ({
  ...(await originale()),
  useRoute: () => rotta,
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}))

const ME = 'GET /api/auth/me'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const slot = (id) => ({
  idDisponibilita: id, dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idMedico: 4, nomeMedico: 'Elena',
  cognomeMedico: 'Sintetica', idPrestazione: 1, nomePrestazione: 'Visita cardiologica', durataPrestazione: 30, prezzoPrestazione: 80,
})

let net
let wrapper

beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function monta(Componente) {
  net.on(ME, meOk(utente()))
  const session = createSession()
  await session.ensureBootstrap()
  wrapper = mount(Componente, {
    global: { provide: { [SESSION_KEY]: session }, stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    attachTo: document.body,
  })
  await flushPromises()
  return session
}

const bottone = (testo) => wrapper.findAll('button').find((b) => b.text() === testo)

describe('D1: cambio di contesto durante il ripristino del focus', () => {
  it('prenotazione: cambio di query dopo «Modifica la scelta» → nessun focus nel nuovo contesto', async () => {
    Object.assign(rotta, { name: 'prenota', params: {}, query: { slot: '10' } })
    net.on(CAT, jsonResponse(200, [])).on(DISP, jsonResponse(200, [slot(10), slot(11)]))
    await monta(PrenotaView)
    const uid = wrapper.vm.$.uid
      await wrapper.get('form:not(.filters)').trigger('submit')
    await flushPromises()
    expect(document.activeElement.id).toBe('titolo-conferma')
    const ricerca = net.trattieni(DISP)
    // Il gestore si ferma su nextTick; il cambio di query avviene prima dell'aggiornamento del DOM.
    bottone('Modifica la scelta').trigger('click')
    rotta.query = { slot: '11' }
    await flushPromises()
    expect(wrapper.vm.$.uid).toBe(uid)
    // Nel nuovo contesto il titolo degli orari esiste ed è focalizzabile, ma non riceve il focus.
    expect(wrapper.find('#titolo-orari').exists()).toBe(true)
    expect(document.activeElement.id).not.toBe('titolo-orari')
    expect(document.activeElement.textContent?.trim()).not.toBe('Continua')
    ricerca.resolve(jsonResponse(200, [slot(10), slot(11)]))
    await flushPromises()
    expect(wrapper.get('input[type="radio"][value="11"]').element.checked).toBe(true)
    expect(document.activeElement.textContent?.trim()).not.toBe('Continua')
    expect(document.activeElement.id).not.toBe('titolo-orari')
  })

  it('dettaglio: cambio di ID dopo «Non annullare» → nessun focus nella scheda della nuova prenotazione', async () => {
    Object.assign(rotta, { name: 'prenotazione-dettaglio', params: { id: '12' }, query: {} })
    net.on(LISTA, jsonResponse(200, [appuntamento(12), appuntamento(13, { nomePrestazione: 'Ecografia' })]))
    await monta(PrenotazioneDettaglioView)
    const uid = wrapper.vm.$.uid
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    expect(document.activeElement.id).toBe('titolo-conferma-annullamento')
    net.on(LISTA, jsonResponse(200, [appuntamento(12), appuntamento(13, { nomePrestazione: 'Ecografia' })]))
    bottone('Non annullare').trigger('click')
    rotta.params = { id: '13' }
    await flushPromises()
    expect(wrapper.vm.$.uid).toBe(uid)
    expect(wrapper.get('#titolo-prenotazione').text()).toBe('Ecografia')
    expect(document.activeElement.textContent?.trim()).not.toBe('Annulla prenotazione')
    expect(['titolo-annullamento', 'titolo-prenotazione']).not.toContain(document.activeElement.id)
  })
})
