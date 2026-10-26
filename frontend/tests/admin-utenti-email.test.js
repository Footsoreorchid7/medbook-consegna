import { beforeEach, describe, expect, it } from 'vitest'
import { emailConfronto, parseAccount, parseCreazione, parseUtenteAmministrativo } from '../src/api/utenti.js'
import { TESTO_INCOERENTE } from '../src/admin/formato.js'
import { adminStore, chiaveEmail } from '../src/admin/store.js'
import { createSession } from '../src/session/session.js'
import { CREA, PASSWORD, RICERCA, account, admin, amministrativo, pagina } from './admin-utenti-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

// Normalizzazione email e coerenza fra riscontri per ID ed email.

const ME = 'GET /api/auth/me'
const DATI = Object.freeze({ nome: 'Nina', cognome: 'Nuova', email: 'nina@example.test', ruolo: 'OPERATORE_DESK' })
const K = 'email:nina@example.test'
const a = (id, extra) => parseUtenteAmministrativo(amministrativo(id, extra))
const g = (id, extra) => parseAccount(account(id, extra))
const pag = (righe, prossimo = null) => ({ risultati: righe, prossimoDopoId: prossimo })
const qEmail = (dopo = 0) => RICERCA(`email=nina%40example.test${dopo ? `&dopoId=${dopo}` : ''}&limite=50`)

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia() {
  net.on(ME, meOk(admin()))
  const session = createSession()
  await session.ensureBootstrap()
  return adminStore(session)
}
async function creaIncerta(store) {
  net.on(CREA, 'rete')
  expect((await store.crea(DATI, PASSWORD)).kind).toBe('incerto')
}
/** Verifica A: la ricerca per email trova l'utente 20 con l'email del tentativo. */
async function letturaA(store, email = 'nina@example.test') {
  net.on(qEmail(), jsonResponse(200, pagina([amministrativo(20, { email })])))
  const l = await store.leggiVerifica(K)
  expect(l).toMatchObject({ kind: 'letta', conclusiva: true })
  return l
}

describe('normalizzazione unica delle email', () => {
  it('stessa forma per chiavi e confronti: spazi esterni (come strip) e minuscolo indipendente dalla lingua', () => {
    expect(emailConfronto('  NINA@Example.TEST ')).toBe('nina@example.test')
    expect(chiaveEmail('NINA@EXAMPLE.TEST')).toBe(chiaveEmail(' nina@example.test '))
    expect(emailConfronto(' nina@x')).toBe(' nina@x')
  })

  it('creazione: risposta con email maiuscola corrisponde al tentativo minuscolo; payload invariato', async () => {
    expect(parseCreazione({ status: 201, hasBody: true, data: account(20, { nome: 'Nina', cognome: 'Nuova', email: 'NINA@EXAMPLE.TEST' }) }, DATI).email).toBe('NINA@EXAMPLE.TEST')
    const store = await avvia()
    net.on(CREA, jsonResponse(201, account(20, { nome: 'Nina', cognome: 'Nuova', email: 'NINA@EXAMPLE.TEST' })))
    expect((await store.crea(DATI, PASSWORD)).kind).toBe('eseguita')
    expect(JSON.parse(net.callsTo(CREA)[0].body).email).toBe('nina@example.test')
  })

  it('creazione incerta minuscola, ricerca con email maiuscola: corrispondenza trovata, nessuna falsa assenza', async () => {
    const store = await avvia()
    await creaIncerta(store)
    net.on(qEmail(), jsonResponse(200, pagina([amministrativo(20, { email: 'NINA@EXAMPLE.TEST' })])))
    const e = await store.verifica(K)
    expect(e).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 20, email: 'NINA@EXAMPLE.TEST' } })
  })

  it('corrispondenza maiuscola in una pagina non finale: la verifica si ferma lì, senza leggere altre pagine', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const righe = Array.from({ length: 50 }, (_, k) => amministrativo(k + 1, { email: k === 7 ? 'Nina@EXAMPLE.test' : `nina@example.test.z${k}` }))
    net.on(qEmail(), jsonResponse(200, pagina(righe, 50)))
    expect(await store.verifica(K)).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 8 } })
    expect(net.calls.filter((c) => c.url.startsWith('/api/utenti/ricerca'))).toHaveLength(1)
  })

  it('riga maiuscola in una risposta vecchia, collegamento già smentito: nessuna falsa assenza registrata', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const t1 = store.inizioLettura()
    store.registraRicerca({ idUtente: '4' }, 0, pag([a(4, { email: 'altra@example.test' })]), store.inizioLettura())
    store.registraRicerca({ email: 'NINA@' }, 0, pag([a(4, { email: 'NINA@EXAMPLE.TEST' })]), t1)
    expect(store.esitoVerifica(K, { kind: 'letta', ticket: t1, conclusiva: true })).toEqual({ kind: 'superata' })
  })

  it('prefissi con maiuscole diverse: presenza normalizzata; ricerca esaurita senza corrispondenza: assenza coperta', async () => {
    const store = await avvia()
    await creaIncerta(store)
    for (let i = 0; i < 10; i++) {
      net.on(qEmail(i * 50), jsonResponse(200, pagina(Array.from({ length: 50 }, (_, k) => amministrativo(i * 50 + k + 1, { email: `nina@example.test.z${k}` })), (i + 1) * 50)))
    }
    const l = await store.leggiVerifica(K)
    store.registraRicerca({ email: 'NINA@' }, 0, pag([a(4, { email: 'Nina@Example.TEST' })]), store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toMatchObject({ esito: 'presente', voce: { idUtente: 4 } })
    await creaIncerta(store)
    for (let i = 0; i < 10; i++) {
      net.on(qEmail(i * 50), jsonResponse(200, pagina(Array.from({ length: 50 }, (_, k) => amministrativo(i * 50 + k + 1, { email: `nina@example.test.z${k}` })), (i + 1) * 50)))
    }
    const l2 = await store.leggiVerifica(K)
    store.registraRicerca({ email: 'NINA@EX' }, 0, pag([a(5, { email: 'NINA@EXAMPLE.OTHER' })]), store.inizioLettura())
    expect(store.esitoVerifica(K, l2)).toMatchObject({ esito: 'assente', fonte: 'successiva' })
  })
})

