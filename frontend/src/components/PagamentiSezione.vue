<script setup>
import NotificaInformativa from './NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { dataOrario, nomePrestazione } from '../prenotazioni/formato.js'
import { RouterLink } from 'vue-router'
import { STATI_BLOCCANTI } from '../api/pagamenti.js'
import { ETICHETTE_MODALITA, ETICHETTE_PAGAMENTO, dataOra, pagamentoProponibile } from '../prenotazioni/economia.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useSession } from '../session/useSession.js'
import { formatEuro } from '../utils/money.js'

/**
 * Storico pagamenti e pagamento online simulato di una prenotazione. Il componente vive in un
 * solo contesto del dettaglio (la vista lo rimonta a ogni cambio di ID o di epoca): dopo uno
 * smontaggio nessun esito tardivo tocca l'interfaccia, mentre lo store registra comunque
 * l'esito del tentativo.
 */
const props = defineProps({
  incompatibile: { type: Boolean, default: false },
  compatto: { type: Boolean, default: false },
  prenotazione: { type: Object, required: true },
  /**
   * { stato: 'caricamento'|'pronto'|'errore', voci, errore, aggiornata, richiesta, completata } letta
   * dalla vista: `richiesta` identifica l'ultima lettura avviata, `completata` l'ultima applicata.
   */
  storico: { type: Object, required: true },
  /** La prenotazione mostrata è confermata dall'ultima lettura (non in rilettura). */
  prenotazioneAggiornata: { type: Boolean, required: true },
})
const emit = defineEmits(['rileggi', 'fase'])

const session = useSession()
const store = usePrenotazioni()
const id = props.prenotazione.idPrenotazione
const titoloId = props.compatto ? `titolo-pagamenti-${id}` : 'titolo-pagamenti'
const confermaId = props.compatto ? `titolo-conferma-pagamento-${id}` : 'titolo-conferma-pagamento'
const storicoAttuale = computed(() => props.storico.aggiornata && (props.storico.ticket === undefined || store.storicoValido(id, props.storico.ticket)))

// 'nessuna' | 'conferma' | 'invio'
const fase = ref('nessuna')
watch(fase, x => emit('fase', x !== 'nessuna'), { flush: 'sync' })
const avviso = ref(null)
const avvisoBox = ref(null)
const titolo = ref(null)
const titoloConferma = ref(null)
const bottonePaga = ref(null)
const incertoBox = ref(null)
const bottoneRiprova = ref(null)
let vivo = true
onBeforeUnmount(() => { vivo = false; emit('fase', false) })

const incerto = computed(() => store.pagamentoIncerto(id))
const inVolo = computed(() => store.state.pagamentiInCorso.includes(id))
// Idoneità solo con dati aggiornati (prenotazione e storico) e senza blocchi condivisi dello store.
const proponibile = computed(() => props.prenotazioneAggiornata && pagamentoProponibile(props.prenotazione, { ...props.storico, aggiornata: storicoAttuale.value })
  && !store.pagamentoBloccato(id) && !store.altraIncertezza(id, 'paga'))
const inAggiornamento = computed(() => props.storico.stato === 'pronto' && (!storicoAttuale.value || !props.prenotazioneAggiornata))
/** Riscontro condiviso di un pagamento attivo che lo storico mostrato non contiene: va riletto. */
const attivoNonMostrato = computed(() => props.prenotazione.stato === 'CONFERMATA' && store.pagamentoAttivoNoto(id)
  && !props.storico.voci.some((x) => STATI_BLOCCANTI.includes(x.stato)))
const puoConfermare = computed(() => proponibile.value && !props.incompatibile && !store.operazioneInCorso(id) && !session.state.passwordUncertain && !session.state.busy)

async function mostra(tipo, testo) {
  if (!vivo) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && vivo) avvisoBox.value?.focus()
}

async function apriConferma() {
  if (!puoConfermare.value) return
  avviso.value = null
  fase.value = 'conferma'
  await nextTick()
  if (vivo) titoloConferma.value?.focus()
}

// Una conferma aperta sui dati precedenti non resta utilizzabile: se l'idoneità decade
// (rilettura, blocco condiviso) viene chiusa e il focus torna al titolo della sezione.
watch(() => proponibile.value && !props.incompatibile && !store.operazioneInCorso(id), (valido) => {
  if (valido || fase.value !== 'conferma') return
  fase.value = 'nessuna'
  nextTick(() => { if (vivo && document.activeElement === document.body) titolo.value?.focus() })
}, { flush: 'sync' })

