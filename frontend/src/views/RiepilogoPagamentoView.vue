<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchPagamenti } from '../api/pagamenti.js'
import { fetchPrenotazioniPaziente, parseIdRisorsa } from '../api/prenotazioni.js'
import { ETICHETTE_MODALITA, dataOra } from '../prenotazioni/economia.js'
import { dataOrario, nomePrestazione } from '../prenotazioni/formato.js'
import { useLetturaPaziente } from '../prenotazioni/useLetturaPaziente.js'
import { formatEuro } from '../utils/money.js'

const route = useRoute()
const titolo = ref(null)
const riprovaButton = ref(null)
let ultimaLettura = 0
const lettura = reactive({ stato: 'caricamento', prenotazione: null, pagamento: null })
function azzera() { Object.assign(lettura, { stato: 'caricamento', prenotazione: null, pagamento: null }) }
const { session, idPaziente, nuovaLettura, attuale, smontato } = useLetturaPaziente({ azzera, ricarica: carica })
const id = computed(() => parseIdRisorsa(route.params.id))
const pagamentoId = computed(() => parseIdRisorsa(route.params.pagamentoId))
async function carica() {
  const mia = nuovaLettura()
  ultimaLettura = mia
  azzera()
  const paziente = idPaziente()
  if (id.value === null || pagamentoId.value === null) { lettura.stato = 'assente'; return }
  if (paziente === null) return
  const prenotazioneId = id.value
  const scelto = pagamentoId.value
  try {
    const [elenco, storico] = await Promise.all([
      fetchPrenotazioniPaziente(session.protectedRequest, { idPaziente: paziente }),
      fetchPagamenti(session.protectedRequest, prenotazioneId),
    ])
    if (!attuale(mia)) return
    const p = elenco.find(x => x.idPrenotazione === prenotazioneId)
    const pagamento = storico.find(x => x.idPagamento === scelto && x.stato === 'PAGATO')
    if (!p || !pagamento) { lettura.stato = 'assente'; return }
    Object.assign(lettura, { stato: 'pronto', prenotazione: p, pagamento })
  } catch {
    if (attuale(mia)) lettura.stato = 'errore'
  }
}
async function riprova() {
  const epoca = session.state.epoch
  const promessa = carica()
  const mia = ultimaLettura
  await promessa
  await nextTick()
  if (smontato() || epoca !== session.state.epoch || !attuale(mia)) return
  const attivo = document.activeElement
  if (attivo && attivo !== document.body && attivo.isConnected) return
  ;(lettura.stato === 'errore' ? riprovaButton.value : titolo.value)?.focus()
}
function stampa() { if (lettura.stato === 'pronto') window.print() }
watch(() => [route.params.id, route.params.pagamentoId], carica, { flush: 'sync' })
onMounted(carica)
</script>

<template>
  <div class="riepilogo-comandi">
    <RouterLink to="/paziente/prenotazioni">Torna alle mie prenotazioni</RouterLink>
  </div>
  <p v-if="lettura.stato === 'caricamento'" role="status">Caricamento del riepilogo…</p>
  <div v-else-if="lettura.stato === 'errore'" class="notice error" role="alert">
    <p>Non è stato possibile verificare i dati del pagamento.</p>
    <button ref="riprovaButton" type="button" class="button" @click="riprova">Riprova</button>
  </div>
  <div v-else-if="lettura.stato === 'assente'" ref="titolo" tabindex="-1" class="notice" role="status">Riepilogo non disponibile: prenotazione o pagamento pagato non trovati.</div>
  <template v-else>
    <article class="card riepilogo-documento" data-riepilogo-pagamento>
      <h1 ref="titolo" tabindex="-1">{{ lettura.pagamento.modalita === 'ONLINE' ? 'Riepilogo del pagamento simulato' : 'Riepilogo della registrazione del pagamento in sede' }}</h1>
      <p><strong>Documento dimostrativo non fiscale.</strong>
        <template v-if="lettura.pagamento.modalita === 'ONLINE'"> Nessuna transazione bancaria reale è stata eseguita.</template>
        <template v-else> L'applicazione registra il pagamento in sede; non esegue né certifica una transazione POS.</template>
      </p>
      <dl class="facts">
        <div><dt>Prenotazione</dt><dd>n. {{ lettura.prenotazione.idPrenotazione }}</dd></div>
        <div><dt>Pagamento</dt><dd>n. {{ lettura.pagamento.idPagamento }}</dd></div>
        <div><dt>Prestazione</dt><dd>{{ nomePrestazione(lettura.prenotazione) }}</dd></div>
        <div><dt>Appuntamento</dt><dd>{{ dataOrario(lettura.prenotazione.inizio, lettura.prenotazione.fine) }}</dd></div>
        <div><dt>Importo</dt><dd>{{ formatEuro(lettura.pagamento.importo) }}</dd></div>
        <div><dt>Modalità</dt><dd>{{ ETICHETTE_MODALITA[lettura.pagamento.modalita] }}</dd></div>
        <div><dt>Stato del pagamento</dt><dd>Pagato</dd></div>
        <div><dt>Data del pagamento</dt><dd>{{ dataOra(lettura.pagamento.pagato) }}</dd></div>
      </dl>
      <p class="small">Orari della struttura, fuso orario da confermare. Questo riepilogo non attesta l'esecuzione di eventuali rimborsi.</p>
    </article>
    <div class="actions riepilogo-comandi">
      <button type="button" class="button" @click="stampa">Stampa / Salva come PDF</button>
      <p class="small">Si apre la finestra di stampa del browser: scegli la stampante o il salvataggio come PDF.</p>
    </div>
  </template>
</template>

<style>
@media print {
  body:has([data-riepilogo-pagamento]) .app-header,
  body:has([data-riepilogo-pagamento]) .app-sidebar,
  body:has([data-riepilogo-pagamento]) .skip-link,
  body:has([data-riepilogo-pagamento]) .riepilogo-comandi,
  body:has([data-riepilogo-pagamento]) main > .container > :not(.riepilogo-documento) { display: none !important; }
  body:has([data-riepilogo-pagamento]) .app-corpo { display: block; }
  body:has([data-riepilogo-pagamento]) main,
  body:has([data-riepilogo-pagamento]) .container { width: 100%; max-width: none; padding: 0; margin: 0; }
  .riepilogo-documento { border: 0; box-shadow: none; color: #000; background: #fff; }
  .riepilogo-documento .facts { display: block; }
  .riepilogo-documento .facts > div { break-inside: avoid; margin-bottom: .75rem; }
}
</style>
