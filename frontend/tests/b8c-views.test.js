import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseRichiesta, parseRimborsoDesk, parseSegnalazione } from '../src/api/desk.js'
import { deskStore } from '../src/desk/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import {
  DECIDI, MEDICI, OP, OPERATORE, RICHIESTE, RIMBORSI, admin, desk, gestione, medicoDesk, richiesta, rimborsoDesk, segnalazione,
} from './b7-fixtures.js'
import {
  ELENCO, ESITO, GIORNO, INCASSO, annullataDaRichiesta, incasso, pagamentoDesk, pagatoOnline, pagatoSede, prenotazioneDesk, rimborsoAnnidato,
} from './b8-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
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
const scheda = (id) => wrapper.get(`[data-prenotazione-desk][data-id="${id}"]`)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length
const letture = () => net.calls.filter((c) => c.url.startsWith('/api/prenotazioni/desk')).map((c) => c.url)
const errore = (status, error = 'x') => jsonResponse(status, { error })

async function apri(user) {
  net.on(MEDICI, jsonResponse(200, [medicoDesk(4), medicoDesk(9)]))
  return mountApp('/desk/prenotazioni', user)
}
async function cerca({ data = GIORNO, medico = '' } = {}) {
  await wrapper.get('#prenotazioni-data').setValue(data)
  await wrapper.get('#prenotazioni-medico').setValue(medico)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}
/** Apre il dettaglio di ogni prenotazione mostrata: dati completi e operazioni sono lì. */
async function apriDettagli() {
  for (const b of wrapper.findAll('[data-dettagli][aria-expanded="false"]')) await click(b)
}
/** Pagina aperta con un elenco del giorno già letto e i dettagli aperti. */
async function conElenco(voci, opzioni = {}) {
  const ctx = await apri(opzioni.user)
  net.on(ELENCO(opzioni.query), jsonResponse(200, voci))
  await cerca(opzioni)
  await apriDettagli()
  return ctx
}

describe('accesso e periodo', () => {
  it('solo OPERATORE_DESK; nessuna lettura senza periodo scelto; link nell’area', async () => {
    for (const user of [admin(), utente()]) {
      const { router } = await mountApp('/desk/prenotazioni', user)
      expect(router.currentRoute.value.name).toBe('accesso-negato')
      wrapper.unmount()
    }
    wrapper = null
    await apri()
    expect(wrapper.get('[data-senza-periodo]').text()).toContain('Indica una data')
    expect(letture()).toEqual([])
    expect(wrapper.get('#prenotazioni-data').element.value).toBe('')
    wrapper.unmount()
    await mountApp('/area')
    expect(wrapper.findAll('main a').map((a) => a.attributes('href'))).toContain('/desk/prenotazioni')
  })

  it('validazione collegata ai campi e focus sul riepilogo; 31 giorni inclusi accettati, 32 no', async () => {
    await apri()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(attivo().getAttribute('role')).toBe('alert')
    expect(wrapper.get('#prenotazioni-data').attributes('aria-describedby')).toBe('errore-prenotazioni-data')
    await wrapper.findAll('input[name="periodo-prenotazioni"]')[1].setValue(true)
    await wrapper.get('#prenotazioni-dal').setValue('2026-11-01')
    await wrapper.get('#prenotazioni-al').setValue('2026-12-02')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#errore-prenotazioni-al').text()).toContain('al massimo 31 giorni')
    expect(letture()).toEqual([])
    net.on(ELENCO('dataDa=2026-11-01&dataA=2026-12-01&medicoId=9'), jsonResponse(200, []))
    await wrapper.get('#prenotazioni-al').setValue('2026-12-01')
    await wrapper.get('#prenotazioni-medico').setValue('9')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(letture()).toEqual(['/api/prenotazioni/desk?dataDa=2026-11-01&dataA=2026-12-01&medicoId=9'])
    expect(wrapper.text()).toContain('Nessuna prenotazione gestita nel periodo indicato.')
    expect(wrapper.text()).toContain('medico effettivo Medico Sintetico9')
  })

  it('errore di lettura con «Riprova»; vincoli orari e incasso prima della visita spiegati', async () => {
    await apri()
    net.on(ELENCO(), errore(500))
    await cerca()
    expect(wrapper.text()).toContain('Non è stato possibile caricare le prenotazioni')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await click(bottoneIn(wrapper, 'Riprova'))
    expect(wrapper.findAll('[data-prenotazione-desk]')).toHaveLength(1)
    const vincoli = wrapper.get('[data-vincoli]').text()
    expect(vincoli).toContain("solo prima dell'inizio")
    expect(vincoli).toContain('solo dopo la fine dello slot')
    expect(vincoli).toContain('prima che il medico registri la visita')
  })
})

