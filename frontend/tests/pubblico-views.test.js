import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { STATO, createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'
const REG = 'POST /api/auth/register'
const PWD = 'PUT /api/auth/password'

const PRESTAZIONI = [
  { idPrestazione: 1, nome: 'Visita cardiologica', descrizione: 'Con ECG', durata: 30, prezzo: 80 },
  { idPrestazione: 2, nome: 'Ecografia', descrizione: null, durata: 45, prezzo: 95.5 },
]
const slot = (id, inizio, fine, extra = {}) => ({
  idDisponibilita: id, dataOraInizio: inizio, dataOraFine: fine, idMedico: 4, nomeMedico: 'Elena', cognomeMedico: 'Sintetica',
  idPrestazione: 1, nomePrestazione: 'Visita cardiologica', durataPrestazione: 30, prezzoPrestazione: 80, ...extra,
})

let net
let wrapper

beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

async function mountApp(path, me = unauthorized()) {
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

const main = () => wrapper.get('main')

describe('/prestazioni', () => {
  it('elenco con descrizione facoltativa, durata e prezzo in euro', async () => {
    net.on(CAT, jsonResponse(200, PRESTAZIONI))
    await mountApp('/prestazioni')
    const voci = wrapper.findAll('[data-prestazione]')
    expect(voci).toHaveLength(2)
    expect(voci[0].text()).toContain('Visita cardiologica')
    expect(voci[0].text()).toContain('Con ECG')
    expect(voci[0].text()).toContain('30 minuti')
    expect(voci[0].text()).toMatch(/80,00\s€/)
    expect(voci[1].text()).toMatch(/95,50\s€/)
  })

  it('elenco vuoto', async () => {
    net.on(CAT, jsonResponse(200, []))
    await mountApp('/prestazioni')
    expect(main().text()).toContain('Nessuna prestazione disponibile')
  })

  it('errore con Riprova manuale, nessun tentativo automatico', async () => {
    net.on(CAT, jsonResponse(500, { error: 'x' }))
    await mountApp('/prestazioni')
    expect(wrapper.get('[role="alert"]').text()).toContain('Non è stato possibile caricare il catalogo')
    expect(net.callsTo(CAT)).toHaveLength(1)
    net.on(CAT, jsonResponse(200, PRESTAZIONI))
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-prestazione]')).toHaveLength(2)
  })
})

describe('/disponibilita', () => {
  async function apri() {
    net.on(CAT, jsonResponse(200, PRESTAZIONI))
    return mountApp('/disponibilita')
  }
  const query = (id, data = '2026-10-20') => `${DISP}?prestazioneId=${id}&data=${data}`
  async function cerca(id, data) {
    await wrapper.get('#filtro-prestazione').setValue(String(id))
    await wrapper.get('#filtro-data').setValue(data)
    await wrapper.get('form').trigger('submit'); await flushPromises()
  }
  it('nessuna ricerca iniziale; date e orari della struttura restano senza spostamenti', async () => {
    await apri()
    expect(net.callsTo(DISP)).toHaveLength(0)
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(0)
    net.on(query(1, '2026-12-31'), jsonResponse(200, [slot(10, '2026-12-31T23:30:00', '2027-01-01T00:00:00')]))
    await cerca(1, '2026-12-31')
    const voce = wrapper.get('[data-disponibilita]')
    expect(voce.get('[data-campo="data"]').text()).toBe('giovedì 31/12/2026')
    expect(voce.get('[data-campo="orario"]').text()).toBe('23:30–00:00')
    expect(voce.text()).toContain('Elena Sintetica')
    expect(voce.text()).toMatch(/80,00\s€/)
    net.on(query(1, '2026-03-29'), jsonResponse(200, [slot(11, '2026-03-29T02:30:00', '2026-03-29T03:00:00')]))
    await cerca(1, '2026-03-29')
    expect(wrapper.get('[data-campo="data"]').text()).toBe('domenica 29/03/2026')
    expect(wrapper.get('[data-campo="orario"]').text()).toBe('02:30–03:00')
    expect(main().text()).toContain('riconfermata al momento della richiesta')
  })
  it('filtri obbligatori nella UI; soltanto query ammesse dopo submit valido', async () => {
    await apri()
    await wrapper.get('#filtro-prestazione').setValue('2')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(net.calls.filter(c => c.url.startsWith('/api/disponibilita/'))).toHaveLength(0)
    net.on(query(2), jsonResponse(200, []))
    await cerca(2, '2026-10-20')
    expect(main().text()).toContain('Nessuna disponibilità per i filtri scelti')
    await wrapper.get('#filtro-prestazione').setValue('')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(net.calls.filter(c => c.url.startsWith('/api/disponibilita/')).map(c => c.url)).toEqual(['/api/disponibilita/prenotabili?prestazioneId=2&data=2026-10-20'])
    expect(main().text()).toContain('Seleziona una prestazione')
  })
  it('risposte fuori ordine: la ricerca vecchia non sostituisce quella corrente', async () => {
    await apri()
    const vecchia = net.trattieni(query(1))
    await cerca(1, '2026-10-20')
    const nuova = net.trattieni(query(2))
    await cerca(2, '2026-10-20')
    expect(vecchia.aborted).toBe(true)
    nuova.resolve(jsonResponse(200, [slot(20, '2026-10-20T10:00:00', '2026-10-20T11:00:00', { idPrestazione: 2, nomePrestazione: 'Ecografia' })]))
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [slot(10, '2026-10-20T09:00:00', '2026-10-20T10:00:00')]))
    await flushPromises()
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(1)
    expect(wrapper.get('[data-disponibilita]').text()).toContain('Ecografia')
    expect(main().text()).toContain('prestazione: Ecografia')
  })
  it('risposta vecchia che ignora l’annullamento: scartata comunque', async () => {
    await apri()
    let vecchia
    net.on(query(1), () => new Promise(resolve => { vecchia = resolve }))
    await cerca(1, '2026-10-20')
    net.on(query(1, '2026-10-21'), jsonResponse(200, [slot(21, '2026-10-21T08:00:00', '2026-10-21T09:00:00')]))
    await cerca(1, '2026-10-21')
    vecchia(jsonResponse(200, [slot(20, '2026-10-20T08:00:00', '2026-10-20T09:00:00')]))
    await flushPromises()
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(1)
    expect(wrapper.get('[data-campo="data"]').text()).toBe('mercoledì 21/10/2026')
    expect(main().text()).toContain('data: 21/10/2026')
  })
  it('errore catalogo recuperabile prima della scelta; errore ricerca con Riprova manuale', async () => {
    net.on(CAT, 'rete')
    await mountApp('/disponibilita')
    expect(wrapper.get('#filtro-prestazione').attributes('disabled')).toBeDefined()
    expect(main().text()).toContain('Elenco delle prestazioni non disponibile')
    expect(net.callsTo(DISP)).toHaveLength(0)
    net.on(CAT, jsonResponse(200, PRESTAZIONI))
    await wrapper.get('#stato-catalogo button').trigger('click'); await flushPromises()
    net.on(query(1), jsonResponse(503, { error: 'x' }))
    await cerca(1, '2026-10-20')
    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('Non è stato possibile caricare le disponibilità')
    net.on(query(1), jsonResponse(200, []))
    await alert.get('button').trigger('click'); await flushPromises()
    expect(main().text()).toContain('Nessuna disponibilità')
  })
})

