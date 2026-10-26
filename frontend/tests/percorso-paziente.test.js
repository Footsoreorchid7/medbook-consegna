import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { sceltaStore } from '../src/prenotazioni/scelta.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { appuntamento } from './b3-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'
const CAT='GET /api/prestazioni', ME='GET /api/auth/me', POST='POST /api/prenotazioni'
const LIST='GET /api/prenotazioni/paziente?idPaziente=7'
const DAY='2026-10-29', DISP=`GET /api/disponibilita/prenotabili?prestazioneId=1&data=${DAY}`
const catalogo=[{idPrestazione:1,nome:'Cardiologia',descrizione:null,durata:30,prezzo:80},{idPrestazione:2,nome:'Ecografia',descrizione:'Descrizione',durata:30,prezzo:95}]
const slot=(id=10,extra={})=>({idDisponibilita:id,idPrestazione:1,idMedico:4,nomeMedico:'Elena',cognomeMedico:'Sintetica',nomePrestazione:'Cardiologia',durataPrestazione:30,prezzoPrestazione:80,dataOraInizio:DAY+'T09:00:00',dataOraFine:DAY+'T10:00:00',...extra})
const created={idPrenotazione:50,idDisponibilita:10,stato:'RICHIESTA',prezzo:80,idMedico:4,nomeMedico:'Elena',cognomeMedico:'Sintetica',nomePrestazione:'Cardiologia',dataOraInizio:DAY+'T09:00:00',dataOraFine:DAY+'T10:00:00',dataOraRichiesta:'2026-10-22T12:00:00'}
let net,wrapper
beforeEach(()=>{net=installFetch(routedFetch())})
afterEach(()=>{wrapper?.unmount();wrapper=null})
async function open(path='/disponibilita',user=unauthorized()) {
 net.on(ME,user)
 const session=createSession(),router=createAppRouter({session,history:createMemoryHistory()})
 await router.push(path)
 wrapper=mount(App,{global:{plugins:[router],provide:{[SESSION_KEY]:session}},attachTo:document.body})
 await flushPromises();return {session,router,choice:sceltaStore(session)}
}
const main=()=>wrapper.get('main')
const submit=async()=>{await wrapper.get('form').trigger('submit');await flushPromises()}
async function search(id='1',date=DAY){await wrapper.get('#filtro-prestazione').setValue(id);await wrapper.get('#filtro-data').setValue(date);await submit()}
async function chosen(){
 net.on(CAT,jsonResponse(200,catalogo)).on(DISP,jsonResponse(200,[slot()])).on(LIST,jsonResponse(200,[]))
 const ctx=await open('/disponibilita',meOk())
 await search();net.on(DISP,jsonResponse(200,[slot()]))
 await wrapper.get('[data-disponibilita] button').trigger('click');await vi.waitFor(()=>expect(ctx.router.currentRoute.value.name).toBe('prenotazioni'));await flushPromises();return ctx
}
const confirm=()=>wrapper.get('[data-completa-richiesta] button.button')

