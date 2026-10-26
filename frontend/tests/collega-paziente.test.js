import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { adminStore } from '../src/admin/store.js'
import { fetchMedicoDesk } from '../src/api/desk.js'
import { postCollegamentoPaziente } from '../src/api/pazientiDesk.js'
import { ProtocolError } from '../src/api/http.js'
import { dettaglio, desk, admin, medicoDesk, prestazione } from './desk-fixtures.js'
import { installFetch, routedFetch, meOk, jsonResponse, utente, CSRF_B } from './helpers.js'
const GET = 'GET /api/medici/5', POST = 'POST /api/pazienti/5/collegamento'
const dati = { codiceFiscale: 'tsttst90a01a000q', dataNascita: '1990-01-01', telefono: '' }
const detail = (id=5, presente=false) => jsonResponse(200,{ ...dettaglio(id), paziente: presente })
const ok = () => jsonResponse(201,{ idPaziente:5,paziente:true,message:'Collegato' })
let net,wrapper
beforeEach(()=>{net=installFetch(routedFetch())})
afterEach(()=>{wrapper?.unmount();wrapper=null;vi.restoreAllMocks()})
async function avvia(user=desk(), path='/desk/medici/5') {
  net.on('GET /api/auth/me',meOk(user));const session=createSession(),router=createAppRouter({session,history:createMemoryHistory()})
  await router.push(path);await router.isReady()
  wrapper=mount(App,{attachTo:document.body,global:{plugins:[router],provide:{[SESSION_KEY]:session}}});await flushPromises()
  return {session,router,store:adminStore(session)}
}
const btn = text=>wrapper.findAll('button').find(b=>b.text()===text)
async function prepara() {
  await btn('Aggiungi profilo paziente').trigger('click');await flushPromises()
  expect(document.activeElement.id).toBe('profilo-cf')
  await wrapper.get('#profilo-cf').setValue(dati.codiceFiscale);await wrapper.get('#profilo-nascita').setValue(dati.dataNascita)
  await wrapper.get('.collega-paziente form').trigger('submit');await flushPromises()
}
async function sessione() {const s=createSession();net.on('GET /api/auth/me',meOk(desk()));await s.ensureBootstrap();return s}
const posts=()=>net.callsTo(POST)
function registra(store,id=5,presente=false) {const t=store.inizioLettura();expect(store.registraProfiloPaziente(id,presente,t)).toBe(true);return t}

describe('contratti dedicati',()=>{
  it('flag obbligatorio nel solo dettaglio desk, senza inventare informazioni del paziente',async()=>{
    const s=await sessione();net.on(GET,detail());expect((await fetchMedicoDesk(s.protectedRequest,5)).paziente).toBe(false)
    for(const value of [undefined,null,'false',0]) {
      net.on(GET,jsonResponse(200,{...dettaglio(5),paziente:value}));await expect(fetchMedicoDesk(s.protectedRequest,5)).rejects.toBeInstanceOf(ProtocolError)
    }
  })
  it.each([200,202,204])('status %s non è successo certo di creazione',async status=>{
    const s=await sessione();net.on(POST,status===204?new Response(null,{status}):jsonResponse(status,{idPaziente:5,paziente:true,message:'ok'}))
    await expect(postCollegamentoPaziente(s.protectedRequest,5,dati)).rejects.toBeInstanceOf(ProtocolError)
  })
  it.each([{idPaziente:6,paziente:true,message:'ok'},{idPaziente:5,paziente:false,message:'ok'},{idPaziente:5,paziente:true,message:''}])('rifiuta risposta incoerente %j',async body=>{
    const s=await sessione();net.on(POST,jsonResponse(201,body));await expect(postCollegamentoPaziente(s.protectedRequest,5,dati)).rejects.toBeInstanceOf(ProtocolError)
  })
})

