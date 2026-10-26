import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { parsePrestazioneAdmin, parseStanza, validaPrestazione } from '../src/api/catalogoAdmin.js'
import { parseUtenteAmministrativo } from '../src/api/utenti.js'
import { PRESTAZIONI as AMB_PRESTAZIONI, STANZE as AMB_STANZE, adminStore } from '../src/admin/store.js'
import { createSession } from '../src/session/session.js'
import { CREA_PRESTAZIONE, CREA_STANZA, PRESTAZIONE, PRESTAZIONI, STANZA, STANZE, admin, desk, prestazioneAdmin, stanza } from './admin-cataloghi-fixtures.js'
import { amministrativo } from './admin-utenti-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const st = (id, extra) => parseStanza(stanza(id, extra))
const pr = (id, extra) => parsePrestazioneAdmin(prestazioneAdmin(id, extra))
const datiP = (f = {}) => validaPrestazione({ nome: 'Visita', descrizione: '', durata: '30', prezzo: '80,50', ...f }).dati
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length

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
  it('desk escluso; password incerta: nessuna scrittura', async () => {
    const { store } = await avvia(desk())
    expect(await store.creaStanza({ nome: 'Sala', descrizione: null })).toEqual({ kind: 'sessione-assente' })
    const { session, store: s2 } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await s2.aggiornaPrestazione(pr(2), datiP())).toEqual({ kind: 'password-incerta' })
    expect(scritture()).toBe(0)
  })
})

describe('stanze', () => {
  it('creazione certa con barriera sull\'elenco; 409 per nome duplicato con messaggio del server, senza blocco', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    net.on(CREA_STANZA, jsonResponse(201, stanza(9, { nome: 'Sala' })))
    expect((await store.creaStanza({ nome: 'Sala', descrizione: null })).kind).toBe('eseguita')
    expect(store.valida(AMB_STANZE, t)).toBe(false)
    net.on(CREA_STANZA, jsonResponse(409, { error: 'Nome stanza già presente.' }))
    expect(await store.creaStanza({ nome: 'Sala', descrizione: null })).toMatchObject({ kind: 'conflitto', serverMessage: 'Nome stanza già presente.' })
    expect(store.bloccata('stanza-nuova:sala')).toBe(false)
  })

  it('creazione incerta: reinvio dello stesso nome bloccato (anche con maiuscole diverse); verifica sull\'elenco completo', async () => {
    const { store } = await avvia()
    net.on(CREA_STANZA, 'rete')
    expect((await store.creaStanza({ nome: 'Sala A', descrizione: null })).kind).toBe('incerto')
    expect(await store.creaStanza({ nome: 'SALA A', descrizione: null })).toEqual({ kind: 'bloccata' })
    net.on(CREA_STANZA, jsonResponse(201, stanza(10, { nome: 'Sala B' })))
    expect((await store.creaStanza({ nome: 'Sala B', descrizione: null })).kind).toBe('eseguita')
    net.on(STANZE, jsonResponse(200, [stanza(7, { nome: 'Sala A' }), stanza(10, { nome: 'Sala B' })]))
    expect(await store.verifica('stanza-nuova:sala a')).toMatchObject({ kind: 'verificata', esito: 'presente', voce: { idStanza: 7 } })
    expect(store.bloccata('stanza-nuova:sala a')).toBe(false)
    net.on(CREA_STANZA, 'rete')
    await store.creaStanza({ nome: 'Sala C', descrizione: null })
    net.on(STANZE, jsonResponse(200, [stanza(7)]))
    expect((await store.verifica('stanza-nuova:sala c')).esito).toBe('assente')
    expect(net.callsTo(CREA_STANZA)).toHaveLength(3)
  })

  it('modifica incerta: come richiesto, come prima, diverso, assente', async () => {
    const { store } = await avvia()
    for (const [elenco, atteso] of [[[stanza(5, { nome: 'Nuova' })], 'come-richiesto'], [[stanza(5)], 'precedente'], [[stanza(5, { nome: 'Altra' })], 'diverso'], [[], 'assente']]) {
      net.on(STANZA(5), jsonResponse(502, { error: 'x' }))
      expect((await store.aggiornaStanza(st(5), { nome: 'Nuova', descrizione: null })).kind).toBe('incerto')
      net.on(STANZE, jsonResponse(200, elenco))
      expect((await store.verifica('stanza:5')).esito).toBe(atteso)
    }
  })
})

