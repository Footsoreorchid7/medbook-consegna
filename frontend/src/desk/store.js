import { effectScope, reactive, readonly, watch } from 'vue'
import { StaleResponseError } from '../api/http.js'
import * as api from '../api/desk.js'
import { configurazionePresente, fetchDisponibilita } from '../api/disponibilitaMedico.js'
import { importoValido } from '../api/pagamenti.js'
import { classifica } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Scritture dell'operatore desk  e loro esiti incerti.
 *
 * Solo in memoria, separato dagli store di paziente e medico: sopravvive alla navigazione nella
 * SPA e alla rotazione della sessione dello stesso utente, si svuota quando si autentica un
 * utente diverso o quando un logout si conclude. Gli esiti appartengono alla generazione in cui
 * è partita la richiesta. Nessun nuovo invio automatico.
 *
 * Ogni risorsa ha una chiave ('prenotazione:12', 'rimborso:5', 'slot:9'): una scrittura in volo o
 * dall'esito incerto blocca ogni altra operazione sulla stessa risorsa, da qualsiasi pagina.
 * Le operazioni (incasso, annullamento di una confermata, mancata erogazione) usano la stessa
 * chiave delle richieste e segnalazioni si bloccano a vicenda sulla stessa prenotazione.
 */

const isId = api.isId
const ERRORI = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }

export const chiavePrenotazione = (id) => `prenotazione:${id}`
export const chiaveRimborso = (id) => `rimborso:${id}`
export const chiaveSlot = (id) => `slot:${id}`
export const listaDisponibilita = (idMedico) => `disponibilita:${idMedico}`
/**
 * Ambito comune delle letture GET /api/prenotazioni/desk: ogni esito certo su una prenotazione
 * (gestione o consultazione) o su un rimborso vi pone una barriera, perché può cambiarne il contenuto.
 */
export const AMBITO_DESK = 'prenotazioni-desk'
const TIPI_B8 = Object.freeze(['incassa', 'annulla-confermata', 'mancata-erogazione'])

