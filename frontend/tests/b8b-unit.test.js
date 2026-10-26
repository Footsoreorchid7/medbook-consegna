import { describe, expect, it } from 'vitest'
import {
  coperturaDesk, fetchPrenotazioniDesk, parseMancataErogazione, parsePagamentoSede, parsePrenotazioneDesk, postMancataErogazione,
  postPagamentoSede, prenotazioniDeskPath,
} from '../src/api/desk.js'
import { ProtocolError } from '../src/api/http.js'
import { OPERATORE } from './b7-fixtures.js'
import {
  GIORNO, annullataDaRichiesta, incasso, pagamentoDesk, pagatoOnline, pagatoSede, prenotazioneDesk, rimborsoAnnidato,
} from './b8-fixtures.js'

const ok = (data, status = 200) => ({ status, hasBody: true, data })
const errato = (d) => () => parsePrenotazioneDesk(d)

describe('copertura e percorso della lettura desk', () => {
  it('periodo obbligatorio, giorno singolo o intervallo di al massimo 31 giorni inclusi', () => {
    expect(coperturaDesk({ data: GIORNO })).toEqual({ dataDa: GIORNO, dataA: GIORNO, medicoId: null, chiave: `prenotazioni-desk:${GIORNO}:${GIORNO}:*` })
    expect(coperturaDesk({ dataDa: '2026-01-01', dataA: '2026-01-31', medicoId: 4 }).chiave).toBe('prenotazioni-desk:2026-01-01:2026-01-31:4')
    expect(coperturaDesk({ dataDa: '2026-02-15', dataA: '2026-03-17' }).dataA).toBe('2026-03-17')
    for (const f of [{}, { dataDa: '2026-01-01' }, { dataA: '2026-01-01' }, { data: GIORNO, dataDa: GIORNO }, { dataDa: '2026-01-01', dataA: '2026-02-01' },
      { dataDa: '2026-01-02', dataA: '2026-01-01' }, { data: '2026-02-30' }, { data: '9999-12-31' }, { data: '0999-01-01' },
      { data: GIORNO, medicoId: 0 }, { data: GIORNO, medicoId: '4' }, { data: GIORNO, medicoId: 2147483648 }]) {
      expect(() => coperturaDesk(f), JSON.stringify(f)).toThrow(TypeError)
    }
  })

  it('percorso: data per un giorno, intervallo altrimenti; medicoId solo se richiesto', () => {
    expect(prenotazioniDeskPath(coperturaDesk({ data: GIORNO }))).toBe(`/api/prenotazioni/desk?data=${GIORNO}`)
    expect(prenotazioniDeskPath(coperturaDesk({ dataDa: '2026-11-01', dataA: '2026-11-30', medicoId: 4 })))
      .toBe('/api/prenotazioni/desk?dataDa=2026-11-01&dataA=2026-11-30&medicoId=4')
  })
})

