import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { deskStore } from '../src/desk/store.js'
import { parseRichiesta } from '../src/api/desk.js'
import { CARD_DESK } from '../src/desk/useDashboardDesk.js'
import { desk, admin, medicoDesk, richiesta, segnalazione, rimborsoDesk, gestione, OP, RICHIESTE, SEGNALAZIONI, RIMBORSI, MEDICI } from './b7-fixtures.js'
import { slot, configurato } from './b6-fixtures.js'
import { CSRF_B, installFetch, routedFetch, jsonResponse, meOk, utente } from './helpers.js'
let wrapper, net, visibilita, descrittore
const DISP = id => `GET /api/disponibilita?medicoId=${id}`
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  vi.setSystemTime(new Date(2026,9,24,12))
  visibilita = 'visible'
  descrittore = Object.getOwnPropertyDescriptor(document, 'visibilityState')
  Object.defineProperty(document,'visibilityState',{ configurable: true, get: () => visibilita })
  net = installFetch(routedFetch())
  dati()
})
afterEach(() => {
  wrapper?.unmount(); wrapper = null
  if (descrittore) Object.defineProperty(document,'visibilityState',descrittore)
  else delete document.visibilityState
  vi.clearAllTimers(); vi.useRealTimers()
})
function dati({ richieste = jsonResponse(200,[richiesta(1),richiesta(2)]), segnalazioni = jsonResponse(200,[segnalazione(3)]), disponibilita9 = jsonResponse(200,[slot(3,'2026-11-01T10:00:00',{idMedico:9}),slot(4,'2026-11-01T11:00:00',{idMedico:9,idStanza:3})]) } = {}) {
  if (richieste !== null) net.on(RICHIESTE,richieste)
  net.on(SEGNALAZIONI,segnalazioni)
  net.on(RIMBORSI,jsonResponse(200,[rimborsoDesk(4)]))
  net.on(MEDICI,jsonResponse(200,[medicoDesk(4),medicoDesk(9,{stato:'INATTIVO'})]))
  net.on(DISP(4),jsonResponse(200,[slot(1,'2001-01-01T09:00:00'),configurato(2,'2026-11-01T09:00:00')]))
  net.on(DISP(9),disponibilita9)
}
async function apri(user=desk()) {
  net.on('GET /api/auth/me',meOk(user))
  const session=createSession(),router=createAppRouter({session,history:createMemoryHistory()})
  await router.push('/area');await router.isReady()
  wrapper=mount(App,{attachTo:document.body,global:{plugins:[router],provide:{[SESSION_KEY]:session}}})
  await flushPromises()
  return {session,router,store:deskStore(session)}
}
const card = id => wrapper.get(`[data-conteggio="${id}"]`)
const aggiorna = () => wrapper.get('[aria-label="Dashboard operativa desk"] button')
const click = async b => {await b.trigger('click');await flushPromises()}
const cambiaVisibilita = async valore => {visibilita=valore;document.dispatchEvent(new Event('visibilitychange'));await flushPromises()}

it('totali completi e link autorizzati, incluse date passate e medici inattivi; configurazioni parziali escluse',async()=>{
  await apri()
  expect(CARD_DESK.map(c=>card(c.id).get('[data-valore]').text())).toEqual(['2','1','1','2'])
  for(const c of CARD_DESK)expect(card(c.id).attributes('href')).toBe(c.id === 'rimborsi' ? '/desk/rimborsi?stato=RICHIESTO' : c.to)
  expect(card('disponibilita').text()).toContain('anche passate')
  expect(net.callsTo(MEDICI)).toHaveLength(1)
  expect(net.callsTo(DISP(9))).toHaveLength(1)
  expect(net.calls.every(c=>c.method==='GET')).toBe(true)
})

it.each([admin(),utente(),utente({medico:true}),utente({medico:true,paziente:false})])('nessuna dashboard né lettura desk per altri profili',async user=>{
  await apri(user)
  expect(wrapper.find('[data-conteggio]').exists()).toBe(false)
  expect(net.calls.map(c=>c.url)).toEqual(['/api/auth/me'])
})

