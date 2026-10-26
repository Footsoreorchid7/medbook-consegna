<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { postRegister } from '../api/auth.js'
import { HttpError } from '../api/http.js'
import { useRetryAfter } from '../composables/useRetryAfter.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import { validaRegistrazione } from '../utils/validazione.js'

const session = useSession()
const router = useRouter()
const { remaining, start } = useRetryAfter()

const vuoto = () => ({ nome: '', cognome: '', email: '', password: '', conferma: '', codiceFiscale: '', dataNascita: '', telefono: '' })
const campi = reactive(vuoto())
const errori = ref({})
const pending = ref(false)
// esito: null | 'completata' | 'errore' | 'incerto'
const esito = ref(null)
const messaggio = ref('')
const riepilogo = ref(null)
const conferma = ref(null)
const verificando = ref(false)
const verificaFallita = ref(false)

// Validità degli effetti: ogni tentativo e lo smontaggio invalidano i risultati precedenti.
let tentativo = 0
let montato = true
onBeforeUnmount(() => {
  montato = false
  tentativo += 1
})

/**
 * La registrazione non crea sessioni ma non deve partire mentre lo stato di autenticazione
 * è irrisolto: solo sessione anonima, nessun login dall'esito incerto, nessuna operazione
 * di autenticazione in corso. Errore di verifica e logout non confermato restano bloccati.
 */
const sessioneLibera = () => session.state.status === STATO.ANONIMO && !session.state.loginUncertain && !session.state.busy
const disponibile = computed(sessioneLibera)
const canSubmit = computed(() => disponibile.value && !pending.value && remaining.value === 0)

const ETICHETTE = {
  nome: 'Nome', cognome: 'Cognome', email: 'Email', password: 'Password', conferma: 'Conferma password',
  codiceFiscale: 'Codice fiscale', dataNascita: 'Data di nascita', telefono: 'Telefono',
}
const describedBy = (campo, extra) => [errori.value[campo] ? `errore-${campo}` : null, extra].filter(Boolean).join(' ') || undefined

async function focusSu(target) {
  await nextTick()
  target.value?.focus()
}

function classifica(error) {
  if (error instanceof HttpError && error.status === 400) {
    return { esito: 'errore', messaggio: 'Alcuni dati non sono stati accettati. Controlla i campi e riprova.' }
  }
  if (error instanceof HttpError && error.status === 409) {
    // Messaggio generico: non si deduce quale dato sia già registrato.
    return { esito: 'errore', messaggio: 'Non è possibile completare la registrazione con i dati forniti.' }
  }
  if (error instanceof HttpError && error.status === 429) {
    return { esito: 'errore', messaggio: 'Troppi tentativi di registrazione.', retryAfter: error.retryAfter }
  }
  if (error instanceof HttpError && error.status < 500) {
    return { esito: 'errore', messaggio: 'Richiesta di registrazione non consentita.' }
  }
  // Rete, 5xx o successo non conforme: l'account potrebbe essere stato creato.
  return { esito: 'incerto', messaggio: '' }
}

async function submit() {
  // Controllo anche qui, non solo sul pulsante: l'invio può arrivare da tastiera o da script.
  if (!canSubmit.value || !sessioneLibera()) return
  esito.value = null
  messaggio.value = ''
  errori.value = validaRegistrazione(campi)
  // Le password del tentativo escono subito dal modulo: la pulizia non tocca un tentativo successivo.
  const payload = {
    nome: campi.nome, cognome: campi.cognome, email: campi.email, password: campi.password,
    codiceFiscale: campi.codiceFiscale, dataNascita: campi.dataNascita, telefono: campi.telefono,
  }
  campi.password = ''
  campi.conferma = ''
  // Istantanea dei soli campi non segreti del tentativo: al successo si azzera solo ciò
  // che appartiene ancora a questo tentativo, mai valori cambiati nel frattempo.
  const inviati = {
    nome: payload.nome, cognome: payload.cognome, email: payload.email,
    codiceFiscale: payload.codiceFiscale, dataNascita: payload.dataNascita, telefono: payload.telefono,
  }
  if (Object.keys(errori.value).length > 0) {
    await focusSu(riepilogo)
    return
  }
  const mio = ++tentativo
  const epoca = session.state.epoch
  const attuale = () => montato && mio === tentativo && session.state.epoch === epoca
  pending.value = true
  let risultato
  try {
    await postRegister(payload)
    risultato = { esito: 'completata', messaggio: '' }
  } catch (error) {
    risultato = classifica(error)
  } finally {
    if (montato && mio === tentativo) pending.value = false
  }
  // Risposta obsoleta (pagina lasciata, nuovo tentativo o sessione cambiata): nessun effetto.
  // Scartarla non annulla la registrazione sul server, che può essere comunque avvenuta.
  if (!attuale()) return
  esito.value = risultato.esito
  messaggio.value = risultato.messaggio
  if (risultato.esito === 'completata') {
    for (const [campo, valore] of Object.entries(inviati)) {
      if (campi[campo] === valore) campi[campo] = ''
    }
  }
  if (typeof risultato.retryAfter === 'number') start(risultato.retryAfter)
  await focusSu(risultato.esito === 'completata' ? conferma : riepilogo)
}

