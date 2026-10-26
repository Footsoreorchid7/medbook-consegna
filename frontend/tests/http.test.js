// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  HttpError, NetworkError, ProtocolError, StaleResponseError, parseRetryAfter, request,
} from '../src/api/http.js'
import { CSRF_A, installFetch, jsonResponse, scriptedFetch } from './helpers.js'

let script
let consoleSpies

beforeEach(() => {
  script = installFetch(scriptedFetch())
  consoleSpies = ['log', 'info', 'warn', 'error', 'debug'].map((m) => vi.spyOn(console, m))
})

afterEach(() => {
  // Il wrapper non scrive mai in console (password, token o corpi).
  for (const spy of consoleSpies) expect(spy).not.toHaveBeenCalled()
})

describe('parseRetryAfter', () => {
  const now = Date.parse('Wed, 15 Oct 2026 10:00:00 GMT')
  it('accetta i secondi', () => {
    expect(parseRetryAfter('300', now)).toBe(300)
    expect(parseRetryAfter(' 60 ', now)).toBe(60)
    expect(parseRetryAfter('0', now)).toBe(0)
  })
  it('accetta una data HTTP', () => {
    expect(parseRetryAfter('Wed, 15 Oct 2026 10:00:42 GMT', now)).toBe(42)
  })
  it('una data passata vale zero', () => {
    expect(parseRetryAfter('Wed, 15 Oct 2026 09:00:00 GMT', now)).toBe(0)
  })
  it('valori assenti o non validi danno null', () => {
    expect(parseRetryAfter(null, now)).toBeNull()
    expect(parseRetryAfter('-5', now)).toBeNull()
    expect(parseRetryAfter('1.5', now)).toBeNull()
    expect(parseRetryAfter('domani', now)).toBeNull()
    expect(parseRetryAfter('2026-10-15T10:00:00', now)).toBeNull()
  })
})

