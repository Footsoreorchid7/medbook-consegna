import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { appuntamento } from './b3-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Verifica ripristino del focus e avviso della preselezione.
// Dati sintetici: nessuna identità reale.
const ME = 'GET /api/auth/me'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'
const CREA = 'POST /api/prenotazioni'
const PWD = 'PUT /api/auth/password'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'

const PRESTAZIONI = [{ idPrestazione: 1, nome: 'Visita cardiologica', descrizione: null, durata: 30, prezzo: 80 }]
const slot = (id, extra = {}) => ({
  idDisponibilita: id, dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idMedico: 4, nomeMedico: 'Elena',
  cognomeMedico: 'Sintetica', idPrestazione: 1, nomePrestazione: 'Visita cardiologica', durataPrestazione: 30, prezzoPrestazione: 80, ...extra,
})
const SLOTS = [slot(10), slot(11, { nomeMedico: 'Marco', cognomeMedico: 'Prova' })]
const creata = (extra = {}) => ({
  idPrenotazione: 50, stato: 'RICHIESTA', dataOraRichiesta: '2026-10-15T20:10:11', prezzo: 80, idDisponibilita: 10,
  dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idPrestazione: 1, nomePrestazione: 'Visita cardiologica',
  idMedico: 4, nomeMedico: 'Elena', cognomeMedico: 'Sintetica', ...extra,
})

const AVVISO_DA_VERIFICARE = "L'orario indicato ha una richiesta precedente da verificare"
const AVVISO_VERIFICATA = "la verifica non ha trovato una richiesta registrata: questo non prova che l'invio precedente sia fallito"

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

async function vai(router, path) {
  await router.push(path)
  await flushPromises()
}

const main = () => wrapper.get('main')
const bottone = (testo) => wrapper.findAll('main button').find((b) => b.text() === testo)
const attivo = () => document.activeElement
const selezionato = () => wrapper.find('input[type="radio"]:checked').exists() ? wrapper.get('input[type="radio"]:checked').element.value : null
const avvisoPreselezione = () => wrapper.find('[data-avviso-preselezione]')

async function apriPrenota(path = '/paziente/prenota') {
  net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, SLOTS))
  return mountApp(path)
}

async function continua(id = '10') {
  await wrapper.get(`input[type="radio"][value="${id}"]`).setValue(true)
  await wrapper.get('form:not(.filters)').trigger('submit')
  await flushPromises()
}

/** Richiesta incerta per lo slot 10, poi uscita e rientro con ?slot=10. */
async function incertezzaSlot10() {
  const ctx = await apriPrenota()
  await continua()
  net.on(CREA, 'rete')
  await bottone('Invia richiesta').trigger('click')
  await flushPromises()
  net.on(LISTA, jsonResponse(200, []))
  await vai(ctx.router, '/paziente/prenotazioni')
  net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, SLOTS))
  await vai(ctx.router, '/paziente/prenota?slot=10')
  return ctx
}

