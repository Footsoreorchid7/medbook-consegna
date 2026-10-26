import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { appuntamento } from './b3-fixtures.js'
import { confermata, pagamento, fallito, rimborso, referto, pdfResponse } from './b4-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'
const LISTA = 'GET /api/prenotazioni/paziente?idPaziente=7'
const PAG = id => `GET /api/prenotazioni/${id}/pagamenti`
const ANNULLA = 'POST /api/prenotazioni/12/annulla-paziente'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'
const RIMB = 'GET /api/rimborsi/paziente'
const REF = 'GET /api/prenotazioni/12/referto'
const DOC = 'GET /api/prenotazioni/12/referto/documento'
const nonErogata = (id = 12) => confermata(id, { stato: 'NON_EROGATA' })
let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.restoreAllMocks() })
async function monta(voci = [appuntamento(12)]) {
  net.on('GET /api/auth/me', meOk()).on(LISTA, jsonResponse(200, voci))
  const session = createSession(), router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push('/paziente/prenotazioni'); await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { session, router, store: prenotazioniStore(session) }
}
const b = (text, dentro = wrapper) => dentro.findAll('button').find(x => x.text() === text)
const clic = async x => { x.element.focus(); await x.trigger('click'); await flushPromises() }
const vai = async (router, path) => { await router.push(path); await flushPromises() }
function downloadSpie() {
  const crea = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:card')
  const revoca = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  return { crea, revoca, click }
}