describe('pagina e contesto',()=>{
  it('permessi: admin e paziente esclusi senza chiamate al medico o collegamento',async()=>{
    for(const u of [admin(),utente()]){const {router}=await avvia(u);expect(router.currentRoute.value.name).toBe('accesso-negato');wrapper.unmount();wrapper=null}
    expect(net.callsTo(GET)).toHaveLength(0);expect(posts()).toHaveLength(0)
  })
  it('conferma esplicita, identità e snapshot esatti; successo nasconde il modulo e blocca altri invii',async()=>{
    net.on(GET,detail());const held=net.trattieni(POST);const {store}=await avvia();await prepara()
    expect(wrapper.get('.confirm').text()).toContain('utente n. 5');expect(posts()).toHaveLength(0)
    net.on(GET,detail(5,true));await btn('Conferma aggiunta profilo').trigger('click');await flushPromises()
    expect(store.bloccata('utente:5')).toBe(true);expect(posts()).toHaveLength(1)
    expect(wrapper.get('#profilo-cf').attributes('disabled')).toBeDefined()
    expect(JSON.parse(posts()[0].body)).toEqual({codiceFiscale:dati.codiceFiscale,dataNascita:dati.dataNascita})
    held.resolve(ok());await flushPromises()
    expect(wrapper.text()).toContain('Profilo paziente aggiunto allo stesso account');expect(wrapper.get('[data-campo="paziente"]').text()).toBe('Presente')
    expect(btn('Aggiungi profilo paziente')).toBeUndefined();expect(wrapper.find('#profilo-cf').exists()).toBe(false)
  })
  it('profilo già presente: nessuna proposta né POST, reset desk vietato nell’informazione',async()=>{
    net.on(GET,detail(5,true));await avvia();expect(btn('Aggiungi profilo paziente')).toBeUndefined();expect(wrapper.text()).toContain('non può reimpostarne la password');expect(posts()).toHaveLength(0)
  })
  it('validazione locale, chiusura e ritorno del focus, nessun invio automatico',async()=>{
    net.on(GET,detail());await avvia();await btn('Aggiungi profilo paziente').trigger('click');await wrapper.get('.collega-paziente form').trigger('submit');await flushPromises()
    expect(wrapper.text()).toContain('codice fiscale deve');expect(posts()).toHaveLength(0)
    await btn('Chiudi').trigger('click');await flushPromises();expect(document.activeElement.textContent).toBe('Aggiungi profilo paziente')
    await btn('Aggiungi profilo paziente').trigger('click');expect(wrapper.get('#profilo-cf').element.value).toBe('')
  })
  it('409 rilegge una volta, non ripete la POST e conserva la spiegazione senza sovrascritture',async()=>{
    net.on(GET,detail()).on(POST,jsonResponse(409,{error:'Codice fiscale già utilizzato. Nessun dato sovrascritto.'}));await avvia();await prepara()
    net.on(GET,detail());await btn('Conferma aggiunta profilo').trigger('click');await flushPromises()
    expect(wrapper.text()).toContain('Codice fiscale già utilizzato');expect(posts()).toHaveLength(1);expect(net.callsTo(GET)).toHaveLength(2)
  })
  it('esito incerto sopravvive alla navigazione, verifica manuale non attribuisce il collegamento',async()=>{
    net.on(GET,detail()).on(POST,'rete');const {router,store}=await avvia();await prepara();await btn('Conferma aggiunta profilo').trigger('click');await flushPromises()
    expect(store.bloccata('utente:5')).toBe(true)
    net.on('GET /api/prestazioni',jsonResponse(200,[prestazione(2)])).on('GET /api/medici',jsonResponse(200,[medicoDesk(5)]));await router.push('/desk/medici');await flushPromises()
    expect(wrapper.text()).toContain('Collegamento paziente dall’esito incerto')
    net.on(GET,detail());await router.push('/desk/medici/5');await flushPromises()
    net.on(GET,detail(5,true),detail(5,true));await btn('Verifica profilo paziente').trigger('click');await flushPromises()
    expect(wrapper.text()).toContain('non prova chi');expect(store.bloccata('utente:5')).toBe(false);expect(posts()).toHaveLength(1)
  })
  it('assenza resta non conclusiva; nuovo tentativo richiede consenso, lettura e nuova compilazione',async()=>{
    net.on(GET,detail()).on(POST,'rete');const {store}=await avvia();await prepara();await btn('Conferma aggiunta profilo').trigger('click');await flushPromises()
    net.on(GET,detail(),detail());await btn('Verifica profilo paziente').trigger('click');await flushPromises()
    expect(wrapper.text()).toContain('non è una prova');expect(store.bloccata('utente:5')).toBe(true)
    vi.spyOn(window,'confirm').mockReturnValue(true);net.on(GET,detail());await btn('Consenti un nuovo collegamento').trigger('click');await flushPromises()
    expect(store.bloccata('utente:5')).toBe(false);expect(posts()).toHaveLength(1)
    await btn('Aggiungi profilo paziente').trigger('click');expect(wrapper.get('#profilo-cf').element.value).toBe('')
  })
  it('smontaggio durante invio conserva il blocco e rende recuperabile il vecchio esito dalla lista',async()=>{
    net.on(GET,detail());const held=net.trattieni(POST);const {router,store}=await avvia();await prepara();await btn('Conferma aggiunta profilo').trigger('click')
    net.on('GET /api/prestazioni',jsonResponse(200,[prestazione(2)])).on('GET /api/medici',jsonResponse(200,[]));await router.push('/desk/medici');await flushPromises()
    held.resolve(jsonResponse(503,{error:'tecnico'}));await flushPromises()
    expect(store.bloccata('utente:5')).toBe(true);expect(wrapper.text()).toContain('Collegamento paziente dall’esito incerto');expect(net.callsTo(GET)).toHaveLength(1)
    expect(wrapper.find('a[href="/desk/medici/5"]').exists()).toBe(true)
  })
  it('A → B → A nella stessa istanza non applica gli effetti del primo tentativo',async()=>{
    net.on(GET,detail());const held=net.trattieni(POST);const {router}=await avvia();await prepara();await btn('Conferma aggiunta profilo').trigger('click')
    const uid=wrapper.findComponent({name:'DeskMedicoDettaglioView'}).vm.$.uid
    net.on('GET /api/medici/6',detail(6));await router.push('/desk/medici/6');await flushPromises()
    net.on(GET,detail());await router.push('/desk/medici/5');await flushPromises()
    expect(wrapper.findComponent({name:'DeskMedicoDettaglioView'}).vm.$.uid).toBe(uid)
    held.resolve(ok());await flushPromises();expect(wrapper.text()).not.toContain('Profilo paziente aggiunto allo stesso account');expect(net.callsTo(GET)).toHaveLength(2)
    expect(btn('Aggiungi profilo paziente')).toBeUndefined()
    expect(btn('Aggiorna il dettaglio')).toBeDefined()
  })
})

