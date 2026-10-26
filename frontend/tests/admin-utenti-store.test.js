import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed } from 'vue'
import { parseAccount, parseUtenteAmministrativo } from '../src/api/utenti.js'
import { ELENCO, RICERCA, adminStore } from '../src/admin/store.js'
import { createSession } from '../src/session/session.js'
import { ADMIN, CREA, GESTIONALI, PASSWORD, RICERCA as Q, RUOLO, STATO, account, admin, amministrativo, desk, pagina } from './admin-utenti-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const DATI = Object.freeze({ nome: 'Nina', cognome: 'Nuova', email: 'nina@example.test', ruolo: 'OPERATORE_DESK' })
const creato = (extra = {}) => account(20, { nome: 'Nina', cognome: 'Nuova', email: 'nina@example.test', ...extra })
const errore = (status, error = 'x', headers = {}) => jsonResponse(status, { error }, headers)
const corpo = (k) => JSON.parse(net.callsTo(k).at(-1).body)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length
const g = (id, extra) => parseAccount(account(id, extra))

let net
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { vi.restoreAllMocks() })

async function avvia(user = admin()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: adminStore(session) }
}

describe('permessi', () => {
  it('l\'operatore desk non è amministratore: nessuna scrittura e nessuna verifica', async () => {
    const { store } = await avvia(desk())
    expect(store.amministratoreCorrente()).toBeNull()
    expect(await store.crea(DATI, PASSWORD)).toEqual({ kind: 'sessione-assente' })
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toEqual({ kind: 'sessione-assente' })
    expect(scritture()).toBe(0)
  })

  it('password incerta: rifiuto prima di fetch', async () => {
    const { session, store } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await store.cambiaRuolo(g(5), 'AMMINISTRATORE')).toEqual({ kind: 'password-incerta' })
    expect(scritture()).toBe(0)
  })
})

describe('creazione', () => {
  it('certa: cinque campi, esito validato, barriere su elenco, ricerca ed email', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    net.on(CREA, jsonResponse(201, creato()))
    const esito = await store.crea(DATI, PASSWORD)
    expect(esito).toMatchObject({ kind: 'eseguita', risultato: { idUtente: 20, proiezione: 'gestionale' } })
    expect(corpo(CREA)).toEqual({ nome: 'Nina', cognome: 'Nuova', email: 'nina@example.test', password: PASSWORD, ruoloGestionale: 'OPERATORE_DESK' })
    expect([store.valida(ELENCO, t), store.valida(RICERCA, t), store.valida('email:nina@example.test', t)]).toEqual([false, false, false])
    expect(store.valida(ELENCO, store.inizioLettura())).toBe(true)
  })

  it('incerta: nessuna password conservata, stessa email bloccata, altre email libere, nessun retry', async () => {
    const { store } = await avvia()
    net.on(CREA, 'rete')
    expect((await store.crea(DATI, PASSWORD)).kind).toBe('incerto')
    expect(JSON.stringify(store.state)).not.toContain(PASSWORD)
    expect(store.incerta('email:nina@example.test')).toMatchObject({ tipo: 'crea', riepilogo: DATI })
    expect(await store.crea(DATI, PASSWORD)).toEqual({ kind: 'bloccata' })
    net.on(CREA, jsonResponse(201, account(21, { nome: 'Nina', cognome: 'Nuova', email: 'altra@example.test' })))
    expect((await store.crea({ ...DATI, email: 'altra@example.test' }, PASSWORD)).kind).toBe('eseguita')
    expect(net.callsTo(CREA)).toHaveLength(2)
  })

  it('201 non conforme (profilo medico o dati diversi): esito incerto, mai successo', async () => {
    const { store } = await avvia()
    net.on(CREA, jsonResponse(201, creato({ medico: true })))
    expect((await store.crea(DATI, PASSWORD)).kind).toBe('incerto')
  })

  it('verifica per email: presente non attribuita, assente, pagine esaurite senza esito', async () => {
    const { store } = await avvia()
    const qEmail = (dopo) => Q(`email=nina%40example.test${dopo ? `&dopoId=${dopo}` : ''}&limite=50`)
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    // Il prefisso trova prima un'altra email: si prosegue fino all'email esatta.
    net.on(qEmail(0), jsonResponse(200, pagina(Array.from({ length: 50 }, (_, i) => amministrativo(i + 1, { email: `nina@example.test${i}` })), 50)))
    net.on(qEmail(50), jsonResponse(200, pagina([amministrativo(60, { email: 'nina@example.test' })])))
    expect(await store.verifica('email:nina@example.test')).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 60 } })
    expect(store.bloccata('email:nina@example.test')).toBe(false)
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    net.on(qEmail(0), jsonResponse(200, pagina([])))
    expect((await store.verifica('email:nina@example.test')).esito).toBe('assente')
    net.on(CREA, 'rete')
    await store.crea(DATI, PASSWORD)
    for (let i = 0; i < 10; i++) {
      net.on(qEmail(i * 50), jsonResponse(200, pagina(Array.from({ length: 50 }, (_, k) => amministrativo(i * 50 + k + 1, { email: `nina@example.test.x${k}` })), (i + 1) * 50)))
    }
    expect(await store.verifica('email:nina@example.test')).toEqual({ kind: 'non-conclusiva' })
    expect(store.bloccata('email:nina@example.test')).toBe(true)
  })
})

