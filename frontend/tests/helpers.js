import { vi } from 'vitest'

// Dati fittizi: nessuna credenziale o identità reale.
export const CSRF_A = 'A'.repeat(43)
export const CSRF_B = 'B'.repeat(43)

export function utente(override = {}) {
  return { idUtente: 7, nome: 'Anna', cognome: 'Prova', paziente: true, medico: false, ruoloGestionale: null, ...override }
}

export function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=UTF-8', ...headers },
  })
}

export function meOk(user = utente(), csrf = CSRF_A) {
  return jsonResponse(200, user, { 'X-CSRF-Token': csrf })
}

export function unauthorized() {
  return jsonResponse(401, { error: 'Autenticazione richiesta.' })
}

/**
 * fetch simulato a copione: ogni chiamata consuma il passo successivo.
 * Una chiamata non prevista fallisce il test.
 */
export function scriptedFetch() {
  const calls = []
  const steps = []
  const fn = vi.fn((url, init = {}) => {
    calls.push({ url, init, headers: new Headers(init.headers) })
    const step = steps.shift()
    if (!step) return Promise.reject(new Error(`fetch inatteso: ${init.method ?? 'GET'} ${url}`))
    return step(init)
  })
  return {
    fn,
    calls,
    reply(response) {
      steps.push(() => Promise.resolve(typeof response === 'function' ? response() : response))
      return this
    },
    networkError() {
      steps.push(() => Promise.reject(new TypeError('Failed to fetch')))
      return this
    },
    /** Risposta trattenuta; rispetta l'annullamento tramite signal come fetch reale. */
    defer() {
      const handle = { resolve: null, called: false }
      steps.push((init) => new Promise((resolve, reject) => {
        handle.called = true
        handle.resolve = resolve
        init.signal?.addEventListener('abort', () => reject(new DOMException('Annullata', 'AbortError')))
      }))
      return handle
    },
    pending() {
      return steps.length
    },
  }
}

export function installFetch(script) {
  vi.stubGlobal('fetch', script.fn)
  return script
}

/**
 * fetch simulato instradato per "METODO /percorso?query": ogni chiave ha una coda di passi
 * (Response, funzione o 'rete'). Una chiamata senza passo previsto fallisce il test.
 * `trattieni(chiave)` restituisce una risposta da completare a mano, che rispetta l'annullamento.
 */
export function routedFetch() {
  const calls = []
  const routes = new Map()
  const queue = (k) => { if (!routes.has(k)) routes.set(k, []); return routes.get(k) }
  const fn = vi.fn((url, init = {}) => {
    const method = (init.method ?? 'GET').toUpperCase()
    const k = `${method} ${url}`
    calls.push({ url, method, init, headers: new Headers(init.headers), body: init.body })
    const step = queue(k).shift()
    if (!step) return Promise.reject(new Error(`fetch inatteso: ${k}`))
    return step(init)
  })
  const api = {
    fn,
    calls,
    on(k, ...responses) {
      for (const r of responses) {
        queue(k).push(r === 'rete'
          ? () => Promise.reject(new TypeError('Failed to fetch'))
          : () => Promise.resolve(typeof r === 'function' ? r() : r))
      }
      return api
    },
    trattieni(k) {
      const handle = { resolve: null, aborted: false }
      queue(k).push((init) => new Promise((resolve, reject) => {
        handle.resolve = resolve
        init.signal?.addEventListener('abort', () => { handle.aborted = true; reject(new DOMException('Annullata', 'AbortError')) })
      }))
      return handle
    },
    pending: (k) => queue(k).length,
    callsTo: (k) => calls.filter((c) => `${c.method} ${c.url}` === k),
  }
  return api
}
