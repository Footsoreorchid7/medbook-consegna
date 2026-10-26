<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { sceltaStore } from '../prenotazioni/scelta.js'
import { fetchDisponibilitaPrenotabili, fetchPrestazioni } from '../api/catalogo.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { parseIdRisorsa } from '../api/prenotazioni.js'
import { dataOrario, etichettaStato } from '../prenotazioni/formato.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useLetturaPaziente } from '../prenotazioni/useLetturaPaziente.js'
import { isLocalDate } from '../utils/datetime.js'
import { formatEuro } from '../utils/money.js'

const route = useRoute()
const router = useRouter()
const store = usePrenotazioni()

const catalogo = reactive({ stato: 'caricamento', voci: [] })
const filtri = reactive({ prestazioneId: '', data: '' })
const erroreFiltri = ref('')
const ricerca = reactive({ stato: 'caricamento', risultati: [], errore: '' })
let ultimiFiltri = {}
let controller = null
let catalogoController = null

// 'scelta' | 'conferma' | 'invio' | 'inviata'
const fase = ref('scelta')
const selezione = ref('')
const erroreSelezione = ref('')
/** Slot di cui si chiede conferma: dati di riepilogo, mai l'identità. */
const slotScelto = ref(null)
const inviata = ref(null)
// { tipo: 'successo'|'errore'|'info', testo, link? }
const avviso = ref(null)
// Preselezione da query: { id, esito: 'attesa'|'trovata'|'bloccata'|'assente'|'non-valida' }
const preselezione = ref(null)

const avvisoBox = ref(null)
const titoloConferma = ref(null)
const titoloInviata = ref(null)
const titoloIncerte = ref(null)
const titoloOrari = ref(null)
const bottoneContinua = ref(null)
/** Pulsanti «Invia una nuova richiesta» per idDisponibilita, per il ripristino del focus. */
const bottoniNuova = new Map()
function registraNuova(id, el) {
  if (el) bottoniNuova.set(id, el)
  else bottoniNuova.delete(id)
}
/** Controllo che ha aperto la conferma: { tipo: 'continua' } | { tipo: 'nuova', id }. */
let origineConferma = { tipo: 'continua' }

/**
 * Contesto locale della scelta: un oggetto nuovo a ogni cambio effettivo della query
 * `slot`, a ogni cambio di epoca e a ogni nuova scelta (conferma aperta, modifica, nuova
 * richiesta). Le operazioni lo catturano all'avvio e, dopo ogni await, toccano fase,
 * selezione, riepilogo, avvisi o focus solo se è ancora quello corrente. L'esito del
 * tentativo resta comunque registrato nello store (anti-doppio invio, incertezza).
 */
let contesto = {}

/** Chiude il contesto corrente e azzera lo stato della scelta che gli apparteneva. */
function nuovoContesto() {
  contesto = {}
  fase.value = 'scelta'
  selezione.value = ''
  erroreSelezione.value = ''
  slotScelto.value = null
  inviata.value = null
  avviso.value = null
}

const { session, nuovaLettura, attuale, smontato } = useLetturaPaziente({
  azzera() {
    nuovoContesto()
    ricerca.risultati = []
    ricerca.stato = 'caricamento'
  },
  ricarica() {
    caricaCatalogo()
    esegui(ultimiFiltri)
  },
})

// Ingresso compatibile per i vecchi link: la scelta può proseguire nell'elenco personale.
function completaNellElenco() {
  const id = parseIdRisorsa(route.query.slot)
  if (id !== null && sceltaStore(session).scegli({ idDisponibilita: id })) router.push('/paziente/prenotazioni')
}

const incerte = computed(() => store.state.richiesteIncerte)
const altroInvio = computed(() => store.state.invioInCorso !== null && fase.value !== 'invio')
const puoInviare = computed(() => fase.value === 'conferma' && slotScelto.value !== null
  && store.state.invioInCorso === null && !store.richiestaBloccata(slotScelto.value.idDisponibilita)
  && !session.state.passwordUncertain && !session.state.busy)

