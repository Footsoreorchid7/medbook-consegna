import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import { parseAppuntamentoAgenda } from '../src/api/medico.js'
import RefertoCaricamento from '../src/components/RefertoCaricamento.vue'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { medicoStore } from '../src/medico/store.js'
import { installFetch, routedFetch, meOk, jsonResponse } from './helpers.js'
import { MEDICO, REFERTO, effettuata, medico, pdf } from './b5-fixtures.js'
let net,session,wrapper
const a=(refertoPresente)=>parseAppuntamentoAgenda(effettuata(12,{refertoPresente}),MEDICO)
beforeEach(async()=>{
 net=installFetch(routedFetch());net.on('GET /api/auth/me',meOk(medico()))
 session=createSession();await session.ensureBootstrap()
})
afterEach(()=>wrapper?.unmount())
function monta(presente) {
 wrapper=mount(RefertoCaricamento,{props:{appuntamento:a(presente),abilitato:true},global:{provide:{[SESSION_KEY]:session}},attachTo:document.body})
}
it('parser richiede un booleano esplicito: dato mancante non diventa assenza',()=>{
 for(const flag of [undefined,null,0,1,'true','false'])expect(()=>a(flag)).toThrow(ProtocolError)
 expect(a(true).refertoPresente).toBe(true)
 expect(a(false).refertoPresente).toBe(false)
})
it('agenda con referto presente, store vuoto: indicatore senza data inventata e nessun upload',async()=>{
 monta(true)
 expect(medicoStore(session).refertoCaricato(12)).toBe(null)
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(wrapper.get('[data-referto-caricato]').text()).not.toContain('in questa sessione')
 expect(wrapper.find('button').exists()).toBe(false)
 expect(await medicoStore(session).caricaReferto(a(true),pdf())).toEqual({kind:'bloccata'})
 expect(net.callsTo(REFERTO(12))).toHaveLength(0)
 expect(a(true).stato).toBe('EFFETTUATA')
})
it('nuova sessione dopo reload/accesso: riscontro server basta senza storage frontend',async()=>{
 monta(true);wrapper.unmount()
 net.on('GET /api/auth/me',meOk(medico()))
 session=createSession();await session.ensureBootstrap();monta(true)
 expect(medicoStore(session).state.refertiCaricati).toEqual([])
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(wrapper.find('button').exists()).toBe(false)
})
it('assenza esplicita consente la scelta; presenza sopraggiunta chiude modulo e pulisce file',async()=>{
 monta(false)
 await wrapper.get('button').trigger('click')
 const input=wrapper.get('input[type=file]')
 Object.defineProperty(input.element,'files',{value:[pdf()],configurable:true});await input.trigger('change')
 await wrapper.setProps({appuntamento:a(true)});await flushPromises()
 expect(wrapper.vm.$.setupState.file).toBe(null)
 expect(wrapper.find('form').exists()).toBe(false)
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(net.callsTo(REFERTO(12))).toHaveLength(0)
})
it('409 generico non diventa presenza del referto',async()=>{
 net.on(REFERTO(12),jsonResponse(409,{error:'Conflitto'}))
 expect((await medicoStore(session).caricaReferto(a(false),pdf())).kind).toBe('conflitto')
 monta(false)
 expect(wrapper.find('[data-referto-caricato]').exists()).toBe(false)
 expect(wrapper.find('button').exists()).toBe(true)
})
it('presenza letta non attribuisce un documento al precedente upload incerto',async()=>{
 net.on(REFERTO(12),'rete')
 expect((await medicoStore(session).caricaReferto(a(false),pdf())).kind).toBe('incerto')
 monta(true)
 expect(wrapper.get('[data-referto-caricato]').text()).toContain('Referto caricato')
 expect(wrapper.get('[data-referto-bloccato]').text()).toContain('da verificare')
 expect(medicoStore(session).refertoIncerto(12)).not.toBe(null)
 expect(wrapper.find('button').exists()).toBe(false)
})
