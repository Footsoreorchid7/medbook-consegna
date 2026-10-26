import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import PrenotazioneDettaglioView from '../src/views/PrenotazioneDettaglioView.vue'
import { appuntamento } from './prenotazioni-paziente-fixtures.js'
import { confermata, fallito, pagamento, pdfResponse, referto, rimborso } from './pagamenti-rimborsi-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Dati sintetici: nessuna identità o dato economico reale.
const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = (id = 12) => `GET /api/prenotazioni/${id}/pagamenti`
const PAGA = (id = 12) => `POST /api/prenotazioni/${id}/pagamento-online`
const RIMB = 'GET /api/rimborsi/paziente'
const CHIEDI = (id = 12) => `POST /api/prenotazioni/${id}/rimborso`
const REF = (id = 12) => `GET /api/prenotazioni/${id}/referto`
const DOC = (id = 12) => `GET /api/prenotazioni/${id}/referto/documento`

let net
let wrapper

beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

async function mountApp(path, me = meOk(utente())) {
  net.on(ME, me)
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
const sezione = (nome) => wrapper.find(`[data-sezione="${nome}"]`)
const corpo = (call) => JSON.parse(call.body)
const attivo = () => document.activeElement
const istanza = () => wrapper.findComponent(PrenotazioneDettaglioView).vm.$.uid

/** Dettaglio di una prenotazione CONFERMATA (12) con lo storico indicato. */
async function dettaglio(storico = [], prenotazione = confermata(12), extra = () => {}) {
  net.on(LISTA, jsonResponse(200, [prenotazione]))
  net.on(PAG(prenotazione.idPrenotazione), Array.isArray(storico) ? jsonResponse(200, storico) : storico)
  extra()
  return mountApp(`/paziente/prenotazioni/${prenotazione.idPrenotazione}`)
}

async function apriConfermaPagamento() {
  await bottone('Paga online (simulato)').trigger('click')
  await flushPromises()
  expect(attivo().id).toBe('titolo-conferma-pagamento')
}

describe('dettaglio: storico pagamenti', () => {
  it('ordine del backend, etichette, FALLITO non incassato, pagamento simulato senza dati di carta', async () => {
    await dettaglio([pagamento(7, { modalita: 'DESK_POS', stato: 'IN_ATTESA', pagatoIl: null }), fallito(6), fallito(5)])
    const voci = wrapper.findAll('[data-pagamento]')
    expect(voci.map((v) => v.text().match(/Numero pagamento\s*(\d+)/)[1])).toEqual(['7', '6', '5'])
    expect(voci[0].get('[data-campo="modalita"]').text()).toBe('In sede, POS')
    expect(voci[1].get('[data-campo="stato-pagamento"]').text()).toBe('Non riuscito (nessun importo incassato)')
    expect(voci[1].text()).not.toContain('Pagato il')
    // IN_ATTESA presente: il pagamento online non è proposto.
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(sezione('pagamenti').findAll('input, select, textarea')).toHaveLength(0)
  })

  it('storico vuoto: messaggio e proposta del pagamento simulato', async () => {
    await dettaglio([])
    expect(sezione('pagamenti').text()).toContain('Nessun pagamento registrato.')
    expect(wrapper.get('[data-pagamento-simulato]').text()).toContain('nessun denaro reale')
    expect(wrapper.get('[data-pagamento-simulato]').text()).toContain('non sono richiesti dati di carta')
    expect(bottone('Paga online (simulato)').attributes('disabled')).toBeUndefined()
  })

  it.each([
    ['PAGATO', pagamento(6)],
    ['IN_ATTESA', pagamento(6, { stato: 'IN_ATTESA', pagatoIl: null })],
  ])('storico con %s: pagamento non proposto', async (_, voce) => {
    await dettaglio([voce])
    expect(bottone('Paga online (simulato)')).toBeUndefined()
  })

  it('storico in errore: prenotazione visibile, pagamento non consentito, recupero manuale', async () => {
    await dettaglio(jsonResponse(500, { error: 'x' }))
    expect(wrapper.get('[data-prenotazione]').text()).toContain('Confermata')
    expect(sezione('pagamenti').get('[role="alert"]').text()).toContain('Non è stato possibile caricare i pagamenti')
    expect(sezione('pagamenti').text()).toContain('il pagamento online non è disponibile')
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    net.on(PAG(), jsonResponse(200, []))
    await sezione('pagamenti').findAll('button').find((b) => b.text() === 'Riprova').trigger('click')
    await flushPromises()
    expect(bottone('Paga online (simulato)')).toBeDefined()
    expect(net.callsTo(PAG())).toHaveLength(2)
  })

  it('prenotazione RICHIESTA: nessuna lettura di pagamenti, rimborsi o referti', async () => {
    net.on(LISTA, jsonResponse(200, [appuntamento(12)]))
    await mountApp('/paziente/prenotazioni/12')
    expect(net.calls.some((c) => /pagament|rimbors|referto/.test(c.url))).toBe(false)
    expect(sezione('pagamenti').exists()).toBe(false)
  })
})

describe('dettaglio: pagamento online simulato', () => {
  it('conferma accessibile, «Non pagare» restituisce il focus, payload col prezzo storico, 201 PAGATO e rilettura', async () => {
    await dettaglio([], confermata(12, { prezzo: 95.5 }))
    await apriConfermaPagamento()
    expect(wrapper.get('[data-campo="importo-atteso"]').text()).toMatch(/95,50\s€/)
    await bottone('Non pagare').trigger('click')
    await flushPromises()
    expect(attivo()).toBe(bottone('Paga online (simulato)').element)
    expect(net.callsTo(PAGA())).toHaveLength(0)
    await apriConfermaPagamento()
    net.on(PAGA(), jsonResponse(201, pagamento(9, { importo: 95.5 })))
    net.on(LISTA, jsonResponse(200, [confermata(12, { prezzo: 95.5 })]))
    net.on(PAG(), jsonResponse(200, [pagamento(9, { importo: 95.5 })]))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(corpo(net.callsTo(PAGA())[0])).toEqual({ importoAtteso: 95.5 })
    expect(wrapper.get('[data-avviso-pagamento]').text()).toContain('Pagamento simulato riuscito')
    expect(net.callsTo(LISTA)).toHaveLength(2)
    expect(net.callsTo(PAG())).toHaveLength(2)
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(bottone('Paga online (simulato)')).toBeUndefined()
  })

  it('201 FALLITO: esito certo di mancato pagamento, rilettura, nuovo tentativo possibile con nuova conferma', async () => {
    await dettaglio([])
    await apriConfermaPagamento()
    net.on(PAGA(), jsonResponse(201, fallito(9)))
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    net.on(PAG(), jsonResponse(200, [fallito(9)]))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    const avviso = wrapper.get('[data-avviso-pagamento]')
    expect(avviso.attributes('role')).toBe('alert')
    expect(avviso.text()).toContain('nessun importo è stato incassato')
    expect(avviso.text()).not.toContain('Pagamento simulato riuscito')
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toContain('Non riuscito')
    expect(bottone('Paga online (simulato)')).toBeDefined()
    expect(net.callsTo(PAGA())).toHaveLength(1)
  })

  it.each([
    [409, 'Pagamento non consentito', true],
    [404, 'Prenotazione non trovata', true],
    [403, 'Operazione non consentita', false],
  ])('%i: messaggio, nessuna ripetizione', async (status, testo, rilettura) => {
    await dettaglio([])
    await apriConfermaPagamento()
    net.on(PAGA(), jsonResponse(status, { error: 'x' }))
    if (rilettura) net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(), jsonResponse(200, []))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-avviso-pagamento]').text()).toContain(testo)
    expect(net.callsTo(PAGA())).toHaveLength(1)
    expect(net.callsTo(LISTA)).toHaveLength(rilettura ? 2 : 1)
  })

  it('doppio clic: una sola POST, conferma disabilitata durante l\'invio', async () => {
    await dettaglio([])
    await apriConfermaPagamento()
    const trattenuta = net.trattieni(PAGA())
    const conferma = bottone('Conferma pagamento simulato')
    await conferma.trigger('click')
    await conferma.trigger('click')
    await flushPromises()
    expect(bottone('Pagamento in corso…').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-conferma-pagamento]').attributes('aria-busy')).toBe('true')
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(), jsonResponse(200, [pagamento(9)]))
    trattenuta.resolve(jsonResponse(201, pagamento(9)))
    await flushPromises()
    expect(net.callsTo(PAGA())).toHaveLength(1)
  })

  it('esito incerto con storico precedente: blocco anche navigando, verifica non conclusiva, nuovo tentativo esplicito', async () => {
    const { router } = await dettaglio([fallito(4)])
    await apriConfermaPagamento()
    net.on(PAGA(), 'rete')
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(attivo().hasAttribute('data-pagamento-incerto')).toBe(true)
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('Il server potrebbe averlo registrato')
    expect(bottone('Paga online (simulato)')).toBeUndefined()
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()

    // Uscita e rientro: il blocco resta (store in memoria).
    net.on(LISTA, jsonResponse(200, [confermata(12)]))
    await vai(router, '/paziente/prenotazioni')
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(), jsonResponse(200, [fallito(4)]))
    await vai(router, '/paziente/prenotazioni/12')
    expect(wrapper.find('[data-pagamento-incerto]').exists()).toBe(true)
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()

    // Lo storico con il solo tentativo precedente non prova l'esito del nuovo.
    // La verifica è seguita da una rilettura autorevole dello storico mostrato.
    net.on(PAG(), jsonResponse(200, [fallito(4)]), jsonResponse(200, [fallito(4)]))
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('non compare un nuovo pagamento')
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('non prova che il tentativo non sia stato registrato')
    expect(net.callsTo(PAGA())).toHaveLength(1)

    await bottone('Nuovo tentativo di pagamento simulato').trigger('click')
    await flushPromises()
    expect(net.callsTo(PAGA())).toHaveLength(1)
    net.on(PAGA(), jsonResponse(201, pagamento(9)))
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(), jsonResponse(200, [pagamento(9), fallito(4)]))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(net.callsTo(PAGA())).toHaveLength(2)
    expect(wrapper.find('[data-pagamento-incerto]').exists()).toBe(false)
  })

  it('verifica con un nuovo pagamento PAGATO: nessuna attribuzione certa, storico aggiornato', async () => {
    await dettaglio([])
    await apriConfermaPagamento()
    net.on(PAGA(), jsonResponse(502, { error: 'x' }))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    net.on(PAG(), jsonResponse(200, [pagamento(9)]), jsonResponse(200, [pagamento(9)]))
    await bottone('Verifica nello storico pagamenti').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-pagamento-incerto]').text()).toContain('Non è possibile stabilire con certezza se corrisponda al tuo tentativo')
    expect(net.callsTo(PAG())).toHaveLength(3)
    expect(wrapper.get('[data-campo="stato-pagamento"]').text()).toBe('Pagato')
    expect(bottone('Nuovo tentativo di pagamento simulato')).toBeUndefined()
  })

  it('passwordUncertain: conferma disabilitata e nessuna POST', async () => {
    const { session } = await dettaglio([])
    await apriConfermaPagamento()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    await flushPromises()
    expect(bottone('Conferma pagamento simulato').attributes('disabled')).toBeDefined()
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    expect(net.callsTo(PAGA())).toHaveLength(0)
    expect(sezione('pagamenti').text()).toContain("finché non verifichi l'esito del cambio password")
  })
})

