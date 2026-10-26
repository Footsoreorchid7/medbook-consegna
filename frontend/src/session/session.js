import { computed, reactive, readonly } from 'vue'
import * as authApi from '../api/auth.js'
import { HttpError, NetworkError, ProtocolError, StaleResponseError, request } from '../api/http.js'

export const STATO = Object.freeze({
  INIZIALE: 'iniziale',
  VERIFICA: 'verifica',
  AUTENTICATO: 'autenticato',
  ANONIMO: 'anonimo',
  ERRORE: 'errore',
  DISCONNESSIONE: 'disconnessione',
  LOGOUT_NON_CONFERMATO: 'logout-non-confermato',
})

export const AVVISO = Object.freeze({
  SESSIONE_SCADUTA: 'sessione-scaduta',
  LOGOUT_CONFERMATO: 'logout-confermato',
  SESSIONE_ASSENTE: 'sessione-assente',
  PASSWORD_NUOVO_ACCESSO: 'password-nuovo-accesso',
  PASSWORD_INCERTA_NUOVO_ACCESSO: 'password-incerta-nuovo-accesso',
})

/** Un'altra operazione di autenticazione è in corso. */
export class AuthBusyError extends Error {
  constructor() { super('Operazione di autenticazione in corso'); this.name = 'AuthBusyError' }
}

/** Operazione non ammessa nello stato corrente della sessione. */
export class AuthStateError extends Error {
  constructor() { super('Stato della sessione non compatibile'); this.name = 'AuthStateError' }
}

/**
 * Scrittura protetta rifiutata localmente, prima di fetch, perché l'esito di un cambio
 * password è ancora incerto: il CSRF in memoria potrebbe non essere più quello valido.
 * Restano disponibili le letture, la verifica manuale con /me e il logout esplicito.
 */
export class PasswordUncertainError extends AuthStateError {
  constructor() { super(); this.name = 'PasswordUncertainError' }
}

/** Metodi sicuri ammessi dalle API protette; ogni altro metodo è una scrittura. */
const SAFE_METHODS = new Set(['GET', 'HEAD'])

/**
 * Capacità cumulative: paziente e medico dai booleani, desk e admin dal ruolo
 * gestionale. L'amministratore non eredita le capacità desk.
 */
export function capabilitiesOf(user) {
  return Object.freeze({
    paziente: user?.paziente === true,
    medico: user?.medico === true,
    desk: user?.ruoloGestionale === 'OPERATORE_DESK',
    admin: user?.ruoloGestionale === 'AMMINISTRATORE',
  })
}

function errorKind(error) {
  if (error instanceof NetworkError) return 'rete'
  if (error instanceof ProtocolError) return 'protocollo'
  if (error instanceof HttpError) return 'http'
  return 'sconosciuto'
}

const LOGIN_FAILURES = { 400: 'non-valida', 401: 'credenziali', 403: 'rifiutata', 429: 'troppi-tentativi', 503: 'non-disponibile' }

/**
 * Esiti HTTP del cambio password con esito certo. 403 non è interpretato: può essere
 * password attuale errata oppure Origin/CSRF rifiutati.
 */
const PASSWORD_FAILURES = { 400: 'non-valida', 403: 'rifiutata', 409: 'conflitto', 415: 'non-valida', 429: 'troppi-tentativi' }

/**
 * Sessione in memoria: identità e CSRF non sono mai salvati nel browser.
 *
 * L'epoca cresce a ogni cambio di sessione (login, inizio logout, sessione persa) e
 * annulla le letture della sessione precedente. Le risposte obsolete vengono scartate
 * senza aggiornare dati, CSRF o stato. L'epoca riguarda solo lo stato del frontend:
 * non impedisce al browser di applicare un Set-Cookie arrivato con una risposta tardiva.
 *
 * Login, logout, bootstrap, cambio password e relative verifiche sono serializzati: mentre uno è in
 * corso gli altri vengono rifiutati con AuthBusyError, senza code implicite.
 */
