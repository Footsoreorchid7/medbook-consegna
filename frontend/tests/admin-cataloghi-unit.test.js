import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  byteCorpo, centesimiDa, fetchPrestazioniAdmin, fetchStanzeAdmin, parseEsitoPrestazione, parseEsitoStanza, parsePrestazioneAdmin, parsePrezzo,
  parseStanza, prezzoTesto, scriviPrestazione, scriviStanza, trimJava, validaPrestazione, validaStanza,
} from '../src/api/catalogoAdmin.js'
import { prestazioneAdmin, stanza } from './admin-cataloghi-fixtures.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data })
const registra = (r) => { const calls = []; return { fn: async (o) => { calls.push(o); return r }, calls } }

describe('prezzo: decimale esatto, input italiano esplicito', () => {
  it('virgola o punto, al più due decimali, zero e massimo; centesimi interi e numero JSON dalla stringa', () => {
    expect(parsePrezzo('80')).toMatchObject({ centesimi: 8000, numero: 80 })
    expect(parsePrezzo(' 80,5 ')).toMatchObject({ centesimi: 8050, numero: 80.5, testo: '80.50' })
    expect(parsePrezzo('80.50')).toMatchObject({ centesimi: 8050 })
    expect(parsePrezzo('0,07')).toMatchObject({ centesimi: 7, numero: 0.07 })
    expect(parsePrezzo('0')).toMatchObject({ centesimi: 0, numero: 0 })
    expect(parsePrezzo('99999999,99')).toMatchObject({ centesimi: 9999999999, numero: 99999999.99 })
    expect(JSON.stringify({ prezzo: parsePrezzo('0,1').numero })).toBe('{"prezzo":0.1}')
  })

  it('rifiuta senza arrotondare: decimali eccessivi, migliaia, segni, esponenti, oltre il massimo', () => {
    for (const v of ['80,555', '1.234,50', '1,234.50', '-1', '+1', '1e2', '', '80,', ',5', '100000000', '8 0']) {
      expect(parsePrezzo(v).errore, v).toBeDefined()
    }
    expect(parsePrezzo('80,555').errore).toContain('non viene arrotondato')
  })

  it('risposte: centesimi solo da numeri con al più due decimali entro il dominio', () => {
    expect([centesimiDa(80), centesimiDa(80.5), centesimiDa(0.07), centesimiDa(99999999.99)]).toEqual([8000, 8050, 7, 9999999999])
    for (const v of [-1, 80.555, 1e-7, 100000000, '80', Number.NaN]) expect(centesimiDa(v), String(v)).toBeNull()
    expect(prezzoTesto(8050)).toBe('80,50')
  })
})

describe('validazioni come il backend', () => {
  it('stanza: trim di Java sul nome, descrizione vuota = null e mai trimmata, code point, surrogati', () => {
    expect(trimJava('\t Sala \u0001')).toBe('Sala')
    expect(trimJava(' Sala ')).toBe(' Sala ')
    expect(validaStanza({ nome: ' Sala A ', descrizione: '' }).dati).toEqual({ nome: 'Sala A', descrizione: null })
    expect(validaStanza({ nome: 'Sala', descrizione: '  primo piano  ' }).dati.descrizione).toBe('  primo piano  ')
    expect(validaStanza({ nome: '🏥'.repeat(100), descrizione: '' }).dati).toBeTruthy()
    expect(validaStanza({ nome: 'x'.repeat(101), descrizione: '' }).errori.nome).toBeDefined()
    expect(validaStanza({ nome: '   ', descrizione: '' }).errori.nome).toBeDefined()
    expect(validaStanza({ nome: 'Sala\uD800', descrizione: '' }).errori.nome).toBeDefined()
    expect(validaStanza({ nome: 'Sala', descrizione: 'é'.repeat(256) }).errori.descrizione).toBeDefined()
  })

  it('prestazione: durata intera 1–60 senza vincolo di divisibilità; corpo JSON al massimo 8192 byte', () => {
    const base = { nome: 'Visita', descrizione: '', prezzo: '80' }
    expect(validaPrestazione({ ...base, durata: '7' }).dati).toMatchObject({ durata: 7, descrizione: null, centesimi: 8000 })
    expect(validaPrestazione({ ...base, durata: ' 60 ' }).dati.durata).toBe(60)
    for (const d of ['0', '61', '30.0', '30,5', '-5', '', 'trenta']) expect(validaPrestazione({ ...base, durata: d }).errori.durata, d).toBeDefined()
    expect(validaPrestazione({ ...base, durata: '30', nome: 'x'.repeat(151) }).errori.nome).toBeDefined()
    // Descrizione entro 65535 byte ma corpo oltre 8192 byte: rifiutata prima dell'invio.
    const lunga = 'è'.repeat(4100)
    expect(validaPrestazione({ ...base, durata: '30', descrizione: lunga }).errori.descrizione).toContain('8192 byte')
    const limite = 'a'.repeat(8192 - byteCorpo({ nome: 'Visita', descrizione: '', durata: 30, prezzo: 80 }))
    expect(validaPrestazione({ ...base, durata: '30', descrizione: limite }).dati).toBeTruthy()
    expect(validaPrestazione({ ...base, durata: '30', descrizione: `${limite}a` }).errori.descrizione).toBeDefined()
  })
})

