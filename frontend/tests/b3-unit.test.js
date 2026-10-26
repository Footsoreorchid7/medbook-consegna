import { beforeEach, describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  parseAnnullamento, parseAppuntamento, parseIdRisorsa, parsePrenotazioneCreata, prenotazioniPazientePath,
} from '../src/api/prenotazioni.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { STATO, createSession } from '../src/session/session.js'
import { appuntamento } from './b3-fixtures.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

// Dati sintetici: nessuna identità reale.
const ME = 'GET /api/auth/me'
const CREA = 'POST /api/prenotazioni'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const PWD = 'PUT /api/auth/password'
const lista = (id = 7, query = '') => `GET /api/prenotazioni/paziente?idPaziente=${id}${query}`
const annulla = (id) => `POST /api/prenotazioni/${id}/annulla-paziente`

const creata = (extra = {}) => ({
  idPrenotazione: 50, stato: 'RICHIESTA', dataOraRichiesta: '2026-10-15T20:10:11', prezzo: 80, idDisponibilita: 10,
  dataOraInizio: '2026-10-20T09:00', dataOraFine: '2026-10-20T10:00', idPrestazione: 1, nomePrestazione: 'Visita cardiologica',
  idMedico: 4, nomeMedico: 'Elena', cognomeMedico: 'Sintetica', ...extra,
})
const SLOT = Object.freeze({ idDisponibilita: 10, nomePrestazione: 'Visita cardiologica', nomeMedico: 'Elena', cognomeMedico: 'Sintetica', prezzo: 80 })

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function autenticata(user = utente()) {
  net.on(ME, meOk(user, CSRF_A))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: prenotazioniStore(session) }
}

const corpo = (call) => JSON.parse(call.body)

describe('prenotazioniPazientePath', () => {
  it('solo idPaziente e filtri valorizzati, nessun parametro vuoto', () => {
    expect(prenotazioniPazientePath({ idPaziente: 7 })).toBe('/api/prenotazioni/paziente?idPaziente=7')
    expect(prenotazioniPazientePath({ idPaziente: 7, stato: '', data: '', dataDa: '', dataA: '' }))
      .toBe('/api/prenotazioni/paziente?idPaziente=7')
    expect(prenotazioniPazientePath({ idPaziente: 7, stato: 'CONFERMATA', data: '2026-10-20' }))
      .toBe('/api/prenotazioni/paziente?idPaziente=7&stato=CONFERMATA&data=2026-10-20')
    expect(prenotazioniPazientePath({ idPaziente: 7, dataDa: '2026-10-01', dataA: '2026-10-31' }))
      .toBe('/api/prenotazioni/paziente?idPaziente=7&dataDa=2026-10-01&dataA=2026-10-31')
    expect(prenotazioniPazientePath({ idPaziente: 7, dataDa: '2026-10-20', dataA: '2026-10-20' }))
      .toBe('/api/prenotazioni/paziente?idPaziente=7&dataDa=2026-10-20&dataA=2026-10-20')
  })

  it.each([
    [{ idPaziente: 0 }],
    [{ idPaziente: '7' }],
    [{ idPaziente: 7, stato: 'IN_ATTESA' }],
    [{ idPaziente: 7, data: '2026-10-20', dataDa: '2026-10-01', dataA: '2026-10-31' }],
    [{ idPaziente: 7, data: '2026-10-20', dataA: '2026-10-31' }],
    [{ idPaziente: 7, dataDa: '2026-10-01' }],
    [{ idPaziente: 7, dataA: '2026-10-31' }],
    [{ idPaziente: 7, dataDa: '2026-10-31', dataA: '2026-10-01' }],
    [{ idPaziente: 7, data: '20/10/2026' }],
    [{ idPaziente: 7, data: '2026-02-30' }],
    [{ idPaziente: 7, data: '0999-12-31' }],
    [{ idPaziente: 7, data: '9999-12-31' }],
    [{ idPaziente: 7, dataDa: '2026-10-01', dataA: '9999-12-31' }],
  ])('combinazione o valore non ammesso rifiutato prima della richiesta: %j', (filtri) => {
    expect(() => prenotazioniPazientePath(filtri)).toThrow(TypeError)
  })
})

