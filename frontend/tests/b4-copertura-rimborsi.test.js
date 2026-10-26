import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { confrontaRimborsi, parseRimborso } from '../src/api/rimborsi.js'
import { ETICHETTE_RIMBORSO } from '../src/prenotazioni/economia.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { rimborso } from './b4-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Riconciliazione dei rimborsi limitata a una prenotazione: la vista aggiorna soltanto le righe
// coperte e validate, senza adottare l'intero elenco. Dati sintetici.
const ME = 'GET /api/auth/me'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const RIMB = 'GET /api/rimborsi/paziente'
const chiedi = (id) => `POST /api/prenotazioni/${id}/rimborso`

// Prenotazione 12 → pagamento 5, prenotazione 14 → pagamento 6.
const r12 = (extra = {}) => rimborso(31, { idPrenotazione: 12, idPagamento: 5, ...extra })
const r14 = (extra = {}) => rimborso(41, { idPrenotazione: 14, idPagamento: 6, richiestoIl: '2026-10-16T08:00:00', ...extra })
const APPROVATO = { stato: 'APPROVATO', verificatoIl: '2026-10-18T10:00:00' }

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

/** Richieste incerte per le prenotazioni indicate, poi apertura di «I miei rimborsi». */
async function apriConIncerti(prenotazioni, iniziale) {
  net.on(ME, meOk(utente()))
  const session = createSession()
  await session.ensureBootstrap()
  const store = prenotazioniStore(session)
  for (const [idPrenotazione, idPagamento] of prenotazioni) {
    net.on(chiedi(idPrenotazione), 'rete')
    expect((await store.richiediRimborso(idPrenotazione, { idPagamento, importo: 80 }, 'Visita non erogata')).kind).toBe('incerto')
  }
  const tenuta = iniziale === 'trattieni' ? net.trattieni(RIMB) : null
  if (!tenuta) net.on(RIMB, jsonResponse(200, iniziale))
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push('/paziente/rimborsi')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store, tenuta }
}

const verificaDi = (id) => wrapper.findAll('[data-rimborso-incerto]')
  .find((w) => w.text().includes(`prenotazione n. ${id}`)).findAll('button').find((b) => b.text() === "Verifica nell'elenco")
/** Righe visibili: [prenotazione, pagamento, stato] nell'ordine mostrato. */
const righe = () => wrapper.findAll('[data-rimborso]').map((w) => [
  Number(w.get('h3').text().replace(/\D+/g, '')),
  Number(w.findAll('dd').at(-1).text().replace(/\D+/g, '')),
  w.get('[data-campo="stato-rimborso"]').text(),
])
const avviso = () => wrapper.find('[role="status"].notice, [role="alert"].notice')

describe('due prenotazioni: nessuna regressione delle righe non coperte', () => {
  it('A sulla 12 avviata prima, B sulla 14 dopo; B (APPROVATO) completa prima, A (RICHIESTO per la 14) dopo', async () => {
    const { store } = await apriConIncerti([[12, 5], [14, 6]], [])
    const a = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    const b = net.trattieni(RIMB)
    await verificaDi(14).trigger('click')
    await flushPromises()
    b.resolve(jsonResponse(200, [r14(APPROVATO)]))
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Approvato']])
    a.resolve(jsonResponse(200, [r14()]))
    await flushPromises()
    // La risposta di A vale soltanto per la 12: la riga della 14 non regredisce.
    expect(righe()).toEqual([[14, 6, 'Approvato']])
    expect(store.rimborsoIncerto(14)).toBeNull()
    expect(store.rimborsoIncerto(12)).toMatchObject({ verifica: 'assente' })
    expect(avviso().text()).toContain('Per la prenotazione n. 12 non risulta una richiesta di rimborso')
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(false)
    expect(net.callsTo(RIMB)).toHaveLength(3)
  })

  it('ordine di completamento inverso: A (vecchia) prima, B (recente) dopo', async () => {
    await apriConIncerti([[12, 5], [14, 6]], [r14()])
    const a = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    const b = net.trattieni(RIMB)
    await verificaDi(14).trigger('click')
    await flushPromises()
    a.resolve(jsonResponse(200, [r14()]))
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Richiesto, in attesa di decisione']])
    b.resolve(jsonResponse(200, [r14(APPROVATO)]))
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Approvato']])
  })

  it('lettura iniziale avviata prima della verifica e completata dopo: la riga verificata resta', async () => {
    const { tenuta } = await apriConIncerti([[12, 5]], 'trattieni')
    net.on(RIMB, jsonResponse(200, [r12(APPROVATO), r14()]))
    await verificaDi(12).trigger('click')
    await flushPromises()
    tenuta.resolve(jsonResponse(200, [r14()]))
    await flushPromises()
    // La lettura completa è più vecchia per la 12 e più recente di nulla per la 14: entrambe coerenti.
    expect(righe()).toEqual([[12, 5, 'Approvato'], [14, 6, 'Richiesto, in attesa di decisione']])
  })
})

