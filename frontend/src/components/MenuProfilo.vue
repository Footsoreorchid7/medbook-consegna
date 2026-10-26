<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ACCOUNT, descrizioneProfili } from '../navigation/collegamenti.js'
import { useSession } from '../session/useSession.js'

/**
 * Pulsante del profilo con un piccolo menu a comparsa (schema «disclosure»: pulsante con
 * aria-expanded e un elenco di collegamenti esistenti, percorribile con Tab). Si chiude con
 * Escape (focus di nuovo sul pulsante), con un clic o un focus fuori dal menu e dopo una navigazione.
 */
const session = useSession()
const aperto = ref(false)
const radice = ref(null)
const pulsante = ref(null)

const utente = computed(() => session.state.user)
const profili = computed(() => descrizioneProfili(session.capabilities.value))

function chiudi({ focus = false } = {}) {
  if (!aperto.value) return
  aperto.value = false
  if (focus) nextTick(() => pulsante.value?.focus())
}
function commuta() { aperto.value = !aperto.value }
function tasto(e) {
  if (e.key === 'Escape' && aperto.value) {
    e.stopPropagation()
    chiudi({ focus: true })
  }
}
function focusFuori(e) {
  if (aperto.value && e.relatedTarget && !radice.value?.contains(e.relatedTarget)) chiudi()
}
function clicFuori(e) {
  if (aperto.value && !radice.value?.contains(e.target)) chiudi()
}
onMounted(() => document.addEventListener('click', clicFuori))
onBeforeUnmount(() => document.removeEventListener('click', clicFuori))

defineExpose({ chiudi })
</script>

<template>
  <div v-if="utente" ref="radice" class="profilo" @keydown="tasto" @focusout="focusFuori">
    <button ref="pulsante" type="button" class="profilo-pulsante" aria-controls="menu-profilo" :aria-expanded="aperto ? 'true' : 'false'"
            data-profilo @click="commuta">
      <span class="profilo-nome">{{ utente.nome }} {{ utente.cognome }}</span>
      <span class="profilo-ruolo">{{ profili ?? 'Account' }}</span>
    </button>
    <div v-show="aperto" id="menu-profilo" class="profilo-menu">
      <p class="profilo-intestazione"><span class="visually-hidden">Profili: </span>{{ profili ?? 'Nessun profilo operativo' }}</p>
      <ul aria-label="Account">
        <li v-for="l in ACCOUNT" :key="l.to"><RouterLink :to="l.to" @click="chiudi()">{{ l.breve }}</RouterLink></li>
      </ul>
    </div>
  </div>
</template>
