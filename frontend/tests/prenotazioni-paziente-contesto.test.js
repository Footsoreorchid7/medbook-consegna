import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import PrenotaView from '../src/views/PrenotaView.vue'
import PrenotazioneDettaglioView from '../src/views/PrenotazioneDettaglioView.vue'
import { appuntamento } from './prenotazioni-paziente-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Regressione: effetti tardivi di una scrittura dopo un cambio di parametro o query nella
// stessa istanza della vista. Dati sintetici: nessuna identità reale.
const ME = 'GET /api/auth/me'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'
const CREA = 'POST /api/prenotazioni'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const annulla = (id) => `POST /api/prenotazioni/${id}/annulla-paziente`

const ELENCO = [appuntamento(12), appuntamento(13, { idDisponibilita: 20, nomePrestazione: 'Ecografia', stato: 'CONFERMATA' })]
const PRESTAZIONI = [{ idPrestazione: 1, nome: 'Visita cardiologica', descrizione: null, durata: 30, prezzo: 80 }]
const slot = (id, extra = {}) => ({
  idDisponibilita: id, dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idMedico: 4, nomeMedico: 'Elena',
  cognomeMedico: 'Sintetica', idPrestazione: 1, nomePrestazione: 'Visita cardiologica', durataPrestazione: 30, prezzoPrestazione: 80, ...extra,
})
const SLOTS = [slot(10), slot(11, { dataOraInizio: '2026-10-21T14:00:00', dataOraFine: '2026-10-21T15:00:00', nomeMedico: 'Marco', cognomeMedico: 'Prova' })]
const creata = (extra = {}) => ({
  idPrenotazione: 50, stato: 'RICHIESTA', dataOraRichiesta: '2026-10-15T20:10:11', prezzo: 80, idDisponibilita: 10,
  dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idPrestazione: 1, nomePrestazione: 'Visita cardiologica',
  idMedico: 4, nomeMedico: 'Elena', cognomeMedico: 'Sintetica', ...extra,
})

let net
let wrapper

beforeEach(() => {
  net = installFetch(routedFetch())
  // la 13 è CONFERMATA e il dettaglio ne legge anche lo storico pagamenti (vuoto).
  for (let i = 0; i < 6; i++) net.on('GET /api/prenotazioni/13/pagamenti', () => jsonResponse(200, []))
})
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
/** Identità dell'istanza montata: deve restare la stessa nei cambi di parametro o query. */
const istanza = (Componente) => wrapper.findComponent(Componente).vm.$.uid