describe('store condiviso, ticket e sessione',()=>{
  it('pending e incertezza bloccano anche chiamate dirette e altri aggiornamenti dello stesso account',async()=>{
    const s=await sessione(),store=adminStore(s),t=registra(store);const held=net.trattieni(POST)
    const op=store.collegaPaziente(medicoDesk(5),dati,t)
    expect((await store.collegaPaziente(medicoDesk(5),dati,t)).kind).toBe('occupato')
    expect((await store.reimpostaPasswordPaziente({idPaziente:5,medico:false,gestionale:false},'password fittizia')).kind).toBe('occupato')
    held.resolve(jsonResponse(503,{error:'Errore'},{'Retry-After':'10'}));expect((await op).kind).toBe('incerto');expect(store.bloccata('utente:5')).toBe(true)
    expect(store.attesaResidua(Date.now()+20000)).toBe(0);expect(store.bloccata('utente:5')).toBe(true)
    expect(JSON.stringify(store.state)).not.toContain(dati.codiceFiscale)
  })
  it.each([[true,false],[false,true]])('vecchia risposta %s ignorata rispetto a nuova %s',async(oldValue,newValue)=>{
    const s=await sessione(),store=adminStore(s);const a=store.inizioLettura(),b=store.inizioLettura()
    expect(store.registraProfiloPaziente(5,newValue,b)).toBe(true);expect(store.registraProfiloPaziente(5,oldValue,a)).toBe(false)
    expect(store.profiloPazienteCorrente(5).presente).toBe(newValue)
  })
  it('successo invalida subito ticket precedenti, anche di una nuova istanza caricata durante pending',async()=>{
    const s=await sessione(),store=adminStore(s),a=registra(store);const held=net.trattieni(POST)
    const op=store.collegaPaziente(medicoDesk(5),dati,a),b=registra(store)
    held.resolve(ok());expect((await op).kind).toBe('eseguita')
    expect(store.profiloPazienteValido(5,b)).toBe(false)
    expect((await store.collegaPaziente(medicoDesk(5),dati,b)).kind).toBe('non-aggiornata');expect(posts()).toHaveLength(1)
  })
  it.each([true,false])('verifica vecchia usa soltanto il riscontro più recente (%s)',async recente=>{
    const s=await sessione(),store=adminStore(s),t=registra(store);net.on(POST,'rete');await store.collegaPaziente(medicoDesk(5),dati,t)
    const held=net.trattieni(GET),op=store.verificaCollegamentoPaziente(5);await flushPromises()
    registra(store,5,recente);held.resolve(detail(5,!recente));const r=await op
    expect(r.kind).toBe(recente?'profilo-presente':'profilo-assente');expect(store.bloccata('utente:5')).toBe(!recente)
  })
  it('errore verifica mantiene blocco; rotazione rende la verifica obsoleta recuperabile',async()=>{
    const s=await sessione(),store=adminStore(s),t=registra(store);net.on(POST,'rete');await store.collegaPaziente(medicoDesk(5),dati,t)
    net.on(GET,jsonResponse(500,{error:'tecnico'}));expect((await store.verificaCollegamentoPaziente(5)).kind).toBe('errore');expect(store.bloccata('utente:5')).toBe(true)
    const held=net.trattieni(GET),op=store.verificaCollegamentoPaziente(5);await flushPromises()
    net.on('PUT /api/auth/password',jsonResponse(200,{message:'ok',nuovoAccessoRichiesto:false},{'X-CSRF-Token':CSRF_B}));expect((await s.changePassword('vecchia','nuova')).kind).toBe('aggiornata')
    held.resolve(detail(5,true));expect((await op).kind).toBe('obsoleto');expect(store.incerta('utente:5').verifica).toBe('errore')
    net.on(GET,detail(5,true));expect((await store.verificaCollegamentoPaziente(5)).kind).toBe('profilo-presente')
  })
  it('password incerta e logout bloccano fetch/adozione; nessuna incertezza trasferita a una nuova identità',async()=>{
    const s=await sessione(),store=adminStore(s),t=registra(store);net.on('PUT /api/auth/password','rete');await s.changePassword('vecchia','nuova')
    expect((await store.collegaPaziente(medicoDesk(5),dati,t)).kind).toBe('password-incerta');expect(posts()).toHaveLength(0)
    net.on('POST /api/auth/logout',new Response(null,{status:204}));await s.logout();expect(store.state.owner).toBe(null);expect(store.profiloPazienteCorrente(5)).toBe(null)
  })
})
