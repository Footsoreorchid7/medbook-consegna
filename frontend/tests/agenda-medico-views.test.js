import { todayLocal } from '../src/utils/datetime.js'
import { periodoMese } from '../src/medico/calendario.js'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import { medicoStore } from '../src/medico/store.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import {
  AGENDA, MEDICO, REFERTO, SEGNALA, VISITA, agendaGiorno, agendaPeriodo, caricato, effettuata, medico, pdf, registrata, richiesta, voce,
} from './agenda-medico-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'

let net
let wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountApp(path, user = medico()) {
  net.on(ME, meOk(user))
  const iniziale = periodoMese(todayLocal().slice(0, 7))
  net.on(agendaPeriodo(iniziale.dataDa, iniziale.dataA), jsonResponse(200, []))
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  router.push(path)
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.matched.length).toBeGreaterThan(0))
  await flushPromises()
  // Mantiene il contesto giornaliero esplicito dei casi ; la nuova apertura mensile ha test propri.
  const filtriAgenda = wrapper.find('main .filtri-avanzati form')
  if (filtriAgenda.exists()) { await filtriAgenda.trigger('submit'); await flushPromises() }
  // Questi casi esercitano la modalità multipla preesistente, ora esplicita.
  const multipla = wrapper.find('main input[type="checkbox"]')
  if (multipla.exists()) await multipla.setValue(true)
  return { session, router, store: medicoStore(session) }
}

const apriAgenda = async (voci, user) => {
  net.on(AGENDA, jsonResponse(200, voci))
  return mountApp('/medico/agenda', user)
}
const riga = (id) => wrapper.get(`[data-appuntamento][data-id="${id}"]`)
const bottoneIn = (w, testo) => w.findAll('button').find((b) => b.text().startsWith(testo))
const bottone = (testo) => bottoneIn(wrapper.get('main'), testo)
const attivo = () => document.activeElement
const click = async (b) => { await b.trigger('click'); await flushPromises() }
const avviso = () => wrapper.get('[data-avviso-agenda]').text()
const scegliFile = async (w, file) => {
  const input = w.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: file ? [file] : [], configurable: true })
  await input.trigger('change')
}

describe('accesso e navigazione', () => {
  it('solo con capacità medico; il paziente riceve accesso negato', async () => {
    const { router } = await mountApp('/medico/agenda', utente())
    expect(router.currentRoute.value.name).toBe('accesso-negato')
    expect(net.calls.some((c) => c.url.startsWith('/api/prenotazioni/agenda'))).toBe(false)
  })

  it('link «Agenda» nell\'area del medico, assente per il solo paziente', async () => {
    await mountApp('/area')
    expect(wrapper.get('main').find('a[href="/medico/agenda"]').exists()).toBe(true)
    wrapper.unmount()
    await mountApp('/area', utente())
    expect(wrapper.get('main').find('a[href="/medico/agenda"]').exists()).toBe(false)
  })
})

