import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { createAppRouter } from '../src/router/index.js'
import { deskStore, chiavePrenotazione } from '../src/desk/store.js'
import { parseRichiesta, parseRichiestaConsultazione, richiestePath, fetchRichiesteConsultazione } from '../src/api/desk.js'
import { STATI_PRENOTAZIONI_UI, stileStatoAgenda } from '../src/medico/statiAgenda.js'
import { ProtocolError } from '../src/api/http.js'
import { appuntamento } from './b3-fixtures.js'
import { desk, admin, richiesta, gestione, RICHIESTE, OP, MEDICI, PRESTAZIONI } from './b7-fixtures.js'
import { installFetch, routedFetch, jsonResponse, meOk, utente } from './helpers.js'
let wrapper,net
beforeEach(()=>{net=installFetch(routedFetch())})
afterEach(()=>{wrapper?.unmount();wrapper=null})
const statoUrl=s=>`${RICHIESTE}?stato=${s}`
async function apri(path='/desk/richieste',rows=[richiesta(24)],user=desk()) {
 net.on('GET /api/auth/me',meOk(user)).on(MEDICI,jsonResponse(200,[])).on(PRESTAZIONI,jsonResponse(200,[]))
 if(path==='/desk/richieste')net.on(RICHIESTE,jsonResponse(200,rows))
 const session=createSession(),router=createAppRouter({session,history:createMemoryHistory()})
 await router.push(path);await router.isReady()
 wrapper=mount(App,{global:{plugins:[router],provide:{[SESSION_KEY]:session}},attachTo:document.body});await flushPromises()
 return {session,router,store:deskStore(session)}
}
const row=id=>wrapper.get(`[data-richiesta][data-id="${id}"]`)
async function cerca(stato) {await wrapper.get('#richieste-stato').setValue(stato);await wrapper.get('main form').trigger('submit');await flushPromises()}
async function rifiuta() {await row(24).get('[data-origine="rifiuta-24"]').trigger('click');await row(24).get('[data-conferma="rifiuta"] button').trigger('click');await flushPromises()}
it('parser storico separato dal parser pendente e query con stato validato',async()=>{
 for(const s of STATI_PRENOTAZIONI_UI) expect(parseRichiestaConsultazione(richiesta(24,{stato:s.stato})).stato).toBe(s.stato)
 expect(()=>parseRichiesta(richiesta(24,{stato:'RIFIUTATA'}))).toThrow(ProtocolError)
 expect(()=>richiestePath({stato:'inventato'})).toThrow(TypeError)
 expect(richiestePath({stato:'RIFIUTATA',data:'2026-10-29'})).toBe('/api/prenotazioni/richieste?stato=RIFIUTATA&data=2026-10-29')
 expect(richiestePath()).toBe('/api/prenotazioni/richieste')
 await expect(fetchRichiesteConsultazione(async()=>({status:200,hasBody:true,data:[richiesta(24)]}),{stato:'RIFIUTATA'})).rejects.toBeInstanceOf(ProtocolError)
})
it.each(['CONFERMATA','RIFIUTATA','ANNULLATA','TUTTE'])('filtro %s: lettura server, badge e nessuna operazione su righe gestite',async stato=>{
 const s=stato==='TUTTE'?'EFFETTUATA':stato
 await apri();net.on(statoUrl(stato),jsonResponse(200,[richiesta(24,{stato:s})]));await cerca(stato)
 expect(net.callsTo(statoUrl(stato))).toHaveLength(1)
 expect(row(24).get('[data-stato-richiesta]').text()).not.toBe('')
 expect(row(24).find('[data-origine]').exists()).toBe(false)
 expect(row(24).find('[data-conferma]').exists()).toBe(false)
})
it('rifiuto certo: card rossa trattenuta durante rilettura, nessuna azione e filtro esplicito',async()=>{
 const {router,store}=await apri()
 const h=net.trattieni(statoUrl('TUTTE'))
 net.on(OP(24,'rifiuta'),jsonResponse(200,gestione(24,'RIFIUTATA')))
 await rifiuta()
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(row(24).element.style.getPropertyValue('--agenda-testo')).toBe(stileStatoAgenda('RIFIUTATA')['--agenda-testo'])
 expect(row(24).get('[data-fuori-filtro]').text()).toContain('Non corrisponde più al filtro')
 expect(row(24).find('[data-origine]').exists()).toBe(false)
 expect(router.currentRoute.value.query.evidenzia).toBeDefined()
 expect((await store.decidiRichiesta('conferma',parseRichiestaConsultazione(richiesta(24,{stato:'RIFIUTATA'})))).kind).toBe('non-valida')
 h.resolve(jsonResponse(200,[richiesta(24,{stato:'RIFIUTATA'})]));await flushPromises()
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(net.callsTo(OP(24,'rifiuta'))).toHaveLength(1)
 net.on(RICHIESTE,jsonResponse(200,[]));await cerca('RICHIESTA')
 expect(wrapper.find('[data-richiesta]').exists()).toBe(false)
 expect(router.currentRoute.value.query.evidenzia).toBeUndefined()
})
it('reload con evidenziazione: la rifiutata viene riletta dal server, senza snapshot locale',async()=>{
 net.on(statoUrl('TUTTE'),jsonResponse(200,[richiesta(24,{stato:'RIFIUTATA'}),richiesta(25)]))
 await apri('/desk/richieste?evidenzia=24')
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(row(25).get('[data-origine="conferma-25"]').exists()).toBe(true)
 expect(row(24).find('[data-origine]').exists()).toBe(false)
 expect(net.calls.filter(c=>c.method==='POST')).toHaveLength(0)
})
it('reload del filtro Rifiutate conserva la consultazione persistente',async()=>{
 net.on(statoUrl('RIFIUTATA'),jsonResponse(200,[richiesta(24,{stato:'RIFIUTATA'})]))
 await apri('/desk/richieste?stato=RIFIUTATA')
 expect(wrapper.get('#richieste-stato').element.value).toBe('RIFIUTATA')
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(row(24).find('[data-fuori-filtro]').exists()).toBe(false)
})
it('rilettura fallita dopo rifiuto conserva card certa e recupero manuale',async()=>{
 await apri();net.on(OP(24,'rifiuta'),jsonResponse(200,gestione(24,'RIFIUTATA'))).on(statoUrl('TUTTE'),'rete')
 await rifiuta()
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(wrapper.get('[data-riprova]').exists()).toBe(true)
 expect(row(24).find('[data-origine]').exists()).toBe(false)
 net.on(statoUrl('TUTTE'),jsonResponse(200,[richiesta(24,{stato:'RIFIUTATA'})]))
 await wrapper.get('[data-riprova]').trigger('click');await flushPromises()
 expect(wrapper.find('[data-riprova]').exists()).toBe(false)
 expect(row(24).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
})
it('risposte fuori ordine A → B → A non ripristinano le righe della prima ricerca',async()=>{
 await apri();const h=net.trattieni(statoUrl('RIFIUTATA'));await cerca('RIFIUTATA')
 net.on(statoUrl('CONFERMATA'),jsonResponse(200,[richiesta(25,{stato:'CONFERMATA'})]));await cerca('CONFERMATA')
 net.on(statoUrl('RIFIUTATA'),jsonResponse(200,[richiesta(26,{stato:'RIFIUTATA'})]));await cerca('RIFIUTATA')
 h.resolve(jsonResponse(200,[richiesta(24,{stato:'RIFIUTATA'})]));await flushPromises()
 expect(wrapper.findAll('[data-richiesta]').map(x=>x.attributes('data-id'))).toEqual(['26'])
})
it('scrittura tardiva dopo nuova ricerca non trattiene righe nel nuovo contesto',async()=>{
 await apri();const h=net.trattieni(OP(24,'rifiuta'));await rifiuta()
 net.on(statoUrl('CONFERMATA'),jsonResponse(200,[richiesta(25,{stato:'CONFERMATA'})]));await cerca('CONFERMATA')
 h.resolve(jsonResponse(200,gestione(24,'RIFIUTATA')));await flushPromises()
 expect(wrapper.findAll('[data-richiesta]').map(x=>x.attributes('data-id'))).toEqual(['25'])
 expect(wrapper.find('[data-avviso-desk]').exists()).toBe(false)
 expect(wrapper.find('[data-elenco-invalidato]').exists()).toBe(true)
})
it('storico filtrato non risolve l’assenza da un elenco pendente durante una verifica',async()=>{
 const {store}=await apri();const r=parseRichiesta(richiesta(24))
 net.on(OP(24,'rifiuta'),'rete');expect((await store.decidiRichiesta('rifiuta',r)).kind).toBe('incerto')
 const h=net.trattieni(RICHIESTE);const verifica=store.verifica(chiavePrenotazione(24))
 net.on(statoUrl('RIFIUTATA'),jsonResponse(200,[]));await cerca('RIFIUTATA')
 h.resolve(jsonResponse(200,[richiesta(24)]))
 expect((await verifica).kind).toBe('superata')
 expect(store.bloccata(chiavePrenotazione(24))).toBe(true)
 expect(store.state.incerte[0].esito).toBe(null)
})
it.each([admin(),utente()])('storico desk escluso per altri profili',async user=>{
 const {router}=await apri('/desk/richieste?stato=RIFIUTATA',[],user)
 expect(router.currentRoute.value.name).toBe('accesso-negato')
 expect(net.calls.some(c=>c.url.startsWith('/api/prenotazioni/richieste'))).toBe(false)
})
it('paziente: sette stati con palette condivisa, etichette distinte e legenda',async()=>{
 const rows=STATI_PRENOTAZIONI_UI.map((s,i)=>appuntamento(i+1,{stato:s.stato}))
 net.on('GET /api/prenotazioni/paziente?idPaziente=7',jsonResponse(200,rows)).on('GET /api/rimborsi/paziente',jsonResponse(200,[]))
 for(const r of rows)net.on(`GET /api/prenotazioni/${r.idPrenotazione}/pagamenti`,jsonResponse(200,[])).on(`GET /api/prenotazioni/${r.idPrenotazione}/referto`,jsonResponse(404,{error:'Assente'}))
 await apri('/paziente/prenotazioni',[],utente())
 const cards=wrapper.findAll('[data-prenotazione]')
 expect(cards).toHaveLength(7)
 for(let i=0;i<cards.length;i++){
  expect(cards[i].element.style.getPropertyValue('--agenda-fondo')).toBe(stileStatoAgenda(rows[i].stato)['--agenda-fondo'])
  expect(cards[i].get('.badge-prenotazione').text()).not.toBe('')
 }
 expect(wrapper.get('.legenda-prenotazioni').text()).toContain('In attesa')
 expect(cards[3].get('.badge-prenotazione').text()).toBe('Rifiutata')
 expect(cards[5].get('.badge-prenotazione').text()).toBe('Paziente assente')
})
it('cambio filtri dalla rotta nella stessa istanza chiude conferme e rilegge lo stato corretto',async()=>{
 const {router}=await apri()
 await row(24).get('[data-origine="conferma-24"]').trigger('click')
 net.on(statoUrl('RIFIUTATA'),jsonResponse(200,[richiesta(25,{stato:'RIFIUTATA'})]))
 await router.push('/desk/richieste?stato=RIFIUTATA');await flushPromises()
 expect(wrapper.get('#richieste-stato').element.value).toBe('RIFIUTATA')
 expect(wrapper.find('[data-conferma]').exists()).toBe(false)
 expect(row(25).get('[data-stato-richiesta]').text()).toBe('Rifiutata')
 expect(net.calls.filter(c=>c.method==='POST')).toHaveLength(0)
})
