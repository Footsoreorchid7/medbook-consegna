<script setup>
import { computed, nextTick, ref } from 'vue'
import { AMBITO_DESK, useDesk } from '../desk/store.js'
import { OPERAZIONI_B8 } from '../desk/formato.js'

/**
 * Esiti incerti del desk per i tipi indicati, dallo store (sopravvivono alla navigazione).
 * La verifica è manuale; la pagina riceve l'esito con l'evento `verifica` e decide messaggio
 * e rilettura nel proprio contesto. `recupero` (facoltativo) indica le voci per cui offrire
 * anche il recupero tramite la lettura desk delle prenotazioni gestite (evento `recupera`).
 */
const props = defineProps({
  tipi: { type: Array, required: true },
  /** (x) => descrizione della risorsa interessata. */
  descrizione: { type: Function, required: true },
  /** (x) => true se la voce ammette il recupero tramite la lettura desk . */
  recupero: { type: Function, default: () => false },
})
const emit = defineEmits(['verifica', 'recupera'])
const store = useDesk()
const voci = computed(() => store.state.incerte.filter((x) => props.tipi.includes(x.tipo)))
const titolo = ref(null)

const OPERAZIONI = {
  conferma: 'Conferma', rifiuta: 'Rifiuto', annulla: 'Annullamento', 'annulla-segnalata': 'Annullamento',
  sostituisci: 'Sostituzione del medico', approva: 'Approvazione', respingi: 'Rifiuto', configura: 'Configurazione', ...OPERAZIONI_B8,
}

/**
 * Dopo la rimozione il controllo sparisce: se il focus è andato perso, va al titolo della sezione
 * o, se la sezione è vuota, al titolo della pagina (se focalizzabile) oppure al contenuto principale.
 */
async function rimuovi(x) {
  store.chiudiIncerta(x.chiave, store.state.owner)
  await nextTick()
  if (document.activeElement !== document.body) return
  ;(titolo.value ?? document.querySelector('main h1[tabindex]') ?? document.getElementById('contenuto'))?.focus()
}
</script>

<template>
  <section v-if="voci.length" class="card" aria-labelledby="titolo-esiti-desk" data-da-verificare>
    <h2 id="titolo-esiti-desk" ref="titolo" tabindex="-1">Esiti da verificare</h2>
    <div v-for="x in voci" :key="x.chiave" class="notice warning testo-dati" data-esito-incerto :data-chiave="x.chiave">
      <p><strong>{{ OPERAZIONI[x.tipo] }} dall'esito non determinato</strong>: {{ descrizione(x) }}.</p>
      <p v-if="x.esito === 'assente' && x.verificaSu === AMBITO_DESK">
        Una verifica non l'ha trovata tra le prenotazioni gestite del giorno dell'appuntamento: l'esito non può essere stabilito
        e l'assenza non indica che l'operazione non sia avvenuta.
      </p>
      <p v-else-if="x.esito === 'assente'">
        Una verifica non l'ha trovata tra le risorse in attesa: non è possibile stabilire quale operazione sia avvenuta né chi l'abbia eseguita.
      </p>
      <p v-else>Il tentativo non è stato ripetuto; altre operazioni sulla stessa risorsa sono bloccate fino alla verifica.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <div class="actions">
        <button v-if="x.esito !== 'assente'" type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="emit('verifica', x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica' }}
        </button>
        <button v-if="recupero(x)" type="button" class="button secondary" :disabled="x.verifica === 'in-corso'" data-recupero
                @click="emit('recupera', x)">Verifica sulla prenotazione n. {{ x.riepilogo.idPrenotazione }}</button>
        <button v-if="x.esito === 'assente'" type="button" class="button secondary" @click="rimuovi(x)">Rimuovi dall'elenco</button>
      </div>
    </div>
  </section>
</template>