describe('catalogo → disponibilità → accesso → richiesta',()=>{
 it('ogni scheda trasferisce la prestazione validata senza cercare automaticamente gli slot',async()=>{
  net.on(CAT,jsonResponse(200,catalogo),jsonResponse(200,catalogo))
  const {router}=await open('/prestazioni')
  const links=wrapper.findAll('[data-prestazione] a.button');expect(links).toHaveLength(2)
  expect(links[0].text()).toContain('Verifica disponibilità')
  await links[1].trigger('click');await flushPromises()
  await vi.waitFor(()=>expect(router.currentRoute.value.query.prestazioneId).toBe('2'));await flushPromises()
  expect(wrapper.get('#filtro-prestazione').element.value).toBe('2')
  expect(net.calls.filter(c=>c.url.startsWith('/api/disponibilita/'))).toHaveLength(0)
 })
 it('dal menu nessuna GET slot; prestazione e data obbligatorie prima del submit',async()=>{
  net.on(CAT,jsonResponse(200,catalogo));await open()
  expect(wrapper.find('[data-disponibilita]').exists()).toBe(false)
  await submit();await wrapper.get('#filtro-prestazione').setValue('1');await submit()
  expect(net.calls.filter(c=>c.url.startsWith('/api/disponibilita/'))).toHaveLength(0)
  net.on(DISP,jsonResponse(200,[]));await wrapper.get('#filtro-data').setValue(DAY);await submit()
  expect(net.callsTo(DISP)).toHaveLength(1);expect(main().text()).toContain('Nessuna disponibilità')
 })
 it.each(['abc','01','2147483648','1&prestazioneId=2','99'])('parametro %s non seleziona dati non validati',async raw=>{
  net.on(CAT,jsonResponse(200,catalogo));await open('/disponibilita?prestazioneId='+raw)
  expect(wrapper.get('#filtro-prestazione').element.value).toBe('')
  expect(main().text()).toMatch(/non è valida|non è nel catalogo/)
  expect(net.calls.filter(c=>c.url.startsWith('/api/disponibilita/'))).toHaveLength(0)
 })
 it('modifica filtri invalida subito i risultati e scarta risposte tardive anche tornando A → B → A',async()=>{
  net.on(CAT,jsonResponse(200,catalogo));await open()
  let old;net.on(DISP,()=>new Promise(r=>{old=r}));await search()
  await wrapper.get('#filtro-data').setValue('2026-10-30')
  expect(wrapper.find('[data-disponibilita]').exists()).toBe(false)
  await wrapper.get('#filtro-data').setValue(DAY)
  net.on(DISP,jsonResponse(200,[slot(11)]));await submit()
  old(jsonResponse(200,[slot(10)]));await flushPromises()
  expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(1)
  expect(main().text()).toContain('1 disponibilità trovate')
  expect(net.callsTo(POST)).toHaveLength(0)
  expect(net.callsTo(DISP)[0].init.signal.aborted).toBe(true)
 })
 it('scelta anonima mantenuta nel login, ritorno all’elenco con solo lo slot scelto e zero POST',async()=>{
  net.on(CAT,jsonResponse(200,catalogo)).on(DISP,jsonResponse(200,[slot(),slot(11)]))
  const {session,router,choice}=await open();await search()
  await wrapper.get('[data-disponibilita] button').trigger('click');await flushPromises()
  await vi.waitFor(()=>expect(router.currentRoute.value.name).toBe('accesso'));await flushPromises()
  expect(choice.state.scelta).toEqual({idDisponibilita:10,prestazioneId:1,data:DAY})
  net.on('POST /api/auth/login',meOk()).on(LIST,jsonResponse(200,[])).on(DISP,jsonResponse(200,[slot(),slot(11)]))
  await wrapper.get('#email').setValue('paziente@example.test');await wrapper.get('#password').setValue('fittizia');await submit()
  await vi.waitFor(()=>expect(router.currentRoute.value.path).toBe('/paziente/prenotazioni'));await flushPromises()
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('Completa la richiesta')
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('80,00')
  expect(net.callsTo(POST)).toHaveLength(0)
  expect(session.state.user.idUtente).toBe(7)
 })
 it('conferma esplicita: GET fresca poi payload esatto; elenco riletto e numero nuovo evidenziato',async()=>{
  await chosen()
  expect(net.callsTo(POST)).toHaveLength(0)
  net.on(DISP,jsonResponse(200,[slot()])).on(POST,jsonResponse(201,created)).on(LIST,jsonResponse(200,[appuntamento(50)]))
  confirm().element.focus()
  await confirm().trigger('click');await flushPromises()
  expect(document.activeElement).toBe(wrapper.get('[data-nuova-richiesta]').element)
  expect(JSON.parse(net.callsTo(POST)[0].body)).toEqual({idPaziente:7,idDisponibilita:10})
  expect(net.callsTo(DISP)).toHaveLength(3)
  expect(wrapper.get('[data-nuova-richiesta]').text()).toContain('Prenotazione n. 50')
  expect(wrapper.get('[data-prenotazione]').text()).toContain('Prenotazione n. 50')
  expect(wrapper.get('[data-prenotazione]').text()).toContain('Nuova richiesta')
  expect(wrapper.find('[data-completa-richiesta]').exists()).toBe(false)
 })
 it('slot sparito prima della conferma: niente POST, ricerca conserva la prestazione',async()=>{
  await chosen();net.on(DISP,jsonResponse(200,[]))
  await confirm().trigger('click');await flushPromises()
  expect(net.callsTo(POST)).toHaveLength(0)
  expect(main().text()).toContain('Lo slot scelto non è più disponibile')
  expect(wrapper.get('[data-completa-richiesta] a').attributes('href')).toBe('/disponibilita?prestazioneId=1')
 })
 it('prezzo modificato nella GET finale: nuovo riepilogo e nuova conferma prima di ogni POST',async()=>{
  await chosen();net.on(DISP,jsonResponse(200,[slot(10,{prezzoPrestazione:95})]))
  await confirm().trigger('click');await flushPromises()
  expect(net.callsTo(POST)).toHaveLength(0)
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('95,00')
  expect(main().text()).toContain('conferma di nuovo')
 })
 it('errore GET prima del POST resta recuperabile senza scrittura',async()=>{
  await chosen();net.on(DISP,'rete');await confirm().trigger('click');await flushPromises()
  expect(main().text()).toContain('nessuna richiesta è stata inviata')
  expect(net.callsTo(POST)).toHaveLength(0)
  net.on(DISP,jsonResponse(200,[slot()]));await wrapper.findAll('[data-completa-richiesta] button').find(b=>b.text()==='Riprova la verifica').trigger('click');await flushPromises()
  expect(main().text()).toContain('Conferma e invia')
 })
 it('login senza profilo paziente scarta la scelta e non propone richieste',async()=>{
  net.on(CAT,jsonResponse(200,catalogo)).on(DISP,jsonResponse(200,[slot()]))
  const {router,choice}=await open();await search();await wrapper.get('[data-disponibilita] button').trigger('click');await flushPromises()
  net.on('POST /api/auth/login',meOk(utente({paziente:false,ruoloGestionale:'AMMINISTRATORE'})))
  await wrapper.get('#email').setValue('admin@example.test');await wrapper.get('#password').setValue('fittizia');await submit()
  await vi.waitFor(()=>expect(router.currentRoute.value.path).toBe('/area'));await flushPromises();expect(choice.state.scelta).toBeNull()
  expect(wrapper.find('[data-completa-richiesta]').exists()).toBe(false);expect(net.callsTo(POST)).toHaveLength(0)
 })
 it('logout e nuovo utente non ereditano la scelta; nessun dato persistito',async()=>{
  const {session,choice}=await chosen()
  net.on('POST /api/auth/logout',new Response(null,{status:204}));await session.logout();await flushPromises()
  expect(choice.state.scelta).toBeNull();expect(choice.state.ultimaCreata).toBeNull()
  net.on('POST /api/auth/login',meOk(utente({idUtente:8})));await session.login('altro@example.test','fittizia')
  expect(choice.state.scelta).toBeNull();expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0)
 })
 it('rotazione durante riverifica: niente POST dal vecchio contesto; stessa scelta ricaricata',async()=>{
  const {session}=await chosen();let old
  net.on(DISP,()=>new Promise(r=>{old=r}));await confirm().trigger('click')
  net.on('PUT /api/auth/password',jsonResponse(200,{message:'ok',nuovoAccessoRichiesto:false},{'X-CSRF-Token':CSRF_B})).on(LIST,jsonResponse(200,[])).on(DISP,jsonResponse(200,[slot()]))
  await session.changePassword('precedente','nuova');await flushPromises();old(jsonResponse(200,[slot()]));await flushPromises()
  expect(net.callsTo(POST)).toHaveLength(0);expect(confirm().attributes('disabled')).toBeUndefined()
 })
 it('incertezza conservata navigando: verifica manuale, nuovo invio solo dopo nuova scelta',async()=>{
  const {router,session}=await chosen();net.on(DISP,jsonResponse(200,[slot()])).on(POST,'rete')
  await confirm().trigger('click');await flushPromises()
  expect(prenotazioniStore(session).richiestaBloccata(10)).toBe(true)
  await router.push('/area');await flushPromises();net.on(LIST,jsonResponse(200,[])).on(DISP,jsonResponse(200,[slot()]))
  await router.push('/paziente/prenotazioni');await flushPromises()
  expect(confirm().attributes('disabled')).toBeDefined()
  net.on(LIST,jsonResponse(200,[]))
  await wrapper.get('[data-completa-incerta] button').trigger('click');await flushPromises()
  expect(main().text()).toContain('non prova che l’invio sia fallito')
  expect(net.callsTo(POST)).toHaveLength(1)
 })
 it('vecchio link attraverso login: ID riconosciuto ma prezzo URL ignorato e nessuna POST',async()=>{
  const {router}=await open('/paziente/prenota?slot=10&prezzo=0')
  net.on('POST /api/auth/login',meOk()).on(LIST,jsonResponse(200,[])).on('GET /api/disponibilita/prenotabili',jsonResponse(200,[slot()]))
  await wrapper.get('#email').setValue('paziente@example.test');await wrapper.get('#password').setValue('fittizia');await submit()
  await vi.waitFor(()=>expect(router.currentRoute.value.path).toBe('/paziente/prenotazioni'));await flushPromises()
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('80,00');expect(net.callsTo(POST)).toHaveLength(0)
 })
 it('numero visibile nel dettaglio oltre a prestazione, data e stato',async()=>{
  net.on(LIST,jsonResponse(200,[appuntamento(12)]));await open('/paziente/prenotazioni/12',meOk())
  expect(wrapper.get('[data-prenotazione]').text()).toContain('Prenotazione n. 12')
  expect(wrapper.get('[data-prenotazione]').text()).toContain('Visita cardiologica')
 })
 it('successo dopo navigazione: store dell’esito originario aggiorna la nuova istanza senza duplicati',async()=>{
  const {router}=await chosen()
  net.on(DISP,jsonResponse(200,[slot()]))
  const post=net.trattieni(POST)
  await confirm().trigger('click');await flushPromises()
  expect(net.callsTo(POST)).toHaveLength(1)
  await router.push('/area');await flushPromises()
  net.on(LIST,jsonResponse(200,[])).on(DISP,jsonResponse(200,[slot()]))
  await router.push('/paziente/prenotazioni');await flushPromises()
  expect(confirm().attributes('disabled')).toBeDefined()
  net.on(LIST,jsonResponse(200,[appuntamento(50)]))
  post.resolve(jsonResponse(201,created));await flushPromises()
  expect(wrapper.get('[data-nuova-richiesta]').text()).toContain('Prenotazione n. 50')
  expect(wrapper.find('[data-completa-richiesta]').exists()).toBe(false)
  expect(net.callsTo(POST)).toHaveLength(1)
 })
 it('nuova identità da verifica manuale non adotta scelta o letture dell’utente precedente',async()=>{
  const {session,choice}=await chosen()
  net.on('PUT /api/auth/password',jsonResponse(500,{error:'x'}))
  await session.changePassword('precedente','nuova')
  expect(confirm().attributes('disabled')).toBeDefined()
  net.on(ME,meOk(utente({idUtente:8}))).on('GET /api/prenotazioni/paziente?idPaziente=8',jsonResponse(200,[]))
  await session.verifyAfterPasswordChange();await flushPromises()
  expect(choice.state.scelta).toBeNull();expect(wrapper.find('[data-completa-richiesta]').exists()).toBe(false)
  expect(net.callsTo(POST)).toHaveLength(0)
 })
 it('doppio clic durante riverifica non duplica GET o POST',async()=>{
  await chosen();const get=net.trattieni(DISP)
  const button=confirm();await button.trigger('click');await button.trigger('click');await flushPromises()
  expect(net.callsTo(DISP)).toHaveLength(3)
  net.on(POST,jsonResponse(201,created)).on(LIST,jsonResponse(200,[appuntamento(50)]))
  get.resolve(jsonResponse(200,[slot()]));await flushPromises()
  expect(net.callsTo(POST)).toHaveLength(1)
 })
 it('link storico già autenticato permette di completare nell’elenco senza alcuna scrittura automatica',async()=>{
  net.on(CAT,jsonResponse(200,catalogo)).on('GET /api/disponibilita/prenotabili',jsonResponse(200,[slot()]))
  const {router}=await open('/paziente/prenota?slot=10',meOk())
  net.on('GET /api/disponibilita/prenotabili',jsonResponse(200,[slot()])).on(LIST,jsonResponse(200,[]))
  await wrapper.findAll('main button').find(b=>b.text()==='Completa la scelta in Le mie prenotazioni').trigger('click')
  await vi.waitFor(()=>expect(router.currentRoute.value.path).toBe('/paziente/prenotazioni'));await flushPromises()
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('80,00')
  expect(net.callsTo(POST)).toHaveLength(0)
 })

 it('evento accodato di una vecchia scelta dopo cambio filtri: nessuna navigazione né selezione',async()=>{
  net.on(CAT,jsonResponse(200,catalogo)).on(DISP,jsonResponse(200,[slot()]))
  const {router,choice}=await open();await search()
  const vecchio=wrapper.get('[data-disponibilita] button')
  await wrapper.get('#filtro-data').setValue('2026-10-30')
  await vecchio.trigger('click');await flushPromises()
  expect(router.currentRoute.value.path).toBe('/disponibilita')
  expect(choice.state.scelta).toBeNull();expect(wrapper.find('[data-disponibilita]').exists()).toBe(false)
  expect(net.callsTo(POST)).toHaveLength(0)
 })

 it('login incerto: scelta minima conservata e recupero manuale B1, mai POST automatiche',async()=>{
  net.on(CAT,jsonResponse(200,catalogo)).on(DISP,jsonResponse(200,[slot()]))
  const {session,router,choice}=await open();await search()
  net.on('POST /api/auth/login','rete');await session.login('paziente@example.test','fittizia')
  expect(session.state.loginUncertain).toBe(true)
  await wrapper.get('[data-disponibilita] button').trigger('click')
  await vi.waitFor(()=>expect(router.currentRoute.value.name).toBe('accesso'));await flushPromises()
  expect(choice.state.scelta.idDisponibilita).toBe(10)
  expect(wrapper.find('form').exists()).toBe(false)
  net.on(ME,meOk()).on(LIST,jsonResponse(200,[])).on(DISP,jsonResponse(200,[slot()]))
  await wrapper.findAll('main button').find(b=>b.text()==='Verifica stato di accesso').trigger('click')
  await vi.waitFor(()=>expect(router.currentRoute.value.path).toBe('/paziente/prenotazioni'));await flushPromises()
  expect(wrapper.get('[data-completa-richiesta]').text()).toContain('80,00')
  expect(net.callsTo(POST)).toHaveLength(0)
 })

})
