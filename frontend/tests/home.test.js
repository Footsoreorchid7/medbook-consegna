import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'
const CAT = 'GET /api/prestazioni', ME = 'GET /api/auth/me'
const voce = (id, extra = {}) => ({ idPrestazione: id, nome: `Prestazione ${id}`, descrizione: `Descrizione ${id}`, durata: 30, prezzo: 45.5, ...extra })
let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })
async function apri(me = unauthorized()) {
  net.on(ME, me)
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push('/')
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { session, router }
}
const home = () => wrapper.get('.home')
const cards = () => wrapper.findAll('[data-home-prestazione]')
describe('home del centro dimostrativo', () => {
  it('presentazione e rotte reali durante il caricamento; elenco vuoto non nasconde la home', async () => {
    const held = net.trattieni(CAT)
    const { router } = await apri()
    expect(home().get('h1').text()).toBe('Le tue visite, organizzate con semplicità')
    for (const testo of ['Centro Medico MedBook', 'Centro dimostrativo — dati di esempio', 'Caricamento delle prestazioni', 'Attendi la conferma della segreteria.', 'Nessun addebito reale.']) expect(home().text()).toContain(testo)
    expect(home().get('a[href="/prestazioni"].button').text()).toContain('Scopri le prestazioni')
    expect(home().get('a[href="/disponibilita"].button').text()).toBe('Consulta le disponibilità')
    for (const a of home().findAll('a')) expect(router.resolve(a.attributes('href')).name).not.toBe('non-trovata')
    held.resolve(jsonResponse(200, [])); await flushPromises()
    expect(home().text()).toContain('Nessuna prestazione disponibile')
    expect(home().find('a[href="/registrazione"]').exists()).toBe(true)
    expect(home().findAll('svg').every(s => s.attributes('focusable') === 'false')).toBe(true)
  })
  it('prime tre righe nell’ordine API, descrizioni e prezzi reali (incluso zero)', async () => {
    net.on(CAT, jsonResponse(200, [voce(8), voce(3, { prezzo: 0, descrizione: null }), voce(9, { prezzo: 120 }), voce(1)]))
    await apri()
    expect(cards().map(c => c.get('h3').text())).toEqual(['Prestazione 8', 'Prestazione 3', 'Prestazione 9'])
    expect(cards()[0].text()).toContain('Descrizione 8')
    expect(cards().map(c => c.get('strong').text().replace(/\s/g, ' '))).toEqual(['45,50 €', '0,00 €', '120,00 €'])
    expect(cards()[1].text()).toContain('Informazioni e dettagli nel catalogo')
    expect(home().find('del').exists()).toBe(false)
    expect(net.callsTo(CAT)).toHaveLength(1)
  })
  it('errore isolato e recupero solo manuale; dopo successo focus al titolo', async () => {
    net.on(CAT, jsonResponse(503, { error: 'errore' }))
    await apri()
    expect(home().get('[role="alert"]').text()).toContain('Non è stato possibile caricare le prestazioni')
    expect(home().find('#home-come-funziona').exists()).toBe(true)
    expect(home().find('#home-servizi').exists()).toBe(true)
    expect(net.callsTo(CAT)).toHaveLength(1)
    const retry = home().get('[data-home-errore-catalogo] button'); retry.element.focus()
    net.on(CAT, jsonResponse(200, [voce(1)]))
    await retry.trigger('click'); await flushPromises()
    expect(cards()).toHaveLength(1)
    expect(document.activeElement.id).toBe('home-prestazioni')
  })
  it('recupero fallito ancora raggiungibile; focus volontario rispettato', async () => {
    net.on(CAT, 'rete'); await apri()
    const held = net.trattieni(CAT)
    await home().get('[data-home-errore-catalogo] button').trigger('click')
    const link = home().get('a[href="/prestazioni"].button').element; link.focus()
    held.resolve(jsonResponse(500, { error: 'errore' })); await flushPromises()
    expect(document.activeElement).toBe(link)
    expect(home().get('[data-home-errore-catalogo] button').text()).toBe('Riprova il catalogo')
  })
  it('risposta malformata non pubblica schede parziali', async () => {
    net.on(CAT, jsonResponse(200, [voce(1), { nome: 'non valida' }]))
    await apri()
    expect(cards()).toHaveLength(0)
    expect(home().get('[role="alert"]').text()).toContain('risposta non valida')
  })
  it.each([
    ['paziente', utente(), true], ['misto', utente({ medico: true }), true],
    ['medico', utente({ paziente: false, medico: true }), false],
    ['desk', utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' }), false],
    ['admin', utente({ paziente: false, ruoloGestionale: 'AMMINISTRATORE' }), false],
  ])('%s: collegamenti consentiti e nessun invito a registrarsi', async (_, user, patient) => {
    net.on(CAT, jsonResponse(200, [])); await apri(meOk(user))
    expect(home().findAll('a[href="/paziente/prenotazioni"]')).toHaveLength(patient ? 3 : 0)
    expect(home().find('a[href="/registrazione"]').exists()).toBe(false)
    expect(home().find('a[href="/accesso"]').exists()).toBe(false)
    expect(home().get('a[href="/area"]').text()).toBe("Vai all'area riservata")
  })
  it('bootstrap fallito: recupero della sessione unico, indipendente dal catalogo', async () => {
    net.on(CAT, jsonResponse(200, [voce(1)])); await apri(jsonResponse(500, { error: 'x' }))
    expect(home().findAll('[role="alert"]')).toHaveLength(1)
    expect(cards()).toHaveLength(1)
    net.on(ME, meOk()).on(CAT, jsonResponse(200, [voce(2)]))
    await home().get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(home().find('[role="alert"]').exists()).toBe(false)
    expect(cards()[0].get('h3').text()).toBe('Prestazione 2')
  })
  it('rotazione: una GET per epoca, vecchia risposta che ignora abort scartata', async () => {
    let resolveOld
    net.on(CAT, () => new Promise(resolve => { resolveOld = resolve }))
    const { session } = await apri(meOk())
    net.on('PUT /api/auth/password', jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    const nuova = net.trattieni(CAT)
    await session.changePassword('precedente', 'nuova'); await flushPromises()
    resolveOld(jsonResponse(200, [voce(1)])); await flushPromises()
    expect(home().text()).toContain('Caricamento delle prestazioni')
    expect(cards()).toHaveLength(0)
    nuova.resolve(jsonResponse(200, [voce(2)])); await flushPromises()
    expect(cards()[0].get('h3').text()).toBe('Prestazione 2')
    expect(net.callsTo(CAT)).toHaveLength(2)
  })
  it('smontaggio: abort e nessun effetto della risposta tardiva nella nuova pagina', async () => {
    let resolveOld
    net.on(CAT, () => new Promise(resolve => { resolveOld = resolve }))
    const { router } = await apri()
    await router.push('/accesso'); await flushPromises()
    expect(net.callsTo(CAT)[0].init.signal.aborted).toBe(true)
    const input = wrapper.get('#email').element; input.focus()
    resolveOld(jsonResponse(200, [voce(1)])); await flushPromises()
    expect(wrapper.find('.home').exists()).toBe(false)
    expect(document.activeElement).toBe(input)
  })
  it('nomi lunghi completi, descrizione breve Unicode senza surrogate spezzate', async () => {
    const nome = 'Prestazione'.repeat(50)
    net.on(CAT, jsonResponse(200, [voce(1, { nome, descrizione: '🩺'.repeat(200) })])); await apri()
    expect(cards()[0].get('h3').text()).toBe(nome)
    expect(cards()[0].get('p').text()).toBe('🩺'.repeat(160) + '…')
  })
})
