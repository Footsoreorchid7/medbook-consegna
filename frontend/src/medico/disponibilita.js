import { effectScope, reactive, readonly, watch } from 'vue'
import { StaleResponseError } from '../api/http.js'
import * as api from '../api/disponibilitaMedico.js'
import { classifica } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Scritture del medico sulle proprie disponibilità  e loro esiti incerti: creazione,
 * modifica dell'orario, eliminazione.
 *
 * Solo in memoria, accanto alla sessione e separato dagli store prenotazioni e agenda: sopravvive alla
 * navigazione nella SPA e alla rotazione della sessione dello stesso utente, si svuota quando
 * si autentica un utente diverso o quando un logout si conclude. Gli esiti appartengono alla
 * generazione in cui è partita la richiesta. Nessun nuovo invio automatico.
 */

const ERRORI_CREAZIONE = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }
const ERRORI_SLOT = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }

const sovrapposti = (a, b) => api.secondi(a.inizio) < api.secondi(b.fine) && api.secondi(b.inizio) < api.secondi(a.fine)

function createStore(session) {
  const state = reactive({
    owner: null,
    /** Creazioni in volo: { inizio, fine }. */
    creazioniInCorso: [],
    /** idDisponibilita delle modifiche o eliminazioni in volo. */
    slotInCorso: [],
    /**
     * Creazioni dall'esito incerto: { chiave, inizio, fine, slot, noti, verifica, esito }.
     * `noti`: ID già nell'elenco prima del tentativo. verifica: null | 'in-corso' | 'errore' |
     * 'superata' | 'verificata'; esito (dopo la verifica): 'completa' | 'parziale' | 'assente'.
     */
    creazioniIncerte: [],
    /**
     * Modifiche ed eliminazioni dall'esito incerto: { tipo: 'orario'|'elimina', idDisponibilita,
     * originale: {inizio, fine}, nuovo: {inizio, fine}|null, verifica, esito }.
     */
    slotIncerti: [],
    /** Ultimo elenco personale applicato (riscontro corrente per le verifiche). */
    elenco: null,
  })
  let generazione = 0
  let logoutAvviato = false
  // Ordinamento delle letture: avanza solo all'avvio di una lettura e a un esito certo di scrittura.
  let orologio = 0
  let applicato = 0
  let barriera = 0
  let chiavi = 0

  function svuota(owner) {
    generazione += 1
    state.owner = owner
    state.creazioniInCorso = []
    state.slotInCorso = []
    state.creazioniIncerte = []
    state.slotIncerti = []
    state.elenco = null
    applicato = 0
    barriera = 0
  }

  /** Stesse regole degli store prenotazioni e agenda: proprietario = ultimo utente autenticato. */
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

  function medicoCorrente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value.medico) return null
    const id = session.state.user?.idUtente
    return id === state.owner ? id : null
  }

  // ---------------------------------------------------------------- letture autorevoli

  /**
   * L'elenco personale copre tutte le disponibilità del medico. Una risposta si applica solo se
   * la sua lettura è iniziata dopo l'ultima lettura applicata e dopo l'ultimo esito certo di
   * scrittura; i ticket valgono per la generazione e l'epoca in cui sono nati.
   */
  function inizioLettura() {
    orologio += 1
    return Object.freeze({ n: orologio, generazione, epoca: session.state.epoch })
  }
  const ticketValido = (t) => t?.generazione === generazione && t.epoca === session.state.epoch

  /** @returns {boolean} true se la lettura è stata applicata */
  function applicaElenco(voci, ticket) {
    if (!ticketValido(ticket) || ticket.n <= Math.max(applicato, barriera)) return false
    applicato = ticket.n
    state.elenco = voci
    return true
  }
  function esitoCerto() {
    orologio += 1
    barriera = orologio
  }
  /** Riscontro corrente per una verifica avviata con `ticket`: l'elenco se almeno altrettanto recente e non superato da una scrittura certa. */
  const riscontro = (ticket) => (state.elenco !== null && applicato >= ticket.n && barriera < applicato ? state.elenco : null)

  // ---------------------------------------------------------------- blocchi

  const creazioneIncertaSovrapposta = (intervallo) => state.creazioniIncerte.find((x) => x.esito !== 'assente' && sovrapposti(x, intervallo)) ?? null
  /** Una creazione in volo o incerta impedisce nuove creazioni sovrapposte; intervalli indipendenti restano liberi. */
  const creazioneBloccata = (intervallo) => state.creazioniInCorso.some((x) => sovrapposti(x, intervallo)) || creazioneIncertaSovrapposta(intervallo) !== null
  const slotIncerto = (id) => state.slotIncerti.find((x) => x.idDisponibilita === id) ?? null
  /** Una modifica o eliminazione in volo o incerta impedisce altre operazioni sullo stesso slot. */
  const slotBloccato = (id) => {
    const x = slotIncerto(id)
    return state.slotInCorso.includes(id) || (x !== null && x.esito !== 'assente')
  }
  const aggiornaCreazione = (chiave, campi) => {
    state.creazioniIncerte = state.creazioniIncerte.map((x) => (x.chiave === chiave ? { ...x, ...campi } : x))
  }
  const aggiornaSlot = (id, campi) => {
    state.slotIncerti = state.slotIncerti.map((x) => (x.idDisponibilita === id ? { ...x, ...campi } : x))
  }

  // ---------------------------------------------------------------- creazione

  /** Crea gli slot dell'intervallo con `idMedico` della sessione. */
  async function crea(inizioRaw, fineRaw) {
    const medicoId = medicoCorrente()
    if (medicoId === null) return { kind: 'sessione-assente' }
    const v = api.validaIntervallo(inizioRaw, fineRaw)
    if (v.errore) return { kind: 'non-valida' }
    const intervallo = { inizio: v.inizio, fine: v.fine }
    if (creazioneBloccata(intervallo)) return { kind: 'bloccata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const mia = generazione
    const noti = Object.freeze((state.elenco ?? []).map((s) => s.idDisponibilita))
    state.creazioniInCorso = [...state.creazioniInCorso, intervallo]
    try {
      const slot = api.parseCreazione(await api.postCreazione(session.protectedRequest, medicoId, v.inizio, v.fine), medicoId, v.inizio, v.fine)
      if (mia !== generazione) return { kind: 'obsoleto' }
      esitoCerto()
      return { kind: 'creata', slot }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_CREAZIONE)
      if (esito.kind === 'incerto') {
        chiavi += 1
        state.creazioniIncerte = [...state.creazioniIncerte, { chiave: chiavi, ...intervallo, slot: v.slot, noti, verifica: null, esito: null }]
      }
      return esito
    } finally {
      // Confronto per orari: lo stato reattivo restituisce proxy, non l'oggetto originale.
      if (mia === generazione) state.creazioniInCorso = state.creazioniInCorso.filter((x) => x.inizio !== intervallo.inizio || x.fine !== intervallo.fine)
    }
  }

  /**
   * Confronta l'intero intervallo con l'elenco corrente:
   * - 'completa': tutti gli slot attesi presenti con orari esatti e nessuno già noto prima del
   *   tentativo (non dimostra che li abbia creati proprio il tentativo);
   * - 'assente': nessuna disponibilità nell'intervallo (non prova che il tentativo sia fallito;
   *   un nuovo invio torna possibile su scelta esplicita, il server rifiuterebbe sovrapposizioni);
   * - 'parziale': ogni altro riscontro (slot preesistenti, sovrapposizioni, copertura incompleta),
   *   non conclusivo: il blocco resta.
   */
  function confrontaCreazione(x, elenco) {
    const attesi = api.slotAttesi(x.inizio, x.slot)
    const nell = elenco.filter((s) => sovrapposti(s, x))
    if (nell.length === 0) return 'assente'
    const esatti = attesi.every((a) => nell.some((s) => s.inizio === a.inizio && s.fine === a.fine && !x.noti.includes(s.idDisponibilita)))
    return esatti && nell.length === attesi.length ? 'completa' : 'parziale'
  }

  async function verificaCreazione(chiave) {
    const medicoId = medicoCorrente()
    const x = state.creazioniIncerte.find((c) => c.chiave === chiave) ?? null
    if (medicoId === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaCreazione(chiave, { verifica: 'in-corso' })
    const ticket = inizioLettura()
    let voci
    try {
      voci = await api.fetchDisponibilita(session.protectedRequest, medicoId)
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaCreazione(chiave, { verifica: stale || precedente === 'verificata' ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== generazione || state.owner !== medicoId) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaCreazione(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    applicaElenco(voci, ticket)
    const elenco = riscontro(ticket)
    if (elenco === null) {
      aggiornaCreazione(chiave, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    const esito = confrontaCreazione(x, elenco)
    if (esito === 'completa') {
      state.creazioniIncerte = state.creazioniIncerte.filter((c) => c.chiave !== chiave)
    } else {
      aggiornaCreazione(chiave, { verifica: 'verificata', esito })
    }
    return { kind: 'verificata', esito }
  }

  /** Elimina dall'elenco una creazione verificata senza riscontri ('assente'). */
  function chiudiCreazioneIncerta(chiave, owner) {
    if (owner !== state.owner) return
    state.creazioniIncerte = state.creazioniIncerte.filter((x) => !(x.chiave === chiave && x.esito === 'assente'))
  }

  // ---------------------------------------------------------------- modifica ed eliminazione

  /** Controlli comuni prima di fetch su uno slot appena letto. */
  function controllaSlot(slot, medicoId) {
    if (medicoId === null) return { kind: 'sessione-assente' }
    if (state.slotInCorso.includes(slot.idDisponibilita)) return { kind: 'occupato' }
    if (slotBloccato(slot.idDisponibilita)) return { kind: 'bloccata' }
    if (api.configurazionePresente(slot)) return { kind: 'configurata' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    return null
  }

  async function scritturaSlot(slot, tipo, nuovo, invia) {
    const id = slot.idDisponibilita
    const mia = generazione
    const originale = Object.freeze({ inizio: slot.inizio, fine: slot.fine })
    state.slotInCorso = [...state.slotInCorso, id]
    try {
      const risultato = await invia()
      if (mia !== generazione) return { kind: 'obsoleto' }
      state.slotIncerti = state.slotIncerti.filter((x) => x.idDisponibilita !== id)
      esitoCerto()
      return { kind: tipo === 'orario' ? 'modificata' : 'eliminata', slot: risultato }
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const esito = classifica(error, ERRORI_SLOT)
      if (esito.kind === 'incerto') {
        state.slotIncerti = [...state.slotIncerti.filter((x) => x.idDisponibilita !== id), { tipo, idDisponibilita: id, originale, nuovo, verifica: null, esito: null }]
      }
      return esito
    } finally {
      if (mia === generazione) state.slotInCorso = state.slotInCorso.filter((x) => x !== id)
    }
  }

  /** Nuovo orario di uno slot non configurato: esattamente 60 minuti dal nuovo inizio. */
  async function modificaOrario(slot, nuovoInizioRaw) {
    const medicoId = medicoCorrente()
    const rifiuto = controllaSlot(slot, medicoId)
    if (rifiuto) return rifiuto
    const inizio = api.normalizzaDataOra(nuovoInizioRaw)
    const fine = inizio && api.fineSlot(inizio)
    if (!fine) return { kind: 'non-valida' }
    if (inizio === slot.inizio) return { kind: 'invariata' }
    const nuovo = Object.freeze({ inizio, fine })
    return scritturaSlot(slot, 'orario', nuovo,
      async () => api.parseOrario(await api.putOrario(session.protectedRequest, slot.idDisponibilita, medicoId, inizio), slot.idDisponibilita, medicoId, inizio))
  }

  async function elimina(slot) {
    const medicoId = medicoCorrente()
    const rifiuto = controllaSlot(slot, medicoId)
    if (rifiuto) return rifiuto
    return scritturaSlot(slot, 'elimina', null,
      async () => api.parseEliminazione(await api.deleteDisponibilita(session.protectedRequest, slot.idDisponibilita, medicoId)))
  }

  /**
   * Verifica di una modifica o eliminazione incerta nell'elenco corrente. Presenza, nuovo
   * orario o assenza sono riscontri correnti, non prova di chi abbia compiuto l'operazione.
   * esito: 'nuovo-orario' | 'originale' | 'altro-orario' | 'assente'.
   */
  async function verificaSlot(id) {
    const medicoId = medicoCorrente()
    const x = slotIncerto(id)
    if (medicoId === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaSlot(id, { verifica: 'in-corso' })
    const ticket = inizioLettura()
    let voci
    try {
      voci = await api.fetchDisponibilita(session.protectedRequest, medicoId)
    } catch (error) {
      if (mia !== generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaSlot(id, { verifica: stale || precedente === 'verificata' ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== generazione || state.owner !== medicoId) return { kind: 'obsoleto' }
    if (session.state.epoch !== epoca) {
      aggiornaSlot(id, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    applicaElenco(voci, ticket)
    const elenco = riscontro(ticket)
    if (elenco === null) {
      aggiornaSlot(id, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    const s = elenco.find((v) => v.idDisponibilita === id)
    let esito
    if (!s) esito = 'assente'
    else if (x.nuovo && s.inizio === x.nuovo.inizio && s.fine === x.nuovo.fine) esito = 'nuovo-orario'
    else if (s.inizio === x.originale.inizio && s.fine === x.originale.fine) esito = 'originale'
    else esito = 'altro-orario'
    // Uno slot presente torna utilizzabile (nuova scelta esplicita); l'assenza resta segnalata
    // finché non viene rimossa dall'elenco degli esiti.
    if (esito === 'assente') aggiornaSlot(id, { verifica: 'verificata', esito })
    else state.slotIncerti = state.slotIncerti.filter((v) => v.idDisponibilita !== id)
    return { kind: 'verificata', esito, slot: s ?? null }
  }

  function chiudiSlotIncerto(id, owner) {
    if (owner !== state.owner) return
    state.slotIncerti = state.slotIncerti.filter((x) => !(x.idDisponibilita === id && x.esito === 'assente'))
  }

  return {
    state: readonly(state),
    medicoCorrente,
    inizioLettura,
    applicaElenco,
    creazioneBloccata,
    slotIncerto,
    slotBloccato,
    crea,
    verificaCreazione,
    chiudiCreazioneIncerta,
    modificaOrario,
    elimina,
    verificaSlot,
    chiudiSlotIncerto,
  }
}

const stores = new WeakMap()

/** Store unico per ogni sessione, distinto da quelli prenotazioni e agenda. */
export function disponibilitaStore(session) {
  let store = stores.get(session)
  if (!store) {
    store = createStore(session)
    stores.set(session, store)
  }
  return store
}

export function useDisponibilitaMedico() {
  return disponibilitaStore(useSession())
}