// «Riprova» dello storico: il pulsante scompare durante la rilettura. Il recupero del focus è
// associato all'identificativo della lettura avviata dal clic: solo il suo completamento lo
// attiva; se un'altra lettura la sostituisce, il recupero decade. A DOM aggiornato, e solo se la
// lettura è ancora l'ultima e il focus è rimasto sul documento, il focus va al titolo della
// sezione dopo un successo o al nuovo «Riprova» dopo un errore. Il componente vive in un solo
// contesto (rimontato a ogni cambio di ID o di epoca): dopo lo smontaggio nessun focus tardivo.
let recupero = null
function riprovaStorico() {
  const prima = props.storico.richiesta
  emit('rileggi', ['pagamenti'])
  // La vista avvia la lettura in modo sincrono: senza un nuovo identificativo nessun recupero.
  const id = props.storico.richiesta
  recupero = id !== undefined && id !== prima ? id : null
}
watch(() => props.storico.richiesta, (id) => {
  if (recupero !== null && id !== recupero) recupero = null
}, { flush: 'sync' })
watch(() => props.storico.completata, (id) => {
  if (recupero === null || id !== recupero) return
  const mia = recupero
  recupero = null
  nextTick(() => {
    if (!vivo || props.storico.richiesta !== mia || props.storico.completata !== mia) return
    const attivo = document.activeElement
    if (attivo && attivo !== document.body) return
    ;(props.storico.stato === 'errore' ? bottoneRiprova : titolo).value?.focus()
  })
}, { flush: 'sync' })

/** Torna indietro senza scritture e restituisce il focus al pulsante, o al titolo della sezione. */
async function chiudiConferma() {
  fase.value = 'nessuna'
  await nextTick()
  if (!vivo) return
  const b = bottonePaga.value
  ;(b && b.isConnected && !b.disabled ? b : titolo.value)?.focus()
}

const ESITI = {
  conflitto: 'Pagamento non consentito per lo stato attuale della prenotazione o dei suoi pagamenti. I dati sono stati riletti.',
  'non-trovata': 'Prenotazione non trovata. I dati sono stati riletti.',
  'non-valida': 'Richiesta di pagamento non valida.',
  rifiutata: 'Operazione non consentita. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "I pagamenti sono sospesi finché non verifichi l'esito del cambio password.",
  'dati-superati': 'I dati sono stati superati: aggiorna lo storico prima di un nuovo tentativo.',
  occupato: 'Un tentativo di pagamento per questa prenotazione è già in corso.',
  bloccata: "L'esito di un tentativo precedente è ancora da verificare.",
}