describe('lettura dell\'agenda', () => {
  it('default: nessuna data (giornata corrente del server), medicoId dalla sessione, ordine del backend e campi', async () => {
    await apriAgenda([
      voce(3, { indisponibilitaSegnalata: true }),
      richiesta(1, { idPrestazione: null, nomePrestazione: null, durataPrestazione: null, idStanza: null, nomeStanza: null }),
      effettuata(2),
    ])
    expect(net.callsTo(AGENDA)).toHaveLength(1)
    expect(wrapper.findAll('[data-appuntamento]').map((w) => w.attributes('data-id'))).toEqual(['3', '1', '2'])
    const r3 = riga(3)
    expect(r3.get('h3').text()).toBe('Paola Prova3')
    expect(r3.get('[data-campo="prestazione"]').text()).toBe('Visita sintetica (60 min)')
    expect(r3.get('[data-campo="stanza"]').text()).toBe('Ambulatorio 1')
    expect(r3.get('[data-campo="quando"]').text()).toBe('lunedì 19/10/2026, 09:00–10:00')
    expect(r3.get('[data-campo="indisponibilita"]').text()).toContain('Indisponibilità segnalata')
    expect(riga(1).get('[data-campo="prestazione"]').text()).toBe('Prestazione non disponibile')
    expect(riga(1).get('[data-campo="stanza"]').text()).toBe('Non assegnata')
    expect(riga(1).get('[data-campo="stato"]').text()).toBe('Richiesta, in attesa di conferma della segreteria')
    expect(wrapper.text()).toContain('giornata corrente secondo il server')
  })

  it('vuoto, errore e Riprova', async () => {
    net.on(AGENDA, 'rete')
    await mountApp('/medico/agenda')
    expect(wrapper.text()).toContain("Non è stato possibile caricare l'agenda")
    net.on(AGENDA, jsonResponse(200, []))
    await click(bottone('Riprova'))
    expect(wrapper.text()).toContain('Nessun appuntamento nel periodo scelto.')
  })

  it('filtri: data singola, intervallo di 31 giorni inclusi; oltre il limite nessuna richiesta e focus sull\'errore', async () => {
    await apriAgenda([])
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#agenda-data').setValue('2026-10-20')
    net.on(agendaGiorno('2026-10-20'), jsonResponse(200, [voce(5)]))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('giornata del 20/10/2026')
    await wrapper.get('input[value="intervallo"]').setValue()
    await wrapper.get('#agenda-dal').setValue('2026-10-01')
    await wrapper.get('#agenda-al').setValue('2026-11-01')
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain("L'intervallo può comprendere al massimo 31 giorni.")
    expect(attivo().textContent).toContain('Controlla il periodo indicato.')
    await wrapper.get('#agenda-al').setValue('2026-10-31')
    net.on(agendaPeriodo('2026-10-01', '2026-10-31'), jsonResponse(200, []))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(net.calls.filter((c) => c.url.includes('dataDa'))).toHaveLength(2) // mese iniziale e intervallo esplicito
    expect(wrapper.text()).toContain('dal 01/10/2026 al 31/10/2026')
  })

  it('risposta fuori ordine scartata: vale l\'ultima ricerca', async () => {
    net.on(ME, meOk(medico()))
    const vecchia = net.trattieni(AGENDA)
    await mountApp('/medico/agenda')
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#agenda-data').setValue('2026-10-20')
    net.on(agendaGiorno('2026-10-20'), jsonResponse(200, [voce(5)]))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    vecchia.resolve(jsonResponse(200, [voce(9)]))
    await flushPromises()
    expect(wrapper.findAll('[data-appuntamento]').map((w) => w.attributes('data-id'))).toEqual(['5'])
  })

  it('cambio di epoca (rotazione): lettura precedente annullata, dati azzerati e riletti', async () => {
    net.on(ME, meOk(medico()))
    const vecchia = net.trattieni(AGENDA)
    const { session } = await mountApp('/medico/agenda')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    net.on(AGENDA, jsonResponse(200, [voce(6)]))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    await flushPromises()
    expect(vecchia.aborted).toBe(true)
    expect(wrapper.findAll('[data-appuntamento]').map((w) => w.attributes('data-id'))).toEqual(['6'])
  })
})

