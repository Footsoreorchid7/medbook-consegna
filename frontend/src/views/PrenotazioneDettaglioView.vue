<script setup>
import AnnullamentoSezione from '../components/AnnullamentoSezione.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchPrenotazioniPaziente, parseIdRisorsa } from '../api/prenotazioni.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { fetchPagamenti } from '../api/pagamenti.js'
import { fetchRimborsi } from '../api/rimborsi.js'
import PagamentiSezione from '../components/PagamentiSezione.vue'
import RefertoSezione from '../components/RefertoSezione.vue'
import RimborsoSezione from '../components/RimborsoSezione.vue'
import { haStoricoPagamenti } from '../prenotazioni/economia.js'
import { dataOrario, etichettaStato, mediciDaMostrare, nomePrestazione } from '../prenotazioni/formato.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useLetturaPaziente } from '../prenotazioni/useLetturaPaziente.js'
import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'
import { formatEuro } from '../utils/money.js'

const route = useRoute()
const store = usePrenotazioni()

// stato: 'caricamento' | 'pronto' | 'non-trovata' | 'errore'
// `aggiornata`: dati confermati dall'ultima lettura riuscita, validi per autorizzare azioni.
// Durante una rilettura in background i dati restano visibili ma le azioni sono sospese.
const lettura = reactive({ stato: 'caricamento', prenotazione: null, errore: '', aggiornata: false, ticket: null })
const titoloPrenotazione = ref(null)
const pagamentoAperto = ref(false), annullamentoAperto = ref(false)

/**
 * Letture collegate, indipendenti dalla prenotazione: un loro errore non la nasconde.
 * stato: 'inattiva' | 'caricamento' | 'pronto' | 'errore'
 * Pagamenti: `richiesta` è l'identificativo dell'ultima lettura avviata, `completata` quello
 * dell'ultima lettura applicata (successo o errore). Una lettura sostituita non viene mai
 * completata: la sezione associa così il recupero del focus alla sola lettura che ha richiesto.
 */
const pagamenti = reactive({ stato: 'inattiva', voci: [], errore: '', aggiornata: false, richiesta: 0, completata: 0, ticket: null })
const rimborsi = reactive({ stato: 'inattiva', voci: [], errore: '', aggiornata: false })
let seqPagamenti = 0
let seqRimborsi = 0
/** Chiave delle sezioni cambia con il contesto, così le sezioni vengono rimontate. */
const chiave = ref(0)

function azzeraCollegate() {
  seqPagamenti += 1
  seqRimborsi += 1
  Object.assign(pagamenti, { stato: 'inattiva', voci: [], errore: '', aggiornata: false, richiesta: 0, completata: 0, ticket: null })
  Object.assign(rimborsi, { stato: 'inattiva', voci: [], errore: '', aggiornata: false })
}

/**
 * Contesto locale della vista: un oggetto nuovo a ogni cambio di ID della rotta (anche
 * 12 → 13 → 12, senza smontaggio) e a ogni cambio di epoca. Ogni operazione lo cattura
 * all'avvio e, dopo ogni await, applica effetti locali solo se è ancora quello corrente.
 * Lo store resta indipendente: l'esito del tentativo originario vi viene comunque registrato.
 */
let contesto = {}

/** Chiude il contesto corrente e azzera lo stato locale che gli apparteneva. */
function nuovoContesto() {
  contesto = {}
  pagamentoAperto.value = false
  annullamentoAperto.value = false
  lettura.prenotazione = null
  lettura.stato = 'caricamento'
  lettura.aggiornata = false
  lettura.errore = ''
  azzeraCollegate()
  chiave.value += 1
}

const { session, idPaziente, nuovaLettura, attuale, smontato } = useLetturaPaziente({
  azzera: nuovoContesto,
  ricarica: () => carica(),
})

const corrente = (ctx) => ctx === contesto && !smontato()

const idRisorsa = computed(() => parseIdRisorsa(route.params.id))
const p = computed(() => lettura.prenotazione)
const prenotazioneAggiornata = computed(() => lettura.aggiornata && store.prenotazioneValida(p.value?.idPrenotazione, lettura.ticket))
function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

/**
 * Non esiste una lettura singola: si rilegge l'elenco personale senza filtri, che non
 * possono quindi escludere la risorsa, e si cerca l'ID. Con `verifica` la lettura riuscita
 * conclude la verifica manuale di un annullamento dall'esito incerto.
 */