describe('ruolo e stato', () => {
  it('ruolo: solo account gestionali, nessun ruolo nullo, stesso ruolo senza invio', async () => {
    const { store } = await avvia()
    const paziente = parseUtenteAmministrativo(amministrativo(7, { ruoloGestionale: null }))
    expect(await store.cambiaRuolo(paziente, 'OPERATORE_DESK')).toEqual({ kind: 'non-gestionale' })
    expect(await store.cambiaRuolo(g(5), 'OPERATORE_DESK')).toEqual({ kind: 'invariato' })
    net.on(RUOLO(5), jsonResponse(200, account(5, { ruoloGestionale: 'AMMINISTRATORE' })))
    expect((await store.cambiaRuolo(g(5), 'AMMINISTRATORE')).kind).toBe('eseguita')
    expect(corpo(RUOLO(5))).toEqual({ ruoloGestionale: 'AMMINISTRATORE' })
    expect(net.callsTo(RUOLO(5))).toHaveLength(1)
  })

  it('stato: proiezione a 7 campi; 409 certi con barriera e senza blocco; 403 senza barriera', async () => {
    const { store } = await avvia()
    net.on(STATO(5), jsonResponse(200, amministrativo(5, { statoAccesso: 'DISABILITATO' })))
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toMatchObject({ kind: 'eseguita', risultato: { proiezione: 'amministrativa' } })
    expect(corpo(STATO(5))).toEqual({ statoAccesso: 'DISABILITATO' })
    for (const messaggio of ['Ultimo amministratore attivo protetto.', 'Inattivare prima il profilo medico.', 'Auto-disabilitazione non consentita.']) {
      const t = store.inizioLettura()
      net.on(STATO(6), errore(409, messaggio))
      expect(await store.cambiaStato(g(6), 'DISABILITATO')).toMatchObject({ kind: 'conflitto', serverMessage: messaggio })
      expect(store.valida(ELENCO, t)).toBe(false)
      expect(store.bloccata('utente:6')).toBe(false)
    }
    const t = store.inizioLettura()
    net.on(STATO(6), errore(403, 'Operazione non autorizzata.'))
    expect((await store.cambiaStato(g(6), 'DISABILITATO')).kind).toBe('rifiutata')
    expect(store.valida(ELENCO, t)).toBe(true)
  })

  it('operazioni incompatibili sullo stesso utente: in volo e incerte bloccano; altri utenti liberi', async () => {
    const { store } = await avvia()
    const h = net.trattieni(RUOLO(5))
    const volo = store.cambiaRuolo(g(5), 'AMMINISTRATORE')
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toEqual({ kind: 'occupato' })
    net.on(STATO(6), jsonResponse(200, amministrativo(6, { statoAccesso: 'DISABILITATO' })))
    expect((await store.cambiaStato(g(6), 'DISABILITATO')).kind).toBe('eseguita')
    h.resolve(errore(500))
    expect((await volo).kind).toBe('incerto')
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toEqual({ kind: 'bloccata' })
    expect(scritture()).toBe(2)
  })

  it('verifica per ID: come richiesto, precedente, diverso, assente', async () => {
    const { store } = await avvia()
    const qId = Q('idUtente=5&limite=1')
    for (const [risultati, atteso] of [[[amministrativo(5, { ruoloGestionale: 'AMMINISTRATORE' })], 'come-richiesto'], [[amministrativo(5)], 'precedente'],
      [[amministrativo(5, { ruoloGestionale: null })], 'diverso'], [[], 'assente']]) {
      net.on(RUOLO(5), 'rete')
      expect((await store.cambiaRuolo(g(5), 'AMMINISTRATORE')).kind).toBe('incerto')
      net.on(qId, jsonResponse(200, pagina(risultati)))
      expect((await store.verifica('utente:5')).esito).toBe(atteso)
      expect(store.bloccata('utente:5')).toBe(false)
    }
  })
})

