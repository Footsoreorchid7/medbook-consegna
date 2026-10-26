import { todayLocal } from '../src/utils/datetime.js'
import { periodoMese } from '../src/medico/calendario.js'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseAppuntamentoAgenda } from '../src/api/medico.js'
import { medicoStore } from '../src/medico/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { AGENDA, MEDICO, REFERTO, SEGNALA, VISITA, agendaPeriodo, agendaGiorno, effettuata, medico, registrata, voce } from './agenda-medico-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Blocchi reciproci visita/segnalazione, recenza dei riscontri e cambio file.
// durante il controllo PDF. Dati sintetici.
const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const GIORNO = agendaGiorno('2026-10-19')
const a = (d) => parseAppuntamentoAgenda(d, MEDICO)
const del20 = (id, extra = {}) => voce(id, { dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', ...extra })
const segnalate = (ids) => jsonResponse(200, { idPrenotazioni: ids, indisponibilitaSegnalata: true })

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function avvia() {
  net.on(ME, meOk(medico()))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: medicoStore(session) }
}

/** Lettura «della vista»: avviata ora, applicata quando lo decide il test. */
const lettura = (store) => {
  const ticket = store.inizioLettura()
  return (voci, periodo = { da: '2026-10-19', a: '2026-10-19' }) => store.applicaAgenda(voci.map(a), ticket, periodo)
}

describe('blocco reciproco visita/segnalazione', () => {
  it('registrazione in volo: segnalazione rifiutata prima di fetch, anche come parte di un gruppo', async () => {
    const { store } = await avvia()
    const h = net.trattieni(VISITA(12))
    const visita = store.registraVisita(a(voce(12)))
    expect(store.visitaImpedisceSegnalazione(12)).toBe(true)
    expect(await store.segnala([a(voce(13)), a(voce(12))])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(SEGNALA)).toHaveLength(0)
    h.resolve(jsonResponse(200, registrata(12)))
    await visita
    // Registrata: con i dati precedenti (CONFERMATA) la segnalazione resta rifiutata.
    expect(await store.segnala([a(voce(12))])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(SEGNALA)).toHaveLength(0)
  })

  it('registrazione incerta: segnalazione bloccata finché la verifica non la risolve; appuntamenti indipendenti liberi', async () => {
    const { store } = await avvia()
    net.on(VISITA(12), 'rete')
    await store.registraVisita(a(voce(12)))
    expect(await store.segnala([a(voce(12))])).toEqual({ kind: 'bloccata' })
    net.on(SEGNALA, segnalate([13]))
    expect((await store.segnala([a(voce(13))])).kind).toBe('segnalate')
    net.on(GIORNO, jsonResponse(200, [voce(12)]))
    expect((await store.verificaVisita(12)).appuntamento.stato).toBe('CONFERMATA')
    net.on(SEGNALA, segnalate([12]))
    expect((await store.segnala([a(voce(12))])).kind).toBe('segnalate')
    expect(JSON.parse(net.callsTo(SEGNALA)[1].body).idPrenotazioni).toEqual([12])
  })

  it('segnalazione in volo o incerta: registrazione rifiutata prima di fetch solo per gli ID del gruppo', async () => {
    const { store } = await avvia()
    const h = net.trattieni(SEGNALA)
    const segnalazione = store.segnala([a(voce(12)), a(voce(13))])
    expect(await store.registraVisita(a(voce(12)))).toEqual({ kind: 'bloccata' })
    net.on(VISITA(14), jsonResponse(200, registrata(14)))
    expect((await store.registraVisita(a(voce(14)))).kind).toBe('registrata')
    h.resolve(jsonResponse(500, { error: 'x' }))
    expect((await segnalazione).kind).toBe('incerto')
    expect(await store.registraVisita(a(voce(13)))).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(VISITA(12)).length + net.callsTo(VISITA(13)).length).toBe(0)
    // Recupero manuale: la verifica resta disponibile e sblocca gli appuntamenti con riscontro.
    net.on(GIORNO, jsonResponse(200, [voce(12), voce(13)]))
    expect((await store.verificaSegnalazione(store.state.segnalazioniIncerte[0].chiave)).pendenti).toEqual([])
    net.on(VISITA(13), jsonResponse(200, registrata(13)))
    expect((await store.registraVisita(a(voce(13)))).kind).toBe('registrata')
  })
})