describe('/registrazione', () => {
  async function compila(dati = {}) {
    const valori = {
      nome: 'Anna', cognome: 'Prova', email: 'anna.prova@example.test', password: ' segreta lunga ', conferma: ' segreta lunga ',
      codiceFiscale: 'PRVNNA90A41Z999X', dataNascita: '1990-01-01', telefono: '', ...dati,
    }
    for (const [campo, valore] of Object.entries(valori)) await wrapper.get(`#${campo}`).setValue(valore)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
  }

  it('payload esatto, 201 senza login automatico, campi password puliti', async () => {
    const { session } = await mountApp('/registrazione')
    net.on(REG, jsonResponse(201, { message: 'Registrazione completata. Accedi per continuare.' }))
    await compila({ telefono: '+39 333 0000000' })
    const [call] = net.callsTo(REG)
    expect(JSON.parse(call.body)).toEqual({
      nome: 'Anna', cognome: 'Prova', email: 'anna.prova@example.test', password: ' segreta lunga ',
      codiceFiscale: 'PRVNNA90A41Z999X', dataNascita: '1990-01-01', telefono: '+39 333 0000000',
    })
    expect(call.headers.get('Content-Type')).toBe('application/json')
    expect(call.headers.has('X-CSRF-Token')).toBe(false)
    expect(main().text()).toContain('Registrazione completata.')
    expect(net.callsTo('POST /api/auth/login')).toHaveLength(0)
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(wrapper.find('#password').exists()).toBe(false)
  })

  it('errori di validazione locali: nessun invio e riepilogo accessibile', async () => {
    await mountApp('/registrazione')
    await compila({ conferma: 'diversa', codiceFiscale: 'CORTO' })
    expect(net.callsTo(REG)).toHaveLength(0)
    const riepilogo = wrapper.get('[role="alert"]')
    expect(riepilogo.text()).toContain('Conferma password')
    expect(riepilogo.text()).toContain('Codice fiscale')
    expect(wrapper.get('#codiceFiscale').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#password').element.value).toBe('')
    expect(wrapper.get('#conferma').element.value).toBe('')
  })

  it('409: messaggio generico senza indicare il dato duplicato', async () => {
    await mountApp('/registrazione')
    net.on(REG, jsonResponse(409, { error: 'Registrazione non disponibile con i dati forniti.' }))
    await compila()
    const testo = wrapper.get('[role="alert"]').text()
    expect(testo).toContain('Non è possibile completare la registrazione con i dati forniti.')
    expect(testo).not.toMatch(/email|codice fiscale/i)
    expect(wrapper.get('#password').element.value).toBe('')
    expect(wrapper.get('#email').element.value).toBe('anna.prova@example.test')
  })

  it('429: invio disabilitato fino a Retry-After', async () => {
    await mountApp('/registrazione')
    vi.useFakeTimers()
    net.on(REG, jsonResponse(429, { error: 'x' }, { 'Retry-After': '2' }))
    await compila()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(main().text()).toContain('Potrai riprovare tra 2 secondi.')
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(REG)).toHaveLength(1)
  })

  it.each([['rete', 'rete'], ['500', () => jsonResponse(500, { error: 'x' })]])(
    'esito incerto (%s): non dichiarato fallito, accesso proposto, nessun nuovo invio', async (_, risposta) => {
      await mountApp('/registrazione')
      net.on(REG, risposta)
      await compila()
      const avviso = wrapper.get('.notice.warning')
      expect(avviso.text()).toContain('Non è stato possibile stabilire se la registrazione sia stata completata.')
      expect(avviso.get('a').attributes('href')).toBe('/accesso')
      expect(main().text()).not.toContain('Registrazione completata')
      expect(wrapper.get('#password').element.value).toBe('')
      expect(net.callsTo(REG)).toHaveLength(1)
    })

  it('doppio invio impedito', async () => {
    await mountApp('/registrazione')
    const pendente = net.trattieni(REG)
    for (const [campo, valore] of Object.entries({
      nome: 'Anna', cognome: 'Prova', email: 'a@example.test', password: '12345678', conferma: '12345678',
      codiceFiscale: 'PRVNNA90A41Z999X', dataNascita: '1990-01-01',
    })) await wrapper.get(`#${campo}`).setValue(valore)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    pendente.resolve(jsonResponse(201, { message: 'ok' }))
    await flushPromises()
    expect(net.callsTo(REG)).toHaveLength(1)
  })

  it('con sessione attiva la registrazione non è disponibile', async () => {
    const { router } = await mountApp('/registrazione', meOk())
    expect(router.currentRoute.value.name).toBe('area')
  })
})