describe('registrazione della visita', () => {
  it('proposta solo per CONFERMATA del medico effettivo senza segnalazione', async () => {
    await apriAgenda([voce(1), voce(2, { indisponibilitaSegnalata: true }), richiesta(3), effettuata(4)])
    expect(bottoneIn(riga(1), 'Registra visita')).toBeDefined()
    for (const id of [2, 3, 4]) expect(bottoneIn(riga(id), 'Registra visita')).toBeUndefined()
  })

  it('conferma con riepilogo; Annulla torna al pulsante; Conferma invia {} e rilegge', async () => {
    await apriAgenda([voce(1)])
    const b = bottoneIn(riga(1), 'Registra visita')
    b.element.focus()
    await click(b)
    const conferma = riga(1).get('[data-conferma-visita]')
    expect(conferma.text()).toContain('Paola Prova1')
    expect(conferma.text()).toContain('lunedì 19/10/2026, 09:00–10:00')
    expect(attivo().textContent).toBe('Confermi la registrazione della visita?')
    await click(bottoneIn(conferma, 'Annulla'))
    expect(attivo()).toBe(bottoneIn(riga(1), 'Registra visita').element)
    expect(net.callsTo(VISITA(1))).toHaveLength(0)

    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), jsonResponse(200, registrata(1)))
    const rilettura = net.trattieni(AGENDA)
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    expect(JSON.parse(net.callsTo(VISITA(1))[0].body)).toEqual({})
    expect(avviso()).toContain('Visita registrata')
    expect(attivo()).not.toBe(wrapper.get('[data-avviso-agenda]').element)
    expect(wrapper.get('[data-avviso-agenda] [aria-label="Chiudi avviso"]').exists()).toBe(true)
    // Durante la rilettura i dati restano visibili ma le azioni sono sospese.
    expect(wrapper.find('[data-aggiornamento]').exists()).toBe(true)
    expect(bottoneIn(riga(1), 'Registra visita')).toBeUndefined()
    rilettura.resolve(jsonResponse(200, [effettuata(1)]))
    await flushPromises()
    expect(riga(1).get('[data-campo="stato"]').text()).toBe('Effettuata')
    expect(bottoneIn(riga(1), 'Carica referto')).toBeDefined()
    expect(net.callsTo(VISITA(1))).toHaveLength(1)
  })

  it('409: messaggio e rilettura, nessuna nuova scrittura', async () => {
    await apriAgenda([voce(1)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), jsonResponse(409, { error: 'Lo slot non è ancora terminato.' })).on(AGENDA, jsonResponse(200, [voce(1)]))
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    expect(avviso()).toContain('Visita non registrata')
    expect(net.callsTo(AGENDA)).toHaveLength(2)
    expect(net.callsTo(VISITA(1))).toHaveLength(1)
  })

  it('esito incerto: blocco del reinvio, verifica manuale nell\'agenda della giornata', async () => {
    const { router } = await apriAgenda([voce(1)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), 'rete')
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    expect(avviso()).toContain('Non è stato possibile stabilire se la visita')
    expect(bottoneIn(riga(1), 'Registra visita')).toBeUndefined()
    expect(wrapper.get('[data-visita-incerta]').text()).toContain('prenotazione n. 1')
    // Uscendo e rientrando il blocco resta.
    await router.push('/area')
    await flushPromises()
    net.on(AGENDA, jsonResponse(200, [voce(1)]))
    const meseRientro = periodoMese(todayLocal().slice(0, 7))
    net.on(agendaPeriodo(meseRientro.dataDa, meseRientro.dataA), jsonResponse(200, []))
    await router.push('/medico/agenda')
    await flushPromises()
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(bottoneIn(riga(1), 'Registra visita')).toBeUndefined()
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [effettuata(1)])).on(AGENDA, jsonResponse(200, [effettuata(1)]))
    await click(bottoneIn(wrapper.get('[data-visita-incerta]'), "Verifica nell'agenda"))
    expect(avviso()).toContain('risulta «Effettuata»')
    expect(avviso()).toContain('non dimostra')
    expect(wrapper.find('[data-visita-incerta]').exists()).toBe(false)
    expect(net.callsTo(VISITA(1))).toHaveLength(1)
  })

  it('cambio di periodo durante la scrittura: nessun messaggio, focus o rilettura nel nuovo contesto; lo store conserva l\'esito', async () => {
    const { store } = await apriAgenda([voce(1)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    const h = net.trattieni(VISITA(1))
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#agenda-data').setValue('2026-10-20')
    net.on(agendaGiorno('2026-10-20'), jsonResponse(200, [voce(5)]))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    const focus = attivo()
    h.resolve(jsonResponse(200, registrata(1)))
    await flushPromises()
    expect(wrapper.find('[data-avviso-agenda]').exists()).toBe(false)
    expect(attivo()).toBe(focus)
    // Nessuna rilettura avviata dal contesto precedente, né del vecchio né del nuovo periodo.
    expect(net.callsTo(AGENDA)).toHaveLength(1)
    expect(net.callsTo(agendaGiorno('2026-10-20'))).toHaveLength(1)
    expect(wrapper.findAll('[data-appuntamento]').map((w) => w.attributes('data-id'))).toEqual(['5'])
    expect(store.visitaRegistrata(1)).toBe(true)
  })

  it('password incerta: azioni disabilitate con indicazione', async () => {
    net.on(PWD, 'rete')
    const { session } = await apriAgenda([voce(1)])
    await session.changePassword('a', 'b')
    await flushPromises()
    expect(bottoneIn(riga(1), 'Registra visita').attributes('disabled')).toBeDefined()
    expect(wrapper.get('main').find('a[href="/area/password"]').exists()).toBe(true)
  })
})

