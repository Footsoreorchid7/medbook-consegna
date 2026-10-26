import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { parseMedicoAdmin } from '../src/api/medici.js'
import { parseUtenteAmministrativo } from '../src/api/utenti.js'
import { adminStore } from '../src/admin/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import {
  ABILITA, CATALOGO, COLLEGA, CREA_MEDICO, MEDICI, MEDICO, PASSWORD, RICERCA, admin, amministrativo, dettaglioAdmin, medicoAdmin, pagina, prestazione,
} from './admin-medici-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Identità condivisa utenti/medici, input durante rilettura e paginazione del collegamento.

const ME = 'GET /api/auth/me'
const K = 'email:nina@example.test'
const qEmail = RICERCA('email=nina%40example.test&limite=50')
const m = (id, extra) => parseMedicoAdmin(medicoAdmin(id, extra))
const u = (id, extra) => parseUtenteAmministrativo(amministrativo(id, extra))
const pag = (righe, prossimo = null) => ({ risultati: righe, prossimoDopoId: prossimo })
const NUOVO = Object.freeze({ nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-9' })

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function avvia() {
  net.on(ME, meOk(admin()))
  const session = createSession()
  await session.ensureBootstrap()
  return adminStore(session)
}
/** Creazione account gestione utenti incerta e prima fase della verifica: la ricerca trova l'utente 20 con l'email del tentativo. */
async function verificaAccountA(store) {
  net.on('POST /api/utenti', 'rete')
  await store.crea({ nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', ruolo: 'OPERATORE_DESK' }, PASSWORD)
  net.on(qEmail, jsonResponse(200, pagina([amministrativo(20, { email: 'nina@example.test' })])))
  const l = await store.leggiVerifica(K)
  expect(l).toMatchObject({ kind: 'letta', conclusiva: true })
  return l
}

describe('identità condivisa fra gestione utenti e medici', () => {
  it('verifica account A, lettura medico B più recente con nuova email, poi esito A: nessuna presenza conclusiva, blocco mantenuto', async () => {
    const store = await avvia()
    const l = await verificaAccountA(store)
    store.registraDettaglio({ medico: m(20, { email: 'altra@example.test' }), prestazioni: [] }, store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toEqual({ kind: 'incoerente' })
    expect(store.bloccata(K)).toBe(true)
    // Recupero manuale: nuova ricerca esaurita senza l'email.
    net.on(qEmail, jsonResponse(200, pagina([])))
    expect(await store.verifica(K)).toMatchObject({ kind: 'verificata', esito: 'assente' })
  })

  it('anche una voce dell\'elenco medici più recente smentisce; risposta A tardiva non ricrea il collegamento', async () => {
    const store = await avvia()
    net.on('POST /api/utenti', 'rete')
    await store.crea({ nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', ruolo: 'OPERATORE_DESK' }, PASSWORD)
    const tA = store.inizioLettura()
    store.registraMedici([m(20, { email: 'altra@example.test' })], store.inizioLettura(), { stato: 'ATTIVO' })
    store.registraRicerca({ email: 'nina@' }, 0, pag([u(20, { email: 'nina@example.test' }), u(21, { email: 'nina@other.test' })], 21), tA)
    expect(store.esitoVerifica(K, { kind: 'letta', ticket: tA, conclusiva: true })).toEqual({ kind: 'superata' })
  })

  it('casing equivalente nella lettura medico: nessuna falsa invalidazione', async () => {
    const store = await avvia()
    const l = await verificaAccountA(store)
    store.registraDettaglio({ medico: m(20, { email: 'NINA@Example.TEST' }), prestazioni: [] }, store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 20 } })
  })

  it('assenza del profilo medico (404 o elenco senza l\'ID) non è assenza dell\'account né smentita', async () => {
    const store = await avvia()
    const l = await verificaAccountA(store)
    store.registraAssenzaMedico(20, store.inizioLettura())
    store.registraMedici([], store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toMatchObject({ esito: 'presente', voce: { idUtente: 20, codiceFiscale: null } })
  })

  it('verifica della creazione medico: ricerca account e dettaglio medico incoerenti → esito neutro; coerenti con casing diverso → presente', async () => {
    const store = await avvia()
    net.on(CREA_MEDICO, 'rete')
    await store.creaMedico(NUOVO, PASSWORD)
    net.on(qEmail, jsonResponse(200, pagina([amministrativo(22, { email: 'nina@example.test' })])))
    net.on(MEDICO(22), jsonResponse(200, dettaglioAdmin(22, [], { email: 'cambiata@example.test' })))
    expect(await store.verifica(K)).toEqual({ kind: 'incoerente' })
    expect(store.bloccata(K)).toBe(true)
    net.on(qEmail, jsonResponse(200, pagina([amministrativo(22, { email: 'nina@example.test' })])))
    net.on(MEDICO(22), jsonResponse(200, dettaglioAdmin(22, [], { nome: 'Nina', cognome: 'Medica', email: 'NINA@EXAMPLE.TEST', numeroIscrizioneOrdine: 'ORD-9' })))
    expect(await store.verifica(K)).toMatchObject({ kind: 'verificata', esito: 'come-richiesto', voce: { idUtente: 22 } })
    expect(net.callsTo(CREA_MEDICO)).toHaveLength(1)
  })
})

// ---------------------------------------------------------------- viste

async function mountApp(path) {
  net.on(ME, meOk(admin()))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { router, store: adminStore(session) }
}
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const valore = (sel) => wrapper.get(sel).element.value

describe('dettaglio: input conservati durante la rilettura', () => {
  async function rilettura(store, risposta) {
    // Esito certo da un'altra istanza: il dettaglio va riletto; la GET resta trattenuta.
    net.on(ABILITA(5), jsonResponse(200, { idMedico: 5, idPrestazione: 3, operazione: 'ASSEGNA' }))
    await store.abilitazione(m(5), 'ASSEGNA', prestazione(3))
    await flushPromises()
    const h = net.trattieni(MEDICO(5))
    await click(bottoneIn(wrapper.get('[data-dettaglio-invalidato]'), 'Aggiorna il dettaglio'))
    return () => { h.resolve(risposta); return flushPromises() }
  }

  it('campi modificati durante la GET restano; campi invariati si aggiornano; conferma non mescola versioni', async () => {
    net.on(CATALOGO, jsonResponse(200, [])).on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5)))
    const { store } = await mountApp('/admin/medici/5')
    const rilascia = await rilettura(store, jsonResponse(200, dettaglioAdmin(5, [], { cognome: 'Rinominata', specializzazione: 'Neurologia', numeroIscrizioneOrdine: 'ORD-55' })))
    await wrapper.get('#personali-nome').setValue('Elisa')
    await wrapper.get('#professionali-specializzazione').setValue('Pediatria')
    await rilascia()
    expect([valore('#personali-nome'), valore('#personali-cognome'), valore('#personali-email')]).toEqual(['Elisa', 'Rinominata', 'medico5@example.test'])
    expect([valore('#professionali-specializzazione'), valore('#professionali-numeroIscrizioneOrdine')]).toEqual(['Pediatria', 'ORD-55'])
    expect(wrapper.find('[data-modificato="nome"]').exists()).toBe(true)
    // La specializzazione è cambiata sul server durante la modifica: niente conferma silenziosa.
    await wrapper.get('form[novalidate]:has(#professionali-specializzazione)').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-conferma="professionali"]').exists()).toBe(false)
    expect(wrapper.get('[data-errori="professionali"]').text()).toContain('cambiato durante la modifica (ora «Neurologia»)')
    await wrapper.get('form[novalidate]:has(#professionali-specializzazione)').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-conferma="professionali"] [data-campo="transizione"]').text()).toBe('Da «Neurologia, ORD-55» a «Pediatria, ORD-55».')
    // Il nome modificato partiva dal valore ancora corrente: conferma diretta, cognome riletto.
    await click(bottoneIn(wrapper.get('[data-conferma="professionali"]'), 'Indietro'))
    await wrapper.get('form[novalidate]:has(#personali-nome)').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-conferma="personali"] [data-campo="transizione"]').text()).toBe('Da «Elena Rinominata, medico5@example.test» a «Elisa Rinominata, medico5@example.test».')
  })

  it('cambio medico e A → B → A: moduli del nuovo contesto, nessun input o risposta del medico precedente ripristinati', async () => {
    net.on(CATALOGO, jsonResponse(200, [])).on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5)))
    const { router } = await mountApp('/admin/medici/5')
    await wrapper.get('#personali-nome').setValue('Modificato')
    const vecchia = net.trattieni(MEDICO(6))
    await router.push('/admin/medici/6')
    await flushPromises()
    net.on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5, [], { nome: 'Elena' })))
    await router.push('/admin/medici/5')
    await flushPromises()
    vecchia.resolve?.(jsonResponse(200, dettaglioAdmin(6, [], { nome: 'Sesto' })))
    await flushPromises()
    expect(valore('#personali-nome')).toBe('Elena')
    expect(wrapper.find('[data-modificato]').exists()).toBe(false)
  })
})

