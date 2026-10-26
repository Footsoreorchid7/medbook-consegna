<script setup>
import { useSession } from '../session/useSession.js'

/**
 * Avviso di verifica della sessione non riuscita con azione manuale Riprova.
 * Solo presentazione: la vista che lo usa esegue il nuovo tentativo e decide cosa fare
 * dopo, senza tentativi automatici.
 */
defineProps({
  message: { type: String, default: 'Non è stato possibile verificare se hai una sessione attiva.' },
  retrying: { type: Boolean, default: false },
  failedAgain: { type: Boolean, default: false },
})
defineEmits(['retry'])

const session = useSession()

const DETTAGLI = {
  rete: 'Il server non è raggiungibile.',
  protocollo: 'Il server ha restituito una risposta non valida.',
  http: 'Il server ha restituito un errore.',
}
</script>

<template>
  <div class="notice error" role="alert">
    <p>{{ message }}</p>
    <p v-if="DETTAGLI[session.state.bootstrapError]">{{ DETTAGLI[session.state.bootstrapError] }}</p>
    <p v-if="failedAgain">Anche l'ultimo tentativo non è riuscito.</p>
    <div class="actions">
      <button type="button" class="button" :disabled="retrying || session.state.busy" @click="$emit('retry')">
        {{ retrying ? 'Verifica in corso…' : 'Riprova' }}
      </button>
    </div>
  </div>
</template>
