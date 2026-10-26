import { beforeEach, describe, expect, it } from 'vitest'
import { parseAppuntamentoAgenda } from '../src/api/medico.js'
import { medicoStore } from '../src/medico/store.js'
import { prenotazioniStore } from '../src/prenotazioni/store.js'
import { createSession } from '../src/session/session.js'
import {
  MEDICO, REFERTO, SEGNALA, VISITA, agendaGiorno, agendaPeriodo, caricato, effettuata, medico, pdf, registrata, richiesta, voce,
} from './b5-fixtures.js'
import { CSRF_B, installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'

const ME = 'GET /api/auth/me'
const LOGIN = 'POST /api/auth/login'
const LOGOUT = 'POST /api/auth/logout'
const PWD = 'PUT /api/auth/password'
const a = (d) => parseAppuntamentoAgenda(d, MEDICO)
const errore = (status) => jsonResponse(status, { error: 'x' })

let net
beforeEach(() => { net = installFetch(routedFetch()) })

async function avvia(user = medico()) {
  net.on(ME, meOk(user))
  const session = createSession()
  await session.ensureBootstrap()
  return { session, store: medicoStore(session) }
}

/** Nessuna API del paziente o di altri profili. */
const soloApiMedico = () => net.calls.every((c) => c.url.startsWith('/api/auth/') || c.url.startsWith('/api/prenotazioni/agenda?')
  || /^\/api\/prenotazioni\/\d+\/(registra-visita|referto)$/.test(c.url) && c.method === 'POST'
  || c.url === '/api/prenotazioni/segnala-indisponibilita')

describe('registrazione della visita', () => {
  it('successo: 200 EFFETTUATA; poi bloccata prima di fetch anche con dati precedenti', async () => {
    const { store } = await avvia()
    net.on(VISITA(12), jsonResponse(200, registrata(12)))
    expect((await store.registraVisita(a(voce(12)))).kind).toBe('registrata')
    expect(JSON.parse(net.callsTo(VISITA(12))[0].body)).toEqual({})
    expect(store.visitaRegistrata(12)).toBe(true)
    expect(await store.registraVisita(a(voce(12)))).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(VISITA(12))).toHaveLength(1)
  })

  it('condizioni verificabili prima di fetch: CONFERMATA, effettivo corrente, nessuna segnalazione', async () => {
    const { store } = await avvia()
    for (const d of [richiesta(12), effettuata(12), voce(12, { indisponibilitaSegnalata: true }), voce(12, { idMedicoEffettivo: 5 })]) {
      const app = d.idMedicoEffettivo === 5 ? { ...a(voce(12)), idMedicoEffettivo: 5 } : a(d)
      expect(await store.registraVisita(app)).toEqual({ kind: 'non-registrabile' })
    }
    expect(net.callsTo(VISITA(12))).toHaveLength(0)
  })

  it('409 e 404 certi senza blocco; rete, 5xx e successo malformato incerti con blocco', async () => {
    const { store } = await avvia()
    net.on(VISITA(12), errore(409))
    expect((await store.registraVisita(a(voce(12)))).kind).toBe('conflitto')
    net.on(VISITA(13), errore(404))
    expect((await store.registraVisita(a(voce(13)))).kind).toBe('non-trovata')
    expect(store.visitaBloccata(12) || store.visitaBloccata(13)).toBe(false)
    net.on(VISITA(14), 'rete').on(VISITA(15), errore(500)).on(VISITA(16), jsonResponse(200, { idPrenotazione: 16, stato: 'CONFERMATA' }))
    for (const id of [14, 15, 16]) {
      expect((await store.registraVisita(a(voce(id)))).kind).toBe('incerto')
      expect(store.visitaBloccata(id)).toBe(true)
      expect(await store.registraVisita(a(voce(id)))).toEqual({ kind: 'bloccata' })
      expect(net.callsTo(VISITA(id))).toHaveLength(1)
    }
  })

  it('verifica manuale nell\'agenda della giornata: EFFETTUATA, CONFERMATA, assente, errore', async () => {
    const { store } = await avvia()
    net.on(VISITA(12), 'rete')
    await store.registraVisita(a(voce(12)))
    net.on(agendaGiorno('2026-10-19'), 'rete')
    expect((await store.verificaVisita(12)).kind).toBe('errore')
    expect(store.visitaIncerta(12)).toMatchObject({ verifica: 'errore' })
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [voce(7)]))
    expect((await store.verificaVisita(12)).kind).toBe('assente')
    // Non più in agenda: l'esito non viene dedotto e il blocco resta.
    expect(store.visitaBloccata(12)).toBe(true)
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [voce(12)]))
    const esito = await store.verificaVisita(12)
    expect(esito.kind).toBe('trovata')
    expect(esito.appuntamento.stato).toBe('CONFERMATA')
    // Ancora CONFERMATA: nessuna registrazione risulta; nuovo invio solo esplicito.
    expect(store.visitaBloccata(12)).toBe(false)

    net.on(VISITA(13), 'rete')
    await store.registraVisita(a(voce(13)))
    net.on(agendaGiorno('2026-10-19'), jsonResponse(200, [effettuata(13)]))
    expect((await store.verificaVisita(13)).appuntamento.stato).toBe('EFFETTUATA')
    expect(store.visitaIncerta(13)).toBeNull()
    expect(store.visitaRegistrata(13)).toBe(true)
    expect(soloApiMedico()).toBe(true)
  })

  it('password incerta: rifiuto prima di fetch', async () => {
    const { session, store } = await avvia()
    net.on(PWD, 'rete')
    await session.changePassword('a', 'b')
    expect(await store.registraVisita(a(voce(12)))).toEqual({ kind: 'password-incerta' })
    expect(net.callsTo(VISITA(12))).toHaveLength(0)
  })

  it('doppio invio: una sola registrazione in volo per prenotazione', async () => {
    const { store } = await avvia()
    const h = net.trattieni(VISITA(12))
    const prima = store.registraVisita(a(voce(12)))
    expect(await store.registraVisita(a(voce(12)))).toEqual({ kind: 'occupato' })
    h.resolve(jsonResponse(200, registrata(12)))
    expect((await prima).kind).toBe('registrata')
    expect(net.callsTo(VISITA(12))).toHaveLength(1)
  })
})

