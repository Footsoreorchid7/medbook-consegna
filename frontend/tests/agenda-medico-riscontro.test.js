import { beforeEach, describe, expect, it } from 'vitest'
import { watch } from 'vue'
import { parseAppuntamentoAgenda } from '../src/api/medico.js'
import { medicoStore } from '../src/medico/store.js'
import { createSession } from '../src/session/session.js'
import { AGENDA, MEDICO, SEGNALA, VISITA, agendaGiorno, effettuata, medico, voce } from './agenda-medico-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Esito di una verifica costruito sullo stato corrente: «applicata in precedenza» non significa
// «ancora corrente». Interleaving riprodotto: A applicata → B applicata → costruzione esito A.
const ME = 'GET /api/auth/me'
const GIORNO = agendaGiorno('2026-10-19')
const a = (d) => parseAppuntamentoAgenda(d, MEDICO)
const segnalata = (id) => voce(id, { indisponibilitaSegnalata: true })

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia() {
  net.on(ME, meOk(medico()))
  const session = createSession()
  await session.ensureBootstrap()
  return medicoStore(session)
}

/**
 * Prepara la lettura B (avviata dopo la verifica A) e la applica nel microtask che segue
 * l'applicazione di A all'ambito `id`: prima che la verifica costruisca il proprio esito.
 * @returns {string[]} sequenza osservata degli eventi
 */
function dopoA(store, id, vociB, periodo = { da: '2026-10-19', a: '2026-10-19' }) {
  const ticketB = store.inizioLettura()
  const eventi = []
  const prima = store.state.ambiti.find((x) => x.idPrenotazione === id)?.applicato ?? 0
  const stop = watch(() => store.state.ambiti.find((x) => x.idPrenotazione === id)?.applicato ?? 0, (n) => {
    if (n <= prima || eventi.length > 0) return
    eventi.push('A applicata')
    stop()
    queueMicrotask(() => {
      store.applicaAgenda(vociB.map(a), ticketB, periodo)
      eventi.push('B applicata')
    })
  }, { flush: 'sync' })
  return eventi
}

async function segnalazioneIncerta(store, voci) {
  net.on(SEGNALA, 'rete')
  await store.segnala(voci.map(a))
  return store.state.segnalazioniIncerte.at(-1).chiave
}

describe('verifica della segnalazione: A applicata, poi B, poi esito', () => {
  it('A flag false, B true: esito «segnalata» dal riscontro corrente, pendenti coerenti', async () => {
    const store = await avvia()
    const chiave = await segnalazioneIncerta(store, [voce(12)])
    net.on(GIORNO, jsonResponse(200, [voce(12)]))
    const verifica = store.verificaSegnalazione(chiave)
    const eventi = dopoA(store, 12, [segnalata(12)])
    const esito = await verifica
    expect(eventi).toEqual(['A applicata', 'B applicata'])
    expect(esito.esiti[12]).toMatchObject({ kind: 'segnalata' })
    expect(esito.pendenti).toEqual([])
    expect(store.state.segnalazioniIncerte[0].esiti[12].kind).toBe('segnalata')
    expect(store.segnalazioneNota(12)).toBe(true)
  })

  it('A true, B false: esito «non-segnalata» dal riscontro corrente', async () => {
    const store = await avvia()
    const chiave = await segnalazioneIncerta(store, [voce(12)])
    net.on(GIORNO, jsonResponse(200, [segnalata(12)]))
    const verifica = store.verificaSegnalazione(chiave)
    const eventi = dopoA(store, 12, [voce(12)])
    const esito = await verifica
    expect(eventi).toEqual(['A applicata', 'B applicata'])
    expect(esito.esiti[12]).toMatchObject({ kind: 'non-segnalata' })
    expect(store.segnalazioneNota(12)).toBe(false)
  })

  it('assenza recente nel periodo coperto: «assente», pendente mantenuto e bloccato', async () => {
    const store = await avvia()
    const chiave = await segnalazioneIncerta(store, [voce(12), voce(13)])
    net.on(GIORNO, jsonResponse(200, [segnalata(12), segnalata(13)]))
    const verifica = store.verificaSegnalazione(chiave)
    const eventi = dopoA(store, 12, [segnalata(13)])
    const esito = await verifica
    expect(eventi).toEqual(['A applicata', 'B applicata'])
    expect(esito.esiti).toMatchObject({ 12: { kind: 'assente' }, 13: { kind: 'segnalata' } })
    expect(esito.pendenti).toEqual([12])
    expect(store.segnalazioneBloccata(12)).toBe(true)
    expect(store.segnalazioneBloccata(13)).toBe(false)
  })

  it('B di un altro periodo applicata dopo A: non conta, vale A', async () => {
    const store = await avvia()
    const chiave = await segnalazioneIncerta(store, [voce(12)])
    net.on(GIORNO, jsonResponse(200, [segnalata(12)]))
    const verifica = store.verificaSegnalazione(chiave)
    const eventi = dopoA(store, 12, [], { da: '2026-10-20', a: '2026-10-20' })
    const esito = await verifica
    expect(eventi).toEqual(['A applicata', 'B applicata'])
    expect(esito.esiti[12]).toMatchObject({ kind: 'segnalata' })
  })

  it('verifica su più letture: B applicata tra la prima lettura di A e la costruzione dell\'esito', async () => {
    const store = await avvia()
    const app = (id, data, extra = {}) => voce(id, { dataOraInizio: `${data}T09:00:00`, dataOraFine: `${data}T10:00:00`, ...extra })
    const chiave = await segnalazioneIncerta(store, [app(12, '2026-10-01'), app(13, '2026-11-05')])
    net.on(agendaGiorno('2026-10-01'), jsonResponse(200, [app(12, '2026-10-01')]))
    const seconda = net.trattieni(agendaGiorno('2026-11-05'))
    const verifica = store.verificaSegnalazione(chiave)
    const ticketB = store.inizioLettura()
    await new Promise((r) => setTimeout(r, 0))
    // A ha già applicato la prima lettura (flag false per la 12); ora B, più recente.
    expect(store.state.ambiti.find((x) => x.idPrenotazione === 12).segnalata).toBe(false)
    store.applicaAgenda([a(app(12, '2026-10-01', { indisponibilitaSegnalata: true }))], ticketB, { da: '2026-10-01', a: '2026-10-01' })
    seconda.resolve(jsonResponse(200, [app(13, '2026-11-05', { indisponibilitaSegnalata: true })]))
    const esito = await verifica
    expect(esito.esiti).toMatchObject({ 12: { kind: 'segnalata' }, 13: { kind: 'segnalata' } })
    expect(esito.pendenti).toEqual([])
  })

  it('percorso ordinario senza sovrapposizione: vale la lettura della verifica', async () => {
    const store = await avvia()
    const chiave = await segnalazioneIncerta(store, [voce(12), voce(13)])
    net.on(GIORNO, jsonResponse(200, [voce(12), segnalata(13)]))
    const esito = await store.verificaSegnalazione(chiave)
    expect(esito.esiti).toMatchObject({ 12: { kind: 'non-segnalata' }, 13: { kind: 'segnalata' } })
  })
})

