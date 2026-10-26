<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

// Notifiche chiudibili: temporanee solo per informazioni semplici. Mai usarle per eliminare blocchi o recuperi.
const props = defineProps({
  temporanea: { type: Boolean, default: true },
  nomeChiusura: { type: String, default: 'Chiudi avviso' },
  notifica: { type: [Object, String], required: true },
  destinazione: { type: Function, default: null },
})
const emit = defineEmits(['close'])
const visibile = ref(true)
const radice = ref(null)
const chiudi = ref(null)
let timer = null
let residuo = 5000
let avvio = 0
let puntatore = false
let focus = false
let generazione = 0
let montata = true

function sospendi() {
  if (timer === null) return
  clearTimeout(timer)
  timer = null
  residuo = Math.max(0, residuo - (Date.now() - avvio))
}
function riprendi() {
  if (!props.temporanea || !montata || !visibile.value || puntatore || focus || timer !== null) return
  avvio = Date.now()
  timer = setTimeout(() => {
    timer = null
    visibile.value = false
    emit('close')
  }, residuo)
}
function puntatoreDentro(dentro) {
  puntatore = dentro
  if (dentro) sospendi()
  else riprendi()
}
function focusDentro(event) {
  focus = event.type === 'focusin' || !!radice.value?.contains(event.relatedTarget)
  if (focus) sospendi()
  else riprendi()
}
async function chiusuraManuale() {
  const mia = generazione
  const recupera = document.activeElement === chiudi.value
  const area = radice.value?.closest('section, article, main') ?? document.querySelector('#contenuto')
  const destinazione = props.destinazione?.() ?? area?.querySelector('h1, h2, [tabindex="-1"]') ?? area
  sospendi()
  visibile.value = false
  await nextTick()
  if (!montata || mia !== generazione) return
  const attivo = document.activeElement
  if (recupera && destinazione?.isConnected && (!attivo || attivo === document.body || !attivo.isConnected)) {
    if (!destinazione.hasAttribute('tabindex')) destinazione.setAttribute('tabindex', '-1')
    destinazione.focus()
  }
  emit('close')
}
watch([() => props.notifica, () => props.temporanea], () => {
  generazione += 1
  sospendi()
  residuo = 5000
  visibile.value = true
  // Un messaggio sostitutivo non sottrae focus e mantiene la pausa se il controllo è ancora attivo.
  riprendi()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { montata = false; generazione += 1; sospendi() })
</script>

<template>
  <div v-if="visibile" ref="radice" class="notice notifica-informativa" role="status"
       @pointerenter="puntatoreDentro(true)" @pointerleave="puntatoreDentro(false)"
       @focusin="focusDentro" @focusout="focusDentro">
    <slot />
    <button ref="chiudi" type="button" class="notifica-chiudi" :aria-label="nomeChiusura" @click="chiusuraManuale">
      <span aria-hidden="true">×</span>
    </button>
  </div>
</template>

<style scoped>
.notifica-informativa { position: relative; padding-right: 3.5rem; overflow-wrap: anywhere; }
.notifica-chiudi { position: absolute; top: .25rem; right: .25rem; display: grid; place-items: center;
  width: 2.75rem; height: 2.75rem; border: 0; border-radius: .5rem; background: transparent;
  color: inherit; font-size: 1.5rem; cursor: pointer; }
.notifica-chiudi:hover { background: var(--color-primary-soft, #eef2ff); }
</style>
