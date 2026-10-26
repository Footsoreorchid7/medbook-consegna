import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed } from 'vue'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseMedicoDesk, parseRichiesta, parseRimborsoDesk, parseSegnalazione } from '../src/api/desk.js'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { testoVerifica } from '../src/desk/formato.js'
import { deskStore } from '../src/desk/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { configurato, slot } from './b6-fixtures.js'
import {
  CONFIGURA, DECIDI, DISPONIBILITA, MEDICI, MEDICO, OP, OPERATORE, PRESTAZIONI, RICHIESTE, RIMBORSI, SEGNALAZIONI, SOSTITUISCI, STANZE,
  decisione, desk, dettaglio, gestione, medicoDesk, prestazione, richiesta, rimborsoDesk, segnalazione, segnalazioneRichiesta, stanzaDesk,
} from './b7-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Riconciliazione della sostituzione e invalidazione reattiva degli elenchi.
// elenchi, recupero del caricamento dei medici.

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const GENERICA = "l'operazione non risulta eseguita"
const sg = (d) => parseSegnalazione(d)
/** Segnalazione CONFERMATA con medico effettivo (e indisponibile) `id`. */
const conEffettivo = (idPrenotazione, id, extra = {}) => segnalazione(idPrenotazione, {
  idMedicoEffettivo: id, nomeMedicoEffettivo: 'Medico', cognomeMedicoEffettivo: `Sintetico${id}`, ...extra,
})
const sostituzioneOk = (id, sostituto) => jsonResponse(200, gestione(id, 'CONFERMATA', { idMedicoEffettivo: sostituto, indisponibilitaSegnalata: false }))

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function avvia() {
  net.on(ME, meOk(desk()))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: deskStore(session) }
}

async function mountApp(path) {
  net.on(ME, meOk(desk()))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store: deskStore(session) }
}
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const avviso = () => wrapper.get('[data-avviso-desk]').text()
const riga = (sel, id) => wrapper.get(`${sel}[data-id="${id}"]`)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length
const invalidato = () => wrapper.find('[data-elenco-invalidato]').exists()

// ---------------------------------------------------------------- 1. riconciliazione della sostituzione