describe('parser della voce B8A', () => {
  it('voce completa: identità, importi, operatori come ID, orari LocalDateTime invariati', () => {
    const v = parsePrenotazioneDesk(prenotazioneDesk(12, {
      dataOraInizio: '2026-03-29T02:30:00', dataOraFine: '2026-03-29T03:30:00',
      pagamenti: [pagamentoDesk(5), pagatoOnline(6)], rimborsi: [rimborsoAnnidato(8, 6)],
    }))
    expect(v).toMatchObject({
      idPrenotazione: 12, stato: 'CONFERMATA', prezzo: 80, idDisponibilita: 212, gestitaDa: OPERATORE,
      paziente: { id: 82, nome: 'Paola', cognome: 'Prova12' }, titolare: { id: 4 }, effettivo: { id: 4 },
      prestazione: { id: 2, nome: 'Visita sintetica' }, stanza: { id: 3, nome: 'Ambulatorio 1' },
    })
    // Ora inesistente in Europe/Rome (cambio d'ora): resta com'è, senza conversioni di fuso.
    expect(v.inizio).toMatchObject({ date: '2026-03-29', hour: 2, minute: 30, testo: '2026-03-29T02:30:00' })
    expect(v.gestione.testo).toBe('2026-10-20T10:00:00')
    expect(v.pagamenti.map((p) => p.idPagamento)).toEqual([5, 6])
    expect(v.pagamenti[1]).toMatchObject({ modalita: 'ONLINE', stato: 'PAGATO', registratoDa: null })
    expect(v.rimborsi[0]).toMatchObject({ idRimborso: 8, idPagamento: 6, stato: 'RICHIESTO', verificatoDa: null, verificato: null })
    expect(Object.isFrozen(v) && Object.isFrozen(v.pagamenti) && Object.isFrozen(v.pagamenti[0])).toBe(true)
  })

  it('null leciti: ANNULLATA da richiesta, slot non configurato, prezzo zero, liste vuote', () => {
    const v = parsePrenotazioneDesk(annullataDaRichiesta(3, { idPrestazione: null, nomePrestazione: null, idStanza: null, nomeStanza: null, prezzo: 0 }))
    expect(v).toMatchObject({ effettivo: null, gestitaDa: null, gestione: null, prestazione: null, stanza: null, prezzo: 0, pagamenti: [], rimborsi: [] })
    // ANNULLATA da una confermata: conserva effettivo e gestione.
    expect(parsePrenotazioneDesk(prenotazioneDesk(4, { stato: 'ANNULLATA' })).effettivo.id).toBe(4)
    expect(parsePrenotazioneDesk(prenotazioneDesk(5, { pagamenti: [pagatoSede(1)] })).pagamenti[0].registratoDa).toBe(OPERATORE)
  })

  it.each([
    ['stato RICHIESTA', { stato: 'RICHIESTA' }],
    ['stato sconosciuto', { stato: 'confermata' }],
    ['confermata senza effettivo', { idMedicoEffettivo: null, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: null }],
    ['effettuata senza gestione', { stato: 'EFFETTUATA', gestitaDa: null, dataOraGestione: null }],
    ['gestione dimezzata', { gestitaDa: null }],
    ['gestitaDa assente', { gestitaDa: undefined }],
    ['prestazione dimezzata', { nomePrestazione: null }],
    ['stanza dimezzata', { idStanza: null }],
    ['prezzo con tre decimali', { prezzo: 80.005 }],
    ['prezzo negativo', { prezzo: -1 }],
    ['prezzo testuale', { prezzo: '80.00' }],
    ['flag non booleano', { indisponibilitaSegnalata: 0 }],
    ['fine non successiva', { dataOraFine: `${GIORNO}T09:00:00` }],
    ['orario con offset', { dataOraInizio: `${GIORNO}T09:00:00Z` }],
    ['ID non valido', { idPrenotazione: 0 }],
    ['paziente mancante', { idPaziente: null, nomePaziente: null, cognomePaziente: null }],
    ['pagamenti assenti', { pagamenti: undefined }],
    ['rimborsi non array', { rimborsi: {} }],
  ])('rifiuta: %s', (_, extra) => {
    expect(errato(prenotazioneDesk(12, extra))).toThrow(ProtocolError)
  })

  it.each([
    ['PAGATO senza data', [pagamentoDesk(1, { stato: 'PAGATO' })]],
    ['FALLITO con data', [pagamentoDesk(1, { pagatoIl: '2026-10-21T08:00:00' })]],
    ['ONLINE con registrante', [pagamentoDesk(1, { registratoDa: OPERATORE })]],
    ['sede senza registrante', [pagatoSede(1, { registratoDa: null })]],
    ['registrante assente', [pagamentoDesk(1, { registratoDa: undefined })]],
    ['importo nullo', [pagamentoDesk(1, { importo: 0 })]],
    ['modalità sconosciuta', [pagamentoDesk(1, { modalita: 'BONIFICO' })]],
    ['ordine decrescente', [pagamentoDesk(2), pagamentoDesk(1)]],
    ['duplicato', [pagamentoDesk(1), pagamentoDesk(1)]],
  ])('pagamenti: rifiuta %s', (_, pagamenti) => {
    expect(errato(prenotazioneDesk(12, { pagamenti }))).toThrow(ProtocolError)
  })

  it.each([
    ['pagamento non della voce', [pagatoOnline(1)], [rimborsoAnnidato(5, 2)]],
    ['due rimborsi sullo stesso pagamento', [pagatoOnline(1)], [rimborsoAnnidato(5, 1), rimborsoAnnidato(6, 1)]],
    ['ordine decrescente', [pagatoOnline(1), pagatoOnline(2)], [rimborsoAnnidato(6, 1), rimborsoAnnidato(5, 2)]],
    ['verifica dimezzata', [pagatoOnline(1)], [rimborsoAnnidato(5, 1, { verificatoDa: OPERATORE })]],
    ['stato sconosciuto', [pagatoOnline(1)], [rimborsoAnnidato(5, 1, { stato: 'CHIUSO' })]],
  ])('rimborsi: rifiuta %s', (_, pagamenti, rimborsi) => {
    expect(errato(prenotazioneDesk(12, { pagamenti, rimborsi }))).toThrow(ProtocolError)
  })

  it('rimborsi ordinati per ID anche se in ordine diverso dai pagamenti', () => {
    const v = parsePrenotazioneDesk(prenotazioneDesk(12, {
      stato: 'NON_EROGATA', pagamenti: [pagatoOnline(1), pagatoOnline(2)],
      rimborsi: [rimborsoAnnidato(5, 2, { stato: 'RESPINTO', verificatoDa: OPERATORE, verificatoIl: '2026-11-07T10:00:00' }), rimborsoAnnidato(6, 1)],
    }))
    expect(v.rimborsi.map((r) => [r.idRimborso, r.idPagamento])).toEqual([[5, 2], [6, 1]])
  })
})

