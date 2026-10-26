<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchPagamenti } from '../api/pagamenti.js'
import { fetchPrenotazioniPaziente } from '../api/prenotazioni.js'
import { haStoricoPagamenti, pagamentoRimborsabile } from '../prenotazioni/economia.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import PagamentiSezione from './PagamentiSezione.vue'
import AnnullamentoSezione from './AnnullamentoSezione.vue'
import RefertoSezione from './RefertoSezione.vue'

// Azioni della card: i dati descrittivi completi restano nel dettaglio.
const props = defineProps({ prenotazione: { type: Object, required: true }, ticket: { type: Object, required: true },
  rimborsi: { type: Object, required: true }, ricaricaRimborsi: { type: Function, required: true } })
const emit = defineEmits(['aggiornata'])
const session = useSession()
const store = usePrenotazioni()
const p = ref(props.prenotazione)
const pagamentoAperto = ref(false), annullamentoAperto = ref(false)
const ticketP = ref(props.ticket)
const confermata = ref(true)
const erroreP = ref('')
const aggiornata = computed(() => confermata.value && store.prenotazioneValida(p.value.idPrenotazione, ticketP.value))
const storico = reactive({ stato: 'inattiva', voci: [], errore: '', aggiornata: false, richiesta: 0, completata: 0, ticket: null })
let seqP = 0, seq = 0
let vivo = true
onBeforeUnmount(() => { vivo = false; seq += 1; seqP += 1 })
const attuale = epoca => vivo && epoca === session.state.epoch && session.state.status === STATO.AUTENTICATO

async function leggiStorico() {
  if (!haStoricoPagamenti(p.value.stato)) return
  const mia = ++seq, epoca = session.state.epoch, id = p.value.idPrenotazione
  storico.richiesta = mia
  storico.aggiornata = false
  storico.errore = ''
  if (storico.stato !== 'pronto') storico.stato = 'caricamento'
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchPagamenti(session.protectedRequest, id)
    if (!attuale(epoca) || seq !== mia) return
    if (!store.registraStorico(id, voci, ticket)) throw new Error('superata')
    Object.assign(storico, { voci, ticket, stato: 'pronto', aggiornata: true })
  } catch {
    if (!attuale(epoca) || seq !== mia) return
    storico.stato = 'errore'
    storico.errore = 'Dati non verificabili o superati. Aggiorna prima di procedere.'
  } finally {
    if (attuale(epoca) && seq === mia) storico.completata = mia
  }
}
async function ricarica(verifica = false) {
  const mia = ++seqP, epoca = session.state.epoch, id = p.value.idPrenotazione
  const paziente = session.state.user?.idUtente
  confermata.value = false
  erroreP.value = ''
  storico.aggiornata = false
  seq += 1
  const ticket = store.inizioLettura()
  try {
    const elenco = await fetchPrenotazioniPaziente(session.protectedRequest, { idPaziente: paziente })
    if (!attuale(epoca) || mia !== seqP) return false
    if (!store.prenotazioneValida(id, ticket)) throw new Error('superata')
    const nuova = elenco.find(x => x.idPrenotazione === id)
    if (!nuova) throw new Error('assente')
    p.value = nuova
    ticketP.value = ticket
    confermata.value = true
    emit('aggiornata', nuova)
    if (verifica) store.annullamentoVerificato(id, paziente)
    leggiStorico()
    if (nuova.stato === 'NON_EROGATA') props.ricaricaRimborsi()
    return true
  } catch {
    if (!attuale(epoca) || mia !== seqP) return false
    erroreP.value = 'La prenotazione non è verificabile o non risulta nell’elenco corrente. Le azioni sono sospese.'
    return false
  }
}
function recupera(risorse) {
  if (risorse.includes('prenotazione') || !aggiornata.value) return ricarica()
  return leggiStorico()
}
const pagamentoRimborso = computed(() => pagamentoRimborsabile(p.value, {
  ...storico, aggiornata: storico.aggiornata && store.storicoValido(p.value.idPrenotazione, storico.ticket),
}, props.rimborsi))
const rimborsoSuperato = computed(() => props.rimborsi.stato === 'pronto' && storico.voci.some(x =>
  x.stato === 'PAGATO' && x.modalita === 'ONLINE' && !store.rimborsiValidi(x.idPagamento, props.rimborsi.ticket)))
