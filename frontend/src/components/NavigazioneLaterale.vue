<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ACCOUNT, PUBBLICI, corrispondenza, gruppiConsentiti } from '../navigation/collegamenti.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'

/**
 * Navigazione laterale: gruppi delle capacità reali della sessione (definizione condivisa con
 * l'area riservata) e pagine pubbliche. La pagina corrente ha aria-current="page"; una pagina di
 * dettaglio evidenzia il collegamento del proprio elenco.
 */
const session = useSession()
const route = useRoute()

const autenticato = computed(() => session.state.status === STATO.AUTENTICATO)
const gruppi = computed(() => (autenticato.value ? gruppiConsentiti(session.capabilities.value) : []))
const tutti = computed(() => [...(autenticato.value ? [ACCOUNT[0]] : []), ...gruppi.value.flatMap((g) => g.link), ...PUBBLICI])

/** Collegamento corrente: corrispondenza esatta, altrimenti il percorso contenitore più lungo. */
const corrente = computed(() => {
  const percorso = route.path
  const esatto = tutti.value.find((l) => corrispondenza(percorso, l.to) === 'esatto')
  if (esatto) return { to: esatto.to, esatto: true }
  const sotto = tutti.value.filter((l) => corrispondenza(percorso, l.to) === 'sotto').sort((a, b) => b.to.length - a.to.length)[0]
  return sotto ? { to: sotto.to, esatto: false } : null
})
const attributi = (l) => ({
  class: { attivo: corrente.value?.to === l.to },
  'aria-current': corrente.value?.to === l.to && corrente.value.esatto ? 'page' : undefined,
})
</script>

<template>
  <div class="nav-corpo">
    <ul v-if="autenticato" class="nav-elenco" aria-label="Account">
      <li><RouterLink :to="ACCOUNT[0].to" v-bind="attributi(ACCOUNT[0])" exact-active-class="" active-class="">{{ ACCOUNT[0].breve }}</RouterLink></li>
    </ul>
    <div v-for="g in gruppi" :key="g.capacita" class="nav-gruppo" :data-gruppo="g.capacita">
      <p :id="`nav-gruppo-${g.capacita}`" class="nav-titolo">{{ g.profilo }}</p>
      <ul class="nav-elenco" :aria-labelledby="`nav-gruppo-${g.capacita}`">
        <li v-for="l in g.link" :key="l.to">
          <RouterLink :to="l.to" v-bind="attributi(l)" exact-active-class="" active-class="">{{ l.breve }}</RouterLink>
        </li>
      </ul>
    </div>
    <div class="nav-gruppo" data-gruppo="pubblici">
      <p id="nav-gruppo-pubblici" class="nav-titolo">Consultazione</p>
      <ul class="nav-elenco" aria-labelledby="nav-gruppo-pubblici">
        <li v-for="l in PUBBLICI" :key="l.to">
          <RouterLink :to="l.to" v-bind="attributi(l)" exact-active-class="" active-class="">{{ l.breve }}</RouterLink>
        </li>
      </ul>
    </div>
  </div>
</template>
