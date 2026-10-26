import { STATO } from '../session/session.js'

/** Rotte che non possono essere destinazione di ritorno dopo il login. */
const NON_RITORNABILI = new Set(['accesso', 'registrazione', 'errore-sessione', 'accesso-negato', 'non-trovata'])

export function isReserved(meta) {
  return meta.requiresAuth === true || typeof meta.capability === 'string'
}

/**
 * Destinazione di ritorno: solo percorsi interni riconosciuti dal router e, se sono
 * note le capacità, autorizzati. Altrimenti `fallback`.
 * @param {object|null} capabilities null = capacità non ancora note (le guardie ricontrollano)
 */
export function resolveSafeRedirect(router, raw, capabilities, fallback = '/area') {
  if (typeof raw !== 'string' || raw.length > 512 || !raw.startsWith('/') || raw.startsWith('//')
      || raw.includes('\\') || /[\u0000-\u001f\u007f]/.test(raw)) {
    return fallback
  }
  let resolved
  try {
    resolved = router.resolve(raw)
  } catch {
    return fallback
  }
  if (resolved.matched.length === 0 || NON_RITORNABILI.has(resolved.name)) return fallback
  if (capabilities) {
    if (typeof resolved.meta.capability === 'string' && capabilities[resolved.meta.capability] !== true) {
      return fallback
    }
  }
  return resolved.fullPath
}

/**
 * Guardia di navigazione, solo a supporto dell'interfaccia: il backend resta autorevole.
 * Ogni redirect porta a una rotta che, nello stesso stato, viene accettata: nessun ciclo.
 */
export function createGuard(session, router) {
  return async (to) => {
    await session.ensureBootstrap()
    const status = session.state.status
    const meta = to.meta

    if (meta.sessionError) {
      return status === STATO.ERRORE ? true : { path: '/' }
    }
    if (meta.guestOnly) {
      if (status !== STATO.AUTENTICATO) return true
      return resolveSafeRedirect(router, to.query.redirect, session.capabilities.value)
    }
    if (isReserved(meta)) {
      if (status === STATO.AUTENTICATO) {
        if (typeof meta.capability === 'string' && session.capabilities.value[meta.capability] !== true) {
          return { name: 'accesso-negato' }
        }
        return true
      }
      if (status === STATO.ERRORE) return { name: 'errore-sessione', query: { redirect: to.fullPath } }
      return { name: 'accesso', query: status === STATO.ANONIMO ? { redirect: to.fullPath } : {} }
    }
    return true
  }
}
