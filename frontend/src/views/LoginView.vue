<script setup>
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BootstrapErrorNotice from '../components/BootstrapErrorNotice.vue'
import { useRetryAfter } from '../composables/useRetryAfter.js'
import { resolveSafeRedirect } from '../router/guards.js'
import { sceltaStore, idParametro } from '../prenotazioni/scelta.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

const session = useSession()
const route = useRoute()
const router = useRouter()
const { remaining, start } = useRetryAfter()

const email = ref('')
const password = ref('')
const pending = ref(false)
const verifying = ref(false)
const error = ref(null)
const offerVerify = ref(false)
const verifyFailed = ref(false)
const retryingBootstrap = ref(false)
const bootstrapFailedAgain = ref(false)
const errorBox = ref(null)

const MESSAGGI = {
  credenziali: 'Email o password non corrette.',
  'non-valida': 'Richiesta di accesso non valida. Controlla i dati inseriti.',
  rifiutata: 'Richiesta di accesso non consentita. Verifica lo stato della sessione prima di riprovare.',
  'troppi-tentativi': 'Troppi tentativi di accesso.',
  'non-disponibile': 'Servizio temporaneamente non disponibile.',
}

const status = computed(() => session.state.status)
const showForm = computed(() => status.value === STATO.ANONIMO && !session.state.loginUncertain)
const canSubmit = computed(() => showForm.value && !session.state.busy && !pending.value && remaining.value === 0)

function goToTarget() {
  const target = resolveSafeRedirect(router, route.query.redirect, session.capabilities.value)
  const destinazione = router.resolve(target)
  // Compatibilità con i vecchi link ricevuti prima del login: solo l'ID è una scelta,
  // mai un prezzo o un riepilogo attendibile. La nuova pagina lo rilegge dal server.
  if (destinazione.name === 'prenota' && session.capabilities.value.paziente) {
    const id = idParametro(destinazione.query.slot)
    if (id !== null) {
      sceltaStore(session).scegli({ idDisponibilita: id })
      return router.replace('/paziente/prenotazioni')
    }
  }
  return router.replace(target)
}

async function submit() {
  if (!canSubmit.value) return
  pending.value = true
  error.value = null
  offerVerify.value = false
  verifyFailed.value = false
  try {
    const outcome = await session.login(email.value, password.value)
    if (outcome.ok) {
      await goToTarget()
      return
    }
    if (outcome.kind !== 'incerto') {
      error.value = MESSAGGI[outcome.kind]
      offerVerify.value = outcome.kind === 'rifiutata'
      if (outcome.retryAfter !== null) start(outcome.retryAfter)
    }
  } catch {
    error.value = 'Un\'altra operazione di accesso è in corso. Attendi il suo completamento.'
  } finally {
    password.value = ''
    pending.value = false
  }
  if (error.value) {
    await nextTick()
    errorBox.value?.focus()
  }
}

async function verify() {
  if (verifying.value) return
  verifying.value = true
  verifyFailed.value = false
  try {
    const result = await session.verifySession()
    if (result === 'autenticato') {
      await goToTarget()
      return
    }
    if (result === 'anonimo') {
      error.value = null
      offerVerify.value = false
    } else {
      verifyFailed.value = true
    }
  } catch {
    verifyFailed.value = true
  } finally {
    verifying.value = false
  }
}

/**
 * Recupero manuale dopo un errore di verifica. Se la sessione risulta autenticata si
 * completa la navigazione verso la destinazione validata; con 401 resta il modulo di
 * accesso; con un nuovo errore resta l'avviso con la possibilità di riprovare.
 */
async function retryBootstrap() {
  if (retryingBootstrap.value) return
  retryingBootstrap.value = true
  try {
    await session.retryBootstrap()
    bootstrapFailedAgain.value = session.state.status === STATO.ERRORE
    if (session.state.status === STATO.AUTENTICATO) await goToTarget()
  } catch {
    // Verifica già in corso.
  } finally {
    retryingBootstrap.value = false
  }
}
</script>

<template>
  <h1>Accesso</h1>

  <p v-if="status === STATO.INIZIALE || status === STATO.VERIFICA" role="status">Verifica della sessione in corso…</p>

  <BootstrapErrorNotice
    v-else-if="status === STATO.ERRORE"
    message="Non è stato possibile verificare la sessione. Riprova prima di accedere."
    :retrying="retryingBootstrap"
    :failed-again="bootstrapFailedAgain"
    @retry="retryBootstrap"
  />

  <div v-else-if="status === STATO.ANONIMO && session.state.loginUncertain" class="notice warning" role="alert">
    <p>Non è stato possibile stabilire l'esito dell'ultimo tentativo di accesso.</p>
    <p>Verifica lo stato della sessione prima di inviare nuovamente le credenziali.</p>
    <p v-if="verifyFailed">Verifica non riuscita. Riprova più tardi.</p>
    <div class="actions">
      <button type="button" class="button" :disabled="verifying || session.state.busy" @click="verify">
        {{ verifying ? 'Verifica in corso…' : 'Verifica stato di accesso' }}
      </button>
    </div>
  </div>

  <section v-if="showForm" class="card" aria-labelledby="titolo-form">
    <h2 id="titolo-form" class="visually-hidden">Credenziali</h2>
    <div v-if="error" ref="errorBox" class="notice error" role="alert" tabindex="-1" id="errore-accesso">
      <p>{{ error }}</p>
      <p v-if="remaining > 0">Potrai riprovare tra {{ remaining }} secondi.</p>
      <p v-if="verifyFailed">Verifica non riuscita. Riprova più tardi.</p>
      <div v-if="offerVerify" class="actions">
        <button type="button" class="button secondary" :disabled="verifying || session.state.busy" @click="verify">
          {{ verifying ? 'Verifica in corso…' : 'Verifica sessione' }}
        </button>
      </div>
    </div>
    <form class="form" novalidate @submit.prevent="submit" :aria-busy="pending ? 'true' : 'false'">
      <div class="field">
        <label for="email">Email</label>
        <input
          id="email"
          v-model="email"
          name="email"
          type="email"
          autocomplete="username"
          inputmode="email"
          required
          :aria-describedby="error ? 'errore-accesso' : undefined"
        />
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input
          id="password"
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
          :aria-describedby="error ? 'errore-accesso' : undefined"
        />
      </div>
      <div>
        <button type="submit" class="button" :disabled="!canSubmit">
          {{ pending ? 'Accesso in corso…' : 'Accedi' }}
        </button>
      </div>
    </form>
    <p>Non hai un account? <RouterLink to="/registrazione">Registrati come paziente</RouterLink></p>
  </section>
</template>
