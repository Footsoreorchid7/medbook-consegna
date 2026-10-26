import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  MAX_SLOT, deleteDisponibilita, elencoPath, fetchDisponibilita, fineSlot, normalizzaDataOra, parseCreazione, parseDisponibilita,
  parseEliminazione, parseOrario, postCreazione, putOrario, slotAttesi, validaIntervallo,
} from '../src/api/disponibilitaMedico.js'
import { createSession } from '../src/session/session.js'
import { ELIMINA, MEDICO, configurato, medico, serie, slot, vuota } from './disponibilita-medico-fixtures.js'
import { CSRF_A, installFetch, meOk, routedFetch } from './helpers.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data, headers: new Headers() })

describe('orari locali senza fuso', () => {
  it('normalizzazione al secondo; frazioni solo se nulle; calendario e anni del backend', () => {
    expect(normalizzaDataOra('2026-11-02T09:00')).toBe('2026-11-02T09:00:00')
    expect(normalizzaDataOra('2026-11-02T09:00:30')).toBe('2026-11-02T09:00:30')
    expect(normalizzaDataOra('2026-11-02T09:00:30.000')).toBe('2026-11-02T09:00:30')
    for (const v of ['2026-11-02T09:00:30.5', '2026-02-30T09:00', '0999-12-31T23:00', '2026-11-02 09:00', '2026-11-02T24:00', '', null]) {
      expect(normalizzaDataOra(v)).toBeNull()
    }
  })

  it('aritmetica di calendario: nessun effetto dell\'ora legale del browser', () => {
    expect(fineSlot('2026-03-29T01:30:00')).toBe('2026-03-29T02:30:00')
    expect(fineSlot('2026-10-25T02:00:00')).toBe('2026-10-25T03:00:00')
    expect(fineSlot('2026-12-31T23:00:00')).toBe('2027-01-01T00:00:00')
    expect(validaIntervallo('2026-03-29T01:00', '2026-03-29T04:00').slot).toBe(3)
  })
})

describe('limiti della creazione', () => {
  it('multiplo esatto di 60 minuti, con precisione al secondo', () => {
    expect(validaIntervallo('2026-11-02T09:00', '2026-11-02T10:00')).toMatchObject({ errore: null, slot: 1 })
    expect(validaIntervallo('2026-11-02T09:00:30', '2026-11-02T12:00:30')).toMatchObject({ errore: null, slot: 3 })
    expect(validaIntervallo('2026-11-02T09:00', '2026-11-02T10:30').errore).toBe('durata')
    expect(validaIntervallo('2026-11-02T09:00:00', '2026-11-02T10:00:01').errore).toBe('durata')
  })

  it('ordine, formato e massimo 1000 slot (limite incluso)', () => {
    expect(validaIntervallo('2026-11-02T10:00', '2026-11-02T09:00').errore).toBe('ordine')
    expect(validaIntervallo('2026-11-02T10:00', '2026-11-02T10:00').errore).toBe('ordine')
    expect(validaIntervallo('x', '2026-11-02T10:00').errore).toBe('inizio')
    expect(validaIntervallo('2026-11-02T10:00', '').errore).toBe('fine')
    // 1000 ore dal 2 novembre: 41 giorni e 16 ore.
    expect(validaIntervallo('2026-11-02T00:00', '2026-12-13T16:00')).toMatchObject({ errore: null, slot: MAX_SLOT })
    expect(validaIntervallo('2026-11-02T00:00', '2026-12-13T17:00').errore).toBe('troppi')
  })
})

describe('elenco personale', () => {
  it('medicoId solo come parametro dell\'identità', () => {
    expect(elencoPath(MEDICO)).toBe('/api/disponibilita?medicoId=4')
    expect(() => elencoPath(0)).toThrow(TypeError)
  })

  it('stati di configurazione: nessuna, parziale, completa; flag coerente', () => {
    expect(parseDisponibilita(slot(1, '2026-11-02T09:00:00'), MEDICO)).toMatchObject({ prestazione: null, stanza: null, configurata: false })
    expect(parseDisponibilita(configurato(2, '2026-11-02T10:00:00'), MEDICO).stanza).toEqual({ id: 3, nome: 'Ambulatorio 1' })
    expect(parseDisponibilita(slot(3, '2026-11-02T11:00:00', { idStanza: 3, nomeStanza: 'A' }), MEDICO).configurata).toBe(false)
    expect(() => parseDisponibilita(slot(4, '2026-11-02T11:00:00', { configurata: true }), MEDICO)).toThrow(ProtocolError)
    expect(() => parseDisponibilita(slot(5, '2026-11-02T11:00:00', { idMedico: 5 }), MEDICO)).toThrow(ProtocolError)
    expect(() => parseDisponibilita(slot(6, '2026-11-02T11:00:00', { dataOraFine: '2026-11-02T11:00:00' }), MEDICO)).toThrow(ProtocolError)
  })

  it('ordine del backend, vuoto ammesso, duplicati rifiutati', async () => {
    const req = (data) => async () => ok(200, data)
    const voci = await fetchDisponibilita(req([slot(9, '2026-11-02T09:00:00'), slot(3, '2026-11-02T10:00:00')]), MEDICO)
    expect(voci.map((v) => v.idDisponibilita)).toEqual([9, 3])
    expect(await fetchDisponibilita(req([]), MEDICO)).toEqual([])
    await expect(fetchDisponibilita(req([slot(1, '2026-11-02T09:00:00'), slot(1, '2026-11-02T10:00:00')]), MEDICO)).rejects.toThrow(ProtocolError)
  })
})

