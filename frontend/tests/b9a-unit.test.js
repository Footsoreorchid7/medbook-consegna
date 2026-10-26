import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  fetchGestionali, fetchRicerca, normalizzaCodiceFiscale, normalizzaEmail, parseAccount, parseCreazione, parseRuolo, parseStato,
  parseUtenteAmministrativo, postAccount, ricercaPath, validaFiltri, validaNuovoAccount,
} from '../src/api/utenti.js'
import { testoVerifica } from '../src/admin/formato.js'
import { CF, PASSWORD, account, amministrativo, pagina } from './b9a-fixtures.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data })
const cfValido = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'].map((c) => CF + c).find((c) => normalizzaCodiceFiscale(c))
const richiesta = (risposta) => {
  const calls = []
  return { fn: async (o) => { calls.push(o); return risposta }, calls }
}

describe('proiezioni distinte', () => {
  it('account gestionale: esattamente 8 campi, ruolo obbligatorio, profili booleani', () => {
    expect(parseAccount(account(5, { medico: true }))).toMatchObject({ proiezione: 'gestionale', idUtente: 5, ruolo: 'OPERATORE_DESK', medico: true, paziente: false })
    for (const guasto of [{ ruoloGestionale: null }, { paziente: 'no' }, { statoAccesso: 'SOSPESO' }, { codiceFiscale: null }, { idUtente: 0 }]) {
      expect(() => parseAccount(account(5, guasto))).toThrow(ProtocolError)
    }
    const { medico, ...senza } = account(5)
    expect(() => parseAccount(senza)).toThrow(ProtocolError)
  })

  it('vista amministrativa: 7 campi, ruolo e codice fiscale anche nulli, profili mai presenti né dedotti', () => {
    const u = parseUtenteAmministrativo(amministrativo(7, { ruoloGestionale: null, codiceFiscale: 'X' }))
    expect(u).toMatchObject({ proiezione: 'amministrativa', ruolo: null, codiceFiscale: 'X' })
    expect('paziente' in u || 'medico' in u).toBe(false)
    expect(() => parseUtenteAmministrativo({ ...amministrativo(7), paziente: true })).toThrow(ProtocolError)
    expect(() => parseUtenteAmministrativo(amministrativo(7, { ruoloGestionale: 'MEDICO' }))).toThrow(ProtocolError)
  })

  it('elenco gestionale per ID crescente; risposte di scrittura validate sulla proiezione e sui dati inviati', async () => {
    expect((await fetchGestionali(richiesta(ok(200, [account(1), account(4)])).fn)).map((a) => a.idUtente)).toEqual([1, 4])
    await expect(fetchGestionali(richiesta(ok(200, [account(4), account(1)])).fn)).rejects.toThrow(ProtocolError)
    const dati = { nome: 'Nome', cognome: 'Prova9', email: 'utente9@example.test', ruolo: 'OPERATORE_DESK' }
    expect(parseCreazione(ok(201, account(9)), dati).idUtente).toBe(9)
    expect(() => parseCreazione(ok(200, account(9)), dati)).toThrow(ProtocolError)
    expect(() => parseCreazione(ok(201, account(9, { medico: true })), dati)).toThrow(ProtocolError)
    expect(() => parseCreazione(ok(201, account(9, { email: 'altro@example.test' })), dati)).toThrow(ProtocolError)
    expect(parseRuolo(ok(200, account(9, { ruoloGestionale: 'AMMINISTRATORE' })), 9, 'AMMINISTRATORE').proiezione).toBe('gestionale')
    expect(() => parseRuolo(ok(200, amministrativo(9, { ruoloGestionale: 'AMMINISTRATORE' })), 9, 'AMMINISTRATORE')).toThrow(ProtocolError)
    expect(parseStato(ok(200, amministrativo(9, { statoAccesso: 'DISABILITATO' })), 9, 'DISABILITATO').proiezione).toBe('amministrativa')
    expect(() => parseStato(ok(200, account(9, { statoAccesso: 'DISABILITATO' })), 9, 'DISABILITATO')).toThrow(ProtocolError)
    expect(() => parseStato(ok(200, amministrativo(8, { statoAccesso: 'DISABILITATO' })), 9, 'DISABILITATO')).toThrow(ProtocolError)
  })
})