describe('dettaglio: annullamento della 12 e cambio di ID nella stessa istanza', () => {
  async function annullamento12Pendente() {
    net.on(LISTA, jsonResponse(200, ELENCO))
    const ctx = await mountApp('/paziente/prenotazioni/12')
    const uid = istanza(PrenotazioneDettaglioView)
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    const post = net.trattieni(annulla(12))
    await bottone('Conferma annullamento').trigger('click')
    await flushPromises()
    expect(bottone('Annullamento in corso…')).toBeDefined()
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(ctx.router, '/paziente/prenotazioni/13')
    expect(istanza(PrenotazioneDettaglioView)).toBe(uid)
    expect(wrapper.get('h2.item-title').text()).toBe('Ecografia')
    return { ...ctx, post, uid }
  }

  /** La 13 resta nel proprio stato iniziale: nessun messaggio, conferma o pending ereditati. */
  function trediciIntatta() {
    expect(wrapper.get('h2.item-title').text()).toBe('Ecografia')
    expect(wrapper.find('main [role="alert"]').exists()).toBe(false)
    expect(wrapper.find('main .notice').exists()).toBe(false)
    expect(bottone('Conferma annullamento')).toBeUndefined()
    expect(bottone('Annullamento in corso…')).toBeUndefined()
    expect(bottone('Annulla prenotazione').attributes('disabled')).toBeUndefined()
  }

  it('successo della 12 dopo il passaggio alla 13: nessun messaggio né rilettura sulla 13; store aggiornato', async () => {
    const { post, store, uid } = await annullamento12Pendente()
    post.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    await flushPromises()
    expect(istanza(PrenotazioneDettaglioView)).toBe(uid)
    trediciIntatta()
    expect(main().text()).not.toContain('Annullamento registrato')
    expect(net.callsTo(LISTA)).toHaveLength(2)
    expect(store.state.annullamentiInCorso).toEqual([])
    expect(store.annullamentoIncerto(12)).toBeNull()
  })

  it('409 della 12 dopo il passaggio alla 13: nessun errore né rilettura sulla 13', async () => {
    const { post, store } = await annullamento12Pendente()
    post.resolve(jsonResponse(409, { error: 'Prenotazione non più annullabile.' }))
    await flushPromises()
    trediciIntatta()
    expect(net.callsTo(LISTA)).toHaveLength(2)
    expect(store.state.annullamentiInCorso).toEqual([])
    expect(store.annullamentoBloccato(12)).toBe(false)
  })

  it('esito incerto della 12 dopo il passaggio alla 13: incertezza registrata nello store, la 13 non cambia', async () => {
    const { post, router, store } = await annullamento12Pendente()
    post.resolve(jsonResponse(500, { error: 'x' }))
    await flushPromises()
    trediciIntatta()
    expect(wrapper.find('[data-incerto]').exists()).toBe(false)
    expect(store.annullamentoIncerto(12)).toEqual({ idPrenotazione: 12, statoAtteso: 'RICHIESTA', verificato: false })
    expect(store.annullamentoBloccato(13)).toBe(false)
    expect(net.callsTo(annulla(12))).toHaveLength(1)
    // Il blocco della 12 resta visibile quando si torna alla 12.
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(router, '/paziente/prenotazioni/12')
    expect(wrapper.find('[data-incerto]').exists()).toBe(true)
    expect(bottone('Annulla prenotazione')).toBeUndefined()
  })

  it('cambio di rotta durante la rilettura successiva alla scrittura: la rilettura vecchia è scartata', async () => {
    net.on(LISTA, jsonResponse(200, ELENCO))
    const { router } = await mountApp('/paziente/prenotazioni/12')
    const uid = istanza(PrenotazioneDettaglioView)
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    net.on(annulla(12), jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    const rilettura = net.trattieni(LISTA)
    await bottone('Conferma annullamento').trigger('click')
    await flushPromises()
    expect(net.callsTo(LISTA)).toHaveLength(2)
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(router, '/paziente/prenotazioni/13')
    rilettura.resolve?.(jsonResponse(200, [appuntamento(12, { stato: 'ANNULLATA' }), ELENCO[1]]))
    await flushPromises()
    expect(istanza(PrenotazioneDettaglioView)).toBe(uid)
    trediciIntatta()
    expect(main().text()).not.toContain('Annullamento registrato')
    expect(wrapper.get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(net.callsTo(LISTA)).toHaveLength(3)
  })

  it('12 → 13 → 12: il ritorno alla 12 è un contesto nuovo e non adotta gli effetti del vecchio tentativo', async () => {
    const { post, router, store, uid } = await annullamento12Pendente()
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(router, '/paziente/prenotazioni/12')
    expect(istanza(PrenotazioneDettaglioView)).toBe(uid)
    // Il tentativo è ancora in volo nello store: nuovo invio impedito, ma nessun pending locale ereditato.
    expect(bottone('Annullamento in corso…')).toBeUndefined()
    expect(bottone('Conferma annullamento')).toBeUndefined()
    expect(bottone('Annulla prenotazione').attributes('disabled')).toBeDefined()
    post.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    await flushPromises()
    expect(main().text()).not.toContain('Annullamento registrato')
    expect(main().text()).toContain('superata da un’altra operazione'.replace('’', "'"))
    expect(bottone('Annulla prenotazione').attributes('disabled')).toBeDefined()
    expect(net.callsTo(LISTA)).toHaveLength(3)
    expect(net.callsTo(annulla(12))).toHaveLength(1)
    expect(store.state.annullamentiInCorso).toEqual([])
    // Lo stato mostrato resta quello letto nel nuovo contesto finché l'utente non rilegge.
    expect(wrapper.get('[data-campo="stato"]').text()).toBe('Richiesta inviata, in attesa di conferma')
  })

  it('12 → 13 → 12 con esito incerto: errore del vecchio contesto non mostrato, blocco dallo store', async () => {
    const { post, router, store } = await annullamento12Pendente()
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(router, '/paziente/prenotazioni/12')
    post.resolve(jsonResponse(503, { error: 'x' }))
    await flushPromises()
    expect(store.annullamentoBloccato(12)).toBe(true)
    // L'avviso persistente deriva dallo store; il vecchio contesto non sposta il focus.
    expect(wrapper.find('[data-incerto]').exists()).toBe(true)
    expect(document.activeElement?.hasAttribute('data-incerto')).toBe(false)
    expect(net.callsTo(LISTA)).toHaveLength(3)
  })

  it('verifica manuale in corso sulla 12 e cambio di ID: nessun messaggio e nessuna verifica registrata', async () => {
    net.on(LISTA, jsonResponse(200, ELENCO))
    const { router, store } = await mountApp('/paziente/prenotazioni/12')
    await bottone('Annulla prenotazione').trigger('click')
    await flushPromises()
    net.on(annulla(12), 'rete')
    await bottone('Conferma annullamento').trigger('click')
    await flushPromises()
    const lettura = net.trattieni(LISTA)
    await bottone('Verifica lo stato').trigger('click')
    net.on(LISTA, jsonResponse(200, ELENCO))
    await vai(router, '/paziente/prenotazioni/13')
    lettura.resolve?.(jsonResponse(200, ELENCO))
    await flushPromises()
    trediciIntatta()
    expect(store.annullamentoBloccato(12)).toBe(true)
  })
})

describe('prenotazione: invio dello slot A e cambio di query nella stessa istanza', () => {
  async function invioAPendente() {
    net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, SLOTS))
    const ctx = await mountApp('/paziente/prenota?slot=10')
    const uid = istanza(PrenotaView)
    expect(wrapper.get('input[type="radio"][value="10"]').element.checked).toBe(true)
    await wrapper.get('form:not(.filters)').trigger('submit')
    await flushPromises()
    const post = net.trattieni(CREA)
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(bottone('Invio in corso…')).toBeDefined()
    // Nuova preselezione B nella stessa istanza.
    net.on(DISP, jsonResponse(200, SLOTS))
    await vai(ctx.router, '/paziente/prenota?slot=11')
    expect(istanza(PrenotaView)).toBe(uid)
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(wrapper.get('input[type="radio"][value="11"]').element.checked).toBe(true)
    // Il tentativo A è ancora in volo nello store: un nuovo invio è impedito.
    expect(main().text()).toContain('Una richiesta di appuntamento è in corso di invio')
    return { ...ctx, post, uid }
  }

  /** Apre la conferma di B mentre A è ancora in volo. */
  async function confermaB() {
    await wrapper.get('form:not(.filters)').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-conferma] [data-campo="medico"]').text()).toBe('Marco Prova')
    expect(bottone('Invia richiesta').attributes('disabled')).toBeDefined()
  }

  /** Il contesto B non è stato toccato dal completamento di A. */
  function bIntatto() {
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(false)
    expect(wrapper.get('[data-conferma] [data-campo="medico"]').text()).toBe('Marco Prova')
    expect(bottone('Invio in corso…')).toBeUndefined()
    expect(wrapper.find('main .notice.error').exists()).toBe(false)
  }

  it('completamento di A con successo: nessun "Richiesta inviata" su B, pending di B intatto, poi B inviabile', async () => {
    const { post, store, uid } = await invioAPendente()
    await confermaB()
    post.resolve(jsonResponse(201, creata()))
    await flushPromises()
    expect(istanza(PrenotaView)).toBe(uid)
    bIntatto()
    expect(store.state.invioInCorso).toBeNull()
    expect(store.richiestaIncerta(10)).toBeNull()
    expect(bottone('Invia richiesta').attributes('disabled')).toBeUndefined()
    net.on(CREA, jsonResponse(201, creata({ idPrenotazione: 51, idDisponibilita: 11 })))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(JSON.parse(net.callsTo(CREA)[1].body)).toEqual({ idPaziente: 7, idDisponibilita: 11 })
    expect(wrapper.find('[data-esito="inviata"] a[href="/paziente/prenotazioni/51"]').exists()).toBe(true)
  })

  it('409 di A: nessun errore, nessuna nuova ricerca e scelta B intatta', async () => {
    const { post, store } = await invioAPendente()
    await confermaB()
    const ricerche = net.callsTo(DISP).length
    post.resolve(jsonResponse(409, { error: 'Disponibilità già occupata.' }))
    await flushPromises()
    bIntatto()
    expect(net.callsTo(DISP)).toHaveLength(ricerche)
    expect(store.richiestaIncerta(10)).toBeNull()
    expect(store.state.invioInCorso).toBeNull()
  })

  it('esito incerto di A: incertezza registrata nello store, scelta B e focus non alterati', async () => {
    const { post, store } = await invioAPendente()
    await confermaB()
    post.resolve(jsonResponse(502, { error: 'x' }))
    await flushPromises()
    bIntatto()
    expect(store.richiestaBloccata(10)).toBe(true)
    expect(store.richiestaIncerta(10).riepilogo.idDisponibilita).toBe(10)
    expect(store.richiestaBloccata(11)).toBe(false)
    expect(document.activeElement?.id).not.toBe('titolo-incerte')
    // L'incertezza di A è visibile dallo store e verificabile manualmente.
    expect(wrapper.get('[data-incerta]').text()).toContain('Visita cardiologica, Elena Sintetica')
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('completamento di A con B ancora in fase di scelta: selezione B conservata', async () => {
    const { post } = await invioAPendente()
    post.resolve(jsonResponse(201, creata()))
    await flushPromises()
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(false)
    expect(wrapper.get('input[type="radio"][value="11"]').element.checked).toBe(true)
    expect(main().text()).not.toContain('Una richiesta di appuntamento è in corso di invio')
  })

  it('stessa query riaperta (slot 10 → 11 → 10): il ritorno non adotta il vecchio invio', async () => {
    const { post, router, uid } = await invioAPendente()
    net.on(DISP, jsonResponse(200, SLOTS))
    await vai(router, '/paziente/prenota?slot=10')
    expect(istanza(PrenotaView)).toBe(uid)
    post.resolve(jsonResponse(201, creata()))
    await flushPromises()
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(false)
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(wrapper.get('input[type="radio"][value="10"]').element.checked).toBe(true)
  })
})
