<script setup>
/** Stati di un elenco desk: caricamento, errore con «Riprova», lettura superata con aggiornamento manuale. */
defineProps({
  elenco: { type: Object, required: true },
  inAggiornamento: { type: Boolean, required: true },
  /** Lettura mostrata superata da un esito certo successivo (anche da un'altra pagina). */
  invalidata: { type: Boolean, default: false },
  /** Es. "le richieste". */
  oggetto: { type: String, required: true },
})
const emit = defineEmits(['rileggi'])
</script>

<template>
  <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento in corso…</p>
  <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
    <p>Non è stato possibile caricare {{ oggetto }}. {{ elenco.errore }}</p>
    <div class="actions"><button type="button" class="button" data-riprova @click="emit('rileggi')">Riprova</button></div>
  </div>
  <div v-else-if="elenco.stato === 'superata'" class="notice warning" role="status" data-elenco-superato>
    <p>L'elenco letto è stato superato da operazioni più recenti e non è stato usato.</p>
    <div class="actions"><button type="button" class="button" data-aggiorna-elenco @click="emit('rileggi')">Aggiorna l'elenco</button></div>
  </div>
  <template v-else>
    <p v-if="inAggiornamento" class="small" role="status" data-aggiornamento>Aggiornamento dell'elenco in corso: le azioni sono sospese.</p>
    <div v-if="invalidata" class="notice warning" role="status" data-elenco-invalidato>
      <p>L'elenco mostrato potrebbe non essere più aggiornato: un'operazione conclusa dopo la sua lettura riguarda queste risorse. Le azioni sono sospese finché non aggiorni l'elenco.</p>
      <div class="actions"><button type="button" class="button" @click="emit('rileggi')">Aggiorna l'elenco</button></div>
    </div>
    <div v-if="elenco.superata" class="notice warning" role="status" data-elenco-superato>
      <p>L'elenco mostrato non è aggiornato: l'ultima lettura è stata superata da operazioni più recenti e non è stata usata. Le azioni sono sospese.</p>
      <div class="actions"><button type="button" class="button" data-aggiorna-elenco @click="emit('rileggi')">Aggiorna l'elenco</button></div>
    </div>
  </template>
</template>