describe('creazione', () => {
  it('corpo {idMedico, dataOraInizio, dataOraFine} con orari normalizzati', () => {
    const richieste = []
    postCreazione((o) => { richieste.push(o); return Promise.resolve() }, MEDICO, '2026-11-02T09:00:00', '2026-11-02T11:00:00')
    expect(richieste).toEqual([{ method: 'POST', path: '/api/disponibilita', json: { idMedico: 4, dataOraInizio: '2026-11-02T09:00:00', dataOraFine: '2026-11-02T11:00:00' } }])
    expect(() => postCreazione(() => {}, MEDICO, '2026-11-02T09:00', '2026-11-02T11:00')).toThrow(TypeError)
    expect(() => postCreazione(() => {}, MEDICO, '2026-11-02T09:00:00', '2026-11-02T10:30:00')).toThrow(TypeError)
  })

  it('successo solo con 201 e la copertura esatta dell\'intervallo', () => {
    const [a, b] = ['2026-11-02T09:00:00', '2026-11-02T12:00:00']
    expect(parseCreazione(ok(201, serie(20, a, 3)), MEDICO, a, b).map((s) => s.idDisponibilita)).toEqual([20, 21, 22])
    expect(slotAttesi(a, 3).at(-1)).toEqual({ inizio: '2026-11-02T11:00:00', fine: '2026-11-02T12:00:00' })
    const casi = [
      ok(200, serie(20, a, 3)), ok(201, serie(20, a, 2)), ok(201, serie(20, '2026-11-02T10:00:00', 3)),
      ok(201, [...serie(20, a, 2), slot(20, '2026-11-02T11:00:00')]), ok(201, serie(20, a, 3).map((s, k) => (k === 1 ? { ...s, idMedico: 5 } : s))),
      ok(201, serie(20, a, 3).map((s, k) => (k === 2 ? { ...s, idStanza: 3, nomeStanza: 'A' } : s))), ok(201, [...serie(20, a, 3)].reverse()), ok(201),
    ]
    for (const r of casi) expect(() => parseCreazione(r, MEDICO, a, b)).toThrow(ProtocolError)
  })
})

describe('modifica e eliminazione', () => {
  it('PUT orario: esattamente 60 minuti con idMedico della sessione; risposta con lo stesso slot e il nuovo orario', () => {
    const richieste = []
    putOrario((o) => { richieste.push(o); return Promise.resolve() }, 7, MEDICO, '2026-11-03T08:00:00')
    expect(richieste[0]).toEqual({ method: 'PUT', path: '/api/disponibilita/7/orario', json: { idMedico: 4, dataOraInizio: '2026-11-03T08:00:00', dataOraFine: '2026-11-03T09:00:00' } })
    expect(parseOrario(ok(200, slot(7, '2026-11-03T08:00:00')), 7, MEDICO, '2026-11-03T08:00:00').inizio).toBe('2026-11-03T08:00:00')
    for (const r of [ok(200, slot(8, '2026-11-03T08:00:00')), ok(200, slot(7, '2026-11-03T09:00:00')), ok(201, slot(7, '2026-11-03T08:00:00')), ok(200)]) {
      expect(() => parseOrario(r, 7, MEDICO, '2026-11-03T08:00:00')).toThrow(ProtocolError)
    }
  })

  it('DELETE: medicoId nella query; successo solo con 204 senza corpo', () => {
    const richieste = []
    deleteDisponibilita((o) => { richieste.push(o); return Promise.resolve() }, 7, MEDICO)
    expect(richieste[0]).toEqual({ method: 'DELETE', path: '/api/disponibilita/7?medicoId=4' })
    expect(parseEliminazione({ status: 204, hasBody: false })).toBe(true)
    for (const r of [{ status: 200, hasBody: true, data: {} }, { status: 200, hasBody: false }, { status: 204, hasBody: true, data: {} }]) {
      expect(() => parseEliminazione(r)).toThrow(ProtocolError)
    }
  })

  it('DELETE reale tramite la sessione: CSRF inviato, 204 accettato', async () => {
    const net = installFetch(routedFetch())
    net.on('GET /api/auth/me', meOk(medico()))
    const session = createSession()
    await session.ensureBootstrap()
    net.on(ELIMINA(7), vuota())
    expect(parseEliminazione(await deleteDisponibilita(session.protectedRequest, 7, MEDICO))).toBe(true)
    expect(net.callsTo(ELIMINA(7))[0].headers.get('X-CSRF-Token')).toBe(CSRF_A)
  })
})
