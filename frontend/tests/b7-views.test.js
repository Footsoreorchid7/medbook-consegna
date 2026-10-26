import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { deskStore } from '../src/desk/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { configurato, slot } from './b6-fixtures.js'
import {
  CONFIGURA, DECIDI, DISPONIBILITA, MEDICI, MEDICO, OP, OPERATORE, PRESTAZIONI, RICHIESTE, RIMBORSI, SEGNALAZIONI, SOSTITUISCI, STANZE,
  admin, decisione, desk, dettaglio, gestione, medicoDesk, prestazione, richiesta, rimborsoDesk, segnalazione, segnalazioneRichiesta, stanzaDesk,
} from './b7-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountApp(path, user = desk()) {
  net.on(ME, meOk(user))
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
const attivo = () => document.activeElement
const avviso = () => wrapper.get('[data-avviso-desk]').text()
const riga = (sel, id) => wrapper.get(`${sel}[data-id="${id}"]`)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length

describe('accesso', () => {
  it('pagine desk solo per OPERATORE_DESK: l\'amministratore e il paziente ricevono accesso negato', async () => {
    for (const user of [admin(), utente()]) {
      const { router } = await mountApp('/desk/richieste', user)
      expect(router.currentRoute.value.name).toBe('accesso-negato')
      wrapper.unmount()
    }
    expect(net.calls.some((c) => c.url.startsWith('/api/prenotazioni/richieste'))).toBe(false)
    wrapper = null
  })

  it('link nell\'area riservata solo per il desk', async () => {
    await mountApp('/area')
    expect(wrapper.findAll('main a').map((a) => a.attributes('href')).filter((h) => h.startsWith('/desk'))).toEqual(
      ['/desk/richieste', '/desk/segnalazioni', '/desk/rimborsi?stato=RICHIESTO', '/desk/disponibilita', '/desk/pazienti', '/desk/prenotazioni', '/desk/medici'])
    wrapper.unmount()
    await mountApp('/area', admin())
    expect(wrapper.find('main a[href="/desk/richieste"]').exists()).toBe(false)
  })
})

