<script setup>
import { computed, nextTick, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRetryAfter } from '../composables/useRetryAfter.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import { minimoNuovaPassword, passwordError } from '../utils/validazione.js'

const session = useSession()
const { remaining, start } = useRetryAfter()

const campi = reactive({ attuale: '', nuova: '', conferma: '' })
const errori = ref({})
const pending = ref(false)
const verifying = ref(false)
// { tipo: 'successo'|'errore'|'info', testo }
const avviso = ref(null)
const avvisoBox = ref(null)

const minimo = computed(() => minimoNuovaPassword(session.state.user))
const incerto = computed(() => session.state.passwordUncertain)
const canSubmit = computed(() => session.state.status === STATO.AUTENTICATO && !incerto.value
  && !session.state.busy && !pending.value && remaining.value === 0)

const ESITI = {
  'non-valida': (min) => `Dati non accettati: la nuova password deve avere almeno ${min} caratteri (massimo 1024) ed essere diversa da quella attuale.`,
  // 403 non è interpretato: può essere password attuale errata o richiesta rifiutata per sicurezza.
  rifiutata: () => "Operazione non consentita. La password attuale potrebbe non essere corretta, oppure la richiesta è stata rifiutata per la sicurezza della sessione. Controlla la password attuale; se il problema persiste, esci e accedi di nuovo.",
  conflitto: () => 'Operazione non disponibile per questo account.',
  'troppi-tentativi': () => 'Troppi tentativi di cambio password.',
  obsoleto: () => "La sessione è cambiata durante l'operazione: l'esito non è stato applicato.",
}

function svuota() {
  campi.attuale = ''
  campi.nuova = ''
  campi.conferma = ''
}

async function mostra(tipo, testo) {
  avviso.value = { tipo, testo }
  await nextTick()
  avvisoBox.value?.focus()
}

function valida() {
  const e = {}
  if (campi.attuale === '') e.attuale = 'Indica la password attuale.'
  const errNuova = passwordError(campi.nuova, minimo.value)
  if (errNuova) e.nuova = errNuova
  else if (campi.nuova === campi.attuale) e.nuova = 'La nuova password deve essere diversa da quella attuale.'
  if (!e.nuova && campi.conferma !== campi.nuova) e.conferma = 'Le due password non coincidono.'
  return e
}

async function submit() {
  if (!canSubmit.value) return
  avviso.value = null
  errori.value = valida()
  if (Object.keys(errori.value).length > 0) {
    svuota()
    await mostra('errore', 'Controlla i campi indicati.')
    return
  }
  pending.value = true
  let outcome
  try {
    outcome = await session.changePassword(campi.attuale, campi.nuova)
  } catch {
    outcome = { kind: 'occupato' }
  } finally {
    svuota()
    pending.value = false
  }
  switch (outcome.kind) {
    case 'aggiornata':
      await mostra('successo', 'Password aggiornata. La sessione è stata rinnovata.')
      break
    case 'nuovo-accesso':
    case 'sessione-assente':
      // La sessione locale è chiusa: il router porta all'accesso con l'avviso globale.
      break
    case 'incerto':
      break
    case 'occupato':
      await mostra('errore', "Un'altra operazione di autenticazione è in corso. Attendi il suo completamento.")
      break
    default:
      if (outcome.kind === 'troppi-tentativi' && outcome.retryAfter !== null) start(outcome.retryAfter)
      await mostra('errore', (ESITI[outcome.kind] ?? ESITI.rifiutata)(minimo.value))
  }
}

async function verifica() {
  if (verifying.value) return
  verifying.value = true
  try {
    const risultato = await session.verifyAfterPasswordChange()
    if (risultato === 'sessione-attiva') {
      await mostra('info', 'La sessione è attiva. Questo non indica se la password sia stata cambiata: se necessario ripeti l\'operazione indicando come password attuale quella che ritieni valida.')
    } else if (risultato === 'non-verificato') {
      await mostra('errore', 'Verifica non riuscita. Riprova più tardi.')
    }
  } catch {
    await mostra('errore', "Un'altra operazione di autenticazione è in corso. Attendi il suo completamento.")
  } finally {
    verifying.value = false
  }
}
</script>

<template>
  <template v-if="session.state.user">
    <h1>Cambio password</h1>
    <p><RouterLink to="/area">Torna all'area riservata</RouterLink></p>

    <div v-if="avviso" ref="avvisoBox" tabindex="-1"
         :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
         :role="avviso.tipo === 'errore' ? 'alert' : 'status'">
      <p>{{ avviso.testo }}</p>
      <p v-if="remaining > 0">Potrai riprovare tra {{ remaining }} secondi.</p>
    </div>

    <div v-if="incerto" class="notice warning" role="alert">
      <p><strong>Non è stato possibile stabilire se la password sia stata cambiata.</strong></p>
      <p>Prima di qualsiasi nuovo tentativo verifica lo stato della sessione.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="verifying || session.state.busy" @click="verifica">
          {{ verifying ? 'Verifica in corso…' : 'Verifica sessione' }}
        </button>
      </div>
    </div>

    <form v-else class="form" novalidate :aria-busy="pending ? 'true' : 'false'" @submit.prevent="submit">
      <div class="field">
        <label for="password-attuale">Password attuale</label>
        <input id="password-attuale" v-model="campi.attuale" type="password" autocomplete="current-password" required
               :aria-invalid="errori.attuale ? 'true' : undefined"
               :aria-describedby="errori.attuale ? 'errore-attuale' : undefined" />
        <p v-if="errori.attuale" id="errore-attuale" class="field-error">{{ errori.attuale }}</p>
      </div>
      <div class="field">
        <label for="password-nuova">Nuova password</label>
        <input id="password-nuova" v-model="campi.nuova" type="password" autocomplete="new-password" required
               :aria-invalid="errori.nuova ? 'true' : undefined"
               :aria-describedby="errori.nuova ? 'aiuto-nuova errore-nuova' : 'aiuto-nuova'" />
        <p id="aiuto-nuova" class="muted small">Almeno {{ minimo }} caratteri, massimo 1024. Gli spazi contano.</p>
        <p v-if="errori.nuova" id="errore-nuova" class="field-error">{{ errori.nuova }}</p>
      </div>
      <div class="field">
        <label for="password-conferma">Conferma nuova password</label>
        <input id="password-conferma" v-model="campi.conferma" type="password" autocomplete="new-password" required
               :aria-invalid="errori.conferma ? 'true' : undefined"
               :aria-describedby="errori.conferma ? 'errore-conferma' : undefined" />
        <p v-if="errori.conferma" id="errore-conferma" class="field-error">{{ errori.conferma }}</p>
      </div>
      <div>
        <button type="submit" class="button" :disabled="!canSubmit">{{ pending ? 'Aggiornamento in corso…' : 'Cambia password' }}</button>
      </div>
    </form>
  </template>
</template>
