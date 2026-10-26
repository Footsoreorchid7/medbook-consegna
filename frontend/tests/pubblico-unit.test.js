// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchDisponibilitaPrenotabili, fetchPrestazioni, prenotabiliPath } from '../src/api/catalogo.js'
import { postRegister } from '../src/api/auth.js'
import { ProtocolError } from '../src/api/http.js'
import { formatLocalDate, formatLocalTime, isLocalDate, parseLocalDateTime, todayLocal } from '../src/utils/datetime.js'
import { formatEuro } from '../src/utils/money.js'
import { hasLoneSurrogate, minimoNuovaPassword, passwordError, validaRegistrazione } from '../src/utils/validazione.js'
import { installFetch, jsonResponse, routedFetch } from './helpers.js'

let net
beforeEach(() => { net = installFetch(routedFetch()) })

describe('timestamp locali', () => {
  let toISOString
  beforeEach(() => { toISOString = vi.spyOn(Date.prototype, 'toISOString') })
  afterEach(() => expect(toISOString).not.toHaveBeenCalled())

  it.each([
    ['2026-10-15T09:00:00', 'giovedì 15/10/2026', '09:00'],
    ['2026-12-31T23:30:00', 'giovedì 31/12/2026', '23:30'],
    ['2027-01-01T00:00', 'venerdì 01/01/2027', '00:00'],
    ['2026-03-29T02:30:00', 'domenica 29/03/2026', '02:30'],
    ['2026-10-25T02:30:00', 'domenica 25/10/2026', '02:30'],
    ['2028-02-29T07:05:00.000', 'martedì 29/02/2028', '07:05'],
  ])('%s resta %s alle %s, senza conversioni di fuso', (value, data, ora) => {
    const parsed = parseLocalDateTime(value)
    expect(formatLocalDate(parsed)).toBe(data)
    expect(formatLocalTime(parsed)).toBe(ora)
    expect(parsed.date).toBe(value.slice(0, 10))
  })

  it.each([
    '2026-10-15T09:00:00Z', '2026-10-15T09:00:00+02:00', '2026-02-30T09:00:00', '2026-10-15 09:00:00',
    '2026-10-15T24:00:00', '2026-10-15', null, 1760511600000,
  ])('rifiuta %s', (value) => {
    expect(parseLocalDateTime(value)).toBeNull()
  })

  it('date ISO locali e data odierna dai getter locali', () => {
    expect(isLocalDate('2028-02-29')).toBe(true)
    expect(isLocalDate('2027-02-29')).toBe(false)
    expect(isLocalDate('15/10/2026')).toBe(false)
    expect(todayLocal(new Date(2026, 9, 15, 23, 59))).toBe('2026-10-15')
  })
})

describe('importi', () => {
  it('formatta in euro senza calcoli', () => {
    expect(formatEuro(50)).toMatch(/^50,00\s€$/)
    // CLDR it-IT: separatore delle migliaia solo da cinque cifre.
    expect(formatEuro(1234.5)).toMatch(/^1234,50\s€$/)
    expect(formatEuro(12345.6)).toMatch(/^12\.345,60\s€$/)
    expect(formatEuro(0)).toMatch(/^0,00\s€$/)
    expect(formatEuro(null)).toBe('—')
  })
})