describe('richieste', () => {
  async function apriRichieste(voci) {
    net.on(RICHIESTE, jsonResponse(200, voci)).on(MEDICI, jsonResponse(200, [medicoDesk(4)])).on(PRESTAZIONI, jsonResponse(200, [prestazione(2)]))
    return mountApp('/desk/richieste')
  }

  it.each([['conferma', 'azione-conferma'], ['rifiuta', 'azione-rifiuta'], ['annulla', null]])('colore di %s preservato anche nella conferma senza invio anticipato', async (tipo, colore) => {
    await apriRichieste([richiesta(12)])
    const r = riga('[data-richiesta]', 12)
    await click(r.get(`[data-origine="${tipo}-12"]`))
    const b = r.get(`[data-conferma="${tipo}"] button`)
    if (colore) expect(b.classes()).toContain(colore)
    else { expect(b.classes()).not.toContain('azione-conferma'); expect(b.classes()).not.toContain('azione-rifiuta') }
    expect(net.calls.filter(c => c.method !== 'GET')).toHaveLength(0)
  })

  it('campi, ordine del backend e segnalazione: nessuna conferma ordinaria per le richieste segnalate', async () => {
    await apriRichieste([richiesta(12), richiesta(13, { indisponibilitaSegnalata: true, idStanza: null, nomeStanza: null })])
    expect(wrapper.findAll('[data-richiesta]').map((w) => w.attributes('data-id'))).toEqual(['12', '13'])
    const r12 = riga('[data-richiesta]', 12)
    expect(r12.get('h3').text()).toBe('Paola Prova12 — Prenotazione n. 12')
    expect(r12.get('[data-origine="conferma-12"]').classes()).toContain('azione-conferma')
    expect(r12.get('[data-origine="rifiuta-12"]').classes()).toContain('azione-rifiuta')
    expect(r12.get('[data-origine="annulla-12"]').classes()).toContain('secondary')
    expect(r12.get('[data-campo="medico"]').text()).toBe('Elena Sintetica')
    expect(r12.get('[data-campo="prezzo"]').text()).toMatch(/^80,00\s€$/)
    expect(riga('[data-richiesta]', 13).get('[data-campo="stanza"]').text()).toBe('Non assegnata')
    expect(riga('[data-richiesta]', 13).get('[data-campo="indisponibilita"]').text()).toContain('gestiscila da')
    expect(bottoneIn(riga('[data-richiesta]', 13), 'Conferma')).toBeUndefined()
    expect(bottoneIn(r12, 'Conferma')).toBeDefined()
  })

  it('filtri: query del contratto; data non valida senza richiesta; risposta fuori ordine scartata', async () => {
    await apriRichieste([])
    const vecchia = net.trattieni('GET /api/prenotazioni/richieste?medicoId=4')
    await wrapper.get('#filtro-medico').setValue('4')
    await wrapper.get('main form').trigger('submit')
    await flushPromises()
    await wrapper.get('#filtro-medico').setValue('')
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#richieste-data').setValue('2026-11-02')
    net.on('GET /api/prenotazioni/richieste?data=2026-11-02', jsonResponse(200, [richiesta(20)]))
    await wrapper.get('main form').trigger('submit')
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [richiesta(30)]))
    await flushPromises()
    expect(wrapper.findAll('[data-richiesta]').map((w) => w.attributes('data-id'))).toEqual(['20'])
    await wrapper.get('input[value="intervallo"]').setValue()
    await wrapper.get('#richieste-dal').setValue('2026-11-05')
    await wrapper.get('#richieste-al').setValue('2026-11-01')
    const n = net.calls.length
    await wrapper.get('main form').trigger('submit')
    await flushPromises()
    expect(net.calls.length).toBe(n)
    expect(wrapper.text()).toContain('La data di fine non può precedere quella di inizio.')
  })

  it('conferma: riepilogo, focus, Indietro al pulsante d\'origine; invio con idOperatore e rilettura', async () => {
    await apriRichieste([richiesta(12)])
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Conferma'))
    expect(attivo().textContent).toBe('Confermi la richiesta?')
    expect(riga('[data-richiesta]', 12).get('[data-conferma="conferma"]').text()).toContain('Paola Prova12')
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Indietro'))
    expect(attivo()).toBe(bottoneIn(riga('[data-richiesta]', 12), 'Conferma').element)
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Conferma'))
    net.on(OP(12, 'conferma'), jsonResponse(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 4 }))).on(RICHIESTE, jsonResponse(200, []))
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Conferma richiesta'))
    expect(JSON.parse(net.callsTo(OP(12, 'conferma'))[0].body)).toEqual({ idOperatore: OPERATORE })
    expect(avviso()).toContain('Richiesta n. 12 confermata')
    expect(wrapper.findAll('[data-richiesta]')).toHaveLength(0)
  })

  it('esito incerto: blocco anche dopo uscita e rientro; scomparsa non attribuita a un\'operazione', async () => {
    const { router } = await apriRichieste([richiesta(12)])
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Rifiuta'))
    net.on(OP(12, 'rifiuta'), 'rete')
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Rifiuta richiesta'))
    expect(avviso()).toContain("Non è stato possibile stabilire l'esito")
    await router.push('/area')
    await flushPromises()
    net.on(RICHIESTE, jsonResponse(200, [richiesta(12)])).on(MEDICI, jsonResponse(200, [])).on(PRESTAZIONI, jsonResponse(200, []))
    await router.push('/desk/richieste')
    await flushPromises()
    expect(bottoneIn(riga('[data-richiesta]', 12), 'Annulla')).toBeUndefined()
    net.on(RICHIESTE, jsonResponse(200, []), jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(avviso()).toContain('non è possibile stabilire se sia stata confermata, rifiutata o annullata')
    expect(net.callsTo(OP(12, 'rifiuta'))).toHaveLength(1)
  })

  it('una conferma aperta si chiude quando parte una rilettura', async () => {
    await apriRichieste([richiesta(12), richiesta(13)])
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Rifiuta'))
    net.on(OP(12, 'rifiuta'), 'rete')
    await click(bottoneIn(riga('[data-richiesta]', 12), 'Rifiuta richiesta'))
    await click(bottoneIn(riga('[data-richiesta]', 13), 'Annulla'))
    expect(riga('[data-richiesta]', 13).find('[data-conferma]').exists()).toBe(true)
    net.on(RICHIESTE, jsonResponse(200, [richiesta(12), richiesta(13)]))
    const rilettura = net.trattieni(RICHIESTE)
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(riga('[data-richiesta]', 13).find('[data-conferma]').exists()).toBe(false)
    rilettura.resolve(jsonResponse(200, [richiesta(12), richiesta(13)]))
    await flushPromises()
    expect(net.callsTo(OP(13, 'annulla'))).toHaveLength(0)
  })
})

