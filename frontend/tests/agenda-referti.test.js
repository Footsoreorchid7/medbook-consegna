import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import RefertoCaricamento from '../src/components/RefertoCaricamento.vue'
import AgendaView from '../src/views/AgendaView.vue'
import { parseAppuntamentoAgenda } from '../src/api/medico.js'
import { STATI_AGENDA_UI, stileStatoAgenda } from '../src/medico/statiAgenda.js'
import { todayLocal } from '../src/utils/datetime.js'
import { periodoMese } from '../src/medico/calendario.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { medicoStore } from '../src/medico/store.js'
import { createAppRouter } from '../src/router/index.js'
import { CSRF_B, installFetch, routedFetch, meOk, jsonResponse } from './helpers.js'
import { MEDICO, REFERTO, effettuata, medico, pdf, caricato, agendaPeriodo } from './b5-fixtures.js'
let net, wrapper, session
beforeEach(async () => {
 net=installFetch(routedFetch()); net.on('GET /api/auth/me',meOk(medico()))
 session=createSession(); await session.ensureBootstrap()
})
afterEach(()=>wrapper?.unmount())
const a=(id)=>parseAppuntamentoAgenda(effettuata(id),MEDICO)
function monta(id=1) {
 wrapper=mount(RefertoCaricamento,{props:{appuntamento:a(id),abilitato:true},global:{provide:{[SESSION_KEY]:session}},attachTo:document.body})
}
const button=(text)=>wrapper.findAll('button').find(b=>b.text().startsWith(text))
async function prepara() {
 await button('Carica referto').trigger('click')
 const input=wrapper.get('input[type=file]')
 Object.defineProperty(input.element,'files',{value:[pdf()],configurable:true})
 await input.trigger('change')
 // Il pulsante submit deve attraversare il gestore del form.
 await wrapper.get('.referto-medico form').trigger('submit'); await flushPromises()
}
async function invia(response) {
 net.on(REFERTO(1),response); await prepara()
 await button('Carica il referto').trigger('click'); await flushPromises()
}
it('201 validato: indicatore persistente immediato, modulo chiuso, file rilasciato e nessun nuovo invio',async()=>{
 monta(); await invia(jsonResponse(201,caricato(1)))
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(wrapper.find('form').exists()).toBe(false)
 expect(wrapper.find('[data-conferma-referto]').exists()).toBe(false)
 expect(button('Carica referto')).toBeUndefined()
 expect(wrapper.vm.$.setupState.file).toBe(null)
 await wrapper.get('[aria-label="Chiudi avviso"]').trigger('click'); await flushPromises()
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(await medicoStore(session).caricaReferto(a(1),pdf())).toEqual({kind:'bloccata'})
 expect(net.callsTo(REFERTO(1))).toHaveLength(1)
})
it('nuova istanza dopo navigazione conserva il riscontro, senza file nello store',async()=>{
 monta(); await invia(jsonResponse(201,caricato(1))); wrapper.unmount(); monta()
 expect(button('Carica referto')).toBeUndefined()
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(medicoStore(session).state.refertiCaricati[0]).toEqual({idPrenotazione:1,referto:expect.objectContaining({idReferto:9})})
 expect(a(1).stato).toBe('EFFETTUATA')
})
it('400 non è successo: file pulito, errore e nuova scelta manuale',async()=>{
 monta(); await invia(jsonResponse(400,{error:'Documento non valido'}))
 expect(wrapper.find('[data-referto-caricato]').exists()).toBe(false)
 expect(wrapper.get('[data-avviso-referto]').text()).toContain('rifiutato')
 expect(button('Carica referto')).toBeDefined()
 expect(wrapper.vm.$.setupState.file).toBe(null)
})
for(const [caso,response] of [['rete','rete'],['201 non conforme',()=>jsonResponse(201,caricato(99))]]) it(`${caso}: incerto distinto dal successo e blocco in nuova istanza`,async()=>{
 monta(); await invia(typeof response==='function'?response():response)
 expect(wrapper.find('[data-referto-caricato]').exists()).toBe(false)
 expect(button('Carica referto')).toBeUndefined()
 expect(wrapper.vm.$.setupState.file).toBe(null)
 wrapper.unmount(); monta()
 expect(wrapper.get('[data-referto-bloccato]').text()).toContain('da verificare')
 expect(button('Carica referto')).toBeUndefined()
})
it('successo tardivo dopo smontaggio non contamina altra risorsa né sposta focus',async()=>{
 monta(); const h=net.trattieni(REFERTO(1)); await prepara(); await button('Carica il referto').trigger('click'); await flushPromises()
 wrapper.unmount(); monta(2); button('Carica referto').element.focus(); const focus=document.activeElement
 h.resolve(jsonResponse(201,caricato(1))); await flushPromises()
 expect(wrapper.find('[data-avviso-referto]').exists()).toBe(false)
 expect(wrapper.find('[data-referto-caricato]').exists()).toBe(false)
 expect(document.activeElement).toBe(focus)
 expect(button('Carica referto')).toBeDefined()
 wrapper.unmount(); monta(1)
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
})
it('card, indicatori e legenda condividono gli stessi colori, con badge testuale',async()=>{
 const mese=todayLocal().slice(0,7), periodo=periodoMese(mese), date=`${mese}-19`
 const rows=STATI_AGENDA_UI.filter(s=>s.stato!=='ANNULLATA').map((s,i)=>effettuata(i+1,{stato:s.stato,idMedicoEffettivo:s.stato==='RICHIESTA'?null:MEDICO,dataOraInizio:`${date}T09:00:00`,dataOraFine:`${date}T10:00:00`}))
 net.on(agendaPeriodo(periodo.dataDa,periodo.dataA),jsonResponse(200,rows))
 const router=createAppRouter({session,history:createMemoryHistory()}); await router.push('/medico/agenda')
 wrapper=mount(AgendaView,{global:{plugins:[router],provide:{[SESSION_KEY]:session}}}); await flushPromises()
 await wrapper.get(`[data-giorno="${date}"]`).trigger('click')
 for(const [i,s] of STATI_AGENDA_UI.filter(s=>s.stato!=='ANNULLATA').entries()) {
  const card=wrapper.get(`[data-appuntamento][data-id="${i+1}"]`), legenda=wrapper.get(`.legenda .stato-${s.colore}`), indicatore=wrapper.get(`[data-giorno="${date}"] .stato-${s.colore}`)
  for(const el of [card,legenda,indicatore]) expect(el.element.style.getPropertyValue('--agenda-fondo')).toBe(stileStatoAgenda(s.stato)['--agenda-fondo'])
  expect(card.get('.badge-agenda').text()).toContain(s.nome)
 }
})
it('agenda: successo conserva Effettuata e indicatore dopo giorno vuoto e ritorno',async()=>{
 const mese=todayLocal().slice(0,7),periodo=periodoMese(mese),date=`${mese}-19`
 net.on(agendaPeriodo(periodo.dataDa,periodo.dataA),jsonResponse(200,[effettuata(1,{dataOraInizio:`${date}T09:00:00`,dataOraFine:`${date}T10:00:00`})]))
 const router=createAppRouter({session,history:createMemoryHistory()}); await router.push('/medico/agenda')
 wrapper=mount(AgendaView,{global:{plugins:[router],provide:{[SESSION_KEY]:session}},attachTo:document.body}); await flushPromises()
 await wrapper.get(`[data-giorno="${date}"]`).trigger('click')
 await invia(jsonResponse(201,caricato(1)))
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(wrapper.get('[data-campo="stato"]').text()).toBe('Effettuata')
 await wrapper.get(`[data-giorno="${mese}-20"]`).trigger('click')
 expect(wrapper.find('[data-appuntamento]').exists()).toBe(false)
 await wrapper.get(`[data-giorno="${date}"]`).trigger('click')
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(button('Carica referto')).toBeUndefined()
 expect(net.callsTo(REFERTO(1))).toHaveLength(1)
})