async function conferma() {
  if (!puoConfermare.value || fase.value !== 'conferma') return
  fase.value = 'invio'
  const esito = await store.pagaOnline(props.prenotazione, props.storico.voci, props.storico.ticket)
  if (!vivo) return
  // Esito certo: rilettura richiesta subito, prima di qualsiasi altro await, così i dati
  // precedenti smettono di autorizzare un nuovo invio nello stesso passo in cui lo store si libera.
  if (['pagato', 'fallito', 'conflitto', 'non-trovata'].includes(esito.kind)) emit('rileggi', ['prenotazione', 'pagamenti'])
  fase.value = 'nessuna'
  switch (esito.kind) {
    case 'pagato':
      await mostra('successo', `Pagamento simulato riuscito: ${formatEuro(esito.pagamento.importo)}, stato «${ETICHETTE_PAGAMENTO.PAGATO}». Nessun denaro reale è stato trasferito.`)
      break
    case 'fallito':
      await mostra('errore', 'Pagamento simulato non riuscito: nessun importo è stato incassato. Dopo l\'aggiornamento dello storico potrai effettuare un nuovo tentativo, con una nuova conferma.')
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

let contestoVerifica = 0
watch(() => [props.prenotazione.idPrenotazione, session.state.epoch], () => { contestoVerifica += 1 }, { flush: 'sync' })

async function verifica() {
  const contesto = contestoVerifica
  const attuale = () => vivo && contesto === contestoVerifica
  avviso.value = null
  const esito = await store.verificaPagamento(id)
  if (!attuale()) return
  // Lo storico mostrato viene riletto con una nuova lettura autorevole, non adottato dalla verifica.
  if (esito.kind === 'verificata') emit('rileggi', ['pagamenti'])
  else if (esito.kind === 'errore') avviso.value = { tipo: 'errore', testo: 'Verifica non riuscita: lo storico dei pagamenti non è leggibile. Riprova più tardi.' }
  if (!['verificata', 'superata', 'errore'].includes(esito.kind)) return
  await nextTick()
  if (attuale()) (esito.kind === 'errore' ? avvisoBox.value : incertoBox.value)?.focus()
}

const NOTE_VERIFICA = {
  'nessun-nuovo': "Nello storico non compare un nuovo pagamento dopo il tuo tentativo. Questo non prova che il tentativo non sia stato registrato. Un nuovo tentativo è possibile solo scegliendolo esplicitamente e confermandolo.",
  'nuovo-fallito': "Nello storico compare un nuovo tentativo non riuscito, senza importo incassato. Potrebbe corrispondere al tuo tentativo, ma non è possibile stabilirlo con certezza. Un nuovo tentativo è possibile solo scegliendolo esplicitamente e confermandolo.",
  attivo: 'Nello storico risulta un pagamento pagato o in attesa per questa prenotazione: un nuovo pagamento non è possibile. Non è possibile stabilire con certezza se corrisponda al tuo tentativo o a un\'altra operazione.',
}
</script>

<template>
  <section :class="['card', { 'pagamenti-compatti': compatto }]" :aria-labelledby="titoloId" data-sezione="pagamenti">
    <h2 :id="titoloId" ref="titolo" tabindex="-1" :class="{ 'visually-hidden': compatto }">Pagamenti</h2>

    <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                          :notifica="avviso" @close="avviso = null" data-avviso-pagamento>
      <p class="testo-dati">{{ avviso.testo }}</p>
    </NotificaInformativa>
    <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
         :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-pagamento>
      <p>{{ avviso.testo }}</p>
      <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
    </div>

    <div v-if="incerto" ref="incertoBox" tabindex="-1" class="notice warning" role="alert" data-pagamento-incerto>
      <p><strong>Non è stato possibile stabilire l'esito del pagamento simulato.</strong></p>
      <template v-if="incerto.verifica !== 'verificata'">
        <p>Il server potrebbe averlo registrato. Non è stato ripetuto e non è possibile un nuovo tentativo prima di una verifica nello storico.</p>
        <p v-if="incerto.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
        <p v-else-if="incerto.verifica === 'superata'" data-verifica-superata>
          Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Ripeti la verifica nello storico.
        </p>
      </template>
      <p v-else>{{ NOTE_VERIFICA[incerto.esito] }}</p>
      <div class="actions">
        <button type="button" class="button" :disabled="incerto.verifica === 'in-corso'" @click="verifica">
          {{ incerto.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica nello storico pagamenti' }}
        </button>
      </div>
    </div>

    <div v-if="attivoNonMostrato" class="notice warning" role="status" data-attivo-non-mostrato>
      <p>Risulta un pagamento registrato o in attesa per questa prenotazione: un nuovo pagamento non è possibile.</p>
      <p>Se lo storico qui sotto non lo mostra, aggiornalo.</p>
      <div class="actions">
        <button type="button" class="button secondary" :disabled="storico.stato === 'pronto' && !storico.aggiornata"
                @click="emit('rileggi', ['pagamenti'])">Aggiorna lo storico</button>
      </div>
    </div>

    <h3 v-if="!compatto" class="small">Storico</h3>
    <div v-if="inAggiornamento" class="small" role="status" data-aggiornamento>
      <p>Dati in aggiornamento o superati: le azioni sono sospese.</p>
      <button v-if="storico.aggiornata && !storicoAttuale" type="button" class="button secondary" @click="riprovaStorico">Aggiorna lo storico</button>
    </div>
    <p v-if="storico.stato === 'caricamento'" role="status">Caricamento dei pagamenti…</p>
    <div v-else-if="storico.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare i pagamenti. {{ storico.errore }}</p>
      <p v-if="prenotazione.stato === 'CONFERMATA'">Finché lo storico non è verificabile il pagamento online non è disponibile.</p>
      <div class="actions"><button ref="bottoneRiprova" type="button" class="button secondary" @click="riprovaStorico">Riprova</button></div>
    </div>
    <p v-else-if="storico.voci.length === 0 && !compatto">Nessun pagamento registrato.</p>
    <ul v-else-if="storico.stato === 'pronto' && (!compatto || storico.voci.some(x => x.stato === 'PAGATO'))" class="items" aria-label="Storico dei pagamenti">
      <li v-for="x in (compatto ? storico.voci.filter(x => x.stato === 'PAGATO') : storico.voci)" :key="x.idPagamento" class="card" data-pagamento>
        <dl v-if="!compatto" class="facts">
          <div><dt>Stato</dt><dd data-campo="stato-pagamento">{{ ETICHETTE_PAGAMENTO[x.stato] }}</dd></div>
          <div><dt>Modalità</dt><dd data-campo="modalita">{{ ETICHETTE_MODALITA[x.modalita] }}</dd></div>
          <div><dt>Importo</dt><dd>{{ formatEuro(x.importo) }}</dd></div>
          <div><dt>Registrato il</dt><dd>{{ dataOra(x.creato) }}</dd></div>
          <div v-if="x.pagato"><dt>Pagato il</dt><dd>{{ dataOra(x.pagato) }}</dd></div>
          <div><dt>Numero pagamento</dt><dd>{{ x.idPagamento }}</dd></div>
        </dl>
        <RouterLink class="button azione-neutra" v-if="x.stato === 'PAGATO' && storicoAttuale && prenotazioneAggiornata"
                    :to="`/paziente/prenotazioni/${id}/pagamenti/${x.idPagamento}/riepilogo`">
          Scarica riepilogo (stampa / PDF)<span class="visually-hidden"> del pagamento n. {{ x.idPagamento }}</span>
        </RouterLink>
      </li>
    </ul>

    <template v-if="proponibile">
      <h3 v-if="!compatto" class="small">Pagamento online</h3>
      <p v-if="!compatto" class="nota-simulazione" data-pagamento-simulato>
        <strong>Pagamento simulato:</strong> nessun denaro reale viene trasferito e non sono richiesti dati di carta.
        L'esito, riuscito o non riuscito, è deciso dal server.
      </p>
      <p v-if="session.state.passwordUncertain" class="small">
        I pagamenti sono sospesi finché non verifichi l'esito del cambio password.
        <RouterLink to="/area/password">Verifica il cambio password</RouterLink>
      </p>
      <p v-if="inVolo && fase !== 'invio'" class="small" role="status">Un tentativo di pagamento è in corso: attendi il suo esito.</p>
      <div v-if="fase === 'nessuna'">
        <button ref="bottonePaga" type="button" class="button" :disabled="!puoConfermare" @click="apriConferma">
          {{ incerto ? 'Nuovo tentativo di pagamento simulato' : compatto ? 'Paga adesso' : 'Paga online (simulato)' }}
        </button>
      </div>
      <div v-else class="confirm" role="group" :aria-labelledby="confermaId"
           :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-pagamento>
        <h3 :id="confermaId" ref="titoloConferma" tabindex="-1">Confermi il tentativo di pagamento simulato?</h3>
        <p><strong>{{ nomePrestazione(prenotazione) }}</strong> — Prenotazione n. {{ id }}</p>
        <p>{{ dataOrario(prenotazione.inizio, prenotazione.fine) }}</p>
        <p>Importo: <strong data-campo="importo-atteso">{{ formatEuro(prenotazione.prezzo) }}</strong>, il prezzo registrato per la prenotazione.</p>
        <p class="small">Pagamento simulato: nessun denaro reale. L'esito potrebbe essere non riuscito.</p>
        <div class="actions">
          <button type="button" class="button" :disabled="fase === 'invio' || !puoConfermare" @click="conferma">
            {{ fase === 'invio' ? 'Pagamento in corso…' : 'Conferma pagamento simulato' }}
          </button>
          <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="chiudiConferma">Non pagare</button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.pagamenti-compatti { margin: 0; padding: 0; border: 0; box-shadow: none; min-width: 0; }
.pagamenti-compatti .items { margin: 0; }
.pagamenti-compatti .items .card { padding: .5rem; margin: 0; }
.pagamenti-compatti .confirm { max-width: 32rem; }
</style>