describe('dettaglio: cambio di rotta nella stessa istanza durante il pagamento', () => {
  async function pagamento12Pendente() {
    net.on(LISTA, jsonResponse(200, [confermata(12), confermata(13, { idDisponibilita: 20, nomePrestazione: 'Ecografia' })]))
    net.on(PAG(12), jsonResponse(200, []))
    const ctx = await mountApp('/paziente/prenotazioni/12')
    const uid = istanza()
    await apriConfermaPagamento()
    const post = net.trattieni(PAGA(12))
    await bottone('Conferma pagamento simulato').trigger('click')
    await flushPromises()
    net.on(LISTA, jsonResponse(200, [confermata(12), confermata(13, { idDisponibilita: 20, nomePrestazione: 'Ecografia' })]))
    net.on(PAG(13), jsonResponse(200, []))
    await vai(ctx.router, '/paziente/prenotazioni/13')
    expect(istanza()).toBe(uid)
    expect(wrapper.get('#titolo-prenotazione').text()).toBe('Ecografia')
    return { ...ctx, post, uid }
  }

  it('12 pendente → 13 → esito di 12: nessun messaggio né rilettura sulla 13, store aggiornato', async () => {
    const { post, store, uid } = await pagamento12Pendente()
    const letture = net.calls.length
    post.resolve(jsonResponse(201, pagamento(9)))
    await flushPromises()
    expect(istanza()).toBe(uid)
    expect(wrapper.find('[data-avviso-pagamento]').exists()).toBe(false)
    expect(net.calls).toHaveLength(letture)
    expect(bottone('Paga online (simulato)').attributes('disabled')).toBeUndefined()
    expect(store.state.pagamentiInCorso).toEqual([])
  })

  it('12 → 13 → 12 durante il tentativo: nessun pending ereditato, esito incerto registrato e mostrato dallo store', async () => {
    const { post, router, store, uid } = await pagamento12Pendente()
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG(12), jsonResponse(200, []))
    await vai(router, '/paziente/prenotazioni/12')
    expect(istanza()).toBe(uid)
    expect(bottone('Pagamento in corso…')).toBeUndefined()
    expect(bottone('Paga online (simulato)').attributes('disabled')).toBeDefined()
    expect(sezione('pagamenti').text()).toContain('Un tentativo di pagamento è in corso')
    post.resolve(jsonResponse(500, { error: 'x' }))
    await flushPromises()
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(wrapper.find('[data-pagamento-incerto]').exists()).toBe(true)
    expect(attivo().hasAttribute?.('data-pagamento-incerto')).not.toBe(true)
    expect(net.callsTo(PAGA(12))).toHaveLength(1)
  })

  it('cambio di identità durante il tentativo: dati e incertezze del precedente utente eliminati', async () => {
    const { post, session, store } = await pagamento12Pendente()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    net.on(ME, meOk(utente({ idUtente: 8, nome: 'Bruno' }), CSRF_B))
    net.on('GET /api/prenotazioni/paziente?idPaziente=8', jsonResponse(200, []))
    await session.verifyAfterPasswordChange()
    await flushPromises()
    post.resolve(jsonResponse(500, { error: 'x' }))
    await flushPromises()
    expect(store.state).toMatchObject({ owner: 8, pagamentiIncerti: [], pagamentiInCorso: [] })
    expect(wrapper.find('[data-sezione="pagamenti"]').exists()).toBe(false)
    expect(main().text()).toContain('Prenotazione non trovata')
  })
})

