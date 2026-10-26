<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchPrestazioni } from '../api/catalogo.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { useSession } from '../session/useSession.js'
import { formatEuro } from '../utils/money.js'

const session = useSession()

const stato = ref('caricamento')
const prestazioni = ref([])
const errore = ref('')
const erroreBox = ref(null)
let controller = null

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

/** Lettura legata all'epoca di sessione: una risposta di una sessione precedente è scartata. */
async function carica({ focus = false } = {}) {
  controller?.abort()
  const corrente = new AbortController()
  controller = corrente
  const epoca = session.state.epoch
  stato.value = 'caricamento'
  try {
    prestazioni.value = await fetchPrestazioni({
      signal: corrente.signal,
      isCurrent: () => controller === corrente && session.state.epoch === epoca,
    })
    stato.value = 'pronto'
  } catch (error) {
    if (controller !== corrente || error instanceof StaleResponseError) return
    errore.value = messaggio(error)
    stato.value = 'errore'
    if (focus) {
      await nextTick()
      erroreBox.value?.focus()
    }
  }
}

onMounted(() => carica())
// Cambio di sessione con la pagina aperta: una sola nuova lettura GET per epoca (nessun ciclo:
// la lettura non cambia l'epoca). Sostituisce subito la lettura precedente, che viene annullata.
watch(() => session.state.epoch, () => { if (controller) carica() }, { flush: 'sync' })
onBeforeUnmount(() => {
  const vecchio = controller
  controller = null
  vecchio?.abort()
})
</script>

<template>
  <h1>Prestazioni</h1>
  <p>Catalogo delle prestazioni erogate dal centro medico.</p>

  <p v-if="stato === 'caricamento'" role="status">Caricamento del catalogo…</p>

  <div v-else-if="stato === 'errore'" ref="erroreBox" class="notice error" role="alert" tabindex="-1">
    <p>Non è stato possibile caricare il catalogo. {{ errore }}</p>
    <div class="actions">
      <button type="button" class="button" @click="carica({ focus: true })">Riprova</button>
    </div>
  </div>

  <p v-else-if="prestazioni.length === 0" role="status">Nessuna prestazione disponibile al momento.</p>

  <template v-else>
    <ul class="items" aria-label="Elenco delle prestazioni">
      <li v-for="p in prestazioni" :key="p.idPrestazione" class="card" data-prestazione>
        <h2 class="item-title testo-dati">{{ p.nome }}</h2>
        <p v-if="p.descrizione" class="testo-dati">{{ p.descrizione }}</p>
        <dl class="facts">
          <div><dt>Durata</dt><dd>{{ p.durata }} minuti</dd></div>
          <div><dt>Prezzo</dt><dd>{{ formatEuro(p.prezzo) }}</dd></div>
        </dl>
        <p class="slot-action"><RouterLink class="button secondary" :to="{ name: 'disponibilita', query: { prestazioneId: String(p.idPrestazione) } }">Verifica disponibilità<span class="visually-hidden"> per {{ p.nome }}</span></RouterLink></p>
      </li>
    </ul>
    <p><RouterLink to="/disponibilita">Consulta le disponibilità</RouterLink></p>
  </template>
</template>
