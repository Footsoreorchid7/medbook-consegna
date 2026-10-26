<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sceltaStore, dataRicercaValida, idParametro } from '../prenotazioni/scelta.js'
import { fetchDisponibilitaPrenotabili, fetchPrestazioni } from '../api/catalogo.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'
import { formatEuro } from '../utils/money.js'

const session = useSession()
const route = useRoute()
const router = useRouter()
const scelta = sceltaStore(session)

// Catalogo per il filtro prestazione: non esiste un elenco pubblico dei medici.
const catalogo = reactive({ stato: 'caricamento', voci: [] })
const filtri = reactive({ prestazioneId: '', data: '' })
const erroreFiltri = ref('')
const erroreParametro = ref('')

const ricerca = reactive({ stato: 'inattiva', risultati: [], errore: '', applicati: null })
let sequenza = 0
let controller = null
let catalogoController = null
let smontato = false
// Filtri dell'ultima ricerca avviata: servono a ripeterla dopo un cambio di sessione.
let ultimiFiltri = null

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

async function caricaCatalogo() {
  catalogoController?.abort()
  const corrente = new AbortController()
  catalogoController = corrente
  const epoca = session.state.epoch
  catalogo.stato = 'caricamento'
  try {
    catalogo.voci = await fetchPrestazioni({
      signal: corrente.signal,
      isCurrent: () => catalogoController === corrente && session.state.epoch === epoca && !smontato,
    })
    catalogo.stato = 'pronto'
    if (filtri.prestazioneId && !catalogo.voci.some(p => String(p.idPrestazione) === filtri.prestazioneId)) {
      filtri.prestazioneId = ''
      erroreParametro.value = 'La prestazione indicata non è nel catalogo. Seleziona una prestazione.'
    }
  } catch (error) {
    if (catalogoController === corrente && !smontato && !(error instanceof StaleResponseError)) catalogo.stato = 'errore'
  }
}

/**
 * Ogni ricerca annulla la precedente e ne invalida la risposta: un risultato vecchio non
 * sostituisce mai quello dei filtri correnti.
 */
function invalida() {
  sequenza += 1
  controller?.abort()
  ricerca.risultati = []
  ricerca.applicati = null
  ricerca.stato = 'inattiva'
  ultimiFiltri = null
}

function leggiParametro() {
  invalida()
  erroreParametro.value = ''
  const raw = route.query.prestazioneId
  const id = idParametro(raw)
  filtri.prestazioneId = id === null ? '' : String(id)
  if (raw !== undefined && (id === null || (catalogo.stato === 'pronto' && !catalogo.voci.some(p => p.idPrestazione === id)))) {
    erroreParametro.value = 'La prestazione indicata non è valida o non è nel catalogo. Seleziona una prestazione.'
    filtri.prestazioneId = ''
  }
}

async function cerca() {
  erroreFiltri.value = ''
  const id = idParametro(filtri.prestazioneId)
  if (catalogo.stato !== 'pronto' || id === null || !catalogo.voci.some(p => p.idPrestazione === id)) {
    erroreFiltri.value = 'Seleziona una prestazione del catalogo.'
    return
  }
  if (!dataRicercaValida(filtri.data)) {
    erroreFiltri.value = 'Scegli una data valida.'
    return
  }
  await esegui({ prestazioneId: id, data: filtri.data })
}

async function esegui(applicati) {
  ultimiFiltri = applicati
  const epoca = session.state.epoch
  const mia = ++sequenza
  controller?.abort()
  controller = new AbortController()
  ricerca.stato = 'caricamento'
  ricerca.errore = ''
  try {
    const risultati = await fetchDisponibilitaPrenotabili(applicati, {
      signal: controller.signal,
      isCurrent: () => mia === sequenza && !smontato && session.state.epoch === epoca,
    })
    ricerca.risultati = risultati
    ricerca.applicati = applicati
    ricerca.stato = 'pronto'
  } catch (error) {
    if (error instanceof StaleResponseError || mia !== sequenza || smontato) return
    ricerca.errore = messaggio(error)
    ricerca.stato = 'errore'
  }
}