const rimborsoValido = computed(() => {
  const x = pagamentoRimborso.value, id = p.value.idPrenotazione
  return x && aggiornata.value && store.rimborsiValidi(x.idPagamento, props.rimborsi.ticket)
    && !store.rimborsoBloccato(id) && !store.rimborsoNoto(x.idPagamento) && !store.operazioneInCorso(id)
    && !store.altraIncertezza(id, 'rimborso') && !session.state.passwordUncertain && !session.state.busy
})
watch(() => session.state.epoch, () => { seq += 1; seqP += 1; confermata.value = false; storico.aggiornata = false }, { flush: 'sync' })
onMounted(leggiStorico)
</script>

<template>
  <div class="azioni-prenotazione" data-azioni-prenotazione>
    <div v-if="erroreP" class="notice error" role="alert">
      <p>{{ erroreP }}</p><button type="button" class="button secondary" @click="ricarica()">Riprova</button>
    </div>
    <div v-else-if="!aggiornata" class="notice warning" role="status">
      <p>Prenotazione in aggiornamento o superata: azioni sospese.</p>
      <button v-if="confermata" type="button" class="button secondary" @click="ricarica()">Aggiorna prenotazione</button>
    </div>
    <PagamentiSezione v-if="haStoricoPagamenti(p.stato)" :prenotazione="p" :storico="storico" :prenotazione-aggiornata="aggiornata" compatto :incompatibile="annullamentoAperto" @fase="pagamentoAperto = $event" @rileggi="recupera" />
    <AnnullamentoSezione :prenotazione="p" :storico="storico" :aggiornata="aggiornata" :ticket="ticketP" :ricarica="ricarica" compatto :incompatibile="pagamentoAperto" @fase="annullamentoAperto = $event" />
    <template v-if="p.stato === 'NON_EROGATA'">
      <RouterLink v-if="rimborsoValido" class="button azione-rimborso" :to="{ path: `/paziente/prenotazioni/${p.idPrenotazione}`, hash: '#titolo-rimborso' }">Chiedi rimborso</RouterLink>
      <div v-else-if="rimborsi.stato === 'errore' || rimborsoSuperato" class="notice error" role="alert">
        <p>Requisiti del rimborso non verificabili.</p>
        <button type="button" class="button secondary" @click="ricaricaRimborsi">Aggiorna requisiti rimborso</button>
      </div>
      <p v-else-if="rimborsi.stato === 'caricamento'" role="status">Verifica dei rimborsi…</p>
    </template>
    <RefertoSezione v-if="p.stato === 'EFFETTUATA'" :key="`referto-${p.idPrenotazione}`" :id-prenotazione="p.idPrenotazione" compatto />
    <RouterLink class="button azione-neutra" :to="`/paziente/prenotazioni/${p.idPrenotazione}`">Dettagli<span class="visually-hidden"> della prenotazione n. {{ p.idPrenotazione }}</span></RouterLink>
  </div>
</template>

<style scoped>
.azioni-prenotazione {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-rows: auto;
  gap: .75rem;
  min-width: 0;
  width: 100%;
  align-items: stretch;
}
/* Mantiene i componenti e i riferimenti di focus: solo i contenitori compatti
   partecipano alla griglia comune. Avvisi e conferme restano gruppi autonomi. */
