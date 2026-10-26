// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest'
import { HttpError, StaleResponseError } from '../src/api/http.js'
import {
  AVVISO, AuthBusyError, AuthStateError, STATO, capabilitiesOf, createSession,
} from '../src/session/session.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, scriptedFetch, unauthorized, utente } from './helpers.js'

let script

beforeEach(() => {
  script = installFetch(scriptedFetch())
})

async function sessioneAutenticata(user = utente(), csrf = CSRF_A) {
  script.reply(meOk(user, csrf))
  const session = createSession()
  await session.ensureBootstrap()
  expect(session.state.status).toBe(STATO.AUTENTICATO)
  return session
}

async function sessioneAnonima() {
  script.reply(unauthorized())
  const session = createSession()
  await session.ensureBootstrap()
  expect(session.state.status).toBe(STATO.ANONIMO)
  return session
}

describe('bootstrap /auth/me', () => {
  it('200 valido: autenticato, identità e capacità', async () => {
    const session = await sessioneAutenticata()
    expect(session.state.user).toMatchObject({ idUtente: 7, nome: 'Anna' })
    expect(session.capabilities.value).toMatchObject({ paziente: true, medico: false, desk: false, admin: false })
    expect(script.calls[0].url).toBe('/api/auth/me')
  })

  it('401: anonimo', async () => {
    const session = await sessioneAnonima()
    expect(session.state.user).toBeNull()
    expect(session.state.bootstrapError).toBeNull()
  })

  it('errore di rete: errore di verifica, non anonimo', async () => {
    script.networkError()
    const session = createSession()
    await session.ensureBootstrap()
    expect(session.state.status).toBe(STATO.ERRORE)
    expect(session.state.bootstrapError).toBe('rete')
  })

  it('500: errore di verifica', async () => {
    script.reply(jsonResponse(500, { error: 'Errore interno del server.' }))
    const session = createSession()
    await session.ensureBootstrap()
    expect(session.state.status).toBe(STATO.ERRORE)
    expect(session.state.bootstrapError).toBe('http')
  })

  it('200 senza CSRF o con identità non valida: errore di protocollo', async () => {
    for (const response of [
      jsonResponse(200, utente()),
      jsonResponse(200, utente(), { 'X-CSRF-Token': 'corto' }),
      jsonResponse(200, utente({ ruoloGestionale: 'SCONOSCIUTO' }), { 'X-CSRF-Token': CSRF_A }),
      jsonResponse(200, { idUtente: '7' }, { 'X-CSRF-Token': CSRF_A }),
      new Response('', { status: 200, headers: { 'X-CSRF-Token': CSRF_A } }),
    ]) {
      script.reply(response)
      const session = createSession()
      await session.ensureBootstrap()
      expect(session.state.status).toBe(STATO.ERRORE)
      expect(session.state.bootstrapError).toBe('protocollo')
      expect(session.state.user).toBeNull()
    }
  })

  it('promessa condivisa: una sola richiesta per più chiamanti', async () => {
    const handle = script.defer()
    const session = createSession()
    const a = session.ensureBootstrap()
    const b = session.ensureBootstrap()
    expect(a).toBe(b)
    expect(session.state.status).toBe(STATO.VERIFICA)
    handle.resolve(meOk())
    await Promise.all([a, b])
    expect(script.fn).toHaveBeenCalledTimes(1)
  })

  it('riprova manuale solo dopo un errore', async () => {
    script.networkError()
    const session = createSession()
    await session.ensureBootstrap()
    script.reply(meOk())
    await session.retryBootstrap()
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    await expect(session.retryBootstrap()).rejects.toBeInstanceOf(AuthStateError)
  })
})