describe('segnalazione di indisponibilità', () => {
  const casella = (id) => riga(id).find('input[type="checkbox"]')

  it('selezionabili solo RICHIESTA del titolare e CONFERMATA dell\'effettivo non segnalate; spiegazione del perimetro', async () => {
    await apriAgenda([richiesta(1), voce(2), voce(3, { indisponibilitaSegnalata: true }), effettuata(4)])
    expect(casella(1).exists() && casella(2).exists()).toBe(true)
    expect(casella(3).exists() || casella(4).exists()).toBe(false)
    expect(wrapper.get('[data-pannello-segnalazione]').text()).toContain('non crea un periodo di assenza e non blocca gli orari liberi')
  })

  it('selezione multipla, conferma con elenco, corpo con medicoId della sessione; successo e rilettura', async () => {
    await apriAgenda([richiesta(1), voce(2), voce(3)])
    expect(bottone('Segnala indisponibilità').attributes('disabled')).toBeDefined()
    await casella(1).setValue(true)
    await casella(2).setValue(true)
    await casella(2).setValue(false)
    await casella(2).setValue(true)
    const b = bottone('Segnala indisponibilità (2)')
    b.element.focus()
    await click(b)
    const conferma = wrapper.get('[data-conferma-segnalazione]')
    expect(conferma.findAll('li').map((l) => l.text().slice(0, 18))).toEqual(['Prenotazione n. 1 ', 'Prenotazione n. 2 '])
    expect(conferma.text()).toContain('nessuno viene segnalato')
    await click(bottoneIn(conferma, 'Annulla'))
    expect(attivo()).toBe(bottone('Segnala indisponibilità (2)').element)
    await click(bottone('Segnala indisponibilità (2)'))
    net.on(SEGNALA, jsonResponse(200, { idPrenotazioni: [1, 2], indisponibilitaSegnalata: true }))
    net.on(AGENDA, jsonResponse(200, [richiesta(1, { indisponibilitaSegnalata: true }), voce(2, { indisponibilitaSegnalata: true }), voce(3)]))
    await click(bottone('Conferma segnalazione'))
    expect(JSON.parse(net.callsTo(SEGNALA)[0].body)).toEqual({ medicoId: MEDICO, idPrenotazioni: [1, 2] })
    expect(avviso()).toContain('Indisponibilità segnalata per 2 appuntamenti')
    expect(casella(1).exists() || casella(2).exists()).toBe(false)
    expect(bottone('Segnala indisponibilità (0)')).toBeDefined()
  })

  it('409 atomico: nessun successo parziale dichiarato, rilettura', async () => {
    await apriAgenda([voce(1), voce(2)])
    await casella(1).setValue(true)
    await casella(2).setValue(true)
    await click(bottone('Segnala indisponibilità (2)'))
    net.on(SEGNALA, jsonResponse(409, { error: "L'appuntamento è già iniziato." })).on(AGENDA, jsonResponse(200, [voce(1), voce(2)]))
    await click(bottone('Conferma segnalazione'))
    expect(avviso()).toContain('nessun appuntamento del gruppo è stato segnalato')
    expect(net.callsTo(AGENDA)).toHaveLength(2)
  })

  it('esito incerto: gruppo bloccato; verifica per appuntamento senza dedurre gli assenti', async () => {
    await apriAgenda([voce(1), voce(2)])
    await casella(1).setValue(true)
    await casella(2).setValue(true)
    await click(bottone('Segnala indisponibilità (2)'))
    net.on(SEGNALA, 'rete')
    await click(bottone('Conferma segnalazione'))
    expect(avviso()).toContain('Non è stato possibile stabilire se la segnalazione')
    expect(casella(1).exists() || casella(2).exists()).toBe(false)
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [voce(1, { indisponibilitaSegnalata: true })]))
    net.on(AGENDA, jsonResponse(200, [voce(1, { indisponibilitaSegnalata: true })]))
    await click(bottoneIn(wrapper.get('[data-segnalazione-incerta]'), "Verifica nell'agenda"))
    const incerta = wrapper.get('[data-segnalazione-incerta]').text()
    expect(incerta).toContain('Prenotazione n. 1')
    expect(incerta).toContain('risulta segnalata')
    expect(incerta).toContain("non compare più nell'agenda: l'esito non può essere stabilito e resta bloccata")
    expect(net.callsTo(SEGNALA)).toHaveLength(1)
  })
})