describe('consultazione', () => {
  it('campi, pagamenti, rimborsi, gestione distinta dall’autore e testi sul rimborso', async () => {
    await conElenco([
      prenotazioneDesk(12, { idMedicoEffettivo: 9, nomeMedicoEffettivo: 'Ugo', cognomeMedicoEffettivo: 'Sostituto', indisponibilitaSegnalata: true }),
      annullataDaRichiesta(13, { idPrestazione: null, nomePrestazione: null, idStanza: null, nomeStanza: null }),
      prenotazioneDesk(14, {
        stato: 'NON_EROGATA', pagamenti: [pagamentoDesk(5), pagatoOnline(6)],
        rimborsi: [rimborsoAnnidato(8, 6, { stato: 'APPROVATO', verificatoDa: 5, verificatoIl: '2026-11-07T10:00:00' })],
      }),
      prenotazioneDesk(15, { stato: 'NON_EROGATA', pagamenti: [pagatoSede(7)] }),
      prenotazioneDesk(16, { stato: 'NON_EROGATA', pagamenti: [pagatoOnline(10)] }),
    ])
    const s12 = scheda(12)
    expect(s12.get('[data-campo="titolare"]').text()).toBe('Elena Sintetica')
    expect(s12.get('[data-campo="effettivo"]').text()).toBe('Ugo Sostituto')
    expect(s12.get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(s12.get('[data-campo="prezzo"]').text()).toMatch(/^80,00\s€$/)
    expect(s12.get('[data-campo="indisponibilita"]').text()).toContain('Segnalazioni')
    expect(s12.get('[data-campo="gestione"]').text()).toContain(`operatore n. ${OPERATORE}`)
    expect(s12.get('[data-campo="gestione"]').text()).toContain('Non indica necessariamente chi ha determinato lo stato attuale')
    const s13 = scheda(13)
    expect(s13.get('[data-campo="effettivo"]').text()).toBe('Non assegnato')
    expect(s13.get('[data-campo="prestazione"]').text()).toBe('Prestazione non configurata')
    expect(s13.get('[data-campo="gestione"]').text()).toBe('Nessuna gestione desk registrata.')
    expect(s13.get('[data-campo="pagamenti"]').text()).toBe('Nessun pagamento registrato.')
    const s14 = scheda(14)
    expect(s14.get('[data-pagamento="5"]').text()).toContain('Non riuscito (nessun importo incassato)')
    expect(s14.get('[data-rimborso="8"]').text()).toContain('non significa che il denaro sia già stato restituito')
    expect(s14.find('[data-campo="nota-rimborso"]').exists()).toBe(false)
    expect(scheda(15).get('[data-pagamento="7"]').text()).toContain(`registrato in sede dall'operatore n. ${OPERATORE}`)
    expect(scheda(15).get('[data-campo="nota-rimborso"]').text()).toBe('Gli incassi in sede non sono rimborsabili tramite MedBook.')
    expect(scheda(16).get('[data-campo="nota-rimborso"]').text()).toContain('la richiesta spetta al paziente')
    // Operazioni solo sulle confermate.
    for (const id of [13, 14, 15]) expect(scheda(id).find('.actions').exists()).toBe(false)
    expect(wrapper.findAll('.testo-dati').length).toBeGreaterThan(10)
  })

  it('condizioni non soddisfatte: azioni non proposte e motivo dichiarato', async () => {
    await conElenco([
      prenotazioneDesk(12, { pagamenti: [pagatoSede(5)] }),
      prenotazioneDesk(13, { pagamenti: [pagamentoDesk(6, { stato: 'IN_ATTESA' })] }),
      prenotazioneDesk(14, { prezzo: 0, indisponibilitaSegnalata: true }),
    ])
    const s12 = scheda(12)
    expect(bottoneIn(s12, 'Registra incasso')).toBeUndefined()
    expect(bottoneIn(s12, 'Annulla prenotazione')).toBeUndefined()
    expect(bottoneIn(s12, 'Registra mancata erogazione')).toBeDefined()
    expect(s12.get('[data-pagamento-presente]').text()).toBe('Pagamento già presente: nuovo incasso e annullamento non disponibili.')
    expect(bottoneIn(scheda(13), 'Registra mancata erogazione')).toBeUndefined()
    expect(scheda(13).get('.esito-prestazione').text()).toContain('pagamento in attesa')
    expect(scheda(14).get('[data-campo="non-disponibili"]').text()).toContain('indisponibilità del medico')
  })
})

describe('operazioni', () => {
  it('incasso: scelta esplicita, payload, successo con rilettura immediata e azioni sospese', async () => {
    await conElenco([prenotazioneDesk(12, { prezzo: 80.5 })])
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    expect(attivo().textContent).toBe('Registri un incasso in sede?')
    const pannello = wrapper.get('[data-conferma="incassa"]')
    expect(pannello.text()).toContain('non avvia una transazione POS')
    expect(pannello.get('[data-campo="importo-incasso"]').text()).toMatch(/^80,50\s€$/)
    await click(bottoneIn(pannello, 'Registra incasso'))
    expect(scritture()).toBe(0)
    expect(wrapper.get('#errore-scelta-12').text()).toBe('Scegli la modalità del pagamento ricevuto.')
    expect(attivo().getAttribute('name')).toBe('scelta-12')
    await wrapper.findAll('input[name="scelta-12"]')[0].setValue(true)
    net.on(INCASSO(12), jsonResponse(201, incasso(12, { importo: 80.5, modalita: 'DESK_CONTANTI' })))
    const riletta = net.trattieni(ELENCO())
    await click(bottoneIn(wrapper.get('[data-conferma="incassa"]'), 'Registra incasso'))
    expect(JSON.parse(net.callsTo(INCASSO(12))[0].body)).toEqual({ idOperatore: OPERATORE, modalita: 'DESK_CONTANTI', importoAtteso: 80.5 })
    expect(avviso()).toContain('Incasso di 80,50')
    expect(attivo()).not.toBe(wrapper.get('[data-avviso-desk]').element)
    expect(wrapper.get('[data-avviso-desk] [aria-label="Chiudi avviso"]').exists()).toBe(true)
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(true)
    expect(bottoneIn(scheda(12), 'Registra mancata erogazione').attributes('disabled')).toBeDefined()
    riletta.resolve(jsonResponse(200, [prenotazioneDesk(12, { prezzo: 80.5, pagamenti: [pagatoSede(900, { importo: 80.5, modalita: 'DESK_CONTANTI' })] })]))
    await flushPromises()
    expect(bottoneIn(scheda(12), 'Registra incasso')).toBeUndefined()
    expect(scritture()).toBe(1)
  })

  it('annullamento: rifiuto orario del server mostrato senza blocco; «Indietro» torna al controllo d’origine', async () => {
    await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    expect(wrapper.get('[data-conferma="annulla-confermata"]').text()).toContain("solo prima dell'inizio dell'appuntamento")
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Indietro'))
    expect(attivo().getAttribute('data-origine')).toBe('annulla-confermata-12')
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    net.on(OP(12, 'annulla'), errore(409, "L'appuntamento è già iniziato."))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    expect(JSON.parse(net.callsTo(OP(12, 'annulla'))[0].body)).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA' })
    expect(avviso()).toContain("L'appuntamento è già iniziato.")
    expect(avviso()).toContain('potrai riprovare quando la condizione sarà soddisfatta')
    expect(bottoneIn(scheda(12), 'Annulla prenotazione').attributes('disabled')).toBeUndefined()
  })

  it('mancata erogazione: scelta esplicita e spiegata; con segnalazione solo NON_EROGATA', async () => {
    await conElenco([prenotazioneDesk(12, { indisponibilitaSegnalata: true })])
    await click(bottoneIn(scheda(12), 'Registra mancata erogazione'))
    const radio = wrapper.findAll('input[name="scelta-12"]')
    expect(wrapper.text()).not.toContain('Condizioni delle operazioni')
    expect(radio.every(r => r.element.closest('label.opzione-esito'))).toBe(true)
    expect(radio.every(r => !r.element.closest('.field'))).toBe(true)
    expect(radio.every(r => r.attributes('aria-labelledby') && r.attributes('aria-describedby'))).toBe(true)
    expect(radio.every(r => !r.element.checked)).toBe(true)
    expect(wrapper.get('[data-conferma]').text()).toContain('Il server la accetta solo dopo la fine dello slot.')
    expect(radio[0].attributes('disabled')).toBeDefined()
    expect(wrapper.get('#nota-esito-PAZIENTE_ASSENTE-12').text()).toContain('Non dà diritto a rimborso')
    expect(wrapper.get('#nota-esito-NON_EROGATA-12').text()).toContain('gli incassi in sede non sono rimborsabili')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Registra esito'))
    expect(attivo()).toBe(radio[1].element)
    await radio[1].setValue(true)
    net.on(ESITO(12), jsonResponse(200, gestione(12, 'NON_EROGATA')))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'NON_EROGATA' })]))
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Registra esito'))
    expect(JSON.parse(net.callsTo(ESITO(12))[0].body)).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA', esito: 'NON_EROGATA' })
    expect(avviso()).toContain('«Non erogata»')
    expect(scheda(12).get('[data-campo="stato"]').text()).toBe('Non erogata')
  })

  it('esito incerto: nessun nuovo invio, blocco visibile e condiviso con B7', async () => {
    const { store } = await conElenco([prenotazioneDesk(12), prenotazioneDesk(13)])
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    net.on(OP(12, 'annulla'), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    expect(avviso()).toContain("Non è stato possibile stabilire l'esito")
    expect(scheda(12).text()).toContain('Operazione in corso o da verificare')
    expect(wrapper.get('[data-esito-incerto]').text()).toContain('Annullamento della prenotazione confermata')
    expect(await store.annullaSegnalata(parseSegnalazione(segnalazione(12)))).toEqual({ kind: 'bloccata' })
    expect(bottoneIn(scheda(13), 'Annulla prenotazione').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(OP(12, 'annulla'))).toHaveLength(1)
  })
})

