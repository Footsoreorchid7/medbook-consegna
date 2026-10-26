import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { disponibilitaStore } from '../src/medico/disponibilita.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CREA, ELENCO, ELIMINA, MEDICO, ORARIO, configurato, medico, serie, slot, vuota } from './disponibilita-medico-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const A = '2026-11-02T09:00:00'

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountApp(path, user = medico()) {
  net.on(ME, meOk(user))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  return { session, router, store: disponibilitaStore(session) }
}
const apri = async (voci, user) => {
  net.on(ELENCO, jsonResponse(200, voci))
  return mountApp('/medico/disponibilita?vista=elenco', user)
}
const riga = (id) => wrapper.get(`[data-disponibilita][data-id="${id}"]`)
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const bottone = (testo) => bottoneIn(wrapper.get('main'), testo)
const attivo = () => document.activeElement
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const avviso = () => wrapper.get('[data-avviso-disponibilita]').text()
const intervallo = async (inizio, fine) => {
  await wrapper.get('#disponibilita-data-inizio').setValue(inizio.split('T')[0])
  await wrapper.get('#disponibilita-inizio').setValue(inizio.split('T')[1])
  await wrapper.get('#disponibilita-data-fine').setValue(fine.split('T')[0])
  await wrapper.get('#disponibilita-fine').setValue(fine.split('T')[1])
}
const prosegui = async () => { await wrapper.get('main form').trigger('submit'); await flushPromises() }

describe('accesso ed elenco', () => {
  it('solo con capacità medico; link nell\'area del medico', async () => {
    const { router } = await mountApp('/medico/disponibilita?vista=elenco', utente())
    expect(router.currentRoute.value.name).toBe('accesso-negato')
    expect(net.calls.some((c) => c.url.startsWith('/api/disponibilita'))).toBe(false)
    wrapper.unmount()
    await mountApp('/area')
    expect(wrapper.get('main').find('a[href="/medico/disponibilita"]').exists()).toBe(true)
  })

  it('ordine del backend, orari, configurazione; azioni solo senza configurazione', async () => {
    await apri([
      slot(5, '2026-11-02T09:00:30'),
      configurato(3, '2026-11-02T11:00:00'),
      slot(4, '2026-11-02T12:00:00', { idStanza: 3, nomeStanza: 'Ambulatorio 1' }),
    ])
    expect(net.callsTo(ELENCO)).toHaveLength(1)
    expect(wrapper.findAll('[data-disponibilita]').map((w) => w.attributes('data-id'))).toEqual(['5', '3', '4'])
    expect(riga(5).get('[data-campo="quando"]').text()).toBe('lunedì 02/11/2026, 09:00:30')
    expect(riga(5).get('[data-campo="configurazione"]').text()).toContain('Da configurare')
    expect(riga(3).get('[data-campo="configurazione"]').text()).toBe('Configurata: Visita sintetica, Ambulatorio 1')
    expect(riga(4).get('[data-campo="configurazione"]').text()).toContain('Configurazione parziale')
    expect(bottoneIn(riga(5), 'Modifica orario')).toBeDefined()
    for (const id of [3, 4]) {
      expect(bottoneIn(riga(id), 'Modifica orario')).toBeUndefined()
      expect(riga(id).find('[data-non-modificabile]').exists()).toBe(true)
    }
    // Nessuno stato di occupazione inventato.
    expect(wrapper.text()).not.toMatch(/prenotat[ao]|occupat[ao]|libera/i)
  })

  it('vuoto, errore e Riprova', async () => {
    net.on(ELENCO, 'rete')
    await mountApp('/medico/disponibilita?vista=elenco')
    expect(wrapper.text()).toContain('Non è stato possibile caricare le disponibilità')
    net.on(ELENCO, jsonResponse(200, []))
    await click(bottone('Riprova'))
    expect(wrapper.text()).toContain('Non hai disponibilità registrate.')
  })

  it('lettura fuori ordine dopo un cambio di epoca: scartata', async () => {
    net.on(ME, meOk(medico()))
    const vecchia = net.trattieni(ELENCO)
    const { session } = await mountApp('/medico/disponibilita?vista=elenco')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(ELENCO, jsonResponse(200, [slot(6, A)]))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [slot(9, A)]))
    await flushPromises()
    expect(wrapper.findAll('[data-disponibilita]').map((w) => w.attributes('data-id'))).toEqual(['6'])
  })
})