describe('segnalazione di indisponibilità', () => {
  it('criteri del backend: RICHIESTA del titolare, CONFERMATA dell\'effettivo, niente già segnalate; nessun duplicato', async () => {
    const { store } = await avvia()
    expect(await store.segnala([a(voce(12)), a(voce(12))])).toEqual({ kind: 'non-valida' })
    expect(await store.segnala([])).toEqual({ kind: 'non-valida' })
    expect(await store.segnala([a(effettuata(12))])).toEqual({ kind: 'non-segnalabile' })
    expect(await store.segnala([a(voce(12, { indisponibilitaSegnalata: true }))])).toEqual({ kind: 'non-segnalabile' })
    expect(net.callsTo(SEGNALA)).toHaveLength(0)
    net.on(SEGNALA, jsonResponse(200, { idPrenotazioni: [12, 13], indisponibilitaSegnalata: true }))
    expect((await store.segnala([a(richiesta(12)), a(voce(13))])).kind).toBe('segnalate')
    expect(JSON.parse(net.callsTo(SEGNALA)[0].body)).toEqual({ medicoId: MEDICO, idPrenotazioni: [12, 13] })
    // Riscontro certo: nessun reinvio con i dati precedenti alla segnalazione.
    expect(store.segnalazioneNota(12)).toBe(true)
    expect(await store.segnala([a(voce(13))])).toEqual({ kind: 'non-segnalabile' })
    expect(await store.registraVisita(a(voce(13)))).toEqual({ kind: 'non-registrabile' })
  })

  it('medicoId solo dalla sessione', async () => {
    const { store } = await avvia(medico({ idUtente: 9 }))
    net.on(SEGNALA, jsonResponse(200, { idPrenotazioni: [12], indisponibilitaSegnalata: true }))
    await store.segnala([{ ...a(voce(12)), idMedicoEffettivo: 9, idMedicoTitolare: 9 }])
    expect(JSON.parse(net.callsTo(SEGNALA)[0].body).medicoId).toBe(9)
  })

  it('la segnalazione nota cade solo con una lettura avviata dopo che riporta la prenotazione', async () => {
    const { store } = await avvia()
    const vecchia = store.inizioLettura()
    net.on(SEGNALA, jsonResponse(200, { idPrenotazioni: [12], indisponibilitaSegnalata: true }))
    await store.segnala([a(voce(12))])
    store.applicaAgenda([a(voce(12))], vecchia)
    expect(store.segnalazioneNota(12)).toBe(true)
    store.applicaAgenda([a(voce(7))], store.inizioLettura())
    expect(store.segnalazioneNota(12)).toBe(true)
    store.applicaAgenda([a(voce(12))], store.inizioLettura())
    expect(store.segnalazioneNota(12)).toBe(false)
  })

  it('conflitto atomico: nessun blocco; esito non conforme o rete: tutto il gruppo bloccato', async () => {
    const { store } = await avvia()
    net.on(SEGNALA, errore(409))
    expect((await store.segnala([a(voce(12)), a(voce(13))])).kind).toBe('conflitto')
    expect(store.segnalazioneBloccata(12)).toBe(false)
    net.on(SEGNALA, jsonResponse(200, { idPrenotazioni: [12], indisponibilitaSegnalata: true }))
    expect((await store.segnala([a(voce(12)), a(voce(13))])).kind).toBe('incerto')
    expect(store.segnalazioneBloccata(12) && store.segnalazioneBloccata(13)).toBe(true)
    expect(await store.segnala([a(voce(13))])).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(SEGNALA)).toHaveLength(2)
  })

  it('verifica: stato per appuntamento, gli assenti restano bloccati e non vengono dedotti', async () => {
    const { store } = await avvia()
    const app = (id, data) => a(voce(id, { dataOraInizio: `${data}T09:00:00`, dataOraFine: `${data}T10:00:00` }))
    net.on(SEGNALA, 'rete')
    await store.segnala([app(12, '2026-10-19'), app(13, '2026-10-20'), app(14, '2026-10-21'), app(15, '2026-10-21')])
    const chiave = store.state.segnalazioniIncerte[0].chiave
    net.on(agendaPeriodo('2026-10-19', '2026-10-21'), jsonResponse(200, [
      voce(12, { indisponibilitaSegnalata: true }), voce(13), effettuata(15),
    ]))
    const esito = await store.verificaSegnalazione(chiave)
    expect(esito.esiti).toMatchObject({ 12: { kind: 'segnalata' }, 13: { kind: 'non-segnalata' }, 14: { kind: 'assente' }, 15: { kind: 'altro-stato' } })
    expect(esito.pendenti).toEqual([14])
    expect(store.segnalazioneBloccata(14)).toBe(true)
    expect(store.segnalazioneBloccata(13)).toBe(false)
    // Nuova verifica solo dei pendenti; quando nessuno resta, la voce si può chiudere.
    net.on(agendaGiorno('2026-10-21'), jsonResponse(200, [voce(14, { dataOraInizio: '2026-10-21T09:00:00', dataOraFine: '2026-10-21T10:00:00' })]))
    expect((await store.verificaSegnalazione(chiave)).pendenti).toEqual([])
    store.chiudiSegnalazioneIncerta(chiave, store.state.owner)
    expect(store.state.segnalazioniIncerte).toEqual([])
  })

  it('verifica su periodi oltre 31 giorni: più letture, ciascuna entro il limite', async () => {
    const { store } = await avvia()
    const app = (id, data) => a(voce(id, { dataOraInizio: `${data}T09:00:00`, dataOraFine: `${data}T10:00:00` }))
    net.on(SEGNALA, 'rete')
    await store.segnala([app(12, '2026-10-01'), app(13, '2026-11-05')])
    net.on(agendaGiorno('2026-10-01'), jsonResponse(200, [])).on(agendaGiorno('2026-11-05'), jsonResponse(200, []))
    expect((await store.verificaSegnalazione(store.state.segnalazioniIncerte[0].chiave)).pendenti).toEqual([12, 13])
  })
})