const nomePrestazione = (id) => catalogo.voci.find((p) => p.idPrestazione === id)?.nome ?? `n. ${id}`
const descrizioneFiltri = computed(() => {
  const a = ricerca.applicati
  if (!a) return ''
  const parti = []
  if (a.prestazioneId !== null) parti.push(`prestazione: ${nomePrestazione(a.prestazioneId)}`)
  if (a.data !== null) {
    const [y, m, d] = a.data.split('-')
    parti.push(`data: ${d}/${m}/${y}`)
  }
  return parti.length ? parti.join(', ') : 'nessun filtro'
})

/**
 * Accesso alla richiesta di appuntamento: il paziente prosegue con lo slot preselezionato
 * (riverificato nella pagina di prenotazione), l'anonimo passa dall'accesso con ritorno
 * interno, un account senza profilo paziente riceve solo un messaggio.
 */
const azione = computed(() => {
  if (session.state.status === STATO.ANONIMO) return 'anonimo'
  if (session.state.status !== STATO.AUTENTICATO) return null
  return session.capabilities.value.paziente ? 'paziente' : 'non-paziente'
})
const percorsoPrenota = '/paziente/prenotazioni'
function prosegui(s) {
  if (ricerca.stato !== 'pronto' || !ricerca.risultati.some(x => x.idDisponibilita === s.idDisponibilita) || !ricerca.applicati
      || ricerca.applicati.prestazioneId !== Number(filtri.prestazioneId) || ricerca.applicati.data !== filtri.data) return
  if (!['anonimo', 'paziente'].includes(azione.value)) return
  const corrente = ricerca.risultati.find(x => x.idDisponibilita === s.idDisponibilita)
  if (!scelta.scegli({ idDisponibilita: corrente.idDisponibilita, prestazioneId: corrente.idPrestazione, data: corrente.inizio.date })) return
  router.push(azione.value === 'anonimo' ? { name: 'accesso', query: { redirect: percorsoPrenota } } : percorsoPrenota)
}

onMounted(() => {
  leggiParametro()
  caricaCatalogo()
})
// Cambio di sessione con la pagina aperta: le letture precedenti sono annullate e ripetute una
// sola volta (catalogo e ultima ricerca avviata), senza cicli e senza lasciare caricamenti sospesi.
watch(() => [filtri.prestazioneId, filtri.data], () => { invalida(); erroreFiltri.value = ''; if (filtri.prestazioneId) erroreParametro.value = '' }, { flush: 'sync' })
watch(() => route.query.prestazioneId, leggiParametro)
watch(() => session.state.epoch, () => {
  if (smontato) return
  const precedenti = ultimiFiltri
  invalida()
  caricaCatalogo()
  if (precedenti) esegui(precedenti)
}, { flush: 'sync' })
onBeforeUnmount(() => {
  smontato = true
  controller?.abort()
  catalogoController?.abort()
})
</script>