describe('filtri e paginazione della ricerca', () => {
  it('almeno un filtro identificativo; normalizzazioni e limiti come il backend', () => {
    expect(validaFiltri({}).errori.generale).toContain('almeno uno')
    expect(validaFiltri({ ruolo: 'NESSUNO', statoAccesso: 'ATTIVO' }).errori.generale).toBeDefined()
    expect(validaFiltri({ email: '  AnNa@Ex ' }).filtri).toEqual({ email: 'anna@ex' })
    expect(validaFiltri({ email: 'ab' }).errori.email).toBeDefined()
    expect(validaFiltri({ cognome: ' R ' }).errori.cognome).toBeDefined()
    expect(validaFiltri({ cognome: ' Ro ' }).filtri.cognome).toBe('Ro ')
    expect(validaFiltri({ idUtente: '007' }).errori.idUtente).toBeDefined()
    expect(validaFiltri({ idUtente: '2147483648' }).errori.idUtente).toBeDefined()
    expect(validaFiltri({ codiceFiscale: ` ${cfValido.toLowerCase()} ` }).filtri.codiceFiscale).toBe(cfValido)
    expect(validaFiltri({ codiceFiscale: CF + (cfValido.at(-1) === 'A' ? 'B' : 'A') }).errori.codiceFiscale).toBeDefined()
    expect(validaFiltri({ idUtente: '12', ruolo: 'NESSUNO' }).filtri).toEqual({ idUtente: '12', ruolo: 'NESSUNO' })
  })

  it('percorso: filtri codificati, cursore solo oltre 0, limite esplicito', () => {
    expect(ricercaPath({ cognome: 'D\'Amico & C', ruolo: 'NESSUNO' })).toBe("/api/utenti/ricerca?cognome=D'Amico%20%26%20C&ruolo=NESSUNO&limite=20")
    expect(ricercaPath({ email: 'a+b@x' }, 40, 50)).toBe('/api/utenti/ricerca?email=a%2Bb%40x&dopoId=40&limite=50')
    expect(() => ricercaPath({})).toThrow(TypeError)
    expect(() => ricercaPath({ email: 'abc' }, 0, 51)).toThrow(TypeError)
  })

  it('pagina: ordine, cursore e prossimoDopoId coerenti; nessun totale', async () => {
    const leggi = (data, dopo = 0, limite = 2) => fetchRicerca(richiesta(ok(200, data)).fn, { email: 'abc' }, dopo, limite)
    expect(await leggi(pagina([amministrativo(3), amministrativo(5)], 5))).toMatchObject({ prossimoDopoId: 5 })
    expect((await leggi(pagina([amministrativo(8)]), 5)).prossimoDopoId).toBeNull()
    for (const [data, dopo] of [
      [pagina([amministrativo(5), amministrativo(3)]), 0], [pagina([amministrativo(3)], 3), 0], [pagina([amministrativo(3), amministrativo(5)], 3), 0],
      [pagina([amministrativo(5)]), 5], [{ ...pagina([]), totale: 0 }, 0], [pagina([amministrativo(1), amministrativo(2), amministrativo(3)]), 0],
    ]) {
      await expect(leggi(data, dopo)).rejects.toThrow(ProtocolError)
    }
  })
})

describe('creazione', () => {
  it('validazione: password mai normalizzata (12–1024 unità UTF-16, niente surrogati isolati); email minuscola; nomi entro 100', () => {
    const base = { nome: ' Ada ', cognome: 'Sintetica', email: ' Ada@Example.TEST ', ruolo: 'AMMINISTRATORE' }
    expect(validaNuovoAccount({ ...base, password: '   12345678 ' }).dati).toEqual({ nome: 'Ada', cognome: 'Sintetica', email: 'ada@example.test', ruolo: 'AMMINISTRATORE' })
    expect(validaNuovoAccount({ ...base, password: '12345678901' }).errori.password).toBeDefined()
    expect(validaNuovoAccount({ ...base, password: 'abcdefghijk\uD800' }).errori.password).toBeDefined()
    expect(validaNuovoAccount({ ...base, password: 'x'.repeat(1025) }).errori.password).toBeDefined()
    expect(validaNuovoAccount({ ...base, nome: 'a'.repeat(101), password: PASSWORD }).errori.nome).toBeDefined()
    expect(validaNuovoAccount({ ...base, cognome: 'a\u0007', password: PASSWORD }).errori.cognome).toBeDefined()
    expect(validaNuovoAccount({ ...base, ruolo: null, password: PASSWORD }).errori.ruolo).toBeDefined()
    expect(normalizzaEmail('a..b@example.test')).toBeNull()
    expect(normalizzaEmail(`${'a'.repeat(65)}@example.test`)).toBeNull()
  })

  it('POST con i soli cinque campi previsti', async () => {
    const r = richiesta(ok(201, account(9)))
    await postAccount(r.fn, { nome: 'N', cognome: 'C', email: 'e@example.test', ruolo: 'OPERATORE_DESK' }, PASSWORD)
    expect(r.calls[0]).toEqual({ method: 'POST', path: '/api/utenti', json: { nome: 'N', cognome: 'C', email: 'e@example.test', password: PASSWORD, ruoloGestionale: 'OPERATORE_DESK' } })
    expect(() => postAccount(r.fn, { nome: 'N', cognome: 'C', email: 'e@example.test', ruolo: null }, PASSWORD)).toThrow(TypeError)
  })
})

describe('messaggi di verifica', () => {
  it('descrivono lo stato corrente senza attribuirlo al tentativo', () => {
    const crea = { tipo: 'crea', idUtente: null, riepilogo: { email: 'e@example.test' } }
    expect(testoVerifica(crea, 'presente', parseUtenteAmministrativo(amministrativo(9, { email: 'e@example.test' })))).toContain('non prova che sia stato creato dal tentativo')
    expect(testoVerifica(crea, 'assente', null)).toContain('la creazione non risulta applicata')
    const ruolo = { tipo: 'ruolo', idUtente: 9, riepilogo: { precedente: 'OPERATORE_DESK', richiesto: 'AMMINISTRATORE' } }
    const voce = (r) => parseUtenteAmministrativo(amministrativo(9, { ruoloGestionale: r }))
    expect(testoVerifica(ruolo, 'come-richiesto', voce('AMMINISTRATORE'))).toContain('non prova chi abbia eseguito')
    expect(testoVerifica(ruolo, 'precedente', voce('OPERATORE_DESK'))).toContain('non risulta applicata')
    expect(testoVerifica(ruolo, 'diverso', voce(null))).toContain('non è possibile stabilire')
    expect(testoVerifica(ruolo, 'assente', null)).toContain('non può essere stabilito')
  })
})
