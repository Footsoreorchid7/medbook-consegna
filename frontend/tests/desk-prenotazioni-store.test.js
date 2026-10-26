import { beforeEach, describe, expect, it } from 'vitest'
import { coperturaDesk, fetchPrenotazioniDesk, parsePrenotazioneDesk, parseRichiesta, parseRimborsoDesk, parseSegnalazione } from '../src/api/desk.js'
import { AMBITO_DESK, deskStore } from '../src/desk/store.js'
import { TESTI_NON_PROPONIBILE, testoVerificaDesk } from '../src/desk/formato.js'
import { createSession } from '../src/session/session.js'
import {
  DECIDI, OP, OPERATORE, RIMBORSI, SOSTITUISCI, admin, decisione, desk, gestione, richiesta, rimborsoDesk, segnalazione,
} from './desk-fixtures.js'
import {
  ELENCO, ESITO, GIORNO, INCASSO, annullataDaRichiesta, incasso, pagamentoDesk, pagatoOnline, pagatoSede, prenotazioneDesk, rimborsoAnnidato,
} from './desk-prenotazioni-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch } from './helpers.js'

const ME = 'GET /api/auth/me'
const PWD = 'PUT /api/auth/password'
const p = (id, extra) => parsePrenotazioneDesk(prenotazioneDesk(id, extra))
const errore = (status, error = 'x') => jsonResponse(status, { error })
const corpo = (k) => JSON.parse(net.callsTo(k).at(-1).body)
const scritture = () => net.calls.filter((c) => c.method !== 'GET' && !c.url.startsWith('/api/auth')).length
const letture = () => net.calls.filter((c) => c.url.startsWith('/api/prenotazioni/desk'))
const giorno = coperturaDesk({ data: GIORNO })
const esitoAnnullamento = (id) => jsonResponse(200, gestione(id, 'ANNULLATA'))
const esitoNegativo = (id, stato) => jsonResponse(200, gestione(id, stato))

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia(user = desk()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: deskStore(session) }
}
/** Lettura desk applicata come farebbe una vista: ticket all'avvio, applicazione all'arrivo. */
async function leggi(session, store, copertura = giorno) {
  const ticket = store.inizioLettura()
  const voci = await fetchPrenotazioniDesk(session.protectedRequest, copertura)
  return { ticket, accettata: store.applicaPrenotazioni(copertura, voci, ticket), voci }
}