function createStore(session) {
  const state = reactive({
    owner: null,
    /** Chiavi delle risorse con una scrittura in volo. */
    inCorso: [],
    /**
     * Esiti incerti: { chiave, tipo, idRisorsa, riepilogo, verifica, esito }.
     * verifica: null | 'in-corso' | 'errore' | 'superata' | 'verificata'
     * esito (dopo la verifica): 'assente' (non conclusivo) o, per la configurazione, il riscontro.
     */
    incerte: [],
    /**
     * Barriera di ogni elenco: valore dell'orologio all'ultimo esito certo che lo riguarda.
     * Reattiva, perché le viste sospendano subito le azioni su un elenco letto prima.
     */
    barriere: {},
    generazione: 0,
  })
  let logoutAvviato = false
  // Ordinamento delle letture per elenco: avanza solo all'avvio di una lettura e a un esito certo.
  let orologio = 0
  let liste = new Map()
  /**
   * Riscontri per prenotazione dalle letture desk : 'prenotazione:id' → { ticket, voce, giorno }.
   * voce null = assenza nel giorno coperto da una lettura senza filtro medico (non conclusiva).
   * Vale l'ordine di avvio; un esito certo sulla prenotazione rende inutilizzabili i precedenti.
   */
  let riscontri = new Map()

  function svuota(owner) {
    state.generazione += 1
    state.owner = owner
    state.inCorso = []
    state.incerte = []
    state.barriere = {}
    liste = new Map()
    riscontri = new Map()
  }

  /** Stesse regole degli altri store: proprietario = ultimo utente autenticato. */
  function sincronizza() {
    const status = session.state.status
    if (status === STATO.AUTENTICATO) {
      const id = session.state.user?.idUtente
      if (id == null) return
      logoutAvviato = false
      if (state.owner !== id) svuota(id)
    } else if (status === STATO.DISCONNESSIONE) {
      logoutAvviato = true
    } else if (status === STATO.ANONIMO && logoutAvviato) {
      logoutAvviato = false
      svuota(null)
    }
  }

  const scope = effectScope(true)
  scope.run(() => {
    watch(() => [session.state.status, session.state.user?.idUtente], sincronizza, { flush: 'sync', immediate: true })
  })

  /** Identità dell'operatore desk, solo dalla sessione. L'amministratore non è operatore desk. */
  function operatoreCorrente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value.desk) return null
    const id = session.state.user?.idUtente
    return id === state.owner ? id : null
  }

  // ---------------------------------------------------------------- letture ordinate

  const lista = (k) => {
    if (!liste.has(k)) liste.set(k, { accettata: 0, completa: 0, voci: null })
    return liste.get(k)
  }
  const barriera = (k) => state.barriere[k] ?? 0
  /** Barriera di un elenco: per le letture desk anche quella dell'ambito comune. */
  const barrieraLista = (k) => Math.max(barriera(k), k.startsWith('rimborsi-storico:') ? barriera('rimborsi') : 0, k.startsWith(`${AMBITO_DESK}:`) ? barriera(AMBITO_DESK) : 0)
  function inizioLettura() {
    orologio += 1
    return Object.freeze({ n: orologio, generazione: state.generazione, epoca: session.state.epoch })
  }
  const ticketValido = (t) => t?.generazione === state.generazione && t.epoca === session.state.epoch

  /**
   * Validità reattiva di una lettura già applicata all'elenco `k`: cessa appena un esito certo
   * successivo all'avvio della lettura pone una barriera sull'elenco (anche da un'altra vista o
   * da una scrittura partita in un'istanza precedente), o cambiano generazione o epoca. Una
   * lettura avviata dopo la barriera resta valida.
   */
  const valida = (k, ticket) => ticketValido(ticket) && ticket.n > barrieraLista(k)

  /**
   * Applica una lettura dell'elenco `k`. Una risposta vale solo se la lettura è iniziata dopo
   * l'ultima accettata e dopo l'ultimo esito certo su quell'elenco. Solo una lettura completa
   * (senza filtri) diventa il riscontro per le verifiche: l'assenza da un elenco filtrato non
   * prova l'assenza della risorsa.
   * @returns {boolean} true se la lettura è accettata (adottabile dalla vista)
   */
  function applica(k, voci, ticket, completa) {
    const l = lista(k)
    if (!ticketValido(ticket) || ticket.n <= Math.max(l.accettata, barrieraLista(k))) return false
    l.accettata = ticket.n
    if (completa) {
      l.completa = ticket.n
      l.voci = voci
    }
    return true
  }
  function esitoCerto(...chiaviLista) {
    orologio += 1
    state.barriere = { ...state.barriere, ...Object.fromEntries(chiaviLista.map((k) => [k, orologio])) }
  }
  /** Riscontro per una verifica: l'ultima lettura completa, se almeno recente quanto la verifica e non superata da un esito certo. */
  function riscontro(k, ticket) {
    const l = lista(k)
    return l.voci !== null && l.completa >= ticket.n && barriera(k) < l.completa ? l.voci : null
  }

  // ---------------------------------------------------------------- blocchi

  const incerta = (chiave) => state.incerte.find((x) => x.chiave === chiave) ?? null
  /** Una scrittura in volo o un esito incerto non risolto bloccano ogni operazione sulla risorsa. */
  function bloccata(chiave) {
    const x = incerta(chiave)
    return state.inCorso.includes(chiave) || (x !== null && x.esito === null)
  }
  const aggiornaIncerta = (chiave, campi) => {
    state.incerte = state.incerte.map((x) => (x.chiave === chiave ? { ...x, ...campi } : x))
  }

  /**
   * Scrittura comune: controlli prima di fetch, un solo invio, classificazione dell'esito.
   * @param {{chiave, tipo, idRisorsa, riepilogo, liste: string[], verifica: string, invia: (idOperatore) => Promise}} op
   */
  async function esegui(op) {
    const idOperatore = operatoreCorrente()
    if (idOperatore === null) return { kind: 'sessione-assente' }
    if (state.inCorso.includes(op.chiave)) return { kind: 'occupato' }
    if (bloccata(op.chiave)) return { kind: 'bloccata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = state.generazione
    state.inCorso = [...state.inCorso, op.chiave]
    try {
      const risultato = await op.invia(idOperatore)
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      state.incerte = state.incerte.filter((x) => x.chiave !== op.chiave)
      esitoCerto(op.chiave, ...op.liste)
      return { kind: 'eseguita', risultato }
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      // Dati non validi rifiutati localmente: nessuna richiesta inviata, nessuna barriera.
      if (error instanceof TypeError) return { kind: 'non-valida' }
      const esito = classifica(error, ERRORI)
      if (esito.kind === 'incerto') {
        state.incerte = [...state.incerte.filter((x) => x.chiave !== op.chiave), {
          chiave: op.chiave, tipo: op.tipo, idRisorsa: op.idRisorsa, riepilogo: op.riepilogo, verificaSu: op.verifica,
          verifica: null, esito: null, ...(op.extra ?? {}),
        }]
      } else if (['conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) {
        // Rifiuto certo: lo stato della risorsa può essere cambiato, le letture precedenti sono superate.
        esitoCerto(op.chiave, ...op.liste)
      }
      return esito
    } finally {
      if (mia === state.generazione) state.inCorso = state.inCorso.filter((x) => x !== op.chiave)
    }
  }

  // ---------------------------------------------------------------- operazioni

  /** Conferma, rifiuto o annullamento (statoAtteso RICHIESTA) di una richiesta letta dall'elenco. */
  function decidiRichiesta(tipo, r) {
    if (r.stato !== 'RICHIESTA') return Promise.resolve({ kind: 'non-valida' })
    if (tipo === 'conferma' && r.indisponibilitaSegnalata) return Promise.resolve({ kind: 'segnalata' })
    return esegui({
      chiave: chiavePrenotazione(r.idPrenotazione), tipo, idRisorsa: r.idPrenotazione, riepilogo: r,
      liste: ['richieste', 'segnalazioni', AMBITO_DESK], verifica: 'richieste',
      invia: async (op) => api.parseDecisionePrenotazione(
        await api.postDecisionePrenotazione(session.protectedRequest, tipo, r.idPrenotazione, op, tipo === 'annulla' ? 'RICHIESTA' : null),
        tipo, r.idPrenotazione, op, tipo === 'conferma' ? r.medico.id : null),
    })
  }

  /** Sostituzione del medico indisponibile, con stato e medico indisponibile della segnalazione letta. */
  function sostituisci(s, idSostituto) {
    return esegui({
      chiave: chiavePrenotazione(s.idPrenotazione), tipo: 'sostituisci', idRisorsa: s.idPrenotazione, riepilogo: { ...s, idSostituto },
      liste: ['richieste', 'segnalazioni', AMBITO_DESK], verifica: 'segnalazioni',
      invia: async (op) => api.parseSostituzione(await api.postSostituzione(session.protectedRequest, s, op, idSostituto), s.idPrenotazione, op, idSostituto),
    })
  }

  /** Annullamento di una prenotazione segnalata, con lo stato letto. */
  function annullaSegnalata(s) {
    return esegui({
      chiave: chiavePrenotazione(s.idPrenotazione), tipo: 'annulla-segnalata', idRisorsa: s.idPrenotazione, riepilogo: s,
      liste: ['richieste', 'segnalazioni', AMBITO_DESK], verifica: 'segnalazioni',
      invia: async (op) => api.parseDecisionePrenotazione(
        await api.postDecisionePrenotazione(session.protectedRequest, 'annulla', s.idPrenotazione, op, s.stato), 'annulla', s.idPrenotazione, op),
    })
  }

  function decidiRimborso(tipo, r) {
    if (r?.stato !== 'RICHIESTO') return Promise.resolve({ kind: 'non-pendente' })
    return esegui({
      chiave: chiaveRimborso(r.idRimborso), tipo, idRisorsa: r.idRimborso, riepilogo: r,
      liste: ['rimborsi', AMBITO_DESK, chiavePrenotazione(r.idPrenotazione)], verifica: 'rimborsi',
      invia: async (op) => api.parseDecisioneRimborso(await api.postDecisioneRimborso(session.protectedRequest, tipo, r.idRimborso), tipo, r.idRimborso, op),
    })
  }

  /** Configurazione unica di uno slot non configurato del medico indicato. */
  function configura(slot, medico, prestazione, stanza) {
    if (configurazionePresente(slot)) return Promise.resolve({ kind: 'configurata' })
    const k = listaDisponibilita(medico.idUtente)
    return esegui({
      chiave: chiaveSlot(slot.idDisponibilita), tipo: 'configura', idRisorsa: slot.idDisponibilita,
      riepilogo: { slot, medico, prestazione, stanza }, liste: [k], verifica: k,
      invia: async () => api.parseConfigurazione(
        await api.putConfigurazione(session.protectedRequest, slot.idDisponibilita, prestazione.idPrestazione, stanza.idStanza),
        slot, medico.idUtente, prestazione.idPrestazione, stanza.idStanza),
    })
  }

  // ---------------------------------------------------------------- prenotazioni gestite

  /** Registra un riscontro della prenotazione se più recente del precedente e di ogni esito certo su di essa. */
  function registraRiscontro(k, ticket, voce, giorno) {
    if (!ticketValido(ticket) || ticket.n <= barriera(k)) return
    const prec = riscontri.get(k)
    if (prec && ticketValido(prec.ticket) && prec.ticket.n >= ticket.n) return
    riscontri.set(k, Object.freeze({ ticket, voce, giorno }))
  }

  /** Giorno noto dell'appuntamento per ogni prenotazione: dai riscontri e dagli esiti incerti. */
  function giorniNoti() {
    const giorni = new Map()
    for (const [k, r] of riscontri) giorni.set(k, r.giorno)
    for (const x of state.incerte) {
      const g = giornoDi(x)
      if (g) giorni.set(chiaveRiscontro(x), g)
    }
    return giorni
  }
  const perRimborso = (x) => x.verificaSu === 'rimborsi'
  /** Prenotazione su cui si verifica l'esito: per una decisione di rimborso, quella del rimborso. */
  const chiaveRiscontro = (x) => (perRimborso(x) ? chiavePrenotazione(x.riepilogo.idPrenotazione) : x.chiave)
  const giornoDi = (x) => (perRimborso(x) ? x.riepilogo.inizio?.date ?? null : TIPI_B8.includes(x.tipo) ? x.riepilogo.giorno : null)

  /**
   * Applica una lettura GET /api/prenotazioni/desk con la sua copertura (api.coperturaDesk).
   * Come elenco vale per la copertura, con ordine di avvio e barriere (anche dell'ambito comune);
   * indipendentemente, ogni prenotazione presente diventa il riscontro corrente di quella
   * prenotazione. L'assenza è registrata solo per prenotazioni di cui è noto il giorno, se il
   * giorno è coperto e la lettura non filtra per medico (una sostituzione può escluderle dal
   * filtro): resta comunque non conclusiva.
   * @returns {boolean} true se la lettura è accettata come elenco della copertura
   */
  function applicaPrenotazioni(copertura, voci, ticket) {
    if (!ticketValido(ticket)) return false
    const presenti = new Set()
    for (const v of voci) {
      presenti.add(chiavePrenotazione(v.idPrenotazione))
      registraRiscontro(chiavePrenotazione(v.idPrenotazione), ticket, v, v.inizio.date)
    }
    if (copertura.medicoId === null) {
      for (const [k, giorno] of giorniNoti()) {
        if (giorno >= copertura.dataDa && giorno <= copertura.dataA && !presenti.has(k)) registraRiscontro(k, ticket, null, giorno)
      }
    }
    return applica(copertura.chiave, voci, ticket, true)
  }

  /** Riscontro corrente di una prenotazione, se usabile per una lettura avviata con `ticket`. */
  function riscontroPrenotazione(idPrenotazione, ticket) {
    const k = chiavePrenotazione(idPrenotazione)
    const r = riscontri.get(k)
    return r && ticketValido(r.ticket) && r.ticket.n >= ticket.n && r.ticket.n > barriera(k) ? r : null
  }

  const STATI_ATTIVI = Object.freeze(['PAGATO', 'IN_ATTESA'])
  const risolto = (kind) => Promise.resolve({ kind })

  /**
   * Incasso in sede (UC-14) di una CONFERMATA letta dall'elenco desk, con le condizioni del
   * backend verificabili sui dati letti: nessuna segnalazione pendente, nessun pagamento PAGATO
   * o IN_ATTESA, prezzo storico positivo inviato come importo atteso. Nessun controllo orario:
   * decide il backend. I pagamenti noti prima dell'invio servono alla verifica di un esito incerto.
   */
  function incassa(v, modalita) {
    if (v.stato !== 'CONFERMATA') return risolto('non-confermata')
    if (v.indisponibilitaSegnalata) return risolto('segnalata')
    if (v.pagamenti.some((p) => STATI_ATTIVI.includes(p.stato))) return risolto('pagamento-presente')
    if (!importoValido(v.prezzo)) return risolto('nessun-importo')
    const noti = Object.freeze(v.pagamenti.map((p) => p.idPagamento))
    return esegui({
      chiave: chiavePrenotazione(v.idPrenotazione), tipo: 'incassa', idRisorsa: v.idPrenotazione,
      riepilogo: Object.freeze({ voce: v, giorno: v.inizio.date, modalita, importo: v.prezzo, noti }),
      liste: [AMBITO_DESK], verifica: AMBITO_DESK,
      invia: async (op) => api.parsePagamentoSede(
        await api.postPagamentoSede(session.protectedRequest, v.idPrenotazione, op, modalita, v.prezzo), v.idPrenotazione, op, modalita, v.prezzo),
    })
  }

  /**
   * Annullamento di una CONFERMATA (UC-13). Come nel backend, ogni pagamento diverso da FALLITO
   * lo impedisce: nessun rimborso è previsto per le confermate pagate. Il vincolo dell'inizio
   * futuro è deciso dal backend, non dall'orologio del browser.
   */
  function annullaConfermata(v) {
    if (v.stato !== 'CONFERMATA') return risolto('non-confermata')
    if (v.pagamenti.some((p) => p.stato !== 'FALLITO')) return risolto('pagamenti-bloccanti')
    return esegui({
      chiave: chiavePrenotazione(v.idPrenotazione), tipo: 'annulla-confermata', idRisorsa: v.idPrenotazione,
      riepilogo: Object.freeze({ voce: v, giorno: v.inizio.date }),
      liste: ['segnalazioni', AMBITO_DESK], verifica: AMBITO_DESK,
      invia: async (op) => api.parseDecisionePrenotazione(
        await api.postDecisionePrenotazione(session.protectedRequest, 'annulla', v.idPrenotazione, op, 'CONFERMATA'), 'annulla', v.idPrenotazione, op),
    })
  }

  /**
   * Esito negativo di una CONFERMATA (UC-15): PAZIENTE_ASSENTE oppure NON_EROGATA. Un pagamento
   * IN_ATTESA lo impedisce; con una segnalazione pendente è ammessa solo NON_EROGATA. La fine
   * dello slot è verificata dal backend. Nessun rimborso è creato o proposto da questa operazione.
   */
  function registraMancataErogazione(v, esito) {
    if (!api.ESITI_MANCATA_EROGAZIONE.includes(esito)) return risolto('non-valida')
    if (v.stato !== 'CONFERMATA') return risolto('non-confermata')
    if (v.pagamenti.some((p) => p.stato === 'IN_ATTESA')) return risolto('pagamento-in-attesa')
    if (esito === 'PAZIENTE_ASSENTE' && v.indisponibilitaSegnalata) return risolto('segnalata')
    return esegui({
      chiave: chiavePrenotazione(v.idPrenotazione), tipo: 'mancata-erogazione', idRisorsa: v.idPrenotazione,
      riepilogo: Object.freeze({ voce: v, giorno: v.inizio.date, esito }),
      liste: ['segnalazioni', AMBITO_DESK], verifica: AMBITO_DESK,
      invia: async (op) => api.parseMancataErogazione(
        await api.postMancataErogazione(session.protectedRequest, v.idPrenotazione, op, esito), v.idPrenotazione, op, esito),
    })
  }

  /**
   * Prima fase della verifica legge il giorno noto dell'appuntamento, senza filtro medico,
   * e applica la lettura come ogni altra. Non decide l'esito: lo costruisce esitoVerificaDesk
   * sul riscontro corrente, che può provenire anche da una lettura successiva.
   * @returns {{kind:'letta', ticket} | {kind:string}}
   */
  async function leggiVerificaDesk(chiave) {
    const idOperatore = operatoreCorrente()
    const x = incerta(chiave)
    if (idOperatore === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const giorno = giornoDi(x)
    if (!giorno) return { kind: 'non-applicabile' }
    const mia = state.generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaIncerta(chiave, { verifica: 'in-corso' })
    const copertura = api.coperturaDesk({ data: giorno })
    const ticket = inizioLettura()
    let voci
    try {
      voci = await api.fetchPrenotazioniDesk(session.protectedRequest, copertura)
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaIncerta(chiave, { verifica: stale || precedente === 'verificata' ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== state.generazione || state.owner !== idOperatore) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaIncerta(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    applicaPrenotazioni(copertura, voci, ticket)
    return { kind: 'letta', ticket }
  }

  /**
   * Seconda fase: esito dal riscontro autorevole corrente della prenotazione, purché non
   * precedente alla lettura della verifica né a un esito certo sulla prenotazione. Una risposta
   * superata non decide nulla: esito 'superata', blocco mantenuto, verifica da ripetere.
   * Nessun esito attribuisce l'operazione al tentativo o a un operatore; l'assenza non prova
   * il fallimento e resta non conclusiva.
   * esito: incasso 'nessun-nuovo' | 'nuovo-pagato' | 'nuovo-non-pagato';
   *   annullamento 'annullata' | 'confermata' | 'altro-stato';
   *   mancata erogazione 'come-richiesto' | 'confermata' | 'altro-stato';
   *   decisione di rimborso 'presente' | 'come-richiesto' | 'diverso';
   *   tutti 'assente' (prenotazione o rimborso non trovati nel giorno coperto).
   */
  function esitoVerificaDesk(chiave, lettura) {
    const x = incerta(chiave)
    if (operatoreCorrente() === null || x === null) return { kind: 'sessione-assente' }
    if (!ticketValido(lettura?.ticket)) return { kind: 'obsoleto' }
    const k = chiaveRiscontro(x)
    const r = riscontri.get(k)
    if (!r || !ticketValido(r.ticket) || r.ticket.n < lettura.ticket.n || r.ticket.n <= barriera(k) || r.ticket.n <= barriera(chiave)) {
      aggiornaIncerta(chiave, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    const voce = r.voce
    const fonte = r.ticket.n === lettura.ticket.n ? 'verifica' : 'successiva'
    const rimborso = voce && perRimborso(x) ? voce.rimborsi.find((v) => v.idRimborso === x.idRisorsa) ?? null : null
    if (!voce || (perRimborso(x) && !rimborso)) {
      aggiornaIncerta(chiave, { verifica: 'verificata', esito: 'assente' })
      return { kind: 'verificata', esito: 'assente', voce, incerta: x, fonte }
    }
    let esito
    let nuovi = Object.freeze([])
    if (x.tipo === 'incassa') {
      nuovi = Object.freeze(voce.pagamenti.filter((p) => !x.riepilogo.noti.includes(p.idPagamento)))
      esito = nuovi.length === 0 ? 'nessun-nuovo' : nuovi.some((p) => p.stato === 'PAGATO') ? 'nuovo-pagato' : 'nuovo-non-pagato'
    } else if (x.tipo === 'annulla-confermata') {
      esito = voce.stato === 'ANNULLATA' ? 'annullata' : voce.stato === 'CONFERMATA' ? 'confermata' : 'altro-stato'
    } else if (x.tipo === 'mancata-erogazione') {
      esito = voce.stato === x.riepilogo.esito ? 'come-richiesto' : voce.stato === 'CONFERMATA' ? 'confermata' : 'altro-stato'
    } else {
      const richiesto = x.tipo === 'approva' ? 'APPROVATO' : 'RESPINTO'
      esito = rimborso.stato === 'RICHIESTO' ? 'presente' : rimborso.stato === richiesto ? 'come-richiesto' : 'diverso'
    }
    // Riscontro corrente acquisito: l'incertezza si chiude; un nuovo invio resta solo esplicito, sui dati riletti.
    state.incerte = state.incerte.filter((v) => v.chiave !== chiave)
    return { kind: 'verificata', esito, voce, rimborso, nuovi, incerta: x, fonte }
  }

  async function verificaDesk(chiave) {
    const lettura = await leggiVerificaDesk(chiave)
    return lettura.kind === 'letta' ? esitoVerificaDesk(chiave, lettura) : lettura
  }

  /**
   * Recupero facoltativo di una decisione di rimborso incerta tramite la lettura desk: solo se
   * sono noti la prenotazione e il giorno dell'appuntamento. L'assenza dall'elenco dei rimborsi
   * pendenti resta non conclusiva; qui conta soltanto il rimborso trovato nella prenotazione.
   */
  function verificaRimborsoDesk(chiave) {
    const x = incerta(chiave)
    if (x !== null && (!perRimborso(x) || !isId(x.riepilogo?.idPrenotazione) || !giornoDi(x))) return risolto('non-applicabile')
    return verificaDesk(chiave)
  }

  // ---------------------------------------------------------------- verifiche

  const LETTURE = {
    richieste: () => api.fetchRichieste(session.protectedRequest, {}),
    segnalazioni: () => api.fetchSegnalazioni(session.protectedRequest),
    rimborsi: () => api.fetchRimborsiDesk(session.protectedRequest),
  }
  const idDi = { richieste: (v) => v.idPrenotazione, segnalazioni: (v) => v.idPrenotazione, rimborsi: (v) => v.idRimborso }

  /**
   * Verifica manuale su un elenco completo. Gli elenchi del desk contengono solo risorse
   * pendenti: una richiesta o un rimborso ancora presenti sono ancora nello stato iniziale (nuovo
   * tentativo solo esplicito); una prenotazione ancora tra le segnalazioni si confronta con lo
   * snapshot del tentativo (riscontroSegnalazione), perché può essere una nuova segnalazione;
   * una risorsa assente non indica quale operazione sia avvenuta né chi l'abbia eseguita.
   * Per la configurazione: riscontro corrente dello slot nell'elenco del medico.
   * esito: 'presente' | 'assente' | 'medico-sostituto' | 'invariata' | 'cambiata'
   *   | 'come-richiesto' | 'diversa' | 'non-configurata'
   */
  async function verifica(chiave) {
    const idOperatore = operatoreCorrente()
    const x = incerta(chiave)
    if (idOperatore === null || x === null) return { kind: 'sessione-assente' }
    if (x.verificaSu === AMBITO_DESK) return verificaDesk(chiave)
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = state.generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaIncerta(chiave, { verifica: 'in-corso' })
    const k = x.verificaSu
    const ticket = inizioLettura()
    let voci
    try {
      voci = k.startsWith('disponibilita:')
        ? await fetchDisponibilita(session.protectedRequest, x.riepilogo.medico.idUtente)
        : await LETTURE[k]()
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaIncerta(chiave, { verifica: stale || precedente === 'verificata' ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== state.generazione || state.owner !== idOperatore) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaIncerta(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    applica(k, voci, ticket, true)
    const correnti = riscontro(k, ticket)
    if (correnti === null) {
      aggiornaIncerta(chiave, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    let esito
    let voce = null
    if (k.startsWith('disponibilita:')) {
      voce = correnti.find((s) => s.idDisponibilita === x.idRisorsa) ?? null
      const r = x.riepilogo
      if (!voce) esito = 'assente'
      else if (!configurazionePresente(voce)) esito = 'non-configurata'
      else if (voce.prestazione?.id === r.prestazione.idPrestazione && voce.stanza?.id === r.stanza.idStanza) esito = 'come-richiesto'
      else esito = 'diversa'
    } else {
      voce = correnti.find((v) => idDi[k](v) === x.idRisorsa) ?? null
      if (!voce) esito = 'assente'
      else esito = k === 'segnalazioni' ? riscontroSegnalazione(x, voce) : 'presente'
    }
    // Un riscontro della risorsa chiude l'incertezza (un nuovo invio resta solo esplicito, sui dati
    // riletti); l'assenza resta segnalata, non conclusiva, finché l'operatore non la rimuove.
    if (esito === 'assente') aggiornaIncerta(chiave, { verifica: 'verificata', esito })
    else state.incerte = state.incerte.filter((v) => v.chiave !== chiave)
    return { kind: 'verificata', esito, voce }
  }

  /**
   * Riscontro di una prenotazione ancora tra le segnalazioni, confrontato con lo snapshot del
   * tentativo (stato, medico indisponibile, sostituto richiesto). Nelle segnalazioni il medico
   * indisponibile è il medico corrente della prenotazione.
   * - 'medico-sostituto': il medico corrente è il sostituto richiesto, a sua volta segnalato:
   *   è una nuova segnalazione, non il tentativo verificato;
   * - 'invariata': stesso stato e stesso medico rilevati al tentativo;
   * - 'cambiata': altro medico o altro stato.
   * Nessuno dei tre attribuisce con certezza l'esito al tentativo né a un operatore.
   */
  function riscontroSegnalazione(x, voce) {
    const r = x.riepilogo
    if (x.tipo === 'sostituisci' && voce.indisponibile.id === r.idSostituto) return 'medico-sostituto'
    if (voce.stato === r.stato && voce.indisponibile.id === r.indisponibile.id) return 'invariata'
    return 'cambiata'
  }

  function chiudiIncerta(chiave, owner) {
    if (owner !== state.owner) return
    state.incerte = state.incerte.filter((x) => !(x.chiave === chiave && x.esito === 'assente'))
  }

  return {
    state: readonly(state),
    operatoreCorrente,
    inizioLettura,
    applica,
    valida,
    incerta,
    bloccata,
    decidiRichiesta,
    sostituisci,
    annullaSegnalata,
    decidiRimborso,
    configura,
    verifica,
    chiudiIncerta,
    applicaPrenotazioni,
    riscontroPrenotazione,
    incassa,
    annullaConfermata,
    registraMancataErogazione,
    leggiVerificaDesk,
    esitoVerificaDesk,
    verificaRimborsoDesk,
  }
}

const stores = new WeakMap()

/** Store unico per ogni sessione, distinto da quelli di paziente e medico. */
export function deskStore(session) {
  let store = stores.get(session)
  if (!store) {
    store = createStore(session)
    stores.set(session, store)
  }
  return store
}

export function useDesk() {
  return deskStore(useSession())
}
