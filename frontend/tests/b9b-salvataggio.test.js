import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CATALOGO, DATI, MEDICO, admin, dettaglioAdmin, medicoAdmin } from './b9b-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Conservazione input nella rilettura successiva a un salvataggio riuscito.

const ME = 'GET /api/auth/me'
let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function apri(id = 5, d = dettaglioAdmin(5)) {
  net.on(ME, meOk(admin())).on(CATALOGO, jsonResponse(200, [])).on(MEDICO(id), jsonResponse(200, d))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(`/admin/medici/${id}`)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { router }
}
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const valore = (sel) => wrapper.get(sel).element.value
const invia = async (campo) => { await wrapper.get(`form[novalidate]:has(#${campo})`).trigger('submit'); await flushPromises() }

describe('dati professionali', () => {
  it('successo, GET trattenuta, input dopo il successo: resta «Pediatria»; campi invariati riallineati al salvato; nessun falso conflitto', async () => {
    await apri()
    await wrapper.get('#professionali-specializzazione').setValue(' Neurologia ')
    await wrapper.get('#professionali-numeroIscrizioneOrdine').setValue(' ORD-77 ')
    await invia('professionali-specializzazione')
    net.on(DATI(5), jsonResponse(200, medicoAdmin(5, { specializzazione: 'Neurologia', numeroIscrizioneOrdine: 'ORD-77' })))
    const get = net.trattieni(MEDICO(5))
    await click(bottoneIn(wrapper.get('[data-conferma="professionali"]'), 'Salva dati professionali'))
    // Campi non modificati dopo l'invio: valori salvati (normalizzati dal backend).
    expect([valore('#professionali-specializzazione'), valore('#professionali-numeroIscrizioneOrdine')]).toEqual(['Neurologia', 'ORD-77'])
    await wrapper.get('#professionali-specializzazione').setValue('Pediatria')
    get.resolve(jsonResponse(200, dettaglioAdmin(5, [], { specializzazione: 'Neurologia', numeroIscrizioneOrdine: 'ORD-77' })))
    await flushPromises()
    expect([valore('#professionali-specializzazione'), valore('#professionali-numeroIscrizioneOrdine')]).toEqual(['Pediatria', 'ORD-77'])
    expect(wrapper.find('[data-modificato="specializzazione"]').exists()).toBe(true)
    expect(wrapper.find('[data-modificato="numeroIscrizioneOrdine"]').exists()).toBe(false)
    await invia('professionali-specializzazione')
    expect(wrapper.find('[data-errori="professionali"]').exists()).toBe(false)
    expect(wrapper.get('[data-conferma="professionali"] [data-campo="transizione"]').text()).toBe('Da «Neurologia, ORD-77» a «Pediatria, ORD-77».')
  })

  it('input modificato mentre la PUT è pendente: conservato dopo successo e rilettura', async () => {
    await apri()
    await wrapper.get('#professionali-specializzazione').setValue('Neurologia')
    await invia('professionali-specializzazione')
    const put = net.trattieni(DATI(5))
    await click(bottoneIn(wrapper.get('[data-conferma="professionali"]'), 'Salva dati professionali'))
    await wrapper.get('#professionali-specializzazione').setValue('Pediatria')
    net.on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5, [], { specializzazione: 'Neurologia' })))
    put.resolve(jsonResponse(200, medicoAdmin(5, { specializzazione: 'Neurologia' })))
    await flushPromises()
    expect(valore('#professionali-specializzazione')).toBe('Pediatria')
    expect(wrapper.find('[data-modificato="specializzazione"]').exists()).toBe(true)
    expect(JSON.parse(net.callsTo(DATI(5))[0].body).specializzazione).toBe('Neurologia')
  })
})