describe('incasso in sede', () => {
  it('operatore della sessione, importo atteso dal prezzo letto; esito certo invalida le letture desk', async () => {
    const { session, store } = await avvia()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { prezzo: 80.5, pagamenti: [pagamentoDesk(5, { importo: 80.5 })] })]))
    const { ticket, voci } = await leggi(session, store)
    const richiesteLette = store.inizioLettura()
    store.applica('richieste', [], richiesteLette, true)
    expect(store.valida(giorno.chiave, ticket)).toBe(true)
    net.on(INCASSO(12), jsonResponse(201, incasso(12, { importo: 80.5, modalita: 'DESK_CONTANTI' })))
    expect(await store.incassa(voci[0], 'DESK_CONTANTI')).toMatchObject({ kind: 'eseguita', risultato: { idPagamento: 900, stato: 'PAGATO' } })
    expect(corpo(INCASSO(12))).toEqual({ idOperatore: OPERATORE, modalita: 'DESK_CONTANTI', importoAtteso: 80.5 })
    expect(store.valida(giorno.chiave, ticket)).toBe(false)
    // Elenchi non interessati dall'incasso restano validi.
    expect(store.valida('richieste', richiesteLette)).toBe(true)
  })

  it.each([
    ['non-confermata', { stato: 'EFFETTUATA' }],
    ['segnalata', { indisponibilitaSegnalata: true }],
    ['pagamento-presente', { pagamenti: [pagatoOnline(5)] }],
    ['pagamento-presente', { pagamenti: [pagamentoDesk(5, { stato: 'IN_ATTESA' })] }],
    ['nessun-importo', { prezzo: 0 }],
  ])('presupposto non soddisfatto (%s): nessuna richiesta', async (kind, extra) => {
    const { store } = await avvia()
    expect(await store.incassa(p(12, extra), 'DESK_POS')).toEqual({ kind })
    expect(TESTI_NON_PROPONIBILE[kind]).toBeTruthy()
    expect(scritture()).toBe(0)
  })

  it('modalità non in sede: rifiutata localmente, nessuna richiesta né blocco', async () => {
    const { store } = await avvia()
    expect(await store.incassa(p(12), 'ONLINE')).toEqual({ kind: 'non-valida' })
    expect(scritture()).toBe(0)
    expect(store.bloccata('prenotazione:12')).toBe(false)
  })

  it.each([
    ['200 invece di 201', () => jsonResponse(200, incasso(12))],
    ['importo diverso', () => jsonResponse(201, incasso(12, { importo: 79 }))],
    ['altra modalità', () => jsonResponse(201, incasso(12, { modalita: 'DESK_CONTANTI' }))],
    ['altro operatore', () => jsonResponse(201, incasso(12, { idOperatore: 9 }))],
    ['corpo vuoto', () => new Response(null, { status: 201 })],
    ['errore server', () => errore(503)],
    ['rete', () => 'rete'],
  ])('risposta non conforme (%s): esito incerto, blocco, nessun nuovo invio', async (_, risposta) => {
    const { store } = await avvia()
    net.on(INCASSO(12), risposta())
    expect((await store.incassa(p(12), 'DESK_POS')).kind).toBe('incerto')
    expect(store.incerta('prenotazione:12')).toMatchObject({ tipo: 'incassa', verificaSu: AMBITO_DESK, esito: null })
    expect(await store.incassa(p(12), 'DESK_POS')).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(INCASSO(12))).toHaveLength(1)
  })

  it('409 certo: messaggio del server, nessun blocco, letture superate', async () => {
    const { session, store } = await avvia()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    const { ticket, voci } = await leggi(session, store)
    net.on(INCASSO(12), errore(409, 'Pagamento già registrato o in attesa.'))
    expect(await store.incassa(voci[0], 'DESK_POS')).toMatchObject({ kind: 'conflitto', serverMessage: 'Pagamento già registrato o in attesa.' })
    expect(store.bloccata('prenotazione:12')).toBe(false)
    expect(store.valida(giorno.chiave, ticket)).toBe(false)
  })
})

