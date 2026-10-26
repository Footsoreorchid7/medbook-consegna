<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BootstrapErrorNotice from '../components/BootstrapErrorNotice.vue'
import { resolveSafeRedirect } from '../router/guards.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

const session = useSession()
const route = useRoute()
const router = useRouter()
const retrying = ref(false)
const failedAgain = ref(false)

async function retry() {
  if (retrying.value) return
  retrying.value = true
  try {
    await session.retryBootstrap()
    failedAgain.value = session.state.status === STATO.ERRORE
    if (!failedAgain.value) {
      // Le guardie rivalutano la destinazione con il nuovo stato (area o accesso).
      await router.replace(resolveSafeRedirect(router, route.query.redirect, null, '/'))
    }
  } catch {
    // Verifica già in corso.
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <h1>Verifica della sessione non riuscita</h1>
  <BootstrapErrorNotice :retrying="retrying" :failed-again="failedAgain" @retry="retry" />
</template>