describe('prestazioni', () => {
  it('creazione incerta: stesso modulo bloccato, dati diversi liberi (nome non unico)', async () => {
    const { store } = await avvia()
    net.on(CREA_PRESTAZIONE, 'rete')
    await store.creaPrestazione(datiP(), [1, 2])
    expect(await store.creaPrestazione(datiP({ nome: 'VISITA' }), [1, 2])).toEqual({ kind: 'bloccata' })
    net.on(CREA_PRESTAZIONE, jsonResponse(201, prestazioneAdmin(4, { nome: 'Visita', durata: 45, prezzo: 80.5 })))
    expect((await store.creaPrestazione(datiP({ durata: '45' }), [1, 2])).kind).toBe('eseguita')
  })

  it('verifica della creazione: nuova corrispondenza, solo preesistenti, non distinguibile, assente; mai attribuita', async () => {
    const { store } = await avvia()
    const uguale = (id) => prestazioneAdmin(id, { nome: 'Visita', prezzo: 80.5 })
    for (const [noti, elenco, atteso] of [[[1, 2], [prestazioneAdmin(1), uguale(3)], 'nuova'], [[1, 2], [uguale(2)], 'solo-preesistenti'],
      [null, [uguale(2)], 'non-distinguibile'], [[1, 2], [prestazioneAdmin(1), prestazioneAdmin(3, { nome: 'Visita', prezzo: 80.51 })], 'assente']]) {
      net.on(CREA_PRESTAZIONE, 'rete')
      await store.creaPrestazione(datiP(), noti)
      net.on(PRESTAZIONI, jsonResponse(200, elenco))
      const key = store.state.incerte[0].chiave
      expect((await store.verifica(key)).esito).toBe(atteso)
    }
  })

  it('409 reale per cambio di durata con disponibilità associate: rifiuto certo con messaggio del server e barriera', async () => {
    const { store } = await avvia()
    const t = store.inizioLettura()
    net.on(PRESTAZIONE(2), jsonResponse(409, { error: 'La durata non può cambiare quando esistono disponibilità associate.' }))
    expect(await store.aggiornaPrestazione(pr(2), datiP({ durata: '45' }))).toMatchObject({ kind: 'conflitto', serverMessage: expect.stringContaining('durata') })
    expect(store.valida(AMB_PRESTAZIONI, t)).toBe(false)
    expect(store.valida(AMB_STANZE, t)).toBe(true)
  })

  it('503 con Retry-After: incerto, attesa condivisa con la gestione utenti; alla scadenza l\'incertezza resta', async () => {
    const { store } = await avvia()
    let ora = 1_000_000
    vi.spyOn(Date, 'now').mockImplementation(() => ora)
    net.on(PRESTAZIONE(2), jsonResponse(503, { error: 'x' }, { 'Retry-After': '5' }))
    expect(await store.aggiornaPrestazione(pr(2), datiP())).toMatchObject({ kind: 'incerto', retryAfter: 5 })
    expect(await store.cambiaStato(parseUtenteAmministrativo(amministrativo(9)), 'DISABILITATO')).toEqual({ kind: 'attesa', retryAfter: 5 })
    ora += 5000
    expect(await store.aggiornaPrestazione(pr(2), datiP())).toEqual({ kind: 'bloccata' })
    net.on(PRESTAZIONE(3), new Response('x', { status: 429 }))
    expect((await store.aggiornaPrestazione(pr(3), datiP())).kind).toBe('incerto')
  })
})

describe('identità', () => {
  it('nuova identità: esiti incerti dei cataloghi eliminati, esito tardivo scartato', async () => {
    const { session, store } = await avvia()
    net.on(STANZA(5), 'rete')
    await store.aggiornaStanza(st(5), { nome: 'Nuova', descrizione: null })
    const h = net.trattieni(PRESTAZIONE(2))
    const tardiva = store.aggiornaPrestazione(pr(2), datiP())
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(admin({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, prestazioneAdmin(2, { nome: 'Visita', prezzo: 80.5 })))
    expect((await tardiva).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, incerte: [], inCorso: [] })
  })
})