describe('azioni delle card, senza POST implicite', () => {
  it('RICHIESTA: annullamento e dettagli senza leggere pagamenti inesistenti; focus di ritorno', async () => {
    await monta()
    expect(net.callsTo(PAG(12))).toHaveLength(0)
    expect(wrapper.get('a[href="/paziente/prenotazioni/12"]').text()).toContain('Dettagli')
    await clic(b('Annulla prenotazione'))
    expect(document.activeElement.textContent).toBe("Confermi l'annullamento?")
    expect(b('Conferma annullamento').classes()).toContain('azione-distruttiva')
    expect(net.callsTo(ANNULLA)).toHaveLength(0)
    await clic(b('Non annullare'))
    expect(document.activeElement).toBe(b('Annulla prenotazione').element)
  })
  it('conferma annullamento: payload, doppio clic e blocco durante rilettura', async () => {
    await monta()
    await clic(b('Annulla prenotazione'))
    const post = net.trattieni(ANNULLA), lista = net.trattieni(LISTA)
    const pulsante = b('Conferma annullamento')
    await pulsante.trigger('click'); await pulsante.trigger('click')
    expect(net.callsTo(ANNULLA)).toHaveLength(1)
    expect(JSON.parse(net.callsTo(ANNULLA)[0].body)).toEqual({ idPaziente: 7, statoAtteso: 'RICHIESTA' })
    post.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    await flushPromises()
    expect(b('Annulla prenotazione').attributes('disabled')).toBeDefined()
    net.on(PAG(12), jsonResponse(200, []))
    lista.resolve(jsonResponse(200, [appuntamento(12, { stato: 'ANNULLATA' })]))
    await flushPromises()
    expect(b('Annulla prenotazione')).toBeUndefined()
    expect(wrapper.text()).toContain('Annullamento registrato.')
  })
  it.each(['PAGATO', 'IN_ATTESA'])('%s nasconde annullamento e pagamento; rimane il dettaglio', async stato => {
    net.on(PAG(12), jsonResponse(200, [pagamento(5, { stato, pagatoIl: stato === 'PAGATO' ? '2026-10-16T10:00:00' : null })]))
    await monta([confermata()])
    expect(b('Annulla prenotazione')).toBeUndefined()
    expect(b('Paga adesso')).toBeUndefined()
    expect(wrapper.find('a[href="/paziente/prenotazioni/12"]').exists()).toBe(true)
  })
  it('storico fallito non nasconde la card e non abilita annullamento o pagamento', async () => {
    net.on(PAG(12), jsonResponse(500, {}))
    await monta([confermata()])
    expect(wrapper.find('[data-prenotazione]').exists()).toBe(true)
    expect(b('Annulla prenotazione')).toBeUndefined()
    net.on(PAG(12), jsonResponse(200, [fallito(5)]))
    await clic(b('Riprova'))
    expect(b('Annulla prenotazione')).toBeDefined()
    expect(b('Paga adesso')).toBeDefined()
  })
  it('conferme incompatibili sospese nelle due direzioni', async () => {
    net.on(PAG(12), jsonResponse(200, []))
    await monta([confermata()])
    await clic(b('Annulla prenotazione'))
    expect(b('Paga adesso').attributes('disabled')).toBeDefined()
    await clic(b('Paga adesso'))
    expect(wrapper.find('[data-conferma-pagamento]').exists()).toBe(false)
    await clic(b('Non annullare'))
    await clic(b('Paga adesso'))
    expect(b('Annulla prenotazione').attributes('disabled')).toBeDefined()
    await clic(b('Annulla prenotazione'))
    expect(b('Conferma annullamento')).toBeUndefined()
    expect(net.callsTo(ANNULLA)).toHaveLength(0)
    expect(net.callsTo(PAGA)).toHaveLength(0)
  })
  it('store blocca operazioni incompatibili pending e incerte prima di fetch, per la sola risorsa', async () => {
    net.on(PAG(12), jsonResponse(200, []))
    const { store } = await monta([confermata()])
    const post = net.trattieni(PAGA)
    const promessa = store.pagaOnline(parseAppuntamento(confermata()), [])
    expect((await store.annulla(parseAppuntamento(confermata()))).kind).toBe('occupato')
    post.resolve(jsonResponse(500, {})); await promessa
    expect((await store.annulla(parseAppuntamento(confermata()))).kind).toBe('bloccata')
    expect(net.callsTo(ANNULLA)).toHaveLength(0)
    net.on('POST /api/prenotazioni/13/annulla-paziente', jsonResponse(200, { idPrenotazione: 13, stato: 'ANNULLATA' }))
    expect((await store.annulla(parseAppuntamento(appuntamento(13)))).kind).toBe('annullata')
  })
  it('annullamento in volo o incerto blocca anche il pagamento prima di fetch', async () => {
    net.on(PAG(12), jsonResponse(200, []))
    const { store } = await monta([confermata()])
    const post = net.trattieni(ANNULLA)
    const promessa = store.annulla(parseAppuntamento(confermata()))
    expect((await store.pagaOnline(parseAppuntamento(confermata()), [])).kind).toBe('occupato')
    post.resolve(jsonResponse(500, {})); await promessa
    expect((await store.pagaOnline(parseAppuntamento(confermata()), [])).kind).toBe('bloccata')
    expect(net.callsTo(PAGA)).toHaveLength(0)
  })
  it('nuova card caricata durante un annullamento: il vecchio successo invalida senza effetti locali tardivi', async () => {
    const { router } = await monta()
    await clic(b('Annulla prenotazione'))
    const post = net.trattieni(ANNULLA)
    await b('Conferma annullamento').trigger('click')
    await vai(router, '/area')
    net.on(LISTA, jsonResponse(200, [appuntamento(12)]))
    await vai(router, '/paziente/prenotazioni')
    expect(b('Annulla prenotazione').attributes('disabled')).toBeDefined()
    post.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('Annullamento registrato.')
    expect(b('Annulla prenotazione').attributes('disabled')).toBeDefined()
    expect(b('Aggiorna prenotazione')).toBeDefined()
    expect(net.callsTo(LISTA)).toHaveLength(2)
    net.on(LISTA, jsonResponse(200, [appuntamento(12, { stato: 'ANNULLATA' })])).on(PAG(12), jsonResponse(200, []))
    await clic(b('Aggiorna prenotazione'))
    expect(b('Annulla prenotazione')).toBeUndefined()
    expect(net.callsTo(ANNULLA)).toHaveLength(1)
  })
  it('annullamento incerto navigando: recupero manuale senza reinvio', async () => {
    const { router } = await monta()
    await clic(b('Annulla prenotazione'))
    net.on(ANNULLA, 'rete')
    await clic(b('Conferma annullamento'))
    await vai(router, '/area')
    net.on(LISTA, jsonResponse(200, [appuntamento(12)]))
    await vai(router, '/paziente/prenotazioni')
    expect(wrapper.get('[data-incerto]').text()).toContain("Non è stato possibile stabilire")
    net.on(LISTA, jsonResponse(200, [appuntamento(12)]))
    await clic(b('Verifica lo stato'))
    expect(wrapper.text()).toContain('Stato attuale: Richiesta')
    expect(b('Annulla prenotazione')).toBeDefined()
    expect(net.callsTo(ANNULLA)).toHaveLength(1)
  })
})

