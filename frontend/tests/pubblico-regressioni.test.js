import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { postRegister } from '../src/api/auth.js'
import { ProtocolError } from '../src/api/http.js'
import { createAppRouter } from '../src/router/index.js'
import { PasswordUncertainError, STATO, createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { emailError, validaRegistrazione } from '../src/utils/validazione.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const REG = 'POST /api/auth/register'
const PWD = 'PUT /api/auth/password'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const CAT = 'GET /api/prestazioni'
const DISP = 'GET /api/disponibilita/prenotabili'

const DATI = {
  nome: 'Anna', cognome: 'Prova', email: 'anna.prova@example.test', password: 'segreta-lunga', conferma: 'segreta-lunga',
  codiceFiscale: 'PRVNNA90A41Z999X', dataNascita: '1990-01-01',
}
const PRESTAZIONE = (id, nome) => ({ idPrestazione: id, nome, descrizione: null, durata: 30, prezzo: 50 })
const SLOT = (id, nome) => ({
  idDisponibilita: id, dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00', idMedico: 4,
  nomeMedico: 'Elena', cognomeMedico: 'Sintetica', idPrestazione: 1, nomePrestazione: nome, durataPrestazione: 30, prezzoPrestazione: 50,
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

async function compilaRegistrazione(dati = DATI) {
  for (const [campo, valore] of Object.entries(dati)) await wrapper.get(`#${campo}`).setValue(valore)
}

/** Risposta trattenuta che ignora l'annullamento, come un server già raggiunto. */
function trattieniSenzaAbort(chiave) {
  const handle = {}
  net.on(chiave, () => new Promise((resolve) => { handle.resolve = resolve }))
  return handle
}

describe('successo della registrazione non conforme', () => {
  const base = { nome: 'A', cognome: 'B', email: 'a@example.test', password: '12345678', codiceFiscale: 'X'.repeat(16), dataNascita: '1990-01-01' }

  it.each([
    ['201 {}', () => jsonResponse(201, {})],
    ['201 message vuoto', () => jsonResponse(201, { message: '  ' })],
    ['201 message non stringa', () => jsonResponse(201, { message: 42 })],
    ['201 array', () => jsonResponse(201, [{ message: 'ok' }])],
    ['204', () => new Response(null, { status: 204 })],
    ['200 con message', () => jsonResponse(200, { message: 'ok' })],
    ['202 con message', () => jsonResponse(202, { message: 'ok' })],
  ])('%s: errore di protocollo', async (_, risposta) => {
    net.on(REG, risposta)
    await expect(postRegister(base)).rejects.toBeInstanceOf(ProtocolError)
  })

  it('201 con message qualsiasi: successo, senza vincolo sul testo', async () => {
    net.on(REG, jsonResponse(201, { message: 'Testo diverso da quello attuale del backend' }))
    expect((await postRegister(base)).status).toBe(201)
  })

  it.each([['201 {}', () => jsonResponse(201, {})], ['204', () => new Response(null, { status: 204 })]])(
    'vista con %s: esito incerto, nessuna conferma di creazione, nessun nuovo invio', async (_, risposta) => {
      await mountApp('/registrazione')
      net.on(REG, risposta)
      await compilaRegistrazione()
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('main').text()).not.toContain('Registrazione completata')
      expect(wrapper.get('.notice.warning').text()).toContain('Non è stato possibile stabilire se la registrazione sia stata completata.')
      expect(wrapper.get('#email').element.value).toBe(DATI.email)
      expect(net.callsTo(REG)).toHaveLength(1)
    })
})

describe('registrazione durante autenticazione incerta o in corso', () => {
  it('login incerto: modulo non disponibile, recupero manuale della sessione con /me', async () => {
    const { session, router } = await mountApp('/registrazione')
    net.on(LOGIN, 'rete')
    expect((await session.login('a@example.test', 'p')).kind).toBe('incerto')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    const avviso = wrapper.get('.notice.warning')
    expect(avviso.text()).toContain("Non è stato possibile stabilire l'esito dell'ultimo tentativo di accesso.")

    net.on(ME, unauthorized())
    await avviso.get('button').trigger('click')
    await flushPromises()
    expect(session.state.loginUncertain).toBe(false)
    expect(wrapper.find('form').exists()).toBe(true)
    expect(router.currentRoute.value.name).toBe('registrazione')
    expect(net.callsTo(REG)).toHaveLength(0)
  })

  it('login incerto con sessione esistente: il recupero porta all\'area riservata', async () => {
    const { session, router } = await mountApp('/registrazione')
    net.on(LOGIN, jsonResponse(500, { error: 'x' }))
    await session.login('a@example.test', 'p')
    await flushPromises()
    net.on(ME, meOk())
    await wrapper.get('.notice.warning button').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('area'))
    expect(net.callsTo(REG)).toHaveLength(0)
  })

  it('sessione busy: il gestore di invio rifiuta anche se il modulo è ancora nel DOM', async () => {
    const { session } = await mountApp('/registrazione')
    await compilaRegistrazione()
    const form = wrapper.get('form').element
    const login = net.trattieni(LOGIN)
    const pending = session.login('a@example.test', 'p')
    expect(session.state.busy).toBe(true)
    // Invio sincrono, prima che Vue aggiorni il DOM: deve fermarlo il gestore stesso.
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()
    expect(net.callsTo(REG)).toHaveLength(0)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.get('main').text()).toContain('Operazione di autenticazione in corso')
    login.resolve(jsonResponse(401, { error: 'x' }))
    await pending
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('logout non confermato ed errore di verifica restano bloccati', async () => {
    const { session } = await mountApp('/registrazione', meOk())
    net.on(LOGOUT, 'rete')
    await session.logout()
    expect(session.state.status).toBe(STATO.LOGOUT_NON_CONFERMATO)
    await wrapper.vm.$router.push('/registrazione')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
    wrapper = null

    const { router } = await mountApp('/registrazione', 'rete')
    expect(router.currentRoute.value.name).toBe('registrazione')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(net.callsTo(REG)).toHaveLength(0)
  })
})

describe('risultati tardivi della registrazione', () => {
  it.each([
    ['successo', () => jsonResponse(201, { message: 'ok' })],
    ['429', () => jsonResponse(429, { error: 'x' }, { 'Retry-After': '30' })],
    ['409', () => jsonResponse(409, { error: 'x' })],
  ])('%s dopo un cambio di epoca: scartato senza messaggi né countdown', async (_, risposta) => {
    const { session } = await mountApp('/registrazione')
    const post = trattieniSenzaAbort(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#password').element.value).toBe('')
    const epoca = session.state.epoch
    net.on(ME, meOk())
    await session.verifySession()
    expect(session.state.epoch).toBeGreaterThan(epoca)
    vi.useFakeTimers()
    post.resolve(risposta())
    await flushPromises()
    const testo = wrapper.get('main').text()
    expect(testo).not.toContain('Registrazione completata')
    expect(testo).not.toContain('Troppi tentativi')
    expect(testo).not.toContain('Non è possibile completare')
    expect(testo).not.toContain('Potrai riprovare')
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    ['successo', () => jsonResponse(201, { message: 'ok' })],
    ['429', () => jsonResponse(429, { error: 'x' }, { 'Retry-After': '30' })],
  ])('%s dopo lo smontaggio: nessun effetto e nessun timer', async (_, risposta) => {
    const { router } = await mountApp('/registrazione')
    const post = trattieniSenzaAbort(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    net.on(CAT, jsonResponse(200, []))
    await router.push('/prestazioni')
    await flushPromises()
    vi.useFakeTimers()
    post.resolve(risposta())
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    vi.useRealTimers()
    await router.push('/registrazione')
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    expect(wrapper.get('main').text()).not.toContain('Registrazione completata')
    expect(wrapper.get('main').text()).not.toContain('Potrai riprovare')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })

  it('campi del modulo disabilitati durante il POST, riabilitati al termine', async () => {
    await mountApp('/registrazione')
    const post = net.trattieni(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const fieldset = wrapper.get('form fieldset')
    expect(fieldset.attributes('disabled')).toBeDefined()
    expect(fieldset.get('legend').text()).toBe('Dati di registrazione')
    for (const id of ['nome', 'email', 'password', 'conferma', 'telefono']) {
      expect(wrapper.get(`#${id}`).element.matches(':disabled')).toBe(true)
    }
    // Anti-doppio invio anche nel gestore.
    await wrapper.get('form').trigger('submit')
    expect(net.callsTo(REG)).toHaveLength(1)
    post.resolve(jsonResponse(400, { error: 'x' }))
    await flushPromises()
    expect(wrapper.get('form fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('#nome').element.matches(':disabled')).toBe(false)
  })

  it('201 tardivo: azzerati solo i valori del tentativo, non quelli cambiati dopo', async () => {
    await mountApp('/registrazione')
    const post = net.trattieni(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    // Cambi programmatici durante l'invio (i campi sono disabilitati per l'utente).
    const set = (id, value) => {
      const el = wrapper.get(`#${id}`).element
      el.value = value
      el.dispatchEvent(new Event('input'))
    }
    set('password', 'nuova-password-dopo')
    set('conferma', 'nuova-password-dopo')
    set('email', 'altra@example.test')
    await flushPromises()
    post.resolve(jsonResponse(201, { message: 'ok' }))
    await flushPromises()
    expect(wrapper.get('main').text()).toContain('Registrazione completata.')
    const campi = wrapper.findComponent({ name: 'RegistrazioneView' }).vm.$.setupState.campi
    expect(campi.password).toBe('nuova-password-dopo')
    expect(campi.conferma).toBe('nuova-password-dopo')
    expect(campi.email).toBe('altra@example.test')
    expect(campi.nome).toBe('')
    expect(campi.codiceFiscale).toBe('')
    expect(campi.dataNascita).toBe('')
  })

  it('cambio di epoca con la stessa istanza: esito scartato, pending sbloccato, nessun timer', async () => {
    const { session } = await mountApp('/registrazione')
    const post = trattieniSenzaAbort(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const vista = wrapper.findComponent({ name: 'RegistrazioneView' })
    expect(vista.vm.$.setupState.pending).toBe(true)
    // Nuova epoca restando anonimi e sulla stessa pagina: login riuscito poi sessione persa.
    net.on(LOGIN, meOk())
    await session.login('a@example.test', 'p')
    net.on('GET /api/stanze', unauthorized())
    await session.protectedRequest({ path: '/api/stanze' }).catch(() => {})
    expect(session.state.status).toBe(STATO.ANONIMO)
    await flushPromises()
    // Stessa istanza del componente: nessun rimontaggio durante il cambio di epoca.
    expect(wrapper.findComponent({ name: 'RegistrazioneView' }).vm.$.uid).toBe(vista.vm.$.uid)
    vi.useFakeTimers()
    post.resolve(jsonResponse(429, { error: 'x' }, { 'Retry-After': '30' }))
    await flushPromises()
    expect(vista.vm.$.setupState.pending).toBe(false)
    expect(vista.vm.$.setupState.esito).toBeNull()
    expect(wrapper.get('main').text()).not.toContain('Troppi tentativi')
    expect(vi.getTimerCount()).toBe(0)
    expect(wrapper.get('form fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })

  it('la pulizia delle password del tentativo non tocca quanto digitato dopo', async () => {
    await mountApp('/registrazione')
    const post = net.trattieni(REG)
    await compilaRegistrazione()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#password').element.value).toBe('')
    expect(wrapper.get('#conferma').element.value).toBe('')
    await wrapper.get('#password').setValue('digitata-durante-l-invio')
    post.resolve(jsonResponse(400, { error: 'Dati di registrazione non validi.' }))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Alcuni dati non sono stati accettati.')
    expect(wrapper.get('#password').element.value).toBe('digitata-durante-l-invio')
  })
})

describe('contratto del cambio password', () => {
  async function autenticata() {
    net.on(ME, meOk(utente(), CSRF_A))
    const session = createSession()
    await session.ensureBootstrap()
    return session
  }

  it.each([
    ['202 nel ramo false con token valido', () => jsonResponse(202, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B })],
    ['201 nel ramo false con token valido', () => jsonResponse(201, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B })],
    ['ramo false senza message', () => jsonResponse(200, { nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B })],
    ['ramo false con message vuoto', () => jsonResponse(200, { message: '', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B })],
    ['esito non booleano', () => jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: 'false' }, { 'X-CSRF-Token': CSRF_B })],
    ['ramo true con token contraddittorio', () => jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: true }, { 'X-CSRF-Token': CSRF_B })],
    ['ramo true senza message', () => jsonResponse(200, { nuovoAccessoRichiesto: true })],
    ['202 nel ramo true', () => jsonResponse(202, { message: 'ok', nuovoAccessoRichiesto: true })],
  ])('%s: esito incerto, nessuna adozione del CSRF né cambio di epoca', async (_, risposta) => {
    const session = await autenticata()
    const epoca = session.state.epoch
    const user = session.state.user
    net.on(PWD, risposta)
    expect(await session.changePassword('a', 'b')).toEqual({ kind: 'incerto' })
    expect(session.state.passwordUncertain).toBe(true)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(session.state.user).toBe(user)
    expect(session.state.epoch).toBe(epoca)
    // Il token ricevuto non è adottato e nessuna scrittura parte durante l'incertezza.
    await expect(session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} }))
      .rejects.toBeInstanceOf(PasswordUncertainError)
    expect(net.callsTo('POST /api/stanze')).toHaveLength(0)
  })

  it('i due rami conformi restano accettati, con message qualsiasi', async () => {
    const session = await autenticata()
    net.on(PWD, jsonResponse(200, { message: 'Testo libero', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    net.on(PWD, jsonResponse(200, { message: 'Altro testo', nuovoAccessoRichiesto: true }))
    expect((await session.changePassword('a', 'b')).kind).toBe('nuovo-accesso')
  })

  it('dopo una risposta non conforme il recupero manuale con /me resta disponibile', async () => {
    const session = await autenticata()
    net.on(PWD, jsonResponse(202, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    net.on(ME, meOk(utente(), CSRF_B))
    expect(await session.verifyAfterPasswordChange()).toBe('sessione-attiva')
    expect(session.state.passwordUncertain).toBe(false)
  })
})

describe('scritture protette durante l\'incertezza del cambio password', () => {
  const CSRF_C = 'C'.repeat(43)

  async function incerta() {
    net.on(ME, meOk(utente(), CSRF_A))
    const session = createSession()
    await session.ensureBootstrap()
    net.on(PWD, jsonResponse(202, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('incerto')
    return session
  }

  it.each(['POST', 'PUT', 'DELETE', 'PATCH', 'post'])('%s rifiutato prima di fetch, stato invariato', async (method) => {
    const session = await incerta()
    const epoca = session.state.epoch
    const user = session.state.user
    const chiamate = net.calls.length
    const errore = await session.protectedRequest({ method, path: '/api/stanze/1', json: {} }).catch((e) => e)
    expect(errore).toBeInstanceOf(PasswordUncertainError)
    expect(net.calls).toHaveLength(chiamate)
    expect(session.state).toMatchObject({ status: STATO.AUTENTICATO, epoch: epoca, passwordUncertain: true })
    expect(session.state.user).toBe(user)
  })

  it('le letture restano consentite, senza CSRF', async () => {
    const session = await incerta()
    net.on('GET /api/stanze', jsonResponse(200, []))
    await session.protectedRequest({ path: '/api/stanze' })
    expect(net.callsTo('GET /api/stanze')[0].headers.has('X-CSRF-Token')).toBe(false)
  })

  it('recupero valido con /me: scritture sbloccate con il CSRF recuperato', async () => {
    const session = await incerta()
    net.on(ME, meOk(utente(), CSRF_C))
    expect(await session.verifyAfterPasswordChange()).toBe('sessione-attiva')
    net.on('POST /api/stanze', jsonResponse(201, {}))
    await session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })
    expect(net.callsTo('POST /api/stanze')[0].headers.get('X-CSRF-Token')).toBe(CSRF_C)
  })

  it('recupero fallito: il blocco resta', async () => {
    const session = await incerta()
    net.on(ME, 'rete')
    expect(await session.verifyAfterPasswordChange()).toBe('non-verificato')
    await expect(session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} }))
      .rejects.toBeInstanceOf(PasswordUncertainError)
    expect(net.callsTo('POST /api/stanze')).toHaveLength(0)
  })

  it('/me 401: stato anonimo, nessuna richiesta protetta', async () => {
    const session = await incerta()
    net.on(ME, unauthorized())
    expect(await session.verifyAfterPasswordChange()).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.passwordUncertain).toBe(false)
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toThrow()
    expect(net.callsTo('GET /api/stanze')).toHaveLength(0)
  })

  it('il logout esplicito resta utilizzabile', async () => {
    const session = await incerta()
    net.on(LOGOUT, jsonResponse(200, { message: 'x' }))
    expect(await session.logout()).toBe('confermato')
    expect(net.callsTo(LOGOUT)).toHaveLength(1)
    expect(net.callsTo(LOGOUT)[0].headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(session.state.status).toBe(STATO.ANONIMO)
  })
})

describe('letture pubbliche e cambio di sessione', () => {
  async function ruotaSessione(session) {
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('precedente', 'nuova')).kind).toBe('aggiornata')
    await flushPromises()
  }

  it('catalogo: risposta tardiva scartata, una sola rilettura, nessun caricamento sospeso', async () => {
    const vecchia = trattieniSenzaAbort(CAT)
    const { session } = await mountApp('/prestazioni', meOk())
    expect(wrapper.get('main').text()).toContain('Caricamento del catalogo')
    const nuova = trattieniSenzaAbort(CAT)
    await ruotaSessione(session)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(net.callsTo(CAT)).toHaveLength(2)
    vecchia.resolve(jsonResponse(200, [PRESTAZIONE(1, 'Vecchia')]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('Vecchia')
    expect(wrapper.get('main').text()).toContain('Caricamento del catalogo')
    nuova.resolve(jsonResponse(200, [PRESTAZIONE(2, 'Nuova')]))
    await flushPromises()
    expect(wrapper.findAll('[data-prestazione]').map((p) => p.text())).toEqual([expect.stringContaining('Nuova')])
    expect(net.callsTo(CAT)).toHaveLength(2)
  })

  it('catalogo: rilettura fallita dopo il cambio di sessione resta recuperabile', async () => {
    const vecchia = trattieniSenzaAbort(CAT)
    const { session } = await mountApp('/prestazioni', meOk())
    net.on(CAT, jsonResponse(500, { error: 'x' }))
    await ruotaSessione(session)
    vecchia.resolve(jsonResponse(200, []))
    await flushPromises()
    expect(wrapper.get('main').text()).not.toContain('Nessuna prestazione')
    const alert = wrapper.get('main [role="alert"]')
    expect(alert.text()).toContain('Non è stato possibile caricare il catalogo')
    net.on(CAT, jsonResponse(200, [PRESTAZIONE(3, 'Recuperata')]))
    await alert.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('main').text()).toContain('Recuperata')
  })

  it('disponibilità: catalogo dei filtri e ricerca ripetuti con gli ultimi filtri, risposte vecchie scartate', async () => {
    net.on(CAT, jsonResponse(200, [PRESTAZIONE(1, 'Visita'), PRESTAZIONE(2, 'Ecografia')]))
    const { session } = await mountApp('/disponibilita', meOk())
    await wrapper.get('#filtro-prestazione').setValue('2')
    await wrapper.get('#filtro-data').setValue('2026-10-20')
    const vecchiaRicerca = trattieniSenzaAbort(`${DISP}?prestazioneId=2&data=2026-10-20`)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('main').text()).toContain('Ricerca in corso')

    const nuovoCatalogo = trattieniSenzaAbort(CAT)
    const nuovaRicerca = trattieniSenzaAbort(`${DISP}?prestazioneId=2&data=2026-10-20`)
    await ruotaSessione(session)
    expect(net.callsTo(CAT)).toHaveLength(2)
    expect(net.callsTo(`${DISP}?prestazioneId=2&data=2026-10-20`)).toHaveLength(2)

    vecchiaRicerca.resolve(jsonResponse(200, [SLOT(1, 'Vecchia')]))
    await flushPromises()
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(0)
    expect(wrapper.get('main').text()).toContain('Ricerca in corso')
    expect(wrapper.get('main').text()).not.toContain('Nessuna disponibilità')

    nuovoCatalogo.resolve(jsonResponse(200, [PRESTAZIONE(1, 'Visita'), PRESTAZIONE(2, 'Ecografia')]))
    nuovaRicerca.resolve(jsonResponse(200, [SLOT(2, 'Ecografia')]))
    await flushPromises()
    const voci = wrapper.findAll('[data-disponibilita]')
    expect(voci).toHaveLength(1)
    expect(voci[0].text()).toContain('Ecografia')
    expect(wrapper.get('main').text()).toContain('prestazione: Ecografia')
    expect(net.callsTo(`${DISP}?prestazioneId=2&data=2026-10-20`)).toHaveLength(2)
  })
})

describe('normalizzazione dell\'email come il backend', () => {
  it('simbolo Kelvin e maiuscole: ammessi dopo il minuscolo indipendente dalla lingua', () => {
    expect(emailError('K@example.test')).toBeNull()
    expect(emailError('  ANNA.Prova@Example.TEST ')).toBeNull()
    expect(validaRegistrazione({ ...DATI, email: 'K@example.test', telefono: '' }, '2026-10-15')).toEqual({})
  })

  it('limiti valutati prima del minuscolo, sui code point', () => {
    // 64 + 1 + 185 + 5 = 255 code point: ammessa; 256: rifiutata.
    expect(emailError(`${'a'.repeat(64)}@${'b'.repeat(185)}.test`)).toBeNull()
    expect(emailError(`${'a'.repeat(64)}@${'b'.repeat(186)}.test`)).not.toBeNull()
    // Il limite conta i code point del testo originale, prima del minuscolo.
    expect(emailError(`${'a'.repeat(64)}@${'b'.repeat(184)}.tes\u212A`)).toBeNull()
    expect(emailError('anna\u0007@example.test')).not.toBeNull()
  })

  it('email inviata come scritta: normalizza il backend; password invariata', async () => {
    await mountApp('/registrazione')
    net.on(REG, jsonResponse(201, { message: 'ok' }))
    await compilaRegistrazione({ ...DATI, email: 'K@example.test', password: ' Pass Word ', conferma: ' Pass Word ' })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const body = JSON.parse(net.callsTo(REG)[0].body)
    expect(body.email).toBe('K@example.test')
    expect(body.password).toBe(' Pass Word ')
    expect(wrapper.get('main').text()).toContain('Registrazione completata.')
  })
})
