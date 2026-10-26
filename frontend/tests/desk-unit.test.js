import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  fetchMedicoDesk, fetchRichieste, mediciPath, parseConfigurazione, parseDecisionePrenotazione, parseDecisioneRimborso, parseMedicoDesk,
  parseRichiesta, parseRimborsoDesk, parseSegnalazione, parseSostituzione, postDecisionePrenotazione, postDecisioneRimborso, postSostituzione,
  putConfigurazione, richiestePath,
} from '../src/api/desk.js'
import { parseDisponibilita } from '../src/api/disponibilitaMedico.js'
import { configurato, slot } from './disponibilita-medico-fixtures.js'
import { OPERATORE, dettaglio, decisione, gestione, medicoDesk, prestazione, richiesta, rimborsoDesk, segnalazione, segnalazioneRichiesta } from './desk-fixtures.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data, headers: new Headers() })
const raccogli = () => {
  const richieste = []
  return { richieste, fn: (o) => { richieste.push(o); return Promise.resolve() } }
}

describe('richieste di prenotazione', () => {
  it('filtri del contratto: solo valorizzati, data esclusiva, intervallo anche aperto ma ordinato', () => {
    expect(richiestePath()).toBe('/api/prenotazioni/richieste')
    expect(richiestePath({ medicoId: 4, prestazioneId: 2, data: '2026-11-02' })).toBe('/api/prenotazioni/richieste?medicoId=4&prestazioneId=2&data=2026-11-02')
    expect(richiestePath({ dataDa: '2026-11-01' })).toBe('/api/prenotazioni/richieste?dataDa=2026-11-01')
    expect(() => richiestePath({ data: '2026-11-02', dataA: '2026-11-03' })).toThrow(TypeError)
    expect(() => richiestePath({ dataDa: '2026-11-03', dataA: '2026-11-02' })).toThrow(TypeError)
    expect(() => richiestePath({ medicoId: 0 })).toThrow(TypeError)
    expect(() => richiestePath({ data: '9999-12-31' })).toThrow(TypeError)
  })

  it('voci: solo RICHIESTA, prestazione e stanza facoltative, paziente e medico obbligatori, ID unici', async () => {
    const r = parseRichiesta(richiesta(12, { idStanza: null, nomeStanza: null }))
    expect(r).toMatchObject({ idPrenotazione: 12, prezzo: 80, stanza: null, indisponibilitaSegnalata: false })
    expect(r.medico).toEqual({ id: 4, nome: 'Elena', cognome: 'Sintetica' })
    for (const extra of [{ stato: 'CONFERMATA' }, { idPaziente: null, nomePaziente: null, cognomePaziente: null }, { prezzo: -1 }, { indisponibilitaSegnalata: 1 }]) {
      expect(() => parseRichiesta(richiesta(1, extra))).toThrow(ProtocolError)
    }
    await expect(fetchRichieste(async () => ok(200, [richiesta(1), richiesta(1)]))).rejects.toThrow(ProtocolError)
  })

  it('conferma, rifiuto e annullamento: idOperatore della sessione; annullamento con statoAtteso', () => {
    const { richieste, fn } = raccogli()
    postDecisionePrenotazione(fn, 'conferma', 12, OPERATORE)
    postDecisionePrenotazione(fn, 'rifiuta', 12, OPERATORE)
    postDecisionePrenotazione(fn, 'annulla', 12, OPERATORE, 'RICHIESTA')
    expect(richieste.map((r) => [r.path, r.json])).toEqual([
      ['/api/prenotazioni/12/conferma', { idOperatore: 3 }],
      ['/api/prenotazioni/12/rifiuta', { idOperatore: 3 }],
      ['/api/prenotazioni/12/annulla', { idOperatore: 3, statoAtteso: 'RICHIESTA' }],
    ])
    expect(() => postDecisionePrenotazione(fn, 'annulla', 12, OPERATORE, 'EFFETTUATA')).toThrow(TypeError)
    expect(() => postDecisionePrenotazione(fn, 'conferma', 12, 0)).toThrow(TypeError)
  })

  it('risposte: stato atteso per operazione, stesso operatore, effettivo = titolare per la conferma', () => {
    expect(parseDecisionePrenotazione(ok(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 4 })), 'conferma', 12, OPERATORE, 4).stato).toBe('CONFERMATA')
    expect(parseDecisionePrenotazione(ok(200, gestione(12, 'RIFIUTATA')), 'rifiuta', 12, OPERATORE).stato).toBe('RIFIUTATA')
    for (const [r, tipo] of [
      [ok(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 5 })), 'conferma'], [ok(200, gestione(12, 'ANNULLATA')), 'rifiuta'],
      [ok(200, gestione(12, 'RIFIUTATA', { idOperatore: 9 })), 'rifiuta'], [ok(200, gestione(13, 'RIFIUTATA')), 'rifiuta'],
      [ok(201, gestione(12, 'RIFIUTATA')), 'rifiuta'], [ok(200, gestione(12, 'RIFIUTATA', { dataOraGestione: null })), 'rifiuta'],
    ]) {
      expect(() => parseDecisionePrenotazione(r, tipo, 12, OPERATORE, 4)).toThrow(ProtocolError)
    }
  })
})

