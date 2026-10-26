import { onBeforeUnmount, readonly, ref } from 'vue'

/**
 * Conto alla rovescia per Retry-After. Disabilita un'azione fino alla scadenza;
 * non la ripete: al termine l'utente può soltanto riprovare manualmente.
 */
export function useRetryAfter() {
  const remaining = ref(0)
  let timer = null
  let disposed = false

  function stop() {
    if (timer !== null) clearInterval(timer)
    timer = null
  }

  function start(seconds) {
    // Dopo lo smontaggio nessun nuovo timer: un esito tardivo non può riattivarlo.
    if (disposed) return
    stop()
    remaining.value = Number.isSafeInteger(seconds) && seconds > 0 ? seconds : 0
    if (remaining.value === 0) return
    timer = setInterval(() => {
      remaining.value = Math.max(0, remaining.value - 1)
      if (remaining.value === 0) stop()
    }, 1000)
  }

  onBeforeUnmount(() => {
    disposed = true
    stop()
  })
  return { remaining: readonly(remaining), start }
}
