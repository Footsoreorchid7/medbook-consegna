import { effectScope, reactive, readonly, watch } from 'vue'
import { HttpError, StaleResponseError } from '../api/http.js'
import * as api from '../api/utenti.js'
import { putPasswordPaziente, postCollegamentoPaziente } from '../api/pazientiDesk.js'
import * as apiMedici from '../api/medici.js'
import * as apiCatalogo from '../api/catalogoAdmin.js'
import { fetchMedicoDesk } from '../api/desk.js'
import { classifica } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Scritture sugli account (utenti/medici e reset/collegamento pazienti desk) e loro esiti incerti.
 *
 * Solo in memoria; i reset e collegamenti desk riusano le stesse chiavi utente e attese. Sopravvive alla navigazione nella SPA e alla
 * rotazione della sessione dello stesso utente, si svuota quando si autentica un utente diverso
 * o quando un logout si conclude. Nessuna persistenza nel browser e nessuna password: la
 * password di un nuovo account passa solo come argomento dell'invio.
 *
 * Chiavi delle risorse: 'utente:12' (ruolo e stato dello stesso utente sono incompatibili tra
 * loro) e 'email:indirizzo' (creazione). Una scrittura in volo o incerta blocca la stessa chiave
 * da qualsiasi vista. Nessun nuovo invio automatico.
 */

const ERRORI = { 400: 'non-valida', 403: 'rifiutata', 404: 'non-trovata', 409: 'conflitto' }

export const chiaveUtente = (id) => `utente:${id}`
/** Chiave di un'email nella forma di confronto: lo stesso indirizzo con maiuscole diverse è la stessa risorsa. */
export const chiaveEmail = (email) => `email:${api.emailConfronto(email)}`
/** Elenchi interessati da ogni esito certo: l'elenco gestionale, ogni ricerca, la singola risorsa. */
export const ELENCO = 'gestionali'
export const RICERCA = 'ricerca'
/** gestione medici: elenchi dei medici e dettaglio (riscontro) del singolo medico. */
export const MEDICI = 'medici'
export const chiaveMedico = (id) => `medico:${id}`
/** Tipi degli esiti incerti di gestione medici, verificati con le letture dei medici. */
export const TIPI_MEDICO = Object.freeze(['medico-crea', 'collega', 'personali', 'professionali', 'stato-medico', 'abilitazione', 'password-medico'])
/** gestione utenti: tipi degli esiti incerti della gestione utenti. */
export const TIPI_UTENTE = Object.freeze(['crea', 'ruolo', 'stato'])
/** cataloghi: cataloghi (elenco completo come unico riscontro) e tipi dei loro esiti incerti. */
export const STANZE = 'catalogo:stanze'
export const PRESTAZIONI = 'catalogo:prestazioni'
export const TIPI_CATALOGO = Object.freeze(['stanza-crea', 'stanza-modifica', 'prestazione-crea', 'prestazione-modifica'])
export const chiaveStanza = (id) => `stanza:${id}`
export const chiavePrestazione = (id) => `prestazione:${id}`
/** Nuova stanza: il nome è unico per il backend; chiave prudente indipendente dalle maiuscole. */
export const chiaveNuovaStanza = (nome) => `stanza-nuova:${apiCatalogo.trimJava(nome).toLowerCase()}`
/**
 * Nuova prestazione: il nome non è unico, quindi la chiave è l'insieme dei dati inviati; protegge
 * dal reinvio involontario dello stesso modulo senza impedire prestazioni diverse.
 */
export const chiaveNuovaPrestazione = (d) => `prestazione-nuova:${JSON.stringify([d.nome.toLowerCase(), d.descrizione, d.durata, d.centesimi])}`

/**
 * 429 e ogni 5xx (503 compreso) sono esiti incerti: il contratto non permette di distinguere con
 * certezza una risposta del backend prima della scrittura da quella di un intermediario, e né il
 * testo, né la presenza di JSON o di Retry-After lo dimostrano. L'eventuale Retry-After è
 * restituito a parte: diventa un'attesa sulle scritture, indipendente dall'incertezza.
 */
function classificaAdmin(error) {
  const retryAfter = error instanceof HttpError ? error.retryAfter : null
  if (error instanceof HttpError && (error.status === 429 || error.status >= 500)) return { kind: 'incerto', retryAfter }
  const esito = classifica(error, ERRORI)
  return esito.kind === 'incerto' ? { ...esito, retryAfter } : esito
}