describe('verifica della visita: A applicata, poi B, poi esito', () => {
  async function visitaIncerta(store) {
    net.on(VISITA(12), 'rete')
    await store.registraVisita(a(voce(12)))
  }

  it('A CONFERMATA, B EFFETTUATA: esito dal riscontro corrente, registrazione risultante', async () => {
    const store = await avvia()
    await visitaIncerta(store)
    net.on(GIORNO, jsonResponse(200, [voce(12)]))
    const verifica = store.verificaVisita(12)
    const eventi = dopoA(store, 12, [effettuata(12)])
    const esito = await verifica
    expect(eventi).toEqual(['A applicata', 'B applicata'])
    expect(esito.appuntamento.stato).toBe('EFFETTUATA')
    expect(store.visitaRegistrata(12)).toBe(true)
    expect(store.visitaIncerta(12)).toBeNull()
  })

  it('A EFFETTUATA, B CONFERMATA: nessuna registrazione dedotta dai dati vecchi', async () => {
    const store = await avvia()
    await visitaIncerta(store)
    net.on(GIORNO, jsonResponse(200, [effettuata(12)]))
    const verifica = store.verificaVisita(12)
    dopoA(store, 12, [voce(12)])
    const esito = await verifica
    expect(esito.appuntamento.stato).toBe('CONFERMATA')
    expect(store.visitaRegistrata(12)).toBe(false)
  })

  it('assenza recente nel giorno coperto: «assente», blocco mantenuto', async () => {
    const store = await avvia()
    await visitaIncerta(store)
    net.on(GIORNO, jsonResponse(200, [effettuata(12)]))
    const verifica = store.verificaVisita(12)
    dopoA(store, 12, [voce(7)])
    expect((await verifica).kind).toBe('assente')
    expect(store.visitaIncerta(12)).toMatchObject({ verifica: 'assente' })
    expect(store.visitaBloccata(12)).toBe(true)
  })

  it('percorso ordinario senza sovrapposizione: vale la lettura della verifica', async () => {
    const store = await avvia()
    await visitaIncerta(store)
    net.on(GIORNO, jsonResponse(200, [effettuata(12)]))
    expect((await store.verificaVisita(12)).appuntamento.stato).toBe('EFFETTUATA')
    expect(net.callsTo(AGENDA)).toHaveLength(0)
  })
})