describe('validità, invalidazione e contesto', () => {
  it('esito certo da un’altra pagina: elenco invalidato, conferma chiusa, focus su un riferimento stabile', async () => {
    const { store } = await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    document.activeElement.blur()
    net.on(OP(30, 'conferma'), jsonResponse(200, gestione(30, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    expect((await store.decidiRichiesta('conferma', parseRichiesta(richiesta(30)))).kind).toBe('eseguita')
    await flushPromises()
    expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(attivo().id).toBe('titolo-prenotazioni')
    expect(bottoneIn(scheda(12), 'Registra incasso').attributes('disabled')).toBeDefined()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(bottoneIn(scheda(12), 'Registra incasso').attributes('disabled')).toBeUndefined()
  })

  it('lettura avviata prima di un esito certo: scartata, dati precedenti visibili, nessuna azione', async () => {
    const { store } = await conElenco([prenotazioneDesk(12)])
    const h = net.trattieni(ELENCO())
    await click(bottoneIn(wrapper, 'Cerca'))
    net.on(DECIDI(5, 'approva'), jsonResponse(200, { idRimborso: 5, stato: 'APPROVATO', idOperatore: OPERATORE, verificatoIl: '2026-11-07T10:00:00' }))
    expect((await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5)))).kind).toBe('eseguita')
    h.resolve(jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await flushPromises()
    expect(wrapper.find('[data-elenco-superato]').exists()).toBe(true)
  })

  it('ricontrollo prima della fetch: un blocco nato altrove dopo l’apertura impedisce l’invio', async () => {
    const { store } = await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    await wrapper.findAll('input[name="scelta-12"]')[1].setValue(true)
    const bottone = bottoneIn(wrapper.get('[data-conferma]'), 'Registra incasso')
    net.on(OP(12, 'annulla'), 'rete')
    await store.annullaSegnalata(parseSegnalazione(segnalazione(12)))
    await bottone.trigger('click')
    await flushPromises()
    expect(net.callsTo(INCASSO(12))).toHaveLength(0)
    expect(scheda(12).text()).toContain('Operazione in corso o da verificare')
  })

  it('cambio periodo A → B → A: risposte tardive scartate, nessun messaggio né focus nel nuovo contesto', async () => {
    await apri()
    const primaA = net.trattieni(ELENCO())
    await cerca()
    const b = net.trattieni(ELENCO('data=2026-11-06'))
    await cerca({ data: '2026-11-06' })
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(20)]))
    await cerca()
    primaA.resolve(jsonResponse(200, [prenotazioneDesk(10)]))
    b.resolve(jsonResponse(200, [prenotazioneDesk(11, { dataOraInizio: '2026-11-06T09:00:00', dataOraFine: '2026-11-06T10:00:00' })]))
    await flushPromises()
    expect(wrapper.findAll('[data-prenotazione-desk]').map((w) => w.attributes('data-id'))).toEqual(['20'])
    expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
  })

  it('scrittura in volo e smontaggio: nessun messaggio tardivo; l’esito resta nello store', async () => {
    const { router, store } = await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    const h = net.trattieni(OP(12, 'annulla'))
    await bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione').trigger('click')
    net.on(RICHIESTE, jsonResponse(200, [])).on(MEDICI, jsonResponse(200, [])).on('GET /api/prestazioni', jsonResponse(200, []))
    await router.push('/desk/richieste')
    await flushPromises()
    h.resolve(jsonResponse(200, gestione(12, 'ANNULLATA')))
    await flushPromises()
    expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
    expect(store.bloccata('prenotazione:12')).toBe(false)
    expect(letture()).toEqual([`/api/prenotazioni/desk?data=${GIORNO}`])
  })

  it('rotazione della sessione: dati azzerati e rilettura dello stesso periodo', async () => {
    const { session } = await conElenco([prenotazioneDesk(12)], { medico: '4', query: `data=${GIORNO}&medicoId=4` })
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(ELENCO(`data=${GIORNO}&medicoId=4`), jsonResponse(200, [prenotazioneDesk(12)]))
    await session.changePassword('a', 'b')
    await flushPromises()
    expect(letture()).toEqual([`/api/prenotazioni/desk?data=${GIORNO}&medicoId=4`, `/api/prenotazioni/desk?data=${GIORNO}&medicoId=4`])
    expect(wrapper.findAll('[data-prenotazione-desk]')).toHaveLength(1)
  })
})

