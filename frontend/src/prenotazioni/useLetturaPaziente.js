import { onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Letture delle viste riservate a un profilo (paziente in pagine paziente, medico in ).
 *
 * - Ogni lettura riceve un numero di sequenza: una risposta superata da una lettura più
 *   recente, arrivata dopo lo smontaggio o dopo un cambio di epoca viene scartata.
 * - Al cambio di epoca i dati della sessione precedente vengono azzerati subito; la nuova
 *   lettura parte dopo che la sessione ha completato la transizione, e solo se resta
 *   autenticata con la capacità richiesta. Senza quella capacità si va ad accesso negato.
 *
 * @param {'paziente'|'medico'} capacita
 * @param {{azzera: () => void, ricarica: () => void}} hooks
 */
export function useLetturaProfilo(capacita, { azzera, ricarica }) {
  const session = useSession()
  const router = useRouter()
  let sequenza = 0
  let smontato = false

  /** Identità dell'utente dalla sessione corrente, solo con la capacità richiesta; altrimenti null. */
  function idUtente() {
    if (session.state.status !== STATO.AUTENTICATO || !session.capabilities.value[capacita]) return null
    return session.state.user?.idUtente ?? null
  }

  const nuovaLettura = () => ++sequenza
  const attuale = (n) => n === sequenza && !smontato

  watch(() => session.state.epoch, () => {
    sequenza += 1
    azzera()
    // Login, rotazione e logout aggiornano identità e stato nello stesso passo sincrono che
    // incrementa l'epoca: la decisione si prende a transizione completata.
    queueMicrotask(() => {
      if (smontato || session.state.status !== STATO.AUTENTICATO) return
      if (!session.capabilities.value[capacita]) {
        router.replace({ name: 'accesso-negato' })
        return
      }
      ricarica()
    })
  }, { flush: 'sync' })

  onBeforeUnmount(() => {
    smontato = true
    sequenza += 1
  })

  return { session, idUtente, nuovaLettura, attuale, smontato: () => smontato }
}

/** Letture delle viste riservate al paziente: `idPaziente` è l'identità della sessione. */
export function useLetturaPaziente(hooks) {
  const { idUtente, ...resto } = useLetturaProfilo('paziente', hooks)
  return { ...resto, idPaziente: idUtente }
}
