<script setup>
import { RouterLink } from 'vue-router'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

const session = useSession()
</script>

<template>
  <h1>Accesso negato</h1>
  <p>Il tuo account non dispone dei permessi necessari per questa pagina.</p>
  <p>
    <RouterLink v-if="session.state.status === STATO.AUTENTICATO" to="/area">Torna all'area riservata</RouterLink>
    <RouterLink v-else to="/">Torna alla pagina iniziale</RouterLink>
  </p>
</template>