describe('collegamento: paginazione della ricerca', () => {
  const pieno = (base) => Array.from({ length: 20 }, (_, i) => amministrativo(base + i + 1, { ruoloGestionale: null }))
  async function cerca(cognome) {
    if (!wrapper.find('#collega-cognome').exists()) await click(wrapper.get('[data-apri-collegamento]'))
    await wrapper.get('#collega-cognome').setValue(cognome)
    await wrapper.get('form[novalidate]:has(#collega-cognome)').trigger('submit')
    await flushPromises()
  }
  const apri = () => {
    net.on(CATALOGO, jsonResponse(200, [])).on(MEDICI, jsonResponse(200, []))
    return mountApp('/admin/medici')
  }

  it('utente della seconda pagina selezionabile e collegabile; fine della paginazione; nessun totale', async () => {
    await apri()
    net.on(RICERCA('cognome=Prova&limite=20'), jsonResponse(200, pagina(pieno(0), 20)))
    await cerca('Prova')
    expect(wrapper.get('[data-esito-ricerca-utenti]').text()).toBe('20 utenti mostrati, altri disponibili.')
    net.on(RICERCA('cognome=Prova&dopoId=20&limite=20'), jsonResponse(200, pagina([amministrativo(50, { ruoloGestionale: null })])))
    await click(bottoneIn(wrapper, 'Mostra altri utenti'))
    expect(wrapper.findAll('[data-utente-trovato]')).toHaveLength(21)
    expect(bottoneIn(wrapper, 'Mostra altri utenti')).toBeUndefined()
    expect(wrapper.get('[data-esito-ricerca-utenti]').text()).toBe('21 utenti mostrati.')
    await click(bottoneIn(wrapper.get('[data-utente-trovato][data-id="50"]'), 'Scegli'))
    await wrapper.get('#collega-specializzazione').setValue('S')
    await wrapper.get('#collega-numero').setValue('O')
    await click(bottoneIn(wrapper.get('[data-utente-scelto]'), 'Prosegui'))
    net.on(COLLEGA(50), jsonResponse(201, medicoAdmin(50, { nome: 'Nome', cognome: 'Prova50', specializzazione: 'S', numeroIscrizioneOrdine: 'O' }))).on(MEDICI, jsonResponse(200, []))
    await click(bottoneIn(wrapper.get('[data-conferma="collega"]'), 'Collega profilo medico'))
    expect(net.callsTo(COLLEGA(50))).toHaveLength(1)
  })

  it('cambio filtri durante una pagina trattenuta: la pagina tardiva non si aggiunge alla nuova ricerca', async () => {
    await apri()
    net.on(RICERCA('cognome=Prova&limite=20'), jsonResponse(200, pagina(pieno(0), 20)))
    await cerca('Prova')
    const tardiva = net.trattieni(RICERCA('cognome=Prova&dopoId=20&limite=20'))
    await click(bottoneIn(wrapper, 'Mostra altri utenti'))
    net.on(RICERCA('cognome=Bianchi&limite=20'), jsonResponse(200, pagina([amministrativo(70, { ruoloGestionale: null })])))
    await cerca('Bianchi')
    tardiva.resolve(jsonResponse(200, pagina([amministrativo(21, { ruoloGestionale: null })])))
    await flushPromises()
    expect(wrapper.findAll('[data-utente-trovato]').map((w) => w.attributes('data-id'))).toEqual(['70'])
  })

  it('esito certo durante la paginazione e la conferma: pagina scartata, ricerca non estendibile, conferma chiusa', async () => {
    const { store } = await apri()
    net.on(RICERCA('cognome=Prova&limite=20'), jsonResponse(200, pagina(pieno(0), 20)))
    await cerca('Prova')
    await click(bottoneIn(wrapper.get('[data-utente-trovato][data-id="3"]'), 'Scegli'))
    await wrapper.get('#collega-specializzazione').setValue('S')
    await wrapper.get('#collega-numero').setValue('O')
    await click(bottoneIn(wrapper.get('[data-utente-scelto]'), 'Prosegui'))
    expect(wrapper.find('[data-conferma="collega"]').exists()).toBe(true)
    const h = net.trattieni(RICERCA('cognome=Prova&dopoId=20&limite=20'))
    await click(bottoneIn(wrapper, 'Mostra altri utenti'))
    net.on('PUT /api/utenti/99/stato', jsonResponse(200, amministrativo(99, { statoAccesso: 'DISABILITATO' })))
    await store.cambiaStato(u(99), 'DISABILITATO')
    h.resolve(jsonResponse(200, pagina([amministrativo(21, { ruoloGestionale: null })])))
    await flushPromises()
    expect(wrapper.find('[data-conferma="collega"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-utente-trovato]')).toHaveLength(20)
    expect(wrapper.find('[data-ricerca-utenti-invalidata]').exists()).toBe(true)
    expect(bottoneIn(wrapper, 'Mostra altri utenti').attributes('disabled')).toBeDefined()
    expect(bottoneIn(wrapper.get('[data-utente-trovato][data-id="4"]'), 'Scegli').attributes('disabled')).toBeDefined()
    expect(net.calls.some((c) => c.url.includes('/collegamento'))).toBe(false)
    // Recupero manuale: la ripetizione riparte dalla prima pagina con i filtri applicati.
    net.on(RICERCA('cognome=Prova&limite=20'), jsonResponse(200, pagina(pieno(0), 20)))
    await click(bottoneIn(wrapper.get('[data-ricerca-utenti-invalidata]'), 'Ripeti la ricerca'))
    expect(bottoneIn(wrapper.get('[data-utente-trovato][data-id="4"]'), 'Scegli').attributes('disabled')).toBeUndefined()
  })
})
