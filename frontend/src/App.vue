<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import MenuProfilo from './components/MenuProfilo.vue'
import NavigazioneLaterale from './components/NavigazioneLaterale.vue'
import SessionNotices from './components/SessionNotices.vue'
import { isReserved } from './router/guards.js'
import { STATO } from './session/session.js'
import { useSession } from './session/useSession.js'

const session = useSession()
const route = useRoute()
const router = useRouter()
const main = ref(null)
const menuPulsante = ref(null)
const profilo = ref(null)
/** Navigazione su schermi stretti: chiusa di norma, aperta dal pulsante «Menu». */
const menuAperto = ref(false)

const authenticated = computed(() => session.state.status === STATO.AUTENTICATO)
// Difesa aggiuntiva: una vista riservata non viene mai mostrata fuori da una sessione attiva.
const canRender = computed(() => !isReserved(route.meta) || authenticated.value)
/** La pagina di accesso è una scheda centrata, senza navigazione laterale. */
const senzaNavigazione = computed(() => route.name === 'accesso')

// Dopo ogni navigazione interna i menu si chiudono e il focus passa al contenuto principale.
const removeFocusHook = router.afterEach(async (to, from) => {
  menuAperto.value = false
  profilo.value?.chiudi()
  if (from.matched.length === 0) return
  await nextTick()
  main.value?.focus()
})
onBeforeUnmount(removeFocusHook)

function chiudiMenu() {
  menuAperto.value = false
  nextTick(() => menuPulsante.value?.focus())
}
function tastoMenu(e) {
  if (e.key === 'Escape' && menuAperto.value) chiudiMenu()
}

async function logout() {
  try {
    await session.logout()
  } catch {
    // Operazione già in corso o stato cambiato: gli avvisi di sessione riportano l'esito.
  }
}
</script>

<template>
  <a class="skip-link" href="#contenuto">Vai al contenuto</a>
  <header class="app-header">
    <div class="app-header-riga">
      <button v-if="!senzaNavigazione" ref="menuPulsante" type="button" class="menu-pulsante" aria-controls="navigazione"
              :aria-expanded="menuAperto ? 'true' : 'false'" data-menu @click="menuAperto = !menuAperto" @keydown="tastoMenu">
        <span class="menu-icona" aria-hidden="true"></span>Menu
      </button>
      <RouterLink class="brand" to="/">MedBook</RouterLink>
      <div class="header-azioni">
        <template v-if="authenticated">
          <MenuProfilo ref="profilo" />
          <button type="button" class="button secondary esci" :disabled="session.state.busy" @click="logout">Esci</button>
        </template>
        <template v-else-if="session.state.status === STATO.ANONIMO && !senzaNavigazione">
          <RouterLink class="button" to="/accesso">Accedi</RouterLink>
          <RouterLink class="button secondary" to="/registrazione">Registrati</RouterLink>
        </template>
      </div>
    </div>
  </header>
  <div :class="['app-corpo', { 'senza-navigazione': senzaNavigazione }]">
    <nav v-if="!senzaNavigazione" id="navigazione" :class="['app-sidebar', { aperta: menuAperto }]" aria-label="Navigazione principale"
         @keydown="tastoMenu">
      <NavigazioneLaterale />
    </nav>
    <main id="contenuto" ref="main" tabindex="-1">
      <div class="container">
        <SessionNotices />
        <RouterView v-if="canRender" />
      </div>
    </main>
  </div>
</template>
