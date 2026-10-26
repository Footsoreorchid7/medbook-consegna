import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { deskStore } from '../src/desk/store.js'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { parseMedicoDesk } from '../src/api/desk.js'
import { slot, configurato } from './b6-fixtures.js'
import { desk, medicoDesk, dettaglio, stanzaDesk, prestazione, MEDICI, MEDICO, STANZE, DISPONIBILITA, CONFIGURA } from './b7-fixtures.js'
import { installFetch, routedFetch, jsonResponse, meOk } from './helpers.js'
let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })
async function apri() {
 net.on('GET /api/auth/me', meOk(desk())).on(MEDICI,jsonResponse(200,[medicoDesk(4),medicoDesk(5,{stato:'INATTIVO'})]))
 const session=createSession(), router=createAppRouter({session,history:createMemoryHistory()})
 await router.push('/desk/disponibilita');await router.isReady()
 wrapper=mount(App,{attachTo:document.body,global:{plugins:[router],provide:{[SESSION_KEY]:session}}});await flushPromises()
 return {session,router}
}
const righe=()=>wrapper.findAll('[data-slot-desk]')
const button=(w,t)=>w.findAll('button').find(x=>x.text().startsWith(t))
async function click(w,t){await button(w,t).trigger('click');await flushPromises()}
function dati(){net.on(DISPONIBILITA(4),jsonResponse(200,[slot(9,'2026-11-02T10:00:00'),configurato(10,'2026-11-03T09:00:00'),slot(11,'2026-11-04T09:00:00',{idStanza:3,nomeStanza:'A'})])).on(DISPONIBILITA(5),jsonResponse(200,[slot(20,'2026-11-01T09:00:00',{idMedico:5})]))}
it('apertura tutti, ordine, copertura e parziali distinti',async()=>{
 dati();await apri()
 expect(wrapper.get('#desk-medico').element.value).toBe('')
 expect(righe().map(x=>x.attributes('data-id'))).toEqual(['20','9','11'])
 expect(righe()[0].text()).toContain('Medico Sintetico5')
 expect(wrapper.text()).toContain('2 slot da configurare')
 expect(wrapper.text()).toContain('Tutte le date, anche passate')
 expect(button(righe()[2],'Configura')).toBeUndefined()
 expect(wrapper.find('[data-slot-desk][data-id="10"]').exists()).toBe(false)
 expect(net.calls.filter(x=>x.method!=='GET')).toHaveLength(0)
})
it('filtro facoltativo e ritorno a tutti',async()=>{
 dati();await apri()
 net.on(DISPONIBILITA(4),jsonResponse(200,[slot(9,'2026-11-02T10:00:00')])).on(MEDICO(4),jsonResponse(200,dettaglio(4))).on(STANZE,jsonResponse(200,[]))
 await wrapper.get('#desk-medico').setValue('4');await flushPromises()
 expect(righe().map(x=>x.attributes('data-id'))).toEqual(['9'])
 dati();await wrapper.get('#desk-medico').setValue('');await flushPromises()
 expect(righe().map(x=>x.attributes('data-id'))).toEqual(['20','9','11'])
})
it('lettura fallita non diventa vuoto o totale; recupero manuale',async()=>{
 net.on(DISPONIBILITA(4),jsonResponse(200,[])).on(DISPONIBILITA(5),jsonResponse(500,{}));await apri()
 expect(wrapper.text()).toContain('elenco è incompleto')
 expect(wrapper.text()).not.toContain('0 slot da configurare')
 expect(wrapper.text()).not.toContain('Nessuna disponibilità da configurare')
 dati();await click(wrapper,'Riprova');expect(righe()).toHaveLength(3)
})
it('successo rimuove slot, aggiorna conteggio e usa medico corretto',async()=>{
 dati();await apri()
 net.on(MEDICO(5),jsonResponse(200,dettaglio(5))).on(STANZE,jsonResponse(200,[stanzaDesk(3)]))
 await click(righe()[0],'Configura')
 await wrapper.get('#prestazione-20').setValue('2');await wrapper.get('#stanza-20').setValue('3');await click(righe()[0],'Prosegui')
 expect(righe()[0].text()).toContain('Medico: Medico Sintetico5')
 expect(net.callsTo(CONFIGURA(20))).toHaveLength(0)
 net.on(CONFIGURA(20),jsonResponse(200,configurato(20,'2026-11-01T09:00:00',{idMedico:5})))
 net.on(DISPONIBILITA(4),jsonResponse(200,[])).on(DISPONIBILITA(5),jsonResponse(200,[configurato(20,'2026-11-01T09:00:00',{idMedico:5})]))
 await click(righe()[0],'Conferma configurazione')
 expect(righe()).toHaveLength(0);expect(wrapper.text()).toContain('0 slot da configurare')
 expect(JSON.parse(net.callsTo(CONFIGURA(20))[0].body)).toEqual({idPrestazione:2,idStanza:3})
})
it('cambio filtro scarta vecchia risposta che ignora abort',async()=>{
 let risolvi
 net.on(DISPONIBILITA(4),()=>new Promise(r=>{risolvi=r})).on(DISPONIBILITA(5),jsonResponse(200,[]));await apri()
 net.on(DISPONIBILITA(4),jsonResponse(200,[])).on(MEDICO(4),jsonResponse(200,dettaglio(4))).on(STANZE,jsonResponse(200,[]))
 await wrapper.get('#desk-medico').setValue('4');await flushPromises()
 risolvi(jsonResponse(200,[slot(9,'2026-11-02T09:00:00')]));await flushPromises()
 expect(righe()).toHaveLength(0);expect(wrapper.text()).toContain('0 slot da configurare')
})