async function carica({ verifica = false, ctx = contesto } = {}) {
  if (!corrente(ctx)) return false
  const id = idRisorsa.value
  const paziente = idPaziente()
  if (id === null || paziente === null) return false
  const mia = nuovaLettura()
  const ticket = store.inizioLettura()
  // Con la prenotazione già mostrata la rilettura avviene in background: le sezioni restano
  // montate, ma i dati non autorizzano azioni finché la lettura non è riuscita.
  lettura.aggiornata = false
  if (!lettura.prenotazione) lettura.stato = 'caricamento'
  lettura.errore = ''
  try {
    const elenco = await fetchPrenotazioniPaziente(session.protectedRequest, { idPaziente: paziente })
    if (!attuale(mia) || !corrente(ctx)) return false
    if (!store.prenotazioneValida(id, ticket)) throw new Error('Lettura superata')
    lettura.ticket = ticket
    lettura.prenotazione = elenco.find((x) => x.idPrenotazione === id) ?? null
    lettura.stato = lettura.prenotazione ? 'pronto' : 'non-trovata'
    lettura.aggiornata = lettura.prenotazione !== null
    if (verifica) store.annullamentoVerificato(id, paziente)
    caricaCollegate(ctx)
    return true
  } catch (error) {
    if (!attuale(mia) || !corrente(ctx) || error instanceof StaleResponseError) return false
    lettura.prenotazione = null
    lettura.errore = messaggio(error)
    lettura.stato = 'errore'
    return false
  }
}

/**
 * Lettura collegata: invalida subito l'idoneità (`aggiornata` false) lasciando visibili i
 * dati precedenti; la riabilita solo a lettura riuscita e attuale. Ogni risposta viene
 * comunque registrata nello store con il ticket di avvio, che decide se può aggiornare i
 * riscontri condivisi (mai sblocchi da risposte più vecchie del riscontro).
 */
async function leggiCollegata(ctx, lettura, seq, leggi, registra) {
  if (!corrente(ctx)) return
  lettura.aggiornata = false
  if (lettura.stato !== 'pronto') lettura.stato = 'caricamento'
  lettura.errore = ''
  const ticket = store.inizioLettura()
  try {
    const voci = await leggi()
    const applicata = registra(voci, ticket)
    if (!seq() || !corrente(ctx)) return
    if (applicata === false) {
      lettura.stato = 'errore'
      lettura.errore = 'Lettura superata: aggiorna i dati prima di procedere.'
      return true
    }
    lettura.ticket = ticket
    lettura.voci = voci
    lettura.stato = 'pronto'
    lettura.aggiornata = true
    return true
  } catch (error) {
    if (!seq() || !corrente(ctx) || error instanceof StaleResponseError) return
    lettura.voci = []
    lettura.errore = messaggio(error)
    lettura.stato = 'errore'
    lettura.aggiornata = false
    return true
  }
}

function caricaPagamenti(ctx = contesto) {
  const id = lettura.prenotazione?.idPrenotazione
  if (!id) return
  const mia = ++seqPagamenti
  if (corrente(ctx)) pagamenti.richiesta = mia
  return leggiCollegata(ctx, pagamenti, () => mia === seqPagamenti, () => fetchPagamenti(session.protectedRequest, id),
    (voci, ticket) => store.registraStorico(id, voci, ticket))
    // Completamento esplicito, nello stesso passo dell'applicazione: solo se ancora l'ultima lettura.
    .then((applicata) => { if (applicata && mia === seqPagamenti && corrente(ctx)) pagamenti.completata = mia })
}

function caricaRimborsi(ctx = contesto) {
  const mia = ++seqRimborsi
  return leggiCollegata(ctx, rimborsi, () => mia === seqRimborsi, () => fetchRimborsi(session.protectedRequest),
    (voci, ticket) => store.registraRimborsi(voci, ticket))
}

/** Pagamenti dagli stati in cui possono esistere; rimborsi solo per prenotazioni NON_EROGATA. */
function caricaCollegate(ctx) {
  const stato = lettura.prenotazione?.stato
  if (!stato) {
    azzeraCollegate()
    return
  }
  if (haStoricoPagamenti(stato)) caricaPagamenti(ctx)
  if (stato === 'NON_EROGATA') caricaRimborsi(ctx)
}

/** Rilettura richiesta da una sezione dopo un esito certo, sempre nel contesto corrente. */
function rileggi(risorse) {
  const ctx = contesto
  // Invalidazione sincrona: nessuna azione resta autorizzata dai dati precedenti mentre si rilegge.
  if (risorse.includes('prenotazione')) lettura.aggiornata = false
  if (risorse.includes('pagamenti')) pagamenti.aggiornata = false
  if (risorse.includes('rimborsi')) rimborsi.aggiornata = false
  if (risorse.includes('prenotazione')) {
    // La rilettura della prenotazione rilegge anche pagamenti e rimborsi pertinenti.
    carica({ ctx })
    return
  }
  if (risorse.includes('pagamenti')) caricaPagamenti(ctx)
  if (risorse.includes('rimborsi')) caricaRimborsi(ctx)
}

