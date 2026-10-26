<script setup>
import NotificaInformativa from './NotificaInformativa.vue'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { HttpError, NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { avviaDownload, fetchReferto, nomeFileSicuro, scaricaDocumentoReferto } from '../api/referti.js'
import { dataOra } from '../prenotazioni/economia.js'
import { useSession } from '../session/useSession.js'

/**
 * Referto della prenotazione per il paziente proprietario. Lettura indipendente: un errore qui
 * non nasconde prenotazione o pagamenti. 404 sui metadati significa referto non disponibile.
 * Vive in un solo contesto del dettaglio (rimontato a ogni cambio di ID o di epoca).
 */
const props = defineProps({ compatto: { type: Boolean, default: false }, idPrenotazione: { type: Number, required: true } })

const titoloId = props.compatto ? `titolo-referto-${props.idPrenotazione}` : 'titolo-referto'
const session = useSession()
// stato: 'caricamento' | 'assente' | 'pronto' | 'errore'
const lettura = reactive({ stato: 'caricamento', referto: null, errore: '' })
const scaricando = ref(false)
const avviso = ref(null)
const avvisoBox = ref(null)
let vivo = true
let sequenza = 0
onBeforeUnmount(() => { vivo = false; sequenza += 1 })

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

async function carica() {
  const mia = ++sequenza
  lettura.stato = 'caricamento'
  lettura.errore = ''
  try {
    const referto = await fetchReferto(session.protectedRequest, props.idPrenotazione)
    if (mia !== sequenza || !vivo) return
    lettura.referto = referto
    lettura.stato = referto ? 'pronto' : 'assente'
  } catch (error) {
    if (mia !== sequenza || !vivo || error instanceof StaleResponseError) return
    lettura.referto = null
    lettura.errore = messaggio(error)
    lettura.stato = 'errore'
  }
}

async function mostra(tipo, testo) {
  if (!vivo) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && vivo) avvisoBox.value?.focus()
}

/** Download manuale: nessun nuovo tentativo automatico in caso di errore. */
async function scarica() {
  if (scaricando.value || lettura.stato !== 'pronto') return
  const referto = lettura.referto
  scaricando.value = true
  avviso.value = null
  try {
    const blob = await scaricaDocumentoReferto(session.protectedRequest, props.idPrenotazione)
    if (!vivo) return
    avviaDownload(blob, nomeFileSicuro(referto.nomeFile, props.idPrenotazione))
    await mostra('successo', 'Download del referto avviato.')
  } catch (error) {
    if (!vivo || error instanceof StaleResponseError) return
    if (error instanceof HttpError && error.status === 404) await mostra('errore', 'Il documento del referto non è disponibile.')
    else if (error instanceof ProtocolError) await mostra('errore', 'Il documento ricevuto non è un PDF valido. Riprova più tardi.')
    else await mostra('errore', `Download non riuscito. ${messaggio(error)} Puoi riprovare.`)
  } finally {
    if (vivo) scaricando.value = false
  }
}

onMounted(carica)
</script>

<template>
  <section :class="['card', { 'referto-compatto': compatto }]" :aria-labelledby="titoloId" data-sezione="referto">
    <h2 :id="titoloId" tabindex="-1" :class="{ 'visually-hidden': compatto }">Referto</h2>
    <p v-if="lettura.stato === 'caricamento'" role="status">Caricamento del referto…</p>
    <p v-else-if="lettura.stato === 'assente'" :class="{ 'visually-hidden': compatto }" data-referto-assente>Referto non disponibile.</p>
    <div v-else-if="lettura.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile verificare la disponibilità del referto. {{ lettura.errore }}</p>
      <div class="actions"><button type="button" class="button secondary" @click="carica">Riprova</button></div>
    </div>
    <template v-else>
      <p v-if="!compatto">Referto caricato il {{ dataOra(lettura.referto.caricato) }}.</p>
      <NotificaInformativa v-if="avviso?.tipo === 'successo'" :notifica="avviso"
                           @close="avviso = null" data-avviso-referto>
        <p>{{ avviso.testo }}</p>
      </NotificaInformativa>
      <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
           :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-referto>
        <p>{{ avviso.testo }}</p>
      </div>
      <button type="button" class="button azione-referto" :disabled="scaricando" :aria-busy="scaricando ? 'true' : 'false'" @click="scarica">
        {{ scaricando ? 'Download in corso…' : compatto ? 'Scarica referto' : 'Scarica il referto (PDF)' }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.referto-compatto { padding: 0; border: 0; margin: 0; box-shadow: none; }
</style>