describe('elenco: contratto e copertura', () => {
  const leggi = (data, filtri = { data: GIORNO }) => fetchPrenotazioniDesk(async () => ok(data), coperturaDesk(filtri))

  it('ordine per inizio e ID, array vuoto ammesso', async () => {
    expect(await leggi([])).toEqual([])
    const voci = await leggi([prenotazioneDesk(3), prenotazioneDesk(4), prenotazioneDesk(1, { dataOraInizio: `${GIORNO}T11:00:00`, dataOraFine: `${GIORNO}T12:00:00` })])
    expect(voci.map((v) => v.idPrenotazione)).toEqual([3, 4, 1])
  })

  it.each([
    ['ID non crescente a parità di inizio', [prenotazioneDesk(4), prenotazioneDesk(3)]],
    ['inizio decrescente', [prenotazioneDesk(1, { dataOraInizio: `${GIORNO}T11:00:00`, dataOraFine: `${GIORNO}T12:00:00` }), prenotazioneDesk(2)]],
    ['duplicato', [prenotazioneDesk(3), prenotazioneDesk(3)]],
    ['fuori dal periodo', [prenotazioneDesk(3, { dataOraInizio: '2026-11-06T09:00:00', dataOraFine: '2026-11-06T10:00:00' })]],
  ])('rifiuta: %s', async (_, data) => {
    await expect(leggi(data)).rejects.toThrow(ProtocolError)
  })

  it('filtro medico: ogni voce ha quel medico effettivo; risposta non 200 o non array rifiutata', async () => {
    await expect(leggi([prenotazioneDesk(3)], { data: GIORNO, medicoId: 4 })).resolves.toHaveLength(1)
    await expect(leggi([prenotazioneDesk(3)], { data: GIORNO, medicoId: 5 })).rejects.toThrow(ProtocolError)
    await expect(leggi([annullataDaRichiesta(3)], { data: GIORNO, medicoId: 4 })).rejects.toThrow(ProtocolError)
    await expect(fetchPrenotazioniDesk(async () => ok([], 201), coperturaDesk({ data: GIORNO }))).rejects.toThrow(ProtocolError)
    await expect(fetchPrenotazioniDesk(async () => ok({}), coperturaDesk({ data: GIORNO }))).rejects.toThrow(ProtocolError)
  })
})

