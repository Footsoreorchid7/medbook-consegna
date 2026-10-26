<script setup>
import { ref } from 'vue'
import NotificaInformativa from './NotificaInformativa.vue'
import { AVVISO, STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

const session = useSession()
const recovering = ref(false)
const recoveryFailed = ref(false)

const MESSAGGI = {
  [AVVISO.SESSIONE_SCADUTA]: 'La sessione è scaduta o non è più valida. Accedi di nuovo per continuare.',
  [AVVISO.LOGOUT_CONFERMATO]: 'Disconnessione completata.',
  [AVVISO.SESSIONE_ASSENTE]: 'La sessione risultava già terminata.',
  [AVVISO.PASSWORD_NUOVO_ACCESSO]: 'Password aggiornata. Accedi di nuovo con la nuova password.',
  [AVVISO.PASSWORD_INCERTA_NUOVO_ACCESSO]:
    'La sessione non è più attiva e non è stato possibile stabilire se la password sia stata cambiata. Accedi di nuovo: prova prima con la nuova password e, se non funziona, con quella precedente.',
}

async function recover() {
  if (recovering.value) return
  recovering.value = true
  recoveryFailed.value = false
  try {
    recoveryFailed.value = (await session.recoverLogout()) === 'non-confermato'
  } catch {
    recoveryFailed.value = true
  } finally {
    recovering.value = false
  }
}
</script>

<template>
  <div aria-live="polite">
    <div v-if="session.state.status === STATO.DISCONNESSIONE" class="notice" role="status">
      <p>Disconnessione in corso…</p>
    </div>

    <div v-else-if="session.state.status === STATO.LOGOUT_NON_CONFERMATO" class="notice warning" role="alert">
      <p><strong>La disconnessione non è stata confermata dal server.</strong></p>
      <p>
        La sessione potrebbe essere ancora attiva in questo browser. Le pagine riservate restano chiuse
        e non è possibile accedere di nuovo finché la situazione non è chiarita.
      </p>
      <p v-if="recoveryFailed">Verifica non riuscita. Riprova più tardi.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="recovering || session.state.busy" @click="recover">
          {{ recovering ? 'Verifica in corso…' : 'Verifica e completa la disconnessione' }}
        </button>
      </div>
    </div>

    <NotificaInformativa v-else-if="[AVVISO.LOGOUT_CONFERMATO, AVVISO.SESSIONE_ASSENTE].includes(session.state.notice)"
                          :notifica="session.state.notice" @close="session.clearNotice()">
      <p>{{ MESSAGGI[session.state.notice] }}</p>
    </NotificaInformativa>

    <NotificaInformativa v-else-if="session.state.notice && MESSAGGI[session.state.notice]"
                         :notifica="session.state.notice" :temporanea="false" nome-chiusura="Chiudi avviso" @close="session.clearNotice()">
      <p>{{ MESSAGGI[session.state.notice] }}</p>
    </NotificaInformativa>
  </div>
</template>
