import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { STATO, createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CSRF_B, installFetch, jsonResponse, meOk, scriptedFetch, unauthorized, utente } from './helpers.js'

let script
let wrapper

beforeEach(() => {
  script = installFetch(scriptedFetch())
  // Questi copioni verificano la sessione ; la nuova home legge anche il catalogo pubblico,
  // verificato separatamente in home.test.js, senza consumare i passi di autenticazione.
  vi.stubGlobal('fetch', (url, init) => url === '/api/prestazioni' && (init?.method ?? 'GET') === 'GET'
    ? Promise.resolve(jsonResponse(200, [])) : script.fn(url, init))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

// Rotta solo di test per verificare il fallback di una destinazione non autorizzata.
const ROTTA_ADMIN_TEST = { path: '/test/admin', name: 'test-admin', component: { render: () => null }, meta: { capability: 'admin' } }

async function mountApp(path, extraRoutes = []) {
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory(), extraRoutes })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { session, router }
}

async function compila(email, password) {
  await wrapper.get('#email').setValue(email)
  await wrapper.get('#password').setValue(password)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('pagina di accesso', () => {
  it('campi etichettati con autocomplete e nessun vincolo di lunghezza', async () => {
    script.reply(unauthorized())
    await mountApp('/accesso')
    const email = wrapper.get('#email')
    const password = wrapper.get('#password')
    expect(wrapper.get('label[for="email"]').text()).toBe('Email')
    expect(wrapper.get('label[for="password"]').text()).toBe('Password')
    expect(email.attributes('autocomplete')).toBe('username')
    expect(password.attributes('autocomplete')).toBe('current-password')
    expect(password.attributes('minlength')).toBeUndefined()
    expect(password.attributes('maxlength')).toBeUndefined()
  })

  it('401: messaggio di credenziali, nessun redirect, password azzerata', async () => {
    script.reply(unauthorized())
    const { router, session } = await mountApp('/accesso?redirect=/area')
    script.reply(jsonResponse(401, { error: 'Credenziali non valide.' }))
    await compila('anna@example.test', 'sbagliata')
    expect(JSON.parse(script.calls[1].init.body)).toEqual({ email: 'anna@example.test', password: 'sbagliata' })
    expect(wrapper.get('[role="alert"]').text()).toContain('Email o password non corrette.')
    expect(wrapper.text()).not.toContain('La sessione è scaduta')
    expect(router.currentRoute.value.fullPath).toBe('/accesso?redirect=/area')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(wrapper.get('#password').element.value).toBe('')
  })

  it('successo: ritorno alla destinazione e riepilogo delle capacità', async () => {
    script.reply(unauthorized())
    const { router } = await mountApp('/accesso?redirect=/area')
    script.reply(meOk(utente({ paziente: true, medico: true }), CSRF_B))
    await compila('anna@example.test', 'giusta')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('area'))
    await flushPromises()
    const capacita = wrapper.findAll('[data-capacita]').map((li) => li.attributes('data-capacita'))
    expect(capacita).toEqual(['paziente', 'medico'])
  })

  it('429: pulsante disabilitato fino a Retry-After, nessun nuovo invio automatico', async () => {
    script.reply(unauthorized())
    await mountApp('/accesso')
    vi.useFakeTimers()
    script.reply(jsonResponse(429, { error: 'Troppi tentativi.' }, { 'Retry-After': '2' }))
    await compila('anna@example.test', 'p')
    const submit = wrapper.get('button[type="submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Potrai riprovare tra 2 secondi.')
    await vi.advanceTimersByTimeAsync(2000)
    expect(submit.attributes('disabled')).toBeUndefined()
    expect(script.fn).toHaveBeenCalledTimes(2)
  })

  it('esito incerto: modulo nascosto e verifica manuale con /me', async () => {
    script.reply(unauthorized())
    await mountApp('/accesso')
    script.networkError()
    await compila('anna@example.test', 'p')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain("Non è stato possibile stabilire l'esito")
    script.reply(unauthorized())
    await wrapper.get('.notice.warning button').trigger('click')
    await flushPromises()
    expect(script.calls.at(-1).url).toBe('/api/auth/me')
    expect(wrapper.find('form').exists()).toBe(true)
  })
})