describe('recenza delle verifiche', () => {
  async function visitaIncerta(store, id = 12) {
    net.on(VISITA(id), 'rete')
    await store.registraVisita(a(voce(id)))
  }

  it('visita: verifica A vecchia (CONFERMATA) contro agenda B recente (EFFETTUATA): vale B', async () => {
    const { store } = await avvia()
    await visitaIncerta(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaVisita(12)
    lettura(store)([effettuata(12)])
    h.resolve(jsonResponse(200, [voce(12)]))
    const esito = await verifica
    expect(esito.appuntamento.stato).toBe('EFFETTUATA')
    expect(store.visitaRegistrata(12)).toBe(true)
  })

  it('visita: ordine inverso, A vecchia (EFFETTUATA) contro B recente (CONFERMATA): nessuna registrazione dedotta', async () => {
    const { store } = await avvia()
    await visitaIncerta(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaVisita(12)
    lettura(store)([voce(12)])
    h.resolve(jsonResponse(200, [effettuata(12)]))
    expect((await verifica).appuntamento.stato).toBe('CONFERMATA')
    expect(store.visitaRegistrata(12)).toBe(false)
  })

  it('visita: assenza recente nello stesso giorno contro presenza vecchia: assente, blocco mantenuto', async () => {
    const { store } = await avvia()
    await visitaIncerta(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaVisita(12)
    lettura(store)([voce(7)])
    h.resolve(jsonResponse(200, [effettuata(12)]))
    expect((await verifica).kind).toBe('assente')
    expect(store.visitaBloccata(12)).toBe(true)
  })

  it('periodi diversi e default vuoto: nessuna assenza conclusiva, vale la verifica', async () => {
    const { store } = await avvia()
    await visitaIncerta(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaVisita(12)
    lettura(store)([del20(5)], { da: '2026-10-20', a: '2026-10-20' })
    lettura(store)([], null)
    h.resolve(jsonResponse(200, [effettuata(12)]))
    expect((await verifica).appuntamento.stato).toBe('EFFETTUATA')
  })

  it('una lettura più vecchia applicata dopo non contraddice quella più recente', async () => {
    const { store } = await avvia()
    const vecchia = lettura(store)
    const recente = lettura(store)
    recente([voce(12, { indisponibilitaSegnalata: true })])
    expect(store.segnalazioneNota(12)).toBe(true)
    expect(vecchia([voce(12)]).size).toBe(0)
    expect(store.segnalazioneNota(12)).toBe(true)
  })

  it('segnalazione: A vecchia (segnalata) contro B recente (flag assente): vale B, senza dire che l\'invio è fallito', async () => {
    const { store } = await avvia()
    net.on(SEGNALA, 'rete')
    await store.segnala([a(voce(12)), a(voce(13))])
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaSegnalazione(store.state.segnalazioniIncerte[0].chiave)
    lettura(store)([voce(12), voce(13, { indisponibilitaSegnalata: true })])
    h.resolve(jsonResponse(200, [voce(12, { indisponibilitaSegnalata: true }), voce(13)]))
    const esito = await verifica
    expect(esito.esiti).toMatchObject({ 12: { kind: 'non-segnalata' }, 13: { kind: 'segnalata' } })
    expect(esito.pendenti).toEqual([])
  })

  it('segnalazione: assenza recente non dedotta, presenza recente in un altro periodo non usata', async () => {
    const { store } = await avvia()
    net.on(SEGNALA, 'rete')
    await store.segnala([a(voce(12)), a(del20(13))])
    const h = net.trattieni(`${AGENDA}&dataDa=2026-10-19&dataA=2026-10-20`)
    const verifica = store.verificaSegnalazione(store.state.segnalazioniIncerte[0].chiave)
    lettura(store)([voce(7)])
    h.resolve(jsonResponse(200, [voce(12, { indisponibilitaSegnalata: true }), del20(13, { indisponibilitaSegnalata: true })]))
    const esito = await verifica
    expect(esito.esiti).toMatchObject({ 12: { kind: 'assente' }, 13: { kind: 'segnalata' } })
    expect(store.segnalazioneBloccata(12)).toBe(true)
  })

  it('isolamento di sessione: verifica attraversata da una rotazione ignorata, recupero manuale', async () => {
    const { session, store } = await avvia()
    await visitaIncerta(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaVisita(12)
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    h.resolve(jsonResponse(200, [effettuata(12)]))
    expect((await verifica).kind).toBe('obsoleto')
    expect(store.visitaIncerta(12)).toMatchObject({ verifica: null })
    expect(store.visitaRegistrata(12)).toBe(false)
    net.on(GIORNO, jsonResponse(200, [voce(12)]))
    expect((await store.verificaVisita(12)).appuntamento.stato).toBe('CONFERMATA')
  })

  it('cambio di identità: verifica tardiva e ticket precedenti senza effetti', async () => {
    const { session, store } = await avvia()
    net.on(SEGNALA, 'rete')
    await store.segnala([a(voce(12))])
    const vecchia = lettura(store)
    const h = net.trattieni(GIORNO)
    const verifica = store.verificaSegnalazione(store.state.segnalazioniIncerte[0].chiave)
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    net.on(LOGIN, meOk(medico({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, [voce(12, { indisponibilitaSegnalata: true })]))
    expect((await verifica).kind).toBe('obsoleto')
    expect(vecchia([voce(12, { indisponibilitaSegnalata: true })]).size).toBe(0)
    expect(store.state).toMatchObject({ owner: 8, ambiti: [], segnalazioniIncerte: [] })
  })
})

// ---------------------------------------------------------------- vista

async function mountAgenda(voci) {
  net.on(ME, meOk(medico()))
  net.on(AGENDA, jsonResponse(200, voci))
  const iniziale = periodoMese(todayLocal().slice(0, 7))
  net.on(agendaPeriodo(iniziale.dataDa, iniziale.dataA), jsonResponse(200, []))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push('/medico/agenda')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  // Mantiene il contesto giornaliero esplicito dei casi ; la nuova apertura mensile ha test propri.
  const filtriAgenda = wrapper.find('main .filtri-avanzati form')
  if (filtriAgenda.exists()) { await filtriAgenda.trigger('submit'); await flushPromises() }
  // Questi casi esercitano la modalità multipla preesistente, ora esplicita.
  const multipla = wrapper.find('main input[type="checkbox"]')
  if (multipla.exists()) await multipla.setValue(true)
  return { session, router, store: medicoStore(session) }
}
const riga = (id) => wrapper.get(`[data-appuntamento][data-id="${id}"]`)
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const casella = (id) => riga(id).find('input[type="checkbox"]')

describe('blocco reciproco nell\'interfaccia', () => {
  it('visita incerta: nessuna selezione per la segnalazione, anche in una nuova istanza; altri appuntamenti liberi', async () => {
    const { router } = await mountAgenda([voce(1), voce(2)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), 'rete')
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    expect(casella(1).exists()).toBe(false)
    expect(casella(2).exists()).toBe(true)
    await router.push('/area')
    await flushPromises()
    net.on(AGENDA, jsonResponse(200, [voce(1), voce(2)]))
    const meseRientro = periodoMese(todayLocal().slice(0, 7))
    net.on(agendaPeriodo(meseRientro.dataDa, meseRientro.dataA), jsonResponse(200, []))
    await router.push('/medico/agenda')
    await flushPromises()
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(casella(1).exists()).toBe(false)
    expect(bottoneIn(wrapper.get('[data-visita-incerta]'), "Verifica nell'agenda")).toBeDefined()
  })

  it('segnalazione in volo e incerta: registrazione sospesa per il gruppo, verifica disponibile', async () => {
    await mountAgenda([voce(1), voce(2), voce(3)])
    await casella(1).setValue(true)
    await casella(2).setValue(true)
    await click(bottoneIn(wrapper.get('main'), 'Segnala indisponibilità (2)'))
    const h = net.trattieni(SEGNALA)
    await click(bottoneIn(wrapper.get('main'), 'Conferma segnalazione'))
    h.resolve(jsonResponse(502, { error: 'x' }))
    await flushPromises()
    for (const id of [1, 2]) {
      expect(bottoneIn(riga(id), 'Registra visita')).toBeUndefined()
      expect(riga(id).find('[data-visita-sospesa]').exists()).toBe(true)
    }
    expect(bottoneIn(riga(3), 'Registra visita')).toBeDefined()
    expect(bottoneIn(wrapper.get('[data-segnalazione-incerta]'), "Verifica nell'agenda")).toBeDefined()
    expect(net.calls.filter((c) => c.url.endsWith('/registra-visita'))).toHaveLength(0)
  })

  it('il testo del flag assente non afferma che l\'invio sia fallito', async () => {
    await mountAgenda([voce(1)])
    await casella(1).setValue(true)
    await click(bottoneIn(wrapper.get('main'), 'Segnala indisponibilità (1)'))
    net.on(SEGNALA, 'rete')
    await click(bottoneIn(wrapper.get('main'), 'Conferma segnalazione'))
    net.on(GIORNO, jsonResponse(200, [voce(1)])).on(AGENDA, jsonResponse(200, [voce(1)]))
    await click(bottoneIn(wrapper.get('[data-segnalazione-incerta]'), "Verifica nell'agenda"))
    const testo = wrapper.get('[data-segnalazione-incerta]').text()
    expect(testo).toContain('potrebbe non essere stata registrata oppure essere già stata gestita dalla segreteria')
  })
})

describe('cambio file durante il controllo PDF', () => {
  /** File sintetico con lettura della firma trattenuta. */
  function fileTrattenuto(name, testa = '%PDF-') {
    let rilascia
    const pronto = new Promise((r) => { rilascia = r })
    const file = { name, size: 2048, type: 'application/pdf', slice: () => ({ arrayBuffer: () => pronto.then(() => new TextEncoder().encode(testa).buffer) }) }
    return { file, rilascia }
  }
  const sezione = () => riga(1).get('[data-referto-caricamento="1"]')
  const scegli = async (file) => {
    const input = sezione().get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: file ? [file] : [], configurable: true })
    await input.trigger('change')
  }
  const prosegui = () => click(bottoneIn(sezione(), 'Prosegui'))
  async function apri() {
    const ctx = await mountAgenda([effettuata(1)])
    await click(bottoneIn(sezione(), 'Carica referto'))
    return ctx
  }

  it('successo vecchio dopo il cambio file: nessuna conferma per A; B controllato da sé', async () => {
    const { store } = await apri()
    const A = fileTrattenuto('a.pdf')
    await scegli(A.file)
    await prosegui()
    expect(bottoneIn(sezione(), 'Controllo del file')).toBeDefined()
    const B = fileTrattenuto('b.pdf', '<html')
    await scegli(B.file)
    expect(bottoneIn(sezione(), 'Prosegui').attributes('disabled')).toBeUndefined()
    const input = sezione().get('input[type="file"]').element
    input.focus()
    A.rilascia()
    await flushPromises()
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
    expect(document.activeElement).toBe(input)
    await prosegui()
    B.rilascia()
    await flushPromises()
    expect(sezione().text()).toContain('non è un documento PDF')
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
    expect(JSON.stringify(store.state)).not.toContain('a.pdf')
  })

  it('successo vecchio mentre è in corso il controllo del nuovo file: vale solo il controllo di B', async () => {
    await apri()
    const A = fileTrattenuto('a.pdf')
    const B = fileTrattenuto('b.pdf', '<html')
    await scegli(A.file)
    await prosegui()
    await scegli(B.file)
    await prosegui()
    A.rilascia()
    await flushPromises()
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
    expect(bottoneIn(sezione(), 'Controllo del file')).toBeDefined()
    B.rilascia()
    await flushPromises()
    expect(sezione().text()).toContain('non è un documento PDF')
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
  })

  it('errore vecchio dopo il cambio file: nessun errore mostrato; B arriva alla conferma con il suo nome', async () => {
    await apri()
    const A = fileTrattenuto('a.pdf', '<html')
    await scegli(A.file)
    await prosegui()
    const B = fileTrattenuto('b.pdf')
    await scegli(B.file)
    A.rilascia()
    await flushPromises()
    expect(sezione().find('.field-error').exists()).toBe(false)
    await prosegui()
    B.rilascia()
    await flushPromises()
    expect(sezione().get('[data-campo="nome-file"]').text()).toBe('b.pdf')
    expect(net.calls.some((c) => c.url.endsWith('/referto'))).toBe(false)
  })

  it('cancellazione del file durante il controllo: stato utilizzabile, nessun effetto tardivo', async () => {
    await apri()
    const A = fileTrattenuto('a.pdf')
    await scegli(A.file)
    await prosegui()
    await scegli(null)
    A.rilascia()
    await flushPromises()
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
    await prosegui()
    expect(sezione().text()).toContain('Scegli il documento PDF del referto.')
  })

  it('A → B → A: il controllo iniziato su A non vale per la nuova scelta di A', async () => {
    await apri()
    const A = fileTrattenuto('a.pdf')
    const B = fileTrattenuto('b.pdf')
    await scegli(A.file)
    await prosegui()
    await scegli(B.file)
    await scegli(A.file)
    A.rilascia()
    await flushPromises()
    expect(sezione().find('[data-conferma-referto]').exists()).toBe(false)
    await prosegui()
    await flushPromises()
    expect(sezione().get('[data-campo="nome-file"]').text()).toBe('a.pdf')
    expect(net.callsTo(REFERTO(1))).toHaveLength(0)
  })
})