function createStore(session) {
  const state = reactive({
    owner: null,
    generazione: 0,
    /** Chiavi con una scrittura in volo. */
    inCorso: [],
    /**
     * Esiti incerti: { chiave, tipo: 'crea'|'ruolo'|'stato', idUtente, riepilogo, verifica }.
     * riepilogo: dati del tentativo, mai la password. verifica: null | 'in-corso' | 'errore' | 'superata' | 'non-conclusiva'.
     */
    incerte: [],
    /** Barriera di ogni elenco o risorsa: valore dell'orologio all'ultimo esito certo che la riguarda. */
    barriere: {},
    profiliPaziente: {},
    /** Istante (ms) fino a cui il server ha chiesto di attendere prima di nuove scritture, oppure null. */
    attesaFino: null,
  })
  let logoutAvviato = false
  let orologio = 0
  /** Riscontri ordinati per risorsa (vedi applicaRiscontro). */
  let riscontri = new Map()

  function svuota(owner) {
    state.generazione += 1
    state.owner = owner
    state.inCorso = []
    state.incerte = []
    state.barriere = {}
    state.profiliPaziente = {}
    state.attesaFino = null
    riscontri = new Map()
  }

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

  /** Identità dell'amministratore, solo dalla sessione. L'operatore desk non è amministratore. */
  function amministratoreCorrente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value.admin) return null
    const id = session.state.user?.idUtente
    return id === state.owner ? id : null
  }

  // ---------------------------------------------------------------- letture ordinate

  function inizioLettura() {
    orologio += 1
    return Object.freeze({ n: orologio, generazione: state.generazione, epoca: session.state.epoch })
  }
  const barriera = (k) => state.barriere[k] ?? 0
  /**
   * Validità reattiva di una lettura per l'ambito `k`: cessa appena un esito certo successivo al
   * suo avvio tocca quell'ambito (anche da un'altra vista o da un'istanza precedente), o cambiano
   * generazione o epoca. Una lettura avviata dopo la barriera resta valida.
   */
  const valida = (k, ticket) => ticket?.generazione === state.generazione && ticket.epoca === session.state.epoch && ticket.n > barriera(k)
  function esitoCerto(ambiti) {
    orologio += 1
    state.barriere = { ...state.barriere, ...Object.fromEntries(ambiti.map((k) => [k, orologio])) }
  }

  // ---------------------------------------------------------------- blocchi e attese

  const incerta = (chiave) => state.incerte.find((x) => x.chiave === chiave) ?? null
  const bloccata = (chiave) => state.inCorso.includes(chiave) || incerta(chiave) !== null
  const aggiornaIncerta = (chiave, campi) => {
    state.incerte = state.incerte.map((x) => (x.chiave === chiave ? { ...x, ...campi } : x))
  }
  /** Secondi di attesa residui chiesti dal server (0 se nessuna). */
  function attesaResidua(ora = Date.now()) {
    return state.attesaFino === null ? 0 : Math.max(0, Math.ceil((state.attesaFino - ora) / 1000))
  }
  function impostaAttesa(secondi) {
    if (secondi === null || secondi === undefined) return
    const fino = Date.now() + secondi * 1000
    if (state.attesaFino === null || fino > state.attesaFino) state.attesaFino = fino
  }

  /**
   * Scrittura comune: controlli prima di fetch, un solo invio, classificazione dell'esito.
   * @param {{chiave, tipo, idUtente, riepilogo, ambiti: string[], invia: () => Promise}} op
   */
  async function esegui(op) {
    if (op.desk ? !deskCorrente() : amministratoreCorrente() === null) return { kind: 'sessione-assente' }
    if (session.state.passwordUncertain) return { kind: 'password-incerta' }
    const secondi = attesaResidua()
    if (secondi > 0) return { kind: 'attesa', retryAfter: secondi }
    if (state.inCorso.includes(op.chiave)) return { kind: 'occupato' }
    if (bloccata(op.chiave)) return { kind: 'bloccata' }
    const mia = state.generazione
    state.inCorso = [...state.inCorso, op.chiave]
    try {
      const risultato = await op.invia()
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      esitoCerto(op.ambiti)
      return { kind: 'eseguita', risultato }
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      // Dati non validi rifiutati localmente: nessuna richiesta inviata.
      if (error instanceof TypeError) return { kind: 'non-valida' }
      const esito = classificaAdmin(error)
      if (esito.kind === 'incerto') {
        state.incerte = [...state.incerte, {
          chiave: op.chiave, tipo: op.tipo, idUtente: op.idUtente, riepilogo: op.riepilogo, verifica: null,
        }]
      } else if (esito.kind === 'conflitto' || esito.kind === 'non-trovata') {
        // Rifiuto certo dovuto allo stato corrente: le letture precedenti sono superate.
        esitoCerto(op.ambiti)
      }
      // L'attesa chiesta dal server vale per tutte le scritture e non risolve né crea certezze:
      // alla scadenza l'incertezza resta e serve la verifica manuale.
      if (esito.retryAfter !== undefined) impostaAttesa(esito.retryAfter)
      return esito
    } finally {
      if (mia === state.generazione) state.inCorso = state.inCorso.filter((x) => x !== op.chiave)
    }
  }

  const deskCorrente = () => session.state.status === STATO.AUTENTICATO && session.capabilities.value.desk
    && session.state.user?.idUtente === state.owner
  function reimpostaPasswordPaziente(p, password) {
    if (!p || p.medico !== false || p.gestionale !== false || !api.isId(p.idPaziente)) return Promise.resolve({ kind: 'rifiutata' })
    return esegui({ desk: true, chiave: chiaveUtente(p.idPaziente), tipo: 'password-paziente', idUtente: p.idPaziente,
      riepilogo: Object.freeze({ nome: p.nome, cognome: p.cognome, email: p.email }),
      ambiti: ['pazienti', RICERCA, ELENCO, chiaveUtente(p.idPaziente)],
      invia: () => putPasswordPaziente(session.protectedRequest, p.idPaziente, password) })
  }
  function consentiNuovoResetPaziente(id) {
    const k = chiaveUtente(id)
    if (!deskCorrente() || state.inCorso.includes(k) || incerta(k)?.tipo !== 'password-paziente') return false
    state.incerte = state.incerte.filter(x => x.chiave !== k)
    esitoCerto(['pazienti', k])
    return true
  }

  /** Riscontri minimi del dettaglio desk, ordinati per avvio; nessun dato locale del modulo. */
  function registraProfiloPaziente(id, presente, ticket) {
    if (!deskCorrente() || typeof presente !== 'boolean' || !valida(chiaveMedico(id), ticket)) return false
    const prima = profiloPazienteCorrente(id)
    if (prima && prima.ticket.n > ticket.n) return false
    state.profiliPaziente = { ...state.profiliPaziente, [id]: { presente, ticket } }
    return true
  }
  function profiloPazienteCorrente(id) {
    const r = state.profiliPaziente[id]
    return deskCorrente() && r && valida(chiaveMedico(id), r.ticket) ? r : null
  }
  function profiloPazienteValido(id, ticket) {
    const r = profiloPazienteCorrente(id)
    return !!r && r.ticket.n === ticket?.n && valida(chiaveMedico(id), ticket)
  }
  function collegaPaziente(medico, dati, ticket) {
    const id = medico?.idUtente
    if (!api.isId(id) || !profiloPazienteValido(id, ticket)) return Promise.resolve({ kind: 'non-aggiornata' })
    if (profiloPazienteCorrente(id).presente) return Promise.resolve({ kind: 'profilo-presente' })
    return esegui({ desk: true, chiave: chiaveUtente(id), tipo: 'collega-paziente', idUtente: id,
      riepilogo: Object.freeze({ nome: medico.nome, cognome: medico.cognome }),
      ambiti: ['pazienti', MEDICI, RICERCA, ELENCO, chiaveUtente(id), chiaveMedico(id)],
      invia: () => postCollegamentoPaziente(session.protectedRequest, id, dati) })
  }
  async function verificaCollegamentoPaziente(id) {
    const k = chiaveUtente(id), x = incerta(k)
    if (!deskCorrente() || x?.tipo !== 'collega-paziente') return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = state.generazione, epoca = session.state.epoch, prima = x.verifica, t = inizioLettura()
    aggiornaIncerta(k, { verifica: 'in-corso', verificaTicket: t.n })
    const attuale = () => mia === state.generazione && epoca === session.state.epoch && deskCorrente()
    const ripristina = () => {
      if (mia === state.generazione && incerta(k)?.verificaTicket === t.n) aggiornaIncerta(k, { verifica: prima, verificaTicket: null })
    }
    try {
      const d = await fetchMedicoDesk(session.protectedRequest, id)
      if (!attuale()) { ripristina(); return { kind: 'obsoleto' } }
      registraProfiloPaziente(id, d.paziente, t)
      // Ricontrollo al momento di costruire l'esito: applicazione precedente non basta.
      const r = profiloPazienteCorrente(id)
      if (!r || r.ticket.n < t.n) { ripristina(); return { kind: 'superata' } }
      if (r.presente) {
        state.incerte = state.incerte.filter(v => v.chiave !== k)
        return { kind: 'profilo-presente' }
      }
      aggiornaIncerta(k, { verifica: 'assente-non-conclusiva', verificaTicket: null })
      return { kind: 'profilo-assente' }
    } catch {
      if (!attuale()) { ripristina(); return { kind: 'obsoleto' } }
      aggiornaIncerta(k, { verifica: 'errore', verificaTicket: null })
      return { kind: 'errore' }
    }
  }
  function consentiNuovoCollegamentoPaziente(id) {
    const k = chiaveUtente(id), x = incerta(k), r = profiloPazienteCorrente(id)
    if (!deskCorrente() || state.inCorso.includes(k) || x?.tipo !== 'collega-paziente' || x.verifica !== 'assente-non-conclusiva'
        || !r || r.presente) return false
    state.incerte = state.incerte.filter(v => v.chiave !== k)
    esitoCerto(['pazienti', chiaveUtente(id), chiaveMedico(id)])
    return true
  }

  // ---------------------------------------------------------------- operazioni

  /**
   * Creazione di un account gestionale. `dati` già validati (validaNuovoAccount); la password
   * resta solo in questo argomento e non entra nel riepilogo.
   */
  function crea(dati, password) {
    const chiave = chiaveEmail(dati.email)
    return esegui({
      chiave, tipo: 'crea', idUtente: null, riepilogo: Object.freeze({ ...dati }),
      ambiti: [ELENCO, RICERCA, chiave],
      invia: async () => api.parseCreazione(await api.postAccount(session.protectedRequest, dati, password), dati),
    })
  }

  /** Cambio di ruolo di un account già gestionale (u.ruolo non nullo) verso l'altro ruolo gestionale. */
  function cambiaRuolo(u, ruolo) {
    if (!api.RUOLI.includes(u?.ruolo)) return Promise.resolve({ kind: 'non-gestionale' })
    if (ruolo === u.ruolo) return Promise.resolve({ kind: 'invariato' })
    const chiave = chiaveUtente(u.idUtente)
    return esegui({
      chiave, tipo: 'ruolo', idUtente: u.idUtente,
      riepilogo: Object.freeze({ nome: u.nome, cognome: u.cognome, email: u.email, precedente: u.ruolo, richiesto: ruolo }),
      ambiti: [ELENCO, RICERCA, chiave],
      invia: async () => api.parseRuolo(await api.putRuolo(session.protectedRequest, u.idUtente, ruolo), u.idUtente, ruolo),
    })
  }

  /** Stato di accesso dell'account (ATTIVO/DISABILITATO). */
  function cambiaStato(u, stato) {
    if (stato === u?.statoAccesso) return Promise.resolve({ kind: 'invariato' })
    const chiave = chiaveUtente(u.idUtente)
    return esegui({
      chiave, tipo: 'stato', idUtente: u.idUtente,
      riepilogo: Object.freeze({ nome: u.nome, cognome: u.cognome, email: u.email, precedente: u.statoAccesso, richiesto: stato }),
      ambiti: [ELENCO, RICERCA, chiave],
      invia: async () => api.parseStato(await api.putStato(session.protectedRequest, u.idUtente, stato), u.idUtente, stato),
    })
  }

  // ---------------------------------------------------------------- riscontri ordinati

  /**
   * Ultima lettura applicata per risorsa ('utente:12', 'email:indirizzo'), distinta dalle barriere
   * delle scritture: { ticket, voce } con voce null = assenza coperta. Vale l'ordine di avvio:
   * una lettura avviata prima dell'ultima applicata o di un esito certo sulla risorsa non si
   * applica. Solo letture autorevoli: mai dati locali né risposte di scrittura.
   */
  const ticketValido = (t) => t?.generazione === state.generazione && t.epoca === session.state.epoch
  function applicaRiscontro(k, ticket, voce) {
    if (!ticketValido(ticket) || ticket.n <= barriera(k)) return false
    const prec = riscontri.get(k)
    if (prec && ticketValido(prec.ticket) && prec.ticket.n >= ticket.n) return false
    riscontri.set(k, Object.freeze({ ticket, voce }))
    return true
  }
  /**
   * Identità condivisa utenti/medici: un riscontro più recente dello stesso ID smentisce il collegamento
   * email → voce se registra un'altra email (un cambio del solo maiuscolo/minuscolo non conta).
   * Fonti: la vista amministrativa ('utente:id', anche assenza dell'account) e le letture dei
   * medici ('medico:id', solo il campo email esposto; l'assenza del profilo medico non dice nulla
   * sull'account). Non prova l'assenza dell'email: potrebbe appartenere a un altro account.
   */
  function collegamentoSmentito(email, voce, ticket) {
    const e = api.emailConfronto(email)
    const recente = (r) => r && ticketValido(r.ticket) && r.ticket.n > ticket.n
    const u = riscontri.get(chiaveUtente(voce.idUtente))
    if (recente(u) && (u.voce === null || api.emailConfronto(u.voce.email) !== e)) return true
    const m = riscontri.get(chiaveMedico(voce.idUtente))
    return !!(recente(m) && m.voce?.medico && api.emailConfronto(m.voce.medico.email) !== e)
  }

  /**
   * Le righe restituite sono riscontri puntuali della loro presenza, per ID e per email. Una
   * risposta vecchia non ricrea un collegamento email già smentito da un riscontro più recente dell'ID.
   */
  function presenze(ticket, voci) {
    for (const v of voci) {
      applicaRiscontro(chiaveUtente(v.idUtente), ticket, v)
      if (!collegamentoSmentito(v.email, v, ticket)) applicaRiscontro(chiaveEmail(v.email), ticket, v)
    }
  }

  /** Elenco gestionale applicato: solo presenze (non prova l'assenza di utenti non gestionali). */
  function registraElenco(voci, ticket) {
    presenze(ticket, voci)
  }

  /**
   * Pagina di ricerca applicata. Oltre alle presenze, un'assenza è coperta solo se la ricerca la
   * dimostra: per ID con il solo filtro ID; per email con il solo filtro email, dalla prima
   * pagina e senza pagine successive (ricerca esaurita), per le email in attesa di verifica che
   * iniziano con il prefisso. Nessuna copertura per ricerche con altri filtri.
   */
  function registraRicerca(filtri, dopoId, pagina, ticket) {
    presenze(ticket, pagina.risultati)
    const chiavi = Object.keys(filtri)
    if (dopoId !== 0 || chiavi.length !== 1) return
    if (chiavi[0] === 'idUtente') {
      const id = Number(filtri.idUtente)
      if (!pagina.risultati.some((u) => u.idUtente === id)) applicaRiscontro(chiaveUtente(id), ticket, null)
    } else if (chiavi[0] === 'email' && pagina.prossimoDopoId === null) {
      const prefisso = api.emailConfronto(filtri.email)
      for (const x of state.incerte) {
        const e = x.tipo === 'crea' ? api.emailConfronto(x.riepilogo.email) : null
        if (e && e.startsWith(prefisso) && !pagina.risultati.some((u) => api.emailConfronto(u.email) === e)) {
          applicaRiscontro(chiaveEmail(e), ticket, null)
        }
      }
    }
  }

  // ---------------------------------------------------------------- verifiche

  const PAGINE_MASSIME = 10

  /**
   * Prima fase della verifica manuale: lettura con la ricerca amministrativa, registrata come
   * riscontro. Ruolo e stato: ricerca per ID (assenza coperta). Creazione: ricerca per email a
   * prefisso, pagina dopo pagina; una pagina senza corrispondenza esatta non prova nulla, serve
   * l'esaurimento della ricerca; oltre PAGINE_MASSIME la lettura non è conclusiva.
   * @returns {{kind:'letta', ticket, conclusiva:boolean} | {kind:string}}
   */
  async function leggiVerifica(chiave) {
    if (['password-medico', 'password-paziente', 'collega-paziente'].includes(incerta(chiave)?.tipo)) return { kind: 'non-verificabile' }
    if (TIPI_MEDICO.includes(incerta(chiave)?.tipo)) return leggiVerificaMedico(chiave)
    if (TIPI_CATALOGO.includes(incerta(chiave)?.tipo)) return leggiVerificaCatalogo(chiave)
    const admin = amministratoreCorrente()
    const x = incerta(chiave)
    if (admin === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = state.generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaIncerta(chiave, { verifica: 'in-corso' })
    const attuale = () => mia === state.generazione && session.state.epoch === epoca && state.owner === admin
    let lettura
    try {
      if (x.tipo === 'crea') {
        const cercata = api.emailConfronto(x.riepilogo.email)
        const filtri = { email: cercata }
        const primo = inizioLettura()
        lettura = { kind: 'letta', ticket: primo, conclusiva: false }
        let dopo = 0
        for (let n = 0; n < PAGINE_MASSIME; n++) {
          const ticket = n === 0 ? primo : inizioLettura()
          const r = await api.fetchRicerca(session.protectedRequest, filtri, dopo, 50)
          if (!attuale()) break
          presenze(ticket, r.risultati)
          if (r.risultati.some((u) => api.emailConfronto(u.email) === cercata)) { lettura = { kind: 'letta', ticket, conclusiva: true }; break }
          if (r.prossimoDopoId === null) {
            // Ricerca esaurita: assenza coperta dall'avvio della prima pagina.
            applicaRiscontro(chiave, primo, null)
            lettura = { kind: 'letta', ticket: primo, conclusiva: true }
            break
          }
          dopo = r.prossimoDopoId
        }
      } else {
        const ticket = inizioLettura()
        const filtri = { idUtente: String(x.idUtente) }
        const r = await api.fetchRicerca(session.protectedRequest, filtri, 0, 1)
        if (attuale()) registraRicerca(filtri, 0, r, ticket)
        lettura = { kind: 'letta', ticket, conclusiva: true }
      }
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaIncerta(chiave, { verifica: stale ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== state.generazione) return { kind: 'obsoleto' }
    if (!attuale()) {
      aggiornaIncerta(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    aggiornaIncerta(chiave, { verifica: null })
    return lettura
  }

  /**
   * Seconda fase, da eseguire quando si costruisce il messaggio: ricontrolla il riscontro corrente
   * della risorsa. Se la lettura della verifica (A) è ancora l'ultima applicata usa A; se un
   * riscontro più recente (B) è applicato ed è sufficiente usa B; altrimenti esito neutro
   * ('superata', 'non-conclusiva' o, se la presenza per email è smentita da un riscontro più
   * recente dello stesso ID, 'incoerente'), blocco mantenuto e verifica da ripetere a mano.
   * esito: 'presente' | 'assente' (creazione); 'come-richiesto' | 'precedente' | 'diverso' | 'assente' (ruolo, stato).
   */
  function esitoVerifica(chiave, lettura) {
    if (['password-medico', 'password-paziente', 'collega-paziente'].includes(incerta(chiave)?.tipo)) return { kind: 'non-verificabile' }
    if (TIPI_MEDICO.includes(incerta(chiave)?.tipo)) return esitoVerificaMedico(chiave, lettura)
    if (TIPI_CATALOGO.includes(incerta(chiave)?.tipo)) return esitoVerificaCatalogo(chiave, lettura)
    const x = incerta(chiave)
    if (amministratoreCorrente() === null || x === null) return { kind: 'sessione-assente' }
    if (!ticketValido(lettura?.ticket)) return { kind: 'obsoleto' }
    const r = riscontri.get(chiave)
    // Coerenza ricontrollata ora: una presenza per email vale solo se l'ID non risulta, più di recente, altrove.
    const smentito = x.tipo === 'crea' && r?.voce && collegamentoSmentito(x.riepilogo.email, r.voce, r.ticket)
    const usabile = r && ticketValido(r.ticket) && r.ticket.n >= lettura.ticket.n && r.ticket.n > barriera(chiave) && !smentito
    if (!usabile) {
      const kind = smentito ? 'incoerente' : lettura.conclusiva ? 'superata' : 'non-conclusiva'
      aggiornaIncerta(chiave, { verifica: kind })
      return { kind }
    }
    const voce = r.voce
    let esito
    if (x.tipo === 'crea') esito = voce ? 'presente' : 'assente'
    else if (!voce) esito = 'assente'
    else {
      const corrente = x.tipo === 'ruolo' ? voce.ruolo : voce.statoAccesso
      esito = corrente === x.riepilogo.richiesto ? 'come-richiesto' : corrente === x.riepilogo.precedente ? 'precedente' : 'diverso'
    }
    // Riscontro corrente acquisito: l'incertezza si chiude; un nuovo invio resta solo esplicito
    // e comunque soggetto all'eventuale attesa chiesta dal server.
    state.incerte = state.incerte.filter((v) => v.chiave !== chiave)
    return { kind: 'verificata', esito, voce, incerta: x, fonte: r.ticket.n === lettura.ticket.n ? 'verifica' : 'successiva' }
  }

  /** Verifica completa: lettura e costruzione immediata dell'esito. */
  async function verifica(chiave) {
    const lettura = await leggiVerifica(chiave)
    return lettura.kind === 'letta' ? esitoVerifica(chiave, lettura) : lettura
  }


  // ================================================================ gestione medici: medici e abilitazioni

  /**
   * Le operazioni sui medici usano le stesse chiavi di gestione utenti ('utente:id' per un account esistente,
   * 'email:indirizzo' per un account nuovo): operazioni utenti e medici sulla stessa identità si
   * bloccano a vicenda. Ogni esito certo pone barriere sugli elenchi dei medici, sul dettaglio e
   * sulle chiavi dell'identità; i dati personali anche sulla ricerca utenti e sulle email.
   * I riscontri dei medici vivono nell'ambito 'medico:id' e non alimentano quelli di gestione utenti: la
   * proiezione dei medici non contiene lo stato di accesso dell'account.
   */
  const ambitiMedico = (id, ...altri) => [MEDICI, chiaveMedico(id), chiaveUtente(id), ...altri]
  const nomeDi = (m) => ({ nome: m.nome, cognome: m.cognome, email: m.email })

  function reimpostaPasswordMedico(m, password) {
    return esegui({ chiave: chiaveUtente(m.idUtente), tipo: 'password-medico', idUtente: m.idUtente,
      riepilogo: Object.freeze(nomeDi(m)), ambiti: ambitiMedico(m.idUtente, RICERCA, ELENCO),
      invia: () => apiMedici.putPasswordMedico(session.protectedRequest, m.idUtente, password) })
  }
  /** Consente un nuovo reset esplicito; non dimostra quale password sia stata salvata prima. */
  function consentiNuovoReset(id) {
    const k = chiaveUtente(id)
    if (amministratoreCorrente() === null || state.inCorso.includes(k) || incerta(k)?.tipo !== 'password-medico') return false
    state.incerte = state.incerte.filter(x => x.chiave !== k)
    esitoCerto(ambitiMedico(id, RICERCA, ELENCO))
    return true
  }

  /** Nuovo medico (POST /api/medici). La password resta solo nell'argomento dell'invio. */
  function creaMedico(dati, password) {
    const chiave = chiaveEmail(dati.email)
    return esegui({
      chiave, tipo: 'medico-crea', idUtente: null, riepilogo: Object.freeze({ ...dati }),
      ambiti: [MEDICI, RICERCA, chiave],
      invia: async () => apiMedici.parseEsitoMedico(await apiMedici.postMedico(session.protectedRequest, dati, password), 201, null, {
        nome: dati.nome, cognome: dati.cognome, email: dati.email, specializzazione: dati.specializzazione,
        numeroIscrizioneOrdine: dati.numeroIscrizioneOrdine, stato: 'ATTIVO', paziente: false, ruolo: null,
      }),
    })
  }

  /** Profilo medico per un utente esistente scelto dalla ricerca (u: vista amministrativa gestione utenti). */
  function collega(u, dati) {
    const chiave = chiaveUtente(u.idUtente)
    return esegui({
      chiave, tipo: 'collega', idUtente: u.idUtente,
      riepilogo: Object.freeze({ ...nomeDi(u), richiesto: Object.freeze({ ...dati }) }),
      ambiti: ambitiMedico(u.idUtente),
      invia: async () => apiMedici.parseEsitoMedico(await apiMedici.postCollegamento(session.protectedRequest, u.idUtente, dati), 201, u.idUtente, {
        specializzazione: dati.specializzazione, numeroIscrizioneOrdine: dati.numeroIscrizioneOrdine, stato: 'ATTIVO',
      }),
    })
  }

  /** Variante completa PERSONALI o PROFESSIONALI di PUT /api/medici/{id}. */
  function aggiornaMedico(m, tipo, dati) {
    const personali = tipo === 'PERSONALI'
    const campi = personali ? ['nome', 'cognome', 'email'] : ['specializzazione', 'numeroIscrizioneOrdine']
    const precedente = Object.freeze(Object.fromEntries(campi.map((k) => [k, m[k]])))
    const chiave = chiaveUtente(m.idUtente)
    const altri = personali ? [RICERCA, chiaveEmail(m.email), chiaveEmail(dati.email)] : []
    return esegui({
      chiave, tipo: personali ? 'personali' : 'professionali', idUtente: m.idUtente,
      riepilogo: Object.freeze({ ...nomeDi(m), precedente, richiesto: Object.freeze({ ...dati }) }),
      ambiti: ambitiMedico(m.idUtente, ...altri),
      invia: async () => apiMedici.parseEsitoMedico(await apiMedici.putDatiMedico(session.protectedRequest, m.idUtente, tipo, dati), 200, m.idUtente, dati),
    })
  }

  /** Stato operativo del medico (ATTIVO/INATTIVO), distinto dallo stato di accesso dell'account. */
  function cambiaStatoMedico(m, stato) {
    if (stato === m.stato) return Promise.resolve({ kind: 'invariato' })
    const chiave = chiaveUtente(m.idUtente)
    return esegui({
      chiave, tipo: 'stato-medico', idUtente: m.idUtente,
      riepilogo: Object.freeze({ ...nomeDi(m), precedente: m.stato, richiesto: stato }),
      ambiti: ambitiMedico(m.idUtente),
      invia: async () => apiMedici.parseEsitoMedico(await apiMedici.putStatoMedico(session.protectedRequest, m.idUtente, stato), 200, m.idUtente, { stato }),
    })
  }

  /** Abilitazione: ASSEGNA o RIMUOVI una prestazione del catalogo; dipendenze decise dal backend. */
  function abilitazione(m, operazione, prestazione) {
    const chiave = chiaveUtente(m.idUtente)
    return esegui({
      chiave, tipo: 'abilitazione', idUtente: m.idUtente,
      riepilogo: Object.freeze({ ...nomeDi(m), operazione, idPrestazione: prestazione.idPrestazione, nomePrestazione: prestazione.nome }),
      ambiti: ambitiMedico(m.idUtente),
      invia: async () => apiMedici.parseEsitoAbilitazione(
        await apiMedici.putAbilitazione(session.protectedRequest, m.idUtente, operazione, prestazione.idPrestazione), m.idUtente, operazione, prestazione.idPrestazione),
    })
  }

  // ---------------------------------------------------------------- riscontri dei medici

  /**
   * Elenco dei medici applicato: presenze puntuali (senza abilitazioni). Solo l'elenco senza
   * filtri copre l'assenza di un profilo, e solo per gli utenti con un collegamento incerto.
   */
  function registraMedici(voci, ticket, filtri = {}) {
    for (const m of voci) applicaRiscontro(chiaveMedico(m.idUtente), ticket, Object.freeze({ medico: m, prestazioni: undefined }))
    if (filtri.stato || filtri.idPrestazione) return
    for (const x of state.incerte) {
      if (x.tipo === 'collega' && !voci.some((m) => m.idUtente === x.idUtente)) applicaRiscontro(chiaveMedico(x.idUtente), ticket, null)
    }
  }
  /** Dettaglio applicato: medico con abilitazioni. */
  function registraDettaglio(dettaglio, ticket) {
    applicaRiscontro(chiaveMedico(dettaglio.medico.idUtente), ticket, Object.freeze({ medico: dettaglio.medico, prestazioni: dettaglio.prestazioni }))
  }
  /** 404 del dettaglio: assenza del profilo medico per quell'ID (lettura diretta). */
  function registraAssenzaMedico(id, ticket) {
    applicaRiscontro(chiaveMedico(id), ticket, null)
  }

  // ---------------------------------------------------------------- verifiche gestione medici

  const is404 = (e) => e instanceof HttpError && e.status === 404
  /** Dettaglio con 404 registrato come assenza; altri errori propagati. */
  async function leggiDettaglio(id, ticket) {
    try {
      registraDettaglio(await apiMedici.fetchMedicoAdmin(session.protectedRequest, id), ticket)
    } catch (error) {
      if (!is404(error)) throw error
      registraAssenzaMedico(id, ticket)
    }
  }

  /**
   * Prima fase della verifica di un esito incerto gestione medici. Creazione: ricerca per email fino
   * all'esaurimento (regole gestione utenti), poi dettaglio dell'ID trovato. Altre operazioni: dettaglio
   * del medico (un 404 è assenza del profilo per quell'ID).
   */
  async function leggiVerificaMedico(chiave) {
    const admin = amministratoreCorrente()
    const x = incerta(chiave)
    if (admin === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = state.generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaIncerta(chiave, { verifica: 'in-corso' })
    const attuale = () => mia === state.generazione && session.state.epoch === epoca && state.owner === admin
    let lettura
    try {
      if (x.tipo === 'medico-crea') {
        const cercata = api.emailConfronto(x.riepilogo.email)
        const primo = inizioLettura()
        lettura = { kind: 'letta', ticket: primo, conclusiva: false }
        let dopo = 0
        for (let n = 0; n < PAGINE_MASSIME; n++) {
          const ticket = n === 0 ? primo : inizioLettura()
          const r = await api.fetchRicerca(session.protectedRequest, { email: cercata }, dopo, 50)
          if (!attuale()) break
          presenze(ticket, r.risultati)
          const trovato = r.risultati.find((u) => api.emailConfronto(u.email) === cercata)
          if (trovato) {
            await leggiDettaglio(trovato.idUtente, inizioLettura())
            lettura = { kind: 'letta', ticket, conclusiva: true }
            break
          }
          if (r.prossimoDopoId === null) {
            applicaRiscontro(chiave, primo, null)
            lettura = { kind: 'letta', ticket: primo, conclusiva: true }
            break
          }
          dopo = r.prossimoDopoId
        }
      } else {
        const ticket = inizioLettura()
        await leggiDettaglio(x.idUtente, ticket)
        lettura = { kind: 'letta', ticket, conclusiva: true }
      }
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaIncerta(chiave, { verifica: stale ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    if (mia !== state.generazione) return { kind: 'obsoleto' }
    if (!attuale()) {
      aggiornaIncerta(chiave, { verifica: precedente })
      return { kind: 'obsoleto' }
    }
    aggiornaIncerta(chiave, { verifica: null })
    return lettura
  }

  /** Riscontro corrente del medico se usabile per una lettura avviata con `ticket`. */
  function riscontroMedico(id, ticket) {
    const r = riscontri.get(chiaveMedico(id))
    return r && ticketValido(r.ticket) && r.ticket.n >= ticket.n && r.ticket.n > barriera(chiaveMedico(id)) ? r : null
  }
  const uguali = (a, b, campi) => campi.every((k) => (k === 'email' ? api.emailConfronto(a[k]) === api.emailConfronto(b[k]) : a[k] === b[k]))
  const confronto = (corrente, x, campi) => (uguali(corrente, x.riepilogo.richiesto, campi) ? 'come-richiesto'
    : x.riepilogo.precedente && uguali(corrente, x.riepilogo.precedente, campi) ? 'precedente' : 'diverso')

  /**
   * Seconda fase: esito costruito sul riscontro corrente (lettura della verifica o lettura più
   * recente sufficiente), mai attribuito con certezza al tentativo; altrimenti esito neutro.
   * esito: 'come-richiesto' | 'precedente' | 'diverso' | 'assente' | 'senza-profilo' (creazione).
   */
  function esitoVerificaMedico(chiave, lettura) {
    const x = incerta(chiave)
    if (amministratoreCorrente() === null || x === null) return { kind: 'sessione-assente' }
    if (!ticketValido(lettura?.ticket)) return { kind: 'obsoleto' }
    const neutro = (kind) => { aggiornaIncerta(chiave, { verifica: kind }); return { kind } }
    let esito
    let voce = null
    if (x.tipo === 'medico-crea') {
      const r = riscontri.get(chiave)
      const smentito = r?.voce && collegamentoSmentito(x.riepilogo.email, r.voce, r.ticket)
      if (!r || !ticketValido(r.ticket) || r.ticket.n < lettura.ticket.n || r.ticket.n <= barriera(chiave)) return neutro(lettura.conclusiva ? 'superata' : 'non-conclusiva')
      if (smentito) return neutro('incoerente')
      if (r.voce === null) esito = 'assente'
      else {
        const m = riscontroMedico(r.voce.idUtente, lettura.ticket)
        if (!m) return neutro('superata')
        voce = m.voce?.medico ?? null
        esito = voce === null ? 'senza-profilo'
          : uguali(voce, x.riepilogo, ['nome', 'cognome', 'email', 'specializzazione', 'numeroIscrizioneOrdine']) ? 'come-richiesto' : 'diverso'
        if (voce === null) voce = r.voce
      }
    } else {
      const r = riscontroMedico(x.idUtente, lettura.ticket)
      if (!r) return neutro('superata')
      const m = r.voce?.medico ?? null
      voce = m
      if (m === null) esito = 'assente'
      else if (x.tipo === 'collega') esito = uguali(m, x.riepilogo.richiesto, ['specializzazione', 'numeroIscrizioneOrdine']) ? 'come-richiesto' : 'diverso'
      else if (x.tipo === 'personali') esito = confronto(m, x, ['nome', 'cognome', 'email'])
      else if (x.tipo === 'professionali') esito = confronto(m, x, ['specializzazione', 'numeroIscrizioneOrdine'])
      else if (x.tipo === 'stato-medico') esito = m.stato === x.riepilogo.richiesto ? 'come-richiesto' : m.stato === x.riepilogo.precedente ? 'precedente' : 'diverso'
      else {
        // Abilitazioni: servono quelle del dettaglio; un elenco più recente senza abilitazioni non basta.
        if (r.voce.prestazioni === undefined) return neutro('superata')
        const presente = r.voce.prestazioni.some((p) => p.idPrestazione === x.riepilogo.idPrestazione)
        esito = presente === (x.riepilogo.operazione === 'ASSEGNA') ? 'come-richiesto' : 'precedente'
      }
    }
    state.incerte = state.incerte.filter((v) => v.chiave !== chiave)
    return { kind: 'verificata', esito, voce, incerta: x, fonte: 'riscontro' }
  }

  // ================================================================ cataloghi: stanze e prestazioni

  /**
   * Scritture sui cataloghi con le garanzie comuni (blocchi per risorsa, attesa Retry-After,
   * esiti incerti, barriere). Riscontro unico: l'elenco completo (non esistono letture singole
   * né filtri), registrato con il suo ticket nell'ambito dell'elenco stesso.
   */
  function scritturaCatalogo({ chiave, tipo, ambito, riepilogo, invia }) {
    return esegui({ chiave, tipo, idUtente: null, riepilogo: Object.freeze(riepilogo), ambiti: [ambito, chiave], invia })
  }
  function creaStanza(dati) {
    return scritturaCatalogo({
      chiave: chiaveNuovaStanza(dati.nome), tipo: 'stanza-crea', ambito: STANZE, riepilogo: { richiesto: dati },
      invia: async () => apiCatalogo.parseEsitoStanza(await apiCatalogo.scriviStanza(session.protectedRequest, null, dati), null, dati),
    })
  }
  function aggiornaStanza(st, dati) {
    return scritturaCatalogo({
      chiave: chiaveStanza(st.idStanza), tipo: 'stanza-modifica', ambito: STANZE,
      riepilogo: { idRisorsa: st.idStanza, precedente: { nome: st.nome, descrizione: st.descrizione }, richiesto: dati },
      invia: async () => apiCatalogo.parseEsitoStanza(await apiCatalogo.scriviStanza(session.protectedRequest, st.idStanza, dati), st.idStanza, dati),
    })
  }
  /** `noti`: ID delle prestazioni già nell'elenco valido al momento dell'invio (o null se non disponibile). */
  function creaPrestazione(dati, noti) {
    return scritturaCatalogo({
      chiave: chiaveNuovaPrestazione(dati), tipo: 'prestazione-crea', ambito: PRESTAZIONI, riepilogo: { richiesto: dati, noti: noti ? Object.freeze([...noti]) : null },
      invia: async () => apiCatalogo.parseEsitoPrestazione(await apiCatalogo.scriviPrestazione(session.protectedRequest, null, dati), null, dati),
    })
  }
  function aggiornaPrestazione(pr, dati) {
    return scritturaCatalogo({
      chiave: chiavePrestazione(pr.idPrestazione), tipo: 'prestazione-modifica', ambito: PRESTAZIONI,
      riepilogo: { idRisorsa: pr.idPrestazione, precedente: { nome: pr.nome, descrizione: pr.descrizione, durata: pr.durata, centesimi: pr.centesimi }, richiesto: dati },
      invia: async () => apiCatalogo.parseEsitoPrestazione(await apiCatalogo.scriviPrestazione(session.protectedRequest, pr.idPrestazione, dati), pr.idPrestazione, dati),
    })
  }
  /** Elenco completo applicato da una vista: riscontro dell'intero catalogo (copertura completa). */
  function registraCatalogo(ambito, voci, ticket) {
    applicaRiscontro(ambito, ticket, Object.freeze([...voci]))
  }

  const ambitoDi = (tipo) => (tipo.startsWith('stanza') ? STANZE : PRESTAZIONI)
  /** Prima fase: lettura dell'elenco completo, registrata come riscontro. */
  async function leggiVerificaCatalogo(chiave) {
    const admin = amministratoreCorrente()
    const x = incerta(chiave)
    if (admin === null || x === null) return { kind: 'sessione-assente' }
    if (x.verifica === 'in-corso') return { kind: 'occupato' }
    const mia = state.generazione
    const epoca = session.state.epoch
    const precedente = x.verifica
    aggiornaIncerta(chiave, { verifica: 'in-corso' })
    const ticket = inizioLettura()
    try {
      const ambito = ambitoDi(x.tipo)
      const voci = ambito === STANZE ? await apiCatalogo.fetchStanzeAdmin(session.protectedRequest) : await apiCatalogo.fetchPrestazioniAdmin(session.protectedRequest)
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      if (session.state.epoch !== epoca || state.owner !== admin) {
        aggiornaIncerta(chiave, { verifica: precedente })
        return { kind: 'obsoleto' }
      }
      registraCatalogo(ambito, voci, ticket)
    } catch (error) {
      if (mia !== state.generazione) return { kind: 'obsoleto' }
      const stale = error instanceof StaleResponseError
      aggiornaIncerta(chiave, { verifica: stale ? precedente : 'errore' })
      return { kind: stale ? 'obsoleto' : 'errore' }
    }
    aggiornaIncerta(chiave, { verifica: null })
    return { kind: 'letta', ticket, conclusiva: true }
  }

  const ugualeStanza = (a, b) => a.nome === b.nome && a.descrizione === b.descrizione
  const ugualePrestazione = (a, b) => a.nome === b.nome && a.descrizione === b.descrizione && a.durata === b.durata && a.centesimi === b.centesimi
  /**
   * Seconda fase, sul riscontro corrente dell'elenco: la lettura della verifica o un elenco più
   * recente; se superato da un esito certo sulla risorsa o su qualsiasi voce del catalogo, esito
   * neutro (incertezza e blocco mantenuti, verifica ripetibile). Mai attribuito al tentativo.
   * esito: stanza-crea 'presente'|'assente'; prestazione-crea 'nuova'|'solo-preesistenti'|
   * 'non-distinguibile'|'assente'; modifiche 'come-richiesto'|'precedente'|'diverso'|'assente'.
   */
  function esitoVerificaCatalogo(chiave, lettura) {
    const x = incerta(chiave)
    if (amministratoreCorrente() === null || x === null) return { kind: 'sessione-assente' }
    if (!ticketValido(lettura?.ticket)) return { kind: 'obsoleto' }
    const ambito = ambitoDi(x.tipo)
    const r = riscontri.get(ambito)
    // Autorevole solo se successivo a ogni esito certo sulla risorsa/tentativo e sull'intero
    // catalogo, e non precedente alla lettura della verifica: un riscontro anteriore resta tale
    // anche se la lettura della verifica è stata scartata dalla barriera.
    if (!r || !ticketValido(r.ticket) || r.ticket.n < lettura.ticket.n || r.ticket.n <= barriera(chiave) || r.ticket.n <= barriera(ambito)) {
      aggiornaIncerta(chiave, { verifica: 'superata' })
      return { kind: 'superata' }
    }
    const voci = r.voce
    const rq = x.riepilogo.richiesto
    let esito
    let trovate = []
    if (x.tipo === 'stanza-crea') {
      trovate = voci.filter((v) => v.nome === rq.nome)
      esito = trovate.length ? 'presente' : 'assente'
    } else if (x.tipo === 'prestazione-crea') {
      trovate = voci.filter((v) => ugualePrestazione(v, rq))
      const noti = x.riepilogo.noti
      if (!trovate.length) esito = 'assente'
      else if (noti === null) esito = 'non-distinguibile'
      else esito = trovate.some((v) => !noti.includes(v.idPrestazione)) ? 'nuova' : 'solo-preesistenti'
    } else {
      const id = x.riepilogo.idRisorsa
      const v = voci.find((w) => (x.tipo === 'stanza-modifica' ? w.idStanza : w.idPrestazione) === id) ?? null
      if (v) trovate = [v]
      const uguale = x.tipo === 'stanza-modifica' ? ugualeStanza : ugualePrestazione
      esito = !v ? 'assente' : uguale(v, rq) ? 'come-richiesto' : uguale(v, x.riepilogo.precedente) ? 'precedente' : 'diverso'
    }
    state.incerte = state.incerte.filter((v) => v.chiave !== chiave)
    return { kind: 'verificata', esito, voce: trovate[0] ?? null, trovate: Object.freeze(trovate), incerta: x }
  }

  return {
    state: readonly(state),
    amministratoreCorrente,
    inizioLettura,
    valida,
    incerta,
    bloccata,
    attesaResidua,
    crea,
    cambiaRuolo,
    cambiaStato,
    verifica,
    leggiVerifica,
    esitoVerifica,
    registraElenco,
    registraRicerca,
    // gestione medici
    creaMedico,
    reimpostaPasswordMedico,
    reimpostaPasswordPaziente,
    registraProfiloPaziente,
    profiloPazienteCorrente,
    profiloPazienteValido,
    collegaPaziente,
    verificaCollegamentoPaziente,
    consentiNuovoCollegamentoPaziente,
    consentiNuovoResetPaziente,
    consentiNuovoReset,
    collega,
    aggiornaMedico,
    cambiaStatoMedico,
    abilitazione,
    registraMedici,
    registraDettaglio,
    registraAssenzaMedico,
    // cataloghi
    creaStanza,
    aggiornaStanza,
    creaPrestazione,
    aggiornaPrestazione,
    registraCatalogo,
  }
}

const stores = new WeakMap()

/** Store degli account unico per sessione, condiviso anche dal reset desk dei pazienti. */
export function adminStore(session) {
  let store = stores.get(session)
  if (!store) {
    store = createStore(session)
    stores.set(session, store)
  }
  return store
}

export function useAdmin() {
  return adminStore(useSession())
}