describe('segnalazioni', () => {
  it('sostituzione: candidati attivi e abilitati senza l\'indisponibile, conferma completa, payload dalla risorsa', async () => {
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazioneRichiesta(7)]))
    await mountApp('/desk/segnalazioni')
    expect(riga('[data-segnalazione]', 7).get('h3').text()).toBe('Prenotazione n. 7 · Paola Prova7')
    expect(wrapper.text()).not.toContain('dato non fornito da questo elenco')
    expect(riga('[data-segnalazione]', 7).get('h3').text()).toContain('Prenotazione n. 7')
    net.on('GET /api/medici?stato=ATTIVO&idPrestazione=2', jsonResponse(200, [medicoDesk(4), medicoDesk(9)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Sostituisci medico'))
    const select = wrapper.get('#sostituto-7')
    expect(attivo()).toBe(select.element)
    expect(select.findAll('option').map((o) => o.attributes('value'))).toEqual(['', '9'])
    expect(riga('[data-segnalazione]', 7).text()).toContain("L'elenco non garantisce la disponibilità")
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Prosegui'))
    expect(wrapper.text()).toContain('Scegli il medico sostituto.')
    await select.setValue('9')
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Prosegui'))
    const conferma = riga('[data-segnalazione]', 7).get('[data-conferma="sostituisci"]')
    expect(conferma.text()).toContain('Paziente: Paola Prova7')
    expect(conferma.text()).toContain('Medico indisponibile: Elena Sintetica')
    expect(conferma.get('[data-campo="sostituto"]').text()).toBe('Medico Sintetico9')
    net.on(SOSTITUISCI(7), jsonResponse(200, gestione(7, 'CONFERMATA', { idMedicoEffettivo: 9, indisponibilitaSegnalata: false }))).on(SEGNALAZIONI, jsonResponse(200, []))
    await click(bottoneIn(conferma, 'Conferma sostituzione'))
    expect(JSON.parse(net.callsTo(SOSTITUISCI(7))[0].body)).toEqual({ idOperatore: OPERATORE, idMedicoSostituto: 9, statoAtteso: 'RICHIESTA', idMedicoIndisponibile: 4 })
    expect(avviso()).toContain('confermata con il medico Medico Sintetico9')
  })

  it('annullamento con lo stato letto; segnalazione senza prestazione non sostituibile', async () => {
    net.on(SEGNALAZIONI, jsonResponse(200, [segnalazione(7), segnalazione(8, { idPrestazione: null, nomePrestazione: null })]))
    await mountApp('/desk/segnalazioni')
    expect(bottoneIn(riga('[data-segnalazione]', 8), 'Sostituisci')).toBeUndefined()
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Annulla prenotazione'))
    expect(riga('[data-segnalazione]', 7).get('[data-conferma]').text()).toContain('Paziente: Paola Prova7')
    net.on(OP(7, 'annulla'), jsonResponse(409, { error: 'Pagamenti associati impediscono l\'annullamento.' })).on(SEGNALAZIONI, jsonResponse(200, [segnalazione(7)]))
    await click(bottoneIn(riga('[data-segnalazione]', 7), 'Annulla prenotazione'))
    expect(JSON.parse(net.callsTo(OP(7, 'annulla'))[0].body)).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA' })
    expect(avviso()).toContain("Pagamenti associati impediscono l'annullamento.")
    expect(avviso()).toContain("L'elenco viene riletto")
  })
})

