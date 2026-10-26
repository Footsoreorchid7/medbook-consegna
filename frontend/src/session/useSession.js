import { inject } from 'vue'

export const SESSION_KEY = Symbol('medbook-session')

export function useSession() {
  const session = inject(SESSION_KEY, null)
  if (!session) throw new Error('Sessione non disponibile.')
  return session
}