describe('scritture B8: corpi e risposte', () => {
  const cattura = () => { const calls = []; return { calls, fn: async (r) => { calls.push(r); return r } } }

  it('incasso: operatore, modalità in sede e importo atteso; dati non validi rifiutati prima della richiesta', async () => {
    const c = cattura()
    await postPagamentoSede(c.fn, 12, OPERATORE, 'DESK_POS', 80)
    expect(c.calls[0]).toEqual({ method: 'POST', path: '/api/prenotazioni/12/pagamento-sede', json: { idOperatore: OPERATORE, modalita: 'DESK_POS', importoAtteso: 80 } })
    for (const args of [[12, OPERATORE, 'ONLINE', 80], [12, OPERATORE, 'DESK_POS', 0], [12, OPERATORE, 'DESK_POS', 80.001], [0, OPERATORE, 'DESK_POS', 80], [12, 0, 'DESK_POS', 80]]) {
      expect(() => postPagamentoSede(c.fn, ...args)).toThrow(TypeError)
    }
    expect(c.calls).toHaveLength(1)
  })

  it('incasso: 201 conforme; ogni difformità è una risposta non valida', () => {
    expect(parsePagamentoSede(ok(incasso(12), 201), 12, OPERATORE, 'DESK_POS', 80)).toMatchObject({ idPagamento: 900, stato: 'PAGATO' })
    for (const [r, modalita] of [[ok(incasso(12), 200), 'DESK_POS'], [ok(incasso(13), 201), 'DESK_POS'], [ok(incasso(12), 201), 'DESK_CONTANTI'],
      [ok(incasso(12, { importo: 79 }), 201), 'DESK_POS'], [ok(incasso(12, { stato: 'IN_ATTESA', pagatoIl: null }), 201), 'DESK_POS'],
      [ok(incasso(12, { idOperatore: 9 }), 201), 'DESK_POS'], [ok(incasso(12, { pagatoIl: null }), 201), 'DESK_POS'],
      [{ status: 201, hasBody: false, data: null }, 'DESK_POS']]) {
      expect(() => parsePagamentoSede(r, 12, OPERATORE, modalita, 80)).toThrow(ProtocolError)
    }
  })

  it('mancata erogazione: corpo con stato atteso CONFERMATA; risposta con lo stesso esito', async () => {
    const c = cattura()
    await postMancataErogazione(c.fn, 12, OPERATORE, 'PAZIENTE_ASSENTE')
    expect(c.calls[0].json).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA', esito: 'PAZIENTE_ASSENTE' })
    expect(() => postMancataErogazione(c.fn, 12, OPERATORE, 'ANNULLATA')).toThrow(TypeError)
    const risposta = (extra) => ok({ idPrenotazione: 12, stato: 'NON_EROGATA', idOperatore: OPERATORE, dataOraGestione: '2026-11-05T11:00:00', ...extra })
    expect(parseMancataErogazione(risposta(), 12, OPERATORE, 'NON_EROGATA')).toEqual({ idPrenotazione: 12, stato: 'NON_EROGATA' })
    for (const extra of [{ stato: 'PAZIENTE_ASSENTE' }, { idOperatore: 9 }, { idPrenotazione: 13 }, { dataOraGestione: null }]) {
      expect(() => parseMancataErogazione(risposta(extra), 12, OPERATORE, 'NON_EROGATA')).toThrow(ProtocolError)
    }
  })
})