describe('annullamento della confermata ed esiti negativi', () => {
  it('annullamento: stato atteso CONFERMATA; il vincolo orario lo decide il backend', async () => {
    const { store } = await avvia()
    // Appuntamento passato secondo qualunque orologio: la richiesta parte e il backend risponde.
    const passata = p(12, { dataOraInizio: '2020-01-01T09:00:00', dataOraFine: '2020-01-01T10:00:00', pagamenti: [pagamentoDesk(5)] })
    net.on(OP(12, 'annulla'), errore(409, "L'appuntamento è già iniziato."))
    expect(await store.annullaConfermata(passata)).toMatchObject({ kind: 'conflitto', serverMessage: "L'appuntamento è già iniziato." })
    expect(corpo(OP(12, 'annulla'))).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA' })
    net.on(OP(13, 'annulla'), esitoAnnullamento(13))
    expect((await store.annullaConfermata(p(13))).kind).toBe('eseguita')
  })

  it('annullamento: pagamento PAGATO o IN_ATTESA lo impedisce, nessun rimborso proposto', async () => {
    const { store } = await avvia()
    expect(await store.annullaConfermata(p(12, { pagamenti: [pagatoSede(5)] }))).toEqual({ kind: 'pagamenti-bloccanti' })
    expect(await store.annullaConfermata(p(12, { pagamenti: [pagamentoDesk(5, { stato: 'IN_ATTESA' })] }))).toEqual({ kind: 'pagamenti-bloccanti' })
    expect(await store.annullaConfermata(p(12, { stato: 'ANNULLATA' }))).toEqual({ kind: 'non-confermata' })
    expect(TESTI_NON_PROPONIBILE['pagamenti-bloccanti']).toMatch(/non è previsto un rimborso/)
    expect(scritture()).toBe(0)
  })

  it('PAZIENTE_ASSENTE e NON_EROGATA distinti; flag e pagamenti in attesa secondo il backend', async () => {
    const { store } = await avvia()
    net.on(ESITO(12), esitoNegativo(12, 'PAZIENTE_ASSENTE'))
    expect((await store.registraMancataErogazione(p(12), 'PAZIENTE_ASSENTE')).kind).toBe('eseguita')
    expect(corpo(ESITO(12))).toEqual({ idOperatore: OPERATORE, statoAtteso: 'CONFERMATA', esito: 'PAZIENTE_ASSENTE' })
    // Con segnalazione pendente: solo NON_EROGATA.
    expect(await store.registraMancataErogazione(p(13, { indisponibilitaSegnalata: true }), 'PAZIENTE_ASSENTE')).toEqual({ kind: 'segnalata' })
    net.on(ESITO(13), esitoNegativo(13, 'NON_EROGATA'))
    expect((await store.registraMancataErogazione(p(13, { indisponibilitaSegnalata: true, pagamenti: [pagatoOnline(5)] }), 'NON_EROGATA')).kind).toBe('eseguita')
    expect(await store.registraMancataErogazione(p(14, { pagamenti: [pagamentoDesk(5, { stato: 'IN_ATTESA' })] }), 'NON_EROGATA')).toEqual({ kind: 'pagamento-in-attesa' })
    expect(await store.registraMancataErogazione(p(14), 'ANNULLATA')).toEqual({ kind: 'non-valida' })
    expect(await store.registraMancataErogazione(p(14, { stato: 'NON_EROGATA' }), 'NON_EROGATA')).toEqual({ kind: 'non-confermata' })
    expect(scritture()).toBe(2)
  })

  it('risposta con un esito diverso da quello richiesto: incerta', async () => {
    const { store } = await avvia()
    net.on(ESITO(12), esitoNegativo(12, 'NON_EROGATA'))
    expect((await store.registraMancataErogazione(p(12), 'PAZIENTE_ASSENTE')).kind).toBe('incerto')
    expect(store.incerta('prenotazione:12').riepilogo).toMatchObject({ esito: 'PAZIENTE_ASSENTE', giorno: GIORNO })
  })

  it('esiti certi: invalidano elenco desk e segnalazioni, non richieste e rimborsi', async () => {
    const { store } = await avvia()
    const t = Object.fromEntries(['richieste', 'segnalazioni', 'rimborsi', giorno.chiave].map((k) => [k, store.inizioLettura()]))
    for (const [k, ticket] of Object.entries(t)) store.applica(k, [], ticket, true)
    net.on(ESITO(12), esitoNegativo(12, 'NON_EROGATA'))
    await store.registraMancataErogazione(p(12), 'NON_EROGATA')
    expect(Object.fromEntries(Object.entries(t).map(([k, ticket]) => [k, store.valida(k, ticket)])))
      .toEqual({ richieste: true, segnalazioni: false, rimborsi: true, [giorno.chiave]: false })
  })
})

