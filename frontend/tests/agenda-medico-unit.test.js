import { describe, expect, it } from 'vitest'
import { ProtocolError } from '../src/api/http.js'
import {
  MAX_BYTES_REFERTO, agendaPath, controllaPdf, fetchAgenda, giorniTra, parseAppuntamentoAgenda, parseRefertoCaricato,
  parseSegnalazione, parseVisitaRegistrata, postReferto, postRegistraVisita, postSegnalazione,
} from '../src/api/medico.js'
import { createSession } from '../src/session/session.js'
import { MEDICO, caricato, effettuata, medico, pdf, registrata, richiesta, voce } from './agenda-medico-fixtures.js'
import { CSRF_A, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ok = (status, data) => ({ status, hasBody: data !== undefined, data, headers: new Headers() })

describe('agenda: query del contratto', () => {
  it('default senza date: la giornata corrente la decide il backend', () => {
    expect(agendaPath({ medicoId: MEDICO })).toBe('/api/prenotazioni/agenda?medicoId=4')
  })

  it('data singola oppure intervallo, mai insieme; intervallo completo e ordinato', () => {
    expect(agendaPath({ medicoId: 4, data: '2026-10-19' })).toBe('/api/prenotazioni/agenda?medicoId=4&data=2026-10-19')
    expect(agendaPath({ medicoId: 4, dataDa: '2026-10-01', dataA: '2026-10-31' }))
      .toBe('/api/prenotazioni/agenda?medicoId=4&dataDa=2026-10-01&dataA=2026-10-31')
    expect(() => agendaPath({ medicoId: 4, data: '2026-10-19', dataDa: '2026-10-19', dataA: '2026-10-20' })).toThrow(TypeError)
    expect(() => agendaPath({ medicoId: 4, dataDa: '2026-10-19' })).toThrow(TypeError)
    expect(() => agendaPath({ medicoId: 4, dataDa: '2026-10-20', dataA: '2026-10-19' })).toThrow(TypeError)
  })

  it('al massimo 31 giorni inclusi (DAYS.between < 31), anche a cavallo di mesi e anni bisestili', () => {
    expect(giorniTra('2026-10-01', '2026-10-31')).toBe(30)
    expect(() => agendaPath({ medicoId: 4, dataDa: '2026-10-01', dataA: '2026-11-01' })).toThrow(TypeError)
    expect(agendaPath({ medicoId: 4, dataDa: '2028-02-01', dataA: '2028-03-02' })).toContain('dataA=2028-03-02')
    expect(() => agendaPath({ medicoId: 4, dataDa: '2028-02-01', dataA: '2028-03-03' })).toThrow(TypeError)
  })

  it('date non rappresentabili dal backend e medico non valido rifiutati prima della richiesta', () => {
    for (const data of ['0999-12-31', '9999-12-31', '2026-02-30', '19-10-2026']) {
      expect(() => agendaPath({ medicoId: 4, data })).toThrow(TypeError)
    }
    expect(() => agendaPath({ medicoId: 0 })).toThrow(TypeError)
    expect(() => agendaPath({ medicoId: '4' })).toThrow(TypeError)
  })
})

describe('agenda: voci validate', () => {
  it('campi del contratto, null ammessi per prestazione e stanza', () => {
    const a = parseAppuntamentoAgenda(voce(12, { idPrestazione: null, nomePrestazione: null, durataPrestazione: null, idStanza: null, nomeStanza: null }), MEDICO)
    expect(a).toMatchObject({ idPrenotazione: 12, stato: 'CONFERMATA', nomePrestazione: null, durata: null, stanza: null, indisponibilitaSegnalata: false })
    expect(a.paziente).toEqual({ id: 82, nome: 'Paola', cognome: 'Prova12' })
    expect(a.inizio).toMatchObject({ date: '2026-10-19', hour: 9 })
  })

  it('RICHIESTA del titolare senza effettivo; gli altri stati dell\'effettivo', () => {
    expect(parseAppuntamentoAgenda(richiesta(1), MEDICO).idMedicoEffettivo).toBeNull()
    expect(() => parseAppuntamentoAgenda(richiesta(1, { idMedicoEffettivo: MEDICO }), MEDICO)).toThrow(ProtocolError)
    expect(() => parseAppuntamentoAgenda(voce(1, { idMedicoEffettivo: null }), MEDICO)).toThrow(ProtocolError)
    // Sostituzione: titolare diverso, effettivo corrente = medico richiedente.
    expect(parseAppuntamentoAgenda(voce(1, { idMedicoTitolare: 5 }), MEDICO).idMedicoTitolare).toBe(5)
    // Voce che non riguarda il medico richiedente: errore di protocollo.
    expect(() => parseAppuntamentoAgenda(voce(1, { idMedicoEffettivo: 5 }), MEDICO)).toThrow(ProtocolError)
    expect(() => parseAppuntamentoAgenda(richiesta(1, { idMedicoTitolare: 5 }), MEDICO)).toThrow(ProtocolError)
  })

  it('stati fuori agenda, flag non booleano, paziente o orari mancanti rifiutati', () => {
    for (const extra of [{ stato: 'ANNULLATA' }, { indisponibilitaSegnalata: 'false' }, { nomePaziente: null }, { dataOraFine: null }, { idDisponibilita: 0 }]) {
      expect(() => parseAppuntamentoAgenda(voce(1, extra), MEDICO)).toThrow(ProtocolError)
    }
  })

  it('elenco nell\'ordine del backend, vuoto ammesso, duplicati e non-array rifiutati', async () => {
    const req = (data) => async () => ok(200, data)
    const voci = await fetchAgenda(req([voce(3), voce(1), voce(2)]), { medicoId: MEDICO })
    expect(voci.map((v) => v.idPrenotazione)).toEqual([3, 1, 2])
    expect(await fetchAgenda(req([]), { medicoId: MEDICO })).toEqual([])
    await expect(fetchAgenda(req([voce(1), voce(1)]), { medicoId: MEDICO })).rejects.toThrow(ProtocolError)
    await expect(fetchAgenda(req({}), { medicoId: MEDICO })).rejects.toThrow(ProtocolError)
  })
})

describe('registrazione della visita', () => {
  it('corpo esattamente {} e successo solo con 200, stessa prenotazione, EFFETTUATA', () => {
    const richieste = []
    postRegistraVisita((o) => { richieste.push(o); return Promise.resolve() }, 12)
    expect(richieste).toEqual([{ method: 'POST', path: '/api/prenotazioni/12/registra-visita', json: {} }])
    expect(parseVisitaRegistrata(ok(200, registrata(12)), 12)).toEqual({ idPrenotazione: 12, stato: 'EFFETTUATA' })
    for (const r of [ok(201, registrata(12)), ok(200, registrata(13)), ok(200, { idPrenotazione: 12, stato: 'CONFERMATA' }), ok(200)]) {
      expect(() => parseVisitaRegistrata(r, 12)).toThrow(ProtocolError)
    }
  })
})

describe('segnalazione di indisponibilità', () => {
  it('{medicoId, idPrenotazioni} senza duplicati né vuoti', () => {
    const richieste = []
    const fn = (o) => { richieste.push(o); return Promise.resolve() }
    postSegnalazione(fn, MEDICO, [12, 13])
    expect(richieste[0]).toEqual({ method: 'POST', path: '/api/prenotazioni/segnala-indisponibilita', json: { medicoId: 4, idPrenotazioni: [12, 13] } })
    expect(() => postSegnalazione(fn, MEDICO, [])).toThrow(TypeError)
    expect(() => postSegnalazione(fn, MEDICO, [12, 12])).toThrow(TypeError)
    expect(() => postSegnalazione(fn, MEDICO, [0])).toThrow(TypeError)
    expect(richieste).toHaveLength(1)
  })

  it('risposta atomica: stesso insieme di ID e flag true; ogni difformità è un esito non conforme', () => {
    expect(parseSegnalazione(ok(200, { idPrenotazioni: [13, 12], indisponibilitaSegnalata: true }), [12, 13]).idPrenotazioni).toEqual([13, 12])
    for (const d of [
      { idPrenotazioni: [12], indisponibilitaSegnalata: true },
      { idPrenotazioni: [12, 13, 14], indisponibilitaSegnalata: true },
      { idPrenotazioni: [12, 12], indisponibilitaSegnalata: true },
      { idPrenotazioni: [12, 13], indisponibilitaSegnalata: false },
      { idPrenotazioni: [12, 99], indisponibilitaSegnalata: true },
    ]) {
      expect(() => parseSegnalazione(ok(200, d), [12, 13])).toThrow(ProtocolError)
    }
    expect(() => parseSegnalazione(ok(201, { idPrenotazioni: [12, 13], indisponibilitaSegnalata: true }), [12, 13])).toThrow(ProtocolError)
  })
})

describe('referto: controlli locali e contratto binario', () => {
  it('controlli essenziali: assente, vuoto, oltre 10 MiB, firma non PDF', async () => {
    expect(await controllaPdf(null)).toBe('assente')
    expect(await controllaPdf(new File([], 'vuoto.pdf'))).toBe('vuoto')
    expect(await controllaPdf({ size: MAX_BYTES_REFERTO + 1, slice: () => new Blob(['%PDF-']) })).toBe('troppo-grande')
    expect(await controllaPdf({ size: MAX_BYTES_REFERTO, slice: () => new Blob(['%PDF-']) })).toBeNull()
    expect(await controllaPdf(new File(['<html>'], 'finto.pdf', { type: 'application/pdf' }))).toBe('non-pdf')
    expect(await controllaPdf(pdf())).toBeNull()
  })

  it('il file è il corpo, con Content-Type application/pdf: niente multipart, JSON o nome file', () => {
    const richieste = []
    const file = pdf()
    postReferto((o) => { richieste.push(o); return Promise.resolve() }, 12, file)
    expect(richieste).toEqual([{ method: 'POST', path: '/api/prenotazioni/12/referto', body: file, contentType: 'application/pdf' }])
    expect(() => postReferto(() => {}, 12, new File([], 'x.pdf'))).toThrow(TypeError)
    expect(() => postReferto(() => {}, 12, null)).toThrow(TypeError)
  })

  it('richiesta reale tramite la sessione: corpo binario, header esatto, CSRF, nessun JSON', async () => {
    const net = installFetch(routedFetch())
    net.on('GET /api/auth/me', meOk(medico()))
    const session = createSession()
    await session.ensureBootstrap()
    const file = pdf()
    net.on('POST /api/prenotazioni/12/referto', jsonResponse(201, caricato(12)))
    const result = await postReferto(session.protectedRequest, 12, file)
    const [call] = net.callsTo('POST /api/prenotazioni/12/referto')
    expect(call.body).toBe(file)
    expect(call.body instanceof FormData).toBe(false)
    expect(call.headers.get('Content-Type')).toBe('application/pdf')
    expect(call.headers.get('X-CSRF-Token')).toBe(CSRF_A)
    expect(parseRefertoCaricato(result, 12)).toMatchObject({ idReferto: 9, idPrenotazione: 12 })
  })

  it('successo solo con 201, idReferto valido, stessa prenotazione, nome e data del server', () => {
    expect(parseRefertoCaricato(ok(201, caricato(12)), 12).caricato).toMatchObject({ date: '2026-10-19', hour: 11 })
    for (const r of [ok(200, caricato(12)), ok(201, caricato(13)), ok(201, caricato(12, { idReferto: 0 })), ok(201, caricato(12, { nomeFile: '' })),
      ok(201, caricato(12, { caricatoIl: 'ieri' })), ok(201)]) {
      expect(() => parseRefertoCaricato(r, 12)).toThrow(ProtocolError)
    }
  })

  it('agenda e scritture: stati RICHIESTA/EFFETTUATA ammessi dal parser', () => {
    expect(parseAppuntamentoAgenda(effettuata(5), MEDICO).stato).toBe('EFFETTUATA')
  })
})