export function createSession(api = authApi) {
  const state = reactive({
    status: STATO.INIZIALE,
    user: null,
    epoch: 0,
    busy: false,
    loginUncertain: false,
    passwordUncertain: false,
    notice: null,
    bootstrapError: null,
  })
  let csrfToken = null
  let readController = new AbortController()
  let bootstrapPromise = null

  const capabilities = computed(() => capabilitiesOf(state.user))

  function advanceEpoch() {
    readController.abort()
    readController = new AbortController()
    state.epoch += 1
  }

  function clearIdentity() {
    state.user = null
    csrfToken = null
    state.passwordUncertain = false
  }

  function setAuthenticated(identity) {
    advanceEpoch()
    state.user = identity.user
    csrfToken = identity.csrfToken
    state.status = STATO.AUTENTICATO
    state.loginUncertain = false
    state.passwordUncertain = false
    state.bootstrapError = null
  }

  async function exclusive(operation) {
    if (state.busy) throw new AuthBusyError()
    state.busy = true
    try {
      return await operation()
    } finally {
      state.busy = false
    }
  }

  async function runBootstrap() {
    state.status = STATO.VERIFICA
    state.bootstrapError = null
    try {
      setAuthenticated(api.parseAuthenticated(await api.fetchMe()))
    } catch (error) {
      clearIdentity()
      if (error instanceof HttpError && error.status === 401) {
        state.status = STATO.ANONIMO
      } else {
        state.status = STATO.ERRORE
        state.bootstrapError = errorKind(error)
      }
    }
  }

  /** Promessa condivisa: il primo chiamante avvia la verifica, gli altri la attendono. */
  function ensureBootstrap() {
    if (!bootstrapPromise) bootstrapPromise = exclusive(runBootstrap)
    return bootstrapPromise
  }

  /** Nuova verifica manuale, ammessa solo dopo un errore di verifica. */
  function retryBootstrap() {
    if (state.status !== STATO.ERRORE) return Promise.reject(new AuthStateError())
    bootstrapPromise = exclusive(runBootstrap)
    return bootstrapPromise
  }

  function login(email, password) {
    return exclusive(async () => {
      if (state.status !== STATO.ANONIMO || state.loginUncertain) throw new AuthStateError()
      state.notice = null
      try {
        setAuthenticated(api.parseAuthenticated(await api.postLogin(email, password)))
        return { ok: true }
      } catch (error) {
        if (error instanceof HttpError && LOGIN_FAILURES[error.status]) {
          return { ok: false, kind: LOGIN_FAILURES[error.status], retryAfter: error.retryAfter }
        }
        // Rete, 5xx o 200 non conforme: il server potrebbe aver creato la sessione.
        state.loginUncertain = true
        return { ok: false, kind: 'incerto', retryAfter: error instanceof HttpError ? error.retryAfter : null }
      }
    })
  }

  /** Verifica manuale con /auth/me, ad esempio dopo un login dall'esito incerto. */
  function verifySession() {
    return exclusive(async () => {
      if (state.status !== STATO.ANONIMO) throw new AuthStateError()
      try {
        setAuthenticated(api.parseAuthenticated(await api.fetchMe()))
        return 'autenticato'
      } catch (error) {
        if (error instanceof HttpError && error.status === 401) {
          state.loginUncertain = false
          return 'anonimo'
        }
        return 'non-verificato'
      }
    })
  }

  async function completeLogout(token) {
    try {
      await api.postLogout(token)
      state.status = STATO.ANONIMO
      state.notice = AVVISO.LOGOUT_CONFERMATO
      return 'confermato'
    } catch (error) {
      if (error instanceof HttpError && error.status === 401) {
        state.status = STATO.ANONIMO
        state.notice = AVVISO.SESSIONE_ASSENTE
        return 'sessione-assente'
      }
      // Rete, 403, 5xx o risposta non conforme: la revoca non è dimostrata.
      state.status = STATO.LOGOUT_NON_CONFERMATO
      state.notice = null
      return 'non-confermato'
    }
  }

  /**
   * Le viste riservate vengono rimosse subito (identità azzerata, letture annullate);
   * resta solo il token CSRF necessario a completare questa richiesta.
   */
  function logout() {
    return exclusive(async () => {
      if (state.status !== STATO.AUTENTICATO) throw new AuthStateError()
      const token = csrfToken
      advanceEpoch()
      clearIdentity()
      state.notice = null
      state.status = STATO.DISCONNESSIONE
      return completeLogout(token)
    })
  }

  /**
   * Azione manuale dopo un logout non confermato: rilegge /auth/me e, se la sessione
   * è ancora valida, ritenta una sola volta il logout con il CSRF recuperato.
   * L'identità riletta non viene pubblicata: le viste private restano chiuse.
   */
  function recoverLogout() {
    return exclusive(async () => {
      if (state.status !== STATO.LOGOUT_NON_CONFERMATO) throw new AuthStateError()
      let token
      try {
        token = api.parseAuthenticated(await api.fetchMe()).csrfToken
      } catch (error) {
        if (error instanceof HttpError && error.status === 401) {
          state.status = STATO.ANONIMO
          state.notice = AVVISO.SESSIONE_ASSENTE
          return 'sessione-assente'
        }
        return 'non-confermato'
      }
      return completeLogout(token)
    })
  }

  /** 401 su risorsa protetta: agisce solo se riguarda la sessione ancora attuale. */
  function sessionLost(epoch) {
    if (epoch !== state.epoch || state.status !== STATO.AUTENTICATO) return
    advanceEpoch()
    clearIdentity()
    // L'avviso precede il cambio di stato: il watcher del router lo legge in modo sincrono.
    state.notice = AVVISO.SESSIONE_SCADUTA
    state.status = STATO.ANONIMO
  }

  /**
   * Richiesta verso un'API protetta, legata all'epoca corrente. Le letture usano il
   * segnale della sessione e vengono annullate al cambio di sessione; le scritture no,
   * perché l'annullamento lato client non annulla l'operazione sul server.
   * 403, 409 e gli altri errori vengono solo propagati. Durante un cambio password
   * dall'esito incerto le scritture sono rifiutate con PasswordUncertainError.
   */
  async function protectedRequest(options) {
    if (state.status !== STATO.AUTENTICATO) throw new AuthStateError()
    const write = !SAFE_METHODS.has((options.method ?? 'GET').toUpperCase())
    // Nessuna scrittura (e nessun CSRF inviato) finché l'esito del cambio password non è chiarito.
    if (write && state.passwordUncertain) throw new PasswordUncertainError()
    const epoch = state.epoch
    let signal = write ? undefined : readController.signal
    let cleanup = () => {}
    if (!write && options.signal) {
      // Una lettura può essere annullata dalla vista e dalla sessione, indipendentemente.
      const controller = new AbortController()
      const sources = new Set([readController.signal, options.signal])
      const abort = () => controller.abort()
      for (const source of sources) {
        if (source.aborted) abort()
        else source.addEventListener('abort', abort, { once: true })
      }
      signal = controller.signal
      cleanup = () => { for (const source of sources) source.removeEventListener('abort', abort) }
    }
    try {
      return await request({
        ...options,
        csrfToken: write ? csrfToken : null,
        signal,
        isCurrent: () => state.epoch === epoch,
      })
    } catch (error) {
      if (error instanceof HttpError && error.status === 401) sessionLost(epoch)
      throw error
    } finally {
      cleanup()
    }
  }

  /** Termina localmente la sessione e richiede un nuovo accesso, con l'avviso indicato. */
  function requireNewLogin(notice) {
    advanceEpoch()
    clearIdentity()
    // L'avviso precede il cambio di stato: il watcher del router lo legge in modo sincrono.
    state.notice = notice
    state.status = STATO.ANONIMO
  }

  /**
   * PUT /api/auth/password, serializzato con login, logout e bootstrap. Le risposte di
   * una sessione non più attuale vengono scartate prima di adottare CSRF o identità.
   * Nessun nuovo invio automatico: con esito incerto serve la verifica manuale.
   * @returns {Promise<{kind:string, retryAfter?:number|null}>}
   */
  function changePassword(passwordAttuale, nuovaPassword) {
    return exclusive(async () => {
      if (state.status !== STATO.AUTENTICATO || state.passwordUncertain) throw new AuthStateError()
      const epoch = state.epoch
      const user = state.user
      let result
      try {
        result = api.parsePasswordChange(await api.putPassword(csrfToken, passwordAttuale, nuovaPassword, {
          isCurrent: () => state.epoch === epoch,
        }))
      } catch (error) {
        if (error instanceof StaleResponseError || epoch !== state.epoch) return { kind: 'obsoleto' }
        if (error instanceof HttpError && error.status === 401) {
          sessionLost(epoch)
          return { kind: 'sessione-assente' }
        }
        if (error instanceof HttpError && PASSWORD_FAILURES[error.status]) {
          return { kind: PASSWORD_FAILURES[error.status], retryAfter: error.retryAfter }
        }
        if (error instanceof HttpError && error.status < 500) return { kind: 'rifiutata', retryAfter: error.retryAfter }
        // Rete, 5xx o 200 non conforme: la password potrebbe essere cambiata o no.
        state.passwordUncertain = true
        return { kind: 'incerto' }
      }
      if (epoch !== state.epoch) return { kind: 'obsoleto' }
      if (result.nuovoAccessoRichiesto) {
        requireNewLogin(AVVISO.PASSWORD_NUOVO_ACCESSO)
        return { kind: 'nuovo-accesso' }
      }
      // Sessione ruotata dal server: stessa identità, nuovo CSRF, nuova epoca.
      setAuthenticated({ user, csrfToken: result.csrfToken })
      return { kind: 'aggiornata' }
    })
  }

  /**
   * Verifica manuale dopo un cambio password dall'esito incerto. Un /me riuscito conferma
   * solo che la sessione è attiva, non se la password sia cambiata; un 401 richiede un
   * nuovo accesso.
   */
  function verifyAfterPasswordChange() {
    return exclusive(async () => {
      if (state.status !== STATO.AUTENTICATO || !state.passwordUncertain) throw new AuthStateError()
      const epoch = state.epoch
      try {
        const identity = api.parseAuthenticated(await api.fetchMe({ isCurrent: () => state.epoch === epoch }))
        setAuthenticated(identity)
        return 'sessione-attiva'
      } catch (error) {
        if (error instanceof StaleResponseError || epoch !== state.epoch) return 'obsoleto'
        if (error instanceof HttpError && error.status === 401) {
          requireNewLogin(AVVISO.PASSWORD_INCERTA_NUOVO_ACCESSO)
          return 'sessione-assente'
        }
        return 'non-verificato'
      }
    })
  }

  function clearNotice() {
    state.notice = null
  }

  return {
    state: readonly(state),
    capabilities,
    ensureBootstrap,
    retryBootstrap,
    login,
    verifySession,
    logout,
    recoverLogout,
    protectedRequest,
    changePassword,
    verifyAfterPasswordChange,
    clearNotice,
  }
}
