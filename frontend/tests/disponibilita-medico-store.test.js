import { beforeEach, describe, expect, it } from 'vitest'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { disponibilitaStore } from '../src/medico/disponibilita.js'
import { medicoStore } from '../src/medico/store.js'
import { createSession } from '../src/session/session.js'
import { CREA, ELENCO, ELIMINA, MEDICO, ORARIO, configurato, medico, serie, slot, vuota } from './disponibilita-medico-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const s = (d) => parseDisponibilita(d, MEDICO)
const errore = (status, error = 'x') => jsonResponse(status, { error })
const A = '2026-11-02T09:00:00'
const B = '2026-11-02T12:00:00'

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia(user = medico()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: disponibilitaStore(session) }
}
/** Lettura «della vista» con ticket preso ora, applicata quando lo decide il test. */
const lettura = (store) => {
  const t = store.inizioLettura()
  return (voci) => store.applicaElenco(voci.map(s), t)
}
const corpo = (k) => JSON.parse(net.callsTo(k).at(-1).body)

describe('creazione', () => {
  it('singola e multipla: corpo con idMedico della sessione, 201 con copertura esatta', async () => {
    const { store } = await avvia()
    net.on(CREA, jsonResponse(201, serie(20, A, 3)))
    const esito = await store.crea('2026-11-02T09:00', '2026-11-02T12:00')
    expect(esito.kind).toBe('creata')
    expect(esito.slot).toHaveLength(3)
    expect(corpo(CREA)).toEqual({ idMedico: MEDICO, dataOraInizio: A, dataOraFine: B })
    net.on(CREA, jsonResponse(201, serie(30, '2026-11-03T09:00:00', 1)))
    expect((await store.crea('2026-11-03T09:00', '2026-11-03T10:00')).kind).toBe('creata')
  })

  it('limiti locali prima di fetch; 400 del server certo, con il suo messaggio', async () => {
    const { store } = await avvia()
    expect(await store.crea(A, '2026-11-02T10:30:00')).toEqual({ kind: 'non-valida' })
    // 1001 slot dal 2 novembre alle 09:00.
    expect(await store.crea(A, '2026-12-14T02:00:00')).toEqual({ kind: 'non-valida' })
    expect(net.callsTo(CREA)).toHaveLength(0)
    net.on(CREA, errore(400, "L'inizio della disponibilità deve essere futuro."))
    expect(await store.crea(A, B)).toMatchObject({ kind: 'non-valida', serverMessage: "L'inizio della disponibilità deve essere futuro." })
    expect(store.creazioneBloccata({ inizio: A, fine: B })).toBe(false)
  })

  it('rete, 5xx e 201 non conforme: incerti, intervalli sovrapposti bloccati, indipendenti liberi', async () => {
    const { store } = await avvia()
    net.on(CREA, 'rete')
    expect((await store.crea(A, B)).kind).toBe('incerto')
    expect(await store.crea('2026-11-02T11:00:00', '2026-11-02T13:00:00')).toEqual({ kind: 'bloccata' })
    net.on(CREA, errore(500), jsonResponse(201, serie(40, '2026-11-04T09:00:00', 1)))
    expect((await store.crea('2026-11-03T09:00:00', '2026-11-03T10:00:00')).kind).toBe('incerto')
    expect((await store.crea('2026-11-04T09:00:00', '2026-11-04T11:00:00')).kind).toBe('incerto')
    expect(net.callsTo(CREA)).toHaveLength(3)
  })

  it('creazione in volo: un secondo invio sovrapposto rifiutato prima di fetch', async () => {
    const { store } = await avvia()
    const h = net.trattieni(CREA)
    const prima = store.crea(A, B)
    expect(await store.crea(A, B)).toEqual({ kind: 'bloccata' })
    h.resolve(jsonResponse(201, serie(20, A, 3)))
    expect((await prima).kind).toBe('creata')
    expect(net.callsTo(CREA)).toHaveLength(1)
    // Terminato l'invio, l'intervallo non resta bloccato (stato reattivo confrontato per orari).
    expect(store.state.creazioniInCorso).toEqual([])
    expect(store.creazioneBloccata({ inizio: A, fine: B })).toBe(false)
  })

  async function incerta(store, noti = []) {
    lettura(store)(noti)
    net.on(CREA, 'rete')
    await store.crea(A, B)
    return store.state.creazioniIncerte.at(-1).chiave
  }

  it('verifica completa: tutti gli slot attesi e nuovi; incertezza risolta', async () => {
    const { store } = await avvia()
    const chiave = await incerta(store)
    net.on(ELENCO, jsonResponse(200, serie(20, A, 3)))
    expect(await store.verificaCreazione(chiave)).toEqual({ kind: 'verificata', esito: 'completa' })
    expect(store.state.creazioniIncerte).toEqual([])
  })

  it('verifica parziale: slot preesistenti noti, copertura incompleta o sovrapposizioni non conclusivi', async () => {
    const { store } = await avvia()
    // Tutti gli orari presenti ma uno era già noto prima del tentativo.
    const chiave = await incerta(store, [slot(20, A)])
    net.on(ELENCO, jsonResponse(200, serie(20, A, 3)))
    expect((await store.verificaCreazione(chiave)).esito).toBe('parziale')
    expect(store.creazioneBloccata({ inizio: A, fine: B })).toBe(true)
    // Copertura incompleta.
    net.on(ELENCO, jsonResponse(200, serie(21, '2026-11-02T10:00:00', 2)))
    expect((await store.verificaCreazione(chiave)).esito).toBe('parziale')
    // Slot sovrapposto non allineato.
    net.on(ELENCO, jsonResponse(200, [slot(50, '2026-11-02T08:30:00')]))
    expect((await store.verificaCreazione(chiave)).esito).toBe('parziale')
    expect(net.callsTo(CREA)).toHaveLength(1)
  })

  it('verifica assente: non prova il fallimento; nuovo invio solo esplicito, poi voce rimovibile', async () => {
    const { store } = await avvia()
    const chiave = await incerta(store)
    net.on(ELENCO, jsonResponse(200, [slot(9, '2026-11-02T13:00:00')]))
    expect((await store.verificaCreazione(chiave)).esito).toBe('assente')
    expect(store.creazioneBloccata({ inizio: A, fine: B })).toBe(false)
    net.on(CREA, jsonResponse(201, serie(20, A, 3)))
    expect((await store.crea(A, B)).kind).toBe('creata')
    store.chiudiCreazioneIncerta(chiave, store.state.owner)
    expect(store.state.creazioniIncerte).toEqual([])
  })

  it('verifica vecchia (assente) contro lettura recente (completa) applicata prima: vale la recente', async () => {
    const { store } = await avvia()
    const chiave = await incerta(store)
    const h = net.trattieni(ELENCO)
    const verifica = store.verificaCreazione(chiave)
    lettura(store)(serie(20, A, 3))
    h.resolve(jsonResponse(200, []))
    expect(await verifica).toEqual({ kind: 'verificata', esito: 'completa' })
  })

  it('ordine inverso: lettura vecchia applicata dopo la verifica non la contraddice', async () => {
    const { store } = await avvia()
    const chiave = await incerta(store)
    const vecchia = lettura(store)
    net.on(ELENCO, jsonResponse(200, serie(20, A, 3)))
    expect((await store.verificaCreazione(chiave)).esito).toBe('completa')
    expect(vecchia([])).toBe(false)
    expect(store.state.elenco.map((x) => x.idDisponibilita)).toEqual([20, 21, 22])
  })

  it('esito certo successivo all\'avvio della verifica: esito neutro, blocco mantenuto, verifica ripetibile', async () => {
    const { store } = await avvia()
    const chiave = await incerta(store)
    const h = net.trattieni(ELENCO)
    const verifica = store.verificaCreazione(chiave)
    net.on(ELIMINA(9), vuota())
    expect((await store.elimina(s(slot(9, '2026-11-05T09:00:00')))).kind).toBe('eliminata')
    h.resolve(jsonResponse(200, serie(20, A, 3)))
    expect(await verifica).toEqual({ kind: 'superata' })
    expect(store.creazioneBloccata({ inizio: A, fine: B })).toBe(true)
    net.on(ELENCO, jsonResponse(200, serie(20, A, 3)))
    expect((await store.verificaCreazione(chiave)).esito).toBe('completa')
  })
})