describe('rimborso dal collegamento verificato', () => {
  it('una sola lettura rimborsi per più card; richiesta preesistente anche RESPINTA non duplicata', async () => {
    net.on(PAG(12), jsonResponse(200, [pagamento(5)]))
    net.on(PAG(13), jsonResponse(200, [pagamento(6, { idPrenotazione: 13 })]))
    net.on(RIMB, jsonResponse(200, [rimborso(1, { stato: 'RESPINTO' })]))
    await monta([nonErogata(), nonErogata(13)])
    expect(net.callsTo(RIMB)).toHaveLength(1)
    const links = wrapper.findAll('a').filter(x => x.text() === 'Chiedi rimborso')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toBe('/paziente/prenotazioni/13#titolo-rimborso')
  })
  it('navigazione e focus al titolo; motivazione raggiungibile, nessuna scrittura automatica', async () => {
    net.on(PAG(12), jsonResponse(200, [pagamento(5)]), jsonResponse(200, [pagamento(5)]))
    net.on(RIMB, jsonResponse(200, []), jsonResponse(200, []))
    const { router } = await monta([nonErogata()])
    net.on(LISTA, jsonResponse(200, [nonErogata()]))
    await clic(wrapper.get('a[href$="#titolo-rimborso"]'))
    await vi.waitFor(() => expect(router.currentRoute.value.hash).toBe('#titolo-rimborso'))
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('#titolo-rimborso').element)
    expect(wrapper.get('label[for="motivazione-rimborso"]').text()).toBe('Motivazione')
    expect(wrapper.get('textarea').element.value).toBe('')
    expect(net.calls.filter(x => x.method === 'POST')).toHaveLength(0)
  })
  it('condizioni cambiate durante la navigazione: stato corrente, nessun modulo indebito', async () => {
    net.on(PAG(12), jsonResponse(200, [pagamento(5)]), jsonResponse(200, [pagamento(5)]))
    net.on(RIMB, jsonResponse(200, []))
    await monta([nonErogata()])
    net.on(LISTA, jsonResponse(200, [confermata(12, { stato: 'ANNULLATA' })]))
    await clic(wrapper.get('a[href$="#titolo-rimborso"]'))
    await vi.waitFor(() => expect(wrapper.text()).toContain('Lo stato corrente della prenotazione non consente'))
    await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('#titolo-rimborso').element)
  })
  it('errore rimborsi recuperabile; nessuna indisponibilità dell’intero elenco', async () => {
    net.on(PAG(12), jsonResponse(200, [pagamento(5)]))
    net.on(RIMB, jsonResponse(500, {}))
    await monta([nonErogata(), appuntamento(13)])
    expect(wrapper.findAll('[data-prenotazione]')).toHaveLength(2)
    expect(b('Annulla prenotazione')).toBeDefined()
    expect(wrapper.find('a[href$="#titolo-rimborso"]').exists()).toBe(false)
    net.on(RIMB, jsonResponse(200, []))
    await clic(b('Aggiorna requisiti rimborso'))
    expect(wrapper.find('a[href$="#titolo-rimborso"]').exists()).toBe(true)
  })
})

describe('referto sulla card', () => {
  it('metadati prima del pulsante; download solo esplicito e autenticato', async () => {
    const spie = downloadSpie()
    net.on(PAG(12), jsonResponse(200, [])); net.on(REF, jsonResponse(200, referto()))
    await monta([confermata(12, { stato: 'EFFETTUATA' })])
    expect(b('Scarica referto').classes()).toContain('azione-referto')
    expect(net.callsTo(DOC)).toHaveLength(0)
    net.on(DOC, pdfResponse())
    await clic(b('Scarica referto'))
    expect(net.callsTo(DOC)[0].init.credentials).toBe('same-origin')
    expect(spie.crea).toHaveBeenCalledOnce()
    expect(spie.click).toHaveBeenCalledOnce()
  })
  it('404 dei metadati non propone download; errore tecnico non nasconde gli altri dati', async () => {
    net.on(PAG(12), jsonResponse(200, [])); net.on(REF, jsonResponse(404, {}))
    await monta([confermata(12, { stato: 'EFFETTUATA' })])
    expect(b('Scarica referto')).toBeUndefined()
    expect(wrapper.find('[data-prenotazione]').exists()).toBe(true)
  })
  it('download 404 mostra errore senza object URL o retry automatico', async () => {
    const spie = downloadSpie()
    net.on(PAG(12), jsonResponse(200, [])); net.on(REF, jsonResponse(200, referto()))
    await monta([confermata(12, { stato: 'EFFETTUATA' })])
    net.on(DOC, jsonResponse(404, {}))
    await clic(b('Scarica referto'))
    expect(wrapper.text()).toContain('Il documento del referto non è disponibile.')
    expect(spie.crea).not.toHaveBeenCalled()
    expect(net.callsTo(DOC)).toHaveLength(1)
  })
  it('logout durante il download: nessun documento o risultato della vecchia identità', async () => {
    const spie = downloadSpie()
    net.on(PAG(12), jsonResponse(200, [])); net.on(REF, jsonResponse(200, referto()))
    const { session } = await monta([confermata(12, { stato: 'EFFETTUATA' })])
    let completa
    net.on(DOC, () => new Promise(resolve => { completa = resolve }))
    await b('Scarica referto').trigger('click')
    net.on('POST /api/auth/logout', jsonResponse(200, { message: 'ok' }))
    await session.logout(); await flushPromises()
    completa(pdfResponse()); await flushPromises()
    expect(spie.crea).not.toHaveBeenCalled()
    expect(wrapper.find('[data-prenotazione]').exists()).toBe(false)
  })
  it('download tardivo dopo navigazione non viene avviato nella nuova pagina', async () => {
    const spie = downloadSpie()
    net.on(PAG(12), jsonResponse(200, [])); net.on(REF, jsonResponse(200, referto()))
    const { router } = await monta([confermata(12, { stato: 'EFFETTUATA' })])
    const doc = net.trattieni(DOC)
    await b('Scarica referto').trigger('click')
    await vai(router, '/area')
    doc.resolve(pdfResponse()); await flushPromises()
    expect(spie.crea).not.toHaveBeenCalled()
    expect(spie.click).not.toHaveBeenCalled()
  })
})