const quandoRichiesta = computed(() => (p.value ? `${formatLocalDate(p.value.richiesta)}, ${formatLocalTime(p.value.richiesta)}` : ''))

onMounted(() => carica())
// Stessa vista per un altro ID: nuova lettura, nessun dato della precedente.
watch(() => route.params.id, () => {
  if (route.name !== 'prenotazione-dettaglio') return
  nuovoContesto()
  carica()
})
</script>

<template>
  <h1>Dettaglio prenotazione</h1>
  <p><RouterLink to="/paziente/prenotazioni">Torna alle mie prenotazioni</RouterLink></p>

  <div v-if="idRisorsa === null" class="notice error" role="alert">
    <p><strong>Prenotazione non trovata.</strong> L'indirizzo non contiene un identificativo valido.</p>
  </div>

  <template v-else>
    <p v-if="lettura.stato === 'caricamento'" role="status">Caricamento della prenotazione…</p>

    <div v-else-if="lettura.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare la prenotazione. {{ lettura.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="carica()">Riprova</button></div>
    </div>

    <div v-else-if="lettura.stato === 'non-trovata'" class="notice error" role="alert">
      <p><strong>Prenotazione non trovata.</strong> Non risulta tra le tue prenotazioni.</p>
    </div>

    <template v-else-if="p">
      <div v-if="lettura.aggiornata && !prenotazioneAggiornata" class="notice warning" role="status">
        <p>La prenotazione è stata superata da un'altra operazione: aggiorna prima di procedere.</p>
        <button type="button" class="button secondary" @click="carica()">Aggiorna prenotazione</button>
      </div>
      <section class="card" aria-labelledby="titolo-prenotazione" data-prenotazione>
        <p><strong>Prenotazione n. {{ p.idPrenotazione }}</strong></p>
        <h2 id="titolo-prenotazione" ref="titoloPrenotazione" tabindex="-1" class="item-title">{{ nomePrestazione(p) }}</h2>
        <dl class="facts">
          <div><dt>Stato</dt><dd data-campo="stato">{{ etichettaStato(p.stato) }}</dd></div>
          <div><dt>Data e orario della struttura</dt><dd data-campo="quando">{{ dataOrario(p.inizio, p.fine) }}</dd></div>
          <div v-if="p.durata"><dt>Durata</dt><dd>{{ p.durata }} minuti</dd></div>
          <div><dt>Prezzo</dt><dd>{{ formatEuro(p.prezzo) }}</dd></div>
          <div v-if="p.stanza"><dt>Stanza</dt><dd data-campo="stanza">{{ p.stanza.nome ?? 'Stanza assegnata' }}</dd></div>
          <div v-for="m in mediciDaMostrare(p)" :key="m.etichetta"><dt>{{ m.etichetta }}</dt><dd data-campo="medico">{{ m.nome }}</dd></div>
          <div><dt>Richiesta inviata</dt><dd>{{ quandoRichiesta }}</dd></div>
          <div><dt>Numero prenotazione</dt><dd>{{ p.idPrenotazione }}</dd></div>
        </dl>
        <p v-if="p.stato === 'RICHIESTA'" class="muted small">
          La richiesta è in attesa di gestione da parte della segreteria: non è ancora un appuntamento confermato.
        </p>
      </section>

      <AnnullamentoSezione :key="`annullamento-${chiave}`" :prenotazione="p" :aggiornata="prenotazioneAggiornata"
                            :storico="pagamenti" :ticket="lettura.ticket" :destinazione-focus="() => titoloPrenotazione" :ricarica="verifica => carica({ verifica })" :incompatibile="pagamentoAperto" @fase="annullamentoAperto = $event" />

      <PagamentiSezione v-if="pagamenti.stato !== 'inattiva'" :key="`pagamenti-${chiave}`" :prenotazione="p"
                        :storico="pagamenti" :prenotazione-aggiornata="prenotazioneAggiornata" :incompatibile="annullamentoAperto" @fase="pagamentoAperto = $event" @rileggi="rileggi" />
      <RimborsoSezione v-if="p.stato === 'NON_EROGATA' || route.hash === '#titolo-rimborso'" :key="`rimborso-${chiave}`" :prenotazione="p"
                       :storico="pagamenti" :rimborsi="rimborsi" :focus-richiesto="route.hash === '#titolo-rimborso'" :prenotazione-aggiornata="prenotazioneAggiornata" @rileggi="rileggi" />
      <RefertoSezione v-if="p.stato === 'EFFETTUATA'" :key="`referto-${chiave}`" :id-prenotazione="p.idPrenotazione" />
    </template>
  </template>
</template>