it('zero solo da letture riuscite, errore indipendente e conteggio disponibilità incompleto mai presentato come totale',async()=>{
  net=installFetch(routedFetch())
  dati({richieste:jsonResponse(200,[]),segnalazioni:jsonResponse(500,{error:'Errore sintetico'}),disponibilita9:jsonResponse(500,{error:'Errore sintetico'})})
  await apri()
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
  expect(card('segnalazioni').text()).toContain('Dato non disponibile')
  expect(card('disponibilita').text()).toContain('Dato non disponibile')
  expect(card('disponibilita').find('[data-valore]').exists()).toBe(false)
  expect(card('rimborsi').get('[data-valore]').text()).toBe('1')
  dati()
  await click(aggiorna())
  expect(card('disponibilita').get('[data-valore]').text()).toBe('2')
})

it('aggiornamento ogni 60 secondi senza spostare il focus',async()=>{
  await apri()
  aggiorna().element.focus()
  dati()
  await vi.advanceTimersByTimeAsync(59_999)
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(1)
  await flushPromises()
  expect(net.callsTo(RICHIESTE)).toHaveLength(2)
  expect(document.activeElement).toBe(aggiorna().element)
  expect(wrapper.get('[data-ultimo-controllo]').text()).toContain('12:01:00')
})

it('pagina nascosta: timer fermato; ritorno avvia una sola lettura e smontaggio ferma gli aggiornamenti',async()=>{
  const {router}=await apri()
  await cambiaVisibilita('hidden')
  await vi.advanceTimersByTimeAsync(180_000)
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  dati()
  await cambiaVisibilita('visible')
  expect(net.callsTo(RICHIESTE)).toHaveLength(2)
  await router.push('/prestazioni');await flushPromises()
  await vi.advanceTimersByTimeAsync(180_000)
  expect(net.callsTo(RICHIESTE)).toHaveLength(2)
})

it('richiesta trattenuta: doppio clic e tempo trascorso non sovrappongono cicli',async()=>{
  net=installFetch(routedFetch())
  const h=net.trattieni(RICHIESTE)
  dati({richieste:null})
  await apri()
  await click(aggiorna());await click(aggiorna())
  await vi.advanceTimersByTimeAsync(180_000)
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  h.resolve(jsonResponse(200,[richiesta(1)]));await flushPromises()
  expect(card('richieste').get('[data-valore]').text()).toBe('1')
  expect(aggiorna().attributes('aria-disabled')).toBeUndefined()
})

it('risposta che ignora abort non pubblicata da nascosta; ritorno attende la conclusione prima di rileggere',async()=>{
  net=installFetch(routedFetch())
  let rilascia
  net.on(RICHIESTE,()=>new Promise(resolve=>{rilascia=resolve}))
  dati({richieste:null})
  await apri()
  await cambiaVisibilita('hidden')
  await cambiaVisibilita('visible')
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  dati({richieste:jsonResponse(200,[])})
  rilascia(jsonResponse(200,[richiesta(1)]));await flushPromises()
  expect(net.callsTo(RICHIESTE)).toHaveLength(2)
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
})

it('rotazione sessione scarta il vecchio ciclo e riparte senza sovrapposizioni',async()=>{
  net=installFetch(routedFetch())
  let rilascia
  net.on(RICHIESTE,()=>new Promise(resolve=>{rilascia=resolve}))
  dati({richieste:null})
  const {session}=await apri()
  net.on('PUT /api/auth/password',jsonResponse(200,{message:'ok',nuovoAccessoRichiesto:false},{'X-CSRF-Token':CSRF_B}))
  expect((await session.changePassword('a','b')).kind).toBe('aggiornata')
  await flushPromises()
  dati({richieste:jsonResponse(200,[])})
  rilascia(jsonResponse(200,[richiesta(1)]));await flushPromises()
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
  expect(net.callsTo(RICHIESTE)).toHaveLength(2)
})