describe('proiezioni, payload ed esiti', () => {
  it('chiavi esatte; prezzo numerico e valido; ordine per ID', async () => {
    expect(parseStanza(stanza(1, { descrizione: 'x' }))).toEqual({ idStanza: 1, nome: 'Ambulatorio 1', descrizione: 'x' })
    expect(() => parseStanza({ ...stanza(1), extra: 1 })).toThrow(ProtocolError)
    expect(() => parseStanza(stanza(1, { descrizione: undefined }))).toThrow(ProtocolError)
    expect(parsePrestazioneAdmin(prestazioneAdmin(2, { prezzo: 80.5 })).centesimi).toBe(8050)
    for (const guasto of [{ prezzo: '80' }, { prezzo: 80.555 }, { durata: 0 }, { durata: 61 }, { durata: 30.5 }]) {
      expect(() => parsePrestazioneAdmin(prestazioneAdmin(2, guasto))).toThrow(ProtocolError)
    }
    await expect(fetchStanzeAdmin(registra(ok(200, [stanza(2), stanza(1)])).fn)).rejects.toThrow(ProtocolError)
    expect((await fetchPrestazioniAdmin(registra(ok(200, [prestazioneAdmin(1), prestazioneAdmin(3)])).fn)).map((p) => p.idPrestazione)).toEqual([1, 3])
  })

  it('POST e PUT con il corpo completo (descrizione null inclusa) e prezzo come numero JSON esatto', async () => {
    const r = registra(ok(201, stanza(9)))
    await scriviStanza(r.fn, null, { nome: 'Sala', descrizione: null })
    await scriviStanza(r.fn, 9, { nome: 'Sala', descrizione: 'Primo piano' })
    const dati = validaPrestazione({ nome: 'Visita', descrizione: '', durata: '20', prezzo: '40,05' }).dati
    await scriviPrestazione(r.fn, null, dati)
    await scriviPrestazione(r.fn, 3, dati)
    expect(r.calls.map((c) => `${c.method} ${c.path} ${JSON.stringify(c.json)}`)).toEqual([
      'POST /api/stanze {"nome":"Sala","descrizione":null}',
      'PUT /api/stanze/9 {"nome":"Sala","descrizione":"Primo piano"}',
      'POST /api/prestazioni {"nome":"Visita","descrizione":null,"durata":20,"prezzo":40.05}',
      'PUT /api/prestazioni/3 {"nome":"Visita","descrizione":null,"durata":20,"prezzo":40.05}',
    ])
  })

  it('esito valido solo con stato, ID e dati normalizzati coerenti', () => {
    const ds = { nome: 'Sala', descrizione: null }
    expect(parseEsitoStanza(ok(201, stanza(9, { nome: 'Sala' })), null, ds).idStanza).toBe(9)
    expect(() => parseEsitoStanza(ok(200, stanza(9, { nome: 'Sala' })), null, ds)).toThrow(ProtocolError)
    expect(() => parseEsitoStanza(ok(200, stanza(8, { nome: 'Sala' })), 9, ds)).toThrow(ProtocolError)
    expect(() => parseEsitoStanza(ok(201, stanza(9, { nome: 'sala' })), null, ds)).toThrow(ProtocolError)
    const dp = validaPrestazione({ nome: 'Visita', descrizione: '', durata: '20', prezzo: '40,5' }).dati
    expect(parseEsitoPrestazione(ok(200, prestazioneAdmin(3, { nome: 'Visita', durata: 20, prezzo: 40.5 })), 3, dp).centesimi).toBe(4050)
    expect(() => parseEsitoPrestazione(ok(200, prestazioneAdmin(3, { nome: 'Visita', durata: 20, prezzo: 40.51 })), 3, dp)).toThrow(ProtocolError)
    expect(() => parseEsitoPrestazione(ok(201, prestazioneAdmin(3, { nome: 'Visita', durata: 20, prezzo: 40.5 })), 3, dp)).toThrow(ProtocolError)
  })
})
