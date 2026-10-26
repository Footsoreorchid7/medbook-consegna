import { beforeEach, describe, expect, it } from 'vitest'
import { parseMedicoDesk, parseRichiesta, parseRimborsoDesk, parseSegnalazione } from '../src/api/desk.js'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { deskStore } from '../src/desk/store.js'
import { createSession } from '../src/session/session.js'
import { configurato, slot } from './disponibilita-medico-fixtures.js'
import {
  CONFIGURA, DECIDI, DISPONIBILITA, OP, OPERATORE, RICHIESTE, RIMBORSI, SEGNALAZIONI, SOSTITUISCI, admin, decisione, desk, gestione,
  medicoDesk, richiesta, rimborsoDesk, segnalazione, segnalazioneRichiesta,
} from './desk-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const r = (id, extra) => parseRichiesta(richiesta(id, extra))
const sg = (d) => parseSegnalazione(d)
const errore = (status, error = 'x') => jsonResponse(status, { error })
const corpo = (k) => JSON.parse(net.callsTo(k).at(-1).body)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia(user = desk()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: deskStore(session) }
}

describe('richieste: conferma, rifiuto, annullamento', () => {
  it('payload con idOperatore della sessione; risposte validate', async () => {
    const { store } = await avvia()
    net.on(OP(12, 'conferma'), jsonResponse(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 4 })))
    expect((await store.decidiRichiesta('conferma', r(12))).kind).toBe('eseguita')
    expect(corpo(OP(12, 'conferma'))).toEqual({ idOperatore: OPERATORE })
    net.on(OP(13, 'annulla'), jsonResponse(200, gestione(13, 'ANNULLATA')))
    expect((await store.decidiRichiesta('annulla', r(13))).kind).toBe('eseguita')
    expect(corpo(OP(13, 'annulla'))).toEqual({ idOperatore: OPERATORE, statoAtteso: 'RICHIESTA' })
    net.on(OP(14, 'rifiuta'), jsonResponse(200, gestione(14, 'RIFIUTATA')))
    expect((await store.decidiRichiesta('rifiuta', r(14))).kind).toBe('eseguita')
  })

  it('conferma di una richiesta segnalata rifiutata prima di fetch; 409 certo senza blocco', async () => {
    const { store } = await avvia()
    expect(await store.decidiRichiesta('conferma', r(12, { indisponibilitaSegnalata: true }))).toEqual({ kind: 'segnalata' })
    net.on(OP(12, 'conferma'), errore(409, "L'appuntamento è già iniziato."))
    expect(await store.decidiRichiesta('conferma', r(12))).toMatchObject({ kind: 'conflitto', serverMessage: "L'appuntamento è già iniziato." })
    expect(store.bloccata('prenotazione:12')).toBe(false)
    expect(net.callsTo(OP(12, 'conferma'))).toHaveLength(1)
  })

  it('esito incerto: blocco della stessa prenotazione per ogni operazione e da ogni pagina; altre libere', async () => {
    const { store } = await avvia()
    net.on(OP(12, 'conferma'), jsonResponse(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 5 })))
    expect((await store.decidiRichiesta('conferma', r(12))).kind).toBe('incerto')
    expect(await store.decidiRichiesta('rifiuta', r(12))).toEqual({ kind: 'bloccata' })
    expect(await store.annullaSegnalata(sg(segnalazione(12)))).toEqual({ kind: 'bloccata' })
    expect(await store.sostituisci(sg(segnalazione(12)), 9)).toEqual({ kind: 'bloccata' })
    net.on(OP(13, 'rifiuta'), jsonResponse(200, gestione(13, 'RIFIUTATA')))
    expect((await store.decidiRichiesta('rifiuta', r(13))).kind).toBe('eseguita')
    expect(net.callsTo(OP(12, 'conferma'))).toHaveLength(1)
  })

  it('in volo: operazione incompatibile sulla stessa prenotazione rifiutata prima di fetch', async () => {
    const { store } = await avvia()
    const h = net.trattieni(OP(12, 'rifiuta'))
    const prima = store.decidiRichiesta('rifiuta', r(12))
    expect(await store.decidiRichiesta('annulla', r(12))).toEqual({ kind: 'occupato' })
    h.resolve(jsonResponse(200, gestione(12, 'RIFIUTATA')))
    expect((await prima).kind).toBe('eseguita')
    expect(scritture()).toBe(1)
  })

  it('verifica: ancora presente → non eseguita (sblocco); assente → non conclusiva, voce rimovibile', async () => {
    const { store } = await avvia()
    net.on(OP(12, 'rifiuta'), 'rete')
    await store.decidiRichiesta('rifiuta', r(12))
    net.on(RICHIESTE, jsonResponse(200, [richiesta(12)]))
    expect((await store.verifica('prenotazione:12')).esito).toBe('presente')
    expect(store.bloccata('prenotazione:12')).toBe(false)
    net.on(OP(12, 'rifiuta'), errore(503))
    await store.decidiRichiesta('rifiuta', r(12))
    net.on(RICHIESTE, jsonResponse(200, []))
    expect((await store.verifica('prenotazione:12')).esito).toBe('assente')
    // Nessuna deduzione: la voce resta tra gli esiti finché non viene rimossa.
    expect(store.incerta('prenotazione:12')).toMatchObject({ esito: 'assente' })
    store.chiudiIncerta('prenotazione:12', store.state.owner)
    expect(store.state.incerte).toEqual([])
    // La verifica usa l'elenco completo, senza filtri.
    expect(net.calls.filter((c) => c.url.startsWith('/api/prenotazioni/richieste')).every((c) => c.url === '/api/prenotazioni/richieste')).toBe(true)
  })
})