describe('request: corpi di risposta', () => {
  it('JSON 200', async () => {
    script.reply(jsonResponse(200, [{ id: 1 }]))
    const result = await request({ path: '/api/prova' })
    expect(result).toMatchObject({ status: 200, hasBody: true, data: [{ id: 1 }] })
  })

  it('204 senza corpo', async () => {
    script.reply(new Response(null, { status: 204 }))
    const result = await request({ method: 'DELETE', path: '/api/prova/1' })
    expect(result.hasBody).toBe(false)
    expect(result.data).toBeUndefined()
  })

  it('200 con corpo vuoto distinto da {}', async () => {
    script.reply(new Response('', { status: 200 }))
    script.reply(jsonResponse(200, {}))
    const vuoto = await request({ path: '/api/a' })
    const oggetto = await request({ path: '/api/b' })
    expect(vuoto).toMatchObject({ hasBody: false, data: undefined })
    expect(oggetto).toMatchObject({ hasBody: true, data: {} })
  })

  it('200 non JSON o JSON malformato è un errore di protocollo', async () => {
    script.reply(new Response('<html></html>', { status: 200, headers: { 'Content-Type': 'text/html' } }))
    script.reply(new Response('{"a":', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await expect(request({ path: '/api/a' })).rejects.toBeInstanceOf(ProtocolError)
    await expect(request({ path: '/api/b' })).rejects.toBeInstanceOf(ProtocolError)
  })

  it('blob PDF', async () => {
    script.reply(new Response(new Uint8Array([37, 80, 68, 70]), { status: 200, headers: { 'Content-Type': 'application/pdf' } }))
    const result = await request({ path: '/api/doc', responseType: 'blob' })
    expect(result.hasBody).toBe(true)
    expect(result.data.size).toBe(4)
  })

  it('in modalità blob un errore JSON viene letto prima del blob', async () => {
    script.reply(jsonResponse(404, { error: 'Referto non trovato.' }))
    const error = await request({ path: '/api/doc', responseType: 'blob' }).catch((e) => e)
    expect(error).toBeInstanceOf(HttpError)
    expect(error.status).toBe(404)
    expect(error.serverMessage).toBe('Referto non trovato.')
  })

  it('blob con tipo inatteso è un errore di protocollo', async () => {
    script.reply(jsonResponse(200, { ok: true }))
    await expect(request({ path: '/api/doc', responseType: 'blob' })).rejects.toBeInstanceOf(ProtocolError)
  })
})

describe('request: errori', () => {
  it('errore HTTP JSON con Retry-After', async () => {
    script.reply(jsonResponse(429, { error: 'Troppi tentativi.' }, { 'Retry-After': '300' }))
    const error = await request({ path: '/api/a' }).catch((e) => e)
    expect(error).toBeInstanceOf(HttpError)
    expect(error).toMatchObject({ status: 429, serverMessage: 'Troppi tentativi.', retryAfter: 300 })
  })

  it('errore HTTP non JSON resta un HttpError senza messaggio', async () => {
    script.reply(new Response('Bad Gateway', { status: 502, headers: { 'Content-Type': 'text/plain' } }))
    script.reply(new Response('{rotto', { status: 500, headers: { 'Content-Type': 'application/json' } }))
    const a = await request({ path: '/api/a' }).catch((e) => e)
    const b = await request({ path: '/api/b' }).catch((e) => e)
    expect(a).toBeInstanceOf(HttpError)
    expect(a).toMatchObject({ status: 502, serverMessage: null })
    expect(b).toMatchObject({ status: 500, serverMessage: null })
  })

  it('errore di rete distinto, con scrittura marcata come esito incerto', async () => {
    script.networkError()
    const error = await request({ method: 'POST', path: '/api/a', json: {} }).catch((e) => e)
    expect(error).toBeInstanceOf(NetworkError)
    expect(error.write).toBe(true)
  })

  it('nessun retry automatico di una scrittura', async () => {
    script.reply(jsonResponse(503, { error: 'x' }, { 'Retry-After': '5' }))
    await expect(request({ method: 'PUT', path: '/api/a', json: {} })).rejects.toBeInstanceOf(HttpError)
    expect(script.fn).toHaveBeenCalledTimes(1)
  })

  it('risposta arrivata dopo il cambio di epoca è obsoleta', async () => {
    let current = true
    const handle = script.defer()
    const pending = request({ path: '/api/a', isCurrent: () => current })
    current = false
    handle.resolve(jsonResponse(401, { error: 'Autenticazione richiesta.' }))
    await expect(pending).rejects.toBeInstanceOf(StaleResponseError)
  })

  it('una lettura annullata per cambio di epoca è obsoleta, non un errore di rete', async () => {
    let current = true
    const controller = new AbortController()
    script.defer()
    const pending = request({ path: '/api/a', signal: controller.signal, isCurrent: () => current })
    current = false
    controller.abort()
    await expect(pending).rejects.toBeInstanceOf(StaleResponseError)
  })
})

describe('request: header', () => {
  it('Content-Type JSON solo con corpo JSON, CSRF solo sulle scritture, mai Origin', async () => {
    script.reply(jsonResponse(200, {})).reply(jsonResponse(200, {})).reply(new Response(null, { status: 204 }))
    await request({ path: '/api/a', csrfToken: CSRF_A })
    await request({ method: 'POST', path: '/api/b', json: { x: 1 }, csrfToken: CSRF_A })
    await request({ method: 'POST', path: '/api/c', csrfToken: CSRF_A })

    const [get, postJson, postVuoto] = script.calls
    expect(get.headers.get('Content-Type')).toBeNull()
    expect(get.headers.get('X-CSRF-Token')).toBeNull()
    expect(postJson.headers.get('Content-Type')).toBe('application/json')
    expect(postJson.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(postJson.init.body).toBe('{"x":1}')
    expect(postVuoto.init.body).toBeUndefined()
    expect(postVuoto.headers.get('Content-Type')).toBeNull()
    for (const call of script.calls) {
      expect(call.headers.has('Origin')).toBe(false)
      expect(call.init.credentials).toBe('same-origin')
    }
  })

  it('corpo binario con il Content-Type indicato', async () => {
    script.reply(jsonResponse(201, { idReferto: 1 }))
    const pdf = new Blob([new Uint8Array([1, 2, 3])])
    await request({ method: 'POST', path: '/api/doc', body: pdf, contentType: 'application/pdf', csrfToken: CSRF_A })
    expect(script.calls[0].headers.get('Content-Type')).toBe('application/pdf')
    expect(script.calls[0].init.body).toBe(pdf)
  })

})

describe('request: confinamento sotto /api/', () => {
  it.each([
    '/api/auth/me',
    '/api/prenotazioni/12/referto/documento',
    '/api/prenotazioni/richieste?medicoId=3&data=2026-10-15',
    '/api/utenti/ricerca?email=anna%40example.test&limite=20',
    '/api/disponibilita/5?medicoId=7',
    '/api/prova?testo=..%2F..%2Faltro',
  ])('accetta %s e lo passa invariato a fetch', async (path) => {
    script.reply(jsonResponse(200, {}))
    await request({ method: 'POST', path, json: {}, csrfToken: CSRF_A })
    expect(script.calls[0].url).toBe(path)
    expect(script.calls[0].headers.get('X-CSRF-Token')).toBe(CSRF_A)
  })

  it.each([
    ['dot-segment', '/api/../outside'],
    ['dot-segment finale', '/api/auth/..'],
    ['segmento punto', '/api/./auth/me'],
    ['dot-segment codificato', '/api/%2e%2e/outside'],
    ['dot-segment codificato maiuscolo', '/api/%2E%2E/outside'],
    ['dot-segment misto', '/api/.%2e/outside'],
    ['punto codificato', '/api/%2e/auth/me'],
    ['slash codificato', '/api/a%2F..%2F..%2Foutside'],
    ['backslash codificato', '/api/a%5C..%5Coutside'],
    ['backslash', '/api/\\..\\outside'],
    ['codifica non valida', '/api/%zz'],
    ['URL assoluta', 'https://esterno.example/api/a'],
    ['URL protocol-relative', '//esterno.example/api/a'],
    ['schema senza host', 'http:/api/a'],
    ['percorso relativo', 'api/a'],
    ['fuori da /api', '/altro'],
    ['maiuscole', '/API/a'],
    ['solo prefisso', '/api'],
    ['segmento vuoto finale', '/api/'],
    ['segmento vuoto interno', '/api//a'],
    ['frammento', '/api/a#b'],
    ['spazio', '/api/a b'],
    ['carattere di controllo', '/api/a\n'],
    ['non stringa', undefined],
  ])('rifiuta %s (%s) prima di fetch, anche in scrittura con CSRF', async (_, path) => {
    await expect(request({ method: 'POST', path, csrfToken: CSRF_A })).rejects.toBeInstanceOf(TypeError)
    await expect(request({ path })).rejects.toBeInstanceOf(TypeError)
    expect(script.fn).not.toHaveBeenCalled()
  })

  it('un URL assoluto della stessa origine non è accettato', async () => {
    vi.stubGlobal('location', { origin: 'http://localhost:5173' })
    await expect(request({ path: 'http://localhost:5173/api/a' })).rejects.toBeInstanceOf(TypeError)
    expect(script.fn).not.toHaveBeenCalled()
  })
})
