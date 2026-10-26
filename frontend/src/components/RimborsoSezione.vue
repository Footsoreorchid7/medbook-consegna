<script setup>
import NotificaInformativa from './NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { MAX_MOTIVAZIONE, lunghezzaMotivazione, motivazioneErrore } from '../api/rimborsi.js'
import { ETICHETTE_RIMBORSO, NOTE_RIMBORSO, dataOra, pagamentoRimborsabile } from '../prenotazioni/economia.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useSession } from '../session/useSession.js'
import { formatEuro } from '../utils/money.js'

/**
 * Richiesta di rimborso del paziente per una prenotazione. Come la sezione pagamenti, vive in
 * un solo contesto del dettaglio; lo store conserva comunque l'esito del tentativo.
 * Decisione ed esecuzione del rimborso non sono funzioni del paziente.
 */
const props = defineProps({
  focusRichiesto: { type: Boolean, default: false },
  prenotazione: { type: Object, required: true },
  storico: { type: Object, required: true },
  rimborsi: { type: Object, required: true },
  /** La prenotazione mostrata è confermata dall'ultima lettura (non in rilettura). */
  prenotazioneAggiornata: { type: Boolean, required: true },
})
const emit = defineEmits(['rileggi'])

const session = useSession()
const store = usePrenotazioni()
const id = props.prenotazione.idPrenotazione

const motivazione = ref('')
const errore = ref('')
// 'nessuna' | 'conferma' | 'invio'
const fase = ref('nessuna')
const avviso = ref(null)
const avvisoBox = ref(null)
const titolo = ref(null)
const titoloConferma = ref(null)
const bottoneProsegui = ref(null)
const incertoBox = ref(null)
let vivo = true
onBeforeUnmount(() => { vivo = false })

const propri = computed(() => (props.rimborsi.stato === 'pronto' ? props.rimborsi.voci.filter((r) => r.idPrenotazione === id) : []))
const pagamento = computed(() => pagamentoRimborsabile(props.prenotazione, { ...props.storico, aggiornata: props.storico.aggiornata && (props.storico.ticket === undefined || store.storicoValido(id, props.storico.ticket)) }, props.rimborsi))
const incerto = computed(() => store.rimborsoIncerto(id))
const inVolo = computed(() => store.state.rimborsiInCorso.includes(id))
// Idoneità solo con prenotazione, pagamenti e rimborsi aggiornati e senza richieste già note allo store.
const proponibile = computed(() => props.prenotazioneAggiornata && pagamento.value !== null && !store.rimborsoBloccato(id)
  && !store.rimborsoNoto(pagamento.value.idPagamento)
  && (props.rimborsi.ticket === undefined || store.rimborsiValidi(pagamento.value.idPagamento, props.rimborsi.ticket)))
const puoInviare = computed(() => proponibile.value && !store.operazioneInCorso(id) && !store.altraIncertezza(id, 'rimborso') && !session.state.passwordUncertain && !session.state.busy)
let focusConsumito = false
watch(() => [props.focusRichiesto, props.prenotazione, props.storico.stato, props.rimborsi.stato], async () => {
  if (!props.focusRichiesto || focusConsumito) return
  if (props.prenotazione.stato === 'NON_EROGATA' && [props.storico.stato, props.rimborsi.stato].some(x => ['inattiva', 'caricamento'].includes(x))) return
  const epoca = session.state.epoch, risorsa = props.prenotazione
  await nextTick()
  if (!vivo || epoca !== session.state.epoch || risorsa !== props.prenotazione || !props.focusRichiesto || focusConsumito) return
  focusConsumito = true
  const a = document.activeElement
  if (!a || a === document.body || !a.isConnected || a.id === 'contenuto') titolo.value?.focus()
}, { immediate: true })

const nonVerificabile = computed(() => props.storico.stato === 'errore' || props.rimborsi.stato === 'errore')
const inLettura = computed(() => !nonVerificabile.value && (!props.prenotazioneAggiornata
  || !props.storico.aggiornata || !props.rimborsi.aggiornata))

// Una conferma aperta sui dati precedenti non resta utilizzabile: se l'idoneità decade viene
// chiusa (la motivazione resta nel modulo) e il focus torna al titolo della sezione.
watch(proponibile, (valido) => {
  if (valido || fase.value !== 'conferma') return
  fase.value = 'nessuna'
  nextTick(() => { if (vivo && document.activeElement === document.body) titolo.value?.focus() })
}, { flush: 'sync' })

async function mostra(tipo, testo) {
  if (!vivo) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && vivo) avvisoBox.value?.focus()
}

