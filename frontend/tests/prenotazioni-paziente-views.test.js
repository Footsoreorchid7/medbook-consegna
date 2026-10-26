import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { STATO, createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'
import { appuntamento } from './prenotazioni-paziente-fixtures.js'

// Dati sintetici: nessuna identità reale.
const ME = 'GET /api/auth/me'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'
const CREA = 'POST /api/prenotazioni'
const PWD = 'PUT /api/auth/password'
const LOGOUT = 'POST /api/auth/logout'
const lista = (id = 7, query = '') => `GET /api/prenotazioni/paziente?idPaziente=${id}${query}`
const annulla = (id) => `POST /api/prenotazioni/${id}/annulla-paziente`

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

beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
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
  return { session, router }
}

async function vai(router, path) {
  await router.push(path)
  await flushPromises()
}

const main = () => wrapper.get('main')
const bottone = (testo) => wrapper.findAll('main button').find((b) => b.text() === testo)
const corpo = (call) => JSON.parse(call.body)

describe('accesso alle pagine delle prenotazioni', () => {
  it('anonimo: login con ritorno interno', async () => {
    const { router } = await mountApp('/paziente/prenotazioni', unauthorized())
    expect(router.currentRoute.value.name).toBe('accesso')
    expect(router.currentRoute.value.query.redirect).toBe('/paziente/prenotazioni')
  })

  it.each([
    ['medico', utente({ paziente: false, medico: true })],
    ['amministratore', utente({ paziente: false, ruoloGestionale: 'AMMINISTRATORE' })],
    ['operatore desk', utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' })],
  ])('%s senza profilo paziente: accesso negato, nessuna API paziente', async (_, user) => {
    const { router } = await mountApp('/paziente/prenota', meOk(user))
    expect(router.currentRoute.value.name).toBe('accesso-negato')
    expect(net.calls.some((c) => c.url.startsWith('/api/prenotazioni') || c.url.startsWith('/api/disponibilita'))).toBe(false)
  })

  it('profilo combinato medico+paziente: pagine paziente disponibili', async () => {
    net.on(lista(), jsonResponse(200, []))
    const { router } = await mountApp('/paziente/prenotazioni', meOk(utente({ medico: true })))
    expect(router.currentRoute.value.name).toBe('prenotazioni')
    expect(main().text()).toContain('Non hai ancora prenotazioni.')
  })

  it('area paziente: link a Prenota ed elenco, nessun link a pagine inesistenti', async () => {
    const { router } = await mountApp('/area')
    const link = wrapper.findAll('main a').map((a) => a.attributes('href'))
    // aggiunge «I miei rimborsi»; ogni link deve portare a una pagina implementata.
    expect(link).toEqual(expect.arrayContaining(['/paziente/prenota', '/paziente/prenotazioni', '/paziente/rimborsi']))
    for (const href of link) expect(router.resolve(href).name).not.toBe('non-trovata')
    for (const href of link) expect(href).not.toMatch(/pagament|referti/)
  })

  it('area senza profilo paziente: nessun link alle prenotazioni', async () => {
    await mountApp('/area', meOk(utente({ paziente: false, ruoloGestionale: 'AMMINISTRATORE' })))
    const link = wrapper.findAll('main a').map((a) => a.attributes('href'))
    expect(link).not.toContain('/paziente/prenota')
    expect(link).not.toContain('/paziente/prenotazioni')
  })
})

describe('/disponibilita: accesso alla prenotazione', () => {
  async function apri(me) {
    net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(`${DISP}?prestazioneId=1&data=2026-10-20`, jsonResponse(200, [slot(10)]))
    const ctx = await mountApp('/disponibilita', me)
    await wrapper.get('#filtro-prestazione').setValue('1')
    await wrapper.get('#filtro-data').setValue('2026-10-20')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    return ctx
  }
  const azioni = () => wrapper.findAll('[data-disponibilita] button')

  it('paziente: scelta verso l’elenco con riepilogo dello slot, senza POST', async () => {
    const { router } = await apri(meOk(utente()))
    net.on(lista(), jsonResponse(200, [])).on(`${DISP}?prestazioneId=1&data=2026-10-20`, jsonResponse(200, [slot(10)]))
    await azioni()[0].trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/paziente/prenotazioni'))
    await flushPromises()
    expect(wrapper.get('[data-completa-richiesta]').text()).toContain('Elena Sintetica')
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('anonimo: login con ritorno interno validato verso la prenotazione', async () => {
    const { router, session } = await apri(unauthorized())
    expect(azioni()[0].text()).toContain('Accedi per richiedere')
    // Dopo l'accesso la destinazione validata è la pagina di prenotazione.
    net.on(lista(), jsonResponse(200, [])).on(`${DISP}?prestazioneId=1&data=2026-10-20`, jsonResponse(200, [slot(10)]))
    await wrapper.get('[data-disponibilita] button').trigger('click'); await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('accesso'))
    expect(router.currentRoute.value.query.redirect).toBe('/paziente/prenotazioni')
    net.on('POST /api/auth/login', meOk(utente(), CSRF_B))
    await wrapper.get('#email').setValue('paziente@example.test')
    await wrapper.get('#password').setValue('x')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/paziente/prenotazioni'))
  })

  it('autenticato senza profilo paziente: messaggio coerente, nessun link', async () => {
    await apri(meOk(utente({ paziente: false, medico: true })))
    expect(azioni()).toEqual([])
    expect(wrapper.get('[data-avviso-profilo]').text()).toContain('riservata agli account con profilo paziente')
  })
})

describe('/paziente/prenota', () => {
  async function apri(path = '/paziente/prenota', disp = SLOTS) {
    net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, disp))
    return mountApp(path)
  }
  async function scegliEConferma(id = '10') {
    await wrapper.get(`input[type="radio"][value="${id}"]`).setValue(true)
    await wrapper.get('[data-disponibilita]').element.closest('form').dispatchEvent(new Event('submit'))
    await flushPromises()
  }

  it('selezione, riepilogo accessibile e invio con identità dalla sessione', async () => {
    await apri()
    expect(wrapper.get('fieldset legend').text()).toBe('Scegli un orario')
    await scegliEConferma('11')
    const conferma = wrapper.get('[data-conferma]')
    expect(conferma.get('[data-campo="prestazione"]').text()).toBe('Visita cardiologica')
    expect(conferma.get('[data-campo="medico"]').text()).toBe('Marco Prova')
    expect(conferma.get('[data-campo="quando"]').text()).toBe('mercoledì 21/10/2026, 14:00–15:00')
    expect(conferma.get('[data-campo="prezzo"]').text()).toMatch(/80,00\s€/)
    expect(document.activeElement?.id).toBe('titolo-conferma')
    expect(net.callsTo(CREA)).toHaveLength(0)
    net.on(CREA, jsonResponse(201, creata({ idDisponibilita: 11 })))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(corpo(net.callsTo(CREA)[0])).toEqual({ idPaziente: 7, idDisponibilita: 11 })
  })

  it('201 RICHIESTA: "Richiesta inviata", non confermata, con link al dettaglio', async () => {
    await apri()
    await scegliEConferma()
    net.on(CREA, jsonResponse(201, creata()))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    const esito = wrapper.get('[data-esito="inviata"]')
    expect(esito.get('h2').text()).toBe('Richiesta inviata')
    expect(esito.text()).toContain('Non è ancora un appuntamento confermato')
    expect(esito.find('a[href="/paziente/prenotazioni/50"]').exists()).toBe(true)
    expect(document.activeElement?.id).toBe('titolo-inviata')
  })

  it('doppio clic: una sola POST, pulsante disabilitato durante l\'invio', async () => {
    await apri()
    await scegliEConferma()
    const trattenuta = net.trattieni(CREA)
    const invia = bottone('Invia richiesta')
    await invia.trigger('click')
    await invia.trigger('click')
    await flushPromises()
    expect(bottone('Invio in corso…').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-conferma]').attributes('aria-busy')).toBe('true')
    trattenuta.resolve(jsonResponse(201, creata()))
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('409 slot conteso: messaggio, elenco riletto, nessuna nuova POST', async () => {
    await apri()
    await scegliEConferma()
    net.on(CREA, jsonResponse(409, { error: 'Disponibilità già occupata.' }))
    net.on(DISP, jsonResponse(200, [slot(11)]))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('non è più disponibile')
    expect(net.callsTo(DISP)).toHaveLength(2)
    expect(net.callsTo(CREA)).toHaveLength(1)
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(1)
    expect(wrapper.find('input[type="radio"]:checked').exists()).toBe(false)
  })

  it.each([
    [400, 'Richiesta non valida.'],
    [403, 'Operazione non consentita'],
    [404, 'non è stato trovato'],
  ])('%i: messaggio, nessuna incertezza', async (status, testo) => {
    await apri()
    await scegliEConferma()
    net.on(CREA, jsonResponse(status, { error: 'x' }))
    if (status === 404) net.on(DISP, jsonResponse(200, SLOTS))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(testo)
    expect(wrapper.find('[data-incerta]').exists()).toBe(false)
  })

  it('401: sessione persa, ritorno all\'accesso', async () => {
    const { router, session } = await apri()
    await scegliEConferma()
    net.on(CREA, unauthorized())
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(router.currentRoute.value.name).toBe('accesso')
  })

  it('esito incerto: nessun retry, blocco che sopravvive alla navigazione, verifica manuale senza duplicazione', async () => {
    const { router } = await apri()
    await scegliEConferma()
    net.on(CREA, 'rete')
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(1)
    expect(wrapper.get('[data-incerta]').text()).toContain("Non è stato possibile stabilire l'esito della richiesta")
    expect(document.activeElement?.id).toBe('titolo-incerte')
    expect(wrapper.get('input[type="radio"][value="10"]').attributes('disabled')).toBeDefined()

    // Navigazione nella SPA e ritorno: l'incertezza resta, lo slot riletto resta bloccato.
    net.on(lista(), jsonResponse(200, []))
    await vai(router, '/paziente/prenotazioni')
    net.on(CAT, jsonResponse(200, PRESTAZIONI)).on(DISP, jsonResponse(200, SLOTS))
    await vai(router, '/paziente/prenota?slot=10')
    expect(wrapper.find('[data-incerta]').exists()).toBe(true)
    expect(wrapper.get('input[type="radio"][value="10"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('input[type="radio"]:checked').exists()).toBe(false)
    expect(main().text()).toContain("L'orario indicato ha una richiesta precedente da verificare")
    expect(bottone('Invia una nuova richiesta')).toBeUndefined()

    // Verifica: nulla trovato. Non prova il fallimento; reinvio solo su scelta esplicita.
    net.on(lista(), jsonResponse(200, [appuntamento(30, { idDisponibilita: 99 })]))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-incerta]').text()).toContain('non prova che l\'invio non sia stato registrato')
    expect(net.callsTo(CREA)).toHaveLength(1)

    // Seconda verifica: la richiesta risulta registrata.
    net.on(lista(), jsonResponse(200, [appuntamento(31, { idDisponibilita: 10 })]))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-incerta]').exists()).toBe(false)
    expect(main().text()).toContain('risulta una tua prenotazione, stato: Richiesta inviata, in attesa di conferma')
    expect(main().find('a[href="/paziente/prenotazioni/31"]').exists()).toBe(true)
    expect(net.callsTo(CREA)).toHaveLength(1)
    // La verifica non deduce nulla dall'elenco pubblico degli slot.
    expect(net.callsTo(DISP)).toHaveLength(2)
  })

  it('dopo una verifica senza risultati: nuovo invio esplicito con conferma', async () => {
    await apri()
    await scegliEConferma()
    net.on(CREA, jsonResponse(502, { error: 'x' }))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    net.on(lista(), jsonResponse(200, []))
    await bottone('Verifica nelle mie prenotazioni').trigger('click')
    await flushPromises()
    await bottone('Invia una nuova richiesta').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-conferma] [data-campo="medico"]').text()).toBe('Elena Sintetica')
    expect(net.callsTo(CREA)).toHaveLength(1)
    net.on(CREA, jsonResponse(201, creata()))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(2)
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(true)
    expect(wrapper.find('[data-incerta]').exists()).toBe(false)
  })

  it('successo malformato: esito incerto', async () => {
    await apri()
    await scegliEConferma()
    net.on(CREA, jsonResponse(201, creata({ stato: 'CONFERMATA' })))
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-esito="inviata"]').exists()).toBe(false)
    expect(wrapper.find('[data-incerta]').exists()).toBe(true)
  })

  it('preselezione da query: solo se lo slot è tra quelli appena letti', async () => {
    await apri('/paziente/prenota?slot=11')
    expect(wrapper.get('input[type="radio"][value="11"]').element.checked).toBe(true)
    expect(main().text()).toContain('Orario preselezionato')
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
  })

  it.each([
    ['/paziente/prenota?slot=999', 'non risulta più disponibile'],
    ['/paziente/prenota?slot=abc', 'non è valido'],
    ['/paziente/prenota?slot=010', 'non è valido'],
  ])('preselezione non utilizzabile %s', async (path, testo) => {
    await apri(path)
    expect(wrapper.get('[role="alert"]').text()).toContain(testo)
    expect(wrapper.find('input[type="radio"]:checked').exists()).toBe(false)
    expect(net.calls.map((c) => c.url)).not.toContain(expect.stringContaining('999'))
  })

  it('nessuna selezione: errore accessibile, nessuna POST', async () => {
    await apri()
    await wrapper.get('[data-disponibilita]').element.closest('form').dispatchEvent(new Event('submit'))
    await flushPromises()
    expect(wrapper.get('#errore-selezione').text()).toContain('Seleziona un orario')
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('passwordUncertain: invio disabilitato e nessuna POST', async () => {
    const { session } = await apri()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    await flushPromises()
    await scegliEConferma()
    expect(main().text()).toContain("finché non verifichi l'esito del cambio password")
    expect(bottone('Invia richiesta').attributes('disabled')).toBeDefined()
    await bottone('Invia richiesta').trigger('click')
    await flushPromises()
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('Modifica la scelta torna all\'elenco senza scritture', async () => {
    await apri()
    await scegliEConferma()
    await bottone('Modifica la scelta').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-conferma]').exists()).toBe(false)
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(2)
    expect(net.callsTo(CREA)).toHaveLength(0)
  })
})

describe('/paziente/prenotazioni', () => {
  const voci = () => wrapper.findAll('[data-prenotazione]')

  it('ordine numerico locale, stato, stanza e medici titolare/effettivo distinti', async () => {
    net.on(lista(), jsonResponse(200, [
      appuntamento(3, { stato: 'CONFERMATA', idStanza: 2, nomeStanza: 'Ambulatorio 2', idMedicoEffettivo: 5, nomeMedicoEffettivo: 'Marco', cognomeMedicoEffettivo: 'Prova' }),
      appuntamento(1, { stato: 'CONFERMATA', idMedicoEffettivo: 4, nomeMedicoEffettivo: 'Elena', cognomeMedicoEffettivo: 'Sintetica' }),
      appuntamento(2, { idPrestazione: null, nomePrestazione: null, durataPrestazione: null }),
    ]))
    await mountApp('/paziente/prenotazioni')
    expect(voci().map((v) => v.get('a').attributes('href'))).toEqual(['/paziente/prenotazioni/1', '/paziente/prenotazioni/2', '/paziente/prenotazioni/3'])
    const [b, c, a] = voci()
    expect(a.get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(a.get('[data-campo="stanza"]').text()).toBe('Ambulatorio 2')
    expect(a.findAll('dt').map((d) => d.text())).toEqual(expect.arrayContaining(['Medico titolare', 'Medico effettivo']))
    expect(a.findAll('[data-campo="medico"]').map((d) => d.text())).toEqual(['Elena Sintetica', 'Marco Prova'])
    expect(b.findAll('[data-campo="medico"]').map((d) => d.text())).toEqual(['Elena Sintetica'])
    expect(b.find('[data-campo="stanza"]').exists()).toBe(false)
    expect(c.get('h3').text()).toBe('Prestazione non disponibile')
    expect(c.get('[data-campo="stato"]').text()).toBe('Richiesta inviata, in attesa di conferma')
    expect(c.get('[data-campo="quando"]').text()).toBe('martedì 20/10/2026, 09:00–10:00')
  })

  it('filtri: query vuote omesse, data singola o intervallo, mai entrambi', async () => {
    net.on(lista(), jsonResponse(200, []))
    await mountApp('/paziente/prenotazioni')
    await wrapper.get('#filtro-stato').setValue('CONFERMATA')
    net.on(lista(7, '&stato=CONFERMATA'), jsonResponse(200, []))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('input[value="singola"]').setValue(true)
    await wrapper.get('#filtro-data').setValue('2026-10-20')
    net.on(lista(7, '&stato=CONFERMATA&data=2026-10-20'), jsonResponse(200, []))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('#filtro-stato').setValue('')
    await wrapper.get('input[value="intervallo"]').setValue(true)
    expect(wrapper.find('#filtro-data').exists()).toBe(false)
    await wrapper.get('#filtro-dal').setValue('2026-10-01')
    await wrapper.get('#filtro-al').setValue('2026-10-31')
    net.on(lista(7, '&dataDa=2026-10-01&dataA=2026-10-31'), jsonResponse(200, []))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('input[value="tutte"]').setValue(true)
    net.on(lista(), jsonResponse(200, []))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(net.calls.filter((c) => c.url.startsWith('/api/prenotazioni')).map((c) => c.url)).toEqual([
      '/api/prenotazioni/paziente?idPaziente=7',
      '/api/prenotazioni/paziente?idPaziente=7&stato=CONFERMATA',
      '/api/prenotazioni/paziente?idPaziente=7&stato=CONFERMATA&data=2026-10-20',
      '/api/prenotazioni/paziente?idPaziente=7&dataDa=2026-10-01&dataA=2026-10-31',
      '/api/prenotazioni/paziente?idPaziente=7',
    ])
    expect(main().text()).toContain('Non hai ancora prenotazioni.')
  })

  it.each([
    [{ dal: '2026-10-31', al: '2026-10-01' }, 'non può precedere'],
    [{ dal: '2026-10-01', al: '' }, 'data di fine valida'],
    [{ dal: '', al: '2026-10-01' }, 'data di inizio valida'],
  ])('intervallo non valido %j: errore accessibile, nessuna richiesta', async ({ dal, al }, testo) => {
    net.on(lista(), jsonResponse(200, []))
    await mountApp('/paziente/prenotazioni')
    await wrapper.get('input[value="intervallo"]').setValue(true)
    await wrapper.get('#filtro-dal').setValue(dal)
    await wrapper.get('#filtro-al').setValue(al)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(main().text()).toContain(testo)
    expect(wrapper.find('[aria-invalid="true"]').exists()).toBe(true)
    expect(document.activeElement?.getAttribute('role')).toBe('alert')
    expect(net.callsTo(lista())).toHaveLength(1)
  })

  it('vuoto con filtri, errore e recupero manuale', async () => {
    net.on(lista(), jsonResponse(500, { error: 'x' }))
    await mountApp('/paziente/prenotazioni')
    expect(wrapper.get('[role="alert"]').text()).toContain('Non è stato possibile caricare le prenotazioni')
    expect(net.callsTo(lista())).toHaveLength(1)
    net.on(lista(), jsonResponse(200, [appuntamento(1)]))
    await bottone('Riprova').trigger('click')
    await flushPromises()
    expect(voci()).toHaveLength(1)
    await wrapper.get('#filtro-stato').setValue('ANNULLATA')
    net.on(lista(7, '&stato=ANNULLATA'), jsonResponse(200, []))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(main().text()).toContain('Nessuna prenotazione per i filtri scelti.')
  })

  it('risposta non conforme: errore, nessun dato parziale', async () => {
    net.on(lista(), jsonResponse(200, [appuntamento(1), { idPrenotazione: 2 }]))
    await mountApp('/paziente/prenotazioni')
    expect(main().text()).toContain('risposta non valida')
    expect(voci()).toHaveLength(0)
  })

  it('risposte fuori ordine: vince l\'ultima lettura avviata', async () => {
    net.on(lista(), jsonResponse(200, []))
    await mountApp('/paziente/prenotazioni')
    await wrapper.get('#filtro-stato').setValue('RICHIESTA')
    const vecchia = net.trattieni(lista(7, '&stato=RICHIESTA'))
    await wrapper.get('form').trigger('submit')
    await wrapper.get('#filtro-stato').setValue('CONFERMATA')
    const nuova = net.trattieni(lista(7, '&stato=CONFERMATA'))
    await wrapper.get('form').trigger('submit')
    nuova.resolve(jsonResponse(200, [appuntamento(2, { stato: 'CONFERMATA' })]))
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [appuntamento(1), appuntamento(3)]))
    await flushPromises()
    expect(voci()).toHaveLength(1)
    expect(voci()[0].get('[data-campo="stato"]').text()).toBe('Confermata')
  })

  it('risposta dopo lo smontaggio: scartata senza errori', async () => {
    const trattenuta = net.trattieni(lista())
    const { router } = await mountApp('/paziente/prenotazioni')
    await vai(router, '/area')
    trattenuta.resolve(jsonResponse(200, [appuntamento(1)]))
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('area')
    expect(wrapper.find('[data-prenotazione]').exists()).toBe(false)
  })

  it('rotazione della sessione: dati azzerati e una sola nuova lettura; la risposta vecchia è scartata', async () => {
    const vecchia = net.trattieni(lista())
    const { session } = await mountApp('/paziente/prenotazioni')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(lista(), jsonResponse(200, [appuntamento(2)]))
    await session.changePassword('a', 'b')
    await flushPromises()
    vecchia.resolve?.(jsonResponse(200, [appuntamento(1), appuntamento(3)]))
    await flushPromises()
    expect(voci().map((v) => v.get('a').attributes('href'))).toEqual(['/paziente/prenotazioni/2'])
    expect(net.callsTo(lista())).toHaveLength(2)
  })

  it('cambio di identità: dati e incertezze del precedente utente eliminati, lettura con il nuovo ID', async () => {
    net.on(lista(), jsonResponse(200, [appuntamento(1)]))
    const { session } = await mountApp('/paziente/prenotazioni')
    expect(voci()).toHaveLength(1)
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    net.on(ME, meOk(utente({ idUtente: 8, nome: 'Bruno' }), CSRF_B))
    net.on(lista(8), jsonResponse(200, []))
    await session.verifyAfterPasswordChange()
    expect(voci()).toHaveLength(0)
    await flushPromises()
    expect(net.callsTo(lista(8))).toHaveLength(1)
    expect(main().text()).toContain('Non hai ancora prenotazioni.')
  })

  it('logout: la vista viene chiusa e i dati non restano visibili', async () => {
    net.on(lista(), jsonResponse(200, [appuntamento(1)]))
    const { router, session } = await mountApp('/paziente/prenotazioni')
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(wrapper.find('[data-prenotazione]').exists()).toBe(false)
    expect(net.callsTo(lista())).toHaveLength(1)
  })
})

describe('/paziente/prenotazioni/:id', () => {
  it('apertura diretta: rilegge l\'elenco senza filtri e cerca l\'ID', async () => {
    net.on('GET /api/prenotazioni/12/pagamenti', jsonResponse(200, []))
    net.on(lista(), jsonResponse(200, [appuntamento(11), appuntamento(12, {
      stato: 'CONFERMATA', idStanza: 1, nomeStanza: 'Ambulatorio 1', idMedicoEffettivo: 5, nomeMedicoEffettivo: 'Marco', cognomeMedicoEffettivo: 'Prova',
    })]))
    await mountApp('/paziente/prenotazioni/12')
    const scheda = wrapper.get('[data-prenotazione]')
    expect(scheda.get('[data-campo="stato"]').text()).toBe('Confermata')
    expect(scheda.get('[data-campo="stanza"]').text()).toBe('Ambulatorio 1')
    expect(scheda.findAll('[data-campo="medico"]').map((d) => d.text())).toEqual(['Elena Sintetica', 'Marco Prova'])
    // per una prenotazione CONFERMATA si legge anche lo storico pagamenti della stessa risorsa;
    // nessuna chiamata a referti, rimborsi o API di altri profili.
    expect(net.calls.filter((c) => c.url.startsWith('/api/prenotazioni')).map((c) => c.url))
      .toEqual(['/api/prenotazioni/paziente?idPaziente=7', '/api/prenotazioni/12/pagamenti'])
    expect(net.calls.some((c) => /referto|rimbors|agenda|richieste/.test(c.url))).toBe(false)
  })

  it.each(['abc', '0', '012', '2147483648', '1e3'])('ID non valido %s: nessuna richiesta', async (id) => {
    await mountApp(`/paziente/prenotazioni/${id}`)
    expect(main().text()).toContain('Prenotazione non trovata.')
    expect(net.calls.some((c) => c.url.startsWith('/api/prenotazioni'))).toBe(false)
  })

  it('ID assente dall\'elenco personale: "Prenotazione non trovata"', async () => {
    net.on(lista(), jsonResponse(200, [appuntamento(11)]))
    await mountApp('/paziente/prenotazioni/99')
    expect(wrapper.get('[role="alert"]').text()).toContain('Prenotazione non trovata.')
  })

  it.each(['ANNULLATA', 'RIFIUTATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA'])('stato %s: nessuna azione di annullamento', async (stato) => {
    net.on(lista(), jsonResponse(200, [appuntamento(12, { stato })]))
      if (stato === 'CONFERMATA') net.on('GET /api/prenotazioni/12/pagamenti', jsonResponse(200, []))
    await mountApp('/paziente/prenotazioni/12')
    expect(bottone('Annulla prenotazione')).toBeUndefined()
  })

  describe('annullamento', () => {
    async function apri(stato = 'RICHIESTA') {
      net.on(lista(), jsonResponse(200, [appuntamento(12, { stato })]))
      if (stato === 'CONFERMATA') net.on('GET /api/prenotazioni/12/pagamenti', jsonResponse(200, []))
      const ctx = await mountApp('/paziente/prenotazioni/12')
      await bottone('Annulla prenotazione').trigger('click')
      await flushPromises()
      return ctx
    }

    it('conferma esplicita da tastiera, statoAtteso dalla risorsa, rilettura dopo il successo', async () => {
      await apri('CONFERMATA')
      expect(document.activeElement?.id).toBe('titolo-conferma-annullamento')
      expect(net.callsTo(annulla(12))).toHaveLength(0)
      net.on(annulla(12), jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
      net.on(lista(), jsonResponse(200, [appuntamento(12, { stato: 'ANNULLATA' })]))
      const conferma = bottone('Conferma annullamento')
      expect(conferma.attributes('type')).toBe('button')
      await conferma.trigger('click')
      await flushPromises()
      expect(corpo(net.callsTo(annulla(12))[0])).toEqual({ idPaziente: 7, statoAtteso: 'CONFERMATA' })
      expect(net.callsTo(lista())).toHaveLength(2)
      expect(wrapper.get('[data-campo="stato"]').text()).toBe('Annullata')
      expect(main().text()).toContain('Annullamento registrato.')
      expect(bottone('Annulla prenotazione')).toBeUndefined()
    })

    it('Non annullare: nessuna scrittura', async () => {
      await apri()
      await bottone('Non annullare').trigger('click')
      await flushPromises()
      expect(bottone('Annulla prenotazione')).toBeDefined()
      expect(net.callsTo(annulla(12))).toHaveLength(0)
    })

    it('409: messaggio e rilettura, senza ripetere la scrittura', async () => {
      await apri()
      net.on(annulla(12), jsonResponse(409, { error: 'Pagamenti associati impediscono l\'annullamento.' }))
      net.on(lista(), jsonResponse(200, [appuntamento(12, { stato: 'CONFERMATA' })]))
      await bottone('Conferma annullamento').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('Non è stato possibile annullare la prenotazione')
      expect(wrapper.get('[data-campo="stato"]').text()).toBe('Confermata')
      expect(net.callsTo(annulla(12))).toHaveLength(1)
      expect(net.callsTo(lista())).toHaveLength(2)
    })

    it('doppio clic sulla conferma: una sola POST', async () => {
      await apri()
      const trattenuta = net.trattieni(annulla(12))
      const conferma = bottone('Conferma annullamento')
      await conferma.trigger('click')
      await conferma.trigger('click')
      await flushPromises()
      expect(bottone('Annullamento in corso…').attributes('disabled')).toBeDefined()
      net.on(lista(), jsonResponse(200, [appuntamento(12, { stato: 'ANNULLATA' })]))
      trattenuta.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
      await flushPromises()
      expect(net.callsTo(annulla(12))).toHaveLength(1)
    })

    it('esito incerto: reinvio bloccato, verifica manuale con stato corrente non attribuito', async () => {
      const { router } = await apri()
      net.on(annulla(12), 'rete')
      await bottone('Conferma annullamento').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-incerto]').text()).toContain("Non è stato possibile stabilire l'esito dell'annullamento")
      expect(document.activeElement?.hasAttribute('data-incerto')).toBe(true)
      expect(bottone('Annulla prenotazione')).toBeUndefined()
      expect(net.callsTo(lista())).toHaveLength(1)

      // Navigazione e riapertura diretta: il blocco resta finché non si verifica.
      net.on(lista(), jsonResponse(200, [appuntamento(12)]))
      await vai(router, '/paziente/prenotazioni')
      expect(main().text()).toContain('Esito di un annullamento da verificare.')
      net.on(lista(), jsonResponse(200, [appuntamento(12)]))
      await vai(router, '/paziente/prenotazioni/12')
      expect(wrapper.find('[data-incerto]').exists()).toBe(true)
      expect(bottone('Annulla prenotazione')).toBeUndefined()

      net.on(lista(), jsonResponse(200, [appuntamento(12)]))
      await bottone('Verifica lo stato').trigger('click')
      await flushPromises()
      expect(main().text()).toContain('Stato attuale: Richiesta inviata, in attesa di conferma')
      expect(main().text()).toContain('potrebbe non dipendere dal tuo ultimo tentativo')
      expect(wrapper.find('[data-incerto]').exists()).toBe(false)
      expect(bottone('Annulla prenotazione')).toBeDefined()
      expect(net.callsTo(annulla(12))).toHaveLength(1)
    })

    it('verifica fallita: il blocco resta', async () => {
      await apri()
      net.on(annulla(12), jsonResponse(503, { error: 'x' }))
      await bottone('Conferma annullamento').trigger('click')
      await flushPromises()
      net.on(lista(), 'rete')
      await bottone('Verifica lo stato').trigger('click')
      await flushPromises()
      expect(main().text()).toContain('Non è stato possibile caricare la prenotazione')
      net.on(lista(), jsonResponse(200, [appuntamento(12)]))
      await bottone('Riprova').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-incerto]').exists()).toBe(true)
      expect(bottone('Annulla prenotazione')).toBeUndefined()
    })

    it('passwordUncertain: annullamento disabilitato, nessuna POST', async () => {
      const { session } = await apri()
      net.on(PWD, 'rete')
      await session.changePassword('a', 'b')
      await flushPromises()
      expect(bottone('Conferma annullamento').attributes('disabled')).toBeDefined()
      await bottone('Conferma annullamento').trigger('click')
      await flushPromises()
      expect(net.callsTo(annulla(12))).toHaveLength(0)
      expect(main().text()).toContain("finché non verifichi l'esito del cambio password")
    })
  })
})