describe('capacità', () => {
  it('paziente e medico si cumulano', () => {
    expect(capabilitiesOf(utente({ paziente: true, medico: true }))).toEqual({ paziente: true, medico: true, desk: false, admin: false })
  })
  it('amministratore non eredita desk', () => {
    expect(capabilitiesOf(utente({ paziente: false, ruoloGestionale: 'AMMINISTRATORE' }))).toEqual({ paziente: false, medico: false, desk: false, admin: true })
  })
  it('operatore desk', () => {
    expect(capabilitiesOf(utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' }))).toEqual({ paziente: false, medico: false, desk: true, admin: false })
  })
  it('nessun utente, nessuna capacità', () => {
    expect(capabilitiesOf(null)).toEqual({ paziente: false, medico: false, desk: false, admin: false })
  })
})

describe('login', () => {
  it('invia soltanto email e password, senza CSRF', async () => {
    const session = await sessioneAnonima()
    script.reply(meOk(utente(), CSRF_B))
    const outcome = await session.login('anna@example.test', 'x')
    expect(outcome).toEqual({ ok: true })
    const call = script.calls[1]
    expect(call.url).toBe('/api/auth/login')
    expect(JSON.parse(call.init.body)).toEqual({ email: 'anna@example.test', password: 'x' })
    expect(call.headers.get('Content-Type')).toBe('application/json')
    expect(call.headers.has('X-CSRF-Token')).toBe(false)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
  })

  it('401 = credenziali errate, non sessione persa', async () => {
    const session = await sessioneAnonima()
    script.reply(jsonResponse(401, { error: 'Credenziali non valide.' }))
    const outcome = await session.login('anna@example.test', 'sbagliata')
    expect(outcome.kind).toBe('credenziali')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBeNull()
    expect(session.state.loginUncertain).toBe(false)
  })

  it('429 e 503 riportano Retry-After senza nuovi tentativi', async () => {
    const session = await sessioneAnonima()
    script.reply(jsonResponse(429, { error: 'x' }, { 'Retry-After': '300' }))
    expect(await session.login('a@example.test', 'p')).toEqual({ ok: false, kind: 'troppi-tentativi', retryAfter: 300 })
    script.reply(jsonResponse(503, { error: 'x' }, { 'Retry-After': '60' }))
    expect(await session.login('a@example.test', 'p')).toEqual({ ok: false, kind: 'non-disponibile', retryAfter: 60 })
    expect(script.fn).toHaveBeenCalledTimes(3)
  })

  it('esito incerto: nuovo login bloccato fino alla verifica manuale', async () => {
    const session = await sessioneAnonima()
    script.networkError()
    expect((await session.login('a@example.test', 'p')).kind).toBe('incerto')
    expect(session.state.loginUncertain).toBe(true)
    await expect(session.login('a@example.test', 'p')).rejects.toBeInstanceOf(AuthStateError)

    script.reply(unauthorized())
    expect(await session.verifySession()).toBe('anonimo')
    expect(session.state.loginUncertain).toBe(false)
    expect(script.calls.at(-1).url).toBe('/api/auth/me')
  })

  it('verifica manuale dopo esito incerto che trova una sessione valida', async () => {
    const session = await sessioneAnonima()
    script.reply(jsonResponse(200, utente()))
    expect((await session.login('a@example.test', 'p')).kind).toBe('incerto')
    script.reply(meOk(utente(), CSRF_B))
    expect(await session.verifySession()).toBe('autenticato')
    expect(session.state.status).toBe(STATO.AUTENTICATO)
  })
})

describe('richieste protette', () => {
  it('403 non provoca logout', async () => {
    const session = await sessioneAutenticata()
    script.reply(jsonResponse(403, { error: 'Operazione non autorizzata.' }))
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toMatchObject({ status: 403 })
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(session.state.user).not.toBeNull()
  })

  it('401 sulla sessione attuale: sessione persa', async () => {
    const session = await sessioneAutenticata()
    script.reply(unauthorized())
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toBeInstanceOf(HttpError)
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBe(AVVISO.SESSIONE_SCADUTA)
    expect(session.state.user).toBeNull()
  })

  it('CSRF solo sulle scritture; nessun retry dopo 409', async () => {
    const session = await sessioneAutenticata()
    script.reply(jsonResponse(200, [])).reply(jsonResponse(409, { error: 'Conflitto.' }))
    await session.protectedRequest({ path: '/api/stanze' })
    await expect(session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })).rejects.toMatchObject({ status: 409 })
    expect(script.calls[1].headers.has('X-CSRF-Token')).toBe(false)
    expect(script.calls[2].headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(script.fn).toHaveBeenCalledTimes(3)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
  })

  it('vecchio 401 arrivato dopo una nuova sessione non la invalida', async () => {
    const session = await sessioneAutenticata(utente(), CSRF_A)
    const vecchia = script.defer()
    const scrittura = session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })

    script.reply(jsonResponse(200, { message: 'Logout effettuato.' }))
    await session.logout()
    script.reply(meOk(utente({ idUtente: 9 }), CSRF_B))
    await session.login('b@example.test', 'p')

    vecchia.resolve(unauthorized())
    const error = await scrittura.catch((e) => e)
    expect(error).toBeInstanceOf(StaleResponseError)
    expect(error.write).toBe(true)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(session.state.user.idUtente).toBe(9)

    script.reply(jsonResponse(200, {}))
    await session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })
    expect(script.calls.at(-1).headers.get('X-CSRF-Token')).toBe(CSRF_B)
  })

  it('le letture della sessione precedente vengono annullate al logout', async () => {
    const session = await sessioneAutenticata()
    script.defer()
    const lettura = session.protectedRequest({ path: '/api/stanze' })
    script.reply(jsonResponse(200, {}))
    await session.logout()
    await expect(lettura).rejects.toBeInstanceOf(StaleResponseError)
  })

  it('le scritture non ricevono il segnale di annullamento', async () => {
    const session = await sessioneAutenticata()
    script.reply(jsonResponse(200, {}))
    await session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })
    expect(script.calls.at(-1).init.signal).toBeUndefined()
  })

  it('rifiutate fuori da una sessione autenticata', async () => {
    const session = await sessioneAnonima()
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toBeInstanceOf(AuthStateError)
  })
})