describe('blocchi condivisi con richieste e segnalazioni', () => {
  it('incasso incerto: la stessa prenotazione è bloccata per ogni operazione su richieste e prenotazioni gestite; le altre restano libere', async () => {
    const { store } = await avvia()
    net.on(INCASSO(12), 'rete')
    await store.incassa(p(12), 'DESK_POS')
    expect(await store.annullaSegnalata(parseSegnalazione(segnalazione(12)))).toEqual({ kind: 'bloccata' })
    expect(await store.sostituisci(parseSegnalazione(segnalazione(12)), 9)).toEqual({ kind: 'bloccata' })
    expect(await store.decidiRichiesta('annulla', parseRichiesta(richiesta(12)))).toEqual({ kind: 'bloccata' })
    expect(await store.annullaConfermata(p(12))).toEqual({ kind: 'bloccata' })
    expect(await store.registraMancataErogazione(p(12), 'NON_EROGATA')).toEqual({ kind: 'bloccata' })
    net.on(INCASSO(13), jsonResponse(201, incasso(13)))
    expect((await store.incassa(p(13), 'DESK_POS')).kind).toBe('eseguita')
    net.on(DECIDI(5, 'approva'), jsonResponse(200, decisione(5, 'APPROVATO')))
    expect((await store.decidiRimborso('approva', parseRimborsoDesk(rimborsoDesk(5, { idPrenotazione: 12 })))).kind).toBe('eseguita')
    expect(net.callsTo(INCASSO(12))).toHaveLength(1)
  })

  it('operazioni in volo: richieste e prenotazioni gestite si bloccano a vicenda prima della fetch', async () => {
    const { store } = await avvia()
    const h = net.trattieni(SOSTITUISCI(12))
    const sostituzione = store.sostituisci(parseSegnalazione(segnalazione(12)), 9)
    expect(await store.annullaConfermata(p(12))).toEqual({ kind: 'occupato' })
    expect(await store.incassa(p(12), 'DESK_POS')).toEqual({ kind: 'occupato' })
    h.resolve(jsonResponse(200, { idPrenotazione: 12, stato: 'CONFERMATA', idMedicoEffettivo: 9, indisponibilitaSegnalata: false, idOperatore: OPERATORE, dataOraGestione: '2026-10-20T11:00:00' }))
    expect((await sostituzione).kind).toBe('eseguita')
    const k = net.trattieni(ESITO(14))
    const esito = store.registraMancataErogazione(p(14), 'NON_EROGATA')
    expect(await store.decidiRichiesta('rifiuta', parseRichiesta(richiesta(14)))).toEqual({ kind: 'occupato' })
    k.resolve(esitoNegativo(14, 'NON_EROGATA'))
    expect((await esito).kind).toBe('eseguita')
    expect(scritture()).toBe(2)
  })

  it('esito certo: il riscontro precedente della stessa prenotazione non è più usabile, quello delle altre sì', async () => {
    const { session, store } = await avvia()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12), prenotazioneDesk(13)]))
    const { ticket, voci } = await leggi(session, store)
    net.on(INCASSO(12), jsonResponse(201, incasso(12)))
    expect((await store.incassa(voci[0], 'DESK_POS')).kind).toBe('eseguita')
    expect(store.riscontroPrenotazione(12, ticket)).toBeNull()
    expect(store.riscontroPrenotazione(13, ticket)?.voce.idPrenotazione).toBe(13)
    // Una lettura avviata prima dell'esito non ripristina il riscontro della prenotazione 12.
    const vecchia = { ...ticket }
    expect(store.applicaPrenotazioni(giorno, voci, Object.freeze(vecchia))).toBe(false)
    expect(store.riscontroPrenotazione(12, ticket)).toBeNull()
  })

  it('esiti certi sulle richieste e decisioni di rimborso invalidano anche le letture desk', async () => {
    const { store } = await avvia()
    for (const [op, risposta, esegui] of [
      [OP(12, 'conferma'), jsonResponse(200, gestione(12, 'CONFERMATA', { idMedicoEffettivo: 4 })), (s) => s.decidiRichiesta('conferma', parseRichiesta(richiesta(12)))],
      [DECIDI(5, 'respingi'), jsonResponse(200, decisione(5, 'RESPINTO')), (s) => s.decidiRimborso('respingi', parseRimborsoDesk(rimborsoDesk(5)))],
    ]) {
      const ticket = store.inizioLettura()
      expect(store.applicaPrenotazioni(giorno, [], ticket)).toBe(true)
      net.on(op, risposta)
      expect((await esegui(store)).kind).toBe('eseguita')
      expect(store.valida(giorno.chiave, ticket)).toBe(false)
    }
  })
})