it('barriera su un medico invalida la vista tutti e chiude conferme',async()=>{
 dati();const {session}=await apri();const store=deskStore(session)
 net.on(MEDICO(4),jsonResponse(200,dettaglio(4))).on(STANZE,jsonResponse(200,[stanzaDesk(3)]))
 await click(righe()[1],'Configura')
 await wrapper.get('#prestazione-9').setValue('2');await wrapper.get('#stanza-9').setValue('3');await click(righe()[1],'Prosegui')
 net.on(CONFIGURA(21),jsonResponse(200,configurato(21,'2026-11-06T09:00:00',{idMedico:5})))
 await store.configura(parseDisponibilita(slot(21,'2026-11-06T09:00:00',{idMedico:5}),5),parseMedicoDesk(medicoDesk(5)),prestazione(2),stanzaDesk(3));await flushPromises()
 expect(wrapper.find('[data-conferma="configura"]').exists()).toBe(false)
 expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
 expect(button(righe()[1],'Configura').attributes('disabled')).toBeDefined()
 expect(net.callsTo(CONFIGURA(9))).toHaveLength(0)
 dati();await click(wrapper,"Aggiorna l'elenco")
 expect(button(righe()[1],'Configura').attributes('disabled')).toBeUndefined()
})
it('lettura globale precedente a una scrittura certa viene scartata e resta recuperabile',async()=>{
 dati();const {session}=await apri();const store=deskStore(session)
 let risolvi
 net.on(DISPONIBILITA(4),()=>new Promise(r=>{risolvi=r})).on(DISPONIBILITA(5),jsonResponse(200,[]))
 await wrapper.get('#desk-medico').setValue('');await flushPromises()
 net.on(CONFIGURA(9),jsonResponse(200,configurato(9,'2026-11-02T10:00:00')))
 await store.configura(parseDisponibilita(slot(9,'2026-11-02T10:00:00'),4),parseMedicoDesk(medicoDesk(4)),prestazione(2),stanzaDesk(3))
 risolvi(jsonResponse(200,[slot(9,'2026-11-02T10:00:00')]));await flushPromises()
 expect(wrapper.find('[data-elenco-superato]').exists()).toBe(true)
 expect(wrapper.find('[data-slot-desk][data-id="9"]').exists()).toBe(false)
 net.on(DISPONIBILITA(4),jsonResponse(200,[configurato(9,'2026-11-02T10:00:00')])).on(DISPONIBILITA(5),jsonResponse(200,[]))
 await click(wrapper,"Aggiorna l'elenco")
 expect(wrapper.text()).toContain('0 slot da configurare')
 expect(wrapper.find('[data-elenco-superato]').exists()).toBe(false)
})