<template>
  <h1>Disponibilità</h1>
  <p>Orari ancora liberi per le prestazioni del centro medico.</p>
  <p class="muted">
    Orari espressi nell'orario della struttura (fuso orario da confermare). La disponibilità sarà
    riconfermata al momento della richiesta di appuntamento.
  </p>

  <section class="card" aria-labelledby="titolo-filtri">
    <h2 id="titolo-filtri">Scegli prestazione e data</h2>
    <p v-if="erroreParametro" class="notice warning" role="status">{{ erroreParametro }}</p>
    <form class="form filters" novalidate @submit.prevent="cerca">
      <div class="field">
        <label for="filtro-prestazione">Prestazione</label>
        <select id="filtro-prestazione" v-model="filtri.prestazioneId" :disabled="catalogo.stato !== 'pronto'"
                aria-describedby="stato-catalogo">
          <option value="">Seleziona una prestazione</option>
          <option v-for="p in catalogo.voci" :key="p.idPrestazione" :value="String(p.idPrestazione)">{{ p.nome }}</option>
        </select>
        <p id="stato-catalogo" class="muted small">
          <template v-if="catalogo.stato === 'caricamento'">Caricamento delle prestazioni…</template>
          <template v-else-if="catalogo.stato === 'pronto' && catalogo.voci.length === 0">Nessuna prestazione nel catalogo.</template>
          <template v-else-if="catalogo.stato === 'errore'">
            Elenco delle prestazioni non disponibile.
            <button type="button" class="link-button" @click="caricaCatalogo">Riprova</button>
          </template>
        </p>
      </div>
      <div class="field">
        <label for="filtro-data">Data</label>
        <input id="filtro-data" v-model="filtri.data" type="date" min="1000-01-01" max="9999-12-30"
               :aria-invalid="erroreFiltri ? 'true' : undefined"
               :aria-describedby="erroreFiltri ? 'errore-filtri' : undefined" />
        <p v-if="erroreFiltri" id="errore-filtri" class="field-error" role="alert">{{ erroreFiltri }}</p>
      </div>
      <div>
        <button type="submit" class="button" :disabled="ricerca.stato === 'caricamento'">Cerca disponibilità</button>
      </div>
    </form>
  </section>

  <section aria-labelledby="titolo-risultati" :aria-busy="ricerca.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-risultati">Risultati</h2>
    <p v-if="ricerca.stato === 'inattiva'" role="status">Scegli una prestazione e una data, poi cerca le disponibilità.</p>
    <p v-if="ricerca.stato === 'caricamento'" role="status">Ricerca in corso…</p>
    <div v-else-if="ricerca.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare le disponibilità. {{ ricerca.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="cerca">Riprova</button></div>
    </div>
    <template v-else-if="ricerca.stato === 'pronto'">
      <p class="muted" role="status">
        {{ ricerca.risultati.length }} disponibilità trovate ({{ descrizioneFiltri }}).
      </p>
      <p v-if="azione === 'non-paziente'" class="notice" data-avviso-profilo>
        La richiesta di appuntamento è riservata agli account con profilo paziente.
      </p>
      <p v-if="ricerca.risultati.length === 0">Nessuna disponibilità per i filtri scelti.</p>
      <ul v-else class="items" aria-label="Disponibilità trovate">
        <li v-for="s in ricerca.risultati" :key="s.idDisponibilita" class="card" data-disponibilita :data-id="s.idDisponibilita">
          <h3 class="item-title">{{ s.nomePrestazione }}</h3>
          <dl class="facts">
            <div><dt>Medico</dt><dd>{{ s.nomeMedico }} {{ s.cognomeMedico }}</dd></div>
            <div><dt>Data</dt><dd data-campo="data">{{ formatLocalDate(s.inizio) }}</dd></div>
            <div>
              <dt>Orario della struttura</dt>
              <dd data-campo="orario">{{ formatLocalTime(s.inizio) }}–{{ formatLocalTime(s.fine) }}</dd>
            </div>
            <div><dt>Prezzo</dt><dd>{{ formatEuro(s.prezzo) }}</dd></div>
          </dl>
          <p v-if="azione === 'paziente'" class="small slot-action">
            <button type="button" class="button secondary" @click="prosegui(s)">
              Richiedi questo appuntamento<span class="visually-hidden">: {{ s.nomePrestazione }}, {{ formatLocalDate(s.inizio) }} {{ formatLocalTime(s.inizio) }}</span>
            </button>
          </p>
          <p v-else-if="azione === 'anonimo'" class="small slot-action">
            <button type="button" class="button secondary" @click="prosegui(s)">
              Accedi per richiedere l'appuntamento<span class="visually-hidden">: {{ s.nomePrestazione }}, {{ formatLocalDate(s.inizio) }} {{ formatLocalTime(s.inizio) }}</span>
            </button>
          </p>
        </li>
      </ul>
    </template>
  </section>
</template>