async function prosegui() {
  if (!puoInviare.value) return
  avviso.value = null
  errore.value = motivazioneErrore(motivazione.value) ?? ''
  if (errore.value) {
    await nextTick()
    if (vivo) document.getElementById('motivazione-rimborso')?.focus()
    return
  }
  fase.value = 'conferma'
  await nextTick()
  if (vivo) titoloConferma.value?.focus()
}

/** Torna al modulo senza scritture; il focus torna a «Prosegui», o al titolo della sezione. */
async function chiudiConferma() {
  fase.value = 'nessuna'
  await nextTick()
  if (!vivo) return
  const b = bottoneProsegui.value
  ;(b && b.isConnected && !b.disabled ? b : titolo.value)?.focus()
}

const ESITI = {
  conflitto: 'Richiesta non ammessa: potrebbe esistere già una richiesta per questo pagamento, oppure le condizioni non sono più soddisfatte. I dati sono stati riletti.',
  'non-trovata': 'Prenotazione non trovata. I dati sono stati riletti.',
  'non-valida': 'Richiesta non valida: controlla la motivazione.',
  rifiutata: 'Operazione non consentita. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le richieste sono sospese finché non verifichi l'esito del cambio password.",
  occupato: 'Una richiesta di rimborso per questa prenotazione è già in corso.',
  bloccata: "L'esito di una richiesta precedente è ancora da verificare.",
}

async function invia() {
  if (!puoInviare.value || fase.value !== 'conferma') return
  fase.value = 'invio'
  const esito = await store.richiediRimborso(id, pagamento.value, motivazione.value)
  if (!vivo) return
  // Esito certo: rilettura richiesta subito, prima di qualsiasi altro await.
  if (['richiesto', 'conflitto', 'non-trovata'].includes(esito.kind)) emit('rileggi', ['prenotazione', 'pagamenti', 'rimborsi'])
  fase.value = 'nessuna'
  switch (esito.kind) {
    case 'richiesto':
      motivazione.value = ''
      await mostra('successo', 'Richiesta di rimborso registrata, stato «Richiesto». Non è ancora un rimborso approvato né eseguito.')
      break
    case 'conflitto':
    case 'non-trovata':
      await mostra('errore', ESITI[esito.kind])
      break
    case 'incerto':
      await nextTick()
      if (vivo) incertoBox.value?.focus()
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra('errore', ESITI[esito.kind] ?? ESITI.rifiutata)
  }
}

async function verifica() {
  avviso.value = null
  const esito = await store.verificaRimborso(id)
  if (!vivo) return
  // L'elenco mostrato viene riletto con una nuova lettura autorevole.
  if (esito.kind === 'trovato' || esito.kind === 'assente') emit('rileggi', ['rimborsi'])
  if (esito.kind === 'trovato') {
    await mostra('info', esito.rimborso
      ? `Risulta una richiesta di rimborso per questo pagamento, stato «${ETICHETTE_RIMBORSO[esito.rimborso.stato]}». Lo stato è quello registrato ora.`
      : 'Risulta una richiesta di rimborso per questo pagamento, secondo i dati più recenti.')
  } else if (esito.kind === 'errore') {
    await mostra('errore', "Verifica non riuscita: l'elenco dei rimborsi non è leggibile. Riprova più tardi.")
  }
}
</script>