describe('riconciliazione della sostituzione', () => {
  it('incerta, poi nuova segnalazione del sostituto: riscontro "medico-sostituto", messaggio sul medico corrente, nessuna deduzione', async () => {
    const { store } = await avvia()
    net.on(SOSTITUISCI(7), 'rete')
    expect((await store.sostituisci(sg(segnalazioneRichiesta(7)), 9)).kind).toBe('incerto')
    net.on(SEGNALAZIONI, jsonResponse(200, [conEffettivo(7, 9)]))
    const x = store.incerta('prenotazione:7')
    const esito = await store.verifica('prenotazione:7')
    expect(esito).toMatchObject({ kind: 'verificata', esito: 'medico-sostituto' })
    const testo = testoVerifica(x, esito.esito, esito.voce)
    expect(testo).toContain('risulta assegnata a Medico Sintetico9, il sostituto richiesto')
    expect(testo).toContain('È una nuova segnalazione, distinta dal tentativo verificato')
    expect(testo).toContain('non stabilisce con certezza chi abbia eseguito la sostituzione')
    expect(testo).not.toContain('non risulta')
    // La nuova segnalazione si gestisce solo con una nuova scelta: nessun invio automatico.
    expect(store.bloccata('prenotazione:7')).toBe(false)
    expect(net.callsTo(SOSTITUISCI(7))).toHaveLength(1)
  })

  it('medico corrente ancora uguale al precedente, altro medico, assenza: riscontri distinti e non attribuiti', async () => {
    const { store } = await avvia()
    const casi = [
      [[segnalazione(7)], 'invariata', ['stesso medico indisponibile (Elena Sintetica)', 'non mostra la sostituzione richiesta']],
      [[conEffettivo(7, 6)], 'cambiata', ['medico Medico Sintetico6', '(Elena Sintetica, «Confermata»)', 'sia dal sostituto richiesto', 'non è possibile stabilire quali operazioni siano avvenute']],
      [[segnalazioneRichiesta(7)], 'cambiata', ['stato «Richiesta»']],
      [[], 'assente', ['non è più tra le segnalazioni pendenti', 'non è possibile stabilire quale operazione sia avvenuta']],
    ]
    for (const [elenco, atteso, frasi] of casi) {
      net.on(SOSTITUISCI(7), 'rete')
      expect((await store.sostituisci(sg(segnalazione(7)), 9)).kind).toBe('incerto')
      net.on(SEGNALAZIONI, jsonResponse(200, elenco))
      const x = store.incerta('prenotazione:7')
      const esito = await store.verifica('prenotazione:7')
      expect(esito.esito).toBe(atteso)
      const testo = testoVerifica(x, esito.esito, esito.voce)
      for (const f of frasi) expect(testo).toContain(f)
      expect(testo).not.toContain(GENERICA)
      store.chiudiIncerta('prenotazione:7', store.state.owner)
      expect(store.bloccata('prenotazione:7')).toBe(false)
    }
    expect(net.callsTo(SOSTITUISCI(7))).toHaveLength(4)
  })

  it('annullamento di una segnalata: invariata o cambiata, senza la frase generica', async () => {
    const { store } = await avvia()
    for (const [elenco, atteso, frase] of [[[segnalazione(7)], 'invariata', "non mostra l'annullamento richiesto"], [[conEffettivo(7, 6)], 'cambiata', 'diversi da quelli rilevati']]) {
      net.on(OP(7, 'annulla'), 'rete')
      await store.annullaSegnalata(sg(segnalazione(7)))
      net.on(SEGNALAZIONI, jsonResponse(200, elenco))
      const x = store.incerta('prenotazione:7')
      const esito = await store.verifica('prenotazione:7')
      expect(esito.esito).toBe(atteso)
      expect(testoVerifica(x, esito.esito, esito.voce)).toContain(frase)
    }
  })

  it('nessun messaggio di verifica usa la frase generica; richieste e rimborsi indicano lo stato riscontrato', () => {
    const x = (verificaSu, tipo, riepilogo = {}) => ({ verificaSu, tipo, idRisorsa: 5, riepilogo })
    const testi = [
      testoVerifica(x('richieste', 'conferma'), 'presente'), testoVerifica(x('richieste', 'rifiuta'), 'presente'),
      testoVerifica(x('rimborsi', 'approva'), 'presente'),
      testoVerifica(x('disponibilita:4', 'configura', { prestazione: { nome: 'P' }, stanza: { nome: 'S' } }), 'non-configurata'),
    ]
    for (const t of testi) expect(t).not.toContain(GENERICA)
    expect(testi[0]).toContain('nello stato «Richiesta»: la conferma non risulta applicata')
    expect(testi[1]).toContain('il rifiuto non risulta applicato')
    expect(testi[2]).toContain('nello stato «Richiesto»')
  })

  it('vista: sostituzione riuscita, poi il sostituto si segnala indisponibile: messaggio coerente, nuova riga gestita solo con nuova scelta', async () => {
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazioneRichiesta(7)]))
    await mountApp('/desk/segnalazioni')
    net.on('GET /api/medici?stato=ATTIVO&idPrestazione=2', jsonResponse(200, [medicoDesk(9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico'))
    await wrapper.get('#sostituto-7').setValue('9')
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Prosegui'))
    // Il sostituto segnala a sua volta: la rilettura riporta la prenotazione con il sostituto indisponibile.
    net.on(SOSTITUISCI(7), sostituzioneOk(7, 9)).on(SEGNALAZIONI, jsonResponse(200, [conEffettivo(7, 9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Conferma sostituzione'))
    expect(avviso()).toContain('confermata con il medico Medico Sintetico9; la segnalazione di Elena Sintetica è risolta')
    expect(avviso()).toContain('si tratta di una nuova segnalazione')
    expect(riga('[data-segnalazione]', 7).get('[data-campo="indisponibile"]').text()).toBe('Medico Sintetico9')
    expect(riga('[data-segnalazione]', 7).get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(net.callsTo(SOSTITUISCI(7))).toHaveLength(1)
    // Nuovo invio solo con scelta e conferma esplicite, sui dati riletti: indisponibile 9, stato CONFERMATA.
    net.on('GET /api/medici?stato=ATTIVO&idPrestazione=2', jsonResponse(200, [medicoDesk(4), medicoDesk(9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico'))
    expect(wrapper.get('#sostituto-7').findAll('option').map((o) => o.attributes('value'))).toEqual(['', '4'])
    await wrapper.get('#sostituto-7').setValue('4')
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Prosegui'))
    net.on(SOSTITUISCI(7), sostituzioneOk(7, 4)).on(SEGNALAZIONI, jsonResponse(200, []))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Conferma sostituzione'))
    expect(JSON.parse(net.callsTo(SOSTITUISCI(7))[1].body)).toEqual({ idOperatore: OPERATORE, idMedicoSostituto: 4, statoAtteso: 'CONFERMATA', idMedicoIndisponibile: 9 })
  })

  it('vista: sostituzione incerta, verifica con nuova segnalazione del sostituto: messaggio sul medico corrente, nessun retry', async () => {
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazioneRichiesta(7)]))
    await mountApp('/desk/segnalazioni')
    net.on('GET /api/medici?stato=ATTIVO&idPrestazione=2', jsonResponse(200, [medicoDesk(9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico'))
    await wrapper.get('#sostituto-7').setValue('9')
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Prosegui'))
    net.on(SOSTITUISCI(7), 'rete')
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Conferma sostituzione'))
    expect(avviso()).toContain("Non è stato possibile stabilire l'esito")
    net.on(SEGNALAZIONI, jsonResponse(200, [conEffettivo(7, 9)]), jsonResponse(200, [conEffettivo(7, 9)]))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(avviso()).toContain('risulta assegnata a Medico Sintetico9, il sostituto richiesto')
    expect(avviso()).toContain('nuova segnalazione, distinta dal tentativo verificato')
    expect(avviso()).not.toContain(GENERICA)
    expect(wrapper.find('[data-esito-incerto]').exists()).toBe(false)
    expect(riga('[data-segnalazione]', 7).get('[data-campo="indisponibile"]').text()).toBe('Medico Sintetico9')
    expect(net.callsTo(SOSTITUISCI(7))).toHaveLength(1)
  })
})

// ---------------------------------------------------------------- 2. invalidazione reattiva

describe('invalidazione reattiva degli elenchi', () => {
  it('store: validità reattiva per elenco; letture successive alla barriera restano valide; ambiti rispettati', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    expect(store.applica('rimborsi', [], t, true)).toBe(true)
    expect(store.applica('richieste', [], t, true)).toBe(true)
    const rimborsi = computed(() => store.valida('rimborsi', t))
    const richieste = computed(() => store.valida('richieste', t))
    expect([rimborsi.value, richieste.value]).toEqual([true, true])
    net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO')))
    await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5)))
    expect([rimborsi.value, richieste.value]).toEqual([false, true])
    const dopo = store.inizioLettura()
    expect(store.applica('rimborsi', [], dopo, true)).toBe(true)
    expect(store.valida('rimborsi', dopo)).toBe(true)
    // Le barriere non cambiano per accettare dati superati.
    expect(store.applica('rimborsi', [], t, true)).toBe(false)
    // Un rifiuto certo (409) pone la barriera; un esito incerto no.
    net.on(OP(12, 'rifiuta'), jsonResponse(409, { error: 'x' }))
    await store.decidiRichiesta('rifiuta', parseRichiesta(richiesta(12)))
    expect(richieste.value).toBe(false)
    const t2 = store.inizioLettura()
    store.applica('rimborsi', [], t2, true)
    net.on(DECIDI(6, 'approva'), 'rete')
    await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(6)))
    expect(store.valida('rimborsi', t2)).toBe(true)
  })

  it('rimborso pendente → uscita/rientro → GET completato → successo vecchia istanza: azioni non riabilitate, nessuna seconda POST', async () => {
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5), rimborsoDesk(6)]))
    const { router } = await mountApp('/desk/rimborsi?stato=RICHIESTO')
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Approva'))
    const post = net.trattieni(DECIDI(5, 'approva'))
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Approva'))
    await router.push('/area')
    await flushPromises()
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5), rimborsoDesk(6)]))
    await router.push('/desk/rimborsi?stato=RICHIESTO')
    await flushPromises()
    expect(riga('[data-rimborso-desk]', 5).text()).toContain('Decisione in corso o da verificare')
    expect(bottoneIn(riga('[data-rimborso-desk]', 6), 'Approva').attributes('disabled')).toBeUndefined()
    post.resolve(jsonResponse(200, decisione(5, 'APPROVATO')))
    await flushPromises()
    // inCorso rimosso nello stesso completamento della barriera: le azioni restano sospese.
    for (const id of [5, 6]) {
      for (const testo of ['Approva', 'Respingi']) expect(bottoneIn(riga('[data-rimborso-desk]', id), testo).attributes('disabled')).toBeDefined()
    }
    expect(invalidato()).toBe(true)
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(false)
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Approva'))
    expect(riga('[data-rimborso-desk]', 5).find('[data-conferma]').exists()).toBe(false)
    expect(scritture()).toBe(1)
    // Aggiornamento manuale: lettura successiva alla barriera, idoneità ripristinata.
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(6)]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(invalidato()).toBe(false)
    expect(wrapper.find('[data-rimborso-desk][data-id="5"]').exists()).toBe(false)
    expect(bottoneIn(riga('[data-rimborso-desk]', 6), 'Approva').attributes('disabled')).toBeUndefined()
    expect(scritture()).toBe(1)
  })

  it('richieste: conferma già aperta chiusa da un esito certo di un\'altra istanza; dati visibili con avviso; rilettura manuale', async () => {
    net.on(RICHIESTE, jsonResponse(200, [richiesta(12), richiesta(13)])).on(MEDICI, jsonResponse(200, [])).on(PRESTAZIONI, jsonResponse(200, []))
    const { store } = await mountApp('/desk/richieste')
    await click(bottoneIn(riga('[data-richiesta]', 13), 'Annulla'))
    expect(riga('[data-richiesta]', 13).find('[data-conferma]').exists()).toBe(true)
    // Altra pagina desk: rifiuto certo della 12.
    net.on(OP(12, 'rifiuta'), jsonResponse(200, gestione(12, 'RIFIUTATA')))
    await store.decidiRichiesta('rifiuta', parseRichiesta(richiesta(12)))
    await flushPromises()
    expect(riga('[data-richiesta]', 13).find('[data-conferma]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('#titolo-richieste').element)
    expect(invalidato()).toBe(true)
    expect(wrapper.findAll('[data-richiesta]')).toHaveLength(2)
    expect(bottoneIn(riga('[data-richiesta]', 13), 'Annulla').attributes('disabled')).toBeDefined()
    net.on(RICHIESTE, jsonResponse(200, [richiesta(13)]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(invalidato()).toBe(false)
    expect(bottoneIn(riga('[data-richiesta]', 13), 'Annulla').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(OP(13, 'annulla'))).toHaveLength(0)
  })

  it('segnalazioni: scelta del sostituto chiusa da un esito certo sulle prenotazioni; un esito sui rimborsi non la tocca', async () => {
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazione(7), segnalazione(8)]))
    const { store } = await mountApp('/desk/segnalazioni')
    net.on('GET /api/medici?stato=ATTIVO&idPrestazione=2', jsonResponse(200, [medicoDesk(9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico'))
    net.on(DECIDI(5, 'respingi'), jsonResponse(200, decisione(5, 'RESPINTO')))
    await store.decidiRimborso('respingi', parseRimborsoDesk(rimborsoDesk(5)))
    await flushPromises()
    expect(wrapper.find('[data-scelta-sostituto]').exists()).toBe(true)
    expect(invalidato()).toBe(false)
    await wrapper.get('#sostituto-7').setValue('9')
    // Altra istanza: annullamento certo della 8 (barriera su richieste e segnalazioni).
    net.on(OP(8, 'annulla'), jsonResponse(200, gestione(8, 'ANNULLATA')))
    await store.annullaSegnalata(sg(segnalazione(8)))
    await flushPromises()
    expect(wrapper.find('[data-scelta-sostituto]').exists()).toBe(false)
    expect(invalidato()).toBe(true)
    expect(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico').attributes('disabled')).toBeDefined()
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazione(7)]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(SOSTITUISCI(7))).toHaveLength(0)
  })

  it('configurazione: conferma chiusa da un esito certo sullo stesso medico; un altro medico non la tocca', async () => {
    net.on(MEDICI, jsonResponse(200, [medicoDesk(4), medicoDesk(5)]))
    const { store } = await mountApp('/desk/disponibilita')
    net.on(DISPONIBILITA(4), jsonResponse(200, [slot(9, '2026-11-02T09:00:00'), slot(10, '2026-11-02T10:00:00')]))
      .on(MEDICO(4), jsonResponse(200, dettaglio(4, [prestazione(2)]))).on(STANZE, jsonResponse(200, [stanzaDesk(3)]))
    await wrapper.get('#desk-medico').setValue('4')
    await flushPromises()
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Configura'))
    await wrapper.get('#prestazione-9').setValue('2')
    await wrapper.get('#stanza-9').setValue('3')
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Prosegui'))
    expect(riga('[data-slot-desk]', 9).find('[data-conferma="configura"]').exists()).toBe(true)
    const p2 = { idPrestazione: 2, nome: 'Visita sintetica' }
    const st3 = { idStanza: 3, nome: 'Ambulatorio 1' }
    // Altra istanza, medico 5: ambito diverso.
    net.on(CONFIGURA(20), jsonResponse(200, configurato(20, '2026-11-02T09:00:00', { idMedico: 5 })))
    await store.configura(parseDisponibilita(slot(20, '2026-11-02T09:00:00', { idMedico: 5 }), 5), parseMedicoDesk(medicoDesk(5)), p2, st3)
    await flushPromises()
    expect(riga('[data-slot-desk]', 9).find('[data-conferma="configura"]').exists()).toBe(true)
    // Altra istanza, stesso medico: la conferma aperta si chiude.
    net.on(CONFIGURA(10), jsonResponse(200, configurato(10, '2026-11-02T10:00:00')))
    await store.configura(parseDisponibilita(slot(10, '2026-11-02T10:00:00'), 4), parseMedicoDesk(medicoDesk(4)), p2, st3)
    await flushPromises()
    expect(riga('[data-slot-desk]', 9).find('[data-conferma="configura"]').exists()).toBe(false)
    expect(invalidato()).toBe(true)
    expect(bottoneIn(riga('[data-slot-desk]', 9), 'Configura').attributes('disabled')).toBeDefined()
    net.on(DISPONIBILITA(4), jsonResponse(200, [slot(9, '2026-11-02T09:00:00'), configurato(10, '2026-11-02T10:00:00')]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(bottoneIn(riga('[data-slot-desk]', 9), 'Configura').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(CONFIGURA(9))).toHaveLength(0)
  })

  it('una rilettura avviata dopo la barriera non viene invalidata', async () => {
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5), rimborsoDesk(6)]))
    await mountApp('/desk/rimborsi?stato=RICHIESTO')
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Respingi'))
    net.on(DECIDI(5, 'respingi'), jsonResponse(200, decisione(5, 'RESPINTO'))).on(RIMBORSI, jsonResponse(200, [rimborsoDesk(6)]))
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Respingi'))
    expect(invalidato()).toBe(false)
    expect(bottoneIn(riga('[data-rimborso-desk]', 6), 'Respingi').attributes('disabled')).toBeUndefined()
  })
})

