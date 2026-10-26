import { effectScope, reactive, readonly, watch } from 'vue'
import * as api from '../api/prenotazioni.js'
import * as apiPagamenti from '../api/pagamenti.js'
import * as apiRimborsi from '../api/rimborsi.js'
import { HttpError } from '../api/http.js'
import { AuthStateError, PasswordUncertainError, STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Scritture del paziente e loro esiti incerti, condivisi dalle viste e .
 *
 * Vive solo in memoria, accanto alla sessione: nessun localStorage, sessionStorage o
 * IndexedDB. Sopravvive alla navigazione nella SPA e alla rotazione della sessione dello
 * stesso utente, così un tentativo incerto resta bloccato finché non viene verificato.
 * Viene svuotato quando si autentica un utente diverso o quando un logout si conclude.
 *
 * Le scritture non vengono mai ripetute in automatico. Ogni esito appartiene alla
 * "generazione" in cui è partita la richiesta: dopo uno svuotamento i risultati tardivi
 * sono scartati senza toccare lo stato del nuovo utente.
 */

const ERRORI_RICHIESTA = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }
const ERRORI_ANNULLAMENTO = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }
const ERRORI_ECONOMICI = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }

/** Stati che indicano una prenotazione ancora attiva o già erogata per lo slot. */
const STATI_CHIUSI = new Set(['ANNULLATA', 'RIFIUTATA'])

/**
 * Classifica un errore di scrittura. 401 e i rifiuti locali (stato di sessione, password
 * incerta) sono esiti certi: la richiesta non è stata eseguita. Un 4xx è un rifiuto certo.
 * Rete, 5xx, risposta 2xx non conforme o arrivata per una sessione non più attuale sono
 * esiti incerti: il server potrebbe aver eseguito la scrittura.
 */
export function classifica(error, mappa) {
  if (error instanceof PasswordUncertainError) return { kind: 'password-incerta' }
  if (error instanceof AuthStateError) return { kind: 'sessione-assente' }
  if (error instanceof HttpError && error.status === 401) return { kind: 'sessione-assente' }
  if (error instanceof HttpError && error.status < 500) {
    return { kind: mappa[error.status] ?? 'rifiutata', serverMessage: error.serverMessage }
  }
  return { kind: 'incerto' }
}