it('scrittura certa dello store invalida reattivamente il conteggio senza modificare blocchi né riconciliazioni',async()=>{
  const {store}=await apri()
  net.on(OP(1,'rifiuta'),jsonResponse(200,gestione(1,'RIFIUTATA')))
  expect((await store.decidiRichiesta('rifiuta',parseRichiesta(richiesta(1)))).kind).toBe('eseguita')
  await flushPromises()
  expect(card('richieste').text()).toContain('Dato non disponibile')
  expect(card('segnalazioni').text()).toContain('Dato non disponibile')
  expect(card('rimborsi').get('[data-valore]').text()).toBe('1')
  net.on(RICHIESTE,jsonResponse(200,[]))
  await click(aggiorna())
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
})

it('la pagina nascosta annulla anche la fetch, non soltanto il timer',async()=>{
  net=installFetch(routedFetch())
  const h=net.trattieni(RICHIESTE)
  dati({richieste:null})
  await apri()
  await cambiaVisibilita('hidden')
  expect(h.aborted).toBe(true)
  await vi.advanceTimersByTimeAsync(120_000)
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  dati({richieste:jsonResponse(200,[])})
  await cambiaVisibilita('visible')
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
})

it('abort della vista non termina la sessione; cambio epoca annulla anche una lettura con segnale proprio',async()=>{
  const {session}=await apri()
  const epoca=session.state.epoch
  const primo=net.trattieni('GET /api/prestazioni')
  const controller=new AbortController()
  const esito=session.protectedRequest({path:'/api/prestazioni',signal:controller.signal}).catch(e=>e)
  controller.abort()
  expect((await esito).aborted).toBe(true)
  expect(primo.aborted).toBe(true)
  expect(session.state.epoch).toBe(epoca)
  expect(session.state.status).toBe('autenticato')
  const secondo=net.trattieni('GET /api/prestazioni')
  const indipendente=new AbortController()
  const lettura=session.protectedRequest({path:'/api/prestazioni',signal:indipendente.signal}).catch(e=>e)
  dati()
  net.on('PUT /api/auth/password',jsonResponse(200,{message:'ok',nuovoAccessoRichiesto:false},{'X-CSRF-Token':CSRF_B}))
  expect((await session.changePassword('a','b')).kind).toBe('aggiornata')
  await flushPromises()
  expect(secondo.aborted).toBe(true)
  expect(indipendente.signal.aborted).toBe(false)
  expect((await lettura).name).toBe('StaleResponseError')
})

it('barriera prodotta durante la lettura impedisce di adottare il vecchio conteggio',async()=>{
  net=installFetch(routedFetch())
  const h=net.trattieni(RICHIESTE)
  dati({richieste:null})
  const {store}=await apri()
  net.on(OP(1,'rifiuta'),jsonResponse(200,gestione(1,'RIFIUTATA')))
  await store.decidiRichiesta('rifiuta',parseRichiesta(richiesta(1)))
  h.resolve(jsonResponse(200,[richiesta(1)]));await flushPromises()
  expect(card('richieste').text()).toContain('Dato non disponibile')
  expect(card('richieste').find('[data-valore]').exists()).toBe(false)
  expect(net.callsTo(RICHIESTE)).toHaveLength(1)
  dati({richieste:jsonResponse(200,[])})
  await click(aggiorna())
  expect(card('richieste').get('[data-valore]').text()).toBe('0')
})

it.each(CARD_DESK)('la card $titolo apre la rotta desk corrispondente senza scritture',async c=>{
  const {router}=await apri()
  dati()
  await click(card(c.id))
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(c.to))
  if (c.id === 'rimborsi') {
    expect(router.currentRoute.value.query.stato).toBe('RICHIESTO')
    expect(wrapper.get('#filtro-rimborsi-desk').element.value).toBe('RICHIESTO')
  }
  expect(router.currentRoute.value.meta.capability).toBe('desk')
  expect(net.calls.every(x=>x.method==='GET')).toBe(true)
})
