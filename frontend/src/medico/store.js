import { effectScope, reactive, readonly, watch } from 'vue'
import { StaleResponseError } from '../api/http.js'
import * as api from '../api/medico.js'
import { classifica } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Scritture del medico  e loro esiti incerti: registrazione della visita, segnalazione di
 * indisponibilità, caricamento del referto.
 *
 * Separato dallo store del paziente: un account con entrambi i profili non mescola stati né
 * blocchi. Vive solo in memoria, accanto alla sessione (nessuno storage persistente): sopravvive
 * alla navigazione nella SPA e alla rotazione della sessione dello stesso utente, viene svuotato
 * quando si autentica un utente diverso o quando un logout si conclude. Gli esiti appartengono
 * alla generazione in cui è partita la richiesta: dopo uno svuotamento i risultati tardivi sono
 * scartati. Nessun file o contenuto PDF viene conservato: lo store riceve il file solo come
 * argomento della singola chiamata.
 */

const ERRORI_VISITA = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }
const ERRORI_SEGNALAZIONE = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }
const ERRORI_REFERTO = {
  400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto', 413: 'troppo-grande', 415: 'tipo-non-supportato',
}

/** Competenza a segnalare: RICHIESTA del titolare, CONFERMATA del medico effettivo corrente. */
export function segnalabile(a, medicoId) {
  if (a.indisponibilitaSegnalata) return false
  if (a.stato === 'RICHIESTA') return a.idMedicoTitolare === medicoId
  return a.stato === 'CONFERMATA' && a.idMedicoEffettivo === medicoId
}

/** Condizioni verificabili dal medico per registrare la visita; il resto lo verifica il backend. */
export function registrabile(a, medicoId) {
  return a.stato === 'CONFERMATA' && a.idMedicoEffettivo === medicoId && !a.indisponibilitaSegnalata
}

/** Referto caricabile solo per una prenotazione EFFETTUATA di cui si è il medico effettivo. */
export function refertabile(a, medicoId) {
  return a.stato === 'EFFETTUATA' && a.idMedicoEffettivo === medicoId
}

/** Dati dell'appuntamento mostrati nei riepiloghi e nelle verifiche (solo in memoria). */
function riepilogoDi(a) {
  return Object.freeze({
    idPrenotazione: a.idPrenotazione,
    stato: a.stato,
    inizio: a.inizio,
    fine: a.fine,
    paziente: a.paziente,
    nomePrestazione: a.nomePrestazione,
    stanza: a.stanza,
  })
}