describe('dati personali', () => {
  it('successo con email normalizzata, GET trattenuta, nome modificato dopo il successo: conservato; email riallineata', async () => {
    await apri()
    await wrapper.get('#personali-email').setValue(' NUOVA@Example.test ')
    await invia('personali-email')
    net.on(DATI(5), jsonResponse(200, medicoAdmin(5, { email: 'nuova@example.test' })))
    const get = net.trattieni(MEDICO(5))
    await click(bottoneIn(wrapper.get('[data-conferma="personali"]'), 'Salva dati personali'))
    expect(valore('#personali-email')).toBe('nuova@example.test')
    await wrapper.get('#personali-nome').setValue('Elisa')
    get.resolve(jsonResponse(200, dettaglioAdmin(5, [], { email: 'nuova@example.test' })))
    await flushPromises()
    expect([valore('#personali-nome'), valore('#personali-cognome'), valore('#personali-email')]).toEqual(['Elisa', 'Medica5', 'nuova@example.test'])
    expect(wrapper.find('[data-modificato="nome"]').exists()).toBe(true)
    expect(wrapper.find('[data-modificato="email"]').exists()).toBe(false)
  })

  it('input durante la PUT pendente e campo cambiato sul server nella GET: conservato e conflitto ancora segnalato', async () => {
    await apri()
    await wrapper.get('#personali-email').setValue('nuova@example.test')
    await invia('personali-email')
    const put = net.trattieni(DATI(5))
    await click(bottoneIn(wrapper.get('[data-conferma="personali"]'), 'Salva dati personali'))
    await wrapper.get('#personali-cognome').setValue('Bianchi')
    net.on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5, [], { email: 'nuova@example.test', cognome: 'Rossi' })))
    put.resolve(jsonResponse(200, medicoAdmin(5, { email: 'nuova@example.test' })))
    await flushPromises()
    expect([valore('#personali-cognome'), valore('#personali-email')]).toEqual(['Bianchi', 'nuova@example.test'])
    await invia('personali-cognome')
    expect(wrapper.get('[data-errori="personali"]').text()).toContain('cambiato durante la modifica (ora «Rossi»)')
  })
})

describe('contesto', () => {
  it('cambio medico con PUT pendente: il vecchio successo non riallinea né rilegge il nuovo modulo', async () => {
    const { router } = await apri()
    await wrapper.get('#professionali-specializzazione').setValue('Neurologia')
    await invia('professionali-specializzazione')
    const put = net.trattieni(DATI(5))
    await click(bottoneIn(wrapper.get('[data-conferma="professionali"]'), 'Salva dati professionali'))
    net.on(MEDICO(6), jsonResponse(200, dettaglioAdmin(6, [], { specializzazione: 'Dermatologia' })))
    await router.push('/admin/medici/6')
    await flushPromises()
    put.resolve(jsonResponse(200, medicoAdmin(5, { specializzazione: 'Neurologia' })))
    await flushPromises()
    expect(valore('#professionali-specializzazione')).toBe('Dermatologia')
    expect(wrapper.find('[data-modificato]').exists()).toBe(false)
    expect(net.callsTo(MEDICO(5))).toHaveLength(1)
  })

  it('cambio medico con GET successiva trattenuta: la risposta del medico precedente non tocca il nuovo modulo', async () => {
    const { router } = await apri()
    await wrapper.get('#professionali-specializzazione').setValue('Neurologia')
    await invia('professionali-specializzazione')
    net.on(DATI(5), jsonResponse(200, medicoAdmin(5, { specializzazione: 'Neurologia' })))
    const get = net.trattieni(MEDICO(5))
    await click(bottoneIn(wrapper.get('[data-conferma="professionali"]'), 'Salva dati professionali'))
    net.on(MEDICO(6), jsonResponse(200, dettaglioAdmin(6, [], { specializzazione: 'Dermatologia' })))
    await router.push('/admin/medici/6')
    await flushPromises()
    await wrapper.get('#professionali-numeroIscrizioneOrdine').setValue('ORD-NUOVO')
    get.resolve?.(jsonResponse(200, dettaglioAdmin(5, [], { specializzazione: 'Neurologia' })))
    await flushPromises()
    expect([valore('#professionali-specializzazione'), valore('#professionali-numeroIscrizioneOrdine')]).toEqual(['Dermatologia', 'ORD-NUOVO'])
    expect(wrapper.get('h1').text()).toBe('Elena Medica6')
  })
})