const corrente = (ctx) => ctx === contesto && !smontato()

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

async function mostra(ctx, tipo, testo, link = null) {
  if (!corrente(ctx)) return
  avviso.value = { tipo, testo, link }
  await nextTick()
  if (corrente(ctx)) avvisoBox.value?.focus()
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
      isCurrent: () => catalogoController === corrente && session.state.epoch === epoca && !smontato(),
    })
    catalogo.stato = 'pronto'
  } catch (error) {
    if (catalogoController === corrente && !smontato() && !(error instanceof StaleResponseError)) catalogo.stato = 'errore'
  }
}

async function cerca() {
  erroreFiltri.value = ''
  if (filtri.data !== '' && !isLocalDate(filtri.data)) {
    erroreFiltri.value = 'Inserisci una data valida.'
    return
  }
  await esegui({
    prestazioneId: filtri.prestazioneId === '' ? null : Number(filtri.prestazioneId),
    data: filtri.data === '' ? null : filtri.data,
  })
}

/** Ricerca pubblica degli slot: ogni ricerca annulla e scarta la precedente. */
async function esegui(applicati) {
  ultimiFiltri = applicati
  const mia = nuovaLettura()
  controller?.abort()
  const corrente = new AbortController()
  controller = corrente
  ricerca.stato = 'caricamento'
  ricerca.errore = ''
  try {
    const risultati = await fetchDisponibilitaPrenotabili(applicati, { signal: corrente.signal, isCurrent: () => attuale(mia) })
    ricerca.risultati = risultati
    ricerca.stato = 'pronto'
    // La selezione resta solo se lo slot è ancora tra i risultati appena letti.
    if (selezione.value !== '' && !risultati.some((s) => String(s.idDisponibilita) === selezione.value)) selezione.value = ''
    applicaPreselezione()
  } catch (error) {
    if (!attuale(mia) || error instanceof StaleResponseError) return
    ricerca.errore = messaggio(error)
    ricerca.stato = 'errore'
  }
}

/**
 * Un ID slot in query è solo una preselezione: vale se lo slot compare nella ricerca
 * appena letta dal server, e la richiesta resta comunque da confermare.
 */
function leggiPreselezione() {
  const raw = route.query.slot
  if (raw === undefined) {
    preselezione.value = null
    return
  }
  const id = typeof raw === 'string' ? parseIdRisorsa(raw) : null
  preselezione.value = id === null ? { id: null, esito: 'non-valida' } : { id, esito: 'attesa' }
}

function applicaPreselezione() {
  const pre = preselezione.value
  if (!pre || pre.esito !== 'attesa') return
  const trovato = ricerca.risultati.find((s) => s.idDisponibilita === pre.id)
  // Con un tentativo incerto registrato per lo slot nessuna preselezione: il nuovo invio resta una scelta esplicita.
  if (trovato && store.richiestaIncerta(trovato.idDisponibilita) !== null) {
    preselezione.value = { ...pre, esito: 'bloccata' }
  } else if (trovato) {
    selezione.value = String(trovato.idDisponibilita)
    preselezione.value = { ...pre, esito: 'trovata' }
  } else {
    preselezione.value = { ...pre, esito: 'assente' }
  }
}

async function apriConferma(slot, origine = { tipo: 'continua' }) {
  origineConferma = origine
  const scelta = selezione.value
  nuovoContesto()
  selezione.value = scelta
  const ctx = contesto
  slotScelto.value = slot
  fase.value = 'conferma'
  await nextTick()
  if (corrente(ctx)) titoloConferma.value?.focus()
}

function continua() {
  erroreSelezione.value = ''
  const slot = ricerca.risultati.find((s) => String(s.idDisponibilita) === selezione.value)
  if (!slot) {
    erroreSelezione.value = 'Seleziona un orario tra quelli disponibili.'
    return
  }
  apriConferma(slot)
}

