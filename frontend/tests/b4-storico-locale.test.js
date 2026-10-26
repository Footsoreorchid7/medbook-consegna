import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parsePagamento } from '../src/api/pagamenti.js'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { confermata, fallito, pagamento } from './b4-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

// Rifiuto prudenziale di pagaOnline con uno storico locale positivo, separato dai riscontri
// condivisi: solo letture autorevoli ordinate ed esiti certi li aggiornano. Dati sintetici.
const ME = 'GET /api/auth/me'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = 'GET /api/prenotazioni/12/pagamenti'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

const pren = () => parseAppuntamento(confermata(12))
const ATTIVO = () => [parsePagamento(pagamento(9), 12)]
const NEGATIVO = () => [parsePagamento(fallito(4), 12)]
/** Copia profonda dello stato condiviso dei riscontri, per confronti prima/dopo. */
const fotografia = (store) => JSON.parse(JSON.stringify({ ambiti: store.state.ambitiPagamento, rimborsi: store.state.ambitiRimborso, globale: store.state.rimborsiApplicatoGlobale }))

async function autenticata() {
  net.on(ME, meOk(utente()))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: prenotazioniStore(session) }
}

describe('pagaOnline: storico locale positivo', () => {
  it('lettura recente negativa applicata, vecchia positiva ignorata, storico locale positivo: rifiuto senza fetch e stato invariato', async () => {
    const { store } = await autenticata()
    const vecchia = store.inizioLettura()
    const recente = store.inizioLettura()
    // 1. Lettura autorevole recente negativa.
    store.registraStorico(12, NEGATIVO(), recente)
    // 2. Vecchia risposta positiva ignorata.
    store.registraStorico(12, ATTIVO(), vecchia)
    expect(store.pagamentoBloccato(12)).toBe(false)
    const prima = fotografia(store)
    const prossimoTicket = recente.n + 1
    // 3. Chiamata con lo storico locale positivo della vecchia risposta: rifiutata prima di fetch.
    expect(await store.pagaOnline(pren(), ATTIVO())).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(0)
    expect(fotografia(store)).toEqual(prima)
    expect(store.pagamentoBloccato(12)).toBe(false)
    // Orologio non avanzato dal controllo locale.
    expect(store.inizioLettura().n).toBe(prossimoTicket)
    // Una chiamata con dati validi procede senza blocchi artificiali.
    net.on(PAGA, jsonResponse(201, fallito(10)))
    expect((await store.pagaOnline(pren(), NEGATIVO())).kind).toBe('fallito')
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('rifiuto locale senza alcun riscontro precedente: nessuna mutazione condivisa', async () => {
    const { store } = await autenticata()
    const prima = fotografia(store)
    const ticket = store.inizioLettura()
    expect(await store.pagaOnline(pren(), ATTIVO())).toEqual({ kind: 'bloccata' })
    expect(fotografia(store)).toEqual(prima)
    expect(store.state.ambitiPagamento).toEqual([])
    expect(store.inizioLettura().n).toBe(ticket.n + 1)
    expect(net.callsTo(PAGA)).toHaveLength(0)
  })

  it('riscontro condiviso positivo autentico: continua a bloccare anche con storico locale vuoto', async () => {
    const { store } = await autenticata()
    store.registraStorico(12, ATTIVO(), store.inizioLettura())
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(net.callsTo(PAGA)).toHaveLength(0)
  })

  it('barriera di un 201/PAGATO autentico: blocca anche con storico locale vuoto', async () => {
    const { store } = await autenticata()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    expect((await store.pagaOnline(pren(), [])).kind).toBe('pagato')
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })
})

describe('vista con dati autorevoli validi', () => {
  async function mountApp(session) {
    const router = createAppRouter({ session, history: createMemoryHistory() })
    router.push('/paziente/prenotazioni/12')
    await router.isReady()
    wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
    await flushPromises()
  }

  it('un rifiuto locale altrove non blocca artificialmente il dettaglio con storico autorevole senza pagamenti attivi', async () => {
    const { session, store } = await autenticata()
    // Un altro chiamante usa uno storico locale positivo (ad esempio vecchio): rifiuto.
    expect(await store.pagaOnline(pren(), ATTIVO())).toEqual({ kind: 'bloccata' })
    net.on(LISTA, jsonResponse(200, [confermata(12)])).on(PAG, jsonResponse(200, [fallito(4)]))
    await mountApp(session)
    expect(wrapper.find('[data-attivo-non-mostrato]').exists()).toBe(false)
    const paga = wrapper.findAll('main button').find((b) => b.text() === 'Paga online (simulato)')
    expect(paga.attributes('disabled')).toBeUndefined()
    expect(net.callsTo(PAGA)).toHaveLength(0)
  })
})
