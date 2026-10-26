<script setup>
import { STATI_PRENOTAZIONI_UI, stileStatoAgenda } from '../medico/statiAgenda.js'
</script>
<template>
  <ul class="legenda-prenotazioni" aria-label="Legenda degli stati">
    <li v-for="s in STATI_PRENOTAZIONI_UI" :key="s.stato" class="badge-prenotazione" :style="stileStatoAgenda(s.stato)">{{ s.stato === 'RICHIESTA' ? 'In attesa' : s.nome }}</li>
  </ul>
</template>