.azioni-prenotazione > section,
.azioni-prenotazione :deep(.annullamento-compatto),
.azioni-prenotazione :deep(.pagamenti-compatti > div:not(.notice):not(.confirm):not([role])),
.azioni-prenotazione :deep(.annullamento-compatto > div:not(.confirm)),
.azioni-prenotazione :deep(.pagamenti-compatti > .items),
.azioni-prenotazione :deep(.pagamenti-compatti > .items > .card) { display: contents; }
.azioni-prenotazione :deep(.pagamenti-compatti:not(:has(.button, .notice, .confirm, [role="status"]:not(.visually-hidden), [role="alert"]))) { display: none; }
/* I contenuti di lunghezza variabile precedono sempre la fila dei comandi. */
.azioni-prenotazione :deep(.notice),
.azioni-prenotazione :deep(.confirm),
.azioni-prenotazione :deep([role="status"]:not(.visually-hidden)),
.azioni-prenotazione :deep(section > p:not(.visually-hidden)) {
  grid-column: 1 / -1;
  order: 0;
  min-inline-size: 0;
  max-inline-size: none;
}
.azioni-prenotazione :deep(.confirm) { inline-size: 100%; }
.azioni-prenotazione :deep(.button) {
  display: flex;
  align-items: center;
  justify-content: center;
  order: 1;
  inline-size: 100%;
  min-inline-size: 0;
  min-block-size: 2.5rem;
  block-size: auto;
  padding: .375rem .75rem;
  line-height: 1.35;
  text-align: center;
  white-space: normal;
  overflow-wrap: anywhere;
}
/* I pulsanti di una conferma o di recupero non ereditano l'altezza del pannello. */
.azioni-prenotazione :deep(:is(.notice, .confirm, [role="status"]) .button) {
  inline-size: auto;
  min-block-size: 2.75rem;
  align-self: start;
  flex: 1 1 12rem;
}
.azioni-prenotazione :deep(.confirm .actions) { display: flex; flex-wrap: wrap; gap: .75rem; align-items: start; }
@container card-prenotazione (max-width: 40rem) {
  .azioni-prenotazione { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@container card-prenotazione (max-width: 26rem) {
  .azioni-prenotazione { grid-template-columns: minmax(0, 1fr); }
}
/* Il download paziente resta verde petrolio; l'upload medico usa l'ambra. */
.azioni-prenotazione :deep(.referto-compatto .button.azione-referto:not(:disabled)) {
  color: #fff; background: #00665e; border-color: #00665e;
}
.azioni-prenotazione :deep(.referto-compatto .button.azione-referto:not(:disabled):hover) {
  color: #fff; background: #00514b; border-color: #00514b;
}
/* Palette delle sole card: il dettaglio e le altre pagine mantengono i propri stili. */
.azioni-prenotazione :deep(.button.azione-pericolo) {
  color: #fff; background: #b42318; border-color: #b42318;
}
.azioni-prenotazione :deep(.button.azione-pericolo:hover:not(:disabled):not([aria-disabled="true"])) {
  color: #fff; background: #912018; border-color: #912018;
}
.azioni-prenotazione > .button.azione-neutra {
  color: #1d2939; background: #e4e7ec; border-color: #98a2b3;
}
.azioni-prenotazione > .button.azione-neutra:hover:not([aria-disabled="true"]) {
  color: #1d2939; background: #d0d5dd; border-color: #667085;
}
.azioni-prenotazione :deep(.pagamenti-compatti .button.azione-neutra) {
  color: #083344; background: #a5f3fc; border-color: #0e7490;
}
.azioni-prenotazione :deep(.pagamenti-compatti .button.azione-neutra:hover:not([aria-disabled="true"])) {
  color: #083344; background: #67e8f9; border-color: #0e7490;
}
.azioni-prenotazione :deep(.button.azione-pericolo:is(:disabled, [aria-disabled="true"])) {
  opacity: 1; color: #fff; background: #805454; border-color: #805454; border-style: dashed; cursor: not-allowed;
}
.azioni-prenotazione > .button.azione-neutra[aria-disabled="true"],
.azioni-prenotazione :deep(.pagamenti-compatti .button.azione-neutra[aria-disabled="true"]) {
  opacity: 1; color: #475467; background: #e4e7ec; border-color: #667085; border-style: dashed; cursor: not-allowed;
}
.azioni-prenotazione :deep(.button:is(.azione-pericolo, .azione-neutra):focus-visible) {
  outline: .1875rem solid #101828; outline-offset: .1875rem;
}
</style>