describe('errore di verifica sulla home', () => {
  it('avviso unico e accessibile con Riprova manuale; recupero autenticato resta sulla home', async () => {
    script.reply(jsonResponse(500, { error: 'Errore interno del server.' }))
    const { router, session } = await mountApp('/')
    expect(router.currentRoute.value.name).toBe('home')
    expect(session.state.status).toBe(STATO.ERRORE)
    const avvisi = wrapper.findAll('[role="alert"]')
    expect(avvisi).toHaveLength(1)
    expect(avvisi[0].text()).toContain('Non è stato possibile verificare')
    expect(avvisi[0].text()).toContain('Il server ha restituito un errore.')
    expect(wrapper.find('a[href="/accesso"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/registrazione"]').exists()).toBe(false)

    script.reply(meOk())
    await avvisi[0].get('button').trigger('click')
    await flushPromises()
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    expect(router.currentRoute.value.name).toBe('home')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/area"].button').text()).toBe("Vai all'area riservata")
    expect(script.fn).toHaveBeenCalledTimes(2)
  })

  it('recupero anonimo mostra Accedi; nuovo errore conserva Riprova senza tentativi automatici', async () => {
    script.networkError()
    await mountApp('/')
    vi.useFakeTimers()
    script.networkError()
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain("Anche l'ultimo tentativo non è riuscito.")
    await vi.advanceTimersByTimeAsync(60_000)
    expect(script.fn).toHaveBeenCalledTimes(2)

    script.reply(unauthorized())
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('main a[href="/accesso"].button').text()).toBe('Accedi')
  })
})

describe('recupero della verifica da /accesso', () => {
  async function erroreSuAccesso(redirect, extraRoutes) {
    script.networkError()
    const ctx = await mountApp(`/accesso?redirect=${encodeURIComponent(redirect)}`, extraRoutes)
    expect(ctx.router.currentRoute.value.name).toBe('accesso')
    expect(ctx.session.state.status).toBe(STATO.ERRORE)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.find('form').exists()).toBe(false)
    return ctx
  }

  async function riprova() {
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
  }

  it('recupero autenticato: navigazione alla destinazione valida', async () => {
    const { router } = await erroreSuAccesso('/area')
    script.reply(meOk())
    await riprova()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/area'))
    await flushPromises()
    expect(wrapper.text()).toContain('Benvenuto, Anna Prova.')
    expect(script.fn).toHaveBeenCalledTimes(2)
  })

  it('recupero anonimo: resta sul modulo di accesso', async () => {
    const { router, session } = await erroreSuAccesso('/area')
    script.reply(unauthorized())
    await riprova()
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(router.currentRoute.value.name).toBe('accesso')
    expect(router.currentRoute.value.query.redirect).toBe('/area')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('nuovo errore: avviso e Riprova conservati', async () => {
    const { router, session } = await erroreSuAccesso('/area')
    script.reply(jsonResponse(500, { error: 'x' }))
    await riprova()
    expect(session.state.status).toBe(STATO.ERRORE)
    expect(router.currentRoute.value.name).toBe('accesso')
    const avviso = wrapper.get('[role="alert"]')
    expect(avviso.text()).toContain("Anche l'ultimo tentativo non è riuscito.")
    expect(avviso.get('button').attributes('disabled')).toBeUndefined()
    expect(script.fn).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['esterna', '//esterno.example/area'],
    ['inesistente', '/inesistente'],
    ['non autorizzata', '/test/admin'],
  ])('destinazione %s: fallback sicuro su /area', async (_, redirect) => {
    const { router } = await erroreSuAccesso(redirect, [ROTTA_ADMIN_TEST])
    script.reply(meOk(utente({ paziente: true, ruoloGestionale: null })))
    await riprova()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/area'))
  })
})

describe('errore di verifica', () => {
  it('pagina dedicata con Riprova manuale', async () => {
    script.networkError()
    const { router } = await mountApp('/area')
    expect(router.currentRoute.value.name).toBe('errore-sessione')
    expect(wrapper.text()).toContain('Il server non è raggiungibile.')
    script.reply(meOk())
    await wrapper.get('main button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('area')
  })
})

describe('logout dall\'interfaccia', () => {
  it('non confermato: avviso persistente, nessuna vista privata, recupero manuale', async () => {
    script.reply(meOk())
    const { router, session } = await mountApp('/area')
    expect(wrapper.text()).toContain('Benvenuto, Anna Prova.')

    script.reply(jsonResponse(500, { error: 'Errore interno del server.' }))
    await wrapper.findAll('header button').find((b) => b.text() === 'Esci').trigger('click')
    await flushPromises()

    expect(session.state.status).toBe(STATO.LOGOUT_NON_CONFERMATO)
    expect(router.currentRoute.value.name).toBe('accesso')
    expect(wrapper.text()).not.toContain('Benvenuto')
    expect(wrapper.find('form').exists()).toBe(false)
    const avviso = wrapper.get('.notice.warning')
    expect(avviso.attributes('role')).toBe('alert')
    expect(avviso.text()).toContain('La disconnessione non è stata confermata')

    script.reply(meOk(utente(), CSRF_B)).reply(jsonResponse(200, { message: 'Logout effettuato.' }))
    await avviso.get('button').trigger('click')
    await flushPromises()
    expect(script.calls.at(-1).headers.get('X-CSRF-Token')).toBe(CSRF_B)
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(wrapper.text()).toContain('Disconnessione completata.')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Benvenuto')
  })
})