describe('D1: focus dopo «Modifica la scelta»', () => {
  it('torna a «Continua», con la selezione conservata', async () => {
    await apriPrenota()
    await continua('11')
    expect(attivo().id).toBe('titolo-conferma')
    await bottone('Modifica la scelta').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(attivo()).toBe(bottone('Continua').element)
    expect(selezionato()).toBe('11')
  })

  it('aperta da «Invia una nuova richiesta»: torna a quel pulsante', async () => {
    await incertezzaSlot10()
    net.on(LISTA, jsonResponse(200, []))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    await bottone('Invia una nuova richiesta').trigger('click')
    await flushPromises()
    expect(attivo().id).toBe('titolo-conferma')
    await bottone('Modifica la scelta').trigger('click')
    await flushPromises()
    expect(attivo()).toBe(bottone('Invia una nuova richiesta').element)
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('«Continua» disabilitato (altro invio in volo): focus sul titolo degli orari, mai su body', async () => {
    const { router } = await apriPrenota('/paziente/prenota?slot=10')
    await continua('10')
    const trattenuta = net.trattieni(CREA)
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    net.on(DISP, jsonResponse(200, SLOTS))
    await vai(router, '/paziente/prenota?slot=11')
    // Conferma di B aperta mentre A è in volo (il pulsante è disabilitato, si invia il form).
    await wrapper.get('form:not(.filters)').trigger('submit')
    await flushPromises()
    await bottone('Modifica la scelta').trigger('click')
    await flushPromises()
    expect(bottone('Continua').attributes('disabled')).toBeDefined()
    expect(attivo().id).toBe('titolo-orari')
    expect(attivo()).not.toBe(document.body)
    trattenuta.resolve(jsonResponse(201, creata()))
    await flushPromises()
  })
})

describe('D1: focus dopo «Non annullare»', () => {
  async function apriConfermaAnnullamento() {
    net.on(LISTA, jsonResponse(200, [appuntamento(12)]))
    const ctx = await mountApp('/paziente/prenotazioni/12')
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    expect(attivo().id).toBe('titolo-conferma-annullamento')
    return ctx
  }

  it('torna a «Annulla prenotazione», senza scritture', async () => {
    await apriConfermaAnnullamento()
    await bottone('Non annullare').trigger('click')
    await flushPromises()
    expect(attivo()).toBe(bottone('Annulla prenotazione').element)
    expect(net.calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('pulsante non più abilitato (password incerta): focus sul titolo della sezione, mai su body', async () => {
    const { session } = await apriConfermaAnnullamento()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    await flushPromises()
    await bottone('Non annullare').trigger('click')
    await flushPromises()
    expect(bottone('Annulla prenotazione').attributes('disabled')).toBeDefined()
    expect(attivo().id).toBe('titolo-annullamento')
  })
})

describe('D2: avviso di preselezione allineato allo store', () => {
  it('da verificare → verificata senza corrispondenze: avviso aggiornato, non conclusivo, blocco e nuovo invio esplicito', async () => {
    const { store } = await incertezzaSlot10()
    // Incertezza da verificare: avviso originale, slot bloccato e non preselezionato.
    expect(avvisoPreselezione().text()).toContain(AVVISO_DA_VERIFICARE)
    expect(wrapper.get('input[type="radio"][value="10"]').attributes('disabled')).toBeDefined()
    expect(selezionato()).toBeNull()
    expect(store.richiestaBloccata(10)).toBe(true)

    net.on(LISTA, jsonResponse(200, [appuntamento(30, { idDisponibilita: 99 })]))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    // Verifica senza corrispondenze: l'avviso obsoleto sparisce, resta l'incertezza non conclusiva.
    expect(main().text()).not.toContain(AVVISO_DA_VERIFICARE)
    expect(avvisoPreselezione().text()).toContain(AVVISO_VERIFICATA)
    expect(avvisoPreselezione().text()).toContain('solo scegliendolo esplicitamente e confermandolo')
    expect(wrapper.get('[data-incerta]').text()).toContain("Questo non prova che l'invio non sia stato registrato")
    expect(store.richiestaIncerta(10)).toMatchObject({ verifica: 'assente' })
    // Nessuna preselezione automatica né invio: il nuovo tentativo richiede scelta e conferma.
    expect(selezionato()).toBeNull()
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(net.callsTo(CREA)).toHaveLength(1)

    await bottone('Invia una nuova richiesta').trigger('click')
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(1)
    net.on(CREA, jsonResponse(201, creata()))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(2)
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(true)
    expect(store.richiestaIncerta(10)).toBeNull()
  })

  it('arrivo con ?slot dopo una verifica senza corrispondenze: nessuna preselezione, avviso non conclusivo', async () => {
    const { router } = await incertezzaSlot10()
    net.on(LISTA, jsonResponse(200, []))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    net.on(LISTA, jsonResponse(200, []))
    await vai(router, '/paziente/prenotazioni')
    net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, SLOTS))
    await vai(router, '/paziente/prenota?slot=10')
    expect(selezionato()).toBeNull()
    expect(main().text()).not.toContain('Orario preselezionato')
    expect(avvisoPreselezione().text()).toContain(AVVISO_VERIFICATA)
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('verifica fallita: l\'avviso resta «da verificare» e il blocco resta', async () => {
    const { store } = await incertezzaSlot10()
    net.on(LISTA, 'rete')
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    expect(avvisoPreselezione().text()).toContain(AVVISO_DA_VERIFICARE)
    expect(store.richiestaBloccata(10)).toBe(true)
    expect(bottone('Invia una nuova richiesta')).toBeUndefined()
  })

  it('richiesta trovata in verifica: nessun avviso di preselezione residuo', async () => {
    await incertezzaSlot10()
    net.on(LISTA, jsonResponse(200, [appuntamento(31, { idDisponibilita: 10 })]))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    expect(avvisoPreselezione().exists()).toBe(false)
    expect(main().text()).toContain('risulta una tua prenotazione')
  })
})
