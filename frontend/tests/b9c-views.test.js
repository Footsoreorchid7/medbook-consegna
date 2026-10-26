import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parsePrestazioneAdmin, validaPrestazione } from '../src/api/catalogoAdmin.js'
import { adminStore } from '../src/admin/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CREA_PRESTAZIONE, CREA_STANZA, PRESTAZIONE, PRESTAZIONI, STANZA, STANZE, admin, desk, prestazioneAdmin, stanza } from './b9c-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountApp(path, user = admin()) {
  net.on(ME, meOk(user))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store: adminStore(session) }
}
const apriStanze = (voci = [stanza(1, { descrizione: 'Primo piano' }), stanza(2)]) => { net.on(STANZE, jsonResponse(200, voci)); return mountApp('/admin/stanze') }
const apriPrestazioni = (voci = [prestazioneAdmin(1), prestazioneAdmin(2, { prezzo: 40.5, durata: 20 })]) => { net.on(PRESTAZIONI, jsonResponse(200, voci)); return mountApp('/admin/prestazioni') }
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const attivo = () => document.activeElement
const avviso = () => wrapper.get('[data-avviso-admin]').text()
const riga = (id) => wrapper.get(`[data-voce-catalogo][data-id="${id}"]`)
const valore = (sel) => wrapper.get(sel).element.value
const invia = async (sel) => { await wrapper.get(sel).trigger('submit'); await flushPromises() }
async function compila(prefisso, campi) {
  if (prefisso.endsWith('-nuovo') && !wrapper.find('[data-modulo-nuovo]').exists()) await click(wrapper.get('[data-apri-nuovo]'))
  for (const [k, v] of Object.entries(campi)) await wrapper.get(`#${prefisso}-${k}`).setValue(v)
}

describe('permessi', () => {
  it('solo amministratore; desk e paziente esclusi senza letture; link alle sole pagine implementate', async () => {
    for (const user of [desk(), utente()]) {
      for (const path of ['/admin/stanze', '/admin/prestazioni']) {
        const { router } = await mountApp(path, user)
        expect(router.currentRoute.value.name).toBe('accesso-negato')
        wrapper.unmount()
      }
    }
    expect(net.calls.some((c) => c.url.startsWith('/api/stanze') || c.url.startsWith('/api/prestazioni'))).toBe(false)
    wrapper = null
    await mountApp('/area')
    expect(wrapper.findAll('main a').map((a) => a.attributes('href')).filter((h) => h.startsWith('/admin'))).toEqual(['/admin/utenti', '/admin/medici', '/admin/stanze', '/admin/prestazioni'])
  })
})

describe('stanze', () => {
  it('creazione: descrizione vuota = null, conferma, payload completo, successo e rilettura; 409 con la sola causa del contratto', async () => {
    await apriStanze()
    await compila('stanze-nuovo', { nome: '  Sala 3  ', descrizione: '' })
    await invia('[data-modulo-nuovo="stanze"]')
    expect(attivo()).toBe(wrapper.get('#titolo-conferma-nuovo-stanze').element)
    expect(wrapper.get('[data-conferma="crea"] [data-campo="riepilogo"]').text()).toBe('«Sala 3», nessuna descrizione.')
    net.on(CREA_STANZA, jsonResponse(201, stanza(3, { nome: 'Sala 3' }))).on(STANZE, jsonResponse(200, [stanza(1), stanza(2), stanza(3, { nome: 'Sala 3' })]))
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    expect(JSON.parse(net.callsTo(CREA_STANZA)[0].body)).toEqual({ nome: 'Sala 3', descrizione: null })
    expect(avviso()).toContain('Stanza creata n. 3: «Sala 3», nessuna descrizione')
    expect(wrapper.findAll('[data-voce-catalogo]')).toHaveLength(3)
    await compila('stanze-nuovo', { nome: 'Sala 3', descrizione: '  testo  ' })
    await invia('[data-modulo-nuovo="stanze"]')
    net.on(CREA_STANZA, jsonResponse(409, { error: 'Nome stanza già presente.' })).on(STANZE, jsonResponse(200, [stanza(3, { nome: 'Sala 3' })]))
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    expect(JSON.parse(net.callsTo(CREA_STANZA)[1].body).descrizione).toBe('  testo  ')
    expect(avviso()).toContain('Nome stanza già presente. Gli elenchi vengono riletti. Possibile causa indicata dal contratto: nome di stanza già presente.')
  })

  it('modifica: focus sul primo campo, Chiudi al controllo d\'origine, PUT completo con descrizione null', async () => {
    await apriStanze()
    await click(bottoneIn(riga(1), 'Modifica'))
    expect(attivo()).toBe(wrapper.get('#stanze-modifica-nome').element)
    expect(valore('#stanze-modifica-descrizione')).toBe('Primo piano')
    await click(bottoneIn(riga(1), 'Chiudi'))
    expect(attivo()).toBe(riga(1).get('[data-origine="modifica-stanze-1"]').element)
    await click(bottoneIn(riga(1), 'Modifica'))
    await wrapper.get('#stanze-modifica-descrizione').setValue('')
    await invia('[data-modulo-modifica="stanze"]')
    expect(wrapper.get('[data-conferma="modifica"] [data-campo="transizione"]').text()).toBe('Da «Ambulatorio 1», descrizione «Primo piano» a «Ambulatorio 1», nessuna descrizione.')
    net.on(STANZA(1), jsonResponse(200, stanza(1))).on(STANZE, jsonResponse(200, [stanza(1), stanza(2)]))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'), 'Salva'))
    expect(JSON.parse(net.callsTo(STANZA(1))[0].body)).toEqual({ nome: 'Ambulatorio 1', descrizione: null })
    expect(avviso()).toContain('Il server conferma la stanza n. 1')
  })
})

