import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HttpError, ProtocolError } from '../src/api/http.js'
import { fetchPagamenti, importoValido, parsePagamento, parsePagamentoOnline, postPagamentoOnline } from '../src/api/pagamenti.js'
import { avviaDownload, fetchReferto, nomeFileSicuro, scaricaDocumentoReferto } from '../src/api/referti.js'
import { fetchRimborsi, motivazioneErrore, parseRimborso, parseRimborsoCreato, postRimborso } from '../src/api/rimborsi.js'
import { parseAppuntamento } from '../src/api/prenotazioni.js'
import { pagamentoProponibile, pagamentoRimborsabile } from '../src/prenotazioni/economia.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { STATO, createSession } from '../src/session/session.js'
import { appuntamento } from './b3-fixtures.js'
import { PDF, confermata, fallito, pagamento, pdfResponse, referto, rimborso } from './b4-fixtures.js'
import { CSRF_A, CSRF_B, installFetch, jsonResponse, meOk, routedFetch, unauthorized, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const PAG = 'GET /api/prenotazioni/12/pagamenti'
const PAGA = 'POST /api/prenotazioni/12/pagamento-online'
const RIMB = 'GET /api/rimborsi/paziente'
const CHIEDI = 'POST /api/prenotazioni/12/rimborso'
const REF = 'GET /api/prenotazioni/12/referto'
const DOC = 'GET /api/prenotazioni/12/referto/documento'

let net
beforeEach(() => { net = installFetch(routedFetch()) })

const ok = (status, data) => ({ status, hasBody: data !== undefined, data, headers: new Headers() })
const corpo = (call) => JSON.parse(call.body)
const lettura = (voci, stato = 'pronto', aggiornata = stato === 'pronto') => ({ stato, voci, aggiornata })

async function autenticata(user = utente()) {
  net.on(ME, meOk(user, CSRF_A))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: prenotazioniStore(session) }
}
const pren = (extra) => parseAppuntamento(confermata(12, extra))