describe('creazione', () => {
  it('errori locali associati ai campi, focus sul riepilogo, nessuna richiesta', async () => {
    await apri([])
    await intervallo('2026-11-02T09:00', '2026-11-02T10:30')
    await prosegui()
    expect(wrapper.get('#errore-disponibilita-fine').text()).toContain('multiplo esatto di 60 minuti')
    expect(wrapper.get('#disponibilita-fine').attributes('aria-describedby')).toBe('errore-disponibilita-fine')
    expect(attivo().textContent).toContain("Controlla l'intervallo indicato.")
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('conferma con intervallo e numero di slot; Annulla torna a «Prosegui»; successo e rilettura', async () => {
    await apri([])
    await intervallo('2026-11-02T09:00', '2026-11-02T12:00')
    await prosegui()
    const conferma = wrapper.get('[data-conferma-creazione]')
    expect(conferma.text()).toContain('dal lunedì 02/11/2026, 09:00 al lunedì 02/11/2026, 12:00')
    expect(conferma.get('[data-campo="numero-slot"]').text()).toBe('3 slot da 60 minuti')
    expect(attivo().textContent).toBe('Confermi la creazione delle disponibilità?')
    await click(bottoneIn(conferma, 'Annulla'))
    expect(attivo()).toBe(bottone('Prosegui').element)
    await prosegui()
    net.on(CREA, jsonResponse(201, serie(20, A, 3)))
    const rilettura = net.trattieni(ELENCO)
    await click(bottone('Crea disponibilità'))
    expect(JSON.parse(net.callsTo(CREA)[0].body)).toEqual({ idMedico: MEDICO, dataOraInizio: A, dataOraFine: '2026-11-02T12:00:00' })
    expect(avviso()).toContain('Create 3 slot da 60 minuti')
    expect(attivo()).not.toBe(wrapper.get('[data-avviso-disponibilita]').element)
    expect(wrapper.get('[data-avviso-disponibilita] [aria-label="Chiudi avviso"]').exists()).toBe(true)
    expect(wrapper.get('#disponibilita-inizio').element.value).toBe('')
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(true)
    rilettura.resolve(jsonResponse(200, serie(20, A, 3)))
    await flushPromises()
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(3)
  })

  it('400 del server: messaggio mostrato, nessuna creazione dichiarata, rilettura', async () => {
    await apri([])
    await intervallo('2026-11-02T09:00', '2026-11-02T10:00')
    await prosegui()
    net.on(CREA, jsonResponse(400, { error: "L'inizio della disponibilità deve essere futuro." })).on(ELENCO, jsonResponse(200, []))
    await click(bottone('Crea disponibilità'))
    expect(avviso()).toContain("L'inizio della disponibilità deve essere futuro.")
    expect(avviso()).toContain('Nessuna disponibilità è stata creata')
    expect(net.callsTo(ELENCO)).toHaveLength(2)
  })

  it('esito incerto: blocco anche dopo uscita e rientro, verifica manuale parziale non conclusiva', async () => {
    const { router } = await apri([slot(20, A)])
    await intervallo('2026-11-02T09:00', '2026-11-02T12:00')
    await prosegui()
    net.on(CREA, 'rete')
    await click(bottone('Crea disponibilità'))
    expect(avviso()).toContain('Non è stato possibile stabilire se le disponibilità siano state create')
    await router.push('/area')
    await flushPromises()
    net.on(ELENCO, jsonResponse(200, [slot(20, A)]))
    await router.push('/medico/disponibilita?vista=elenco')
    await flushPromises()
    await intervallo('2026-11-02T10:00', '2026-11-02T11:00')
    await prosegui()
    expect(avviso()).toContain('intervallo sovrapposto')
    expect(wrapper.find('[data-conferma-creazione]').exists()).toBe(false)
    net.on(ELENCO, jsonResponse(200, serie(20, A, 3)), jsonResponse(200, serie(20, A, 3)))
    await click(bottoneIn(wrapper.get('[data-creazione-incerta]'), "Verifica nell'elenco"))
    // Lo slot 20 era già noto prima del tentativo: il riscontro non è conclusivo.
    expect(avviso()).toContain('Riscontro parziale')
    expect(wrapper.get('[data-creazione-incerta]').text()).toContain('riscontro parziale, non conclusivo')
    expect(net.callsTo(CREA)).toHaveLength(1)
  })
})

describe('modifica dell\'orario', () => {
  it('modulo con focus e fine calcolata; conferma con orari originale e nuovo; Annulla al pulsante d\'origine', async () => {
    await apri([slot(7, A)])
    const b = bottoneIn(riga(7), 'Modifica orario')
    await click(b)
    const campo = wrapper.get('#orario-7')
    expect(attivo()).toBe(campo.element)
    // Valore iniziale: l'orario attuale (il campo omette i secondi nulli).
    expect(campo.element.value).toBe('2026-11-02T09:00')
    await campo.setValue('2026-11-03T08:00')
    expect(riga(7).text()).toContain('Nuova fine: martedì 03/11/2026, 09:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    expect(riga(7).get('[data-campo="originale"]').text()).toBe('dal lunedì 02/11/2026, 09:00 al lunedì 02/11/2026, 10:00')
    expect(riga(7).get('[data-campo="nuovo"]').text()).toBe('dal martedì 03/11/2026, 08:00 al martedì 03/11/2026, 09:00')
    expect(attivo().textContent).toBe('Confermi il nuovo orario?')
    await click(bottoneIn(riga(7), 'Annulla'))
    expect(attivo()).toBe(bottoneIn(riga(7), 'Modifica orario').element)
    expect(net.calls.filter((c) => c.method === 'PUT')).toHaveLength(0)
  })

  it('orario invariato segnalato sul campo; 409 con messaggio del server e rilettura', async () => {
    await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await click(bottoneIn(riga(7), 'Prosegui'))
    expect(riga(7).get('#errore-orario-7').text()).toContain('coincide')
    expect(attivo()).toBe(wrapper.get('#orario-7').element)
    await wrapper.get('#orario-7').setValue('2026-11-03T08:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    net.on(ORARIO(7), jsonResponse(409, { error: 'Disponibilità con prenotazioni associate.' })).on(ELENCO, jsonResponse(200, [slot(7, A)]))
    await click(bottoneIn(riga(7), 'Conferma nuovo orario'))
    expect(avviso()).toContain('Disponibilità con prenotazioni associate.')
    expect(net.callsTo(ELENCO)).toHaveLength(2)
    expect(net.callsTo(ORARIO(7))).toHaveLength(1)
  })

  it('successo: messaggio con il nuovo orario e rilettura', async () => {
    await apri([slot(7, A)])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await wrapper.get('#orario-7').setValue('2026-11-03T08:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    net.on(ORARIO(7), jsonResponse(200, slot(7, '2026-11-03T08:00:00'))).on(ELENCO, jsonResponse(200, [slot(7, '2026-11-03T08:00:00')]))
    await click(bottoneIn(riga(7), 'Conferma nuovo orario'))
    expect(avviso()).toContain('ora va dal martedì 03/11/2026, 08:00')
    expect(riga(7).get('[data-campo="quando"]').text()).toBe('martedì 03/11/2026, 08:00')
  })
})

describe('eliminazione', () => {
  it('conferma con riepilogo; 204 e rilettura', async () => {
    await apri([slot(7, A), slot(8, '2026-11-02T10:00:00')])
    await click(bottoneIn(riga(7), 'Elimina'))
    expect(riga(7).get('[data-conferma="elimina"]').text()).toContain('Disponibilità n. 7: dal lunedì 02/11/2026, 09:00')
    expect(attivo().textContent).toBe("Confermi l'eliminazione della disponibilità?")
    net.on(ELIMINA(7), vuota()).on(ELENCO, jsonResponse(200, [slot(8, '2026-11-02T10:00:00')]))
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    expect(net.callsTo(ELIMINA(7))[0].headers.get('X-CSRF-Token')).toBeTruthy()
    expect(avviso()).toContain('eliminata')
    expect(wrapper.findAll('[data-disponibilita]').map((w) => w.attributes('data-id'))).toEqual(['8'])
  })

  it('esito incerto: operazioni sullo slot bloccate anche dopo uscita e rientro; verifica senza fallimento certo', async () => {
    const { router } = await apri([slot(7, A), slot(8, '2026-11-02T10:00:00')])
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), 'rete')
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    expect(bottoneIn(riga(7), 'Elimina')).toBeUndefined()
    expect(bottoneIn(riga(8), 'Elimina')).toBeDefined()
    await router.push('/area')
    await flushPromises()
    net.on(ELENCO, jsonResponse(200, [slot(7, A), slot(8, '2026-11-02T10:00:00')]))
    await router.push('/medico/disponibilita?vista=elenco')
    await flushPromises()
    expect(bottoneIn(riga(7), 'Modifica orario')).toBeUndefined()
    net.on(ELENCO, jsonResponse(200, [slot(8, '2026-11-02T10:00:00')]), jsonResponse(200, [slot(8, '2026-11-02T10:00:00')]))
    await click(bottoneIn(wrapper.get('[data-slot-incerto]'), "Verifica nell'elenco"))
    expect(avviso()).toContain('non risulta più nell\'elenco. Questo non prova che sia stata eliminata dal tuo tentativo')
    expect(net.callsTo(ELIMINA(7))).toHaveLength(1)
  })
})

describe('contesto, riletture e focus', () => {
  it('una conferma aperta si chiude quando parte una rilettura', async () => {
    await apri([slot(7, A), slot(8, '2026-11-02T10:00:00')])
    await click(bottoneIn(riga(7), 'Elimina'))
    net.on(ELIMINA(7), 'rete')
    await click(bottoneIn(riga(7), 'Elimina disponibilità'))
    await click(bottoneIn(riga(8), 'Elimina'))
    expect(riga(8).find('[data-conferma="elimina"]').exists()).toBe(true)
    net.on(ELENCO, jsonResponse(200, [slot(7, A), slot(8, '2026-11-02T10:00:00')]))
    const rilettura = net.trattieni(ELENCO)
    await click(bottoneIn(wrapper.get('[data-slot-incerto]'), "Verifica nell'elenco"))
    expect(riga(8).find('[data-conferma="elimina"]').exists()).toBe(false)
    expect(bottoneIn(riga(8), 'Elimina').attributes('disabled')).toBeDefined()
    rilettura.resolve(jsonResponse(200, [slot(7, A), slot(8, '2026-11-02T10:00:00')]))
    await flushPromises()
    expect(bottoneIn(riga(8), 'Elimina').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(ELIMINA(8))).toHaveLength(0)
  })

  it('cambio di epoca durante una scrittura: nessun messaggio, focus o reset nel nuovo contesto; esito nello store', async () => {
    const { session, store } = await apri([slot(7, A)])
    await intervallo('2026-11-02T09:00', '2026-11-02T10:00')
    await prosegui()
    const h = net.trattieni(CREA)
    await click(bottone('Crea disponibilità'))
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(ELENCO, jsonResponse(200, [slot(7, A)]))
    await session.changePassword('a', 'b')
    await flushPromises()
    await wrapper.get('#disponibilita-data-inizio').setValue('2026-11-05')
    await wrapper.get('#disponibilita-inizio').setValue('09:00')
    const focus = attivo()
    h.resolve(jsonResponse(201, serie(20, A, 1)))
    await flushPromises()
    expect(wrapper.find('[data-avviso-disponibilita]').exists()).toBe(false)
    expect(attivo()).toBe(focus)
    expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('2026-11-05')
    expect(wrapper.get('#disponibilita-inizio').element.value).toBe('09:00')
    expect(net.callsTo(ELENCO)).toHaveLength(2)
    // Risposta arrivata dopo il cambio di epoca: scartata, quindi esito incerto registrato dallo store (regola ).
    expect(store.state.creazioniIncerte).toHaveLength(1)
    expect(wrapper.find('[data-creazione-incerta]').exists()).toBe(true)
  })

  it('uscita dalla pagina durante una creazione riuscita: nessuna rilettura né messaggio dalla vista smontata', async () => {
    const { router, store } = await apri([])
    await intervallo('2026-11-02T09:00', '2026-11-02T10:00')
    await prosegui()
    const h = net.trattieni(CREA)
    await click(bottone('Crea disponibilità'))
    await router.push('/area')
    await flushPromises()
    h.resolve(jsonResponse(201, serie(20, A, 1)))
    await flushPromises()
    expect(net.callsTo(ELENCO)).toHaveLength(1)
    expect(store.state.creazioniIncerte).toEqual([])
    net.on(ELENCO, jsonResponse(200, serie(20, A, 1)))
    await router.push('/medico/disponibilita?vista=elenco')
    await flushPromises()
    expect(wrapper.find('[data-avviso-disponibilita]').exists()).toBe(false)
    expect(wrapper.findAll('[data-disponibilita]')).toHaveLength(1)
  })

  it('password incerta: azioni disabilitate con indicazione', async () => {
    net.on(PWD, 'rete')
    const { session } = await apri([slot(7, A)])
    await session.changePassword('a', 'b')
    await flushPromises()
    expect(bottoneIn(riga(7), 'Elimina').attributes('disabled')).toBeDefined()
    expect(bottone('Prosegui').attributes('disabled')).toBeDefined()
    expect(wrapper.get('main').find('a[href="/area/password"]').exists()).toBe(true)
  })
})


describe('ingresso dall’agenda e precisione al minuto', () => {
  it('data valida precompilata, ore ancora da scegliere, nessuna scrittura automatica', async () => {
    net.on(ELENCO, jsonResponse(200, []))
    await mountApp('/medico/disponibilita?vista=elenco&data=2026-11-05')
    expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('2026-11-05')
    expect(wrapper.get('#disponibilita-data-fine').element.value).toBe('2026-11-05')
    expect(wrapper.get('#disponibilita-inizio').element.value).toBe('')
    expect(wrapper.get('#disponibilita-fine').element.value).toBe('')
    expect(wrapper.get('#disponibilita-inizio').attributes('step')).toBe('60')
    await prosegui()
    expect(wrapper.get('#errore-disponibilita-inizio').exists()).toBe(true)
    expect(net.callsTo(CREA)).toHaveLength(0)
    await wrapper.get('#disponibilita-inizio').setValue('09:15')
    await wrapper.get('#disponibilita-fine').setValue('10:15')
    await prosegui()
    expect(wrapper.get('[data-conferma-creazione]').text()).toContain('09:15')
    expect(net.callsTo(CREA)).toHaveLength(0)
    net.on(CREA, jsonResponse(201, serie(20, '2026-11-05T09:15:00', 1)))
    net.on(ELENCO, jsonResponse(200, []))
    await click(bottone('Crea disponibilità'))
    expect(JSON.parse(net.callsTo(CREA)[0].body)).toEqual({ idMedico: MEDICO, dataOraInizio: '2026-11-05T09:15:00', dataOraFine: '2026-11-05T10:15:00' })
  })

  it.each(['2026-02-30', '0999-01-01', '2026-11-05T09:00', '2026-11-05&data=2026-11-06'])('data non valida o ripetuta rifiutata: %s', async (data) => {
    net.on(ELENCO, jsonResponse(200, []))
    await mountApp('/medico/disponibilita?vista=elenco&data=' + data)
    expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('')
    expect(wrapper.text()).toContain('La data nel collegamento non è valida')
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('secondi storici conservati senza modifica esplicita; variazione visibile prima della PUT', async () => {
    const originale = '2026-11-02T09:00:30'
    await apri([slot(7, originale)])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    const campo = wrapper.get('#orario-7')
    expect(campo.element.value).toBe('2026-11-02T09:00')
    expect(campo.attributes('step')).toBe('60')
    expect(riga(7).get('[data-secondi-storici]').text()).toContain('09:00:30')
    expect(riga(7).text()).toContain('Nuova fine: lunedì 02/11/2026, 10:00:30')
    await click(bottoneIn(riga(7), 'Prosegui'))
    expect(riga(7).get('#errore-orario-7').text()).toContain('coincide')
    expect(net.callsTo(ORARIO(7))).toHaveLength(0)
    await campo.setValue('2026-11-02T09:01')
    await campo.setValue('2026-11-02T09:00')
    await click(bottoneIn(riga(7), 'Prosegui'))
    expect(riga(7).get('[data-campo="originale"]').text()).toContain('09:00:30')
    expect(riga(7).get('[data-variazione-secondi]').text()).toContain('da 30 a 00')
    expect(net.callsTo(ORARIO(7))).toHaveLength(0)
    net.on(ORARIO(7), jsonResponse(200, slot(7, '2026-11-02T09:00:00')))
    net.on(ELENCO, jsonResponse(200, [slot(7, '2026-11-02T09:00:00')]))
    await click(bottoneIn(riga(7), 'Conferma nuovo orario'))
    expect(JSON.parse(net.callsTo(ORARIO(7))[0].body)).toEqual({ idMedico: MEDICO, dataOraInizio: '2026-11-02T09:00:00', dataOraFine: '2026-11-02T10:00:00' })
  })

  it('annullare il modulo non modifica i secondi storici', async () => {
    await apri([slot(7, '2026-11-02T09:00:30')])
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await wrapper.get('#orario-7').setValue('2026-11-02T10:00')
    await click(bottoneIn(riga(7), 'Annulla'))
    await click(bottoneIn(riga(7), 'Modifica orario'))
    await click(bottoneIn(riga(7), 'Prosegui'))
    expect(riga(7).get('#errore-orario-7').text()).toContain('coincide')
    expect(net.callsTo(ORARIO(7))).toHaveLength(0)
    expect(riga(7).get('[data-campo="quando"]').text()).toContain('09:00:30')
  })
})


it('cambio data di ingresso invalida la vecchia conferma e preserva il nuovo modulo', async () => {
  net.on(ELENCO, jsonResponse(200, []))
  const { router } = await mountApp('/medico/disponibilita?vista=elenco&data=2026-11-05')
  await wrapper.get('#disponibilita-inizio').setValue('09:00')
  await wrapper.get('#disponibilita-fine').setValue('10:00')
  await prosegui()
  const pendente = net.trattieni(CREA)
  await click(bottone('Crea disponibilità'))
  net.on(ELENCO, jsonResponse(200, []))
  await router.push('/medico/disponibilita?vista=elenco&data=2026-11-06')
  await flushPromises()
  expect(wrapper.find('[data-conferma-creazione]').exists()).toBe(false)
  expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('2026-11-06')
  await wrapper.get('#disponibilita-inizio').setValue('14:00')
  pendente.resolve(jsonResponse(201, serie(20, '2026-11-05T09:00:00', 1)))
  await flushPromises()
  expect(wrapper.get('#disponibilita-data-inizio').element.value).toBe('2026-11-06')
  expect(wrapper.get('#disponibilita-inizio').element.value).toBe('14:00')
  expect(wrapper.find('[data-avviso-disponibilita]').exists()).toBe(false)
  expect(net.callsTo(CREA)).toHaveLength(1)
  expect(net.callsTo(ELENCO)).toHaveLength(2)
})

it('nuovi orari con secondi non nulli non vengono troncati né inviati', async () => {
  await apri([])
  await intervallo('2026-11-05T09:00:30', '2026-11-05T10:00:30')
  await prosegui()
  expect(wrapper.find('[data-conferma-creazione]').exists()).toBe(false)
  expect(wrapper.get('#errore-disponibilita-inizio').exists()).toBe(true)
  expect(net.callsTo(CREA)).toHaveLength(0)
})