describe('prestazioni', () => {
  it('prezzo con virgola e durata non divisore di 60: numero JSON esatto; decimali eccessivi e durata 0 rifiutati senza richiesta', async () => {
    await apriPrestazioni()
    await compila('prestazioni-nuovo', { nome: 'Ecografia', descrizione: '', durata: '45', prezzo: '80,555' })
    await invia('[data-modulo-nuovo="prestazioni"]')
    expect(wrapper.get('#errore-prestazioni-nuovo-prezzo').text()).toContain('non viene arrotondato')
    expect(wrapper.get('#prestazioni-nuovo-prezzo').attributes('aria-describedby')).toBe('errore-prestazioni-nuovo-prezzo')
    expect(attivo()).toBe(wrapper.get('[data-errori-nuovo]').element)
    await compila('prestazioni-nuovo', { durata: '0', prezzo: '80,5' })
    await invia('[data-modulo-nuovo="prestazioni"]')
    expect(wrapper.get('#errore-prestazioni-nuovo-durata').text()).toContain('da 1 a 60')
    expect(net.calls.some((c) => c.method === 'POST')).toBe(false)
    await compila('prestazioni-nuovo', { durata: '7' })
    await invia('[data-modulo-nuovo="prestazioni"]')
    expect(wrapper.get('[data-conferma="crea"] [data-campo="riepilogo"]').text()).toBe('«Ecografia», 7 minuti, 80,50 €, nessuna descrizione.')
    net.on(CREA_PRESTAZIONE, jsonResponse(201, prestazioneAdmin(3, { nome: 'Ecografia', durata: 7, prezzo: 80.5 }))).on(PRESTAZIONI, jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    expect(net.callsTo(CREA_PRESTAZIONE)[0].body).toBe('{"nome":"Ecografia","descrizione":null,"durata":7,"prezzo":80.5}')
    expect(avviso()).toContain('Prestazione creata n. 3')
  })

  it('PUT seguito da GET trattenuta: input successivi conservati, campi invariati riallineati al salvato; nota sul prezzo storico', async () => {
    await apriPrestazioni()
    await click(bottoneIn(riga(2), 'Modifica'))
    expect(valore('#prestazioni-modifica-prezzo')).toBe('40,50')
    await wrapper.get('#prestazioni-modifica-prezzo').setValue('45.5')
    await invia('[data-modulo-modifica="prestazioni"]')
    const put = net.trattieni(PRESTAZIONE(2))
    const get = net.trattieni(PRESTAZIONI)
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'), 'Salva'))
    await wrapper.get('#prestazioni-modifica-nome').setValue('Bozza durante invio')
    put.resolve(jsonResponse(200, prestazioneAdmin(2, { durata: 20, prezzo: 45.5 })))
    await flushPromises()
    expect(JSON.parse(net.callsTo(PRESTAZIONE(2))[0].body)).toEqual({ nome: 'Visita 2', descrizione: null, durata: 20, prezzo: 45.5 })
    expect(avviso()).toContain('Le prenotazioni già richieste conservano il prezzo registrato alla richiesta')
    expect(valore('#prestazioni-modifica-prezzo')).toBe('45,50')
    await wrapper.get('#prestazioni-modifica-nome').setValue('Visita lunga')
    get.resolve(jsonResponse(200, [prestazioneAdmin(1), prestazioneAdmin(2, { durata: 20, prezzo: 45.5 })]))
    await flushPromises()
    expect([valore('#prestazioni-modifica-nome'), valore('#prestazioni-modifica-prezzo'), valore('#prestazioni-modifica-durata')]).toEqual(['Visita lunga', '45,50', '20'])
    expect(wrapper.find('[data-modificato="nome"]').exists()).toBe(true)
    expect(wrapper.find('[data-modificato="prezzo"]').exists()).toBe(false)
  })

  it('campo modificato cambiato sul server durante la rilettura: conflitto prima di una nuova conferma', async () => {
    const { store } = await apriPrestazioni()
    await click(bottoneIn(riga(2), 'Modifica'))
    await wrapper.get('#prestazioni-modifica-durata').setValue('25')
    // Esito certo da un'altra istanza su un'altra prestazione: l'elenco va riletto.
    net.on(PRESTAZIONE(1), jsonResponse(200, prestazioneAdmin(1, { nome: 'Visita 1', prezzo: 81 })))
    await store.aggiornaPrestazione(parsePrestazioneAdmin(prestazioneAdmin(1)), validaPrestazione({ nome: 'Visita 1', descrizione: '', durata: '30', prezzo: '81' }).dati)
    await flushPromises()
    expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
    expect(bottoneIn(riga(2), 'Prosegui').attributes('disabled')).toBeDefined()
    net.on(PRESTAZIONI, jsonResponse(200, [prestazioneAdmin(1, { prezzo: 81 }), prestazioneAdmin(2, { durata: 40, prezzo: 40.5 })]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(valore('#prestazioni-modifica-durata')).toBe('25')
    await invia('[data-modulo-modifica="prestazioni"]')
    expect(wrapper.get('[data-errori-modifica="prestazioni"]').text()).toContain('cambiato durante la modifica (ora «40»)')
    await invia('[data-modulo-modifica="prestazioni"]')
    expect(wrapper.get('[data-conferma="modifica"] [data-campo="transizione"]').text()).toBe('Da «Visita 2», 40 minuti, 40,50 €, nessuna descrizione a «Visita 2», 25 minuti, 40,50 €, nessuna descrizione.')
  })
})

describe('incertezza, invalidazione e contesto', () => {
  it('creazione incerta: reinvio dello stesso modulo bloccato; verifica sull\'elenco senza attribuzione', async () => {
    const { router } = await apriPrestazioni()
    await compila('prestazioni-nuovo', { nome: 'Visita 2', descrizione: '', durata: '20', prezzo: '40,5' })
    await invia('[data-modulo-nuovo="prestazioni"]')
    net.on(CREA_PRESTAZIONE, 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    expect(avviso()).toContain("Non è stato possibile stabilire l'esito")
    await router.push('/area')
    await flushPromises()
    net.on(PRESTAZIONI, jsonResponse(200, [prestazioneAdmin(1), prestazioneAdmin(2, { durata: 20, prezzo: 40.5 })]))
    await router.push('/admin/prestazioni')
    await flushPromises()
    await compila('prestazioni-nuovo', { nome: 'visita 2', descrizione: '', durata: '20', prezzo: '40.50' })
    await invia('[data-modulo-nuovo="prestazioni"]')
    expect(wrapper.get('[data-errori-nuovo]').text()).toContain('ancora in corso o da verificare')
    net.on(PRESTAZIONI, jsonResponse(200, [prestazioneAdmin(1), prestazioneAdmin(2, { durata: 20, prezzo: 40.5 }), prestazioneAdmin(5, { nome: 'Visita 2', durata: 20, prezzo: 40.5 })]), jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    expect(avviso()).toContain('(n. 2, n. 5), almeno una assente prima dell\'invio. Questo non prova che sia stata creata dal tentativo')
    expect(net.callsTo(CREA_PRESTAZIONE)).toHaveLength(1)
  })

  it('scrittura in volo → uscita/rientro → elenco letto → successo della vecchia istanza: azioni sospese, nessuna seconda PUT', async () => {
    const { router } = await apriStanze()
    await click(bottoneIn(riga(1), 'Modifica'))
    await wrapper.get('#stanze-modifica-nome').setValue('Sala nuova')
    await invia('[data-modulo-modifica="stanze"]')
    const put = net.trattieni(STANZA(1))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'), 'Salva'))
    await router.push('/area')
    await flushPromises()
    net.on(STANZE, jsonResponse(200, [stanza(1), stanza(2)]))
    await router.push('/admin/stanze')
    await flushPromises()
    expect(riga(1).text()).toContain('Operazione in corso o da verificare')
    put.resolve(jsonResponse(200, stanza(1, { nome: 'Sala nuova', descrizione: 'Primo piano' })))
    await flushPromises()
    expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
    expect(bottoneIn(riga(2), 'Modifica').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-avviso-admin]').exists()).toBe(false)
    net.on(STANZE, jsonResponse(200, [stanza(1, { nome: 'Sala nuova' }), stanza(2)]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(bottoneIn(riga(2), 'Modifica').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(STANZA(1))).toHaveLength(1)
  })

  it('conferma di modifica aperta chiusa da un esito certo di un\'altra istanza; focus al titolo; recupero manuale', async () => {
    const { store } = await apriStanze()
    await click(bottoneIn(riga(2), 'Modifica'))
    await wrapper.get('#stanze-modifica-nome').setValue('Sala 2 bis')
    await invia('[data-modulo-modifica="stanze"]')
    expect(wrapper.find('[data-conferma="modifica"]').exists()).toBe(true)
    net.on(CREA_STANZA, jsonResponse(201, stanza(9, { nome: 'Altra' })))
    await store.creaStanza({ nome: 'Altra', descrizione: null })
    await flushPromises()
    expect(wrapper.find('[data-conferma="modifica"]').exists()).toBe(false)
    expect(attivo()).toBe(wrapper.get('#titolo-elenco-stanze').element)
    expect(valore('#stanze-modifica-nome')).toBe('Sala 2 bis')
    net.on(STANZE, jsonResponse(200, [stanza(1), stanza(2), stanza(9, { nome: 'Altra' })]))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    expect(bottoneIn(riga(2), 'Prosegui').attributes('disabled')).toBeUndefined()
    expect(net.calls.some((c) => c.url === '/api/stanze/2')).toBe(false)
  })

  it('input modificato mentre la PUT è pendente: conservato dopo il successo', async () => {
    await apriPrestazioni()
    await click(bottoneIn(riga(2), 'Modifica'))
    await wrapper.get('#prestazioni-modifica-prezzo').setValue('45,5')
    await invia('[data-modulo-modifica="prestazioni"]')
    const put = net.trattieni(PRESTAZIONE(2))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'), 'Salva'))
    await wrapper.get('#prestazioni-modifica-prezzo').setValue('46')
    net.on(PRESTAZIONI, jsonResponse(200, [prestazioneAdmin(1), prestazioneAdmin(2, { durata: 20, prezzo: 45.5 })]))
    put.resolve(jsonResponse(200, prestazioneAdmin(2, { durata: 20, prezzo: 45.5 })))
    await flushPromises()
    expect(valore('#prestazioni-modifica-prezzo')).toBe('46')
    expect(wrapper.find('[data-modificato="prezzo"]').exists()).toBe(true)
  })

  it('lettura vecchia dell\'elenco rilasciata dopo una più recente: non sostituisce i dati correnti', async () => {
    await apriStanze()
    await compila('stanze-nuovo', { nome: 'Incerta', descrizione: '' })
    await invia('[data-modulo-nuovo="stanze"]')
    net.on(CREA_STANZA, 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    await click(bottoneIn(riga(2), 'Modifica'))
    await wrapper.get('#stanze-modifica-nome').setValue('Sala 2 nuova')
    await invia('[data-modulo-modifica="stanze"]')
    net.on(STANZA(2), jsonResponse(200, stanza(2, { nome: 'Sala 2 nuova' })))
    const vecchia = net.trattieni(STANZE)
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'), 'Salva'))
    const corrente = [stanza(1, { descrizione: 'Primo piano' }), stanza(2, { nome: 'Sala 2 nuova' })]
    net.on(STANZE, jsonResponse(200, corrente), jsonResponse(200, corrente))
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    vecchia.resolve(jsonResponse(200, [stanza(1, { descrizione: 'Primo piano' }), stanza(2)]))
    await flushPromises()
    expect(riga(2).get('[data-campo="nome"]').text()).toBe('Sala 2 nuova')
  })

  it('verifica trattenuta e lettura più recente della vista: esito costruito sull\'elenco più recente', async () => {
    const { store } = await apriStanze()
    await compila('stanze-nuovo', { nome: 'Sala C', descrizione: '' })
    await invia('[data-modulo-nuovo="stanze"]')
    net.on(CREA_STANZA, 'rete')
    await click(bottoneIn(wrapper.get('[data-conferma="crea"]'), 'Crea'))
    const a = net.trattieni(STANZE)
    await click(bottoneIn(wrapper.get('[data-esito-incerto]'), 'Verifica'))
    // Esito certo altrove (nuova stanza): l'elenco della vista va aggiornato; la lettura B è più recente di A.
    net.on(CREA_STANZA, jsonResponse(201, stanza(8, { nome: 'Altra' })))
    await store.creaStanza({ nome: 'Altra', descrizione: null })
    await flushPromises()
    net.on(STANZE, jsonResponse(200, [stanza(1), stanza(2), stanza(7, { nome: 'Sala C' }), stanza(8, { nome: 'Altra' })]), jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-elenco-invalidato]'), "Aggiorna l'elenco"))
    a.resolve(jsonResponse(200, [stanza(1), stanza(2)]))
    await flushPromises()
    expect(avviso()).toContain('esiste la stanza n. 7 con nome «Sala C»')
    expect(avviso()).toContain('non prova che sia stata creata dal tentativo')
  })

  it('cambio di epoca: moduli svuotati, conferma chiusa, elenco riletto', async () => {
    const { session } = await apriStanze()
    await compila('stanze-nuovo', { nome: 'Bozza', descrizione: 'x' })
    await click(bottoneIn(riga(1), 'Modifica'))
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B })).on(STANZE, jsonResponse(200, [stanza(1)]))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    await flushPromises()
    expect(wrapper.find('#stanze-nuovo-nome').exists()).toBe(false)
    await click(wrapper.get('[data-apri-nuovo]'))
    expect(valore('#stanze-nuovo-nome')).toBe('')
    expect(wrapper.find('#stanze-modifica-nome').exists()).toBe(false)
    expect(net.callsTo(STANZE)).toHaveLength(2)
  })
})

const cataloghiModuli = [
  { k: 'stanze', apri: apriStanze, put: STANZA, get: STANZE, voce: stanza },
  { k: 'prestazioni', apri: apriPrestazioni, put: PRESTAZIONE, get: PRESTAZIONI, voce: prestazioneAdmin },
]
describe.each(cataloghiModuli)('moduli su richiesta: $k', ({ k, apri, put, get, voce }) => {
  it('creazione inizialmente chiusa, apertura e chiusura restituiscono il focus', async () => {
    await apri()
    expect(wrapper.find('[data-modulo-nuovo]').exists()).toBe(false)
    await click(wrapper.get('[data-apri-nuovo]'))
    expect(attivo()).toBe(wrapper.get(`#${k}-nuovo-nome`).element)
    await click(wrapper.get('[data-chiudi-nuovo]'))
    expect(wrapper.find('[data-modulo-nuovo]').exists()).toBe(false)
    expect(attivo()).toBe(wrapper.get('[data-apri-nuovo]').element)
    expect(net.calls.every(c => c.method === 'GET')).toBe(true)
  })
  it('successo chiude modifica, mostra subito risposta validata e recupera solo focus perso durante GET', async () => {
    await apri();await click(bottoneIn(riga(1),'Modifica'))
    await wrapper.get(`#${k}-modifica-nome`).setValue('Nome salvato')
    await invia(`[data-modulo-modifica="${k}"]`)
    net.on(put(1),jsonResponse(200,voce(1,{nome:'Nome salvato', ...(k === 'stanze' ? {descrizione:'Primo piano'} : {})})))
    const lettura=net.trattieni(get)
    wrapper.get('[data-conferma="modifica"] button').element.focus()
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'),'Salva'))
    expect(wrapper.find('[data-modulo-modifica]').exists()).toBe(false)
    expect(riga(1).get('h3').text()).toBe('Nome salvato')
    expect(attivo()).toBe(riga(1).get('h3').element)
    expect(bottoneIn(riga(1),'Modifica').attributes('disabled')).toBeDefined()
    lettura.resolve(jsonResponse(200,[voce(1,{nome:'Nome salvato', ...(k === 'stanze' ? {descrizione:'Primo piano'} : {})})]));await flushPromises()
    expect(bottoneIn(riga(1),'Modifica').attributes('disabled')).toBeUndefined()
  })
  it.each(['conferma', 'invio'])('modifiche durante %s restano aperte e il payload resta quello confermato', async fase => {
    await apri();await click(bottoneIn(riga(1),'Modifica'))
    await wrapper.get(`#${k}-modifica-nome`).setValue('Confermato')
    await invia(`[data-modulo-modifica="${k}"]`)
    if (fase === 'conferma') await wrapper.get(`#${k}-modifica-nome`).setValue('Bozza successiva')
    const richiesta = net.trattieni(put(1))
    net.on(get,jsonResponse(200,[voce(1,{nome:'Confermato', ...(k === 'stanze' ? {descrizione:'Primo piano'} : {})})]))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'),'Salva'))
    if (fase === 'invio') await wrapper.get(`#${k}-modifica-nome`).setValue('Bozza successiva')
    wrapper.get(`#${k}-modifica-nome`).element.focus()
    richiesta.resolve(jsonResponse(200,voce(1,{nome:'Confermato', ...(k === 'stanze' ? {descrizione:'Primo piano'} : {})})))
    await flushPromises()
    expect(avviso()).toContain('Il server conferma')
    expect(JSON.parse(net.callsTo(put(1))[0].body).nome).toBe('Confermato')
    expect(valore(`#${k}-modifica-nome`)).toBe('Bozza successiva')
    expect(attivo()).toBe(wrapper.get(`#${k}-modifica-nome`).element)
  })
  it('successo chiude senza sottrarre il focus spostato su un altro controllo', async () => {
    await apri();await click(bottoneIn(riga(2),'Modifica'))
    await wrapper.get(`#${k}-modifica-nome`).setValue('Salvato')
    await invia(`[data-modulo-modifica="${k}"]`)
    const richiesta=net.trattieni(put(2))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'),'Salva'))
    const altro=wrapper.get('[data-apri-nuovo]');altro.element.focus()
    net.on(get,jsonResponse(200,[voce(2,{nome:'Salvato', ...(k === 'prestazioni' ? {prezzo:40.5,durata:20} : {})})]))
    richiesta.resolve(jsonResponse(200,voce(2,{nome:'Salvato', ...(k === 'prestazioni' ? {prezzo:40.5,durata:20} : {})})));await flushPromises()
    expect(wrapper.find('[data-modulo-modifica]').exists()).toBe(false)
    expect(attivo()).toBe(altro.element)
  })
  it.each([400,503])('errore HTTP %i mantiene modulo e focus volontario altrove', async status => {
    await apri();await click(bottoneIn(riga(1),'Modifica'))
    await wrapper.get(`#${k}-modifica-nome`).setValue('Bozza')
    await invia(`[data-modulo-modifica="${k}"]`)
    const richiesta=net.trattieni(put(1))
    await click(bottoneIn(wrapper.get('[data-conferma="modifica"]'),'Salva'))
    const altro=wrapper.get('[data-apri-nuovo]');altro.element.focus()
    richiesta.resolve(jsonResponse(status,{}));await flushPromises()
    expect(valore(`#${k}-modifica-nome`)).toBe('Bozza')
    expect(attivo()).toBe(altro.element)
    if(status===503)expect(wrapper.find('[data-esito-incerto]').exists()).toBe(true)
    expect(net.callsTo(put(1))).toHaveLength(1)
  })
})

const creazioni = [
 {k:'stanze',apri:apriStanze,post:CREA_STANZA,get:STANZE,risposta:()=>stanza(8,{nome:'Nuova'}),campi:{nome:'Nuova',descrizione:''}},
 {k:'prestazioni',apri:apriPrestazioni,post:CREA_PRESTAZIONE,get:PRESTAZIONI,risposta:()=>prestazioneAdmin(8,{nome:'Nuova',durata:30,prezzo:80}),campi:{nome:'Nuova',descrizione:'',durata:'30',prezzo:'80'}},
]
describe.each(creazioni)('completamento creazione $k',({k,apri,post,get,risposta,campi})=>{
 async function prepara(){await apri();await compila(`${k}-nuovo`,campi);await invia(`[data-modulo-nuovo="${k}"]`)}
 it('successo chiude, svuota e mostra nuova card; focus recuperato soltanto se perso',async()=>{
  await prepara()
  net.on(post,jsonResponse(201,risposta())).on(get,jsonResponse(200,[risposta()]))
  wrapper.get('[data-conferma="crea"] button').element.focus()
  await click(bottoneIn(wrapper.get('[data-conferma="crea"]'),'Crea'))
  expect(wrapper.find('[data-modulo-nuovo]').exists()).toBe(false)
  expect(riga(8).text()).toContain('Nuova')
  expect(wrapper.find('[data-avviso-admin]').exists()).toBe(true)
  expect(attivo()).toBe(wrapper.get('[data-apri-nuovo]').element)
  await click(wrapper.get('[data-apri-nuovo]'))
  for(const campo of Object.keys(campi))expect(valore(`#${k}-nuovo-${campo}`)).toBe('')
 })
 it.each(['conferma','invio'])('nuovi dati durante %s restano e non cambiano il payload confermato',async fase=>{
  await prepara()
  const h=net.trattieni(post)
  if(fase==='conferma')await wrapper.get(`#${k}-nuovo-nome`).setValue('Bozza successiva')
  await click(bottoneIn(wrapper.get('[data-conferma="crea"]'),'Crea'))
  if(fase==='invio')await wrapper.get(`#${k}-nuovo-nome`).setValue('Bozza successiva')
  wrapper.get(`#${k}-nuovo-nome`).element.focus()
  net.on(get,jsonResponse(200,[risposta()]))
  h.resolve(jsonResponse(201,risposta()));await flushPromises()
  expect(JSON.parse(net.callsTo(post)[0].body).nome).toBe('Nuova')
  expect(valore(`#${k}-nuovo-nome`)).toBe('Bozza successiva')
  expect(attivo()).toBe(wrapper.get(`#${k}-nuovo-nome`).element)
  expect(avviso()).toContain('creata n. 8')
  expect(riga(8).text()).toContain('Nuova')
 })
 it.each([400,503])('errore HTTP %i non chiude né cancella input',async status=>{
  await prepara();const h=net.trattieni(post)
  await click(bottoneIn(wrapper.get('[data-conferma="crea"]'),'Crea'))
  await wrapper.get(`#${k}-nuovo-nome`).setValue('Bozza conservata')
  h.resolve(jsonResponse(status,{}));await flushPromises()
  expect(valore(`#${k}-nuovo-nome`)).toBe('Bozza conservata')
  if(status===503)expect(wrapper.find('[data-esito-incerto]').exists()).toBe(true)
  expect(net.callsTo(post)).toHaveLength(1)
 })
 it('chiusura e riapertura non autorizzano il vecchio successo a svuotare il nuovo modulo',async()=>{
  await prepara();const h=net.trattieni(post)
  await click(bottoneIn(wrapper.get('[data-conferma="crea"]'),'Crea'))
  await click(wrapper.get('[data-chiudi-nuovo]'));await click(wrapper.get('[data-apri-nuovo]'))
  await wrapper.get(`#${k}-nuovo-nome`).setValue('Nuovo contesto')
  net.on(get,jsonResponse(200,[risposta()]));h.resolve(jsonResponse(201,risposta()));await flushPromises()
  expect(valore(`#${k}-nuovo-nome`)).toBe('Nuovo contesto')
  expect(net.callsTo(post)).toHaveLength(1)
 })
})