describe('Retry-After e risposte del server', () => {
  it('503 con Retry-After e JSON: esito incerto con blocco; attesa separata rispettata senza retry; alla scadenza l\'incertezza resta', async () => {
    const { store } = await avvia()
    let ora = 1_000_000
    vi.spyOn(Date, 'now').mockImplementation(() => ora)
    net.on(STATO(5), errore(503, 'Servizio temporaneamente non disponibile.', { 'Retry-After': '5' }))
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toMatchObject({ kind: 'incerto', retryAfter: 5 })
    expect(store.incerta('utente:5')).toMatchObject({ tipo: 'stato' })
    expect(await store.crea(DATI, PASSWORD)).toEqual({ kind: 'attesa', retryAfter: 5 })
    ora += 5000
    expect(store.attesaResidua()).toBe(0)
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(STATO(5))).toHaveLength(1)
  })

  it('429 e 5xx, con o senza Retry-After: sempre incerti; 409 resta un rifiuto certo', async () => {
    const { store } = await avvia()
    net.on(STATO(5), errore(429, 'Troppi tentativi.', { 'Retry-After': '60' }))
    expect(await store.cambiaStato(g(5), 'DISABILITATO')).toMatchObject({ kind: 'incerto', retryAfter: 60 })
    expect(store.attesaResidua()).toBeGreaterThan(50)
    const { store: altro } = await avvia(admin({ idUtente: 2 }))
    net.on(STATO(6), errore(503, 'Servizio temporaneamente non disponibile.'))
    expect(await altro.cambiaStato(g(6), 'DISABILITATO')).toMatchObject({ kind: 'incerto', retryAfter: null })
    expect(altro.attesaResidua()).toBe(0)
    net.on(STATO(7), errore(409, 'Ultimo amministratore attivo protetto.'))
    expect((await altro.cambiaStato(g(7), 'DISABILITATO')).kind).toBe('conflitto')
  })
})

describe('validità reattiva, sessione e identità', () => {
  it('una lettura perde idoneità appena un esito certo successivo tocca il suo ambito', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    const elenco = computed(() => store.valida(ELENCO, t))
    const altroUtente = computed(() => store.valida('utente:9', t))
    net.on(STATO(5), jsonResponse(200, amministrativo(5, { statoAccesso: 'DISABILITATO' })))
    await store.cambiaStato(g(5), 'DISABILITATO')
    expect([elenco.value, altroUtente.value]).toEqual([false, true])
  })

  it('rotazione: incertezze conservate, ticket precedenti non più validi; nuova identità: svuotamento ed esiti tardivi scartati', async () => {
    const { session, store } = await avvia()
    net.on(RUOLO(5), 'rete')
    await store.cambiaRuolo(g(5), 'AMMINISTRATORE')
    const t = store.inizioLettura()
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    expect(store.bloccata('utente:5')).toBe(true)
    expect(store.valida(ELENCO, t)).toBe(false)
    const h = net.trattieni(STATO(6))
    const tardiva = store.cambiaStato(g(6), 'DISABILITATO')
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(admin({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, amministrativo(6, { statoAccesso: 'DISABILITATO' })))
    expect((await tardiva).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, incerte: [], inCorso: [], barriere: {} })
    expect(ADMIN).toBe(1)
    expect(GESTIONALI).toBe('GET /api/utenti')
  })
})