<template>
  <section class="card" aria-labelledby="titolo-rimborso" data-sezione="rimborso">
    <h2 id="titolo-rimborso" ref="titolo" tabindex="-1">Rimborso</h2>
    <p v-if="prenotazione.stato !== 'NON_EROGATA'" role="status">Lo stato corrente della prenotazione non consente una richiesta di rimborso.</p>
    <template v-else>

    <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                          :notifica="avviso" @close="avviso = null" data-avviso-rimborso>
      <p class="testo-dati">{{ avviso.testo }}</p>
    </NotificaInformativa>
    <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
         :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-rimborso>
      <p>{{ avviso.testo }}</p>
      <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
    </div>

    <div v-if="incerto" ref="incertoBox" tabindex="-1" class="notice warning" role="alert" data-rimborso-incerto>
      <p><strong>Non è stato possibile stabilire l'esito della richiesta di rimborso.</strong></p>
      <template v-if="incerto.verifica !== 'assente'">
        <p>Il server potrebbe averla registrata. Non è stata ripetuta e non può essere inviata di nuovo prima di una verifica nei tuoi rimborsi.</p>
        <p v-if="incerto.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
        <p v-else-if="incerto.verifica === 'superata'" data-verifica-superata>
          Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Ripeti la verifica nei tuoi rimborsi.
        </p>
      </template>
      <p v-else>
        Nei tuoi rimborsi non risulta una richiesta per questo pagamento. Questo non prova che l'invio non sia stato
        registrato. Puoi inviare una nuova richiesta solo scegliendolo esplicitamente e confermandolo: se la precedente
        fosse stata registrata, il server rifiuterebbe il duplicato.
      </p>
      <div class="actions">
        <button type="button" class="button" :disabled="incerto.verifica === 'in-corso'" @click="verifica">
          {{ incerto.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica nei miei rimborsi' }}
        </button>
      </div>
    </div>

    <p v-if="rimborsi.stato === 'caricamento'" role="status">Caricamento dei rimborsi…</p>
    <div v-else-if="rimborsi.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare i rimborsi. {{ rimborsi.errore }}</p>
      <div class="actions"><button type="button" class="button secondary" @click="emit('rileggi', ['rimborsi'])">Riprova</button></div>
    </div>
    <ul v-else-if="propri.length" class="items" aria-label="Richieste di rimborso della prenotazione">
      <li v-for="r in propri" :key="r.idRimborso" class="card" data-rimborso>
        <dl class="facts">
          <div><dt>Stato</dt><dd data-campo="stato-rimborso">{{ ETICHETTE_RIMBORSO[r.stato] }}</dd></div>
          <div><dt>Importo</dt><dd>{{ formatEuro(r.importo) }}</dd></div>
          <div><dt>Richiesto il</dt><dd>{{ dataOra(r.richiesto) }}</dd></div>
          <div v-if="r.verificato"><dt>Deciso il</dt><dd>{{ dataOra(r.verificato) }}</dd></div>
        </dl>
        <p class="small">{{ NOTE_RIMBORSO[r.stato] }}</p>
      </li>
    </ul>

    <p v-if="inLettura" class="small" role="status">Verifica dei requisiti per il rimborso…</p>
    <p v-else-if="nonVerificabile" class="small">Finché pagamenti e rimborsi non sono verificabili, la richiesta di rimborso non è disponibile.</p>
    <template v-else-if="proponibile">
      <h3 class="small">Richiedi il rimborso</h3>
      <p class="small">
        Pagamento online n. {{ pagamento.idPagamento }} di {{ formatEuro(pagamento.importo) }}. La richiesta sarà valutata
        dalla segreteria: un'approvazione non equivale alla restituzione del denaro.
      </p>
      <p v-if="session.state.passwordUncertain" class="small">
        Le richieste sono sospese finché non verifichi l'esito del cambio password.
        <RouterLink to="/area/password">Verifica il cambio password</RouterLink>
      </p>
      <p v-if="inVolo && fase !== 'invio'" class="small" role="status">Una richiesta di rimborso è in corso: attendi il suo esito.</p>
      <form v-if="fase === 'nessuna'" class="form" novalidate @submit.prevent="prosegui">
        <div class="field">
          <label for="motivazione-rimborso">Motivazione</label>
          <textarea id="motivazione-rimborso" v-model="motivazione" rows="4"
                    :aria-invalid="errore ? 'true' : undefined"
                    :aria-describedby="errore ? 'aiuto-motivazione errore-motivazione' : 'aiuto-motivazione'"></textarea>
          <p id="aiuto-motivazione" class="muted small">
            Obbligatoria, al massimo {{ MAX_MOTIVAZIONE }} caratteri ({{ lunghezzaMotivazione(motivazione) }} usati).
          </p>
          <p v-if="errore" id="errore-motivazione" class="field-error" role="alert">{{ errore }}</p>
        </div>
        <div>
          <button ref="bottoneProsegui" type="submit" class="button" :disabled="!puoInviare">Prosegui</button>
        </div>
      </form>
      <div v-else class="confirm" role="group" aria-labelledby="titolo-conferma-rimborso"
           :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-rimborso>
        <h3 id="titolo-conferma-rimborso" ref="titoloConferma" tabindex="-1">Confermi la richiesta di rimborso?</h3>
        <p>Importo: <strong>{{ formatEuro(pagamento.importo) }}</strong> (pagamento online n. {{ pagamento.idPagamento }}).</p>
        <p class="small">Motivazione: {{ motivazione }}</p>
        <div class="actions">
          <button type="button" class="button" :disabled="fase === 'invio' || !puoInviare" @click="invia">
            {{ fase === 'invio' ? 'Invio in corso…' : 'Invia richiesta di rimborso' }}
          </button>
          <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="chiudiConferma">Modifica la motivazione</button>
        </div>
      </div>
    </template>
    <p v-else-if="!propri.length && !incerto" class="small">
      Per questa prenotazione non risulta un pagamento online riuscito per cui richiedere il rimborso.
    </p>
    <p class="small"><RouterLink to="/paziente/rimborsi">I miei rimborsi</RouterLink></p>
    </template>
  </section>
</template>
