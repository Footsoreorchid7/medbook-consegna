<script setup>
import NotificaInformativa from './NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { controllaPdf } from '../api/medico.js'
import { useMedico } from '../medico/store.js'
import { dataOra } from '../prenotazioni/economia.js'
import { dataOrario, nomePrestazione } from '../prenotazioni/formato.js'
import { useSession } from '../session/useSession.js'

/**
 * Caricamento del referto per un appuntamento EFFETTUATA del medico effettivo. Il componente
 * vive in un solo contesto dell'agenda (rimontato a ogni nuova ricerca o cambio di epoca).
 * Il file scelto resta solo qui, mai nello store, nei log o nello storage: viene rilasciato
 * dopo ogni tentativo, all'annullamento e allo smontaggio.
 */
const props = defineProps({
  appuntamento: { type: Object, required: true },
  /** Agenda confermata dall'ultima lettura e nessun'altra conferma aperta. */
  abilitato: { type: Boolean, required: true },
})
const emit = defineEmits(['rileggi'])

const session = useSession()
const store = useMedico()
const id = props.appuntamento.idPrenotazione
const uid = `referto-${id}`

// 'nessuna' | 'scelta' | 'controllo' | 'conferma' | 'invio'
const fase = ref('nessuna')
const file = shallowRef(null)
const erroreFile = ref('')
const avviso = ref(null)
const avvisoBox = ref(null)
const input = ref(null)
const bottone = ref(null)
const titolo = ref(null)
const titoloConferma = ref(null)
let vivo = true

/** Rilascia il riferimento al file e svuota il controllo di selezione. */
function pulisci() {
  controllo += 1
  file.value = null
  erroreFile.value = ''
  if (input.value) input.value.value = ''
}
onBeforeUnmount(() => {
  vivo = false
  pulisci()
})

const caricato = computed(() => store.refertoCaricato(id))
const presente = computed(() => props.appuntamento.refertoPresente === true || caricato.value !== null)
const incerto = computed(() => store.refertoIncerto(id))
const inVolo = computed(() => store.state.refertiInCorso.includes(id))
const puoProcedere = computed(() => props.abilitato && !presente.value && !store.refertoBloccato(id) && !session.state.passwordUncertain && !session.state.busy)
const dimensione = (n) => (n >= 1024 * 1024 ? `${(n / (1024 * 1024)).toFixed(1).replace('.', ',')} MiB` : `${Math.max(1, Math.round(n / 1024))} KiB`)

async function mostra(tipo, testo) {
  if (!vivo) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && vivo) avvisoBox.value?.focus()
}

async function apri() {
  if (!puoProcedere.value || fase.value !== 'nessuna') return
  avviso.value = null
  pulisci()
  fase.value = 'scelta'
  await nextTick()
  if (vivo) input.value?.focus()
}

async function chiudi() {
  pulisci()
  fase.value = 'nessuna'
  await nextTick()
  if (!vivo) return
  const b = bottone.value
  ;(b && b.isConnected && !b.disabled ? b : titolo.value)?.focus()
}

// Ogni controllo della firma ha un numero: un cambio di file lo invalida, anche se si torna
// allo stesso file (A → B → A) o se il file viene tolto.
let controllo = 0

function scegli(event) {
  controllo += 1
  erroreFile.value = ''
  file.value = event.target.files?.[0] ?? null
  if (fase.value === 'controllo') fase.value = 'scelta'
}

const ERRORI_FILE = {
  assente: 'Scegli il documento PDF del referto.',
  vuoto: 'Il file scelto è vuoto.',
  'troppo-grande': 'Il file supera il limite di 10 MiB.',
  'non-pdf': 'Il file scelto non è un documento PDF.',
  illeggibile: 'Il file scelto non è leggibile.',
}

/** Controlli locali essenziali, poi conferma esplicita con il riepilogo. */
async function prosegui() {
  if (fase.value !== 'scelta' || !puoProcedere.value) return
  const scelto = file.value
  const mio = ++controllo
  fase.value = 'controllo'
  const errore = await controllaPdf(scelto)
  // Controllo superato da un cambio di file, da un annullamento o dallo smontaggio: nessun effetto.
  if (!vivo || mio !== controllo || fase.value !== 'controllo') return
  if (errore) {
    fase.value = 'scelta'
    erroreFile.value = ERRORI_FILE[errore]
    await nextTick()
    if (vivo) input.value?.focus()
    return
  }
  fase.value = 'conferma'
  await nextTick()
  if (vivo) titoloConferma.value?.focus()
}

// Selezione e conferma non restano utilizzabili se l'idoneità decade (rilettura, blocco).
watch(puoProcedere, (valido) => {
  if (valido || !['scelta', 'controllo', 'conferma'].includes(fase.value)) return
  pulisci()
  fase.value = 'nessuna'
  nextTick(() => { if (vivo && document.activeElement === document.body) titolo.value?.focus() })
}, { flush: 'sync' })

const ESITI = {
  'non-valida': 'Caricamento rifiutato dal server: il documento o la richiesta non sono validi. Puoi scegliere un altro file.',
  'troppo-grande': 'Caricamento rifiutato dal server: il file supera il limite di 10 MiB.',
  'tipo-non-supportato': 'Caricamento rifiutato dal server: tipo di contenuto non accettato.',
  conflitto: "Caricamento rifiutato: per questa prenotazione risulta già un referto oppure il suo stato non lo consente. Questo esito non indica quale documento sia stato salvato in precedenza. L'agenda è stata riletta.",
  'non-trovata': "Caricamento rifiutato: la prenotazione non risulta assegnata a te come medico effettivo. L'agenda è stata riletta.",
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  occupato: 'Un caricamento per questa prenotazione è già in corso.',
  bloccata: 'Un caricamento precedente per questa prenotazione impedisce un nuovo invio.',
  'non-caricabile': 'Il referto non è caricabile per questa prenotazione secondo i dati letti.',
  'file-non-valido': 'Il file scelto non è utilizzabile.',
}

