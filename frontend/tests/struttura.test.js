import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { ACCOUNT, GRUPPI, PUBBLICI, corrispondenza, descrizioneProfili } from '../src/navigation/collegamenti.js'
import { isReserved } from '../src/router/guards.js'
import { createAppRouter } from '../src/router/index.js'
import { STATO, capabilitiesOf, createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { admin, desk } from './b7-fixtures.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

/** Struttura comune: intestazione, menu del profilo, navigazione laterale e pagina di accesso. */
const ME = 'GET /api/auth/me'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })

async function mountApp(path, me = unauthorized()) {
  net.on(ME, typeof me === 'object' && !(me instanceof Response) ? meOk(me) : me)
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { session, router }
}
const click = async (w) => { await w.trigger('click'); await flushPromises() }
const tasto = async (w, key) => { await w.trigger('keydown', { key }); await flushPromises() }
const linkNav = () => wrapper.findAll('nav a').map((a) => a.attributes('href'))
const gruppiNav = () => wrapper.findAll('[data-gruppo]').map((g) => g.attributes('data-gruppo'))
const attivo = () => document.activeElement

describe('definizione condivisa dei collegamenti', () => {
  it('solo rotte esistenti, protette dalla capacità del proprio gruppo; pubbliche e account coerenti', () => {
    const router = createAppRouter({ session: createSession(), history: createMemoryHistory() })
    for (const g of GRUPPI) {
      for (const l of g.link) {
        const r = router.resolve(l.to)
        expect(r.name, l.to).not.toBe('non-trovata')
        expect(r.meta.capability, l.to).toBe(g.capacita)
      }
    }
    for (const l of PUBBLICI) expect(isReserved(router.resolve(l.to).meta), l.to).toBe(false)
    for (const l of ACCOUNT) expect(router.resolve(l.to).meta.requiresAuth, l.to).toBe(true)
  })

  it('profili descritti dalle capacità reali; nessun permesso implicito per l’amministratore', () => {
    expect(descrizioneProfili(capabilitiesOf(utente({ medico: true })))).toBe('Paziente · Medico')
    expect(descrizioneProfili(capabilitiesOf(admin()))).toBe('Amministratore')
    expect(descrizioneProfili(capabilitiesOf(utente({ paziente: false })))).toBeNull()
  })

  it('pagina corrente: esatta, sotto il proprio elenco, nessun falso prefisso', () => {
    expect(corrispondenza('/desk/medici', '/desk/medici')).toBe('esatto')
    expect(corrispondenza('/desk/medici/5', '/desk/medici')).toBe('sotto')
    expect(corrispondenza('/paziente/prenotazioni', '/paziente/prenota')).toBeNull()
  })
})

describe('navigazione laterale per ruolo', () => {
  it('anonimo: solo pagine pubbliche; Accedi e Registrati nell’intestazione', async () => {
    await mountApp('/')
    expect(gruppiNav()).toEqual(['pubblici'])
    expect(linkNav()).toEqual(['/prestazioni', '/disponibilita'])
    expect(wrapper.find('[data-profilo]').exists()).toBe(false)
    expect(wrapper.findAll('header a').map((a) => a.text())).toEqual(['MedBook', 'Accedi', 'Registrati'])
  })

  it('desk: solo pagine desk; amministratore: solo amministrazione, senza pagine desk', async () => {
    await mountApp('/area', desk())
    expect(gruppiNav()).toEqual(['desk', 'pubblici'])
    expect(linkNav().filter((h) => h.startsWith('/admin'))).toEqual([])
    wrapper.unmount()
    await mountApp('/area', admin())
    expect(gruppiNav()).toEqual(['admin', 'pubblici'])
    expect(linkNav().filter((h) => h.startsWith('/desk'))).toEqual([])
    expect(linkNav().filter((h) => h.startsWith('/admin'))).toEqual(['/admin/utenti', '/admin/medici', '/admin/stanze', '/admin/prestazioni'])
  })

  it('profili misti: gruppi etichettati e stessi collegamenti dell’area riservata', async () => {
    await mountApp('/area', utente({ medico: true }))
    expect(gruppiNav()).toEqual(['paziente', 'medico', 'pubblici'])
    for (const g of ['paziente', 'medico']) {
      const elenco = wrapper.get(`[data-gruppo="${g}"] ul`)
      expect(document.getElementById(elenco.attributes('aria-labelledby')).textContent).toBe(g === 'paziente' ? 'Paziente' : 'Medico')
      const sidebar = elenco.findAll('a').map((a) => a.attributes('href'))
      const area = wrapper.get(`main [aria-labelledby="titolo-area-${g}"]`).findAll('a').map((a) => a.attributes('href'))
      expect(sidebar).toEqual(area)
    }
  })

  it('pagina corrente con aria-current; una pagina sotto un percorso evidenzia il suo collegamento senza aria-current', async () => {
    const { router } = await mountApp('/area', desk())
    const area = () => wrapper.get('nav a[href="/area"]')
    expect(area().attributes('aria-current')).toBe('page')
    expect(area().classes()).toContain('attivo')
    expect(wrapper.findAll('nav a[aria-current]')).toHaveLength(1)
    await router.push('/area/password')
    await flushPromises()
    expect(area().classes()).toContain('attivo')
    expect(area().attributes('aria-current')).toBeUndefined()
    expect(wrapper.findAll('nav a.attivo')).toHaveLength(1)
  })
})