describe('parseIdRisorsa', () => {
  it('solo interi canonici entro l\'Integer Java', () => {
    expect(parseIdRisorsa('12')).toBe(12)
    expect(parseIdRisorsa('2147483647')).toBe(2147483647)
    for (const v of ['0', '012', '-1', '+1', '1e3', '1.0', '2147483648', 'abc', '%31', ' 1', '', undefined, ['1']]) {
      expect(parseIdRisorsa(v)).toBeNull()
    }
  })
})

describe('parseAppuntamento', () => {
  it('campi facoltativi null: prestazione, stanza, medico effettivo', () => {
    const p = parseAppuntamento(appuntamento(1, { idPrestazione: null, nomePrestazione: null, durataPrestazione: null }))
    expect(p).toMatchObject({ nomePrestazione: null, durata: null, stanza: null, effettivo: null })
    expect(p.titolare).toEqual({ id: 4, nome: 'Elena', cognome: 'Sintetica' })
    expect(p.inizio).toMatchObject({ date: '2026-10-20', hour: 9, minute: 0 })
    expect(p.richiesta).toMatchObject({ date: '2026-10-15', hour: 20, minute: 10 })
  })

  it('stanza e medico effettivo presenti', () => {
    const p = parseAppuntamento(appuntamento(1, {
      stato: 'CONFERMATA', idStanza: 3, nomeStanza: 'Ambulatorio 1', idMedicoEffettivo: 5, nomeMedicoEffettivo: 'Marco', cognomeMedicoEffettivo: 'Prova',
    }))
    expect(p.stanza).toEqual({ id: 3, nome: 'Ambulatorio 1' })
    expect(p.effettivo).toEqual({ id: 5, nome: 'Marco', cognome: 'Prova' })
  })

  it.each([
    { stato: 'IN_ATTESA' },
    { idPrenotazione: 0 },
    { prezzo: '80.00' },
    { dataOraInizio: '2026-10-20T09:00:00Z' },
    { dataOraRichiesta: null },
    { idMedicoTitolare: null, nomeMedicoTitolare: null, cognomeMedicoTitolare: null },
    { idMedicoEffettivo: 5, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: 'Prova' },
    { idMedicoEffettivo: null, nomeMedicoEffettivo: 'Marco', cognomeMedicoEffettivo: null },
    { idStanza: 'A' },
  ])('voce non conforme: %j', (extra) => {
    expect(() => parseAppuntamento(appuntamento(1, extra))).toThrow(ProtocolError)
  })
})

describe('parsePrenotazioneCreata e parseAnnullamento', () => {
  const ok = (status, data) => ({ status, hasBody: data !== undefined, data, headers: new Headers() })

  it('201 RICHIESTA dello stesso slot', () => {
    const p = parsePrenotazioneCreata(ok(201, creata()), 10)
    expect(p).toMatchObject({ idPrenotazione: 50, stato: 'RICHIESTA', idDisponibilita: 10, nomeMedico: 'Elena' })
  })

  it.each([
    ['200 invece di 201', ok(200, creata())],
    ['201 senza corpo', ok(201, undefined)],
    ['stato CONFERMATA', ok(201, creata({ stato: 'CONFERMATA' }))],
    ['slot diverso', ok(201, creata({ idDisponibilita: 11 }))],
    ['id mancante', ok(201, creata({ idPrenotazione: null }))],
    ['orario non valido', ok(201, creata({ dataOraInizio: 'domani' }))],
    ['elenco', ok(201, [creata()])],
  ])('successo malformato: %s', (_, result) => {
    expect(() => parsePrenotazioneCreata(result, 10)).toThrow(ProtocolError)
  })

  it('annullamento: 200, stessa prenotazione, ANNULLATA', () => {
    expect(parseAnnullamento(ok(200, { idPrenotazione: 12, stato: 'ANNULLATA' }), 12)).toEqual({ idPrenotazione: 12, stato: 'ANNULLATA' })
    expect(() => parseAnnullamento(ok(200, { idPrenotazione: 13, stato: 'ANNULLATA' }), 12)).toThrow(ProtocolError)
    expect(() => parseAnnullamento(ok(200, { idPrenotazione: 12, stato: 'RICHIESTA' }), 12)).toThrow(ProtocolError)
    expect(() => parseAnnullamento(ok(204, undefined), 12)).toThrow(ProtocolError)
  })
})