describe('dettaglio: richiesta di rimborso', () => {
  const nonErogata = (extra) => confermata(12, { stato: 'NON_EROGATA', ...extra })
  async function apri(storico = [pagamento(5)], rimborsi = []) {
    return dettaglio(storico, nonErogata(), () => net.on(RIMB, Array.isArray(rimborsi) ? jsonResponse(200, rimborsi) : rimborsi))
  }

  it('motivazione validata, conferma accessibile, payload esatto, 201 e rilettura', async () => {
    await apri()
    expect(sezione('rimborso').text()).toContain('Pagamento online n. 5')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#errore-motivazione').text()).toBe('Indica la motivazione.')
    expect(attivo().id).toBe('motivazione-rimborso')
    expect(wrapper.get('#motivazione-rimborso').attributes('aria-invalid')).toBe('true')
    await wrapper.get('#motivazione-rimborso').setValue('  Visita non erogata  ')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    expect(attivo().id).toBe('titolo-conferma-rimborso')
    await bottone('Modifica la motivazione').trigger('click')
    await flushPromises()
    expect(attivo()).toBe(bottone('Prosegui').element)
    expect(wrapper.get('#motivazione-rimborso').element.value).toBe('  Visita non erogata  ')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    net.on(CHIEDI(), jsonResponse(201, rimborso(31, { motivazione: '  Visita non erogata  ' })))
    net.on(LISTA, jsonResponse(200, [nonErogata()])).on(PAG(), jsonResponse(200, [pagamento(5)]))
    net.on(RIMB, jsonResponse(200, [rimborso(31, { motivazione: '  Visita non erogata  ' })]))
    await bottone('Invia richiesta di rimborso').trigger('click')
    await flushPromises()
    expect(corpo(net.callsTo(CHIEDI())[0])).toEqual({ motivazione: '  Visita non erogata  ' })
    expect(wrapper.get('[data-avviso-rimborso]').text()).toContain('Non è ancora un rimborso approvato né eseguito')
    expect(net.callsTo(RIMB)).toHaveLength(2)
    expect(wrapper.get('[data-rimborso] [data-campo="stato-rimborso"]').text()).toBe('Richiesto, in attesa di decisione')
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
  })

  it.each([
    ['pagamento in sede', [pagamento(5, { modalita: 'DESK_POS' })], []],
    ['importo diverso dal prezzo', [pagamento(5, { importo: 70 })], []],
    ['solo tentativi falliti', [fallito(5)], []],
  ])('non proposta: %s', async (_, storico, rimborsi) => {
    await apri(storico, rimborsi)
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    expect(sezione('rimborso').text()).toContain('non risulta un pagamento online riuscito')
  })

  it('richiesta già presente per il pagamento: mostrata, APPROVATO non è una restituzione, nessun modulo', async () => {
    await apri([pagamento(5)], [rimborso(31, { stato: 'APPROVATO', verificatoIl: '2026-10-18T09:00:00' })])
    const voce = wrapper.get('[data-rimborso]')
    expect(voce.get('[data-campo="stato-rimborso"]').text()).toBe('Approvato')
    expect(voce.text()).toContain('non significa che il denaro sia già stato restituito')
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
  })

  it('rimborsi non verificabili: nessun modulo, prenotazione e pagamenti visibili', async () => {
    await apri([pagamento(5)], jsonResponse(500, { error: 'x' }))
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    expect(sezione('rimborso').text()).toContain('non sono verificabili')
    expect(wrapper.findAll('[data-pagamento]')).toHaveLength(1)
    expect(wrapper.get('[data-prenotazione]').exists()).toBe(true)
  })

  it('409 duplicato: messaggio e rilettura, una sola POST', async () => {
    await apri()
    await wrapper.get('#motivazione-rimborso').setValue('Motivo')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    net.on(CHIEDI(), jsonResponse(409, { error: 'Richiesta di rimborso già presente.' }))
    net.on(LISTA, jsonResponse(200, [nonErogata()])).on(PAG(), jsonResponse(200, [pagamento(5)]))
    net.on(RIMB, jsonResponse(200, [rimborso(31)]))
    await bottone('Invia richiesta di rimborso').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-avviso-rimborso]').text()).toContain('potrebbe esistere già una richiesta')
    expect(net.callsTo(CHIEDI())).toHaveLength(1)
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(1)
  })

  it('esito incerto: blocco, verifica senza corrispondenze non conclusiva, nuovo invio esplicito; poi riconciliazione', async () => {
    const { router } = await apri()
    await wrapper.get('#motivazione-rimborso').setValue('Motivo')
    await wrapper.get('[data-sezione="rimborso"] form').trigger('submit')
    await flushPromises()
    net.on(CHIEDI(), 'rete')
    await bottone('Invia richiesta di rimborso').trigger('click')
    await flushPromises()
    expect(attivo().hasAttribute('data-rimborso-incerto')).toBe(true)
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(false)
    net.on(RIMB, jsonResponse(200, []), jsonResponse(200, []))
    await bottone('Verifica nei miei rimborsi').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-rimborso-incerto]').text()).toContain("non prova che l'invio non sia stato registrato")
    expect(wrapper.find('#motivazione-rimborso').exists()).toBe(true)
    expect(net.callsTo(CHIEDI())).toHaveLength(1)

    // Riconciliazione dall'elenco rimborsi: la richiesta risulta registrata.
    net.on(RIMB, jsonResponse(200, []))
    await vai(router, '/paziente/rimborsi')
    expect(wrapper.find('[data-rimborso-incerto]').exists()).toBe(true)
    net.on(RIMB, jsonResponse(200, [rimborso(31)]))
    await bottone("Verifica nell'elenco").trigger('click')
    await flushPromises()
    expect(main().text()).toContain('risulta una richiesta di rimborso, stato «Richiesto, in attesa di decisione»')
    expect(wrapper.find('[data-rimborso-incerto]').exists()).toBe(false)
    expect(wrapper.findAll('[data-rimborso]')).toHaveLength(1)
    expect(net.callsTo(CHIEDI())).toHaveLength(1)
  })
})