describe('caricamento del referto', () => {
  it('successo: 201 validato; secondo caricamento bloccato prima di fetch', async () => {
    const { store } = await avvia()
    net.on(REFERTO(12), jsonResponse(201, caricato(12)))
    const esito = await store.caricaReferto(a(effettuata(12)), pdf())
    expect(esito.kind).toBe('caricato')
    expect(store.refertoCaricato(12)).toMatchObject({ idReferto: 9 })
    expect(await store.caricaReferto(a(effettuata(12)), pdf())).toEqual({ kind: 'bloccata' })
    expect(net.callsTo(REFERTO(12))).toHaveLength(1)
  })

  it('solo EFFETTUATA del medico effettivo; file non utilizzabile rifiutato prima di fetch', async () => {
    const { store } = await avvia()
    expect(await store.caricaReferto(a(voce(12)), pdf())).toEqual({ kind: 'non-caricabile' })
    expect(await store.caricaReferto(a(effettuata(12)), new File([], 'vuoto.pdf'))).toEqual({ kind: 'file-non-valido' })
    expect(net.callsTo(REFERTO(12))).toHaveLength(0)
  })

  it('400, 403, 404, 409, 413 e 415 certi senza blocco né riprova', async () => {
    const { store } = await avvia()
    const attesi = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto', 413: 'troppo-grande', 415: 'tipo-non-supportato' }
    for (const [status, kind] of Object.entries(attesi)) {
      net.on(REFERTO(12), errore(Number(status)))
      expect((await store.caricaReferto(a(effettuata(12)), pdf())).kind).toBe(kind)
      expect(store.refertoBloccato(12)).toBe(false)
    }
    expect(net.callsTo(REFERTO(12))).toHaveLength(6)
  })

  it('esito incerto: blocco senza verifica possibile, nessuna API del paziente, nessun file nello store', async () => {
    const { store } = await avvia(medico({ paziente: true }))
    for (const [id, risposta] of [[12, 'rete'], [13, errore(500)], [14, jsonResponse(201, caricato(99))]]) {
      net.on(REFERTO(id), risposta)
      expect((await store.caricaReferto(a(effettuata(id)), pdf())).kind).toBe('incerto')
      expect(store.refertoIncerto(id)).toMatchObject({ idPrenotazione: id })
      expect(await store.caricaReferto(a(effettuata(id)), pdf())).toEqual({ kind: 'bloccata' })
    }
    // Anche con il profilo paziente nella stessa sessione: nessuna GET metadati/documento del referto.
    expect(net.calls.filter((c) => c.method === 'GET' && c.url.includes('/referto'))).toHaveLength(0)
    expect(soloApiMedico()).toBe(true)
    const stato = JSON.stringify(store.state)
    expect(stato).not.toContain('%PDF')
    expect(stato).not.toContain('referto-sintetico.pdf')
    expect(Object.values(store.state).flat().some((x) => x instanceof Blob)).toBe(false)
  })
})