describe('richiesta di appuntamento (store)', () => {
  it('payload esatto con identità dalla sessione, CSRF e 201 RICHIESTA', async () => {
    const { store } = await autenticata()
    net.on(CREA, jsonResponse(201, creata()))
    const esito = await store.richiedi({ ...SLOT, idPaziente: 99 })
    expect(esito.kind).toBe('inviata')
    expect(esito.prenotazione.idPrenotazione).toBe(50)
    const [call] = net.callsTo(CREA)
    expect(corpo(call)).toEqual({ idPaziente: 7, idDisponibilita: 10 })
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(store.state.richiesteIncerte).toEqual([])
  })

  it('doppio invio: una sola POST, il secondo tentativo è rifiutato localmente', async () => {
    const { store } = await autenticata()
    const trattenuta = net.trattieni(CREA)
    const primo = store.richiedi(SLOT)
    expect(await store.richiedi(SLOT)).toEqual({ kind: 'occupato' })
    expect(await store.richiedi({ ...SLOT, idDisponibilita: 11 })).toEqual({ kind: 'occupato' })
    trattenuta.resolve(jsonResponse(201, creata()))
    expect((await primo).kind).toBe('inviata')
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it.each([
    [409, 'conflitto'], [404, 'non-trovata'], [400, 'non-valida'], [403, 'rifiutata'],
  ])('%i: esito certo %s, nessun blocco', async (status, kind) => {
    const { store } = await autenticata()
    net.on(CREA, jsonResponse(status, { error: 'x' }))
    expect((await store.richiedi(SLOT)).kind).toBe(kind)
    expect(store.richiestaBloccata(10)).toBe(false)
  })

  it('401: sessione persa, esito certo', async () => {
    const { session, store } = await autenticata()
    net.on(CREA, unauthorized())
    expect((await store.richiedi(SLOT)).kind).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(store.state.richiesteIncerte).toEqual([])
  })

  it.each([
    ['rete', 'rete'],
    ['500', jsonResponse(500, { error: 'x' })],
    ['503', jsonResponse(503, { error: 'x' })],
    ['200 invece di 201', jsonResponse(200, creata())],
    ['201 malformato', jsonResponse(201, creata({ stato: 'CONFERMATA' }))],
    ['201 non JSON', () => new Response('ok', { status: 201, headers: { 'Content-Type': 'text/plain' } })],
  ])('%s: esito incerto, nessun nuovo invio automatico, reinvio bloccato', async (_, risposta) => {
    const { store } = await autenticata()
    net.on(CREA, risposta)
    expect((await store.richiedi(SLOT)).kind).toBe('incerto')
    expect(store.richiestaBloccata(10)).toBe(true)
    expect(store.state.richiesteIncerte[0]).toMatchObject({ idDisponibilita: 10, verifica: null })
    expect(await store.richiedi(SLOT)).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(CREA)).toHaveLength(1)
    // Un altro slot resta richiedibile.
    net.on(CREA, jsonResponse(201, creata({ idDisponibilita: 11 })))
    expect((await store.richiedi({ ...SLOT, idDisponibilita: 11 })).kind).toBe('inviata')
  })

  it('passwordUncertain: nessuna POST', async () => {
    const { session, store } = await autenticata()
    net.on(PWD, jsonResponse(202, { message: 'ok', nuovoAccessoRichiesto: false }))
    expect((await session.changePassword('a', 'b')).kind).toBe('incerto')
    expect(await store.richiedi(SLOT)).toEqual({ kind: 'password-incerta' })
    expect(net.callsTo(CREA)).toHaveLength(0)
  })

  it('account senza profilo paziente: nessuna POST', async () => {
    const { store } = await autenticata(utente({ paziente: false, medico: true }))
    expect((await store.richiedi(SLOT)).kind).toBe('sessione-assente')
    expect(net.callsTo(CREA)).toHaveLength(0)
  })
})

describe('verifica manuale di una richiesta incerta', () => {
  async function incerta() {
    const ctx = await autenticata()
    net.on(CREA, 'rete')
    expect((await ctx.store.richiedi(SLOT)).kind).toBe('incerto')
    return ctx
  }

  it('trovata attiva per lo stesso slot: incertezza risolta, nessuna nuova POST', async () => {
    const { store } = await incerta()
    net.on(lista(), jsonResponse(200, [appuntamento(30, { idDisponibilita: 99 }), appuntamento(31, { idDisponibilita: 10 })]))
    const esito = await store.verificaRichiesta(10)
    expect(esito.kind).toBe('trovata')
    expect(esito.prenotazione.idPrenotazione).toBe(31)
    expect(store.state.richiesteIncerte).toEqual([])
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('assente: non prova il fallimento; il reinvio torna possibile solo su scelta esplicita', async () => {
    const { store } = await incerta()
    net.on(lista(), jsonResponse(200, [appuntamento(30, { idDisponibilita: 99 })]))
    expect((await store.verificaRichiesta(10)).kind).toBe('assente')
    expect(store.richiestaIncerta(10).verifica).toBe('assente')
    expect(store.richiestaBloccata(10)).toBe(false)
    expect(net.callsTo(CREA)).toHaveLength(1)
    net.on(CREA, jsonResponse(409, { error: 'Disponibilità già occupata.' }))
    expect((await store.richiedi(SLOT)).kind).toBe('conflitto')
    expect(net.callsTo(CREA)).toHaveLength(2)
  })

  it('solo prenotazioni annullate o rifiutate per lo slot', async () => {
    const { store } = await incerta()
    net.on(lista(), jsonResponse(200, [appuntamento(30, { stato: 'ANNULLATA' }), appuntamento(31, { stato: 'RIFIUTATA' })]))
    expect((await store.verificaRichiesta(10)).kind).toBe('solo-chiuse')
    expect(store.richiestaBloccata(10)).toBe(false)
  })

  it('verifica fallita: il blocco resta', async () => {
    const { store } = await incerta()
    net.on(lista(), 'rete')
    expect((await store.verificaRichiesta(10)).kind).toBe('errore')
    expect(store.richiestaBloccata(10)).toBe(true)
    net.on(lista(), jsonResponse(200, [{ idPrenotazione: 'x' }]))
    expect((await store.verificaRichiesta(10)).kind).toBe('errore')
    expect(store.richiestaBloccata(10)).toBe(true)
  })

  it('la lettura usa l\'elenco senza filtri e l\'identità corrente, mai una GET singola', async () => {
    const { store } = await incerta()
    net.on(lista(), jsonResponse(200, []))
    await store.verificaRichiesta(10)
    expect(net.calls.filter((c) => c.method === 'GET' && c.url.startsWith('/api/prenotazioni')).map((c) => c.url))
      .toEqual(['/api/prenotazioni/paziente?idPaziente=7'])
  })
})

describe('incertezze, identità e sessione', () => {
  async function conIncertezza() {
    const ctx = await autenticata()
    net.on(CREA, 'rete')
    await ctx.store.richiedi(SLOT)
    expect(ctx.store.richiestaBloccata(10)).toBe(true)
    return ctx
  }

  it('rotazione della sessione dello stesso utente: il blocco resta', async () => {
    const { session, store } = await conIncertezza()
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    expect(store.richiestaBloccata(10)).toBe(true)
    expect(await store.richiedi(SLOT)).toEqual({ kind: 'bloccata' })
  })

  it('cambio di epoca durante la POST: esito incerto, non successo', async () => {
    const { session, store } = await autenticata()
    const trattenuta = net.trattieni(CREA)
    const invio = store.richiedi(SLOT)
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    trattenuta.resolve(jsonResponse(201, creata()))
    expect((await invio).kind).toBe('incerto')
    expect(store.richiestaBloccata(10)).toBe(true)
  })

  it('sessione scaduta e nuovo accesso dello stesso utente: il blocco resta', async () => {
    const { session, store } = await conIncertezza()
    net.on(lista(), unauthorized())
    await store.verificaRichiesta(10)
    expect(session.state.status).toBe(STATO.ANONIMO)
    net.on(LOGIN, meOk(utente(), CSRF_B))
    expect((await session.login('paziente@example.test', 'x')).ok).toBe(true)
    expect(store.richiestaBloccata(10)).toBe(true)
  })

  it('sessione scaduta e accesso di un altro utente: incertezze eliminate', async () => {
    const { session, store } = await conIncertezza()
    net.on(lista(), unauthorized())
    await store.verificaRichiesta(10)
    net.on(LOGIN, meOk(utente({ idUtente: 8, nome: 'Bruno' }), CSRF_B))
    await session.login('altro@example.test', 'x')
    expect(store.state.owner).toBe(8)
    expect(store.state.richiesteIncerte).toEqual([])
  })

  it('logout concluso: incertezze eliminate; un esito tardivo non tocca il nuovo utente', async () => {
    const { session, store } = await autenticata()
    const trattenuta = net.trattieni(CREA)
    const invio = store.richiedi(SLOT)
    net.on(LOGOUT, new Response(null, { status: 204 }))
    expect(await session.logout()).toBe('confermato')
    expect(store.state.owner).toBeNull()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    trattenuta.resolve(jsonResponse(201, creata()))
    expect((await invio).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, invioInCorso: null, richiesteIncerte: [] })
  })

  it('logout non confermato: le incertezze restano fino alla conclusione', async () => {
    const { session, store } = await conIncertezza()
    net.on(LOGOUT, 'rete')
    expect(await session.logout()).toBe('non-confermato')
    expect(store.state.richiesteIncerte).toHaveLength(1)
    net.on(ME, unauthorized())
    expect(await session.recoverLogout()).toBe('sessione-assente')
    expect(store.state.richiesteIncerte).toEqual([])
  })
})

describe('annullamento (store)', () => {
  const pren = (stato = 'RICHIESTA') => parseAppuntamento(appuntamento(12, { stato }))

  it('payload con identità della sessione e statoAtteso della risorsa letta', async () => {
    const { store } = await autenticata()
    net.on(annulla(12), jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    expect((await store.annulla(pren('CONFERMATA'))).kind).toBe('annullata')
    const [call] = net.callsTo(annulla(12))
    expect(corpo(call)).toEqual({ idPaziente: 7, statoAtteso: 'CONFERMATA' })
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
  })

  it('stati non supportati: nessuna POST', async () => {
    const { store } = await autenticata()
    for (const stato of ['ANNULLATA', 'RIFIUTATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA']) {
      expect((await store.annulla(pren(stato))).kind).toBe('non-annullabile')
    }
    expect(net.calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('409: conflitto certo, nessun blocco e nessuna ripetizione', async () => {
    const { store } = await autenticata()
    net.on(annulla(12), jsonResponse(409, { error: 'Pagamenti associati impediscono l\'annullamento.' }))
    expect((await store.annulla(pren())).kind).toBe('conflitto')
    expect(store.annullamentoBloccato(12)).toBe(false)
    expect(net.callsTo(annulla(12))).toHaveLength(1)
  })

  it.each([
    ['rete', 'rete'],
    ['500', jsonResponse(500, { error: 'x' })],
    ['200 malformato', jsonResponse(200, { idPrenotazione: 12, stato: 'RICHIESTA' })],
  ])('%s: incerto, reinvio bloccato fino alla verifica manuale', async (_, risposta) => {
    const { store } = await autenticata()
    net.on(annulla(12), risposta)
    expect((await store.annulla(pren())).kind).toBe('incerto')
    expect(store.annullamentoBloccato(12)).toBe(true)
    expect(await store.annulla(pren())).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(annulla(12))).toHaveLength(1)
    store.annullamentoVerificato(12, 8)
    expect(store.annullamentoBloccato(12)).toBe(true)
    store.annullamentoVerificato(12, 7)
    expect(store.annullamentoBloccato(12)).toBe(false)
  })

  it('doppio invio dello stesso annullamento: una sola POST', async () => {
    const { store } = await autenticata()
    const trattenuta = net.trattieni(annulla(12))
    const primo = store.annulla(pren())
    expect(await store.annulla(pren())).toEqual({ kind: 'occupato' })
    trattenuta.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'ANNULLATA' }))
    expect((await primo).kind).toBe('annullata')
    expect(net.callsTo(annulla(12))).toHaveLength(1)
  })

  it('passwordUncertain: nessuna POST di annullamento', async () => {
    const { session, store } = await autenticata()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect((await store.annulla(pren())).kind).toBe('password-incerta')
    expect(net.callsTo(annulla(12))).toHaveLength(0)
  })
})