describe('menu del profilo', () => {
  it('nome e profilo nel pulsante; apertura, collegamenti esistenti, Escape con focus sul pulsante', async () => {
    await mountApp('/area', desk())
    const b = wrapper.get('[data-profilo]')
    expect(b.text()).toContain('Dario Sintetico')
    expect(b.text()).toContain('Operatore desk')
    expect(b.attributes('aria-expanded')).toBe('false')
    const menu = wrapper.get(`#${b.attributes('aria-controls')}`)
    expect(menu.isVisible()).toBe(false)
    await click(b)
    expect(b.attributes('aria-expanded')).toBe('true')
    expect(menu.isVisible()).toBe(true)
    expect(menu.findAll('a').map((a) => a.attributes('href'))).toEqual(['/area', '/area/password'])
    menu.get('a').element.focus()
    await tasto(menu.get('a'), 'Escape')
    expect(b.attributes('aria-expanded')).toBe('false')
    expect(attivo()).toBe(b.element)
  })

  it('chiuso dopo la navigazione da un suo collegamento, con focus sul contenuto principale', async () => {
    const { router } = await mountApp('/area', desk())
    await click(wrapper.get('[data-profilo]'))
    await click(wrapper.get('#menu-profilo a[href="/area/password"]'))
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/area/password'))
    await flushPromises()
    expect(wrapper.get('[data-profilo]').attributes('aria-expanded')).toBe('false')
    expect(attivo().id).toBe('contenuto')
  })

  it('clic fuori dal menu lo chiude', async () => {
    await mountApp('/area', desk())
    await click(wrapper.get('[data-profilo]'))
    document.body.click()
    await flushPromises()
    expect(wrapper.get('[data-profilo]').attributes('aria-expanded')).toBe('false')
  })
})

describe('menu di navigazione su schermi stretti', () => {
  it('pulsante etichettato con aria-expanded e aria-controls; Escape chiude e riporta il focus al pulsante', async () => {
    await mountApp('/area', desk())
    const menu = wrapper.get('[data-menu]')
    expect(menu.text()).toBe('Menu')
    expect(menu.attributes('aria-controls')).toBe('navigazione')
    expect(menu.attributes('aria-expanded')).toBe('false')
    await click(menu)
    expect(menu.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('#navigazione').classes()).toContain('aperta')
    const link = wrapper.get('#navigazione a[href="/desk/richieste"]')
    link.element.focus()
    await tasto(link, 'Escape')
    expect(menu.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('#navigazione').classes()).not.toContain('aperta')
    expect(attivo()).toBe(menu.element)
  })

  it('chiuso dopo una navigazione, con focus sul contenuto principale', async () => {
    const { router } = await mountApp('/area/password', desk())
    await click(wrapper.get('[data-menu]'))
    await click(wrapper.get('#navigazione a[href="/area"]'))
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/area'))
    await flushPromises()
    expect(wrapper.get('[data-menu]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('#navigazione').classes()).not.toContain('aperta')
    expect(attivo().id).toBe('contenuto')
  })
})

describe('logout e accesso', () => {
  it('Esci: stessa disconnessione di prima, menu del profilo rimosso', async () => {
    const { router, session } = await mountApp('/area', desk())
    net.on(LOGOUT, jsonResponse(200, { message: 'ok' }))
    await click(wrapper.findAll('header button').find((b) => b.text() === 'Esci'))
    expect(net.callsTo(LOGOUT)).toHaveLength(1)
    expect(net.callsTo(LOGOUT)[0].headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(session.state.status).toBe(STATO.ANONIMO)
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(wrapper.find('[data-profilo]').exists()).toBe(false)
  })

  it('pagina di accesso senza navigazione laterale; dopo il login ritorno validato e navigazione del profilo', async () => {
    const { router, session } = await mountApp('/accesso?redirect=/area/password')
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('[data-menu]').exists()).toBe(false)
    expect(wrapper.find('.app-corpo').classes()).toContain('senza-navigazione')
    expect(wrapper.get('main#contenuto h1').text()).toBe('Accesso')
    net.on(LOGIN, meOk(utente(), CSRF_B))
    await wrapper.get('#email').setValue('paziente@example.test')
    await wrapper.get('#password').setValue('x')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(session.state.status).toBe(STATO.AUTENTICATO)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/area/password'))
    await flushPromises()
    expect(gruppiNav()).toEqual(['paziente', 'pubblici'])
    expect(net.callsTo(LOGIN)).toHaveLength(1)
  })
})