describe('ordinamento delle letture', () => {
  it('lettura vecchia applicata dopo una recente: scartata', async () => {
    const { store } = await avvia()
    const vecchia = store.inizioLettura()
    const recente = store.inizioLettura()
    expect(store.applica('richieste', [], recente, true)).toBe(true)
    expect(store.applica('richieste', [], vecchia, true)).toBe(false)
  })

  it('verifica vecchia (presente) contro lettura completa recente (assente): vale la recente', async () => {
    const { store } = await avvia()
    net.on(OP(12, 'rifiuta'), 'rete')
    await store.decidiRichiesta('rifiuta', r(12))
    const h = net.trattieni(RICHIESTE)
    const verifica = store.verifica('prenotazione:12')
    store.applica('richieste', [], store.inizioLettura(), true)
    h.resolve(jsonResponse(200, [richiesta(12)]))
    expect((await verifica).esito).toBe('assente')
  })

  it('una lettura filtrata recente non vale come riscontro: esito neutro, verifica ripetibile', async () => {
    const { store } = await avvia()
    net.on(OP(12, 'rifiuta'), 'rete')
    await store.decidiRichiesta('rifiuta', r(12))
    const h = net.trattieni(RICHIESTE)
    const verifica = store.verifica('prenotazione:12')
    // Elenco filtrato (altro medico) senza la 12: non prova la sua assenza.
    store.applica('richieste', [], store.inizioLettura(), false)
    h.resolve(jsonResponse(200, []))
    expect(await verifica).toEqual({ kind: 'superata' })
    expect(store.bloccata('prenotazione:12')).toBe(true)
    net.on(RICHIESTE, jsonResponse(200, [richiesta(12)]))
    expect((await store.verifica('prenotazione:12')).esito).toBe('presente')
  })

  it('esito certo successivo all\'avvio di una lettura: la lettura è scartata', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    net.on(OP(13, 'rifiuta'), jsonResponse(200, gestione(13, 'RIFIUTATA')))
    await store.decidiRichiesta('rifiuta', r(13))
    expect(store.applica('richieste', [r(13)], t, true)).toBe(false)
    expect(store.applica('segnalazioni', [], t, true)).toBe(false)
    expect(store.applica('rimborsi', [], t, true)).toBe(true)
  })
})

describe('segnalazioni', () => {
  it('sostituzione: payload con stato e indisponibile della risorsa; RICHIESTA → titolare, CONFERMATA → effettivo', async () => {
    const { store } = await avvia()
    net.on(SOSTITUISCI(7), jsonResponse(200, gestione(7, 'CONFERMATA', { idMedicoEffettivo: 9, indisponibilitaSegnalata: false })))
    expect((await store.sostituisci(sg(segnalazioneRichiesta(7)), 9)).kind).toBe('eseguita')
    expect(corpo(SOSTITUISCI(7))).toEqual({ idOperatore: OPERATORE, idMedicoSostituto: 9, statoAtteso: 'RICHIESTA', idMedicoIndisponibile: 4 })
    const effettivo6 = segnalazione(8, { idMedicoEffettivo: 6, nomeMedicoEffettivo: 'Sara', cognomeMedicoEffettivo: 'S' })
    net.on(SOSTITUISCI(8), jsonResponse(200, gestione(8, 'CONFERMATA', { idMedicoEffettivo: 9, indisponibilitaSegnalata: false })))
    await store.sostituisci(sg(effettivo6), 9)
    expect(corpo(SOSTITUISCI(8))).toMatchObject({ statoAtteso: 'CONFERMATA', idMedicoIndisponibile: 6 })
    expect(await store.sostituisci(sg(effettivo6), 6)).toEqual({ kind: 'non-valida' })
    expect(net.callsTo(SOSTITUISCI(8))).toHaveLength(1)
  })

  it('annullamento con lo stato letto; incerto verificato sulle segnalazioni', async () => {
    const { store } = await avvia()
    net.on(OP(7, 'annulla'), 'rete')
    expect((await store.annullaSegnalata(sg(segnalazione(7)))).kind).toBe('incerto')
    expect(corpo(OP(7, 'annulla'))).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA' })
    net.on(SEGNALAZIONI, jsonResponse(200, []))
    expect((await store.verifica('prenotazione:7')).esito).toBe('assente')
  })
})