describe('verifica degli esiti incerti', () => {
  async function incertoIncasso(store, voce = p(12, { pagamenti: [pagamentoDesk(5)] })) {
    net.on(INCASSO(12), 'rete')
    await store.incassa(voce, 'DESK_POS')
  }

  it('incasso: giorno noto senza filtro medico; nuovo PAGATO descritto senza attribuzione', async () => {
    const { store } = await avvia()
    await incertoIncasso(store)
    // Dopo una sostituzione il medico effettivo è cambiato: la lettura non filtra per medico.
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { idMedicoEffettivo: 9, nomeMedicoEffettivo: 'Ugo', cognomeMedicoEffettivo: 'Sostituto', pagamenti: [pagamentoDesk(5), pagatoSede(6)] })]))
    const r = await store.verifica('prenotazione:12')
    expect(letture().map((c) => c.url)).toEqual([`/api/prenotazioni/desk?data=${GIORNO}`])
    expect(r).toMatchObject({ kind: 'verificata', esito: 'nuovo-pagato', fonte: 'verifica' })
    expect(r.nuovi.map((x) => x.idPagamento)).toEqual([6])
    expect(store.incerta('prenotazione:12')).toBeNull()
    const testo = testoVerificaDesk(r)
    expect(testo).toMatch(/n\. 6 \(In sede, contanti/)
    expect(testo).toMatch(/non stabilisce se derivi da questo tentativo/)
  })

  it.each([
    ['nessun-nuovo', [pagamentoDesk(5)], /non risulta registrato/],
    ['nuovo-non-pagato', [pagamentoDesk(5), pagamentoDesk(7)], /non riusciti o in attesa/],
  ])('incasso: %s', async (esito, pagamenti, messaggio) => {
    const { store } = await avvia()
    await incertoIncasso(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { pagamenti })]))
    const r = await store.verifica('prenotazione:12')
    expect(r.esito).toBe(esito)
    expect(testoVerificaDesk(r)).toMatch(messaggio)
  })

  it('assenza nel giorno coperto: non conclusiva, voce conservata e rimovibile', async () => {
    const { store } = await avvia()
    await incertoIncasso(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(13)]))
    const r = await store.verifica('prenotazione:12')
    expect(r.esito).toBe('assente')
    expect(store.incerta('prenotazione:12')).toMatchObject({ verifica: 'verificata', esito: 'assente' })
    expect(testoVerificaDesk(r)).toMatch(/l'assenza non indica che l'operazione non sia avvenuta/)
    store.chiudiIncerta('prenotazione:12', store.state.owner)
    expect(store.incerta('prenotazione:12')).toBeNull()
  })

  it.each([
    ['annulla-confermata', 'ANNULLATA', 'annullata'],
    ['annulla-confermata', 'CONFERMATA', 'confermata'],
    ['annulla-confermata', 'EFFETTUATA', 'altro-stato'],
    ['mancata-erogazione', 'NON_EROGATA', 'come-richiesto'],
    ['mancata-erogazione', 'CONFERMATA', 'confermata'],
    ['mancata-erogazione', 'PAZIENTE_ASSENTE', 'altro-stato'],
  ])('%s con stato corrente %s → %s', async (tipo, corrente, esito) => {
    const { store } = await avvia()
    if (tipo === 'annulla-confermata') { net.on(OP(12, 'annulla'), errore(502)); await store.annullaConfermata(p(12)) }
    else { net.on(ESITO(12), errore(500)); await store.registraMancataErogazione(p(12), 'NON_EROGATA') }
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: corrente })]))
    const r = await store.verifica('prenotazione:12')
    expect(r.esito).toBe(esito)
    expect(testoVerificaDesk(r)).not.toBe('Verifica completata.')
    expect(testoVerificaDesk(r)).not.toMatch(/eseguit[oa] da te|dal tuo tentativo/)
  })

  it('errore di lettura: incertezza conservata con verifica in errore, nessun nuovo invio', async () => {
    const { store } = await avvia()
    await incertoIncasso(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { dataOraInizio: '2026-11-06T09:00:00', dataOraFine: '2026-11-06T10:00:00' })]))
    expect(await store.verifica('prenotazione:12')).toEqual({ kind: 'errore' })
    expect(store.incerta('prenotazione:12')).toMatchObject({ verifica: 'errore', esito: null })
    expect(store.bloccata('prenotazione:12')).toBe(true)
    expect(net.callsTo(INCASSO(12))).toHaveLength(1)
  })
})