describe('caricamento del referto', () => {
  const sezione = (id) => riga(id).get(`[data-referto-caricamento="${id}"]`)

  it('solo per EFFETTUATA del medico effettivo', async () => {
    await apriAgenda([effettuata(1), voce(2), richiesta(3)])
    expect(bottoneIn(riga(1), 'Carica referto')).toBeDefined()
    expect(riga(2).find('[data-referto-caricamento]').exists() || riga(3).find('[data-referto-caricamento]').exists()).toBe(false)
  })

  it('controlli locali, conferma con paziente, appuntamento e nome file; corpo binario application/pdf', async () => {
    await apriAgenda([effettuata(1)])
    const carica = bottoneIn(sezione(1), 'Carica referto')
    carica.element.focus()
    await click(carica)
    expect(attivo()).toBe(sezione(1).get('input[type="file"]').element)
    await click(bottoneIn(sezione(1), 'Prosegui'))
    expect(sezione(1).text()).toContain('Scegli il documento PDF del referto.')
    await scegliFile(sezione(1), new File(['<html>'], 'finto.pdf', { type: 'application/pdf' }))
    await click(bottoneIn(sezione(1), 'Prosegui'))
    expect(sezione(1).text()).toContain('non è un documento PDF')
    const file = pdf('esame-sintetico.pdf')
    await scegliFile(sezione(1), file)
    await click(bottoneIn(sezione(1), 'Prosegui'))
    const conferma = sezione(1).get('[data-conferma-referto]')
    expect(conferma.text()).toContain('Paola Prova1')
    expect(conferma.text()).toContain('lunedì 19/10/2026, 09:00–10:00')
    expect(conferma.get('[data-campo="nome-file"]').text()).toBe('esame-sintetico.pdf')
    expect(conferma.text()).toContain('unico per la prenotazione e non può essere sostituito')
    net.on(REFERTO(1), jsonResponse(201, caricato(1)))
    await click(bottoneIn(conferma, 'Carica il referto'))
    const [call] = net.callsTo(REFERTO(1))
    expect(call.body).toBe(file)
    expect(call.headers.get('Content-Type')).toBe('application/pdf')
    expect(sezione(1).get('[data-avviso-referto]').text()).toContain('Referto caricato')
    expect(bottoneIn(sezione(1), 'Carica referto')).toBeUndefined()
  })

  it('Annulla rilascia il file e torna al pulsante d\'origine', async () => {
    await apriAgenda([effettuata(1)])
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await scegliFile(sezione(1), pdf())
    await click(bottoneIn(sezione(1), 'Prosegui'))
    await click(bottoneIn(sezione(1), 'Annulla'))
    expect(attivo()).toBe(bottoneIn(sezione(1), 'Carica referto').element)
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await click(bottoneIn(sezione(1), 'Prosegui'))
    expect(sezione(1).text()).toContain('Scegli il documento PDF del referto.')
  })

  it('409: non prova quale documento sia stato salvato; rilettura, nessuna nuova scrittura', async () => {
    await apriAgenda([effettuata(1)])
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await scegliFile(sezione(1), pdf())
    await click(bottoneIn(sezione(1), 'Prosegui'))
    net.on(REFERTO(1), jsonResponse(409, { error: 'Referto già presente.' })).on(AGENDA, jsonResponse(200, [effettuata(1)]))
    await click(bottoneIn(sezione(1), 'Carica il referto'))
    expect(sezione(1).get('[data-avviso-referto]').text()).toContain('non indica quale documento sia stato salvato')
    expect(net.callsTo(AGENDA)).toHaveLength(2)
    expect(net.callsTo(REFERTO(1))).toHaveLength(1)
  })

  it('esito incerto: limite dichiarato, blocco nella navigazione SPA, nessuna API del paziente', async () => {
    const { router } = await apriAgenda([effettuata(1)], medico({ paziente: true }))
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await scegliFile(sezione(1), pdf())
    await click(bottoneIn(sezione(1), 'Prosegui'))
    net.on(REFERTO(1), 'rete')
    await click(bottoneIn(sezione(1), 'Carica il referto'))
    const testo = sezione(1).get('[data-avviso-referto]').text()
    expect(testo).toContain('non è dichiarato fallito')
    expect(testo).toContain('non permette di attribuirlo con certezza a questo tentativo')
    expect(bottoneIn(sezione(1), 'Carica referto')).toBeUndefined()
    await router.push('/area')
    await flushPromises()
    net.on(AGENDA, jsonResponse(200, [effettuata(1)]))
    const meseRientro = periodoMese(todayLocal().slice(0, 7))
    net.on(agendaPeriodo(meseRientro.dataDa, meseRientro.dataA), jsonResponse(200, []))
    await router.push('/medico/agenda')
    await flushPromises()
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(bottoneIn(sezione(1), 'Carica referto')).toBeUndefined()
    expect(wrapper.get('[data-referto-incerto]').text()).toContain('non permette di attribuirlo con certezza a questo tentativo')
    expect(net.calls.filter((c) => c.method === 'GET' && (c.url.includes('/referto') || c.url.startsWith('/api/prenotazioni/paziente')))).toHaveLength(0)
    expect(net.callsTo(REFERTO(1))).toHaveLength(1)
  })

  it('cambio di periodo durante la scelta del file: componente rimontato, file rilasciato', async () => {
    await apriAgenda([effettuata(1)])
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await scegliFile(sezione(1), pdf())
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#agenda-data').setValue('2026-10-19')
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [effettuata(1)]))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(sezione(1).find('input[type="file"]').exists()).toBe(false)
    await click(bottoneIn(sezione(1), 'Carica referto'))
    await click(bottoneIn(sezione(1), 'Prosegui'))
    expect(sezione(1).text()).toContain('Scegli il documento PDF del referto.')
  })
})