describe('modifica dell\'orario', () => {
  const s7 = () => s(slot(7, '2026-11-02T09:00:00'))

  it('60 minuti esatti con idMedico della sessione; slot configurato o orario invariato rifiutati prima di fetch', async () => {
    const { store } = await avvia()
    net.on(ORARIO(7), jsonResponse(200, slot(7, '2026-11-03T08:00:00')))
    const esito = await store.modificaOrario(s7(), '2026-11-03T08:00')
    expect(esito.kind).toBe('modificata')
    expect(corpo(ORARIO(7))).toEqual({ idMedico: MEDICO, dataOraInizio: '2026-11-03T08:00:00', dataOraFine: '2026-11-03T09:00:00' })
    expect(await store.modificaOrario(s(configurato(8, '2026-11-02T10:00:00')), '2026-11-03T10:00')).toEqual({ kind: 'configurata' })
    expect(await store.modificaOrario(s(slot(9, '2026-11-02T11:00:00', { idStanza: 3, nomeStanza: 'A' })), '2026-11-03T10:00')).toEqual({ kind: 'configurata' })
    expect(await store.modificaOrario(s7(), '2026-11-02T09:00')).toEqual({ kind: 'invariata' })
    expect(await store.modificaOrario(s7(), 'x')).toEqual({ kind: 'non-valida' })
    expect(net.calls.filter((c) => c.method === 'PUT')).toHaveLength(1)
  })

  it('409 certo senza blocco; incerto con blocco dello slot, altri slot liberi', async () => {
    const { store } = await avvia()
    net.on(ORARIO(7), errore(409, 'Orario sovrapposto a un\'altra disponibilità.'))
    expect(await store.modificaOrario(s7(), '2026-11-03T08:00')).toMatchObject({ kind: 'conflitto', serverMessage: "Orario sovrapposto a un'altra disponibilità." })
    expect(store.slotBloccato(7)).toBe(false)
    net.on(ORARIO(7), jsonResponse(200, slot(7, '2026-11-03T09:00:00')))
    expect((await store.modificaOrario(s7(), '2026-11-03T08:00')).kind).toBe('incerto')
    expect(store.slotBloccato(7)).toBe(true)
    expect(await store.elimina(s7())).toEqual({ kind: 'bloccata' })
    net.on(ELIMINA(8), vuota())
    expect((await store.elimina(s(slot(8, '2026-11-02T10:00:00')))).kind).toBe('eliminata')
  })

  it('operazioni incompatibili sullo stesso slot in volo: rifiutate prima di fetch', async () => {
    const { store } = await avvia()
    const h = net.trattieni(ORARIO(7))
    const modifica = store.modificaOrario(s7(), '2026-11-03T08:00')
    expect(await store.elimina(s7())).toEqual({ kind: 'occupato' })
    expect(await store.modificaOrario(s7(), '2026-11-03T10:00')).toEqual({ kind: 'occupato' })
    h.resolve(jsonResponse(200, slot(7, '2026-11-03T08:00:00')))
    expect((await modifica).kind).toBe('modificata')
    expect(net.calls.filter((c) => c.url.includes('/7'))).toHaveLength(1)
  })

  it('verifica: nuovo orario, originale, altro orario, assente come riscontri correnti', async () => {
    const { store } = await avvia()
    const casi = [[slot(7, '2026-11-03T08:00:00'), 'nuovo-orario', false], [slot(7, '2026-11-02T09:00:00'), 'originale', false],
      [slot(7, '2026-11-04T08:00:00'), 'altro-orario', false], [null, 'assente', true]]
    for (const [corrente, esito, bloccato] of casi) {
      net.on(ORARIO(7), 'rete')
      await store.modificaOrario(s7(), '2026-11-03T08:00')
      net.on(ELENCO, jsonResponse(200, corrente ? [corrente] : []))
      expect((await store.verificaSlot(7)).esito).toBe(esito)
      expect(store.slotBloccato(7)).toBe(false)
      expect(store.slotIncerto(7) !== null).toBe(bloccato)
      store.chiudiSlotIncerto(7, store.state.owner)
    }
  })
})