describe('letture sovrapposte e riscontro corrente', () => {
  async function incerto(store) {
    net.on(OP(12, 'annulla'), 'rete')
    await store.annullaConfermata(p(12))
  }

  it('verifica avviata prima di una lettura più recente arrivata prima: vale la recente', async () => {
    const { session, store } = await avvia()
    await incerto(store)
    const h = net.trattieni(ELENCO())
    const verifica = store.verifica('prenotazione:12')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await leggi(session, store)
    h.resolve(jsonResponse(200, [prenotazioneDesk(12)]))
    expect(await verifica).toMatchObject({ esito: 'annullata', fonte: 'successiva' })
  })

  it('lettura più recente arrivata dopo l’applicazione della verifica: l’esito usa il riscontro corrente', async () => {
    const { session, store } = await avvia()
    await incerto(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    const lettura = await store.leggiVerificaDesk('prenotazione:12')
    expect(lettura.kind).toBe('letta')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await leggi(session, store)
    expect(store.esitoVerificaDesk('prenotazione:12', lettura)).toMatchObject({ esito: 'annullata', fonte: 'successiva' })
  })

  it('lettura più vecchia arrivata dopo la verifica: ignorata, non contraddice il riscontro', async () => {
    const { store } = await avvia()
    await incerto(store)
    const vecchia = store.inizioLettura()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    const lettura = await store.leggiVerificaDesk('prenotazione:12')
    const voci = await fetchPrenotazioniDesk(async () => ({ status: 200, hasBody: true, data: [prenotazioneDesk(12)] }), giorno)
    expect(store.applicaPrenotazioni(giorno, voci, vecchia)).toBe(false)
    expect(store.esitoVerificaDesk('prenotazione:12', lettura)).toMatchObject({ esito: 'annullata', fonte: 'verifica' })
  })

  it('scrittura certa sulla prenotazione fra lettura ed esito: verifica superata, blocco mantenuto', async () => {
    const { store } = await avvia()
    net.on(INCASSO(12), 'rete')
    await store.incassa(p(12), 'DESK_POS')
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12, { stato: 'NON_EROGATA', pagamenti: [pagatoOnline(5)], rimborsi: [rimborsoAnnidato(7, 5)] })]))
    const lettura = await store.leggiVerificaDesk('prenotazione:12')
    // Decisione certa sul rimborso della stessa prenotazione: il riscontro letto non è più corrente.
    net.on(DECIDI(7, 'respingi'), jsonResponse(200, decisione(7, 'RESPINTO')))
    expect((await store.decidiRimborso('respingi', parseRimborsoDesk(rimborsoDesk(7, { idPrenotazione: 12 })))).kind).toBe('eseguita')
    expect(store.esitoVerificaDesk('prenotazione:12', lettura)).toEqual({ kind: 'superata' })
    expect(store.incerta('prenotazione:12')).toMatchObject({ verifica: 'superata', esito: null })
    expect(store.bloccata('prenotazione:12')).toBe(true)
  })

  it('copertura: un filtro medico o un altro giorno non registrano assenze; la presenza vale sempre', async () => {
    const { session, store } = await avvia()
    await incerto(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    const prima = await store.leggiVerificaDesk('prenotazione:12')
    net.on(ELENCO(`data=${GIORNO}&medicoId=5`), jsonResponse(200, []))
    await leggi(session, store, coperturaDesk({ data: GIORNO, medicoId: 5 }))
    net.on(ELENCO('data=2026-11-06'), jsonResponse(200, []))
    await leggi(session, store, coperturaDesk({ data: '2026-11-06' }))
    expect(store.esitoVerificaDesk('prenotazione:12', prima)).toMatchObject({ esito: 'confermata', fonte: 'verifica' })
    // Una lettura filtrata che contiene la prenotazione è un riscontro valido.
    net.on(OP(12, 'annulla'), 'rete')
    await store.annullaConfermata(p(12))
    const seconda = await (async () => { net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)])); return store.leggiVerificaDesk('prenotazione:12') })()
    net.on(ELENCO(`data=${GIORNO}&medicoId=4`), jsonResponse(200, [prenotazioneDesk(12, { stato: 'ANNULLATA' })]))
    await leggi(session, store, coperturaDesk({ data: GIORNO, medicoId: 4 }))
    expect(store.esitoVerificaDesk('prenotazione:12', seconda)).toMatchObject({ esito: 'annullata', fonte: 'successiva' })
  })

  it('periodo che include il giorno: l’assenza è registrata anche da una lettura di più giorni', async () => {
    const { session, store } = await avvia()
    await incerto(store)
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(12)]))
    const lettura = await store.leggiVerificaDesk('prenotazione:12')
    net.on(ELENCO('dataDa=2026-11-01&dataA=2026-11-30'), jsonResponse(200, [annullataDaRichiesta(30)]))
    await leggi(session, store, coperturaDesk({ dataDa: '2026-11-01', dataA: '2026-11-30' }))
    expect(store.esitoVerificaDesk('prenotazione:12', lettura)).toMatchObject({ esito: 'assente', fonte: 'successiva' })
  })
})

