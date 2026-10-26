<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { STATI_ANNULLABILI } from '../api/prenotazioni.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useSession } from '../session/useSession.js'
import { dataOrario, etichettaStato, nomePrestazione } from '../prenotazioni/formato.js'
import NotificaInformativa from './NotificaInformativa.vue'

const props = defineProps({
  destinazioneFocus: { type: Function, default: null },
  incompatibile: { type: Boolean, default: false },
  prenotazione: { type: Object, required: true }, aggiornata: { type: Boolean, required: true },
  storico: { type: Object, required: true }, ticket: { type: Object, default: undefined },
  ricarica: { type: Function, required: true }, compatto: { type: Boolean, default: false },
})
const emit = defineEmits(['fase'])
const store = usePrenotazioni()
const session = useSession()
const p = computed(() => props.prenotazione)
const titoloAnnullamento = ref(null), titoloConferma = ref(null)
const bottoneAnnulla = ref(null), incertoBox = ref(null), avvisoBox = ref(null)
const fase = ref('nessuna'), avviso = ref(null), verificando = ref(false)
watch(fase, x => emit('fase', x !== 'nessuna'), { flush: 'sync' })
let contesto = {}
let vivo = true
const corrente = ctx => vivo && ctx === contesto
const riservaFocus = () => props.destinazioneFocus?.()
  ?? titoloConferma.value?.closest('[data-prenotazione]')?.querySelector('.item-title')
  ?? document.querySelector('main h1')
function focalizza(el) {
  if (!el?.isConnected) return
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1')
  el.focus()
}
watch([() => p.value.idPrenotazione, () => session.state.epoch], () => { contesto = {}; fase.value = 'nessuna'; avviso.value = null; verificando.value = false }, { flush: 'sync' })
onBeforeUnmount(() => { vivo = false; contesto = {}; emit('fase', false) })
const requisiti = computed(() => p.value.stato === 'RICHIESTA' || (props.storico.stato === 'pronto'
  && props.storico.aggiornata && (props.storico.ticket === undefined || store.storicoValido(p.value.idPrenotazione, props.storico.ticket))
  && !props.storico.voci.some(x => x.stato !== 'FALLITO') && !store.pagamentoAttivoNoto(p.value.idPrenotazione)))
const titoloId = props.compatto ? `titolo-annullamento-${p.value.idPrenotazione}` : 'titolo-annullamento'
const confermaId = props.compatto ? `titolo-conferma-annullamento-${p.value.idPrenotazione}` : 'titolo-conferma-annullamento'
const incerto = computed(() => (p.value ? store.annullamentoIncerto(p.value.idPrenotazione) : null))
const inVolo = computed(() => p.value !== null && store.state.annullamentiInCorso.includes(p.value.idPrenotazione))
const annullabile = computed(() => p.value !== null && STATI_ANNULLABILI.includes(p.value.stato)
  && !store.annullamentoBloccato(p.value.idPrenotazione) && requisiti.value)
/** Annullamento autorizzabile solo con la prenotazione confermata dall'ultima lettura. */
const annullamentoValido = computed(() => annullabile.value && props.aggiornata && requisiti.value && !props.incompatibile && !store.operazioneInCorso(p.value.idPrenotazione))
const puoInviare = computed(() => annullamentoValido.value && !inVolo.value && fase.value !== 'invio'
  && !store.operazioneInCorso(p.value.idPrenotazione) && !store.altraIncertezza(p.value.idPrenotazione, 'annulla')
  && !session.state.passwordUncertain && !session.state.busy)

async function mostra(ctx, tipo, testo) {
  if (!corrente(ctx)) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && corrente(ctx)) avvisoBox.value?.focus()
}

// Una conferma di annullamento aperta sui dati precedenti non resta utilizzabile: alla loro
// invalidazione viene chiusa e il focus torna al titolo della sezione.
watch(() => annullamentoValido.value, (valido) => {
  if (valido || fase.value !== 'conferma') return
  fase.value = 'nessuna'
  const ctx = contesto
  nextTick(() => {
    if (corrente(ctx) && document.activeElement === document.body) focalizza(titoloAnnullamento.value ?? riservaFocus())
  })
}, { flush: 'sync' })

async function apriConferma() {
  if (!puoInviare.value) return
  const ctx = contesto
  avviso.value = null
  fase.value = 'conferma'
  await nextTick()
  if (corrente(ctx)) titoloConferma.value?.focus()
}

/**
 * Chiude la conferma e, a DOM aggiornato, riporta il focus su «Annulla prenotazione» se
 * presente e abilitato, altrimenti su un titolo della scheda. Un cambio di ID o di epoca
 * nel frattempo apre un nuovo contesto: nessuno spostamento di focus.
 */
async function chiudiConferma() {
  const ctx = contesto
  const riserva = riservaFocus()
  fase.value = 'nessuna'
  await nextTick()
  if (!corrente(ctx)) return
  const bottone = bottoneAnnulla.value
  const bersaglio = bottone && bottone.isConnected && !bottone.disabled
    ? bottone
    : titoloAnnullamento.value ?? riserva
  focalizza(bersaglio)
}

