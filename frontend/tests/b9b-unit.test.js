import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  fetchMediciAdmin, fetchMedicoAdmin, mediciAdminPath, parseEsitoAbilitazione, parseEsitoMedico, parseMedicoAdmin, postCollegamento, postMedico,
  putAbilitazione, putDatiMedico, putStatoMedico, validaCollegamento, validaNuovoMedico, validaPersonali,
} from '../src/api/medici.js'
import { testoVerificaMedico } from '../src/admin/formatoMedici.js'
import { PASSWORD, dettaglioAdmin, medicoAdmin, prestazione } from './b9b-fixtures.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data })
const registra = (risposta) => { const calls = []; return { fn: async (o) => { calls.push(o); return risposta }, calls } }

describe('proiezione amministrativa', () => {
  it('9 campi esatti; la proiezione desk (6 campi) è rifiutata; stato anomalo conservato e segnalato', () => {
    expect(parseMedicoAdmin(medicoAdmin(5, { paziente: true }))).toMatchObject({ proiezione: 'medico-admin', paziente: true, ruolo: null, statoAnomalo: false })
    expect(parseMedicoAdmin(medicoAdmin(5, { stato: 'attivo ' }))).toMatchObject({ stato: 'attivo ', statoAnomalo: true })
    const { email, paziente, ruoloGestionale, ...desk } = medicoAdmin(5)
    expect(() => parseMedicoAdmin(desk)).toThrow(ProtocolError)
    expect(() => parseMedicoAdmin({ ...medicoAdmin(5), statoAccesso: 'ATTIVO' })).toThrow(ProtocolError)
    expect(() => parseMedicoAdmin(medicoAdmin(5, { ruoloGestionale: 'MEDICO' }))).toThrow(ProtocolError)
    expect('statoAccesso' in parseMedicoAdmin(medicoAdmin(5))).toBe(false)
  })

  it('elenco per ID crescente; dettaglio con chiavi esatte, ID corrispondente e abilitazioni ordinate', async () => {
    expect((await fetchMediciAdmin(registra(ok(200, [medicoAdmin(1), medicoAdmin(4)])).fn)).map((m) => m.idUtente)).toEqual([1, 4])
    await expect(fetchMediciAdmin(registra(ok(200, [medicoAdmin(4), medicoAdmin(1)])).fn)).rejects.toThrow(ProtocolError)
    expect((await fetchMedicoAdmin(registra(ok(200, dettaglioAdmin(5, [prestazione(1), prestazione(3)]))).fn, 5)).prestazioni).toHaveLength(2)
    await expect(fetchMedicoAdmin(registra(ok(200, dettaglioAdmin(6))).fn, 5)).rejects.toThrow(ProtocolError)
    await expect(fetchMedicoAdmin(registra(ok(200, { ...dettaglioAdmin(5), extra: 1 })).fn, 5)).rejects.toThrow(ProtocolError)
    await expect(fetchMedicoAdmin(registra(ok(200, dettaglioAdmin(5, [prestazione(3), prestazione(1)]))).fn, 5)).rejects.toThrow(ProtocolError)
    await expect(fetchMedicoAdmin(registra(null).fn, 0)).rejects.toThrow(TypeError)
  })

  it('filtri: stato e prestazione, valori validati', () => {
    expect(mediciAdminPath()).toBe('/api/medici')
    expect(mediciAdminPath({ stato: 'INATTIVO', idPrestazione: 3 })).toBe('/api/medici?stato=INATTIVO&idPrestazione=3')
    expect(() => mediciAdminPath({ stato: 'DISABILITATO' })).toThrow(TypeError)
  })
})