describe('API pubbliche', () => {
  it('query delle disponibilità solo con i filtri valorizzati', () => {
    expect(prenotabiliPath()).toBe('/api/disponibilita/prenotabili')
    expect(prenotabiliPath({ prestazioneId: '', data: '' })).toBe('/api/disponibilita/prenotabili')
    expect(prenotabiliPath({ prestazioneId: 3, data: null })).toBe('/api/disponibilita/prenotabili?prestazioneId=3')
    expect(prenotabiliPath({ data: '2026-10-20' })).toBe('/api/disponibilita/prenotabili?data=2026-10-20')
    expect(prenotabiliPath({ prestazioneId: 3, data: '2026-10-20' }))
      .toBe('/api/disponibilita/prenotabili?prestazioneId=3&data=2026-10-20')
    expect(() => prenotabiliPath({ prestazioneId: 0 })).toThrow(TypeError)
    expect(() => prenotabiliPath({ data: '20/10/2026' })).toThrow(TypeError)
  })

  it('catalogo validato, descrizione facoltativa', async () => {
    net.on('GET /api/prestazioni', jsonResponse(200, [
      { idPrestazione: 1, nome: 'Visita', descrizione: null, durata: 30, prezzo: 50 },
      { idPrestazione: 2, nome: 'Ecografia', descrizione: 'Addome', durata: 45, prezzo: 80.5 },
    ]))
    const voci = await fetchPrestazioni()
    expect(voci.map((v) => [v.nome, v.descrizione, v.durata, v.prezzo])).toEqual([['Visita', null, 30, 50], ['Ecografia', 'Addome', 45, 80.5]])
  })

  it.each([
    [{}],
    [[{ idPrestazione: 1, nome: 'Visita', durata: 30 }]],
    [[{ idPrestazione: '1', nome: 'Visita', durata: 30, prezzo: 50 }]],
  ])('catalogo non conforme: errore di protocollo', async (body) => {
    net.on('GET /api/prestazioni', jsonResponse(200, body))
    await expect(fetchPrestazioni()).rejects.toBeInstanceOf(ProtocolError)
  })

  it('disponibilità con orario non locale: errore di protocollo', async () => {
    net.on('GET /api/disponibilita/prenotabili', jsonResponse(200, [{
      idDisponibilita: 1, dataOraInizio: '2026-10-20T09:00:00Z', dataOraFine: '2026-10-20T10:00:00', idMedico: 4,
      nomeMedico: 'Elena', cognomeMedico: 'Test', idPrestazione: 1, nomePrestazione: 'Visita', durataPrestazione: 30, prezzoPrestazione: 50,
    }]))
    await expect(fetchDisponibilitaPrenotabili({})).rejects.toBeInstanceOf(ProtocolError)
  })

  it('registrazione: telefono vuoto omesso, nessun campo aggiuntivo', async () => {
    net.on('POST /api/auth/register', jsonResponse(201, { message: 'ok' }), jsonResponse(201, { message: 'ok' }))
    const base = { nome: 'A', cognome: 'B', email: 'a@example.test', password: ' p a s s ', codiceFiscale: 'X'.repeat(16), dataNascita: '1990-01-01' }
    await postRegister({ ...base, telefono: '  ', conferma: 'non inviata' })
    await postRegister({ ...base, telefono: '+39 333 1234567' })
    expect(JSON.parse(net.calls[0].body)).toEqual(base)
    expect(JSON.parse(net.calls[1].body)).toEqual({ ...base, telefono: '+39 333 1234567' })
  })
})

describe('validazione di cortesia', () => {
  const valido = {
    nome: 'Anna', cognome: 'Prova', email: 'anna@example.test', password: '12345678', conferma: '12345678',
    codiceFiscale: 'prvnna90a41z999x', dataNascita: '1990-01-01', telefono: '',
  }

  it('dati plausibili: nessun errore', () => {
    expect(validaRegistrazione(valido, '2026-10-15')).toEqual({})
  })

  it.each([
    ['nome', { nome: '   ' }],
    ['cognome', { cognome: 'x'.repeat(101) }],
    ['email', { email: 'anna@localhost' }],
    ['email', { email: '.anna@example.test' }],
    ['password', { password: '1234567', conferma: '1234567' }],
    ['conferma', { conferma: '12345679' }],
    ['codiceFiscale', { codiceFiscale: 'PRVNNA90A41Z99' }],
    ['dataNascita', { dataNascita: '2026-10-16' }],
    ['dataNascita', { dataNascita: '0999-01-01' }],
    ['telefono', { telefono: 'chiamami' }],
  ])('campo %s', (campo, override) => {
    expect(Object.keys(validaRegistrazione({ ...valido, ...override }, '2026-10-15'))).toEqual([campo])
  })

  it('password mai trimmata: gli spazi contano', () => {
    expect(passwordError('       x', 8)).toBeNull()
    expect(passwordError(' 1234567', 8)).toBeNull()
    expect(passwordError('x'.repeat(1025), 8)).not.toBeNull()
    expect(hasLoneSurrogate('abc\uD800def')).toBe(true)
    expect(hasLoneSurrogate('abc😀def')).toBe(false)
  })

  it('minimo nuova password: 12 per medico o ruolo gestionale, 8 altrimenti', () => {
    expect(minimoNuovaPassword({ paziente: true, medico: false, ruoloGestionale: null })).toBe(8)
    expect(minimoNuovaPassword({ paziente: true, medico: true, ruoloGestionale: null })).toBe(12)
    expect(minimoNuovaPassword({ paziente: false, medico: false, ruoloGestionale: 'OPERATORE_DESK' })).toBe(12)
    expect(minimoNuovaPassword({ paziente: false, medico: false, ruoloGestionale: 'AMMINISTRATORE' })).toBe(12)
  })
})