const ESITI = {
  conflitto: "Non è stato possibile annullare la prenotazione: il suo stato è cambiato, l'appuntamento è già iniziato oppure risultano pagamenti associati. I dati sono stati riletti.",
  'non-trovata': 'La prenotazione non è stata trovata. I dati sono stati riletti.',
  'non-valida': 'Richiesta di annullamento non valida.',
  rifiutata: 'Operazione non consentita. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  'dati-superati': 'Dati superati: aggiorna la prenotazione prima di procedere.',
  occupato: 'Un annullamento di questa prenotazione è già in corso.',
  bloccata: "L'esito di un annullamento precedente è ancora da verificare.",
  'non-annullabile': 'La prenotazione non è annullabile nel suo stato attuale.',
}

async function conferma() {
  if (!puoInviare.value || fase.value !== 'conferma') return
  const ctx = contesto
  const prenotazione = p.value
  fase.value = 'invio'
  // Lo store registra comunque l'esito (anti-doppio invio, incertezza); qui si decidono
  // soltanto gli effetti locali, ammessi solo se la vista è ancora nello stesso contesto.
  const esito = await store.annulla(prenotazione, props.ticket)
  if (!corrente(ctx)) return
  fase.value = 'nessuna'
  switch (esito.kind) {
    case 'annullata':
      // Dopo il successo si rilegge sempre la risorsa, nello stesso contesto.
      await props.ricarica()
      await mostra(ctx, 'successo', 'Annullamento registrato.')
      break
    case 'conflitto':
    case 'non-trovata':
      await props.ricarica()
      await mostra(ctx, 'errore', ESITI[esito.kind])
      break
    case 'incerto':
      await nextTick()
      if (corrente(ctx)) incertoBox.value?.focus()
      break
    case 'sessione-assente':
    case 'obsoleto':
      // Sessione persa o cambiata: il router e il cambio di epoca hanno già chiuso o azzerato la vista.
      break
    default:
      await mostra(ctx, 'errore', ESITI[esito.kind] ?? ESITI.rifiutata)
  }
}

async function verifica() {
  if (verificando.value) return
  const ctx = contesto
  verificando.value = true
  avviso.value = null
  try {
    const riuscita = await props.ricarica(true)
    if (riuscita && corrente(ctx)) {
      const testo = p.value
        ? `Stato attuale: ${etichettaStato(p.value.stato)}. Lo stato mostrato è quello registrato ora e potrebbe non dipendere dal tuo ultimo tentativo.`
        : 'La prenotazione non risulta tra le tue prenotazioni.'
      await mostra(ctx, 'info', testo)
    }
  } finally {
    if (corrente(ctx)) verificando.value = false
  }
}

</script>
<template>
  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null">
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1"
       :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'">
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

      <div v-if="incerto && !incerto.verificato" ref="incertoBox" tabindex="-1" class="notice warning" role="alert" data-incerto>
        <p><strong>Non è stato possibile stabilire l'esito dell'annullamento.</strong></p>
        <p>Il server potrebbe averlo registrato. Non ripetere l'operazione: verifica prima lo stato della prenotazione.</p>
        <div class="actions">
          <button type="button" class="button" :disabled="verificando" @click="verifica">
            {{ verificando ? 'Verifica in corso…' : 'Verifica lo stato' }}
          </button>
        </div>
      </div>

      <section v-if="annullabile" :class="['card', { 'annullamento-compatto': compatto }]" :aria-labelledby="titoloId">
        <h2 :id="titoloId" ref="titoloAnnullamento" tabindex="-1" :class="{ 'visually-hidden': compatto }">Annullamento</h2>
        <p v-if="!compatto" class="muted small">
          L'annullabilità è verificata dal server al momento della richiesta: può essere rifiutata se l'appuntamento
          è già iniziato o se risultano pagamenti associati.
        </p>
        <p v-if="session.state.passwordUncertain" class="small">
          Le operazioni sono sospese finché non verifichi l'esito del cambio password.
          <RouterLink to="/area/password">Verifica il cambio password</RouterLink>
        </p>

        <div v-if="fase === 'nessuna'">
          <button ref="bottoneAnnulla" type="button" class="button azione-pericolo" :disabled="!puoInviare" @click="apriConferma">
            Annulla prenotazione
          </button>
        </div>

        <div v-else class="confirm" role="group" :aria-labelledby="confermaId"
             :aria-busy="fase === 'invio' ? 'true' : 'false'">
          <h3 :id="confermaId" ref="titoloConferma" tabindex="-1">Confermi l'annullamento?</h3>
          <p>
            {{ nomePrestazione(p) }}, {{ dataOrario(p.inizio, p.fine) }} — stato attuale: {{ etichettaStato(p.stato) }}.
          </p>
          <p class="small">Un eventuale pagamento non viene rimborsato automaticamente dall'annullamento.</p>
          <div class="actions">
            <button type="button" class="button azione-distruttiva" :disabled="fase === 'invio' || !puoInviare" @click="conferma">
              {{ fase === 'invio' ? 'Annullamento in corso…' : 'Conferma annullamento' }}
            </button>
            <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="chiudiConferma">
              Non annullare
            </button>
          </div>
        </div>
      </section>

</template>
<style scoped>
.annullamento-compatto { padding: 0; border: 0; box-shadow: none; margin: 0; }
</style>
