import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMedicoAdmin, parseMedicoAdmin } from '../src/api/medici.js'
import { parseUtenteAmministrativo } from '../src/api/utenti.js'
import { MEDICI as AMBITO_MEDICI, RICERCA as AMBITO_RICERCA, adminStore, chiaveMedico } from '../src/admin/store.js'
import { createSession } from '../src/session/session.js'
import {
  ABILITA, COLLEGA, CREA_MEDICO, DATI, MEDICI, MEDICO, PASSWORD, RICERCA, STATO_MEDICO, admin, amministrativo, conflitto, desk, dettaglioAdmin,
  medicoAdmin, pagina, prestazione,
} from './b9b-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const NUOVO = Object.freeze({ nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-9' })
const m = (id, extra) => parseMedicoAdmin(medicoAdmin(id, extra))
const u = (id, extra) => parseUtenteAmministrativo(amministrativo(id, extra))
const corpo = (k) => JSON.parse(net.callsTo(k).at(-1).body)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length
const qEmail = RICERCA('email=nina%40example.test&limite=50')

let net
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => vi.restoreAllMocks())

async function avvia(user = admin()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: adminStore(session) }
}

describe('permessi e sessione', () => {
  it('l\'operatore desk non scrive; password incerta: rifiuto prima di fetch', async () => {
    const { store } = await avvia(desk())
    expect(await store.creaMedico(NUOVO, PASSWORD)).toEqual({ kind: 'sessione-assente' })
    const { session, store: s2 } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await s2.cambiaStatoMedico(m(5), 'INATTIVO')).toEqual({ kind: 'password-incerta' })
    expect(scritture()).toBe(0)
  })
})