describe('varianti e payload', () => {
  it('creazione: sei campi esatti; collegamento: due; dati: varianti complete ed esclusive', async () => {
    const r = registra(ok(201, medicoAdmin(9)))
    const dati = { nome: 'N', cognome: 'C', email: 'n@example.test', specializzazione: 'S', numeroIscrizioneOrdine: 'O' }
    await postMedico(r.fn, dati, PASSWORD)
    await postCollegamento(r.fn, 9, dati)
    await putDatiMedico(r.fn, 9, 'PERSONALI', dati)
    await putDatiMedico(r.fn, 9, 'PROFESSIONALI', dati)
    await putStatoMedico(r.fn, 9, 'INATTIVO')
    await putAbilitazione(r.fn, 9, 'RIMUOVI', 2)
    expect(r.calls.map((c) => [c.method, c.path, c.json])).toEqual([
      ['POST', '/api/medici', { nome: 'N', cognome: 'C', email: 'n@example.test', password: PASSWORD, specializzazione: 'S', numeroIscrizioneOrdine: 'O' }],
      ['POST', '/api/medici/9/collegamento', { specializzazione: 'S', numeroIscrizioneOrdine: 'O' }],
      ['PUT', '/api/medici/9', { tipo: 'PERSONALI', nome: 'N', cognome: 'C', email: 'n@example.test' }],
      ['PUT', '/api/medici/9', { tipo: 'PROFESSIONALI', specializzazione: 'S', numeroIscrizioneOrdine: 'O' }],
      ['PUT', '/api/medici/9/stato', { stato: 'INATTIVO' }],
      ['PUT', '/api/medici/9/prestazioni', { operazione: 'RIMUOVI', idPrestazione: 2 }],
    ])
    expect(() => putDatiMedico(r.fn, 9, 'TUTTI', dati)).toThrow(TypeError)
    expect(() => putAbilitazione(r.fn, 9, 'SOSTITUISCI', 2)).toThrow(TypeError)
    expect(() => putStatoMedico(r.fn, 9, 'DISABILITATO')).toThrow(TypeError)
  })

  it('validazioni come il backend: strip, email minuscola, 150/50 caratteri, password mai normalizzata', () => {
    const base = { nome: ' Elena ', cognome: 'Medica', email: ' ELENA@Example.TEST ', specializzazione: ' Cardiologia ', numeroIscrizioneOrdine: ' ORD-1 ' }
    expect(validaNuovoMedico({ ...base, password: `  ${PASSWORD}  ` }).dati).toEqual({ nome: 'Elena', cognome: 'Medica', email: 'elena@example.test', specializzazione: 'Cardiologia', numeroIscrizioneOrdine: 'ORD-1' })
    expect(validaNuovoMedico({ ...base, password: 'corta' }).errori.password).toBeDefined()
    expect(validaCollegamento({ specializzazione: 'x'.repeat(151), numeroIscrizioneOrdine: 'x'.repeat(51) }).errori).toMatchObject({ specializzazione: expect.any(String), numeroIscrizioneOrdine: expect.any(String) })
    expect(validaPersonali({ nome: 'a', cognome: 'b', email: 'non-valida' }).errori.email).toBeDefined()
  })

  it('esiti: stato HTTP, ID e dati coerenti; email confrontata in forma normalizzata', () => {
    expect(parseEsitoMedico(ok(201, medicoAdmin(9, { email: 'NUOVO@EXAMPLE.TEST' })), 201, null, { email: 'nuovo@example.test' }).idUtente).toBe(9)
    expect(() => parseEsitoMedico(ok(200, medicoAdmin(9)), 201, null)).toThrow(ProtocolError)
    expect(() => parseEsitoMedico(ok(200, medicoAdmin(8)), 200, 9)).toThrow(ProtocolError)
    expect(() => parseEsitoMedico(ok(200, medicoAdmin(9, { stato: 'ATTIVO' })), 200, 9, { stato: 'INATTIVO' })).toThrow(ProtocolError)
    expect(parseEsitoAbilitazione(ok(200, { idMedico: 9, idPrestazione: 2, operazione: 'ASSEGNA' }), 9, 'ASSEGNA', 2)).toEqual({ idMedico: 9, idPrestazione: 2, operazione: 'ASSEGNA' })
    expect(() => parseEsitoAbilitazione(ok(200, { idMedico: 9, idPrestazione: 2, operazione: 'RIMUOVI' }), 9, 'ASSEGNA', 2)).toThrow(ProtocolError)
  })
})

describe('messaggi di verifica', () => {
  it('stato corrente senza attribuzione al tentativo', () => {
    const v = parseMedicoAdmin(medicoAdmin(5, { stato: 'INATTIVO' }))
    const x = (tipo, riepilogo) => ({ tipo, idUtente: 5, riepilogo: { nome: 'Elena', cognome: 'Medica5', ...riepilogo } })
    expect(testoVerificaMedico(x('stato-medico', { precedente: 'ATTIVO', richiesto: 'INATTIVO' }), 'come-richiesto', v)).toContain('non prova chi abbia eseguito')
    expect(testoVerificaMedico(x('stato-medico', { precedente: 'INATTIVO', richiesto: 'ATTIVO' }), 'precedente', v)).toContain('non risulta applicata')
    expect(testoVerificaMedico(x('collega', { richiesto: {} }), 'assente', null)).toContain('il collegamento non risulta applicato')
    expect(testoVerificaMedico(x('medico-crea', { email: 'e@example.test' }), 'come-richiesto', v)).toContain('non prova che sia stato creato dal tentativo')
    expect(testoVerificaMedico(x('abilitazione', { operazione: 'RIMUOVI', nomePrestazione: 'Visita' }), 'come-richiesto', v)).toContain('non è abilitato a «Visita»')
  })
})