describe('recupero delle decisioni di rimborso del desk', () => {
  const rimborso = (extra) => parseRimborsoDesk(rimborsoDesk(5, { idPrenotazione: 25, dataOraInizio: `${GIORNO}T09:00:00`, dataOraFine: `${GIORNO}T10:00:00`, ...extra }))
  const voce = (stato, extra = {}) => prenotazioneDesk(25, {
    stato: 'NON_EROGATA', pagamenti: [pagatoOnline(45)], rimborsi: [rimborsoAnnidato(5, 45, stato === 'RICHIESTO' ? {} : { stato, verificatoDa: 8, verificatoIl: '2026-11-07T10:00:00' })], ...extra,
  })

  it.each([
    ['APPROVATO', 'come-richiesto'], ['RESPINTO', 'diverso'], ['RICHIESTO', 'presente'],
  ])('rimborso %s nella prenotazione → %s, senza attribuzione', async (stato, esito) => {
    const { store } = await avvia()
    net.on(DECIDI(5, 'approva'), errore(504))
    await store.decidiRimborso('approva', rimborso())
    net.on(ELENCO(), jsonResponse(200, [voce(stato)]))
    const r = await store.verificaRimborsoDesk('rimborso:5')
    expect(r).toMatchObject({ kind: 'verificata', esito })
    expect(store.incerta('rimborso:5')).toBeNull()
    if (esito !== 'presente') expect(testoVerificaDesk(r)).toMatch(/non stabilisce se derivi da questo tentativo/)
  })

  it('assenza dagli elenchi: non conclusiva anche dopo il recupero; prenotazione senza il rimborso: non conclusiva', async () => {
    const { store } = await avvia()
    net.on(DECIDI(5, 'approva'), 'rete')
    await store.decidiRimborso('approva', rimborso())
    net.on(RIMBORSI, jsonResponse(200, []))
    expect((await store.verifica('rimborso:5')).esito).toBe('assente')
    net.on(ELENCO(), jsonResponse(200, [voce('RICHIESTO', { rimborsi: [] })]))
    expect(await store.verificaRimborsoDesk('rimborso:5')).toMatchObject({ esito: 'assente' })
    net.on(ELENCO(), jsonResponse(200, []))
    expect(await store.verificaRimborsoDesk('rimborso:5')).toMatchObject({ esito: 'assente' })
    expect(store.incerta('rimborso:5')).toMatchObject({ esito: 'assente' })
  })

  it('giorno non noto o verifica ordinaria dei rimborsi: nessuna lettura desk', async () => {
    const { store } = await avvia()
    net.on(DECIDI(5, 'approva'), 'rete')
    await store.decidiRimborso('approva', rimborso({ dataOraInizio: null, dataOraFine: null }))
    expect(await store.verificaRimborsoDesk('rimborso:5')).toEqual({ kind: 'non-applicabile' })
    net.on(RIMBORSI, jsonResponse(200, [rimborsoDesk(5)]))
    expect((await store.verifica('rimborso:5')).esito).toBe('presente')
    expect(letture()).toHaveLength(0)
  })
})