describe('/paziente/rimborsi', () => {
  it('ordine del backend, motivazione, APPROVATO distinto da RIMBORSATO, link alla prenotazione', async () => {
    net.on(RIMB, jsonResponse(200, [
      rimborso(3, { stato: 'APPROVATO', verificatoIl: '2026-10-18T09:00:00', motivazione: 'Medico assente' }),
      rimborso(2, { stato: 'RIMBORSATO', idPrenotazione: 14, idPagamento: 8, verificatoIl: '2026-10-18T09:00:00' }),
      rimborso(1, { stato: 'RESPINTO', idPrenotazione: 15, idPagamento: 9 }),
    ]))
    await mountApp('/paziente/rimborsi')
    const voci = wrapper.findAll('[data-rimborso]')
    expect(voci.map((v) => v.get('h3').text())).toEqual(['Prenotazione n. 12', 'Prenotazione n. 14', 'Prenotazione n. 15'])
    expect(voci[0].get('[data-campo="stato-rimborso"]').text()).toBe('Approvato')
    expect(voci[0].get('[data-campo="nota-stato"]').text()).toBe('La richiesta è stata approvata: questo non significa che il denaro sia già stato restituito. La restituzione dell’importo sarà gestita dal centro medico al di fuori di MedBook.')
    expect(voci[1].get('[data-campo="nota-stato"]').text()).toBe('La restituzione risulta eseguita.')
    expect(voci[0].get('[data-campo="motivazione"]').text()).toBe('Medico assente')
    expect(voci[1].find('a').attributes('href')).toBe('/paziente/prenotazioni/14')
    expect(main().text()).toContain('non una restituzione già eseguita')
  })

  it('vuoto, errore e recupero manuale', async () => {
    net.on(RIMB, jsonResponse(503, { error: 'x' }))
    await mountApp('/paziente/rimborsi')
    expect(wrapper.get('[role="alert"]').text()).toContain('Non è stato possibile caricare i rimborsi')
    net.on(RIMB, jsonResponse(200, []))
    await bottone('Riprova').trigger('click')
    await flushPromises()
    expect(main().text()).toContain('Non hai richieste di rimborso.')
    expect(net.callsTo(RIMB)).toHaveLength(2)
  })

  it('area paziente: link «I miei rimborsi»; accesso negato senza profilo paziente', async () => {
    await mountApp('/area')
    expect(wrapper.findAll('main a').map((a) => a.attributes('href'))).toContain('/paziente/rimborsi')
    wrapper.unmount()
    const { router } = await mountApp('/paziente/rimborsi', meOk(utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' })))
    expect(router.currentRoute.value.name).toBe('accesso-negato')
    expect(net.callsTo(RIMB)).toHaveLength(0)
  })
})

describe('dettaglio: referto', () => {
  const effettuata = () => confermata(12, { stato: 'EFFETTUATA' })
  async function apri(meta) {
    return dettaglio([pagamento(5)], effettuata(), () => net.on(REF(), meta))
  }

  it('404 sui metadati: «Referto non disponibile», prenotazione e pagamenti visibili', async () => {
    await apri(jsonResponse(404, { error: 'Referto non trovato.' }))
    expect(wrapper.get('[data-referto-assente]').text()).toBe('Referto non disponibile.')
    expect(sezione('referto').find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[data-prenotazione]').text()).toContain('Effettuata')
    expect(wrapper.findAll('[data-pagamento]')).toHaveLength(1)
  })

  it('errore tecnico distinto dall\'assenza, con recupero manuale', async () => {
    await apri(jsonResponse(500, { error: 'x' }))
    expect(sezione('referto').get('[role="alert"]').text()).toContain('Non è stato possibile verificare la disponibilità del referto')
    expect(wrapper.find('[data-referto-assente]').exists()).toBe(false)
    expect(wrapper.findAll('[data-pagamento]')).toHaveLength(1)
    net.on(REF(), jsonResponse(200, referto()))
    await sezione('referto').findAll('button').find((b) => b.text() === 'Riprova').trigger('click')
    await flushPromises()
    expect(bottone('Scarica il referto (PDF)')).toBeDefined()
  })

  it('download PDF: nome file sicuro, object URL revocato dopo l\'avvio', async () => {
    await apri(jsonResponse(200, referto({ nomeFile: '../referto sintetico.pdf' })))
    expect(sezione('referto').text()).toContain('Referto caricato il domenica 18/10/2026, 11:30')
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:referto')
    const revoca = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clic = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { clic.push(this.download) })
    net.on(DOC(), pdfResponse())
    await bottone('Scarica il referto (PDF)').trigger('click')
    await flushPromises()
    expect(clic).toEqual(['referto sintetico.pdf'])
    expect(wrapper.get('[data-avviso-referto] p').text()).toBe('Download del referto avviato.')
    expect(wrapper.get('[data-avviso-referto] [aria-label="Chiudi avviso"]').exists()).toBe(true)
    expect(document.activeElement).not.toBe(wrapper.get('[data-avviso-referto]').element)
    expect(revoca).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('[data-avviso-referto]').exists()).toBe(false)
    expect(revoca).not.toHaveBeenCalled()
    vi.advanceTimersByTime(5000)
    expect(revoca).toHaveBeenCalledWith('blob:referto')
  })

  it.each([
    ['404', () => jsonResponse(404, { error: 'Referto non trovato.' }), 'non è disponibile'],
    ['non PDF', () => jsonResponse(200, { finto: true }), 'non è un PDF valido'],
    ['rete', 'rete', 'Download non riuscito'],
  ])('errore di download %s: messaggio, nessun nuovo tentativo automatico', async (_, risposta, testo) => {
    await apri(jsonResponse(200, referto()))
    const crea = vi.spyOn(URL, 'createObjectURL')
    net.on(DOC(), risposta)
    await bottone('Scarica il referto (PDF)').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-avviso-referto]').text()).toContain(testo)
    expect(crea).not.toHaveBeenCalled()
    expect(net.callsTo(DOC())).toHaveLength(1)
    expect(bottone('Scarica il referto (PDF)').attributes('disabled')).toBeUndefined()
  })
})