describe('eliminazione', () => {
  const s7 = () => s(slot(7, '2026-11-02T09:00:00'))

  it('204 senza corpo; 200 con corpo è non conforme (incerto); 409 e 404 certi', async () => {
    const { store } = await avvia()
    net.on(ELIMINA(7), vuota())
    expect((await store.elimina(s7())).kind).toBe('eliminata')
    net.on(ELIMINA(8), jsonResponse(200, {}))
    expect((await store.elimina(s(slot(8, '2026-11-02T10:00:00')))).kind).toBe('incerto')
    net.on(ELIMINA(9), errore(409, 'Disponibilità con prenotazioni associate.'))
    expect(await store.elimina(s(slot(9, '2026-11-02T11:00:00')))).toMatchObject({ kind: 'conflitto', serverMessage: 'Disponibilità con prenotazioni associate.' })
    net.on(ELIMINA(10), errore(404))
    expect((await store.elimina(s(slot(10, '2026-11-02T12:00:00')))).kind).toBe('non-trovata')
    expect(store.slotBloccato(9) || store.slotBloccato(10)).toBe(false)
  })

  it('verifica: presente (non eseguita) oppure assente (non attribuita); nessun fallimento certo dichiarato', async () => {
    const { store } = await avvia()
    net.on(ELIMINA(7), 'rete')
    await store.elimina(s7())
    net.on(ELENCO, jsonResponse(200, [slot(7, '2026-11-02T09:00:00')]))
    expect((await store.verificaSlot(7)).esito).toBe('originale')
    expect(store.slotIncerto(7)).toBeNull()
    net.on(ELIMINA(7), errore(502))
    await store.elimina(s7())
    net.on(ELENCO, jsonResponse(200, []))
    expect((await store.verificaSlot(7)).esito).toBe('assente')
  })
})