describe('rimborsi', () => {
  it('approvazione: avviso sul significato, corpo {}, successo e rilettura', async () => {
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
    await mountApp('/desk/rimborsi?stato=RICHIESTO')
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Approva'))
    expect(riga('[data-rimborso-desk]', 5).text()).toContain("L'approvazione non esegue la restituzione del denaro.")
    net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO'))).on(RIMBORSI, jsonResponse(200, []))
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Approva'))
    expect(JSON.parse(net.callsTo(DECIDI(5, 'approva'))[0].body)).toEqual({})
    expect(avviso()).toContain('non indica che il denaro sia già stato restituito')
  })

  it('decisione in volo → uscita/rientro → GET avviato → esito certo → vecchio GET: elenco non adottato', async () => {
    const { router } = await (async () => { net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)])); return mountApp('/desk/rimborsi?stato=RICHIESTO') })()
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Respingi'))
    const post = net.trattieni(DECIDI(5, 'respingi'))
    await click(bottoneIn(riga('[data-rimborso-desk]', 5), 'Respingi'))
    await router.push('/area')
    await flushPromises()
    const get = net.trattieni(RIMBORSI)
    await router.push('/desk/rimborsi?stato=RICHIESTO')
    await flushPromises()
    post.resolve(jsonResponse(200, decisione(5, 'RESPINTO')))
    await flushPromises()
    get.resolve(jsonResponse(200, [rimborsoDesk(5)]))
    await flushPromises()
    expect(wrapper.find('[data-rimborso-desk]').exists()).toBe(false)
    expect(wrapper.find('[data-elenco-superato]').exists()).toBe(true)
    net.on(RIMBORSI, jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-elenco-superato]'), "Aggiorna l'elenco"))
    expect(wrapper.text()).toContain('Nessuna richiesta di rimborso da decidere.')
    expect(scritture()).toBe(1)
  })
})

describe('configurazione delle disponibilità', () => {
  async function apriMedico(id, slots, prestazioni = [prestazione(2)]) {
    net.on(DISPONIBILITA(id), jsonResponse(200, slots)).on(MEDICO(id), jsonResponse(200, dettaglio(id, prestazioni))).on(STANZE, jsonResponse(200, [stanzaDesk(3)]))
    await wrapper.get('#desk-medico').setValue(String(id))
    await flushPromises()
  }

  it('solo prestazioni autorizzate; slot configurati o parziali senza azione; payload e conferma completa', async () => {
    net.on(MEDICI, jsonResponse(200, [medicoDesk(4), medicoDesk(5, { stato: 'INATTIVO' })]))
    await mountApp('/desk/disponibilita')
    await apriMedico(4, [slot(9, '2026-11-02T09:00:00'), configurato(10, '2026-11-02T10:00:00'), slot(11, '2026-11-02T11:00:00', { idStanza: 3, nomeStanza: 'A' })])
    expect(wrapper.get('[data-abilitazioni]').text()).toContain('Prestazione 2')
    expect(wrapper.find('[data-slot-desk][data-id="10"]').exists()).toBe(false)
    expect(bottoneIn(riga('[data-slot-desk]', 11), 'Configura')).toBeUndefined()
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Configura'))
    expect(attivo()).toBe(wrapper.get('#prestazione-9').element)
    expect(wrapper.get('#prestazione-9').findAll('option').map((o) => o.attributes('value'))).toEqual(['', '2'])
    await wrapper.get('#prestazione-9').setValue('2')
    await wrapper.get('#stanza-9').setValue('3')
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Prosegui'))
    const conferma = riga('[data-slot-desk]', 9).get('[data-conferma="configura"]')
    expect(conferma.text()).toContain('Medico: Medico Sintetico4')
    expect(conferma.get('[data-campo="stanza"]').text()).toBe('Stanza 3')
    net.on(CONFIGURA(9), jsonResponse(200, configurato(9, '2026-11-02T09:00:00', { idPrestazione: 2, nomePrestazione: 'Prestazione 2', idStanza: 3, nomeStanza: 'Stanza 3' })))
    net.on(DISPONIBILITA(4), jsonResponse(200, [configurato(9, '2026-11-02T09:00:00')]))
    await click(bottoneIn(conferma, 'Conferma configurazione'))
    expect(JSON.parse(net.callsTo(CONFIGURA(9))[0].body)).toEqual({ idPrestazione: 2, idStanza: 3 })
    expect(avviso()).toContain('configurata con Prestazione 2 e Stanza 3')
  })

  it('cambio di medico A → B → A durante una configurazione: nessun messaggio nel nuovo contesto, esito nello store', async () => {
    net.on(MEDICI, jsonResponse(200, [medicoDesk(4), medicoDesk(5)]))
    const { store } = await mountApp('/desk/disponibilita')
    await apriMedico(4, [slot(9, '2026-11-02T09:00:00')])
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Configura'))
    await wrapper.get('#prestazione-9').setValue('2')
    await wrapper.get('#stanza-9').setValue('3')
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Prosegui'))
    const h = net.trattieni(CONFIGURA(9))
    await click(bottoneIn(riga('[data-slot-desk]', 9), 'Conferma configurazione'))
    await apriMedico(5, [])
    await apriMedico(4, [slot(9, '2026-11-02T09:00:00')])
    h.resolve(jsonResponse(200, configurato(9, '2026-11-02T09:00:00')))
    await flushPromises()
    expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
    expect(net.callsTo(DISPONIBILITA(4))).toHaveLength(3) // lettura iniziale globale più due selezioni
    expect(store.bloccata('slot:9')).toBe(false)
    expect(store.state.incerte).toEqual([])
  })
})

