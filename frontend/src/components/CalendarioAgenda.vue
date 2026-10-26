<script setup>
import { computed, ref, watch } from 'vue'
import { celleMese, MESI, periodoMese, spostaMese } from '../medico/calendario.js'
import { STATI_AGENDA_UI as STATI, stileStatoAgenda } from '../medico/statiAgenda.js'
import { formatLocalDate } from '../utils/datetime.js'
const props = defineProps({ mese: { type: String, default: '' }, selezionato: { type: String, default: '' }, voci: { type: Array, default: () => [] }, aggiornato: Boolean, tipo: { type: String, default: 'agenda' }, navigazioneBloccata: Boolean })
const disponibilita = computed(() => props.tipo === 'disponibilita')
const idMese = computed(() => disponibilita.value ? 'mese-disponibilita' : 'mese-agenda')
const emit = defineEmits(['mese', 'giorno'])
const scelta = ref(props.mese)
watch(() => props.mese, value => { scelta.value = value })
const errore = ref('')
const celle = computed(() => celleMese(props.mese))
const intestazione = computed(() => props.mese ? `${MESI[Number(props.mese.slice(5)) - 1]} ${props.mese.slice(0, 4)}` : 'Scegli il mese')
const appuntamenti = (data) => props.voci.filter((v) => v.inizio.date === data)
const gruppi = (data) => STATI.map(s => ({ ...s, numero: appuntamenti(data).filter(a => a.stato === s.stato).length })).filter(s => s.numero)
function nomeGiorno(data) {
  const [year, month, day] = data.split('-').map(Number)
  return `${formatLocalDate({ year, month, day })}, ${props.aggiornato ? `${appuntamenti(data).length} ${disponibilita.value ? 'disponibilità' : 'appuntamenti'}${(disponibilita.value ? [] : gruppi(data)).map(s => `, ${s.nome}: ${s.numero}`).join('')}${!disponibilita.value && appuntamenti(data).some(a => a.indisponibilitaSegnalata) ? ', indisponibilità segnalata' : ''}` : 'dati non aggiornati'}`
}
function applica(value) {
  if (props.navigazioneBloccata) return
  if (!periodoMese(value)) { errore.value = 'Scegli un mese valido tra gennaio 1000 e novembre 9999.'; return }
  errore.value = ''
  scelta.value = value
  emit('mese', value)
}
function tastiera(event, data) {
  const delta = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[event.key]
  if (!delta) return
  event.preventDefault()
  const giorno = Number(data.slice(8)) + delta
  event.currentTarget.closest('.calendario').querySelector(`[data-giorno="${props.mese}-${String(giorno).padStart(2, '0')}"]`)?.focus()
}
</script>
<template>
  <section class="card calendario" :aria-labelledby="idMese">
    <h2 :id="idMese">{{ intestazione }}</h2>
    <p class="small">Il mese iniziale segue il calendario locale del dispositivo, non l'orologio della struttura. Gli orari e l'idoneità delle operazioni restano quelli del backend.</p>
    <form class="actions" @submit.prevent="applica(scelta)">
      <label :for="`${idMese}-input`">{{ disponibilita ? "Mese delle disponibilità" : "Mese dell’agenda" }}</label>
      <input :id="`${idMese}-input`" v-model="scelta" :disabled="navigazioneBloccata" type="month" min="1000-01" max="9999-11" required />
      <button class="button" type="submit" :disabled="navigazioneBloccata">Mostra mese</button>
    </form>
    <p v-if="errore" role="alert" class="field-error">{{ errore }}</p>
    <p v-if="disponibilita" class="small">Conteggi per data di inizio. Una disponibilità non indica che lo slot sia libero o prenotabile.</p>
    <template v-if="mese">
      <div class="actions navigazione-mese">
        <button class="button secondary" type="button" :disabled="navigazioneBloccata || !spostaMese(mese, -1)" aria-label="Mese precedente" @click="applica(spostaMese(mese, -1))">← Precedente</button>
        <button class="button secondary" type="button" :disabled="navigazioneBloccata || !spostaMese(mese, 1)" aria-label="Mese successivo" @click="applica(spostaMese(mese, 1))">Successivo →</button>
      </div>
      <div class="giorni" aria-hidden="true"><span v-for="g in ['Lun','Mar','Mer','Gio','Ven','Sab','Dom']" :key="g">{{ g }}</span></div>
      <div class="giorni" role="group" :aria-label="intestazione">
        <template v-for="(data, i) in celle" :key="data ?? `vuota-${i}`">
          <span v-if="!data" aria-hidden="true"></span>
          <button v-else class="giorno" type="button" :disabled="!aggiornato" :data-giorno="data" :aria-label="nomeGiorno(data)" :aria-pressed="selezionato === data" @click="emit('giorno', data)" @keydown="tastiera($event, data)">
            <strong>{{ Number(data.slice(8)) }}</strong>
            <span v-if="!aggiornato">…</span>
            <span v-else-if="disponibilita">{{ appuntamenti(data).length }}</span>
            <span v-else-if="!appuntamenti(data).length">0</span>
            <span v-for="g in aggiornato && !disponibilita ? gruppi(data) : []" :key="g.stato" :style="stileStatoAgenda(g.stato)" :class="['conteggio-stato', `stato-${g.colore}`]" aria-hidden="true">{{ g.breve }} {{ g.numero }}</span>
            <span v-if="!disponibilita && aggiornato && appuntamenti(data).some(a => a.indisponibilitaSegnalata)" class="indicatore" aria-hidden="true">Segn.</span>
          </button>
        </template>
      </div>
      <ul v-if="!disponibilita" class="legenda" aria-label="Legenda degli stati">
        <li v-for="s in STATI" :key="s.stato" :style="stileStatoAgenda(s.stato)" :class="['conteggio-stato', `stato-${s.colore}`]">{{ s.breve }} {{ s.nome }}</li>
      </ul>
      <p v-if="!disponibilita" class="small">Richiesta significa in attesa della conferma della segreteria. Il calendario mostra solo gli stati restituiti dall'agenda medico; gli appuntamenti annullati non sono esposti dall'API attuale.</p>
    </template>
  </section>
</template>
<style scoped>
.calendario { margin-block: 1rem; }
.giorni { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: .25rem; text-align: center; }
.navigazione-mese { margin-block: 1rem; }
.giorno { min-width: 0; min-height: 4.5rem; padding: .35rem .1rem; display: flex; flex-direction: column; align-items: center; gap: .2rem; background: white; color: #24324a; border: 1px solid #cbd3e2; border-radius: .5rem; cursor: pointer; }
.giorno:not(:disabled):hover { background: #eef2ff; }
.giorno:disabled { cursor: default; color: #667085; background: #f2f4f7; }
.giorno[aria-pressed='true'] { border: 2px solid #263ea5; background: #eef2ff; color: #263ea5; }
.giorno:focus-visible { outline: 3px solid #263ea5; outline-offset: 2px; }
.legenda { display: flex; flex-wrap: wrap; gap: .4rem; padding: 0; list-style: none; margin-block: .75rem; }
.conteggio-stato { background: var(--agenda-fondo); color: var(--agenda-testo); border-radius: .25rem; padding: .1rem .15rem; font-size: .7rem; line-height: 1.35; max-width: 100%; overflow-wrap: anywhere; }
.indicatore { font-size: .65rem; overflow-wrap: anywhere; }
@media(max-width: 30rem) { .giorno { min-height: 4rem; } .giorni { gap: .15rem; font-size: .8rem; } }
</style>
