<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import DashboardDesk from '../components/DashboardDesk.vue'
import { CARD_DESK } from '../desk/useDashboardDesk.js'
import IconaAccesso from '../components/IconaAccesso.vue'
import { gruppiConsentiti } from '../navigation/collegamenti.js'
import { useSession } from '../session/useSession.js'
const session = useSession()
const attivi = computed(() => gruppiConsentiti(session.capabilities.value))
const accessi = g => g.capacita === 'desk' ? g.link.filter(l => !CARD_DESK.some(c => c.to === l.to)) : g.link
</script>

<template>
  <template v-if="session.state.user">
    <header class="area-benvenuto">
      <h1>Area riservata</h1>
      <p class="area-saluto">Benvenuto, {{ session.state.user.nome }} {{ session.state.user.cognome }}.</p>
      <section aria-labelledby="titolo-profili">
        <h2 id="titolo-profili" class="area-profili-titolo">Profili attivi</h2>
        <ul v-if="attivi.length" class="area-profili">
          <li v-for="g in attivi" :key="g.capacita" :data-capacita="g.capacita" class="badge-stato badge-blu">{{ g.profilo }}</li>
        </ul>
        <p v-else>Nessun profilo operativo è associato a questo account.</p>
      </section>
    </header>
    <section v-for="g in attivi" :key="g.capacita" class="area-gruppo" :aria-labelledby="`titolo-area-${g.capacita}`">
      <h2 :id="`titolo-area-${g.capacita}`">{{ g.titolo }}</h2>
      <p class="muted area-descrizione">{{ g.descrizione }}</p>
      <DashboardDesk v-if="g.capacita === 'desk'" :collegamenti="g.link" />
      <ul class="area-accessi">
        <li v-for="l in accessi(g)" :key="l.to">
          <RouterLink class="accesso-rapido" :to="l.to">
            <span class="accesso-icona"><IconaAccesso :nome="l.icona" /></span>
            <span class="accesso-testi"><span class="accesso-titolo">{{ l.etichetta }}</span><span class="accesso-descrizione">{{ l.descrizione }}</span></span>
          </RouterLink>
        </li>
      </ul>
    </section>

  </template>
</template>

<style scoped>
.area-benvenuto { margin-block-end: 1.5rem; }
.area-benvenuto h1 { margin-block-end: .375rem; }
.area-saluto { font-size: 1.125rem; margin-block-end: .875rem; font-weight: 500; overflow-wrap: anywhere; }
.area-profili-titolo { font-size: .875rem; color: var(--color-muted); margin-block-end: .375rem; }
.area-profili { display: flex; flex-wrap: wrap; gap: .375rem; list-style: none; padding: 0; margin: 0; }
.area-gruppo { margin-block-end: 1.5rem; }
.area-gruppo h2 { margin-block-end: .25rem; }
.area-descrizione { margin-block-end: .75rem; }
.area-accessi { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr)); gap: .75rem; padding: 0; margin: 0; list-style: none; }
.area-accessi > li { min-width: 0; }
.accesso-rapido { display: flex; align-items: flex-start; gap: .75rem; min-height: 6.5rem; height: 100%; padding: 1rem; border: .0625rem solid var(--color-border); border-radius: .5rem; background: var(--color-surface); color: var(--color-text); text-decoration: none; box-shadow: 0 .125rem .25rem rgb(16 24 40 / 5%); }
.accesso-rapido:hover { border-color: var(--color-primary); background: var(--color-primary-soft); }
.accesso-rapido:active { box-shadow: inset 0 .125rem .25rem rgb(16 24 40 / 12%); }
.accesso-icona { display: grid; place-items: center; flex: none; width: 2.25rem; height: 2.25rem; border-radius: .5rem; color: var(--color-primary); background: var(--color-primary-soft); }
.accesso-icona svg { width: 1.375rem; height: 1.375rem; }
.accesso-testi { display: grid; gap: .375rem; min-width: 0; overflow-wrap: anywhere; }
.accesso-titolo { font-weight: 600; line-height: 1.3; }
.accesso-descrizione { color: var(--color-muted); font-size: .8125rem; line-height: 1.45; }
</style>