it('rotazione durante upload rimonta la card senza messaggi o focus del vecchio contesto',async()=>{
 const mese=todayLocal().slice(0,7),periodo=periodoMese(mese),date=`${mese}-19`
 const rows=[effettuata(1,{dataOraInizio:`${date}T09:00:00`,dataOraFine:`${date}T10:00:00`})]
 const path=agendaPeriodo(periodo.dataDa,periodo.dataA)
 net.on(path,jsonResponse(200,rows))
 const router=createAppRouter({session,history:createMemoryHistory()}); await router.push('/medico/agenda')
 wrapper=mount(AgendaView,{global:{plugins:[router],provide:{[SESSION_KEY]:session}},attachTo:document.body}); await flushPromises()
 await wrapper.get(`[data-giorno="${date}"]`).trigger('click')
 const h=net.trattieni(REFERTO(1)); await prepara(); await button('Carica il referto').trigger('click'); await flushPromises()
 net.on('PUT /api/auth/password',jsonResponse(200,{message:'ok',nuovoAccessoRichiesto:false},{'X-CSRF-Token':CSRF_B}))
 net.on(path,jsonResponse(200,rows))
 expect((await session.changePassword('vecchia','nuova')).kind).toBe('aggiornata')
 await flushPromises()
 wrapper.get('[aria-label="Mese successivo"]').element.focus(); const focus=document.activeElement
 h.resolve(jsonResponse(201,caricato(1))); await flushPromises()
 expect(wrapper.find('[data-avviso-referto]').exists()).toBe(false)
 expect(wrapper.find('[data-referto-caricato]').exists()).toBe(false)
 expect(document.activeElement).toBe(focus)
 expect(medicoStore(session).refertoCaricato(1)).toBe(null)
})