describe('API pagamenti', () => {
  it('voce valida e vincoli dello schema', () => {
    expect(parsePagamento(pagamento(5), 12)).toMatchObject({ idPagamento: 5, modalita: 'ONLINE', importo: 80, stato: 'PAGATO' })
    expect(parsePagamento(fallito(6), 12).pagato).toBeNull()
    for (const extra of [
      { stato: 'RIMBORSATO' }, { modalita: 'BONIFICO' }, { importo: 0 }, { importo: '80.00' }, { idPrenotazione: 13 },
      { pagatoIl: null }, { stato: 'FALLITO' }, { stato: 'IN_ATTESA' }, { creatoIl: '2026-10-16T10:00:00Z' }, { idPagamento: 0 },
    ]) expect(() => parsePagamento(pagamento(5, extra), 12), JSON.stringify(extra)).toThrow(ProtocolError)
  })

  it('storico: percorso della risorsa, ordine del backend, nessun parametro', async () => {
    net.on(PAG, jsonResponse(200, [pagamento(7, { creatoIl: '2026-10-16T11:00:00', stato: 'IN_ATTESA', pagatoIl: null }), fallito(6)]))
    const { session } = await autenticata()
    expect((await fetchPagamenti(session.protectedRequest, 12)).map((x) => x.idPagamento)).toEqual([7, 6])
    net.on(PAG, jsonResponse(200, { voci: [] }))
    await expect(fetchPagamenti(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
  })

  it('importo atteso: positivo, al massimo due decimali', () => {
    expect([80, 95.5, 0.01, 99999999.99].every(importoValido)).toBe(true)
    expect([0, -1, 1.234, Number.NaN, Infinity, '80', 100000000].some(importoValido)).toBe(false)
  })

  it('POST: corpo con il solo importoAtteso; importo non valido rifiutato prima della richiesta', async () => {
    const richieste = []
    const requestFn = (o) => { richieste.push(o); return Promise.resolve(ok(201, pagamento(9))) }
    await postPagamentoOnline(requestFn, 12, 95.5)
    expect(richieste[0]).toEqual({ method: 'POST', path: '/api/prenotazioni/12/pagamento-online', json: { importoAtteso: 95.5 } })
    expect(() => postPagamentoOnline(requestFn, 12, 1.234)).toThrow(TypeError)
    expect(() => postPagamentoOnline(requestFn, 0, 80)).toThrow(TypeError)
    expect(richieste).toHaveLength(1)
  })

  it('201 PAGATO e 201 FALLITO sono entrambi esiti certi, distinti', () => {
    expect(parsePagamentoOnline(ok(201, pagamento(9)), 12, 80).stato).toBe('PAGATO')
    expect(parsePagamentoOnline(ok(201, fallito(9)), 12, 80).stato).toBe('FALLITO')
  })

  it.each([
    ['200 invece di 201', ok(200, pagamento(9))],
    ['corpo assente', ok(201, undefined)],
    ['importo diverso', ok(201, pagamento(9, { importo: 81 }))],
    ['modalità in sede', ok(201, pagamento(9, { modalita: 'DESK_POS' }))],
    ['stato IN_ATTESA', ok(201, pagamento(9, { stato: 'IN_ATTESA', pagatoIl: null }))],
    ['altra prenotazione', ok(201, pagamento(9, { idPrenotazione: 13 }))],
    ['PAGATO senza data', ok(201, pagamento(9, { pagatoIl: null }))],
    ['identificativo non valido', ok(201, pagamento(-1))],
  ])('successo malformato: %s', (_, result) => {
    expect(() => parsePagamentoOnline(result, 12, 80)).toThrow(ProtocolError)
  })
})

describe('API rimborsi', () => {
  it('motivazione allineata al backend', () => {
    expect(motivazioneErrore('Visita non erogata')).toBeNull()
    expect(motivazioneErrore('  testo con spazi  ')).toBeNull()
    expect(motivazioneErrore('riga 1\nriga 2\ttab\r')).toBeNull()
    expect(motivazioneErrore('😀'.repeat(500))).toBeNull()
    expect(motivazioneErrore('\u00A0')).toBeNull() // spazio non separabile: non è whitespace per Java
    for (const v of ['', '   ', '\n\t', '\u2003', 'a'.repeat(501), '😀'.repeat(501), 'campanello\u0007', 'x\u0085', 'a\uD800b', null]) {
      expect(motivazioneErrore(v), JSON.stringify(v)).not.toBeNull()
    }
  })

  it('POST: corpo con la sola motivazione, inviata senza trasformazioni', async () => {
    const richieste = []
    const requestFn = (o) => { richieste.push(o); return Promise.resolve(ok(201, rimborso(1))) }
    await postRimborso(requestFn, 12, '  Visita non erogata  ')
    expect(richieste[0]).toEqual({ method: 'POST', path: '/api/prenotazioni/12/rimborso', json: { motivazione: '  Visita non erogata  ' } })
    expect(() => postRimborso(requestFn, 12, '   ')).toThrow(TypeError)
    expect(richieste).toHaveLength(1)
  })

  it('identificativi distinti e stati del contratto', () => {
    const r = parseRimborso(rimborso(31, { idPrenotazione: 12, idPagamento: 5 }))
    expect([r.idRimborso, r.idPrenotazione, r.idPagamento]).toEqual([31, 12, 5])
    for (const stato of ['RICHIESTO', 'APPROVATO', 'RESPINTO', 'IN_ELABORAZIONE', 'RIMBORSATO', 'FALLITO']) {
      expect(parseRimborso(rimborso(1, { stato })).stato).toBe(stato)
    }
    expect(() => parseRimborso(rimborso(1, { stato: 'ANNULLATO' }))).toThrow(ProtocolError)
    expect(() => parseRimborso(rimborso(1, { idPagamento: null }))).toThrow(ProtocolError)
  })

  it('risposta 201 validata rispetto al pagamento e alla motivazione inviati', () => {
    const atteso = { idPrenotazione: 12, idPagamento: 5, importo: 80, motivazione: 'Visita non erogata' }
    expect(parseRimborsoCreato(ok(201, rimborso(1)), atteso).stato).toBe('RICHIESTO')
    for (const [nome, result] of [
      ['200', ok(200, rimborso(1))],
      ['APPROVATO', ok(201, rimborso(1, { stato: 'APPROVATO', verificatoIl: '2026-10-17T10:00:00' }))],
      ['altro pagamento', ok(201, rimborso(1, { idPagamento: 6 }))],
      ['altra prenotazione', ok(201, rimborso(1, { idPrenotazione: 13 }))],
      ['importo', ok(201, rimborso(1, { importo: 70 }))],
      ['motivazione', ok(201, rimborso(1, { motivazione: 'Altro' }))],
    ]) expect(() => parseRimborsoCreato(result, atteso), nome).toThrow(ProtocolError)
  })

  it('elenco: ordine del backend', async () => {
    net.on(RIMB, jsonResponse(200, [rimborso(2), rimborso(1)]))
    const { session } = await autenticata()
    expect((await fetchRimborsi(session.protectedRequest)).map((r) => r.idRimborso)).toEqual([2, 1])
  })
})

describe('regole di proposta (dati letti dal server)', () => {
  it('pagamento online: CONFERMATA e storico verificato senza PAGATO/IN_ATTESA', () => {
    const p = pren()
    expect(pagamentoProponibile(p, lettura([]))).toBe(true)
    expect(pagamentoProponibile(p, lettura([parsePagamento(fallito(6), 12)]))).toBe(true)
    expect(pagamentoProponibile(p, lettura([parsePagamento(pagamento(6), 12)]))).toBe(false)
    expect(pagamentoProponibile(p, lettura([parsePagamento(pagamento(6, { stato: 'IN_ATTESA', pagatoIl: null }), 12)]))).toBe(false)
    expect(pagamentoProponibile(p, lettura([], 'errore'))).toBe(false)
    expect(pagamentoProponibile(p, lettura([], 'caricamento'))).toBe(false)
    // Dati visibili ma in rilettura: non autorizzano il pagamento.
    expect(pagamentoProponibile(p, lettura([], 'pronto', false))).toBe(false)
    expect(pagamentoProponibile(pren({ stato: 'RICHIESTA' }), lettura([]))).toBe(false)
    expect(pagamentoProponibile(pren({ prezzo: 80.005 }), lettura([]))).toBe(false)
  })

  it('rimborso: NON_EROGATA, un solo pagamento ONLINE/PAGATO dell\'importo, nessuna richiesta per quel pagamento', () => {
    const p = pren({ stato: 'NON_EROGATA' })
    const pag = (x) => parsePagamento(x, 12)
    const r = (x) => parseRimborso(x)
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))]), lettura([]))?.idPagamento).toBe(5)
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5)), pag(fallito(4))]), lettura([]))?.idPagamento).toBe(5)
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5, { modalita: 'DESK_POS' }))]), lettura([]))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5, { importo: 70 }))]), lettura([]))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5)), pag(pagamento(6))]), lettura([]))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(fallito(4))]), lettura([]))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))]), lettura([r(rimborso(1, { idPagamento: 5 }))]))).toBeNull()
    // Una richiesta per un altro pagamento (idRimborso uguale all'idPagamento) non conta.
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))]), lettura([r(rimborso(5, { idPagamento: 99, idPrenotazione: 40 }))]))?.idPagamento).toBe(5)
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))]), lettura([], 'errore'))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))], 'pronto', false), lettura([]))).toBeNull()
    expect(pagamentoRimborsabile(p, lettura([pag(pagamento(5))]), lettura([], 'pronto', false))).toBeNull()
    expect(pagamentoRimborsabile(pren({ stato: 'EFFETTUATA' }), lettura([pag(pagamento(5))]), lettura([]))).toBeNull()
  })
})