describe('rimborsi', () => {
  it('approvazione e rifiuto con corpo {}; operatore della risposta verificato', async () => {
    const { store } = await avvia()
    net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO')))
    expect((await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5)))).kind).toBe('eseguita')
    expect(corpo(DECIDI(5, 'approva'))).toEqual({})
    net.on(DECIDI(6, 'respingi'), jsonResponse(200, decisione(6, 'RESPINTO', { idOperatore: 99 })))
    expect((await store.decidiRimborso('respingi', parseRimborsoDesk(rimborsoDesk(6)))).kind).toBe('incerto')
    expect(await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(6)))).toEqual({ kind: 'bloccata' })
    net.on(RIMBORSI, jsonResponse(200, []))
    expect((await store.verifica('rimborso:6')).esito).toBe('assente')
  })
})

describe('configurazione', () => {
  const s9 = () => parseDisponibilita(slot(9, '2026-11-02T09:00:00'), 4)
  const m4 = parseMedicoDesk(medicoDesk(4))
  const p2 = { idPrestazione: 2, nome: 'Visita sintetica' }
  const st3 = { idStanza: 3, nome: 'Ambulatorio 1' }

  it('configurazione unica; slot configurato anche in parte rifiutato prima di fetch', async () => {
    const { store } = await avvia()
    net.on(CONFIGURA(9), jsonResponse(200, configurato(9, '2026-11-02T09:00:00')))
    expect((await store.configura(s9(), m4, p2, st3)).kind).toBe('eseguita')
    expect(corpo(CONFIGURA(9))).toEqual({ idPrestazione: 2, idStanza: 3 })
    expect(await store.configura(parseDisponibilita(slot(10, '2026-11-02T10:00:00', { idStanza: 3, nomeStanza: 'A' }), 4), m4, p2, st3)).toEqual({ kind: 'configurata' })
    expect(net.calls.filter((c) => c.method === 'PUT')).toHaveLength(1)
  })

  it('verifica dell\'incerto sull\'elenco del medico: come richiesto, diversa, non configurata, assente', async () => {
    const { store } = await avvia()
    const casi = [[configurato(9, '2026-11-02T09:00:00'), 'come-richiesto'], [configurato(9, '2026-11-02T09:00:00', { idStanza: 4, nomeStanza: 'B' }), 'diversa'],
      [slot(9, '2026-11-02T09:00:00'), 'non-configurata'], [null, 'assente']]
    for (const [corrente, esito] of casi) {
      net.on(CONFIGURA(9), 'rete')
      expect((await store.configura(s9(), m4, p2, st3)).kind).toBe('incerto')
      net.on(DISPONIBILITA(4), jsonResponse(200, corrente ? [corrente] : []))
      expect((await store.verifica('slot:9')).esito).toBe(esito)
      store.chiudiIncerta('slot:9', store.state.owner)
    }
  })
})

describe('permessi, sessione e identità', () => {
  it('amministratore: non è operatore desk, nessuna scrittura', async () => {
    const { store } = await avvia(admin())
    expect(store.operatoreCorrente()).toBeNull()
    expect(await store.decidiRichiesta('rifiuta', r(12))).toEqual({ kind: 'sessione-assente' })
    expect(await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5)))).toEqual({ kind: 'sessione-assente' })
    expect(scritture()).toBe(0)
  })

  it('password incerta: rifiuto prima di fetch', async () => {
    const { session, store } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await store.decidiRichiesta('rifiuta', r(12))).toEqual({ kind: 'password-incerta' })
    expect(scritture()).toBe(0)
  })

  it('rotazione: incertezze conservate; logout e nuova identità: svuotamento ed esiti tardivi scartati', async () => {
    const { session, store } = await avvia()
    net.on(OP(12, 'rifiuta'), 'rete')
    await store.decidiRichiesta('rifiuta', r(12))
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    expect(store.bloccata('prenotazione:12')).toBe(true)
    const h = net.trattieni(OP(13, 'rifiuta'))
    const tardiva = store.decidiRichiesta('rifiuta', r(13))
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(desk({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, gestione(13, 'RIFIUTATA')))
    expect((await tardiva).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, incerte: [], inCorso: [] })
  })
})
