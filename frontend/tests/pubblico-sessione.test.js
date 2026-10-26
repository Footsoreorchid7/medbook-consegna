// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest'
import { StaleResponseError } from '../src/api/http.js'
import { AVVISO, AuthBusyError, AuthStateError, STATO, createSession } from '../src/session/session.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

const PWD = 'PUT /api/auth/password'
const ok = (nuovoAccessoRichiesto, headers = {}) => jsonResponse(200, { message: 'x', nuovoAccessoRichiesto }, headers)

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function autenticata(user = utente()) {
  net.on('GET /api/auth/me', meOk(user, CSRF_A))
  const session = createSession()
  await session.ensureBootstrap()
  expect(session.state.status).toBe(STATO.AUTENTICATO)
  return session
}

describe('cambio password: contratto', () => {
  it('PUT JSON con i due soli campi e il CSRF corrente', async () => {
    const session = await autenticata()
    net.on(PWD, ok(false, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword(' vecchia ', ' nuova-password ')
    const [call] = net.callsTo(PWD)
    expect(JSON.parse(call.body)).toEqual({ passwordAttuale: ' vecchia ', nuovaPassword: ' nuova-password ' })
    expect(call.headers.get('Content-Type')).toBe('application/json')
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(call.init.signal).toBeUndefined()
  })

  it('200 senza nuovo accesso: nuovo CSRF, stessa identità, epoca avanzata, letture precedenti annullate', async () => {
    const session = await autenticata()
    const user = session.state.user
    const epoch = session.state.epoch
    const lettura = net.trattieni('GET /api/stanze')
    const letturaPromise = session.protectedRequest({ path: '/api/stanze' })
    net.on(PWD, ok(false, { 'X-CSRF-Token': CSRF_B }))
    expect(await session.changePassword('a', 'b')).toEqual({ kind: 'aggiornata' })
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(session.state.user).toBe(user)
    expect(session.state.epoch).toBeGreaterThan(epoch)
    await expect(letturaPromise).rejects.toBeInstanceOf(StaleResponseError)
    expect(lettura.aborted).toBe(true)

    net.on('POST /api/stanze', jsonResponse(201, {}))
    await session.protectedRequest({ method: 'POST', path: '/api/stanze', json: {} })
    expect(net.callsTo('POST /api/stanze')[0].headers.get('X-CSRF-Token')).toBe(CSRF_B)
  })

  it('200 con nuovo accesso richiesto: sessione locale eliminata', async () => {
    const session = await autenticata()
    net.on(PWD, ok(true))
    expect(await session.changePassword('a', 'b')).toEqual({ kind: 'nuovo-accesso' })
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.user).toBeNull()
    expect(session.state.notice).toBe(AVVISO.PASSWORD_NUOVO_ACCESSO)
    await expect(session.protectedRequest({ path: '/api/stanze' })).rejects.toBeInstanceOf(AuthStateError)
  })

  it('401: sessione assente', async () => {
    const session = await autenticata()
    net.on(PWD, unauthorized())
    expect((await session.changePassword('a', 'b')).kind).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBe(AVVISO.SESSIONE_SCADUTA)
  })

  it('403: nessun logout, esito non interpretato', async () => {
    const session = await autenticata()
    net.on(PWD, jsonResponse(403, { error: 'Password attuale non corretta.' }))
    expect((await session.changePassword('a', 'b')).kind).toBe('rifiutata')
    net.on(PWD, jsonResponse(403, { error: 'Richiesta non consentita.' }))
    expect((await session.changePassword('a', 'b')).kind).toBe('rifiutata')
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(session.state.passwordUncertain).toBe(false)
  })

  it('400, 409 e 429 con Retry-After, senza nuovi invii', async () => {
    const session = await autenticata()
    net.on(PWD, jsonResponse(400, { error: 'x' }), jsonResponse(409, { error: 'x' }),
      jsonResponse(429, { error: 'x' }, { 'Retry-After': '300' }))
    expect((await session.changePassword('a', 'b')).kind).toBe('non-valida')
    expect((await session.changePassword('a', 'b')).kind).toBe('conflitto')
    expect(await session.changePassword('a', 'b')).toEqual({ kind: 'troppi-tentativi', retryAfter: 300 })
    expect(net.callsTo(PWD)).toHaveLength(3)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
  })

  it.each([
    ['rete', 'rete'],
    ['500', () => jsonResponse(500, { error: 'x' })],
    ['200 senza CSRF', () => ok(false)],
    ['200 senza esito', () => jsonResponse(200, { message: 'x' })],
  ])('%s: esito incerto, nessun nuovo invio prima della verifica', async (_, risposta) => {
    const session = await autenticata()
    net.on(PWD, risposta)
    expect((await session.changePassword('a', 'b')).kind).toBe('incerto')
    expect(session.state.passwordUncertain).toBe(true)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    await expect(session.changePassword('a', 'b')).rejects.toBeInstanceOf(AuthStateError)
    expect(net.callsTo(PWD)).toHaveLength(1)
  })
})

describe('cambio password: verifica manuale', () => {
  async function incerta() {
    const session = await autenticata()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    return session
  }

  it('/me riuscito: sessione verificata, nuovo tentativo consentito', async () => {
    const session = await incerta()
    net.on('GET /api/auth/me', meOk(utente(), CSRF_B))
    expect(await session.verifyAfterPasswordChange()).toBe('sessione-attiva')
    expect(session.state.passwordUncertain).toBe(false)
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    net.on(PWD, jsonResponse(403, { error: 'x' }))
    await session.changePassword('a', 'b')
    expect(net.callsTo(PWD).at(-1).headers.get('X-CSRF-Token')).toBe(CSRF_B)
  })

  it('/me 401: nuovo accesso richiesto', async () => {
    const session = await incerta()
    net.on('GET /api/auth/me', unauthorized())
    expect(await session.verifyAfterPasswordChange()).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.notice).toBe(AVVISO.PASSWORD_INCERTA_NUOVO_ACCESSO)
  })

  it('/me in errore: resta incerto', async () => {
    const session = await incerta()
    net.on('GET /api/auth/me', 'rete')
    expect(await session.verifyAfterPasswordChange()).toBe('non-verificato')
    expect(session.state.passwordUncertain).toBe(true)
  })
})