function createStore(session) {
  const state = reactive({
    owner: null,
    /** idDisponibilita della richiesta in volo, oppure null. */
    invioInCorso: null,
    /** idPrenotazione degli annullamenti in volo. */
    annullamentiInCorso: [],
    /**
     * Richieste dall'esito incerto: { idDisponibilita, riepilogo, verifica }.
     * verifica: null | 'in-corso' | 'assente' | 'solo-chiuse' | 'errore'
     */
    richiesteIncerte: [],
    /** Annullamenti dall'esito incerto: { idPrenotazione, statoAtteso, verificato }. */
    annullamentiIncerti: [],
    /** idPrenotazione dei pagamenti online in volo. */
    pagamentiInCorso: [],
    /**
     * Pagamenti online dall'esito incerto: { idPrenotazione, importoAtteso, noti, verifica, esito }.
     * `noti`: idPagamento già nello storico prima del tentativo.
     * verifica: null | 'in-corso' | 'errore' | 'superata' | 'verificata'
     * esito (dopo la verifica): 'nessun-nuovo' | 'nuovo-fallito' | 'nuovo-attivo'
     */
    pagamentiIncerti: [],
    /** idPrenotazione delle richieste di rimborso in volo. */
    rimborsiInCorso: [],
    /**
     * Richieste di rimborso dall'esito incerto: { idPrenotazione, idPagamento, importo, verifica }.
     * verifica: null | 'in-corso' | 'errore' | 'superata' | 'assente'
     */
    rimborsiIncerti: [],
    /**
     * Riscontri condivisi, indipendenti dalle viste e dall'esito incerto di un tentativo
     * (ordinamento descritto in «letture autorevoli»):
     * - ambitiPagamento: { idPrenotazione, attivo, applicato, barriera };
     * - ambitiRimborso: { idPagamento, idPrenotazione, esiste, applicato, barriera };
     * - rimborsiApplicatoGlobale: ticket dell'ultimo elenco rimborsi globale applicato.
     */
    barrierePrenotazione: [],
    ambitiPagamento: [],
    ambitiRimborso: [],
    rimborsiApplicatoGlobale: 0,
  })
  let generazione = 0
  let logoutAvviato = false

  function svuota(owner) {
    generazione += 1
    state.owner = owner
    state.invioInCorso = null
    state.annullamentiInCorso = []
    state.richiesteIncerte = []
    state.annullamentiIncerti = []
    state.pagamentiInCorso = []
    state.pagamentiIncerti = []
    state.rimborsiInCorso = []
    state.rimborsiIncerti = []
    state.barrierePrenotazione = []
    state.ambitiPagamento = []
    state.ambitiRimborso = []
    state.rimborsiApplicatoGlobale = 0
  }

  /**
   * Proprietario delle incertezze: l'ultimo utente autenticato. Una sessione persa o una
   * rotazione non lo cambiano; un utente diverso o un logout concluso svuotano tutto.
   */
  function sincronizza() {
    const status = session.state.status
    if (status === STATO.AUTENTICATO) {
      // Logout e sessione persa azzerano l'identità prima di cambiare stato: quel passaggio
      // intermedio non indica un cambio di utente.
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

  const richiestaIncerta = (idDisponibilita) => state.richiesteIncerte.find((r) => r.idDisponibilita === idDisponibilita) ?? null
  const annullamentoIncerto = (idPrenotazione) => state.annullamentiIncerti.find((a) => a.idPrenotazione === idPrenotazione) ?? null

  /** Un tentativo incerto blocca il reinvio finché una verifica manuale non lo ha cercato. */
  function richiestaBloccata(idDisponibilita) {
    const r = richiestaIncerta(idDisponibilita)
    return r !== null && r.verifica !== 'assente' && r.verifica !== 'solo-chiuse'
  }

  function annullamentoBloccato(idPrenotazione) {
    const a = annullamentoIncerto(idPrenotazione)
    return a !== null && !a.verificato
  }

  /** Identità corrente del paziente, solo dalla sessione; null se non utilizzabile. */
  function pazienteCorrente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value.paziente) return null
    const id = session.state.user?.idUtente
    return id === state.owner ? id : null
  }

  /**
   * Invia la richiesta di appuntamento per lo slot scelto.
   * @param {{idDisponibilita:number}} slot con i dati di riepilogo mostrati all'utente
   */
  async function richiedi(slot) {
    const idPaziente = pazienteCorrente()
    if (idPaziente === null) return { kind: 'sessione-assente' }
    if (state.invioInCorso !== null) return { kind: 'occupato' }
    if (richiestaBloccata(slot.idDisponibilita)) return { kind: 'bloccata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    state.invioInCorso = slot.idDisponibilita
    try {
      const creata = api.parsePrenotazioneCreata(
        await api.postPrenotazione(session.protectedRequest, idPaziente, slot.idDisponibilita),
        slot.idDisponibilita,
      )
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.richiesteIncerte = state.richiesteIncerte.filter((r) => r.idDisponibilita !== slot.idDisponibilita)
      return { kind: 'inviata', prenotazione: creata }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_RICHIESTA)
      if (esito.kind === 'incerto') {
        const riepilogo = Object.freeze({ ...slot })
        state.richiesteIncerte = [
          ...state.richiesteIncerte.filter((r) => r.idDisponibilita !== slot.idDisponibilita),
          { idDisponibilita: slot.idDisponibilita, riepilogo, verifica: null },
        ]
      }
      return esito
    } finally {
      if (mia === generazione) state.invioInCorso = null
    }
  }

  /**
   * Verifica manuale di una richiesta incerta nell'elenco personale, senza filtri che
   * possano escluderla. Confronta lo slot e richiede che l'identità sia ancora quella del
   * tentativo. Una richiesta non trovata non prova il fallimento: si consente solo un
   * nuovo invio esplicito, che il server rifiuta se lo slot risulta già occupato.
   */
  async function verificaRichiesta(idDisponibilita) {
    const idPaziente = pazienteCorrente()
    const voce = richiestaIncerta(idDisponibilita)
    if (idPaziente === null || voce === null) return { kind: 'sessione-assente' }
    if (voce.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const aggiorna = (verifica) => {
      state.richiesteIncerte = state.richiesteIncerte.map((r) => (r.idDisponibilita === idDisponibilita ? { ...r, verifica } : r))
    }
    const precedente = voce.verifica
    aggiorna('in-corso')
    let elenco
    try {
      elenco = await api.fetchPrenotazioniPaziente(session.protectedRequest, { idPaziente })
    } catch {
      if (mia !== generazione) return { kind: 'obsoleto' }
      // Una verifica fallita non sblocca nulla e non annulla una verifica precedente riuscita.
      aggiorna(precedente === 'assente' || precedente === 'solo-chiuse' ? precedente : 'errore')
      return { kind: 'errore' }
    }
    if (mia !== generazione || state.owner !== idPaziente) return { kind: 'obsoleto' }
    const corrispondenti = elenco.filter((p) => p.idDisponibilita === idDisponibilita)
    const attiva = corrispondenti.find((p) => !STATI_CHIUSI.has(p.stato))
    if (attiva) {
      state.richiesteIncerte = state.richiesteIncerte.filter((r) => r.idDisponibilita !== idDisponibilita)
      return { kind: 'trovata', prenotazione: attiva }
    }
    const kind = corrispondenti.length > 0 ? 'solo-chiuse' : 'assente'
    aggiorna(kind)
    return { kind }
  }

  const operazioneInCorso = id => state.annullamentiInCorso.includes(id) || state.pagamentiInCorso.includes(id) || state.rimborsiInCorso.includes(id)
  function altraIncertezza(id, tipo) {
    return (tipo !== 'annulla' && annullamentoBloccato(id))
      || (tipo !== 'paga' && pagamentoIncerto(id) !== null && pagamentoIncerto(id).verifica !== 'verificata')
      || (tipo !== 'rimborso' && rimborsoIncerto(id) !== null && rimborsoIncerto(id).verifica !== 'assente')
  }

  /** Annullamento con lo stato letto dalla risorsa; nessun controllo temporale lato client. */
  async function annulla(prenotazione, ticket) {
    const idPaziente = pazienteCorrente()
    if (idPaziente === null) return { kind: 'sessione-assente' }
    const id = prenotazione.idPrenotazione
    if (operazioneInCorso(id)) return { kind: 'occupato' }
    if (altraIncertezza(id, 'annulla')) return { kind: 'bloccata' }
    if (ticket !== undefined && !prenotazioneValida(id, ticket)) return { kind: 'dati-superati' }
    if (annullamentoBloccato(id)) return { kind: 'bloccata' }
    if (!api.STATI_ANNULLABILI.includes(prenotazione.stato)) return { kind: 'non-annullabile' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    state.annullamentiInCorso = [...state.annullamentiInCorso, id]
    try {
      const esito = api.parseAnnullamento(
        await api.postAnnullamento(session.protectedRequest, id, idPaziente, prenotazione.stato),
        id,
      )
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.annullamentiIncerti = state.annullamentiIncerti.filter((a) => a.idPrenotazione !== id)
      barrieraPrenotazione(id)
      return { kind: 'annullata', esito }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_ANNULLAMENTO)
      if (esito.kind === 'incerto') {
        state.annullamentiIncerti = [
          ...state.annullamentiIncerti.filter((a) => a.idPrenotazione !== id),
          { idPrenotazione: id, statoAtteso: prenotazione.stato, verificato: false },
        ]
      }
      return esito
    } finally {
      if (mia === generazione) state.annullamentiInCorso = state.annullamentiInCorso.filter((x) => x !== id)
    }
  }

  /**
   * Registra una verifica manuale della risorsa dopo un annullamento incerto: lo stato
   * riletto è mostrato all'utente e un nuovo annullamento torna possibile solo su sua
   * richiesta esplicita, con lo stato appena letto.
   */
  function annullamentoVerificato(idPrenotazione, owner) {
    if (owner !== state.owner) return
    state.annullamentiIncerti = state.annullamentiIncerti.map((a) => (a.idPrenotazione === idPrenotazione ? { ...a, verificato: true } : a))
  }

  // ------------------------------------------------------------------ letture autorevoli

  /**
   * Ordinamento dei riscontri condivisi. Tre informazioni distinte:
   * - `orologio`: ordine di AVVIO delle letture e degli esiti certi di scrittura; avanza solo
   *   per questi eventi, mai quando si applica una risposta;
   * - `applicato`: ticket dell'ultima lettura applicata a un ambito, positiva o negativa;
   * - `barriera`: istante dell'ultimo esito certo di scrittura sull'ambito.
   * Una risposta si applica a un ambito solo se la sua lettura è iniziata dopo entrambi;
   * altrimenti è ignorata. Un ticket vale solo per l'identità (generazione) e l'epoca di
   * sessione in cui è nato: logout, cambio utente o nuova sessione lo rendono inutilizzabile.
   */
  let orologio = 0
  function inizioLettura() {
    orologio += 1
    return Object.freeze({ n: orologio, generazione, epoca: session.state.epoch })
  }
  const ticketValido = (t) => t?.generazione === generazione && t.epoca === session.state.epoch
  const piuRecente = (t, ambito, minimo = 0) => t.n > Math.max(ambito?.applicato ?? 0, ambito?.barriera ?? 0, minimo)

  function barrieraPrenotazione(id) {
    orologio += 1
    state.barrierePrenotazione = [...state.barrierePrenotazione.filter(x => x.id !== id), { id, n: orologio }]
  }
  function prenotazioneValida(id, ticket) {
    return ticketValido(ticket) && ticket.n > (state.barrierePrenotazione.find(x => x.id === id)?.n ?? 0)
  }

  // Pagamenti: un ambito per prenotazione; `attivo` = noto un pagamento PAGATO o IN_ATTESA.
  const ambitoPagamento = (idPrenotazione) => state.ambitiPagamento.find((x) => x.idPrenotazione === idPrenotazione) ?? null
  function aggiornaAmbitoPagamento(idPrenotazione, campi) {
    const prima = ambitoPagamento(idPrenotazione) ?? { idPrenotazione, attivo: false, applicato: 0, barriera: 0 }
    state.ambitiPagamento = [...state.ambitiPagamento.filter((x) => x.idPrenotazione !== idPrenotazione), { ...prima, ...campi }]
  }

  /**
   * Applica uno storico letto dal server (lettura per prenotazione): l'intero storico decide se
   * esiste un pagamento attivo. Ignorato se la lettura è iniziata prima dell'ultima applicata
   * o dell'ultimo esito certo di scrittura.
   * @returns {boolean} true se la risposta è stata applicata, false se superata o non valida
   */
  function registraStorico(idPrenotazione, storico, ticket) {
    if (!ticketValido(ticket) || !piuRecente(ticket, ambitoPagamento(idPrenotazione))) return false
    aggiornaAmbitoPagamento(idPrenotazione, {
      attivo: storico.some((x) => apiPagamenti.STATI_BLOCCANTI.includes(x.stato)), applicato: ticket.n,
    })
    return true
  }

  /** Esito certo: invalida le letture precedenti; FALLITO non cancella riscontri attivi già noti. */
  function barrieraPagamento(idPrenotazione, attivo = true) {
    orologio += 1
    aggiornaAmbitoPagamento(idPrenotazione, { attivo: attivo || pagamentoAttivoNoto(idPrenotazione), barriera: orologio })
  }

  // Idoneità reattiva dei dati mostrati: anche FALLITO richiede una nuova lettura prima di un nuovo tentativo.
  function storicoValido(idPrenotazione, ticket) {
    const ambito = ambitoPagamento(idPrenotazione)
    return ticketValido(ticket) && ticket.n > (ambito?.barriera ?? 0) && ticket.n >= (ambito?.applicato ?? 0)
  }

  // Rimborsi: un ambito per pagamento; `esiste` = nota una richiesta di rimborso. L'elenco del
  // paziente copre tutti i pagamenti, anche quelli ancora senza ambito: il suo ultimo ticket
  // applicato vale come limite per ogni ambito.
  const ambitoRimborso = (idPagamento) => state.ambitiRimborso.find((x) => x.idPagamento === idPagamento) ?? null
  function aggiornaAmbitoRimborso(idPagamento, campi) {
    const prima = ambitoRimborso(idPagamento) ?? { idPagamento, idPrenotazione: null, esiste: false, applicato: 0, barriera: 0 }
    state.ambitiRimborso = [...state.ambitiRimborso.filter((x) => x.idPagamento !== idPagamento), { ...prima, ...campi }]
  }

  /**
   * Applica un elenco rimborsi letto dal server.
   * @param {object|null} copertura null = elenco globale del paziente (tutti i pagamenti);
   *   { idPrenotazione, idPagamenti } = riconciliazione limitata a una prenotazione: gli altri
   *   ambiti non vengono toccati.
   * @returns {Set<number>} idPagamento degli ambiti a cui la risposta è stata applicata
   */
  function registraRimborsi(elenco, ticket, copertura = null) {
    const applicati = new Set()
    if (!ticketValido(ticket)) return applicati
    const globale = copertura === null
    if (globale && ticket.n <= state.rimborsiApplicatoGlobale) return applicati
    const presenti = new Map(elenco
      .filter((r) => globale || r.idPrenotazione === copertura.idPrenotazione)
      .map((r) => [r.idPagamento, r.idPrenotazione]))
    const coperti = new Set([
      ...presenti.keys(),
      ...state.ambitiRimborso.filter((x) => globale || x.idPrenotazione === copertura.idPrenotazione).map((x) => x.idPagamento),
      ...(globale ? [] : copertura.idPagamenti),
    ])
    const minimo = globale ? 0 : state.rimborsiApplicatoGlobale
    for (const idPagamento of coperti) {
      const ambito = ambitoRimborso(idPagamento)
      if (!piuRecente(ticket, ambito, minimo)) continue
      aggiornaAmbitoRimborso(idPagamento, {
        esiste: presenti.has(idPagamento),
        idPrenotazione: presenti.get(idPagamento) ?? ambito?.idPrenotazione ?? copertura?.idPrenotazione ?? null,
        applicato: ticket.n,
      })
      applicati.add(idPagamento)
    }
    if (globale) state.rimborsiApplicatoGlobale = ticket.n
    return applicati
  }

  function rimborsiValidi(idPagamento, ticket) {
    const ambito = ambitoRimborso(idPagamento)
    return ticketValido(ticket) && ticket.n >= state.rimborsiApplicatoGlobale
      && ticket.n >= (ambito?.applicato ?? 0) && ticket.n > (ambito?.barriera ?? 0)
  }

  /** Esito certo 201 della richiesta di rimborso: barriera per le letture avviate prima. */
  function barrieraRimborso(idPrenotazione, idPagamento) {
    orologio += 1
    aggiornaAmbitoRimborso(idPagamento, { idPrenotazione, esiste: true, barriera: orologio })
  }

  const rimborsoNoto = (idPagamento) => ambitoRimborso(idPagamento)?.esiste === true

  // ------------------------------------------------------------------ pagamento online

  const pagamentoIncerto = (idPrenotazione) => state.pagamentiIncerti.find((x) => x.idPrenotazione === idPrenotazione) ?? null
  /**
   * Possibilità effettiva di tentare un pagamento, distinta dall'esito della verifica: è
   * bloccata da un tentativo incerto non ancora verificato e da un pagamento PAGATO o
   * IN_ATTESA noto (riscontro condiviso), finché una lettura più recente non lo esclude.
   */
  function pagamentoBloccato(idPrenotazione) {
    const x = pagamentoIncerto(idPrenotazione)
    return (x !== null && x.verifica !== 'verificata') || pagamentoAttivoNoto(idPrenotazione)
  }
  /** Motivo del blocco condiviso, per mostrare all'utente come recuperare. */
  function pagamentoAttivoNoto(idPrenotazione) {
    return ambitoPagamento(idPrenotazione)?.attivo === true
  }
  const aggiornaPagamentoIncerto = (id, campi) => {
    state.pagamentiIncerti = state.pagamentiIncerti.map((x) => (x.idPrenotazione === id ? { ...x, ...campi } : x))
  }

  /**
   * Pagamento online simulato con il prezzo storico della prenotazione come importo atteso.
   * @param {{idPrenotazione:number, prezzo:number}} prenotazione letta dal server
   * @param {Array<{idPagamento:number}>} storico storico appena letto, per riconoscere i nuovi tentativi
   */
  async function pagaOnline(prenotazione, storico, ticket) {
    const idPaziente = pazienteCorrente()
    if (idPaziente === null) return { kind: 'sessione-assente' }
    const id = prenotazione.idPrenotazione
    if (operazioneInCorso(id)) return { kind: 'occupato' }
    if (altraIncertezza(id, 'paga')) return { kind: 'bloccata' }
    // Rifiuto prudenziale della sola chiamata: uno storico fornito dal chiamante con un pagamento
    // attivo impedisce l'invio, ma è un dato locale senza provenienza temporale e non modifica
    // ambiti condivisi, ticket applicati, barriere od orologio.
    if (storico.some((x) => apiPagamenti.STATI_BLOCCANTI.includes(x.stato))) return { kind: 'bloccata' }
    if (pagamentoBloccato(id)) return { kind: 'bloccata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    if (ticket !== undefined && !storicoValido(id, ticket)) return { kind: 'dati-superati' }
    const importoAtteso = prenotazione.prezzo
    const noti = Object.freeze(storico.map((x) => x.idPagamento))
    const mia = generazione
    state.pagamentiInCorso = [...state.pagamentiInCorso, id]
    try {
      const pagamento = apiPagamenti.parsePagamentoOnline(
        await apiPagamenti.postPagamentoOnline(session.protectedRequest, id, importoAtteso), id, importoAtteso,
      )
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.pagamentiIncerti = state.pagamentiIncerti.filter((x) => x.idPrenotazione !== id)
      // Ogni esito certo invalida gli storici precedenti prima di rimuovere il pending.
      barrieraPagamento(id, pagamento.stato === 'PAGATO')
      barrieraPrenotazione(id)
      return { kind: pagamento.stato === 'PAGATO' ? 'pagato' : 'fallito', pagamento }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = error instanceof TypeError ? { kind: 'non-valida' } : classifica(error, ERRORI_ECONOMICI)
      if (esito.kind === 'incerto') {
        state.pagamentiIncerti = [
          ...state.pagamentiIncerti.filter((x) => x.idPrenotazione !== id),
          { idPrenotazione: id, importoAtteso, noti, verifica: null, esito: null },
        ]
      }
      return esito
    } finally {
      if (mia === generazione) state.pagamentiInCorso = state.pagamentiInCorso.filter((x) => x !== id)
    }
  }

  /**
   * Verifica manuale di un pagamento incerto nello storico della prenotazione. Confronta lo
   * storico con i pagamenti noti prima del tentativo: un nuovo pagamento non è attribuito con
   * certezza al tentativo (potrebbe provenire da un'altra scheda), e l'assenza di nuovi
   * pagamenti non prova che il tentativo sia fallito.
   */
  async function verificaPagamento(idPrenotazione) {
    const idPaziente = pazienteCorrente()
    const voce = pagamentoIncerto(idPrenotazione)
    if (idPaziente === null || voce === null) return { kind: 'sessione-assente' }
    if (voce.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const precedente = voce.verifica
    aggiornaPagamentoIncerto(idPrenotazione, { verifica: 'in-corso' })
    const ticket = inizioLettura()
    let storico
    try {
      storico = await apiPagamenti.fetchPagamenti(session.protectedRequest, idPrenotazione)
    } catch {
      if (mia !== generazione) return { kind: 'obsoleto' }
      aggiornaPagamentoIncerto(idPrenotazione, { verifica: precedente === 'verificata' ? 'verificata' : 'errore' })
      return { kind: 'errore' }
    }
    if (mia !== generazione || state.owner !== idPaziente) return { kind: 'obsoleto' }
    if (!registraStorico(idPrenotazione, storico, ticket)) {
      // Risposta superata da una lettura o da un esito più recenti: non decide l'esito testuale.
      // Con un pagamento attivo noto si usa quel riscontro autorevole; altrimenti l'esito resta
      // neutro e serve una nuova verifica manuale (il blocco del tentativo incerto resta).
      if (pagamentoAttivoNoto(idPrenotazione)) {
        aggiornaPagamentoIncerto(idPrenotazione, { verifica: 'verificata', esito: 'attivo', nuovi: Object.freeze([]) })
      } else {
        aggiornaPagamentoIncerto(idPrenotazione, { verifica: 'superata', esito: null })
      }
      return { kind: 'superata' }
    }
    // Si valuta l'intero storico: un pagamento attivo, nuovo o precedente, impedisce l'invio,
    // senza provare che appartenga al tentativo incerto.
    const nuovi = storico.filter((x) => !voce.noti.includes(x.idPagamento))
    const attivo = storico.some((x) => apiPagamenti.STATI_BLOCCANTI.includes(x.stato))
    const esito = attivo ? 'attivo' : nuovi.length > 0 ? 'nuovo-fallito' : 'nessun-nuovo'
    aggiornaPagamentoIncerto(idPrenotazione, { verifica: 'verificata', esito, nuovi: Object.freeze(nuovi) })
    return { kind: 'verificata', esito, storico }
  }

  // ------------------------------------------------------------------ richiesta di rimborso

  const rimborsoIncerto = (idPrenotazione) => state.rimborsiIncerti.find((x) => x.idPrenotazione === idPrenotazione) ?? null
  /** Tentativo incerto non verificato, oppure richiesta già nota per un pagamento della prenotazione. */
  function rimborsoBloccato(idPrenotazione) {
    const x = rimborsoIncerto(idPrenotazione)
    return (x !== null && x.verifica !== 'assente') || state.ambitiRimborso.some((r) => r.idPrenotazione === idPrenotazione && r.esiste)
  }
  const aggiornaRimborsoIncerto = (id, campi) => {
    state.rimborsiIncerti = state.rimborsiIncerti.map((x) => (x.idPrenotazione === id ? { ...x, ...campi } : x))
  }

  /**
   * Richiesta di rimborso per il pagamento individuato come idoneo dai dati letti.
   * @param {number} idPrenotazione
   * @param {{idPagamento:number, importo:number}} pagamento
   * @param {string} motivazione testo inviato così com'è
   */
  async function richiediRimborso(idPrenotazione, pagamento, motivazione) {
    const idPaziente = pazienteCorrente()
    if (idPaziente === null) return { kind: 'sessione-assente' }
    if (operazioneInCorso(idPrenotazione)) return { kind: 'occupato' }
    if (altraIncertezza(idPrenotazione, 'rimborso')) return { kind: 'bloccata' }
    if (rimborsoBloccato(idPrenotazione) || rimborsoNoto(pagamento.idPagamento)) return { kind: 'bloccata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    state.rimborsiInCorso = [...state.rimborsiInCorso, idPrenotazione]
    try {
      const rimborso = apiRimborsi.parseRimborsoCreato(
        await apiRimborsi.postRimborso(session.protectedRequest, idPrenotazione, motivazione),
        { idPrenotazione, idPagamento: pagamento.idPagamento, importo: pagamento.importo, motivazione },
      )
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.rimborsiIncerti = state.rimborsiIncerti.filter((x) => x.idPrenotazione !== idPrenotazione)
      // Richiesta registrata: riscontro condiviso immediato, nessun duplicato prima della rilettura.
      barrieraRimborso(idPrenotazione, rimborso.idPagamento)
      return { kind: 'richiesto', rimborso }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = error instanceof TypeError ? { kind: 'non-valida' } : classifica(error, ERRORI_ECONOMICI)
      if (esito.kind === 'incerto') {
        state.rimborsiIncerti = [
          ...state.rimborsiIncerti.filter((x) => x.idPrenotazione !== idPrenotazione),
          { idPrenotazione, idPagamento: pagamento.idPagamento, importo: pagamento.importo, verifica: null },
        ]
      }
      return esito
    } finally {
      if (mia === generazione) state.rimborsiInCorso = state.rimborsiInCorso.filter((x) => x !== idPrenotazione)
    }
  }

  /**
   * Verifica manuale nell'elenco rimborsi: cerca una richiesta per lo stesso pagamento (il
   * backend ne ammette una sola). Se manca, il risultato non prova il fallimento: un nuovo
   * invio è possibile solo su scelta esplicita; un eventuale duplicato verrebbe rifiutato.
   */
  async function verificaRimborso(idPrenotazione) {
    const idPaziente = pazienteCorrente()
    const voce = rimborsoIncerto(idPrenotazione)
    if (idPaziente === null || voce === null) return { kind: 'sessione-assente' }
    if (voce.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const precedente = voce.verifica
    aggiornaRimborsoIncerto(idPrenotazione, { verifica: 'in-corso' })
    const ticket = inizioLettura()
    let elenco
    try {
      elenco = await apiRimborsi.fetchRimborsi(session.protectedRequest)
    } catch {
      if (mia !== generazione) return { kind: 'obsoleto' }
      aggiornaRimborsoIncerto(idPrenotazione, { verifica: precedente === 'assente' ? 'assente' : 'errore' })
      return { kind: 'errore' }
    }
    if (mia !== generazione || state.owner !== idPaziente) return { kind: 'obsoleto' }
    // Riconciliazione per una prenotazione: aggiorna solo gli ambiti di quella prenotazione.
    const applicati = registraRimborsi(elenco, ticket, { idPrenotazione, idPagamenti: [voce.idPagamento] })
    if (!applicati.has(voce.idPagamento)) {
      // Risposta superata: l'esito segue il riscontro autorevole corrente, se positivo; altrimenti
      // resta neutro e serve una nuova verifica manuale, con il blocco del tentativo incerto.
      if (rimborsoNoto(voce.idPagamento)) {
        state.rimborsiIncerti = state.rimborsiIncerti.filter((x) => x.idPrenotazione !== idPrenotazione)
        return { kind: 'trovato', rimborso: null, copertura: null }
      }
      aggiornaRimborsoIncerto(idPrenotazione, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    // Copertura esplicita: la risposta vale soltanto per gli ambiti applicati di questa prenotazione;
    // le altre righe dell'elenco non sono state validate e non vanno adottate.
    const copertura = Object.freeze({
      idPrenotazione,
      idPagamenti: Object.freeze([...applicati]),
      ticket: ticket.n,
      voci: Object.freeze(elenco.filter((r) => r.idPrenotazione === idPrenotazione && applicati.has(r.idPagamento))),
    })
    const trovato = copertura.voci.find((r) => r.idPagamento === voce.idPagamento)
    if (trovato) {
      state.rimborsiIncerti = state.rimborsiIncerti.filter((x) => x.idPrenotazione !== idPrenotazione)
      return { kind: 'trovato', rimborso: trovato, copertura }
    }
    aggiornaRimborsoIncerto(idPrenotazione, { verifica: 'assente' })
    return { kind: 'assente', copertura }
  }

  return {
    state: readonly(state),
    inizioLettura,
    registraStorico,
    storicoValido,
    operazioneInCorso,
    altraIncertezza,
    prenotazioneValida,
    rimborsiValidi,
    registraRimborsi,
    pagamentoAttivoNoto,
    rimborsoNoto,
    pagamentoIncerto,
    pagamentoBloccato,
    pagaOnline,
    verificaPagamento,
    rimborsoIncerto,
    rimborsoBloccato,
    richiediRimborso,
    verificaRimborso,
    richiestaIncerta,
    richiestaBloccata,
    annullamentoIncerto,
    annullamentoBloccato,
    richiedi,
    verificaRichiesta,
    annulla,
    annullamentoVerificato,
  }
}

const stores = new WeakMap()

/** Store unico per ogni sessione, creato alla prima richiesta. */
export function prenotazioniStore(session) {
  let store = stores.get(session)
  if (!store) {
    store = createStore(session)
    stores.set(session, store)
  }
  return store
}

export function usePrenotazioni() {
  return prenotazioniStore(useSession())
}
