import { beforeEach, describe, expect, it } from 'vitest'
import { parsePagamento } from '../src/api/pagamenti.js'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { parseRimborso } from '../src/api/rimborsi.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { STATO, createSession } from '../src/session/session.js'
import { confermata, fallito, pagamento, rimborso } from './pagamenti-rimborsi-fixtures.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

// Ordinamento dei riscontri condivisi: ordine di avvio delle letture, ultimo ticket applicato
// per ambito (anche negativo) e barriere degli esiti certi di scrittura. Dati sintetici.
const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const PAG = 'GET /api/prenotazioni/12/pagamenti'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'
const RIMB = 'GET /api/rimborsi/paziente'
const CHIEDI = 'POST /api/prenotazioni/12/rimborso'

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function autenticata() {
  net.on(ME, meOk(utente(), CSRF_A))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: prenotazioniStore(session) }
}

const pren = () => parseAppuntamento(confermata(12))
const ATTIVO = () => [parsePagamento(pagamento(9), 12)]
const NESSUNO = () => [parsePagamento(fallito(4), 12)]
const CON_RIMBORSO = () => [parseRimborso(rimborso(31, { idPrenotazione: 12, idPagamento: 5 }))]
const PAGATO5 = Object.freeze({ idPagamento: 5, importo: 80 })