/**
 * Torna alla scelta e, a DOM aggiornato, riporta il focus al controllo che aveva aperto la
 * conferma, se ancora presente e abilitato; altrimenti a un titolo pertinente della vista.
 * Un cambio di query o di epoca nel frattempo apre un nuovo contesto: nessuno spostamento.
 */
async function modificaScelta() {
  const scelta = selezione.value
  const origine = origineConferma
  nuovoContesto()
  selezione.value = scelta
  const ctx = contesto
  await nextTick()
  if (!corrente(ctx)) return
  const usabile = (el) => (el && el.isConnected && !el.disabled ? el : null)
  const bersaglio = origine.tipo === 'nuova'
    ? usabile(bottoniNuova.get(origine.id)) ?? titoloIncerte.value ?? titoloOrari.value
    : usabile(bottoneContinua.value) ?? titoloOrari.value
  bersaglio?.focus()
}

/**
 * Avviso per uno slot preselezionato con un tentativo incerto, allineato allo store:
 * da verificare, oppure verificato senza corrispondenze (non conclusivo).
 */
const avvisoBloccata = computed(() => {
  const pre = preselezione.value
  if (pre?.esito !== 'bloccata') return null
  const r = store.richiestaIncerta(pre.id)
  if (!r) return null
  if (r.verifica === 'assente' || r.verifica === 'solo-chiuse') return 'verificata'
  return 'da-verificare'
})

const ESITI = {
  conflitto: "L'orario scelto non è più disponibile oppure non può essere prenotato. L'elenco è stato aggiornato: scegline un altro.",
  'non-trovata': "L'orario scelto non è stato trovato. L'elenco è stato aggiornato: scegline un altro.",
  'non-valida': 'Richiesta non valida.',
  rifiutata: 'Operazione non consentita. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le richieste sono sospese finché non verifichi l'esito del cambio password.",
  occupato: "Un'altra richiesta di appuntamento è in corso. Attendi il suo esito.",
  bloccata: "L'esito di una richiesta precedente per questo orario è ancora da verificare.",
}

async function invia() {
  if (!puoInviare.value) return
  const ctx = contesto
  const slot = slotScelto.value
  fase.value = 'invio'
  const esito = await store.richiedi(slot)
  // Contesto cambiato durante l'invio: nessun effetto locale; lo store ha già registrato l'esito.
  if (!corrente(ctx)) return
  switch (esito.kind) {
    case 'inviata':
      inviata.value = esito.prenotazione
      selezione.value = ''
      slotScelto.value = null
      fase.value = 'inviata'
      await nextTick()
      if (corrente(ctx)) titoloInviata.value?.focus()
      break
    case 'conflitto':
    case 'non-trovata':
      fase.value = 'scelta'
      slotScelto.value = null
      selezione.value = ''
      await mostra(ctx, 'errore', ESITI[esito.kind])
      if (corrente(ctx)) esegui(ultimiFiltri)
      break
    case 'incerto':
      fase.value = 'scelta'
      slotScelto.value = null
      selezione.value = ''
      await nextTick()
      if (corrente(ctx)) titoloIncerte.value?.focus()
      break
    case 'sessione-assente':
    case 'obsoleto':
      // Sessione persa o cambiata: il router o il cambio di epoca hanno già chiuso o azzerato la vista.
      break
    case 'password-incerta':
      fase.value = 'conferma'
      await mostra(ctx, 'errore', ESITI[esito.kind])
      break
    default:
      fase.value = 'scelta'
      slotScelto.value = null
      await mostra(ctx, 'errore', ESITI[esito.kind] ?? ESITI.rifiutata)
  }
}

function nuovaRichiesta() {
  nuovoContesto()
  esegui(ultimiFiltri)
}

async function verifica(r) {
  const ctx = contesto
  avviso.value = null
  const esito = await store.verificaRichiesta(r.idDisponibilita)
  if (!corrente(ctx)) return
  if (esito.kind === 'trovata') {
    const p = esito.prenotazione
    await mostra(ctx, 'successo', `Per questo orario risulta una tua prenotazione, stato: ${etichettaStato(p.stato)}.`,
      { to: `/paziente/prenotazioni/${p.idPrenotazione}`, testo: 'Vedi il dettaglio' })
  }
}