describe('API referti e download', () => {
  afterEach(() => vi.useRealTimers())

  it('metadati: 404 = non disponibile, altri errori propagati, contratto validato', async () => {
    const { session } = await autenticata()
    net.on(REF, jsonResponse(404, { error: 'Referto non trovato.' }))
    expect(await fetchReferto(session.protectedRequest, 12)).toBeNull()
    net.on(REF, jsonResponse(500, { error: 'x' }))
    await expect(fetchReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(HttpError)
    net.on(REF, jsonResponse(200, referto()))
    expect(await fetchReferto(session.protectedRequest, 12)).toMatchObject({ idReferto: 3, nomeFile: 'referto-sintetico.pdf' })
    net.on(REF, jsonResponse(200, referto({ idPrenotazione: 13 })))
    await expect(fetchReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
  })

  it('documento: PDF valido; errori HTTP letti prima del blob; tipo, firma e lunghezza verificati', async () => {
    const { session } = await autenticata()
    net.on(DOC, pdfResponse())
    const blob = await scaricaDocumentoReferto(session.protectedRequest, 12)
    expect(blob.size).toBe(new TextEncoder().encode(PDF).length)
    expect(net.callsTo(DOC)[0].headers.get('Accept')).toContain('application/pdf')
    net.on(DOC, jsonResponse(404, { error: 'Referto non trovato.' }))
    const e404 = await scaricaDocumentoReferto(session.protectedRequest, 12).catch((e) => e)
    expect(e404).toBeInstanceOf(HttpError)
    expect(e404.status).toBe(404)
    net.on(DOC, jsonResponse(200, { finto: true }))
    await expect(scaricaDocumentoReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
    net.on(DOC, pdfResponse('<html>non un pdf</html>'))
    await expect(scaricaDocumentoReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
    net.on(DOC, pdfResponse(PDF, { 'Content-Length': '9999' }))
    await expect(scaricaDocumentoReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
    net.on(DOC, () => new Response('', { status: 200, headers: { 'Content-Type': 'application/pdf' } }))
    await expect(scaricaDocumentoReferto(session.protectedRequest, 12)).rejects.toBeInstanceOf(ProtocolError)
  })

  it('nome file sicuro con ripiego controllato', () => {
    expect(nomeFileSicuro('referto-sintetico.pdf', 12)).toBe('referto-sintetico.pdf')
    expect(nomeFileSicuro('Esame.PDF', 12)).toBe('Esame.pdf')
    expect(nomeFileSicuro('../../etc/passwd', 12)).toBe('passwd.pdf')
    expect(nomeFileSicuro('C:\\dati\\esito.pdf', 12)).toBe('esito.pdf')
    expect(nomeFileSicuro('.nascosto.pdf', 12)).toBe('nascosto.pdf')
    expect(nomeFileSicuro('a<b>:"|?*.pdf', 12)).toBe('a_b______.pdf')
    expect(nomeFileSicuro('nome\u202Efdp.exe', 12)).toBe('nome_fdp.exe.pdf')
    expect(nomeFileSicuro('x'.repeat(300) + '.pdf', 12)).toBe('x'.repeat(100) + '.pdf')
    for (const v of ['', '...', '   ', '.pdf', '\u0000\u0001', null, 42]) expect(nomeFileSicuro(v, 12), String(v)).toBe('referto-12.pdf')
  })

  it('object URL: link temporaneo, revoca dopo l\'avvio del download, revoca idempotente', () => {
    vi.useFakeTimers()
    const crea = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sintetico')
    const revoca = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clic = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      clic.push({ href: this.getAttribute('href'), download: this.download, collegato: this.isConnected })
    })
    const blob = new Blob(['%PDF-'])
    const handle = avviaDownload(blob, 'referto.pdf', { ritardoMs: 5000 })
    expect(crea).toHaveBeenCalledWith(blob)
    expect(clic).toEqual([{ href: 'blob:sintetico', download: 'referto.pdf', collegato: true }])
    expect(document.querySelector('a[download]')).toBeNull()
    expect(revoca).not.toHaveBeenCalled()
    vi.advanceTimersByTime(5000)
    expect(revoca).toHaveBeenCalledWith('blob:sintetico')
    handle.revoca()
    expect(revoca).toHaveBeenCalledTimes(1)
  })

  it('object URL revocato subito se l\'avvio del download fallisce', () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sintetico')
    const revoca = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { throw new Error('bloccato') })
    expect(() => avviaDownload(new Blob(['%PDF-']), 'r.pdf')).toThrow('bloccato')
    expect(revoca).toHaveBeenCalledWith('blob:sintetico')
    expect(document.querySelector('a[download]')).toBeNull()
  })
})

describe('store: pagamento online', () => {
  it('payload con il prezzo storico, CSRF; PAGATO certo blocca subito un secondo invio', async () => {
    const { store } = await autenticata()
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    const esito = await store.pagaOnline(pren(), [])
    expect(esito.kind).toBe('pagato')
    const [call] = net.callsTo(PAGA)
    expect(corpo(call)).toEqual({ importoAtteso: 80 })
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(store.pagamentoBloccato(12)).toBe(true)
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
    expect(store.state.pagamentiIncerti).toEqual([])
  })

  it('FALLITO certo: nessun blocco condiviso', async () => {
    const { store } = await autenticata()
    net.on(PAGA, jsonResponse(201, fallito(10)))
    expect((await store.pagaOnline(pren(), [])).kind).toBe('fallito')
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it.each([[409, 'conflitto'], [404, 'non-trovata'], [403, 'rifiutata'], [400, 'non-valida']])('%i: %s, nessun blocco', async (status, kind) => {
    const { store } = await autenticata()
    net.on(PAGA, jsonResponse(status, { error: 'x' }))
    expect((await store.pagaOnline(pren(), [])).kind).toBe(kind)
    expect(store.pagamentoBloccato(12)).toBe(false)
  })

  it('401: sessione persa, esito certo', async () => {
    const { session, store } = await autenticata()
    net.on(PAGA, unauthorized())
    expect((await store.pagaOnline(pren(), [])).kind).toBe('sessione-assente')
    expect(session.state.status).toBe(STATO.ANONIMO)
  })

  it.each([
    ['rete', 'rete'],
    ['500', jsonResponse(500, { error: 'x' })],
    ['201 malformato', jsonResponse(201, pagamento(9, { importo: 1 }))],
  ])('%s: incerto, blocco e nessun nuovo tentativo automatico', async (_, risposta) => {
    const { store } = await autenticata()
    net.on(PAGA, risposta)
    expect((await store.pagaOnline(pren(), [parsePagamento(fallito(4), 12)])).kind).toBe('incerto')
    expect(store.pagamentoIncerto(12)).toMatchObject({ importoAtteso: 80, noti: [4], verifica: null })
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  it('doppio invio e passwordUncertain: nessuna POST aggiuntiva', async () => {
    const { session, store } = await autenticata()
    const trattenuta = net.trattieni(PAGA)
    const primo = store.pagaOnline(pren(), [])
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'occupato' })
    trattenuta.resolve(jsonResponse(201, fallito(9)))
    await primo
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await store.pagaOnline(pren(), [])).toEqual({ kind: 'password-incerta' })
    expect(net.callsTo(PAGA)).toHaveLength(1)
  })

  describe('verifica nello storico', () => {
    async function incerto(noti = [parsePagamento(fallito(4), 12)]) {
      const ctx = await autenticata()
      net.on(PAGA, 'rete')
      await ctx.store.pagaOnline(pren(), noti)
      return ctx
    }

    it('storico con soli tentativi precedenti: non prova l\'esito, sblocco solo dopo la verifica', async () => {
      const { store } = await incerto()
      net.on(PAG, jsonResponse(200, [fallito(4)]))
      expect(await store.verificaPagamento(12)).toMatchObject({ kind: 'verificata', esito: 'nessun-nuovo' })
      expect(store.pagamentoBloccato(12)).toBe(false)
      expect(net.callsTo(PAGA)).toHaveLength(1)
    })

    it('nuovo tentativo FALLITO: non attribuito con certezza', async () => {
      const { store } = await incerto()
      net.on(PAG, jsonResponse(200, [fallito(8, { creatoIl: '2026-10-16T12:00:00' }), fallito(4)]))
      expect(await store.verificaPagamento(12)).toMatchObject({ esito: 'nuovo-fallito' })
      expect(store.pagamentoIncerto(12).nuovi.map((x) => x.idPagamento)).toEqual([8])
    })

    it('nuovo pagamento PAGATO: esito "attivo", storico restituito, blocco condiviso', async () => {
      const { store } = await incerto()
      net.on(PAG, jsonResponse(200, [pagamento(8), fallito(4)]))
      const esito = await store.verificaPagamento(12)
      expect(esito.esito).toBe('attivo')
      expect(store.pagamentoBloccato(12)).toBe(true)
      expect(esito.storico.map((x) => x.idPagamento)).toEqual([8, 4])
    })

    it('verifica fallita: il blocco resta', async () => {
      const { store } = await incerto()
      net.on(PAG, 'rete')
      expect((await store.verificaPagamento(12)).kind).toBe('errore')
      expect(store.pagamentoBloccato(12)).toBe(true)
    })
  })

  it('rotazione dello stesso utente: incertezza conservata; cambio di epoca durante la POST: incerto', async () => {
    const { session, store } = await autenticata()
    const trattenuta = net.trattieni(PAGA)
    const invio = store.pagaOnline(pren(), [])
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    await session.changePassword('a', 'b')
    trattenuta.resolve(jsonResponse(201, pagamento(9)))
    expect((await invio).kind).toBe('incerto')
    expect(store.pagamentoBloccato(12)).toBe(true)
  })

  it('logout concluso e altro utente: incertezze eliminate, esito tardivo scartato', async () => {
    const { session, store } = await autenticata()
    const trattenuta = net.trattieni(PAGA)
    const invio = store.pagaOnline(pren(), [])
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    expect(store.state.pagamentiInCorso).toEqual([])
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    trattenuta.resolve(jsonResponse(500, { error: 'x' }))
    expect((await invio).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, pagamentiIncerti: [], pagamentiInCorso: [] })
  })
})

describe('store: richiesta di rimborso', () => {
  const PAGATO5 = Object.freeze({ idPagamento: 5, importo: 80 })

  it('payload esatto, 201 RICHIESTO validato', async () => {
    const { store } = await autenticata()
    net.on(CHIEDI, jsonResponse(201, rimborso(31)))
    expect((await store.richiediRimborso(12, PAGATO5, 'Visita non erogata')).kind).toBe('richiesto')
    const [call] = net.callsTo(CHIEDI)
    expect(corpo(call)).toEqual({ motivazione: 'Visita non erogata' })
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
  })

  it('409 duplicato: conflitto certo, nessuna ripetizione', async () => {
    const { store } = await autenticata()
    net.on(CHIEDI, jsonResponse(409, { error: 'Richiesta di rimborso già presente.' }))
    expect((await store.richiediRimborso(12, PAGATO5, 'Motivo')).kind).toBe('conflitto')
    expect(store.rimborsoBloccato(12)).toBe(false)
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
  })

  it('incerto → verifica: trovato per lo stesso pagamento, oppure assente non conclusivo', async () => {
    const { store } = await autenticata()
    net.on(CHIEDI, jsonResponse(201, rimborso(31, { idPagamento: 6 })))
    expect((await store.richiediRimborso(12, PAGATO5, 'Visita non erogata')).kind).toBe('incerto')
    expect(store.rimborsoBloccato(12)).toBe(true)
    expect(await store.richiediRimborso(12, PAGATO5, 'Visita non erogata')).toEqual({ kind: 'bloccata' })
    // Un rimborso con idRimborso 5 ma per un altro pagamento non è una corrispondenza.
    net.on(RIMB, jsonResponse(200, [rimborso(5, { idPagamento: 77, idPrenotazione: 40 })]))
    expect((await store.verificaRimborso(12)).kind).toBe('assente')
    expect(store.rimborsoBloccato(12)).toBe(false)
    net.on(RIMB, jsonResponse(200, [rimborso(31, { idPagamento: 5 })]))
    expect((await store.verificaRimborso(12)).kind).toBe('trovato')
    expect(store.rimborsoIncerto(12)).toBeNull()
    expect(net.callsTo(CHIEDI)).toHaveLength(1)
  })

  it('identità: altro utente elimina le incertezze di rimborso', async () => {
    const { session, store } = await autenticata()
    net.on(CHIEDI, 'rete')
    await store.richiediRimborso(12, PAGATO5, 'Motivo')
    net.on(PAG, unauthorized())
    await store.verificaPagamento(12)
    net.on(RIMB, unauthorized())
    await store.verificaRimborso(12)
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(store.rimborsoIncerto(12)).not.toBeNull()
    net.on(LOGIN, meOk(utente({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    expect(store.state.rimborsiIncerti).toEqual([])
  })
})

describe('profili', () => {
  it('account senza profilo paziente: nessuna scrittura economica', async () => {
    const { store } = await autenticata(utente({ paziente: false, medico: true }))
    expect((await store.pagaOnline(pren(), [])).kind).toBe('sessione-assente')
    expect((await store.richiediRimborso(12, { idPagamento: 5, importo: 80 }, 'x')).kind).toBe('sessione-assente')
    expect(net.calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('profilo combinato medico+paziente: scritture consentite', async () => {
    const { store } = await autenticata(utente({ medico: true }))
    net.on(PAGA, jsonResponse(201, pagamento(9)))
    expect((await store.pagaOnline(pren(), [])).kind).toBe('pagato')
    expect(appuntamento).toBeTypeOf('function')
  })
})