describe('riscontri ordinati sull\'elenco completo', () => {
  it('verifica A trattenuta, elenco B più recente applicato: esito da B; A tardiva non lo sostituisce', async () => {
    const { store } = await avvia()
    net.on(PRESTAZIONE(2), 'rete')
    await store.aggiornaPrestazione(pr(2), datiP())
    const h = net.trattieni(PRESTAZIONI)
    const lettura = store.leggiVerifica('prestazione:2')
    store.registraCatalogo(AMB_PRESTAZIONI, [pr(2, { nome: 'Visita', prezzo: 80.5 })], store.inizioLettura())
    h.resolve(jsonResponse(200, [prestazioneAdmin(2)]))
    const l = await lettura
    expect(store.esitoVerifica('prestazione:2', l)).toMatchObject({ kind: 'verificata', esito: 'come-richiesto' })
  })

  it('A applicata e B più recente applicata prima della costruzione: vale B; cambio di epoca: esito neutro, blocco mantenuto', async () => {
    const { session, store } = await avvia()
    net.on(STANZA(5), 'rete')
    await store.aggiornaStanza(st(5), { nome: 'Nuova', descrizione: null })
    net.on(STANZE, jsonResponse(200, [stanza(5)]))
    const l = await store.leggiVerifica('stanza:5')
    store.registraCatalogo(AMB_STANZE, [st(5, { nome: 'Nuova' })], store.inizioLettura())
    expect(store.esitoVerifica('stanza:5', l)).toMatchObject({ esito: 'come-richiesto' })
    net.on(STANZA(6), 'rete')
    await store.aggiornaStanza(st(6), { nome: 'Nuova6', descrizione: null })
    net.on(STANZE, jsonResponse(200, [stanza(6)]))
    const l2 = await store.leggiVerifica('stanza:6')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    expect(store.esitoVerifica('stanza:6', l2)).toEqual({ kind: 'obsoleto' })
    expect(store.bloccata('stanza:6')).toBe(true)
  })
})