function riepilogo(s) {
  return `${s.nomePrestazione}, ${s.nomeMedico} ${s.cognomeMedico}, ${dataOrario(s.inizio, s.fine)}, ${formatEuro(s.prezzo)}`
}

onMounted(() => {
  leggiPreselezione()
  caricaCatalogo()
  esegui({})
})
// Nuova preselezione con la vista già aperta: nuovo contesto, poi ricerca senza filtri.
watch(() => route.query.slot, () => {
  if (route.name !== 'prenota') return
  nuovoContesto()
  leggiPreselezione()
  if (preselezione.value?.esito === 'attesa') {
    filtri.prestazioneId = ''
    filtri.data = ''
    esegui({})
  }
})
onBeforeUnmount(() => {
  controller?.abort()
  catalogoController?.abort()
})
</script>

<template>
  <h1>Richiedi un appuntamento</h1>
  <p v-if="typeof route.query.slot === 'string' && parseIdRisorsa(route.query.slot) !== null">
    <button type="button" class="button secondary" @click="completaNellElenco">Completa la scelta in Le mie prenotazioni</button>
  </p>
  <p>
    Scegli un orario libero e conferma la richiesta. La segreteria la valuterà: fino ad allora non è un
    appuntamento confermato.
  </p>
  <p class="muted">
    Orari espressi nell'orario della struttura (fuso orario da confermare). La disponibilità viene verificata
    di nuovo dal server all'invio della richiesta.
  </p>
  <p><RouterLink to="/paziente/prenotazioni">Le mie prenotazioni</RouterLink></p>

  <div v-if="avviso" ref="avvisoBox" tabindex="-1"
       :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'">
    <p>{{ avviso.testo }}</p>
    <p v-if="avviso.link"><RouterLink :to="avviso.link.to">{{ avviso.link.testo }}</RouterLink></p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <div v-if="session.state.passwordUncertain && !avviso" class="notice warning" role="status">
    <p>Le richieste sono sospese finché non verifichi l'esito del cambio password.</p>
    <p><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <div v-if="altroInvio" class="notice" role="status">
    <p>Una richiesta di appuntamento è in corso di invio. Attendi il suo esito prima di inviarne un'altra.</p>
  </div>

  <section v-if="incerte.length" class="card" aria-labelledby="titolo-incerte">
    <h2 id="titolo-incerte" ref="titoloIncerte" tabindex="-1">Richieste da verificare</h2>
    <div v-for="r in incerte" :key="r.idDisponibilita" class="notice warning" role="alert" data-incerta>
      <p><strong>Non è stato possibile stabilire l'esito della richiesta</strong> per: {{ riepilogo(r.riepilogo) }}.</p>
      <p>
        Il server potrebbe averla registrata. Non è stata ripetuta automaticamente e non può essere inviata di nuovo
        prima di una verifica nelle tue prenotazioni.
      </p>
      <p v-if="r.verifica === 'assente'">
        Nelle tue prenotazioni non risulta una richiesta per questo orario. Questo non prova che l'invio non sia
        stato registrato. Puoi verificare di nuovo oppure inviare una nuova richiesta: se la precedente fosse stata
        registrata, il server la rifiuterebbe perché l'orario risulta occupato.
      </p>
      <p v-else-if="r.verifica === 'solo-chiuse'">
        Per questo orario risultano soltanto prenotazioni annullate o rifiutate. Questo non prova che l'ultimo invio
        non sia stato registrato. Puoi verificare di nuovo oppure inviare una nuova richiesta.
      </p>
      <p v-else-if="r.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="r.verifica === 'in-corso'" @click="verifica(r)">
          {{ r.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica nelle mie prenotazioni' }}
        </button>
        <button v-if="r.verifica === 'assente' || r.verifica === 'solo-chiuse'" type="button" class="button secondary"
                :disabled="fase === 'invio' || store.state.invioInCorso !== null" :ref="(el) => registraNuova(r.idDisponibilita, el)"
                @click="apriConferma(r.riepilogo, { tipo: 'nuova', id: r.idDisponibilita })">
          Invia una nuova richiesta
        </button>
      </div>
    </div>
  </section>

  <section v-if="fase === 'inviata' && inviata" class="notice" role="status" aria-labelledby="titolo-inviata" data-esito="inviata">
    <h2 id="titolo-inviata" ref="titoloInviata" tabindex="-1">Richiesta inviata</h2>
    <p>
      La richiesta è stata registrata ed è in attesa di gestione da parte della segreteria.
      <strong>Non è ancora un appuntamento confermato.</strong>
    </p>
    <dl class="facts">
      <div><dt>Prestazione</dt><dd>{{ inviata.nomePrestazione }}</dd></div>
      <div><dt>Medico</dt><dd>{{ inviata.nomeMedico }} {{ inviata.cognomeMedico }}</dd></div>
      <div><dt>Data e orario della struttura</dt><dd>{{ dataOrario(inviata.inizio, inviata.fine) }}</dd></div>
      <div><dt>Prezzo</dt><dd>{{ formatEuro(inviata.prezzo) }}</dd></div>
      <div><dt>Stato</dt><dd>{{ etichettaStato(inviata.stato) }}</dd></div>
    </dl>
    <div class="actions">
      <RouterLink class="button" :to="`/paziente/prenotazioni/${inviata.idPrenotazione}`">Vedi il dettaglio</RouterLink>
      <button type="button" class="button secondary" @click="nuovaRichiesta">Richiedi un altro appuntamento</button>
    </div>
  </section>

  <section v-else-if="(fase === 'conferma' || fase === 'invio') && slotScelto" class="card" aria-labelledby="titolo-conferma"
           :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma>
    <h2 id="titolo-conferma" ref="titoloConferma" tabindex="-1">Conferma la richiesta</h2>
    <dl class="facts">
      <div><dt>Prestazione</dt><dd data-campo="prestazione">{{ slotScelto.nomePrestazione }}</dd></div>
      <div><dt>Medico</dt><dd data-campo="medico">{{ slotScelto.nomeMedico }} {{ slotScelto.cognomeMedico }}</dd></div>
      <div><dt>Data e orario della struttura</dt><dd data-campo="quando">{{ dataOrario(slotScelto.inizio, slotScelto.fine) }}</dd></div>
      <div><dt>Prezzo</dt><dd data-campo="prezzo">{{ formatEuro(slotScelto.prezzo) }}</dd></div>
    </dl>
    <p class="small">
      Inviando la richiesta, l'orario viene riservato in attesa della decisione della segreteria.
    </p>
    <div class="actions">
      <button type="button" class="button" :disabled="!puoInviare" @click="invia">
        {{ fase === 'invio' ? 'Invio in corso…' : 'Invia richiesta' }}
      </button>
      <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="modificaScelta">
        Modifica la scelta
      </button>
    </div>
  </section>

  <template v-else>
    <section class="card" aria-labelledby="titolo-filtri">
      <h2 id="titolo-filtri">Cerca un orario</h2>
      <form class="form filters" novalidate @submit.prevent="cerca">
        <div class="field">
          <label for="prenota-prestazione">Prestazione</label>
          <select id="prenota-prestazione" v-model="filtri.prestazioneId" :disabled="catalogo.stato !== 'pronto'"
                  aria-describedby="prenota-stato-catalogo">
            <option value="">Tutte le prestazioni</option>
            <option v-for="c in catalogo.voci" :key="c.idPrestazione" :value="String(c.idPrestazione)">{{ c.nome }}</option>
          </select>
          <p id="prenota-stato-catalogo" class="muted small">
            <template v-if="catalogo.stato === 'caricamento'">Caricamento delle prestazioni…</template>
            <template v-else-if="catalogo.stato === 'errore'">
              Elenco delle prestazioni non disponibile.
              <button type="button" class="link-button" @click="caricaCatalogo">Riprova</button>
            </template>
          </p>
        </div>
        <div class="field">
          <label for="prenota-data">Data</label>
          <input id="prenota-data" v-model="filtri.data" type="date" min="1000-01-01" max="9999-12-30"
                 :aria-invalid="erroreFiltri ? 'true' : undefined"
                 :aria-describedby="erroreFiltri ? 'prenota-errore-filtri' : undefined" />
          <p v-if="erroreFiltri" id="prenota-errore-filtri" class="field-error" role="alert">{{ erroreFiltri }}</p>
        </div>
        <div>
          <button type="submit" class="button secondary">Cerca</button>
        </div>
      </form>
    </section>

    <section aria-labelledby="titolo-orari" :aria-busy="ricerca.stato === 'caricamento' ? 'true' : 'false'">
      <h2 id="titolo-orari" ref="titoloOrari" tabindex="-1">Orari disponibili</h2>
      <p v-if="preselezione?.esito === 'non-valida'" class="notice error" role="alert">
        L'orario indicato nell'indirizzo non è valido. Scegline uno tra quelli elencati.
      </p>
      <p v-else-if="preselezione?.esito === 'assente'" class="notice error" role="alert">
        L'orario indicato non risulta più disponibile. Scegline un altro tra quelli elencati.
      </p>
      <p v-else-if="avvisoBloccata === 'da-verificare'" class="notice warning" role="status" data-avviso-preselezione>
        L'orario indicato ha una richiesta precedente da verificare: usa la verifica in «Richieste da verificare».
      </p>
      <p v-else-if="avvisoBloccata === 'verificata'" class="notice warning" role="status" data-avviso-preselezione>
        Per l'orario indicato la verifica non ha trovato una richiesta registrata: questo non prova che l'invio precedente
        sia fallito. Un nuovo invio è possibile solo scegliendolo esplicitamente e confermandolo.
      </p>
      <p v-else-if="preselezione?.esito === 'trovata' && selezione !== ''" class="notice" role="status">
        Orario preselezionato. Controlla i dati e prosegui per confermare.
      </p>

      <p v-if="ricerca.stato === 'caricamento'" role="status">Ricerca in corso…</p>
      <div v-else-if="ricerca.stato === 'errore'" class="notice error" role="alert">
        <p>Non è stato possibile caricare gli orari disponibili. {{ ricerca.errore }}</p>
        <div class="actions"><button type="button" class="button" @click="esegui(ultimiFiltri)">Riprova</button></div>
      </div>
      <p v-else-if="ricerca.risultati.length === 0" role="status">Nessun orario disponibile per i filtri scelti.</p>
      <form v-else novalidate @submit.prevent="continua">
        <fieldset class="fieldset-form">
          <legend>Scegli un orario</legend>
          <ul class="items">
            <li v-for="s in ricerca.risultati" :key="s.idDisponibilita" class="card" data-disponibilita>
              <label class="slot-choice">
                <input v-model="selezione" type="radio" name="slot" :value="String(s.idDisponibilita)"
                       :disabled="store.richiestaBloccata(s.idDisponibilita)"
                       :aria-describedby="erroreSelezione ? 'errore-selezione' : undefined" />
                <span>
                  <strong>{{ s.nomePrestazione }}</strong><br />
                  {{ s.nomeMedico }} {{ s.cognomeMedico }} · {{ dataOrario(s.inizio, s.fine) }} · {{ formatEuro(s.prezzo) }}
                </span>
              </label>
              <p v-if="store.richiestaBloccata(s.idDisponibilita)" class="small">
                Esito di una richiesta precedente da verificare.
              </p>
            </li>
          </ul>
        </fieldset>
        <p v-if="erroreSelezione" id="errore-selezione" class="field-error" role="alert">{{ erroreSelezione }}</p>
        <div class="form-actions">
          <button ref="bottoneContinua" type="submit" class="button" :disabled="altroInvio">Continua</button>
        </div>
      </form>
    </section>
  </template>
</template>