describe('creazione e collegamento', () => {
  it('creazione certa: sei campi, 201 coerente, barriere su medici, ricerca ed email; nessuna password conservata', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    net.on(CREA_MEDICO, jsonResponse(201, medicoAdmin(20, { nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', numeroIscrizioneOrdine: 'ORD-9' })))
    expect((await store.creaMedico(NUOVO, PASSWORD)).kind).toBe('eseguita')
    expect(Object.keys(corpo(CREA_MEDICO))).toEqual(['nome', 'cognome', 'email', 'password', 'specializzazione', 'numeroIscrizioneOrdine'])
    expect([store.valida(AMBITO_MEDICI, t), store.valida(AMBITO_RICERCA, t), store.valida('email:nina@example.test', t)]).toEqual([false, false, false])
    net.on(CREA_MEDICO, 'rete')
    await store.creaMedico({ ...NUOVO, email: 'altra@example.test' }, PASSWORD)
    expect(JSON.stringify(store.state)).not.toContain(PASSWORD)
  })

  it('201 incoerente (stato, profilo paziente o ruolo inattesi): incerto, mai successo', async () => {
    const { store } = await avvia()
    net.on(CREA_MEDICO, jsonResponse(201, medicoAdmin(20, { nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', numeroIscrizioneOrdine: 'ORD-9', paziente: true })))
    expect((await store.creaMedico(NUOVO, PASSWORD)).kind).toBe('incerto')
  })

  it('stessa email: creazione medico e creazione account B9A si bloccano a vicenda', async () => {
    const { store } = await avvia()
    net.on(CREA_MEDICO, 'rete')
    await store.creaMedico(NUOVO, PASSWORD)
    expect(await store.crea({ nome: 'N', cognome: 'C', email: 'NINA@example.test', ruolo: 'OPERATORE_DESK' }, PASSWORD)).toEqual({ kind: 'bloccata' })
    expect(await store.creaMedico({ ...NUOVO, email: ' Nina@Example.TEST ' }, PASSWORD)).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(CREA_MEDICO)).toHaveLength(1)
  })

  it('collegamento: due campi, profilo paziente conservato, 409 generico certo con barriera', async () => {
    const { store } = await avvia()
    net.on(COLLEGA(7), jsonResponse(201, medicoAdmin(7, { specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-7', paziente: true })))
    const e = await store.collega(u(7, { ruoloGestionale: null }), { specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-7' })
    expect(e).toMatchObject({ kind: 'eseguita', risultato: { paziente: true } })
    expect(corpo(COLLEGA(7))).toEqual({ specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-7' })
    const t = store.inizioLettura()
    net.on(COLLEGA(8), conflitto())
    expect(await store.collega(u(8), { specializzazione: 'S', numeroIscrizioneOrdine: 'O' })).toMatchObject({ kind: 'conflitto', serverMessage: 'Operazione in conflitto.' })
    expect(store.valida(chiaveMedico(8), t)).toBe(false)
    expect(store.bloccata('utente:8')).toBe(false)
  })
})

describe('blocchi condivisi con B9A sulla stessa identità', () => {
  it('stato di accesso B9A in volo blocca lo stato medico; esito incerto B9B blocca il ruolo B9A; altri utenti liberi', async () => {
    const { store } = await avvia()
    const h = net.trattieni('PUT /api/utenti/5/stato')
    const b9a = store.cambiaStato(parseUtenteAmministrativo(amministrativo(5)), 'DISABILITATO')
    expect(await store.cambiaStatoMedico(m(5), 'INATTIVO')).toEqual({ kind: 'occupato' })
    h.resolve(jsonResponse(200, amministrativo(5, { statoAccesso: 'DISABILITATO' })))
    await b9a
    net.on(STATO_MEDICO(6), jsonResponse(502, { error: 'x' }))
    expect((await store.cambiaStatoMedico(m(6), 'INATTIVO')).kind).toBe('incerto')
    expect(await store.cambiaStato(parseUtenteAmministrativo(amministrativo(6)), 'DISABILITATO')).toEqual({ kind: 'bloccata' })
    net.on(ABILITA(9), jsonResponse(200, { idMedico: 9, idPrestazione: 2, operazione: 'ASSEGNA' }))
    expect((await store.abilitazione(m(9), 'ASSEGNA', prestazione(2))).kind).toBe('eseguita')
  })

  it('503 con Retry-After: incerto, attesa condivisa con le scritture B9A; alla scadenza l\'incertezza resta', async () => {
    const { store } = await avvia()
    let ora = 1_000_000
    vi.spyOn(Date, 'now').mockImplementation(() => ora)
    net.on(DATI(5), jsonResponse(503, { error: 'x' }, { 'Retry-After': '5' }))
    expect(await store.aggiornaMedico(m(5), 'PROFESSIONALI', { specializzazione: 'Neurologia', numeroIscrizioneOrdine: 'ORD-5' })).toMatchObject({ kind: 'incerto', retryAfter: 5 })
    expect(await store.cambiaStato(parseUtenteAmministrativo(amministrativo(9)), 'DISABILITATO')).toEqual({ kind: 'attesa', retryAfter: 5 })
    ora += 5000
    expect(await store.cambiaStatoMedico(m(5), 'INATTIVO')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(DATI(5))).toHaveLength(1)
  })
})

describe('riconciliazione con letture ordinate', () => {
  it('stato medico: verifica sul dettaglio → come richiesto, come prima, diverso; 404 → assente', async () => {
    const { store } = await avvia()
    for (const [risposta, atteso] of [[jsonResponse(200, dettaglioAdmin(5, [], { stato: 'INATTIVO' })), 'come-richiesto'],
      [jsonResponse(200, dettaglioAdmin(5)), 'precedente'], [jsonResponse(200, dettaglioAdmin(5, [], { stato: 'XX' })), 'diverso'],
      [jsonResponse(404, { error: 'Risorsa non trovata.' }), 'assente']]) {
      net.on(STATO_MEDICO(5), 'rete')
      expect((await store.cambiaStatoMedico(m(5), 'INATTIVO')).kind).toBe('incerto')
      net.on(MEDICO(5), risposta)
      expect((await store.verifica('utente:5')).esito).toBe(atteso)
    }
    // Gli esiti medico vengono verificati con le letture medico, non con la ricerca utenti.
    expect(net.calls.some((c) => c.url.startsWith('/api/utenti/ricerca'))).toBe(false)
  })

  it('abilitazione: A dal dettaglio, poi elenco più recente senza abilitazioni: esito neutro, blocco mantenuto; nuovo dettaglio sufficiente', async () => {
    const { store } = await avvia()
    net.on(ABILITA(5), 'rete')
    await store.abilitazione(m(5), 'RIMUOVI', prestazione(2))
    net.on(MEDICO(5), jsonResponse(200, dettaglioAdmin(5, [prestazione(2)])))
    const l = await store.leggiVerifica('utente:5')
    store.registraMedici([m(5)], store.inizioLettura(), { stato: 'ATTIVO' })
    expect(store.esitoVerifica('utente:5', l)).toEqual({ kind: 'superata' })
    expect(store.bloccata('utente:5')).toBe(true)
    store.registraDettaglio({ medico: m(5), prestazioni: [] }, store.inizioLettura())
    expect(store.esitoVerifica('utente:5', l)).toMatchObject({ kind: 'verificata', esito: 'come-richiesto' })
  })

  it('dati personali: risposta con email maiuscola corrisponde; risposta A vecchia non sostituisce B più recente', async () => {
    const { store } = await avvia()
    const dati = { nome: 'Elena', cognome: 'Nuova', email: 'nuova@example.test' }
    net.on(DATI(5), 'rete')
    await store.aggiornaMedico(m(5), 'PERSONALI', dati)
    const h = net.trattieni(MEDICO(5))
    const lettura = store.leggiVerifica('utente:5')
    store.registraDettaglio({ medico: m(5, { cognome: 'Nuova', email: 'NUOVA@EXAMPLE.TEST' }), prestazioni: [] }, store.inizioLettura())
    h.resolve(jsonResponse(200, dettaglioAdmin(5)))
    const l = await lettura
    expect(store.esitoVerifica('utente:5', l)).toMatchObject({ esito: 'come-richiesto', voce: { email: 'NUOVA@EXAMPLE.TEST' } })
  })

  it('collegamento: assenza coperta solo dall\'elenco senza filtri o dal 404 del dettaglio', async () => {
    const { store } = await avvia()
    net.on(COLLEGA(7), 'rete')
    await store.collega(u(7), { specializzazione: 'S', numeroIscrizioneOrdine: 'O' })
    net.on(MEDICO(7), jsonResponse(200, dettaglioAdmin(7, [], { specializzazione: 'S', numeroIscrizioneOrdine: 'O' })))
    const l = await store.leggiVerifica('utente:7')
    store.registraMedici([], store.inizioLettura(), { stato: 'ATTIVO' })
    expect(store.esitoVerifica('utente:7', l)).toMatchObject({ esito: 'come-richiesto' })
    net.on(COLLEGA(7), 'rete')
    await store.collega(u(7), { specializzazione: 'S', numeroIscrizioneOrdine: 'O' })
    net.on(MEDICO(7), jsonResponse(200, dettaglioAdmin(7, [], { specializzazione: 'S', numeroIscrizioneOrdine: 'O' })))
    const l2 = await store.leggiVerifica('utente:7')
    store.registraMedici([], store.inizioLettura())
    expect(store.esitoVerifica('utente:7', l2)).toMatchObject({ esito: 'assente' })
  })

  it('creazione: email esaurita → assente; account trovato con profilo → presente; senza profilo (404) → senza-profilo; email smentita → incoerente', async () => {
    const { store } = await avvia()
    const incerta = async () => { net.on(CREA_MEDICO, 'rete'); await store.creaMedico(NUOVO, PASSWORD) }
    await incerta()
    net.on(qEmail, jsonResponse(200, pagina([])))
    expect((await store.verifica('email:nina@example.test')).esito).toBe('assente')
    await incerta()
    net.on(qEmail, jsonResponse(200, pagina([amministrativo(20, { email: 'NINA@example.test' })])))
    net.on(MEDICO(20), jsonResponse(200, dettaglioAdmin(20, [], { nome: 'Nina', cognome: 'Medica', email: 'nina@example.test', numeroIscrizioneOrdine: 'ORD-9' })))
    expect(await store.verifica('email:nina@example.test')).toMatchObject({ esito: 'come-richiesto', voce: { idUtente: 20 } })
    await incerta()
    net.on(qEmail, jsonResponse(200, pagina([amministrativo(21, { email: 'nina@example.test' })])))
    net.on(MEDICO(21), jsonResponse(404, { error: 'Risorsa non trovata.' }))
    expect((await store.verifica('email:nina@example.test')).esito).toBe('senza-profilo')
    await incerta()
    net.on(qEmail, jsonResponse(200, pagina([amministrativo(22, { email: 'nina@example.test' })])))
    net.on(MEDICO(22), jsonResponse(200, dettaglioAdmin(22)))
    const l = await store.leggiVerifica('email:nina@example.test')
    store.registraRicerca({ idUtente: '22' }, 0, { risultati: [u(22, { email: 'altra@example.test' })], prossimoDopoId: null }, store.inizioLettura())
    expect(store.esitoVerifica('email:nina@example.test', l)).toEqual({ kind: 'incoerente' })
    expect(store.bloccata('email:nina@example.test')).toBe(true)
  })

  it('cambio di epoca durante la lettura: risposta scartata, blocco mantenuto; nuova identità: tutto svuotato', async () => {
    const { session, store } = await avvia()
    net.on(STATO_MEDICO(5), 'rete')
    await store.cambiaStatoMedico(m(5), 'INATTIVO')
    const h = net.trattieni(MEDICO(5))
    const lettura = store.leggiVerifica('utente:5')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    h.resolve?.(jsonResponse(200, dettaglioAdmin(5, [], { stato: 'INATTIVO' })))
    expect((await lettura).kind).toBe('obsoleto')
    expect(store.bloccata('utente:5')).toBe(true)
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(admin({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    expect(store.state).toMatchObject({ incerte: [], inCorso: [] })
    expect(typeof fetchMedicoAdmin).toBe('function')
    expect(MEDICI).toBe('GET /api/medici')
  })
})