describe('esiti incerti e verifica', () => {
  it('dopo la navigazione: verifica sul giorno senza filtro medico; l’elenco mostrato resta quello dei filtri', async () => {
    const { router } = await conElenco([prenotazioneDesk(12)], { medico: '4', query: `data=${GIORNO}&medicoId=4` })
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    await wrapper.findAll('input[name="scelta-12"]')[1].setValue(true)
    net.on(INCASSO(12), errore(503))
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Registra incasso'))
    net.on(RICHIESTE, jsonResponse(200, [])).on(MEDICI, jsonResponse(200, [])).on('GET /api/prestazioni', jsonResponse(200, []))
    await router.push('/desk/richieste')
    await flushPromises()
    expect(wrapper.find('[data-esito-incerto]').exists()).toBe(false)
    net.on(MEDICI, jsonResponse(200, [medicoDesk(4)]))
    await router.push('/desk/prenotazioni')
    await flushPromises()
    expect(wrapper.get('[data-esito-incerto]').text()).toContain('Incasso in sede dall\'esito non determinato')
    expect(wrapper.get('[data-esito-incerto]').text()).toContain('In sede, POS')
    // Verifica: il giorno noto, senza filtro medico (il medico effettivo ora è un sostituto).
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { idMedicoEffettivo: 9, nomeMedicoEffettivo: 'Ugo', cognomeMedicoEffettivo: 'Sostituto', pagamenti: [pagatoSede(900, { modalita: 'DESK_POS' })] })]))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(letture().at(-1)).toBe(`/api/prenotazioni/desk?data=${GIORNO}`)
    expect(avviso()).toContain('risultano nuovi pagamenti')
    expect(avviso()).toContain('non stabilisce se derivi da questo tentativo')
    // Dopo la riapertura la pagina non ha un periodo: nessun elenco adottato dalla verifica.
    expect(wrapper.find('[data-prenotazione-desk]').exists()).toBe(false)
    expect(wrapper.find('[data-senza-periodo]').exists()).toBe(true)
  })

  it('verifica con elenco filtrato mostrato: rilettura con i filtri della vista, non con la copertura della verifica', async () => {
    await conElenco([prenotazioneDesk(12)], { medico: '4', query: `data=${GIORNO}&medicoId=4` })
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    net.on(OP(12, 'annulla'), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' }), prenotazioneDesk(13, { idMedicoEffettivo: 9, nomeMedicoEffettivo: 'Ugo', cognomeMedicoEffettivo: 'Sostituto' })]))
    net.on(ELENCO(`data=${GIORNO}&medicoId=4`), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(letture().slice(-2)).toEqual([`/api/prenotazioni/desk?data=${GIORNO}`, `/api/prenotazioni/desk?data=${GIORNO}&medicoId=4`])
    expect(wrapper.findAll('[data-prenotazione-desk]').map((w) => w.attributes('data-id'))).toEqual(['12'])
    expect(avviso()).toContain('«Annullata»')
  })

  it('verifiche sovrapposte nei due ordini: ciascuna con il proprio esito; la superata non chiude l’incertezza', async () => {
    const { store } = await conElenco([prenotazioneDesk(12), prenotazioneDesk(13)])
    for (const id of [12, 13]) {
      await click(bottoneIn(scheda(id), 'Annulla prenotazione'))
      net.on(OP(id, 'annulla'), 'rete')
      await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    }
    const prima = net.trattieni(ELENCO())
    const seconda = net.trattieni(ELENCO())
    const [v12, v13] = wrapper.findAll('[data-esito-incerto]')
    await v12.get('button').trigger('click')
    await v13.get('button').trigger('click')
    // La seconda lettura arriva per prima: vale per entrambe; la prima, più vecchia, non la contraddice.
    seconda.resolve(jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' }), prenotazioneDesk(13)]))
    await flushPromises()
    prima.resolve(jsonResponse(200, [prenotazioneDesk(12), prenotazioneDesk(13, { stato: 'ANNULLATA' })]))
    net.on(ELENCO(), jsonResponse(200, []), jsonResponse(200, []))
    await flushPromises()
    expect(store.incerta('prenotazione:12')).toBeNull()
    expect(store.incerta('prenotazione:13')).toBeNull()
    expect(avviso()).toMatch(/n\. 12 è nello stato «Annullata»/)
  })

  it('rilettura della vista più recente della verifica: l’esito usa il riscontro corrente della vista', async () => {
    const { store } = await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    net.on(OP(12, 'annulla'), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    const verifica = net.trattieni(ELENCO())
    await bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica').trigger('click')
    // Esito certo su un'altra prenotazione: l'elenco si rilegge nello stesso contesto.
    net.on(OP(40, 'conferma'), jsonResponse(200, gestione(40, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    await store.decidiRichiesta('conferma', parseRichiesta(richiesta(40)))
    await flushPromises()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(scheda(12).get('[data-campo="stato"]').text()).toBe('Annullata')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    verifica.resolve(jsonResponse(200, [prenotazioneDesk(12)]))
    await flushPromises()
    expect(avviso()).toContain('è nello stato «Annullata»')
    expect(wrapper.find('[data-esito-incerto]').exists()).toBe(false)
  })

  it('assenza: non conclusiva; rimozione con focus su un riferimento stabile', async () => {
    await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Registra mancata erogazione'))
    await wrapper.findAll('input[name="scelta-12"]')[0].setValue(true)
    net.on(ESITO(12), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Registra esito'))
    net.on(ELENCO(), jsonResponse(200, []), jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(avviso()).toContain("l'assenza non indica che l'operazione non sia avvenuta")
    expect(wrapper.get('[data-esito-incerto]').text()).toContain('tra le prenotazioni gestite del giorno')
    // Come in un browser, il clic porta il focus sul pulsante, che poi viene rimosso.
    const rimuovi = bottoneIn(wrapper.get('[data-esito-incerto]'), 'Rimuovi')
    rimuovi.element.focus()
    await click(rimuovi)
    expect(wrapper.find('[data-esito-incerto]').exists()).toBe(false)
    expect(attivo().tagName).toBe('H1')
  })
})

describe('rimborsi: recupero B8 delle decisioni incerte', () => {
  it('pagina B7: rimozione dell’ultimo esito con focus sul contenuto principale', async () => {
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
    await mountApp('/desk/rimborsi?stato=RICHIESTO')
    net.on(DECIDI(5, 'respingi'), 'rete')
    await click(bottoneIn(wrapper.get('[data-rimborso-desk][data-id="5"]'), 'Respingi'))
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Respingi'))
    net.on(RIMBORSI, jsonResponse(200, []), jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    const rimuovi = bottoneIn(wrapper.get('[data-esito-incerto]'), 'Rimuovi')
    rimuovi.element.focus()
    await click(rimuovi)
    expect(attivo().id).toBe('contenuto')
  })

  it('offerto solo con prenotazione e giorno noti; usa la lettura desk e non sostituisce l’elenco dei rimborsi', async () => {
    net.on(RIMBORSI, jsonResponse(200, [
      rimborsoDesk(5, { idPrenotazione: 25, dataOraInizio: `${GIORNO}T09:00:00`, dataOraFine: `${GIORNO}T10:00:00` }),
      rimborsoDesk(6, { idPrenotazione: 26, dataOraInizio: null, dataOraFine: null }),
    ]))
    await mountApp('/desk/rimborsi?stato=RICHIESTO')
    for (const id of [5, 6]) {
      net.on(DECIDI(id, 'approva'), 'rete')
      await click(bottoneIn(wrapper.get(`[data-rimborso-desk][data-id="${id}"]`), 'Approva'))
      await click(bottoneIn(wrapper.get('[data-conferma]'), 'Approva'))
    }
    const [e5, e6] = wrapper.findAll('[data-esito-incerto]')
    expect(e5.find('[data-recupero]').text()).toBe('Verifica sulla prenotazione n. 25')
    expect(e6.find('[data-recupero]').exists()).toBe(false)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(25, {
      stato: 'NON_EROGATA', pagamenti: [pagatoOnline(45)], rimborsi: [rimborsoAnnidato(5, 45, { stato: 'APPROVATO', verificatoDa: 8, verificatoIl: '2026-11-07T10:00:00' })],
    })]))
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(6, { idPrenotazione: 26, dataOraInizio: null, dataOraFine: null })]))
    await click(e5.get('[data-recupero]'))
    expect(avviso()).toContain('risulta nello stato «Approvato»')
    expect(avviso()).toContain('non stabilisce se derivi da questo tentativo')
    expect(wrapper.findAll('[data-esito-incerto]')).toHaveLength(1)
    expect(wrapper.findAll('[data-rimborso-desk]').map((w) => w.attributes('data-id'))).toEqual(['6'])
  })
})

describe('focus dopo operazioni e letture manuali (revisione B8C)', () => {
  const titolo = () => wrapper.get('#titolo-prenotazioni').element
  async function conErrore() {
    const ctx = await apri()
    net.on(ELENCO(), errore(500))
    await cerca()
    const riprova = wrapper.get('[data-riprova]')
    riprova.element.focus()
    return Object.assign(riprova, { ctx })
  }

  it('operazione pendente e focus portato sul filtro: il completamento non lo sottrae', async () => {
    await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    await wrapper.findAll('input[name="scelta-12"]')[0].setValue(true)
    const h = net.trattieni(INCASSO(12))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await bottoneIn(wrapper.get('[data-conferma]'), 'Registra incasso').trigger('click')
    const filtro = wrapper.get('#prenotazioni-data').element
    filtro.focus()
    h.resolve(jsonResponse(201, incasso(12, { modalita: 'DESK_CONTANTI' })))
    await flushPromises()
    expect(avviso()).toContain('Incasso di 80,00')
    expect(attivo()).toBe(filtro)
  })

  it('«Riprova» con successo: focus sul titolo dell’elenco', async () => {
    const riprova = await conErrore()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await click(riprova)
    expect(wrapper.find('[data-prenotazione-desk]').exists()).toBe(true)
    expect(attivo()).toBe(titolo())
  })

  it('«Riprova» con errore: focus sul nuovo «Riprova»', async () => {
    const riprova = await conErrore()
    net.on(ELENCO(), errore(503))
    await click(riprova)
    expect(attivo()).toBe(wrapper.get('[data-riprova]').element)
  })

  it('«Aggiorna l’elenco» con lettura scartata: focus sul nuovo «Aggiorna l’elenco»', async () => {
    const { store } = await conElenco([prenotazioneDesk(12)])
    net.on(OP(40, 'conferma'), jsonResponse(200, gestione(40, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    await store.decidiRichiesta('conferma', parseRichiesta(richiesta(40)))
    await flushPromises()
    const aggiorna = bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco")
    aggiorna.element.focus()
    const h = net.trattieni(ELENCO())
    await aggiorna.trigger('click')
    net.on(OP(41, 'conferma'), jsonResponse(200, gestione(41, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    await store.decidiRichiesta('conferma', parseRichiesta(richiesta(41)))
    h.resolve(jsonResponse(200, [prenotazioneDesk(12)]))
    await flushPromises()
    expect(attivo()).toBe(wrapper.get('[data-elenco-superato] [data-aggiorna-elenco]').element)
  })

  it('focus spostato durante la lettura manuale: rispettato', async () => {
    const riprova = await conErrore()
    const h = net.trattieni(ELENCO())
    await riprova.trigger('click')
    const filtro = wrapper.get('#prenotazioni-medico').element
    filtro.focus()
    h.resolve(jsonResponse(200, [prenotazioneDesk(12)]))
    await flushPromises()
    expect(attivo()).toBe(filtro)
  })

  it('lettura manuale sostituita da una rilettura della pagina: nessun recupero tardivo', async () => {
    const { store } = await conElenco([prenotazioneDesk(12), prenotazioneDesk(13)])
    await click(bottoneIn(scheda(13), 'Annulla prenotazione'))
    net.on(OP(13, 'annulla'), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    net.on(OP(40, 'conferma'), jsonResponse(200, gestione(40, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    await store.decidiRichiesta('conferma', parseRichiesta(richiesta(40)))
    await flushPromises()
    const aggiorna = bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco")
    aggiorna.element.focus()
    const manuale = net.trattieni(ELENCO())
    await aggiorna.trigger('click')
    // La verifica dell'esito incerto rilegge l'elenco: la lettura manuale è sostituita.
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12), prenotazioneDesk(13, { stato: 'ANNULLATA' })]))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12), prenotazioneDesk(13, { stato: 'ANNULLATA' })]))
    const verifica = bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica')
    verifica.element.focus()
    await click(verifica)
    expect(attivo()).toBe(wrapper.get('[data-avviso-desk]').element)
    manuale.resolve(jsonResponse(200, [prenotazioneDesk(12), prenotazioneDesk(13)]))
    await flushPromises()
    expect(attivo()).toBe(wrapper.get('[data-avviso-desk]').element)
    expect(scheda(13).get('[data-campo="stato"]').text()).toBe('Annullata')
  })

  it('cambio di epoca durante la lettura manuale: rilettura automatica senza focus dal recupero annullato', async () => {
    const riprova = await conErrore()
    const h = net.trattieni(ELENCO())
    await riprova.trigger('click')
    expect(attivo()).toBe(document.body)
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await riprova.ctx.session.changePassword('a', 'b')
    await flushPromises()
    expect(h.aborted).toBe(true)
    expect(wrapper.find('[data-prenotazione-desk]').exists()).toBe(true)
    expect(attivo()).toBe(document.body)
  })

  it('operazione pendente e cambio di epoca: nessun messaggio né focus nel nuovo contesto', async () => {
    const { session } = await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    const h = net.trattieni(OP(12, 'annulla'))
    await bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione').trigger('click')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await session.changePassword('a', 'b')
    await flushPromises()
    h.resolve(jsonResponse(200, gestione(12, 'ANNULLATA')))
    await flushPromises()
    expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
    expect(attivo()).toBe(document.body)
  })

  it('navigazione durante la lettura manuale: nessun focus dopo lo smontaggio', async () => {
    const riprova = await conErrore()
    const h = net.trattieni(ELENCO())
    await riprova.trigger('click')
    net.on(RICHIESTE, jsonResponse(200, [])).on(MEDICI, jsonResponse(200, [])).on('GET /api/prestazioni', jsonResponse(200, []))
    await riprova.ctx.router.push('/desk/richieste')
    await flushPromises()
    const prima = attivo()
    h.resolve(jsonResponse(200, [prenotazioneDesk(12)]))
    await flushPromises()
    expect(attivo()).toBe(prima)
    expect(wrapper.find('#titolo-prenotazioni').exists()).toBe(false)
  })
})

describe('tabella e dettagli (revisione visiva)', () => {
  const dettagli = (id) => wrapper.get(`[data-dettagli="${id}"]`)

  it('tabella semantica; dettaglio chiuso senza operazioni, aperto con aria-expanded e aria-controls', async () => {
    await apri()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await cerca()
    expect(wrapper.findAll('[data-tabella-prenotazioni] thead th').map((t) => t.text())).toEqual([
      'Data e ora', 'Paziente, data, prestazione e stato', 'Prestazione e medico', 'Stato', 'Pagamento', 'Importo', 'Azioni',
    ])
    expect(scheda(12).get('th[scope="row"]').text()).toContain('Paola Prova12')
    const b = dettagli(12)
    expect(b.attributes('aria-expanded')).toBe('false')
    expect(document.getElementById(b.attributes('aria-controls'))).not.toBeNull()
    expect(bottoneIn(scheda(12), 'Registra incasso')).toBeUndefined()
    await click(b)
    expect(b.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get(`#${b.attributes('aria-controls')}`).find('[data-campo="titolare"]').exists()).toBe(true)
    expect(bottoneIn(scheda(12), 'Registra incasso')).toBeDefined()
    await click(b)
    expect(b.attributes('aria-expanded')).toBe('false')
    expect(scheda(12).find('[data-campo="titolare"]').exists()).toBe(false)
  })

  it('con una conferma aperta il dettaglio non si chiude; «Indietro» la chiude e torna al controllo d’origine', async () => {
    await conElenco([prenotazioneDesk(12)])
    await click(bottoneIn(scheda(12), 'Registra incasso'))
    const b = dettagli(12)
    expect(b.attributes('aria-disabled')).toBe('true')
    expect(wrapper.get(`#${b.attributes('aria-describedby')}`).text()).toContain('«Indietro»')
    b.element.focus()
    await click(b)
    expect(wrapper.find('[data-conferma="incassa"]').exists()).toBe(true)
    expect(b.attributes('aria-expanded')).toBe('true')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Indietro'))
    expect(attivo().getAttribute('data-origine')).toBe('incassa-12')
    expect(b.attributes('aria-disabled')).toBeUndefined()
    expect(scritture()).toBe(0)
  })

  it('riepilogo dei pagamenti: assenza, non riusciti, in attesa, pagati e rimborsi distinti', async () => {
    await apri()
    net.on(ELENCO(), jsonResponse(200, [
      prenotazioneDesk(12),
      prenotazioneDesk(13, { pagamenti: [pagamentoDesk(5), pagamentoDesk(6)] }),
      prenotazioneDesk(14, { pagamenti: [pagamentoDesk(7, { stato: 'IN_ATTESA' })] }),
      prenotazioneDesk(15, { stato: 'NON_EROGATA', pagamenti: [pagamentoDesk(8), pagatoOnline(9)], rimborsi: [rimborsoAnnidato(3, 9)] }),
    ]))
    await cerca()
    const riepilogo = (id) => scheda(id).findAll('[data-campo="riepilogo-pagamento"] span').map((x) => x.text())
    expect(riepilogo(12)).toEqual(['Nessun pagamento'])
    expect(riepilogo(13)).toEqual(['2 tentativi non riusciti'])
    expect(riepilogo(14)).toEqual(['In attesa · Online (simulato)'])
    expect(riepilogo(15)).toEqual(['Pagato · Online (simulato)', '1 tentativo non riuscito', 'Rimborso: Richiesto, in attesa di decisione'])
    expect(scheda(15).get('[data-campo="stato"]').classes()).toContain('tono-rosso')
  })

  it('data compatta con secondi solo se significativi; storico completo nel dettaglio', async () => {
    await conElenco([prenotazioneDesk(12, {
      dataOraInizio: `${GIORNO}T09:00:30`, dataOraFine: `${GIORNO}T10:00:00`,
      pagamenti: [pagatoSede(5, { creatoIl: '2026-11-05T08:50:07', pagatoIl: '2026-11-05T08:50:07' })],
    })])
    expect(scheda(12).get('[data-campo="quando"]').text()).toMatch(/^gio 05\/11\/2026\s*09:00:30–10:00$/)
    expect(scheda(12).get('[data-pagamento="5"]').text()).toContain('pagato il giovedì 05/11/2026, 08:50:07')
    expect(scheda(12).get('[data-campo="gestione"]').text()).toContain('martedì 20/10/2026, 10:00.')
  })

  it('informazioni operative espandibili; avvisi ed esiti critici fuori da esse; nuova ricerca richiude i dettagli', async () => {
    await conElenco([prenotazioneDesk(12)])
    const info = wrapper.get('[data-informazioni]')
    expect(info.element.tagName).toBe('DETAILS')
    expect(info.find('[data-vincoli]').exists()).toBe(true)
    await click(bottoneIn(scheda(12), 'Annulla prenotazione'))
    net.on(OP(12, 'annulla'), 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma]'), 'Annulla prenotazione'))
    expect(info.find('[data-avviso-desk]').exists()).toBe(false)
    expect(info.find('[data-esito-incerto]').exists()).toBe(false)
    expect(wrapper.find('[data-avviso-desk]').exists()).toBe(true)
    expect(scheda(12).get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(scheda(12).text()).toContain('In corso o da verificare')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    await cerca()
    expect(dettagli(12).attributes('aria-expanded')).toBe('false')
  })
})


describe('presentazione dell’incasso registrato', () => {
  it('numero nella riga principale, riepilogo solo PAGATO e operazioni separate', async () => {
    await conElenco([
      prenotazioneDesk(21, { prezzo: 120, pagamenti: [pagatoSede(11, { importo: 120 })] }),
      prenotazioneDesk(22, { pagamenti: [pagamentoDesk(12, { stato: 'IN_ATTESA' })] }),
      prenotazioneDesk(23, { pagamenti: [pagamentoDesk(13)] }),
    ])
    const pagata = scheda(21)
    expect(pagata.get('.riepilogo .paziente [data-campo="numero-prenotazione"]').text()).toBe('n. 21')
    const riepilogo = pagata.get('[data-riepilogo-pagato="11"]')
    expect(riepilogo.text()).toMatch(/Pagamento registrato · 120,00\s€ · Contanti/)
    expect(riepilogo.text()).toContain(`Operatore n. ${OPERATORE}`)
    expect(pagata.get('[data-pagamento-presente]').text()).toBe('Pagamento già presente: nuovo incasso e annullamento non disponibili.')
    expect(bottoneIn(pagata, 'Registra incasso')).toBeUndefined()
    expect(bottoneIn(pagata, 'Annulla prenotazione')).toBeUndefined()
    expect(pagata.get('.esito-prestazione').text()).toContain('Registra mancata erogazione')
    expect(pagata.get('[data-campo="pagamenti"]').element.closest('details').open).toBe(false)
    expect(pagata.get('[data-campo="gestione"]').element.closest('details').open).toBe(false)
    expect(scheda(22).find('[data-riepilogo-pagato]').exists()).toBe(false)
    expect(scheda(22).get('[data-campo="riepilogo-pagamento"]').text()).toContain('In attesa')
    expect(scheda(23).find('[data-riepilogo-pagato]').exists()).toBe(false)
    expect(scheda(23).find('[data-pagamento-presente]').exists()).toBe(false)
    expect(bottoneIn(scheda(23), 'Registra incasso')).toBeDefined()
    expect(scritture()).toBe(0)
  })
})


it('paziente assente: badge rosso su entrambe le viste e pagamento indipendente', async () => {
  await conElenco([prenotazioneDesk(29, { stato: 'PAZIENTE_ASSENTE' })])
  const riga = scheda(29)
  const badge = riga.findAll('.badge')
  expect(badge).toHaveLength(2)
  for (const b of badge) {
    expect(b.text()).toBe('Paziente assente')
    expect(b.classes()).toContain('tono-rosso')
    expect(b.element.style.color).toBe('rgb(145, 32, 24)')
    expect(b.element.style.backgroundColor).toBe('rgb(254, 243, 242)')
  }
  expect(riga.get('[data-campo="riepilogo-pagamento"]').text()).toBe('Nessun pagamento')
  expect(scritture()).toBe(0)
})