describe('medici', () => {
  it('elenco con filtri e dettaglio con abilitazioni; ID non valido senza richiesta; 404', async () => {
    net.on(MEDICI, jsonResponse(200, [medicoDesk(4, { specializzazione: null })])).on(PRESTAZIONI, jsonResponse(200, [prestazione(2)]))
    const { router } = await mountApp('/desk/medici')
    expect(wrapper.get('[data-medico][data-id="4"]').text()).toContain('Non indicata')
    await wrapper.get('#medici-stato').setValue('INATTIVO')
    await wrapper.get('#medici-prestazione').setValue('2')
    net.on('GET /api/medici?stato=INATTIVO&idPrestazione=2', jsonResponse(200, []))
    await wrapper.get('main form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Nessun medico per i filtri scelti.')
    net.on(MEDICO(4), jsonResponse(200, dettaglio(4, [prestazione(2)], { numeroIscrizioneOrdine: null })))
    await router.push('/desk/medici/4')
    await flushPromises()
    expect(wrapper.get('[data-campo="iscrizione"]').text()).toBe('Non indicata')
    expect(wrapper.get('[data-abilitazioni]').text()).toContain('Prestazione 2')
    const n = net.calls.length
    await router.push('/desk/medici/04')
    await flushPromises()
    expect(net.calls.length).toBe(n)
    expect(wrapper.text()).toContain('Medico non trovato.')
    net.on(MEDICO(99), jsonResponse(404, { error: 'Risorsa non trovata.' }))
    await router.push('/desk/medici/99')
    await flushPromises()
    expect(wrapper.text()).toContain('Medico non trovato.')
  })
})

it('segnalazioni: contratto paziente mancante produce errore recuperabile, mai un falso elenco vuoto', async () => {
  const senzaPaziente = segnalazione(30, { idPaziente: null, nomePaziente: null, cognomePaziente: null })
  net.on(SEGNALAZIONI, jsonResponse(200, [senzaPaziente]))
  await mountApp('/desk/segnalazioni')
  expect(wrapper.get('main [role="alert"]').text()).toContain('Non è stato possibile caricare le segnalazioni')
  expect(wrapper.get('main').text()).not.toContain('Nessuna segnalazione pendente.')
  expect(wrapper.find('[data-segnalazione]').exists()).toBe(false)
  net.on(SEGNALAZIONI, jsonResponse(200, [segnalazione(30, {
    idPaziente: 11, nomePaziente: 'Anna', cognomePaziente: 'Fittizia',
  })]))
  await click(bottoneIn(wrapper, 'Riprova'))
  expect(riga('[data-segnalazione]', 30).get('h3').text()).toBe('Prenotazione n. 30 · Anna Fittizia')
  expect(wrapper.find('main [role="alert"]').exists()).toBe(false)
  expect(scritture()).toBe(0)
})

it('segnalazioni: un elenco vuoto precedente non nasconde una segnalazione letta al nuovo ingresso', async () => {
  net.on(SEGNALAZIONI, jsonResponse(200, []))
  const { router } = await mountApp('/desk/segnalazioni')
  expect(wrapper.get('main').text()).toContain('Nessuna segnalazione pendente.')
  await router.push('/area')
  await flushPromises()
  net.on(SEGNALAZIONI, jsonResponse(200, [segnalazione(30)]))
  const letturePrimaDelRientro = net.callsTo(SEGNALAZIONI).length
  await router.push('/desk/segnalazioni')
  await flushPromises()
  expect(riga('[data-segnalazione]', 30).get('h3').text()).toContain('Prenotazione n. 30')
  expect(net.callsTo(SEGNALAZIONI)).toHaveLength(letturePrimaDelRientro + 1)
  expect(wrapper.get('main').text()).not.toContain('Nessuna segnalazione pendente.')
  expect(scritture()).toBe(0)
})