describe('sessione, identità e isolamento', () => {
  it('password incerta e profilo senza medico: rifiuto prima di fetch', async () => {
    const { session, store } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await store.crea(A, B)).toEqual({ kind: 'password-incerta' })
    expect(await store.elimina(s(slot(7, A)))).toEqual({ kind: 'password-incerta' })
    const altro = await avvia(utente())
    expect(await altro.store.crea(A, B)).toEqual({ kind: 'sessione-assente' })
    expect(net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth'))).toHaveLength(0)
  })

  it('rotazione: incertezze conservate; verifica attraversata dalla rotazione ignorata e ripetibile', async () => {
    const { session, store } = await avvia()
    net.on(ELIMINA(7), 'rete')
    await store.elimina(s(slot(7, A)))
    const h = net.trattieni(ELENCO)
    const verifica = store.verificaSlot(7)
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    h.resolve(jsonResponse(200, []))
    expect((await verifica).kind).toBe('obsoleto')
    expect(store.slotIncerto(7)).toMatchObject({ verifica: null })
    net.on(ELENCO, jsonResponse(200, []))
    expect((await store.verificaSlot(7)).esito).toBe('assente')
  })

  it('logout e cambio identità: tutto svuotato, esiti tardivi scartati, ticket precedenti ignorati', async () => {
    const { session, store } = await avvia()
    net.on(CREA, 'rete')
    await store.crea(A, B)
    const vecchia = lettura(store)
    const h = net.trattieni(ORARIO(7))
    const tardiva = store.modificaOrario(s(slot(7, A)), '2026-11-03T08:00')
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(medico({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, slot(7, '2026-11-03T08:00:00')))
    expect((await tardiva).kind).toBe('obsoleto')
    expect(vecchia([slot(1, A)])).toBe(false)
    expect(store.state).toMatchObject({ owner: 8, creazioniIncerte: [], slotIncerti: [], elenco: null })
  })

  it('store separato dall\'agenda: nessun blocco condiviso', async () => {
    const { session, store } = await avvia()
    net.on(ELIMINA(7), 'rete')
    await store.elimina(s(slot(7, A)))
    const agendaStore = medicoStore(session)
    expect(agendaStore).not.toBe(store)
    expect(agendaStore.state.visiteIncerte).toEqual([])
    expect(JSON.stringify(agendaStore.state)).not.toContain('slotIncerti')
  })
})