describe('verifica invalidata da un esito certo su un\'altra voce del catalogo', () => {
  const CASI = {
    stanze: {
      ambito: AMB_STANZE,
      lettura: STANZE,
      crea: (store) => { net.on(CREA_STANZA, 'rete'); return store.creaStanza({ nome: 'Sala richiesta', descrizione: null }) },
      senza: [stanza(2, { nome: 'Altra' })],
      con: [stanza(2, { nome: 'Sala richiesta' })],
      positivo: 'presente',
      voce: (v) => v.map(parseStanza),
      // Esito certo su una risorsa diversa dal tentativo: la stanza 2 assume il nome cercato o lo perde.
      certa: (store, nome) => {
        const prima = nome === 'Sala richiesta' ? 'Altra' : 'Sala richiesta'
        net.on(STANZA(2), jsonResponse(200, stanza(2, { nome })))
        return store.aggiornaStanza(st(2, { nome: prima }), { nome, descrizione: null })
      },
      altra: (store) => { net.on(PRESTAZIONE(2), jsonResponse(200, prestazioneAdmin(2, { nome: 'Visita', prezzo: 80.5 }))); return store.aggiornaPrestazione(pr(2), datiP()) },
    },
    prestazioni: {
      ambito: AMB_PRESTAZIONI,
      lettura: PRESTAZIONI,
      crea: (store) => { net.on(CREA_PRESTAZIONE, 'rete'); return store.creaPrestazione(datiP(), [1, 2]) },
      senza: [prestazioneAdmin(1), prestazioneAdmin(3, { nome: 'Altra', prezzo: 80.5 })],
      con: [prestazioneAdmin(1), prestazioneAdmin(3, { nome: 'Visita', prezzo: 80.5 })],
      positivo: 'nuova',
      voce: (v) => v.map(parsePrestazioneAdmin),
      certa: (store, nome) => {
        const prima = nome === 'Visita' ? 'Altra' : 'Visita'
        net.on(PRESTAZIONE(3), jsonResponse(200, prestazioneAdmin(3, { nome, prezzo: 80.5 })))
        return store.aggiornaPrestazione(pr(3, { nome: prima, prezzo: 80.5 }), datiP({ nome }))
      },
      altra: (store) => { net.on(STANZA(5), jsonResponse(200, stanza(5, { nome: 'Nuova' }))); return store.aggiornaStanza(st(5), { nome: 'Nuova', descrizione: null }) },
    },
  }
  const nomeCercato = (c) => (c.ambito === AMB_STANZE ? 'Sala richiesta' : 'Visita')
  const nomeAltro = 'Altra'
  const chiave = (store) => store.state.incerte[0].chiave
  const neutro = (store, k) => {
    expect(store.bloccata(k)).toBe(true)
    expect(store.incerta(k)).toMatchObject({ verifica: 'superata' })
  }

  for (const [nome, c] of Object.entries(CASI)) {
    describe(nome, () => {
      it('A applicata, poi esito certo su un\'altra voce: né falsa assenza né falsa presenza conclusive, nessuno sblocco né rilettura', async () => {
        const { store } = await avvia()
        await c.crea(store)
        const k = chiave(store)
        net.on(c.lettura, jsonResponse(200, c.senza))
        const a = await store.leggiVerifica(k)
        expect((await c.certa(store, nomeCercato(c))).kind).toBe('eseguita')
        expect(store.esitoVerifica(k, a)).toEqual({ kind: 'superata' })
        neutro(store, k)

        // Variante simmetrica: A mostra la corrispondenza, poi la voce la perde.
        net.on(c.lettura, jsonResponse(200, c.con))
        const a2 = await store.leggiVerifica(k)
        expect((await c.certa(store, nomeAltro)).kind).toBe('eseguita')
        expect(store.esitoVerifica(k, a2)).toEqual({ kind: 'superata' })
        neutro(store, k)
        expect(net.callsTo(c.lettura)).toHaveLength(2)
        expect(scritture()).toBe(3)
      })

      it('verifica scartata dalla barriera mentre resta una lettura precedente: esito neutro', async () => {
        const { store } = await avvia()
        await c.crea(store)
        const k = chiave(store)
        store.registraCatalogo(c.ambito, c.voce(c.senza), store.inizioLettura())
        const h = net.trattieni(c.lettura)
        const lettura = store.leggiVerifica(k)
        expect((await c.certa(store, nomeCercato(c))).kind).toBe('eseguita')
        h.resolve(jsonResponse(200, c.con))
        const a = await lettura
        expect(a.kind).toBe('letta')
        expect(store.esitoVerifica(k, a)).toEqual({ kind: 'superata' })
        neutro(store, k)
        expect(net.callsTo(c.lettura)).toHaveLength(1)
      })

      it('nuova lettura valida dopo la barriera: recupero manuale riuscito', async () => {
        const { store } = await avvia()
        await c.crea(store)
        const k = chiave(store)
        net.on(c.lettura, jsonResponse(200, c.senza))
        const a = await store.leggiVerifica(k)
        await c.certa(store, nomeCercato(c))
        expect(store.esitoVerifica(k, a)).toEqual({ kind: 'superata' })
        net.on(c.lettura, jsonResponse(200, c.con))
        expect(await store.verifica(k)).toMatchObject({ kind: 'verificata', esito: c.positivo })
        expect(store.bloccata(k)).toBe(false)
      })

      it('percorso ordinario invariato, anche con un esito certo sull\'altro catalogo', async () => {
        const { store } = await avvia()
        await c.crea(store)
        const k = chiave(store)
        net.on(c.lettura, jsonResponse(200, c.con))
        const a = await store.leggiVerifica(k)
        expect((await c.altra(store)).kind).toBe('eseguita')
        expect(store.esitoVerifica(k, a)).toMatchObject({ kind: 'verificata', esito: c.positivo })
        expect(store.bloccata(k)).toBe(false)
      })
    })
  }
})