describe('/area/password', () => {
  async function apri(user = utente()) {
    const ctx = await mountApp('/area/password', meOk(user))
    expect(ctx.router.currentRoute.value.name).toBe('cambio-password')
    return ctx
  }
  async function compila(attuale = 'vecchia-password', nuova = 'nuova-password-1', conferma = nuova) {
    await wrapper.get('#password-attuale').setValue(attuale)
    await wrapper.get('#password-nuova').setValue(nuova)
    await wrapper.get('#password-conferma').setValue(conferma)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
  }
  const campiVuoti = () => ['#password-attuale', '#password-nuova', '#password-conferma']
    .every((id) => !wrapper.find(id).exists() || wrapper.get(id).element.value === '')

  it('minimo 12 per medico: errore locale senza invio', async () => {
    await apri(utente({ medico: true }))
    expect(main().text()).toContain('Almeno 12 caratteri')
    await compila('vecchia', 'undici-car')
    expect(net.callsTo(PWD)).toHaveLength(0)
    expect(wrapper.get('#password-nuova').attributes('aria-invalid')).toBe('true')
    expect(campiVuoti()).toBe(true)
  })

  it('aggiornata: conferma, sessione mantenuta, campi puliti', async () => {
    const { session, router } = await apri()
    net.on(PWD, jsonResponse(200, { message: 'x', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await compila()
    expect(JSON.parse(net.callsTo(PWD)[0].body)).toEqual({ passwordAttuale: 'vecchia-password', nuovaPassword: 'nuova-password-1' })
    expect(main().text()).toContain('Password aggiornata. La sessione è stata rinnovata.')
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(router.currentRoute.value.name).toBe('cambio-password')
    expect(campiVuoti()).toBe(true)
  })

  it('nuovo accesso richiesto: pagina di accesso con avviso', async () => {
    const { router } = await apri()
    net.on(PWD, jsonResponse(200, { message: 'x', nuovoAccessoRichiesto: true }))
    await compila()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('accesso'))
    await flushPromises()
    expect(main().text()).toContain('Password aggiornata. Accedi di nuovo con la nuova password.')
  })

  it('401: sessione assente, ritorno all\'accesso', async () => {
    const { router } = await apri()
    net.on(PWD, unauthorized())
    await compila()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('accesso'))
    expect(router.currentRoute.value.query.redirect).toBe('/area/password')
  })

  it('403: resta autenticato con messaggio non conclusivo', async () => {
    const { session } = await apri()
    net.on(PWD, jsonResponse(403, { error: 'Richiesta non consentita.' }))
    await compila()
    const testo = wrapper.get('[role="alert"]').text()
    expect(testo).toContain('La password attuale potrebbe non essere corretta, oppure')
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(campiVuoti()).toBe(true)
  })

  it('429: invio disabilitato fino a Retry-After', async () => {
    await apri()
    vi.useFakeTimers()
    net.on(PWD, jsonResponse(429, { error: 'x' }, { 'Retry-After': '3' }))
    await compila()
    expect(main().text()).toContain('Potrai riprovare tra 3 secondi.')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })

  it('esito incerto: modulo nascosto, verifica /me riuscita senza dichiarare la password cambiata', async () => {
    await apri()
    net.on(PWD, 'rete')
    await compila()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(main().text()).toContain('Non è stato possibile stabilire se la password sia stata cambiata.')
    net.on(ME, meOk(utente(), CSRF_B))
    await wrapper.get('.notice.warning button').trigger('click')
    await flushPromises()
    const testo = main().text()
    expect(testo).toContain('La sessione è attiva. Questo non indica se la password sia stata cambiata')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(net.callsTo(PWD)).toHaveLength(1)
  })

  it('esito incerto e /me 401: nuovo accesso richiesto', async () => {
    const { router } = await apri()
    net.on(PWD, jsonResponse(500, { error: 'x' }))
    await compila()
    net.on(ME, unauthorized())
    await wrapper.get('.notice.warning button').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('accesso'))
    await flushPromises()
    expect(main().text()).toContain('non è stato possibile stabilire se la password sia stata cambiata')
  })

  it('Esci disabilitato durante il cambio password', async () => {
    await apri()
    const put = net.trattieni(PWD)
    await wrapper.get('#password-attuale').setValue('vecchia-password')
    await wrapper.get('#password-nuova').setValue('nuova-password-1')
    await wrapper.get('#password-conferma').setValue('nuova-password-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const esci = wrapper.findAll('header button').find((b) => b.text() === 'Esci')
    expect(esci.attributes('disabled')).toBeDefined()
    put.resolve(jsonResponse(200, { message: 'x', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await flushPromises()
    expect(esci.attributes('disabled')).toBeUndefined()
  })
})

describe('navigazione', () => {
  it('anonimo: link solo a pagine implementate', async () => {
    await mountApp('/')
    const header = wrapper.findAll('header a').map((a) => a.attributes('href'))
    expect(header).toEqual(['/', '/accesso', '/registrazione'])
    expect(wrapper.findAll('nav a').map((a) => a.attributes('href'))).toEqual(['/prestazioni', '/disponibilita'])
  })

  it('autenticato: area e cambio password, nessuna registrazione', async () => {
    await mountApp('/area', meOk(utente({ medico: true })))
    // Intestazione: marchio e menu del profilo (area riservata e cambio password).
    const header = wrapper.findAll('header a').map((a) => a.attributes('href'))
    expect(header).toEqual(['/', '/area', '/area/password'])
    expect(wrapper.findAll('nav a').map((a) => a.attributes('href'))).toEqual(['/area', '/paziente/prenota', '/paziente/prenotazioni', '/paziente/rimborsi', '/medico/agenda', '/medico/disponibilita', '/prestazioni', '/disponibilita'])
    expect(wrapper.findAll('a').map((a) => a.attributes('href'))).not.toContain('/registrazione')
    expect(wrapper.findAll('main a').map((a) => a.attributes('href'))).not.toContain('/area/password')
  })
})