// ---------------------------------------------------------------- 3. recupero del caricamento dei medici

describe('caricamento dei medici nella configurazione', () => {
  it('errore iniziale, «Riprova» con doppio clic: un solo GET, recupero riuscito e focus sul campo', async () => {
    net.on(MEDICI, jsonResponse(500, { error: 'x' }))
    await mountApp('/desk/disponibilita')
    expect(wrapper.get('[data-errore-medici]').text()).toContain("Non è stato possibile leggere l'elenco dei medici.")
    expect(net.callsTo(MEDICI)).toHaveLength(1)
    const h = net.trattieni(MEDICI)
    const riprova = bottoneIn(wrapper.get('[data-errore-medici]'), 'Riprova')
    riprova.element.focus()
    await riprova.trigger('click')
    await riprova.trigger('click')
    expect(wrapper.text()).toContain('Caricamento dei medici…')
    expect(net.callsTo(MEDICI)).toHaveLength(2)
    h.resolve(jsonResponse(200, [medicoDesk(4)]))
    await flushPromises()
    expect(wrapper.get('#desk-medico').findAll('option').map((o) => o.attributes('value'))).toEqual(['', '4'])
    expect(document.activeElement).toBe(wrapper.get('#desk-medico').element)
  })

  it('nuovo errore dopo «Riprova»: di nuovo il pulsante, nessun tentativo automatico', async () => {
    net.on(MEDICI, 'rete', jsonResponse(503, { error: 'x' }))
    await mountApp('/desk/disponibilita')
    await click(bottoneIn(wrapper.get('[data-errore-medici]'), 'Riprova'))
    expect(wrapper.find('[data-errore-medici]').exists()).toBe(true)
    await flushPromises()
    expect(net.callsTo(MEDICI)).toHaveLength(2)
  })

  it('risposta tardiva dopo un cambio di sessione: scartata, vale il caricamento della nuova epoca', async () => {
    net.on(MEDICI, 'rete')
    const { session } = await mountApp('/desk/disponibilita')
    // Risposta del recupero che ignora l'interruzione e arriva dopo quella della nuova epoca.
    let rilascia
    net.on(MEDICI, () => new Promise((resolve) => { rilascia = resolve }))
    await click(bottoneIn(wrapper.get('[data-errore-medici]'), 'Riprova'))
    const nuovo = net.trattieni(MEDICI)
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    await flushPromises()
    expect(net.callsTo(MEDICI)).toHaveLength(3)
    nuovo.resolve(jsonResponse(200, [medicoDesk(5)]))
    await flushPromises()
    rilascia(jsonResponse(200, [medicoDesk(9)]))
    await flushPromises()
    expect(wrapper.find('[data-errore-medici]').exists()).toBe(false)
    expect(wrapper.get('#desk-medico').findAll('option').map((o) => o.attributes('value'))).toEqual(['', '5'])
  })

  it('smontaggio durante il recupero: nessun effetto e nessun nuovo tentativo', async () => {
    net.on(MEDICI, 'rete')
    const { router } = await mountApp('/desk/disponibilita')
    const h = net.trattieni(MEDICI)
    await click(bottoneIn(wrapper.get('[data-errore-medici]'), 'Riprova'))
    expect(net.callsTo(MEDICI)).toHaveLength(2)
    await router.push('/area')
    await flushPromises()
    // La dashboard avvia una propria GET; il completamento del recupero smontato non ne aggiunge altre.
    const lettureDopoNavigazione = net.callsTo(MEDICI).length
    expect(lettureDopoNavigazione).toBe(3)
    h.resolve?.(jsonResponse(200, [medicoDesk(4)]))
    await flushPromises()
    expect(net.callsTo(MEDICI)).toHaveLength(lettureDopoNavigazione)
    expect(wrapper.find('#desk-medico').exists()).toBe(false)
  })
})
