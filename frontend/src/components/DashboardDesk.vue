<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { CARD_DESK, useDashboardDesk } from '../desk/useDashboardDesk.js'
import IconaAccesso from './IconaAccesso.vue'
const props = defineProps({ collegamenti: { type: Array, required: true } })
const { risultati, inCorso, ultimoControllo, aggiorna } = useDashboardDesk()
const card = computed(() => CARD_DESK.map(c => ({ ...c, link: props.collegamenti.find(l => l.to === c.to) })).filter(c => c.link))
const ora = valore => new Intl.DateTimeFormat('it-IT', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(valore))
</script>

<template>
  <section aria-label="Dashboard operativa desk" class="dashboard-desk" :aria-busy="inCorso ? 'true' : 'false'">
    <div class="dashboard-controlli">
      <button type="button" class="button secondary" :aria-disabled="inCorso ? 'true' : undefined" @click="aggiorna">Aggiorna</button>
      <p v-if="inCorso" class="small" role="status">Aggiornamento dei conteggi in corso…</p>
      <p v-if="ultimoControllo !== null" class="small" data-ultimo-controllo>Ultimo controllo: {{ ora(ultimoControllo) }}.</p>
    </div>
    <p class="small muted">Aggiornamento automatico ogni 60 secondi mentre questa pagina è visibile e al ritorno sulla scheda. Orari di aggiornamento del dispositivo.</p>
    <ul class="dashboard-card">
      <li v-for="c in card" :key="c.id">
        <RouterLink :to="c.destinazione ?? c.link.to" :class="['dashboard-accesso', `dashboard-${c.tono}`]" :data-conteggio="c.id">
          <span class="dashboard-titolo"><IconaAccesso :nome="c.link.icona" />{{ c.titolo }}</span>
          <span v-if="risultati[c.id].disponibile" class="dashboard-numero" data-valore>{{ risultati[c.id].valore }}</span>
          <span v-else-if="['iniziale', 'caricamento'].includes(risultati[c.id].stato)" class="dashboard-esito">Caricamento…</span>
          <span v-else class="dashboard-esito">Dato non disponibile</span>
          <span class="dashboard-descrizione">{{ c.descrizione }}</span>
          <span class="dashboard-copertura">{{ c.copertura }}</span>
          <span v-if="risultati[c.id].disponibile" class="dashboard-orario">Aggiornato alle {{ ora(risultati[c.id].aggiornato) }}</span>
          <span v-else-if="risultati[c.id].stato === 'pronto' || risultati[c.id].stato === 'superata'" class="dashboard-orario">Dati superati: usa Aggiorna per verificarli.</span>
        </RouterLink>
      </li>
    </ul>
    <p class="small muted">I conteggi derivano da letture successive, non da un unico snapshot. Il numero di disponibilità include date passate: l'idoneità alla configurazione viene verificata dal server.</p>
  </section>
</template>

<style scoped>
.dashboard-controlli { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem 1rem; margin-block: .75rem; }
.dashboard-controlli p { margin: 0; }
.dashboard-card { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); gap: .75rem; padding: 0; list-style: none; }
.dashboard-card li { min-width: 0; }
.dashboard-accesso { display: flex; flex-direction: column; gap: .5rem; height: 100%; padding: 1rem; border: 1px solid var(--dashboard-accento); border-block-start-width: .25rem; border-radius: .5rem; background: var(--dashboard-fondo); color: #1d2939; text-decoration: none; box-shadow: 0 .125rem .25rem rgb(16 24 40 / 6%); overflow-wrap: anywhere; }
.dashboard-accesso:hover { box-shadow: 0 .25rem .625rem rgb(16 24 40 / 15%); }
.dashboard-accesso:focus-visible { outline: 3px solid var(--color-primary); outline-offset: 3px; }
.dashboard-titolo { display: flex; gap: .5rem; align-items: flex-start; font-weight: 600; }
.dashboard-titolo svg { width: 1.25rem; height: 1.25rem; flex: none; }
.dashboard-numero { font-size: clamp(2rem, 3vw, 2.75rem); line-height: 1.1; font-weight: 700; color: var(--dashboard-accento); }
.dashboard-esito { font-size: 1rem; font-weight: 600; }
.dashboard-descrizione, .dashboard-copertura, .dashboard-orario { font-size: .8125rem; }
.dashboard-orario { margin-top: auto; }
.dashboard-ambra { --dashboard-fondo: #fffaeb; --dashboard-accento: #854a0e; }
.dashboard-rosso { --dashboard-fondo: #fef3f2; --dashboard-accento: #912018; }
.dashboard-blu { --dashboard-fondo: #eef2ff; --dashboard-accento: #263ea5; }
.dashboard-viola { --dashboard-fondo: #f3e8ff; --dashboard-accento: #6b218a; }
</style>