/** Recupero manuale dopo un login dall'esito incerto: riusa la verifica della sessione. */
async function verificaAccesso() {
  if (verificando.value) return
  verificando.value = true
  verificaFallita.value = false
  try {
    const risultato = await session.verifySession()
    if (!montato) return
    if (risultato === 'autenticato') await router.replace('/area')
    else verificaFallita.value = risultato === 'non-verificato'
  } catch {
    if (montato) verificaFallita.value = true
  } finally {
    if (montato) verificando.value = false
  }
}
</script>

<template>
  <h1>Registrazione</h1>

  <p v-if="session.state.status === STATO.INIZIALE || session.state.status === STATO.VERIFICA" role="status">
    Verifica della sessione in corso…
  </p>
  <div v-else-if="session.state.status === STATO.ANONIMO && session.state.loginUncertain" class="notice warning" role="alert">
    <p>Non è stato possibile stabilire l'esito dell'ultimo tentativo di accesso.</p>
    <p>Prima di registrarti verifica lo stato della sessione.</p>
    <p v-if="verificaFallita">Verifica non riuscita. Riprova più tardi.</p>
    <div class="actions">
      <button type="button" class="button" :disabled="verificando || session.state.busy" @click="verificaAccesso">
        {{ verificando ? 'Verifica in corso…' : 'Verifica stato di accesso' }}
      </button>
    </div>
  </div>
  <p v-else-if="session.state.status === STATO.ANONIMO && session.state.busy" role="status">
    Operazione di autenticazione in corso…
  </p>
  <div v-else-if="!disponibile" class="notice warning" role="status">
    <p>La registrazione è disponibile solo senza una sessione attiva.</p>
  </div>

  <div v-else-if="esito === 'completata'" ref="conferma" class="notice" role="status" tabindex="-1">
    <p><strong>Registrazione completata.</strong> L'accesso non è automatico: entra con l'email e la password scelte.</p>
    <div class="actions"><RouterLink class="button" to="/accesso">Vai all'accesso</RouterLink></div>
  </div>

  <template v-else>
    <p>Crea un account paziente. I campi contrassegnati da * sono obbligatori.</p>
    <div ref="riepilogo" tabindex="-1">
      <div v-if="Object.keys(errori).length" class="notice error" role="alert">
        <p>Controlla i seguenti campi:</p>
        <ul>
          <li v-for="(testo, campo) in errori" :key="campo"><a :href="`#${campo}`">{{ ETICHETTE[campo] }}</a>: {{ testo }}</li>
        </ul>
      </div>
      <div v-else-if="esito === 'errore'" class="notice error" role="alert">
        <p>{{ messaggio }}</p>
        <p v-if="remaining > 0">Potrai riprovare tra {{ remaining }} secondi.</p>
      </div>
      <div v-else-if="esito === 'incerto'" class="notice warning" role="alert">
        <p><strong>Non è stato possibile stabilire se la registrazione sia stata completata.</strong></p>
        <p>
          Prima di inviare di nuovo il modulo, prova ad accedere con l'email e la password scelte:
          se l'accesso riesce, l'account è stato creato.
        </p>
        <div class="actions"><RouterLink class="button secondary" to="/accesso">Prova ad accedere</RouterLink></div>
      </div>
    </div>

    <form class="form" novalidate :aria-busy="pending ? 'true' : 'false'" @submit.prevent="submit">
      <!-- Durante l'invio tutti i campi sono disabilitati; il gestore resta comunque protetto. -->
      <fieldset class="fieldset-form" :disabled="pending">
      <legend class="visually-hidden">Dati di registrazione</legend>
      <div class="field">
        <label for="nome">Nome *</label>
        <input id="nome" v-model="campi.nome" autocomplete="given-name" required
               :aria-invalid="errori.nome ? 'true' : undefined" :aria-describedby="describedBy('nome')" />
        <p v-if="errori.nome" id="errore-nome" class="field-error">{{ errori.nome }}</p>
      </div>
      <div class="field">
        <label for="cognome">Cognome *</label>
        <input id="cognome" v-model="campi.cognome" autocomplete="family-name" required
               :aria-invalid="errori.cognome ? 'true' : undefined" :aria-describedby="describedBy('cognome')" />
        <p v-if="errori.cognome" id="errore-cognome" class="field-error">{{ errori.cognome }}</p>
      </div>
      <div class="field">
        <label for="email">Email *</label>
        <input id="email" v-model="campi.email" type="email" autocomplete="email" inputmode="email" maxlength="255" required
               :aria-invalid="errori.email ? 'true' : undefined" :aria-describedby="describedBy('email')" />
        <p v-if="errori.email" id="errore-email" class="field-error">{{ errori.email }}</p>
      </div>
      <div class="field">
        <label for="password">Password *</label>
        <input id="password" v-model="campi.password" type="password" autocomplete="new-password" required
               :aria-invalid="errori.password ? 'true' : undefined" :aria-describedby="describedBy('password', 'aiuto-password')" />
        <p id="aiuto-password" class="muted small">Almeno 8 caratteri, massimo 1024. Gli spazi contano.</p>
        <p v-if="errori.password" id="errore-password" class="field-error">{{ errori.password }}</p>
      </div>
      <div class="field">
        <label for="conferma">Conferma password *</label>
        <input id="conferma" v-model="campi.conferma" type="password" autocomplete="new-password" required
               :aria-invalid="errori.conferma ? 'true' : undefined" :aria-describedby="describedBy('conferma')" />
        <p v-if="errori.conferma" id="errore-conferma" class="field-error">{{ errori.conferma }}</p>
      </div>
      <div class="field">
        <label for="codiceFiscale">Codice fiscale *</label>
        <input id="codiceFiscale" v-model="campi.codiceFiscale" autocomplete="off" autocapitalize="characters" spellcheck="false"
               maxlength="16" required
               :aria-invalid="errori.codiceFiscale ? 'true' : undefined" :aria-describedby="describedBy('codiceFiscale')" />
        <p v-if="errori.codiceFiscale" id="errore-codiceFiscale" class="field-error">{{ errori.codiceFiscale }}</p>
      </div>
      <div class="field">
        <label for="dataNascita">Data di nascita *</label>
        <input id="dataNascita" v-model="campi.dataNascita" type="date" autocomplete="bday" min="1000-01-01" required
               :aria-invalid="errori.dataNascita ? 'true' : undefined" :aria-describedby="describedBy('dataNascita')" />
        <p v-if="errori.dataNascita" id="errore-dataNascita" class="field-error">{{ errori.dataNascita }}</p>
      </div>
      <div class="field">
        <label for="telefono">Telefono (facoltativo)</label>
        <input id="telefono" v-model="campi.telefono" type="tel" autocomplete="tel" maxlength="20"
               :aria-invalid="errori.telefono ? 'true' : undefined" :aria-describedby="describedBy('telefono')" />
        <p v-if="errori.telefono" id="errore-telefono" class="field-error">{{ errori.telefono }}</p>
      </div>
      <div>
        <button type="submit" class="button" :disabled="!canSubmit">{{ pending ? 'Invio in corso…' : 'Registrati' }}</button>
      </div>
      </fieldset>
    </form>
    <p>Hai già un account? <RouterLink to="/accesso">Accedi</RouterLink></p>
  </template>
</template>