describe('contesto, riletture e duplicazioni', () => {
  const casella = (id) => riga(id).find('input[type="checkbox"]')
  const nuovoPeriodo = async (data, voci) => {
    await wrapper.get('input[value="singola"]').setValue()
    await wrapper.get('#agenda-data').setValue(data)
    net.on(agendaGiorno(data), jsonResponse(200, voci))
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
  }

  it('una conferma aperta si chiude quando parte una rilettura (dati non più aggiornati)', async () => {
    await apriAgenda([voce(1), voce(2)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), 'rete')
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    await click(bottoneIn(riga(2), 'Registra visita'))
    expect(riga(2).find('[data-conferma-visita]').exists()).toBe(true)
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [voce(1), voce(2)]))
    const rilettura = net.trattieni(AGENDA)
    await click(bottoneIn(wrapper.get('[data-visita-incerta]'), "Verifica nell'agenda"))
    expect(riga(2).find('[data-conferma-visita]').exists()).toBe(false)
    expect(bottoneIn(riga(2), 'Registra visita').attributes('disabled')).toBeDefined()
    rilettura.resolve(jsonResponse(200, [voce(1), voce(2)]))
    await flushPromises()
    expect(bottoneIn(riga(2), 'Registra visita').attributes('disabled')).toBeUndefined()
    expect(net.callsTo(VISITA(2))).toHaveLength(0)
  })

  it('scrittura in volo: uscendo e rientrando l\'azione non è riproposta', async () => {
    const { router } = await apriAgenda([voce(1), effettuata(2)])
    // Il caricamento in invio non blocca le altre righe; una conferma della vista in invio sì.
    await click(bottoneIn(sezione2(), 'Carica referto'))
    await scegliFile(sezione2(), pdf())
    await click(bottoneIn(sezione2(), 'Prosegui'))
    const r = net.trattieni(REFERTO(2))
    await click(bottoneIn(sezione2(), 'Carica il referto'))
    await click(bottoneIn(riga(1), 'Registra visita'))
    const h = net.trattieni(VISITA(1))
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    await router.push('/area')
    await flushPromises()
    net.on(AGENDA, jsonResponse(200, [voce(1), effettuata(2)]))
    const meseRientro = periodoMese(todayLocal().slice(0, 7))
    net.on(agendaPeriodo(meseRientro.dataDa, meseRientro.dataA), jsonResponse(200, []))
    await router.push('/medico/agenda')
    await flushPromises()
    await wrapper.get('main .filtri-avanzati form').trigger('submit')
    await flushPromises()
    expect(bottoneIn(riga(1), 'Registra visita')).toBeUndefined()
    expect(riga(1).text()).toContain('Registrazione della visita in corso')
    expect(bottoneIn(sezione2(), 'Carica referto').attributes('disabled')).toBeDefined()
    h.resolve(jsonResponse(200, registrata(1)))
    r.resolve(jsonResponse(201, caricato(2)))
    await flushPromises()
    // Esiti registrati nello store; nessun messaggio nella nuova istanza della vista.
    expect(wrapper.find('[data-avviso-agenda]').exists()).toBe(false)
    expect(bottoneIn(riga(1), 'Registra visita')).toBeUndefined()
    expect(sezione2().find('[data-referto-caricato]').exists()).toBe(true)
    expect(net.callsTo(VISITA(1))).toHaveLength(1)
    expect(net.callsTo(REFERTO(2))).toHaveLength(1)
  })
  const sezione2 = () => riga(2).get('[data-referto-caricamento="2"]')

  it('segnalazione e cambio di periodo: nessun messaggio né modifica della selezione del nuovo contesto', async () => {
    const { store } = await apriAgenda([voce(1)])
    await casella(1).setValue(true)
    await click(bottone('Segnala indisponibilità (1)'))
    const h = net.trattieni(SEGNALA)
    await click(bottone('Conferma segnalazione'))
    await nuovoPeriodo('2026-10-20', [voce(5, { dataOraInizio: '2026-10-20T09:00:00', dataOraFine: '2026-10-20T10:00:00' })])
    await casella(5).setValue(true)
    h.resolve(jsonResponse(200, { idPrenotazioni: [1], indisponibilitaSegnalata: true }))
    await flushPromises()
    expect(wrapper.find('[data-avviso-agenda]').exists()).toBe(false)
    expect(casella(5).element.checked).toBe(true)
    expect(bottone('Segnala indisponibilità (1)')).toBeDefined()
    expect(store.segnalazioneNota(1)).toBe(true)
    expect(net.calls.filter((c) => c.url === `${AGENDA.slice(4)}`)).toHaveLength(1)
  })

  it('caricamento e cambio di periodo durante l\'invio: nessun messaggio nel nuovo contesto, esito nello store', async () => {
    const { store } = await apriAgenda([effettuata(1)])
    const s = () => riga(1).get('[data-referto-caricamento="1"]')
    await click(bottoneIn(s(), 'Carica referto'))
    await scegliFile(s(), pdf())
    await click(bottoneIn(s(), 'Prosegui'))
    const h = net.trattieni(REFERTO(1))
    await click(bottoneIn(s(), 'Carica il referto'))
    await nuovoPeriodo('2026-10-19', [effettuata(1)])
    const focus = attivo()
    h.resolve(jsonResponse(201, caricato(1)))
    await flushPromises()
    expect(s().find('[data-avviso-referto]').exists()).toBe(false)
    expect(attivo()).toBe(focus)
    expect(store.refertoCaricato(1)).toMatchObject({ idReferto: 9 })
    expect(s().find('[data-referto-caricato]').exists()).toBe(true)
  })

  it('logout: dati dell\'agenda rimossi e incertezze eliminate', async () => {
    const { session, store, router } = await apriAgenda([voce(1)])
    await click(bottoneIn(riga(1), 'Registra visita'))
    net.on(VISITA(1), 'rete')
    await click(bottoneIn(riga(1), 'Conferma registrazione'))
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(wrapper.find('[data-appuntamento]').exists()).toBe(false)
    expect(store.state.visiteIncerte).toEqual([])
  })
})