describe('identità, rotazione e isolamento', () => {
  it('rotazione dello stesso utente: incertezze conservate', async () => {
    const { session, store } = await avvia()
    net.on(VISITA(12), 'rete')
    await store.registraVisita(a(voce(12)))
    const epoca = session.state.epoch
    net.on(PWD, jsonResponse(200, { message: 'ok', nuovoAccessoRichiesto: false }, { 'X-CSRF-Token': CSRF_B }))
    expect((await session.changePassword('a', 'b')).kind).toBe('aggiornata')
    expect(session.state.epoch).toBeGreaterThan(epoca)
    expect(store.visitaBloccata(12)).toBe(true)
  })

  it('logout concluso e cambio identità: tutto svuotato; esiti tardivi scartati', async () => {
    const { session, store } = await avvia()
    net.on(REFERTO(12), 'rete')
    await store.caricaReferto(a(effettuata(12)), pdf())
    const h = net.trattieni(VISITA(13))
    const tardiva = store.registraVisita(a(voce(13)))
    net.on(LOGOUT, new Response(null, { status: 204 }))
    await session.logout()
    expect(store.state.refertiIncerti).toEqual([])
    net.on(LOGIN, meOk(medico({ idUtente: 8 }), CSRF_B))
    await session.login('altro@example.test', 'x')
    h.resolve(jsonResponse(200, registrata(13)))
    expect((await tardiva).kind).toBe('obsoleto')
    expect(store.state).toMatchObject({ owner: 8, visiteRegistrate: [], visiteIncerte: [], refertiIncerti: [] })
  })

  it('account paziente+medico: store distinti, nessun blocco condiviso', async () => {
    const { session, store } = await avvia(medico({ paziente: true }))
    const paziente = prenotazioniStore(session)
    expect(paziente).not.toBe(store)
    net.on(VISITA(12), 'rete')
    await store.registraVisita(a(voce(12)))
    expect(Object.keys(paziente.state)).not.toContain('visiteIncerte')
    expect(paziente.annullamentoBloccato(12)).toBe(false)
    expect(paziente.pagamentoBloccato(12)).toBe(false)
  })

  it('senza capacità medico: nessuna scrittura', async () => {
    const { store } = await avvia(utente())
    expect(await store.registraVisita(a(voce(12)))).toEqual({ kind: 'sessione-assente' })
    expect(await store.segnala([a(voce(12))])).toEqual({ kind: 'sessione-assente' })
    expect(await store.caricaReferto(a(effettuata(12)), pdf())).toEqual({ kind: 'sessione-assente' })
    expect(net.calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })
})