function createStore(session) {
  const state = reactive({
    owner: null,
    /** idPrenotazione delle registrazioni in volo. */
    visiteInCorso: [],
    /**
     * Registrazioni dall'esito incerto: { idPrenotazione, riepilogo, verifica }.
     * verifica: null | 'in-corso' | 'errore' | 'assente' | 'superata'
     */
    visiteIncerte: [],
    /** Registrazioni certe in questa sessione: la transizione non torna indietro. */
    visiteRegistrate: [],
    /** idPrenotazione della segnalazione in volo. */
    segnalazioneInCorso: [],
    /**
     * Segnalazioni dall'esito incerto: { chiave, riepiloghi, pendenti, verifica, esiti }.
     * `pendenti`: ID ancora bloccati (all'inizio tutti; dopo una verifica quelli non trovati).
     * verifica: null | 'in-corso' | 'errore' | 'verificata'; esiti: { [id]: esito } dopo la verifica.
     */
    segnalazioniIncerte: [],
    /**
     * Riscontri per prenotazione, ordinati (vedi «letture autorevoli»): { idPrenotazione, data,
     * presente, voce, segnalata, applicato, barriera }. `data` è il giorno dell'appuntamento;
     * `presente` e `voce` vengono dall'ultima lettura applicata che copriva quel giorno;
     * `segnalata` è l'ultimo flag noto da una lettura o da una segnalazione certa.
     */
    ambiti: [],
    /** idPrenotazione dei caricamenti in volo. */
    refertiInCorso: [],
    /** Caricamenti dall'esito incerto: { idPrenotazione, riepilogo }. Nessuna lettura medico li può verificare. */
    refertiIncerti: [],
    /** Caricamenti certi in questa sessione: { idPrenotazione, referto }. */
    refertiCaricati: [],
  })
  let generazione = 0
  let logoutAvviato = false
  // Ordine di avvio delle letture e degli esiti certi di scrittura (mai dell'applicazione).
  let orologio = 0
  let chiavi = 0

  function svuota(owner) {
    generazione += 1
    state.owner = owner
    state.visiteInCorso = []
    state.visiteIncerte = []
    state.visiteRegistrate = []
    state.segnalazioneInCorso = []
    state.segnalazioniIncerte = []
    state.ambiti = []
    state.refertiInCorso = []
    state.refertiIncerti = []
    state.refertiCaricati = []
  }

  /** Stesse regole dello store del paziente: proprietario = ultimo utente autenticato. */
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

  /** Identità corrente del medico, solo dalla sessione; null se non utilizzabile. */
  function medicoCorrente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value.medico) return null
    const id = session.state.user?.idUtente
    return id === state.owner ? id : null
  }

  // ---------------------------------------------------------------- letture autorevoli

  /**
   * Ordinamento delle letture dell'agenda per prenotazione, come per i riscontri * - `orologio` avanza solo all'avvio di una lettura e a un esito certo di scrittura;
   * - `applicato`: ticket dell'ultima lettura applicata all'ambito, anche se negativa (assenza);
   * - `barriera`: istante dell'ultimo esito certo di scrittura sull'ambito.
   * Una risposta si applica solo se la sua lettura è iniziata dopo entrambi. Una lettura copre
   * soltanto il periodo richiesto: l'assenza vale solo per prenotazioni di quei giorni. I ticket
   * valgono per l'identità (generazione) e l'epoca di sessione in cui sono nati.
   */
  function inizioLettura() {
    orologio += 1
    return Object.freeze({ n: orologio, generazione, epoca: session.state.epoch })
  }
  const ticketValido = (t) => t?.generazione === generazione && t.epoca === session.state.epoch
  const ambito = (id) => state.ambiti.find((x) => x.idPrenotazione === id) ?? null
  const piuRecente = (t, x) => t.n > Math.max(x?.applicato ?? 0, x?.barriera ?? 0)
  function aggiornaAmbito(id, campi) {
    const prima = ambito(id) ?? { idPrenotazione: id, data: null, presente: null, voce: null, segnalata: null, applicato: 0, barriera: 0 }
    state.ambiti = [...state.ambiti.filter((x) => x.idPrenotazione !== id), { ...prima, ...campi }]
  }
  /** Ambito di una prenotazione oggetto di una scrittura: serve il giorno per valutare le assenze. */
  function assicuraAmbito(a) {
    if (!ambito(a.idPrenotazione)?.data) aggiornaAmbito(a.idPrenotazione, { data: a.inizio.date })
  }

  /**
   * Applica una lettura dell'agenda. `periodo` { da, a } è il periodo richiesto; senza periodo
   * (giornata corrente del server) è il giorno delle voci lette, se ce ne sono.
   * @returns {Set<number>} prenotazioni a cui la lettura è stata applicata
   */
  function applicaAgenda(voci, ticket, periodo = null) {
    const applicati = new Set()
    if (!ticketValido(ticket)) return applicati
    const copertura = periodo ?? (voci.length > 0 ? { da: voci[0].inizio.date, a: voci[0].inizio.date } : null)
    const presenti = new Set()
    for (const v of voci) {
      presenti.add(v.idPrenotazione)
      if (!piuRecente(ticket, ambito(v.idPrenotazione))) continue
      aggiornaAmbito(v.idPrenotazione, { data: v.inizio.date, presente: true, voce: v, segnalata: v.indisponibilitaSegnalata, applicato: ticket.n })
      applicati.add(v.idPrenotazione)
    }
    if (copertura) {
      for (const x of state.ambiti) {
        if (presenti.has(x.idPrenotazione) || !x.data || x.data < copertura.da || x.data > copertura.a || !piuRecente(ticket, x)) continue
        // Assenza nel giorno dell'appuntamento: riscontro negativo, il flag noto non cambia.
        aggiornaAmbito(x.idPrenotazione, { presente: false, voce: null, applicato: ticket.n })
        applicati.add(x.idPrenotazione)
      }
    }
    return applicati
  }

  /**
   * Letture per una verifica, ciascuna entro 31 giorni, applicate con il proprio ticket. L'esito
   * si costruisce poi dallo stato corrente degli ambiti, confrontato con questi ticket.
   * @returns {Promise<{ticketDel: (data:string) => number}>}
   */
  async function leggiPeriodo(medicoId, date) {
    const ordinate = [...new Set(date)].sort()
    const tickets = []
    let i = 0
    while (i < ordinate.length) {
      const da = ordinate[i]
      let j = i
      while (j + 1 < ordinate.length && api.giorniTra(da, ordinate[j + 1]) < api.MAX_GIORNI_AGENDA) j += 1
      const a = ordinate[j]
      const filtri = da === a ? { medicoId, data: da } : { medicoId, dataDa: da, dataA: a }
      const ticket = inizioLettura()
      applicaAgenda(await api.fetchAgenda(session.protectedRequest, filtri), ticket, { da, a })
      tickets.push({ da, a, n: ticket.n })
      i = j + 1
    }
    return { ticketDel: (d) => tickets.find((t) => d >= t.da && d <= t.a)?.n ?? Infinity }
  }

  /**
   * Riscontro per una verifica, valutato sullo stato corrente dell'ambito al momento di
   * costruire l'esito (non al momento in cui la propria lettura è stata applicata: nel frattempo
   * una lettura successiva può averlo aggiornato). Vale l'ultima lettura applicata, propria o più
   * recente, purché sia almeno quella della verifica e nessun esito certo di scrittura sia
   * successivo; altrimenti null: risposta superata con riscontro insufficiente.
   * @returns {{presente:boolean, voce:object|null}|null}
   */
  function riscontro(id, letti, data) {
    const x = ambito(id)
    if (!x || x.presente === null || x.applicato < letti.ticketDel(data) || x.barriera > x.applicato) return null
    return { presente: x.presente, voce: x.voce }
  }

  // ---------------------------------------------------------------- registrazione della visita

  const visitaIncerta = (id) => state.visiteIncerte.find((x) => x.idPrenotazione === id) ?? null
  const visitaRegistrata = (id) => state.visiteRegistrate.includes(id)
  const visitaBloccata = (id) => visitaIncerta(id) !== null || visitaRegistrata(id)
  /** Una registrazione in volo, incerta o certa impedisce la segnalazione della stessa prenotazione. */
  const visitaImpedisceSegnalazione = (id) => state.visiteInCorso.includes(id) || visitaBloccata(id)
  const aggiornaVisitaIncerta = (id, campi) => {
    state.visiteIncerte = state.visiteIncerte.map((x) => (x.idPrenotazione === id ? { ...x, ...campi } : x))
  }

  /** Registra la visita dell'appuntamento appena letto dall'agenda. Corpo `{}`. */
  async function registraVisita(appuntamento) {
    const medicoId = medicoCorrente()
    if (medicoId === null) return { kind: 'sessione-assente' }
    const id = appuntamento.idPrenotazione
    if (state.visiteInCorso.includes(id)) return { kind: 'occupato' }
    if (visitaBloccata(id) || segnalazioneBloccata(id)) return { kind: 'bloccata' }
    if (!registrabile(appuntamento, medicoId) || segnalazioneNota(id)) return { kind: 'non-registrabile' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    const riepilogo = riepilogoDi(appuntamento)
    assicuraAmbito(appuntamento)
    state.visiteInCorso = [...state.visiteInCorso, id]
    try {
      const esito = api.parseVisitaRegistrata(await api.postRegistraVisita(session.protectedRequest, id), id)
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.visiteIncerte = state.visiteIncerte.filter((x) => x.idPrenotazione !== id)
      state.visiteRegistrate = [...state.visiteRegistrate, id]
      // Esito certo: barriera per le letture avviate prima.
      orologio += 1
      aggiornaAmbito(id, { barriera: orologio, presente: true, voce: Object.freeze({ ...appuntamento, stato: 'EFFETTUATA' }) })
      return { kind: 'registrata', esito }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_VISITA)
      if (esito.kind === 'incerto') {
        state.visiteIncerte = [...state.visiteIncerte.filter((x) => x.idPrenotazione !== id), { idPrenotazione: id, riepilogo, verifica: null }]
      }
      return esito
    } finally {
      if (mia === generazione) state.visiteInCorso = state.visiteInCorso.filter((x) => x !== id)
    }
  }

  /**
   * Verifica manuale nell'agenda della giornata dell'appuntamento. Lo stato corrente non
   * dimostra chi abbia eseguito la transizione. Se l'appuntamento non compare più, l'esito non
   * viene dedotto e il blocco resta. Una risposta superata da una lettura più recente usa il
   * riscontro corrente; se non basta, l'esito è neutro ('superata') e il blocco resta.
   */
  async function verificaVisita(id) {
    const medicoId = medicoCorrente()
    const voce = visitaIncerta(id)
    if (medicoId === null || voce === null) return { kind: 'sessione-assente' }
    if (voce.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const precedente = voce.verifica
    const epoca = session.state.epoch
    const data = voce.riepilogo.inizio.date
    aggiornaVisitaIncerta(id, { verifica: 'in-corso' })
    let letti
    try {
      letti = await leggiPeriodo(medicoId, [data])
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      if (error instanceof StaleResponseError) {
        aggiornaVisitaIncerta(id, { verifica: precedente })
        return { kind: 'obsoleto' }
      }
      aggiornaVisitaIncerta(id, { verifica: ['assente', 'superata'].includes(precedente) ? precedente : 'errore' })
      return { kind: 'errore' }
    }
    if (mia !== generazione || state.owner !== medicoId) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaVisitaIncerta(id, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    const r = riscontro(id, letti, data)
    if (r === null) {
      aggiornaVisitaIncerta(id, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    if (!r.presente) {
      aggiornaVisitaIncerta(id, { verifica: 'assente' })
      return { kind: 'assente' }
    }
    state.visiteIncerte = state.visiteIncerte.filter((x) => x.idPrenotazione !== id)
    if (r.voce.stato === 'EFFETTUATA' && !visitaRegistrata(id)) state.visiteRegistrate = [...state.visiteRegistrate, id]
    return { kind: 'trovata', appuntamento: r.voce }
  }

  // ---------------------------------------------------------------- segnalazione di indisponibilità

  /** Segnalazione nota dall'ultimo riscontro ordinato (lettura applicata o esito certo). */
  const segnalazioneNota = (id) => ambito(id)?.segnalata === true
  const segnalazioneIncertaDi = (id) => state.segnalazioniIncerte.find((x) => x.pendenti.includes(id)) ?? null
  const segnalazioneBloccata = (id) => state.segnalazioneInCorso.includes(id) || segnalazioneIncertaDi(id) !== null
  const aggiornaSegnalazioneIncerta = (chiave, campi) => {
    state.segnalazioniIncerte = state.segnalazioniIncerte.map((x) => (x.chiave === chiave ? { ...x, ...campi } : x))
  }

  /**
   * Segnala l'indisponibilità per gli appuntamenti scelti, con `medicoId` della sessione.
   * L'operazione è atomica: un rifiuto vale per tutto il gruppo.
   */
  async function segnala(appuntamenti) {
    const medicoId = medicoCorrente()
    if (medicoId === null) return { kind: 'sessione-assente' }
    const ids = appuntamenti.map((a) => a.idPrenotazione)
    if (ids.length === 0 || new Set(ids).size !== ids.length) return { kind: 'non-valida' }
    if (state.segnalazioneInCorso.length > 0) return { kind: 'occupato' }
    // Tutti gli ID prima dell'invio: uno solo bloccato impedisce l'intero gruppo.
    if (ids.some((id) => segnalazioneBloccata(id) || visitaImpedisceSegnalazione(id))) return { kind: 'bloccata' }
    if (appuntamenti.some((a) => !segnalabile(a, medicoId) || segnalazioneNota(a.idPrenotazione))) return { kind: 'non-segnalabile' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    const riepiloghi = Object.freeze(appuntamenti.map(riepilogoDi))
    appuntamenti.forEach(assicuraAmbito)
    state.segnalazioneInCorso = ids
    try {
      const esito = api.parseSegnalazione(await api.postSegnalazione(session.protectedRequest, medicoId, ids), ids)
      if (mia !== generazione) return { kind: 'obsoleto' }
      // Esito certo: barriera per le letture avviate prima; il flag resta noto fino a una lettura successiva.
      orologio += 1
      for (const id of ids) aggiornaAmbito(id, { barriera: orologio, segnalata: true })
      return { kind: 'segnalate', esito }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_SEGNALAZIONE)
      if (esito.kind === 'incerto') {
        chiavi += 1
        state.segnalazioniIncerte = [...state.segnalazioniIncerte, { chiave: chiavi, riepiloghi, pendenti: ids, verifica: null, esiti: null }]
      }
      return esito
    } finally {
      if (mia === generazione) state.segnalazioneInCorso = []
    }
  }

  /**
   * Verifica manuale nell'agenda dei giorni degli appuntamenti interessati. Per ciascuno indica
   * lo stato corrente senza attribuirlo con certezza al tentativo: 'segnalata', 'non-segnalata'
   * (flag assente: non prova che l'invio sia fallito, la segreteria potrebbe averla già gestita),
   * 'altro-stato', 'assente' oppure 'superata'. Gli appuntamenti assenti o con risposta superata
   * senza riscontro corrente sufficiente restano bloccati: il loro esito non viene dedotto.
   */
  async function verificaSegnalazione(chiave) {
    const medicoId = medicoCorrente()
    const voce = state.segnalazioniIncerte.find((x) => x.chiave === chiave) ?? null
    if (medicoId === null || voce === null) return { kind: 'sessione-assente' }
    if (voce.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const precedente = voce.verifica
    const epoca = session.state.epoch
    const dataDi = (id) => voce.riepiloghi.find((r) => r.idPrenotazione === id).inizio.date
    aggiornaSegnalazioneIncerta(chiave, { verifica: 'in-corso' })
    let letti
    try {
      letti = await leggiPeriodo(medicoId, voce.pendenti.map(dataDi))
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaSegnalazioneIncerta(chiave, { verifica: stale || precedente === 'verificata' ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== generazione || state.owner !== medicoId) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaSegnalazioneIncerta(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    const esiti = { ...(voce.esiti ?? {}) }
    for (const id of voce.pendenti) {
      const r = riscontro(id, letti, dataDi(id))
      const a = r?.voce
      if (r === null) esiti[id] = { kind: 'superata' }
      else if (!r.presente) esiti[id] = { kind: 'assente' }
      else if (a.indisponibilitaSegnalata) esiti[id] = { kind: 'segnalata', stato: a.stato }
      else if (segnalabile(a, medicoId)) esiti[id] = { kind: 'non-segnalata', stato: a.stato }
      else esiti[id] = { kind: 'altro-stato', stato: a.stato }
    }
    const pendenti = voce.pendenti.filter((id) => ['assente', 'superata'].includes(esiti[id].kind))
    aggiornaSegnalazioneIncerta(chiave, { verifica: 'verificata', esiti, pendenti })
    return { kind: 'verificata', esiti, pendenti }
  }

  /** Elimina dall'elenco una segnalazione incerta già interamente verificata. */
  function chiudiSegnalazioneIncerta(chiave, owner) {
    if (owner !== state.owner) return
    state.segnalazioniIncerte = state.segnalazioniIncerte.filter((x) => !(x.chiave === chiave && x.pendenti.length === 0))
  }

  // ---------------------------------------------------------------- referto

  const refertoIncerto = (id) => state.refertiIncerti.find((x) => x.idPrenotazione === id) ?? null
  const refertoCaricato = (id) => state.refertiCaricati.find((x) => x.idPrenotazione === id)?.referto ?? null
  const refertoBloccato = (id) => state.refertiInCorso.includes(id) || refertoIncerto(id) !== null || refertoCaricato(id) !== null

  /**
   * Carica il PDF come corpo binario. Il file è usato solo per questa richiesta e non viene
   * conservato. Un esito incerto non è verificabile con le API del medico: il blocco resta fino
   * al logout o al cambio di identità e non esiste un nuovo invio automatico.
   */
  async function caricaReferto(appuntamento, file) {
    const medicoId = medicoCorrente()
    if (medicoId === null) return { kind: 'sessione-assente' }
    const id = appuntamento.idPrenotazione
    if (state.refertiInCorso.includes(id)) return { kind: 'occupato' }
    if (appuntamento.refertoPresente === true || refertoBloccato(id)) return { kind: 'bloccata' }
    if (!refertabile(appuntamento, medicoId)) return { kind: 'non-caricabile' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    const riepilogo = riepilogoDi(appuntamento)
    let richiesta
    try {
      richiesta = api.postReferto(session.protectedRequest, id, file)
    } catch {
      return { kind: 'file-non-valido' }
    }
    state.refertiInCorso = [...state.refertiInCorso, id]
    try {
      const referto = api.parseRefertoCaricato(await richiesta, id)
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.refertiCaricati = [...state.refertiCaricati, { idPrenotazione: id, referto }]
      return { kind: 'caricato', referto }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_REFERTO)
      if (esito.kind === 'incerto') state.refertiIncerti = [...state.refertiIncerti, { idPrenotazione: id, riepilogo }]
      return esito
    } finally {
      if (mia === generazione) state.refertiInCorso = state.refertiInCorso.filter((x) => x !== id)
    }
  }

  return {
    state: readonly(state),
    medicoCorrente,
    inizioLettura,
    applicaAgenda,
    visitaIncerta,
    visitaRegistrata,
    visitaBloccata,
    visitaImpedisceSegnalazione,
    registraVisita,
    verificaVisita,
    segnalazioneNota,
    segnalazioneIncertaDi,
    segnalazioneBloccata,
    segnala,
    verificaSegnalazione,
    chiudiSegnalazioneIncerta,
    refertoIncerto,
    refertoCaricato,
    refertoBloccato,
    caricaReferto,
  }
}

const stores = new WeakMap()

/** Store unico per ogni sessione, distinto da quello del paziente. */
export function medicoStore(session) {
  let store = stores.get(session)
  if (!store) {
    store = createStore(session)
    stores.set(session, store)
  }
  return store
}

export function useMedico() {
  return medicoStore(useSession())
}