describe('segnalazioni', () => {
  it('medico indisponibile: titolare per RICHIESTA, effettivo per CONFERMATA; coerenza dell\'effettivo', () => {
    expect(parseSegnalazione(segnalazioneRichiesta(1)).indisponibile.id).toBe(4)
    expect(parseSegnalazione(segnalazione(2, { idMedicoEffettivo: 6, nomeMedicoEffettivo: 'Sara', cognomeMedicoEffettivo: 'Sostituta' })).indisponibile.id).toBe(6)
    expect(() => parseSegnalazione(segnalazione(3, { idMedicoEffettivo: null, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: null }))).toThrow(ProtocolError)
    expect(() => parseSegnalazione(segnalazione(4, { indisponibilitaSegnalata: false }))).toThrow(ProtocolError)
  })

  it('sostituzione: payload esatto dalla risorsa letta; sostituto diverso dall\'indisponibile', () => {
    const { richieste, fn } = raccogli()
    postSostituzione(fn, parseSegnalazione(segnalazioneRichiesta(7)), OPERATORE, 9)
    expect(richieste[0]).toEqual({ method: 'POST', path: '/api/prenotazioni/7/sostituisci-medico', json: { idOperatore: 3, idMedicoSostituto: 9, statoAtteso: 'RICHIESTA', idMedicoIndisponibile: 4 } })
    expect(() => postSostituzione(fn, parseSegnalazione(segnalazione(7)), OPERATORE, 4)).toThrow(TypeError)
    expect(parseSostituzione(ok(200, gestione(7, 'CONFERMATA', { idMedicoEffettivo: 9, indisponibilitaSegnalata: false })), 7, OPERATORE, 9).idMedicoEffettivo).toBe(9)
    for (const extra of [{ idMedicoEffettivo: 4 }, { indisponibilitaSegnalata: true }, { stato: 'RICHIESTA' }]) {
      expect(() => parseSostituzione(ok(200, gestione(7, 'CONFERMATA', { idMedicoEffettivo: 9, indisponibilitaSegnalata: false, ...extra })), 7, OPERATORE, 9)).toThrow(ProtocolError)
    }
  })
})

describe('rimborsi', () => {
  it('solo RICHIESTO; decisione con corpo {}; risposta con stato, operatore e data', () => {
    expect(parseRimborsoDesk(rimborsoDesk(5)).paziente).toEqual({ id: 70, nome: 'Paola', cognome: 'Prova' })
    expect(() => parseRimborsoDesk(rimborsoDesk(5, { stato: 'APPROVATO' }))).toThrow(ProtocolError)
    const { richieste, fn } = raccogli()
    postDecisioneRimborso(fn, 'approva', 5)
    expect(richieste[0]).toEqual({ method: 'POST', path: '/api/rimborsi/5/approva', json: {} })
    expect(parseDecisioneRimborso(ok(200, decisione(5, 'APPROVATO')), 'approva', 5, OPERATORE).stato).toBe('APPROVATO')
    for (const d of [decisione(5, 'RESPINTO'), decisione(6, 'APPROVATO'), decisione(5, 'APPROVATO', { idOperatore: 9 }), decisione(5, 'APPROVATO', { verificatoIl: null })]) {
      expect(() => parseDecisioneRimborso(ok(200, d), 'approva', 5, OPERATORE)).toThrow(ProtocolError)
    }
  })
})

describe('medici e configurazione', () => {
  it('proiezione desk: filtri stato e prestazione; dettaglio con prestazioni autorizzate dello stesso medico', async () => {
    expect(mediciPath({ stato: 'ATTIVO', idPrestazione: 2 })).toBe('/api/medici?stato=ATTIVO&idPrestazione=2')
    expect(() => mediciPath({ stato: 'SOSPESO' })).toThrow(TypeError)
    expect(parseMedicoDesk(medicoDesk(4, { specializzazione: null })).specializzazione).toBeNull()
    expect(() => parseMedicoDesk(medicoDesk(4, { stato: 'X' }))).toThrow(ProtocolError)
    const det = await fetchMedicoDesk(async () => ok(200, dettaglio(4, [prestazione(2), prestazione(5)])), 4)
    expect(det.prestazioni.map((p) => p.idPrestazione)).toEqual([2, 5])
    await expect(fetchMedicoDesk(async () => ok(200, dettaglio(5)), 4)).rejects.toThrow(ProtocolError)
  })

  it('PUT configurazione {idPrestazione, idStanza}; risposta con stesso slot e configurazione richiesta', () => {
    const { richieste, fn } = raccogli()
    putConfigurazione(fn, 9, 2, 3)
    expect(richieste[0]).toEqual({ method: 'PUT', path: '/api/disponibilita/9/configurazione', json: { idPrestazione: 2, idStanza: 3 } })
    const s = parseDisponibilita(slot(9, '2026-11-02T09:00:00'), 4)
    expect(parseConfigurazione(ok(200, configurato(9, '2026-11-02T09:00:00')), s, 4, 2, 3).configurata).toBe(true)
    for (const d of [configurato(9, '2026-11-02T09:00:00', { idStanza: 4, nomeStanza: 'X' }), configurato(9, '2026-11-02T10:00:00'), slot(9, '2026-11-02T09:00:00'), configurato(8, '2026-11-02T09:00:00')]) {
      expect(() => parseConfigurazione(ok(200, d), s, 4, 2, 3)).toThrow(ProtocolError)
    }
  })
})


it('segnalazione: identità paziente obbligatoria e limitata a ID, nome e cognome', () => {
  expect(parseSegnalazione(segnalazione(11)).paziente).toEqual({ id: 81, nome: 'Paola', cognome: 'Prova11' })
  for (const extra of [{ idPaziente: null }, { idPaziente: 0 }, { nomePaziente: 42 }, { cognomePaziente: null }]) {
    expect(() => parseSegnalazione(segnalazione(11, extra))).toThrow(ProtocolError)
  }
})