describe('pagamenti: letture sovrapposte', () => {
  it('A poi B: B negativa applicata, A positiva tardiva ignorata (nessun blocco, nessuna lettura aggiuntiva)', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraStorico(12, NESSUNO(), b)
    store.registraStorico(12, ATTIVO(), a)
    expect(store.pagamentoBloccato(12)).toBe(false)
    // L'invio è possibile subito, senza altre letture.
    net.on(PAGA, jsonResponse(201, fallito(10)))
    expect((await store.pagaOnline(pren(), [])).kind).toBe('fallito')
  })

  it('A poi B: A positiva applicata, B negativa più recente la aggiorna', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraStorico(12, ATTIVO(), a)
    expect(store.pagamentoBloccato(12)).toBe(true)
    store.registraStorico(12, NESSUNO(), b)
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('A poi B: B positiva applicata, A negativa tardiva ignorata', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraStorico(12, ATTIVO(), b)
    store.registraStorico(12, NESSUNO(), a)
    expect(store.pagamentoBloccato(12)).toBe(true)
  })

  it('un risultato negativo conserva l\'ultimo ticket applicato anche senza blocco; applicare non avanza l\'ordine', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraStorico(12, NESSUNO(), b)
    expect(store.state.ambitiPagamento).toEqual([{ idPrenotazione: 12, attivo: false, applicato: b.n, barriera: 0 }])
    store.registraStorico(12, ATTIVO(), a)
    expect(store.state.ambitiPagamento[0]).toMatchObject({ attivo: false, applicato: b.n })
    // Nessun nuovo ordine attribuito alle risposte applicate.
    expect(store.inizioLettura().n).toBe(b.n + 1)
  })

  it('lettura avviata prima di un 201/PAGATO non annulla il riscontro, anche se risponde dopo', async () => {
    const { store } = await autenticata()
    const prima = store.inizioLettura()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    await store.pagaOnline(pren(), [])
    store.registraStorico(12, NESSUNO(), prima)
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('lettura avviata dopo il 201/PAGATO può aggiornare il riscontro (nessun blocco permanente)', async () => {
    const { store } = await autenticata()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    await store.pagaOnline(pren(), [])
    const dopo = store.inizioLettura()
    store.registraStorico(12, NESSUNO(), dopo)
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('verifica e lettura della vista sovrapposte: vince la lettura avviata per ultima', async () => {
    const { store } = await autenticata()
    net.on(PAGA, 'rete')
    await store.pagaOnline(pren(), [])
    const trattenuta = net.trattieni(PAG)
    const verifica = store.verificaPagamento(12)
    const vista = store.inizioLettura()
    store.registraStorico(12, NESSUNO(), vista)
    trattenuta.resolve(jsonResponse(200, [pagamento(9)]))
    // La risposta della verifica (avviata prima) è superata: non ricrea il riscontro condiviso sopra la
    // lettura più recente e non decide l'esito testuale, che resta neutro.
    expect(await verifica).toEqual({ kind: 'superata' })
    expect(store.pagamentoAttivoNoto(12)).toBe(false)
    expect(store.pagamentoIncerto(12)).toMatchObject({ verifica: 'superata', esito: null })
    // Il tentativo incerto richiede una nuova verifica manuale; una verifica applicata la conclude.
    expect(store.pagamentoBloccato(12)).toBe(true)
    net.on(PAG, jsonResponse(200, [fallito(4)]))
    expect(await store.verificaPagamento(12)).toMatchObject({ kind: 'verificata', esito: 'nuovo-fallito' })
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('ticket di identità o sessione invalidate ignorati', async () => {
    const { session, store } = await autenticata()
    // Rotazione della sessione dello stesso utente: nuova epoca.
    const primaRotazione = store.inizioLettura()
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    store.registraStorico(12, ATTIVO(), primaRotazione)
    expect(store.state.ambitiPagamento).toEqual([])
    // Sessione scaduta e nuovo accesso dello stesso utente.
    const primaScadenza = store.inizioLettura()
    net.on(PAG, unauthorized())
    await session.protectedRequest({ path: '/api/prenotazioni/12/pagamenti' }).catch(() => {})
    expect(session.state.status).toBe(STATO.ANONIMO)
    net.on(LOGIN, meOk(utente(), CSRF_B))
    await session.login('paziente@example.test', 'x')
    store.registraStorico(12, ATTIVO(), primaScadenza)
    expect(store.state.ambitiPagamento).toEqual([])
    // Logout e altro utente.
    const primaLogout = store.inizioLettura()
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    store.registraStorico(12, ATTIVO(), primaLogout)
    expect(store.state.ambitiPagamento).toEqual([])
    expect(store.pagamentoBloccato(12)).toBe(false)
  })
})

describe('rimborsi: letture sovrapposte', () => {
  it('A poi B: B negativa applicata, A positiva tardiva ignorata', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraRimborsi([], b)
    store.registraRimborsi(CON_RIMBORSO(), a)
    expect(store.rimborsoNoto(5)).toBe(false)
    expect(store.rimborsoBloccato(12)).toBe(false)
  })

  it('A poi B: A positiva applicata, B negativa più recente la aggiorna', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraRimborsi(CON_RIMBORSO(), a)
    expect(store.rimborsoNoto(5)).toBe(true)
    store.registraRimborsi([], b)
    expect(store.rimborsoNoto(5)).toBe(false)
  })

  it('A poi B: B positiva applicata, A negativa tardiva ignorata', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraRimborsi(CON_RIMBORSO(), b)
    store.registraRimborsi([], a)
    expect(store.rimborsoNoto(5)).toBe(true)
  })

  it('un elenco negativo conserva l\'ultimo ticket applicato, anche per pagamenti ancora senza ambito', async () => {
    const { store } = await autenticata()
    const a = store.inizioLettura()
    const b = store.inizioLettura()
    store.registraRimborsi([], b)
    expect(store.state.rimborsiApplicatoGlobale).toBe(b.n)
    store.registraRimborsi(CON_RIMBORSO(), a)
    expect(store.state.ambitiRimborso).toEqual([])
    expect(store.inizioLettura().n).toBe(b.n + 1)
  })

  it('lettura avviata prima di un 201 di rimborso non annulla il riscontro; una successiva sì', async () => {
    const { store } = await autenticata()
    const prima = store.inizioLettura()
    net.on(CHIEDI, jsonResponse(201, rimborso(31)))
    expect((await store.richiediRimborso(12, PAGATO5, 'Visita non erogata')).kind).toBe('richiesto')
    store.registraRimborsi([], prima)
    expect(store.rimborsoNoto(5)).toBe(true)
    expect(await store.richiediRimborso(12, PAGATO5, 'Visita non erogata')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
    store.registraRimborsi([], store.inizioLettura())
    expect(store.rimborsoNoto(5)).toBe(false)
  })

  it('riconciliazione per prenotazione: aggiorna solo gli ambiti coperti', async () => {
    const { store } = await autenticata()
    net.on(CHIEDI, 'rete')
    await store.richiediRimborso(12, PAGATO5, 'Motivo')
    // Elenco globale: richieste per le prenotazioni 12 (pagamento 5) e 14 (pagamento 8).
    store.registraRimborsi([...CON_RIMBORSO(), parseRimborso(rimborso(32, { idPrenotazione: 14, idPagamento: 8 }))], store.inizioLettura())
    expect([store.rimborsoNoto(5), store.rimborsoNoto(8)]).toEqual([true, true])
    // Verifica della 12 con un elenco più recente che non contiene alcuna richiesta.
    net.on(RIMB, jsonResponse(200, []))
    expect((await store.verificaRimborso(12)).kind).toBe('assente')
    expect(store.rimborsoNoto(5)).toBe(false)
    // La 14 non è coperta dalla riconciliazione della 12: il suo riscontro resta.
    expect(store.rimborsoNoto(8)).toBe(true)
  })

  it('riconciliazione per prenotazione avviata prima di un elenco globale già applicato: ignorata', async () => {
    const { store } = await autenticata()
    net.on(CHIEDI, 'rete')
    await store.richiediRimborso(12, PAGATO5, 'Motivo')
    const trattenuta = net.trattieni(RIMB)
    const verifica = store.verificaRimborso(12)
    store.registraRimborsi(CON_RIMBORSO(), store.inizioLettura())
    trattenuta.resolve(jsonResponse(200, []))
    await verifica
    expect(store.rimborsoNoto(5)).toBe(true)
    expect(await store.richiediRimborso(12, PAGATO5, 'Motivo')).toEqual({ kind: 'bloccata' })
  })

  it('ticket di un\'identità precedente ignorati', async () => {
    const { session, store } = await autenticata()
    const vecchio = store.inizioLettura()
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    store.registraRimborsi(CON_RIMBORSO(), vecchio)
    expect(store.state.ambitiRimborso).toEqual([])
    expect(store.state.rimborsiApplicatoGlobale).toBe(0)
  })
})