describe('coerenza tra riscontro per email e riscontro corrente dello stesso ID', () => {
  it('stesso ID con email realmente cambiata in una lettura più recente: costruzione dell\'esito di A neutra, blocco mantenuto', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const l = await letturaA(store)
    store.registraElenco([g(20, { email: 'altra@example.test' })], store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toEqual({ kind: 'incoerente' })
    expect(store.bloccata(K)).toBe(true)
    expect(store.incerta(K).verifica).toBe('incoerente')
    // Nessuna assenza globale dedotta: l'esito non dichiara che l'email non esista.
    expect(TESTO_INCOERENTE).toContain('potrebbe appartenere ad altri account')
    expect(TESTO_INCOERENTE).not.toMatch(/non risulta applicata|nessun account/)
  })

  it('risposta A tardiva (avviata prima di B) non ricrea il collegamento vecchio', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const tA = store.inizioLettura()
    store.registraRicerca({ idUtente: '20' }, 0, pag([a(20, { email: 'altra@example.test' })]), store.inizioLettura())
    store.registraRicerca({ email: 'nina@' }, 0, pag([a(20, { email: 'nina@example.test' }), a(21, { email: 'nina@other.test' })], 21), tA)
    // Nessun riscontro per l'email: esito neutro ordinario, non una presenza ricreata.
    expect(store.esitoVerifica(K, { kind: 'letta', ticket: tA, conclusiva: true })).toEqual({ kind: 'superata' })
  })

  it('cambio del solo maiuscolo/minuscolo nella lettura più recente: nessuna falsa invalidazione', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const l = await letturaA(store)
    store.registraElenco([g(20, { email: 'NINA@Example.Test' })], store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 20 } })
  })

  it('vecchia email associata a un altro ID da un riscontro più recente: vale il nuovo account', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const l = await letturaA(store)
    store.registraElenco([g(20, { email: 'altra@example.test' })], store.inizioLettura())
    store.registraRicerca({ cognome: 'Pr' }, 0, pag([a(30, { email: 'Nina@Example.test' })]), store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 30 }, fonte: 'successiva' })
  })

  it('esito neutro seguito da recupero manuale riuscito, senza scritture', async () => {
    const store = await avvia()
    await creaIncerta(store)
    const l = await letturaA(store)
    store.registraRicerca({ idUtente: '20' }, 0, pag([]), store.inizioLettura())
    expect(store.esitoVerifica(K, l)).toEqual({ kind: 'incoerente' })
    net.on(qEmail(), jsonResponse(200, pagina([amministrativo(30, { email: 'nina@example.test' })])))
    expect(await store.verifica(K)).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idUtente: 30 } })
    expect(store.bloccata(K)).toBe(false)
    expect(net.callsTo(CREA)).toHaveLength(1)
  })
})