async function conferma() {
  if (fase.value !== 'conferma' || !puoProcedere.value || !file.value) return
  const scelto = file.value
  fase.value = 'invio'
  const esito = await store.caricaReferto(props.appuntamento, scelto)
  // Il file non serve più, qualunque sia l'esito: niente nuovi tentativi automatici.
  pulisci()
  if (!vivo) return
  if (esito.kind === 'conflitto' || esito.kind === 'non-trovata') emit('rileggi')
  fase.value = 'nessuna'
  switch (esito.kind) {
    case 'caricato':
      await mostra('successo', `Referto caricato il ${dataOra(esito.referto.caricato)} con il nome «${esito.referto.nomeFile}», assegnato dal server. Il referto è unico e non può essere sostituito né modificato con queste funzioni.`)
      break
    case 'incerto':
      await mostra('errore', "Non è stato possibile stabilire se il referto sia stato salvato. Il caricamento non è stato ripetuto e non è dichiarato fallito; l'agenda può indicare la presenza di un documento, ma non permette di attribuirlo con certezza a questo tentativo, quindi un nuovo caricamento resta bloccato in questa sessione.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra('errore', ESITI[esito.kind] ?? ESITI.rifiutata)
  }
}

const nomePaziente = computed(() => `${props.appuntamento.paziente.nome} ${props.appuntamento.paziente.cognome}`)
</script>

<template>
  <div class="referto-medico" :data-referto-caricamento="id">
    <h4 :id="`${uid}-titolo`" ref="titolo" class="small" tabindex="-1">Referto</h4>
    <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                          :notifica="avviso" @close="avviso = null" data-avviso-referto>
      <p class="testo-dati">{{ avviso.testo }}</p>
    </NotificaInformativa>
    <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
         :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-referto>
      <p>{{ avviso.testo }}</p>
      <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
    </div>
    <p v-if="presente" class="small referto-caricato" data-referto-caricato><strong>Referto caricato</strong><template v-if="caricato"> in questa sessione il {{ dataOra(caricato.caricato) }}</template>. Il documento è unico e non modificabile.</p>
    <p v-if="incerto && !avviso" class="small" data-referto-bloccato>Caricamento da verificare: un nuovo invio è bloccato (vedi «Esiti da verificare»).</p>
    <p v-if="inVolo && fase !== 'invio'" class="small" role="status">Un caricamento è in corso: attendi il suo esito.</p>

    <div v-if="fase === 'nessuna'">
      <button v-if="!presente && !incerto" ref="bottone" type="button" class="button azione-referto" :disabled="!puoProcedere" @click="apri">
        Carica referto<span class="visually-hidden"> per {{ nomePaziente }}, {{ dataOrario(appuntamento.inizio, appuntamento.fine) }}</span>
      </button>
    </div>
    <form v-else-if="fase === 'scelta' || fase === 'controllo'" class="form" novalidate @submit.prevent="prosegui">
      <div class="field">
        <label :for="`${uid}-file`">Documento PDF del referto (massimo 10 MiB)</label>
        <input :id="`${uid}-file`" ref="input" type="file" accept="application/pdf,.pdf"
               :aria-invalid="erroreFile ? 'true' : undefined" :aria-describedby="erroreFile ? `${uid}-errore` : undefined"
               @change="scegli" />
        <p v-if="erroreFile" :id="`${uid}-errore`" class="field-error">{{ erroreFile }}</p>
      </div>
      <div class="actions">
        <button type="submit" class="button" :disabled="fase === 'controllo' || !puoProcedere">
          {{ fase === 'controllo' ? 'Controllo del file…' : 'Prosegui' }}
        </button>
        <button type="button" class="button secondary" @click="chiudi">Annulla</button>
      </div>
    </form>
    <div v-else class="confirm" role="group" :aria-labelledby="`${uid}-conferma`" :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-referto>
      <h4 :id="`${uid}-conferma`" ref="titoloConferma" tabindex="-1">Confermi il caricamento del referto?</h4>
      <p>Paziente: <strong>{{ nomePaziente }}</strong>. {{ nomePrestazione(appuntamento) }}, {{ dataOrario(appuntamento.inizio, appuntamento.fine) }}. Prenotazione n. {{ appuntamento.idPrenotazione }}.</p>
      <p>File selezionato: <strong data-campo="nome-file">{{ file?.name ?? 'documento PDF' }}</strong><template v-if="file"> ({{ dimensione(file.size) }})</template>.</p>
      <p class="small">Il referto è unico per la prenotazione e non può essere sostituito né modificato con queste funzioni. Il server verifica il documento e può rifiutarlo.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="fase === 'invio' || !puoProcedere" @click="conferma">
          {{ fase === 'invio' ? 'Caricamento in corso…' : 'Carica il referto' }}
        </button>
        <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="chiudi">Annulla</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.referto-caricato { padding: .65rem; border: 1px solid #16765f; border-radius: .5rem; color: #085d4d; background: #def7ee; }
</style>