describe('serializzazione delle operazioni di autenticazione', () => {
  it('login rifiutato mentre il bootstrap è in corso', async () => {
    const handle = script.defer()
    const session = createSession()
    const bootstrap = session.ensureBootstrap()
    await expect(session.login('a@example.test', 'p')).rejects.toBeInstanceOf(AuthBusyError)
    handle.resolve(unauthorized())
    await bootstrap
    expect(script.fn).toHaveBeenCalledTimes(1)
  })

  it('logout concorrenti e login durante il logout rifiutati', async () => {
    const session = await sessioneAutenticata()
    const handle = script.defer()
    const primo = session.logout()
    await expect(session.logout()).rejects.toBeInstanceOf(AuthBusyError)
    await expect(session.login('a@example.test', 'p')).rejects.toBeInstanceOf(AuthBusyError)
    handle.resolve(jsonResponse(200, { message: 'Logout effettuato.' }))
    expect(await primo).toBe('confermato')
    expect(script.fn).toHaveBeenCalledTimes(2)
  })

  it('login concorrenti rifiutati', async () => {
    const session = await sessioneAnonima()
    const handle = script.defer()
    const primo = session.login('a@example.test', 'p')
    await expect(session.login('a@example.test', 'p')).rejects.toBeInstanceOf(AuthBusyError)
    handle.resolve(meOk())
    expect(await primo).toEqual({ ok: true })
  })
})

describe('logout', () => {
  it('POST senza corpo con CSRF; identità rimossa prima della risposta', async () => {
    const session = await sessioneAutenticata()
    const handle = script.defer()
    const pending = session.logout()
    expect(session.state.status).toBe(STATO.DISCONNESSIONE)
    expect(session.state.user).toBeNull()
    const call = script.calls.at(-1)
    expect(call.url).toBe('/api/auth/logout')
    expect(call.init.method).toBe('POST')
    expect(call.init.body).toBeUndefined()
    expect(call.headers.has('Content-Type')).toBe(false)
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    handle.resolve(jsonResponse(200, { message: 'Logout effettuato.' }))
    expect(await pending).toBe('confermato')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBe(AVVISO.LOGOUT_CONFERMATO)
  })

  it('401: sessione già assente', async () => {
    const session = await sessioneAutenticata()
    script.reply(unauthorized())
    expect(await session.logout()).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBe(AVVISO.SESSIONE_ASSENTE)
  })

  it.each([
    ['500', () => jsonResponse(500, { error: 'Errore interno del server.' })],
    ['403', () => jsonResponse(403, { error: 'Richiesta non consentita.' })],
    ['rete', null],
  ])('%s: logout non confermato, nuovo login bloccato', async (_, response) => {
    const session = await sessioneAutenticata()
    if (response) script.reply(response())
    else script.networkError()
    expect(await session.logout()).toBe('non-confermato')
    expect(session.state.status).toBe(STATO.LOGOUT_NON_CONFERMATO)
    expect(session.state.user).toBeNull()
    await expect(session.login('a@example.test', 'p')).rejects.toBeInstanceOf(AuthStateError)
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toBeInstanceOf(AuthStateError)
  })

  it('recupero manuale: /me valido, poi logout con il CSRF recuperato', async () => {
    const session = await sessioneAutenticata(utente(), CSRF_A)
    script.networkError()
    await session.logout()
    script.reply(meOk(utente(), CSRF_B)).reply(jsonResponse(200, { message: 'Logout effettuato.' }))
    expect(await session.recoverLogout()).toBe('confermato')
    const [me, logout] = script.calls.slice(-2)
    expect(me.url).toBe('/api/auth/me')
    expect(logout.url).toBe('/api/auth/logout')
    expect(logout.headers.get('X-CSRF-Token')).toBe(CSRF_B)
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.user).toBeNull()
  })

  it('recupero manuale: /me 401 chiude senza nuovo logout', async () => {
    const session = await sessioneAutenticata()
    script.reply(jsonResponse(500, { error: 'x' }))
    await session.logout()
    script.reply(unauthorized())
    expect(await session.recoverLogout()).toBe('sessione-assente')
    expect(script.calls.at(-1).url).toBe('/api/auth/me')
    expect(session.state.status).toBe(STATO.ANONIMO)
  })

  it('recupero manuale fallito: stato invariato, nessuna vista privata ripristinata', async () => {
    const session = await sessioneAutenticata()
    script.networkError()
    await session.logout()
    script.reply(meOk(utente(), CSRF_B)).networkError()
    expect(await session.recoverLogout()).toBe('non-confermato')
    expect(session.state.status).toBe(STATO.LOGOUT_NON_CONFERMATO)
    expect(session.state.user).toBeNull()
    expect(script.pending()).toBe(0)
  })
})