describe('cambio password: serializzazione ed epoca', () => {
  it('rifiutato durante bootstrap, login o logout; logout rifiutato durante il cambio', async () => {
    const bootstrap = net.trattieni('GET /api/auth/me')
    const session = createSession()
    const pending = session.ensureBootstrap()
    await expect(session.changePassword('a', 'b')).rejects.toBeInstanceOf(AuthBusyError)
    bootstrap.resolve(meOk())
    await pending

    const put = net.trattieni(PWD)
    const cambio = session.changePassword('a', 'b')
    await expect(session.logout()).rejects.toBeInstanceOf(AuthBusyError)
    await expect(session.changePassword('a', 'b')).rejects.toBeInstanceOf(AuthBusyError)
    put.resolve(ok(false, { 'X-CSRF-Token': CSRF_B }))
    expect((await cambio).kind).toBe('aggiornata')

    net.on('POST /api/auth/logout', jsonResponse(200, { message: 'x' }))
    const logout = session.logout()
    await expect(session.changePassword('a', 'b')).rejects.toBeInstanceOf(AuthBusyError)
    await logout
    expect(net.callsTo(PWD)).toHaveLength(1)
  })

  it('risposta arrivata dopo una perdita di sessione: scartata senza adottare CSRF', async () => {
    const session = await autenticata()
    const put = net.trattieni(PWD)
    const cambio = session.changePassword('a', 'b')
    net.on('GET /api/stanze', unauthorized())
    await session.protectedRequest({ path: '/api/stanze' }).catch(() => {})
    expect(session.state.status).toBe(STATO.ANONIMO)
    put.resolve(ok(false, { 'X-CSRF-Token': CSRF_B }))
    expect(await cambio).toEqual({ kind: 'obsoleto' })
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.user).toBeNull()
  })

  it('richiede una sessione autenticata', async () => {
    net.on('GET /api/auth/me', unauthorized())
    const session = createSession()
    await session.ensureBootstrap()
    await expect(session.changePassword('a', 'b')).rejects.toBeInstanceOf(AuthStateError)
    expect(net.callsTo(PWD)).toHaveLength(0)
  })
})