describe('identità, epoca e permessi', () => {
  it('amministratore e password incerta: nessuna scrittura', async () => {
    const { store } = await avvia(admin())
    expect(await store.incassa(p(12), 'DESK_POS')).toEqual({ kind: 'sessione-assente' })
    expect(await store.annullaConfermata(p(12))).toEqual({ kind: 'sessione-assente' })
    expect(await store.registraMancataErogazione(p(12), 'NON_EROGATA')).toEqual({ kind: 'sessione-assente' })
    const altro = await avvia()
    net.on(PWD, 'rete')
    await altro.session.changePassword('a', 'b')
    expect(await altro.store.incassa(p(12), 'DESK_POS')).toEqual({ kind: 'password-incerta' })
    expect(scritture()).toBe(0)
  })

  it('rotazione: incertezza conservata, lettura e verifica avviate prima diventano obsolete', async () => {
    const { session, store } = await avvia()
    net.on(INCASSO(12), 'rete')
    await store.incassa(p(12), 'DESK_POS')
    const ticket = store.inizioLettura()
    const h = net.trattieni(ELENCO())
    const verifica = store.verifica('prenotazione:12')
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    expect(h.aborted).toBe(true)
    expect((await verifica).kind).toBe('obsoleto')
    expect(store.incerta('prenotazione:12')).toMatchObject({ verifica: null, esito: null })
    expect(store.applicaPrenotazioni(giorno, [p(12)], ticket)).toBe(false)
    expect(store.riscontroPrenotazione(12, ticket)).toBeNull()
  })

  it('cambio d’identità: store svuotato, riscontri azzerati, esiti tardivi scartati', async () => {
    const { session, store } = await avvia()
    net.on(ELENCO(), jsonResponse(200, [prenotazioneDesk(13)]))
    const { ticket } = await leggi(session, store)
    expect(store.riscontroPrenotazione(13, ticket)).not.toBeNull()
    const h = net.trattieni(INCASSO(12))
    const tardivo = store.incassa(p(12), 'DESK_POS')
    net.on('POST /api/auth/logout', new Response(null, { status: 204 }))
    await session.logout()
    net.on('POST /api/auth/login', meOk(desk({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(201, incasso(12)))
    expect((await tardivo).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, incerte: [], inCorso: [], barriere: {} })
    expect(store.riscontroPrenotazione(13, ticket)).toBeNull()
    const nuovo = store.inizioLettura()
    expect(store.applicaPrenotazioni(giorno, [p(13)], nuovo)).toBe(true)
    expect(store.riscontroPrenotazione(13, nuovo).voce.idPrenotazione).toBe(13)
  })
})