describe('aggiornamento nel solo ambito coperto', () => {
  it('inserimento della richiesta verificata; le altre prenotazioni restano come erano, anche se l\'elenco le riporta diverse', async () => {
    await apriConIncerti([[12, 5]], [r14()])
    net.on(RIMB, jsonResponse(200, [r12(), r14(APPROVATO), rimborso(51, { idPrenotazione: 16, idPagamento: 8 })]))
    await verificaDi(12).trigger('click')
    await flushPromises()
    expect(righe()).toEqual([[12, 5, 'Richiesto, in attesa di decisione'], [14, 6, 'Richiesto, in attesa di decisione']])
    expect(wrapper.find('[data-rimborso-incerto]').exists()).toBe(false)
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(false)
  })

  it('assenza nell\'ambito coperto: rimuove soltanto la riga della prenotazione verificata', async () => {
    // Riga della 12 su un altro pagamento, nota da una lettura precedente; la 14 è di un'altra prenotazione.
    await apriConIncerti([[12, 5]], [r12({ idRimborso: 33, idPagamento: 7, ...APPROVATO }), r14()])
    net.on(RIMB, jsonResponse(200, [r14(APPROVATO)]))
    await verificaDi(12).trigger('click')
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Richiesto, in attesa di decisione']])
  })

  it('lo stato non conferisce precedenza: una risposta più recente sostituisce anche un APPROVATO', async () => {
    await apriConIncerti([[12, 5]], [r12({ idRimborso: 33, idPagamento: 7, ...APPROVATO }), r14()])
    net.on(RIMB, jsonResponse(200, [r12({ idRimborso: 33, idPagamento: 7, stato: 'IN_ELABORAZIONE', verificatoIl: '2026-10-18T10:00:00' })]))
    await verificaDi(12).trigger('click')
    await flushPromises()
    expect(righe()).toEqual([[12, 7, ETICHETTE_RIMBORSO.IN_ELABORAZIONE], [14, 6, 'Richiesto, in attesa di decisione']])
  })

  it('ordine finale dal contratto: richiestoIl decrescente con secondi e frazioni, poi idRimborso decrescente', async () => {
    const altre = [
      rimborso(60, { idPrenotazione: 20, idPagamento: 9, richiestoIl: '2026-10-17T09:00:30' }),
      rimborso(70, { idPrenotazione: 21, idPagamento: 10, richiestoIl: '2026-10-17T09:00:05.5' }),
      rimborso(20, { idPrenotazione: 22, idPagamento: 11, richiestoIl: '2026-10-17T09:00' }),
    ]
    await apriConIncerti([[12, 5]], altre)
    // La richiesta verificata ha lo stesso istante della 22 e un identificativo maggiore.
    net.on(RIMB, jsonResponse(200, [r12({ idRimborso: 25, richiestoIl: '2026-10-17T09:00:00' }), ...altre]))
    await verificaDi(12).trigger('click')
    await flushPromises()
    expect(righe().map((r) => r[0])).toEqual([20, 21, 12, 22])
  })

  it('confronto puro: nessun criterio di stato', () => {
    const p = (x) => parseRimborso(x)
    const [v, n] = [p(r12({ stato: 'APPROVATO', richiestoIl: '2026-10-17T09:00:00.000000001' })), p(r14({ stato: 'RICHIESTO', richiestoIl: '2026-10-17T09:00:00.1' }))]
    expect([v, n].sort(confrontaRimborsi).map((r) => r.idRimborso)).toEqual([41, 31])
  })
})

describe('risposte superate e cambio identità', () => {
  it('risposta superata senza riscontro: lista invariata, aggiornamento solo manuale', async () => {
    const { store } = await apriConIncerti([[12, 5]], [r14()])
    const a = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    await flushPromises()
    // Lettura autorevole più recente di un'altra istanza: nessuna richiesta per la 12.
    store.registraRimborsi([], store.inizioLettura())
    a.resolve(jsonResponse(200, [r12(), r14(APPROVATO)]))
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Richiesto, in attesa di decisione']])
    expect(avviso().text()).toContain('superato da dati più recenti')
    expect(net.callsTo(RIMB)).toHaveLength(2)
    net.on(RIMB, jsonResponse(200, [r14(APPROVATO)]))
    await wrapper.get('[data-aggiorna-elenco] button').trigger('click')
    await flushPromises()
    expect(net.callsTo(RIMB)).toHaveLength(3)
    expect(righe()).toEqual([[14, 6, 'Approvato']])
  })

  it('risposta superata con riscontro positivo corrente: lista invariata e aggiornamento manuale offerto', async () => {
    const { store } = await apriConIncerti([[12, 5]], [r14()])
    const a = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    await flushPromises()
    store.registraRimborsi([parseRimborso(r12())], store.inizioLettura())
    a.resolve(jsonResponse(200, [r14(APPROVATO)]))
    await flushPromises()
    expect(righe()).toEqual([[14, 6, 'Richiesto, in attesa di decisione']])
    expect(avviso().text()).toContain('secondo i dati più recenti')
    expect(wrapper.find('[data-aggiorna-elenco]').exists()).toBe(true)
    expect(net.callsTo(RIMB)).toHaveLength(2)
  })

  it('verifica completata dopo un cambio di identità: nessun effetto sulla lista del nuovo utente', async () => {
    const { session, store } = await apriConIncerti([[12, 5]], [r14()])
    const a = net.trattieni(RIMB)
    await verificaDi(12).trigger('click')
    await flushPromises()
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    await flushPromises()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B)).on(RIMB, jsonResponse(200, []))
    await session.login('altro@example.test', 'x')
    await flushPromises()
    a.resolve(jsonResponse(200, [r12(APPROVATO), r14(APPROVATO)]))
    await flushPromises()
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(0)
    expect(store.state).toMatchObject({ owner: 8, rimborsiIncerti: [], ambitiRimborso: [] })
  })
})
