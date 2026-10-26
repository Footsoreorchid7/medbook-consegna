<script setup>
import { stileStatoAgenda } from '../medico/statiAgenda.js'
import LegendaPrenotazioni from '../components/LegendaPrenotazioni.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import PagamentoPrenotazione from '../components/PagamentoPrenotazione.vue'
import { fetchRimborsi } from '../api/rimborsi.js'
import CompletaRichiesta from '../components/CompletaRichiesta.vue'
import { sceltaStore } from '../prenotazioni/scelta.js'
import { STATI, fetchPrenotazioniPaziente } from '../api/prenotazioni.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { ETICHETTE_STATO, dataOrario, etichettaStato, mediciDaMostrare, nomePrestazione } from '../prenotazioni/formato.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useLetturaPaziente } from '../prenotazioni/useLetturaPaziente.js'
import { isLocalDate } from '../utils/datetime.js'
import { formatEuro } from '../utils/money.js'

const store = usePrenotazioni()

const filtri = reactive({ stato: '', periodo: 'tutte', data: '', dataDa: '', dataA: '' })
const errori = ref({})
const erroreFiltriBox = ref(null)
const nuovaRichiestaBox = ref(null)
let sequenzaElenco = 0
const elenco = reactive({ stato: 'caricamento', voci: [], errore: '', applicati: null, ticket: null })
// Filtri dell'ultima lettura avviata: servono per Riprova e dopo un cambio di sessione.
let ultimiFiltri = {}
const rimborsi = reactive({ stato: 'inattiva', voci: [], errore: '', aggiornata: false, ticket: null })
let seqRimborsi = 0
async function caricaRimborsi() {
  if (rimborsi.stato === 'caricamento') return
  const mia = ++seqRimborsi, epoca = session.state.epoch
  rimborsi.stato = 'caricamento'
  rimborsi.aggiornata = false
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchRimborsi(session.protectedRequest)
    if (smontato() || mia !== seqRimborsi || epoca !== session.state.epoch) return
    store.registraRimborsi(voci, ticket)
    Object.assign(rimborsi, { voci, ticket, stato: 'pronto', aggiornata: true })
  } catch {
    if (!smontato() && mia === seqRimborsi && epoca === session.state.epoch) rimborsi.stato = 'errore'
  }
}

const { session, idPaziente, nuovaLettura, attuale, smontato } = useLetturaPaziente({
  azzera() {
    seqRimborsi += 1
    Object.assign(rimborsi, { stato: 'inattiva', voci: [], aggiornata: false, ticket: null })
    elenco.voci = []
    elenco.applicati = null
    elenco.stato = 'caricamento'
  },
  ricarica: () => esegui(ultimiFiltri),
})

const scelta = sceltaStore(session)
watch(() => scelta.state.ultimaCreata, async (id) => {
  if (id === null) return
  const epoca = session.state.epoch
  const lettura = esegui(ultimiFiltri)
  const mia = sequenzaElenco
  await lettura
  await nextTick()
  if (smontato() || epoca !== session.state.epoch || mia !== sequenzaElenco || id !== scelta.state.ultimaCreata) return
  const active = document.activeElement
  if (!active || active === document.body || !active.isConnected) nuovaRichiestaBox.value?.focus()
})

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

function dataValida(valore) {
  return isLocalDate(valore) && Number(valore.slice(0, 4)) >= 1000 && valore !== '9999-12-31'
}

/** Solo i filtri valorizzati e compatibili; il periodo scelto esclude gli altri campi data. */
function validaFiltri() {
  const e = {}
  const scelti = { stato: filtri.stato === '' ? null : filtri.stato }
  if (filtri.periodo === 'singola') {
    if (!dataValida(filtri.data)) e.data = 'Inserisci una data valida.'
    else scelti.data = filtri.data
  } else if (filtri.periodo === 'intervallo') {
    if (!dataValida(filtri.dataDa)) e.dataDa = 'Inserisci una data di inizio valida.'
    if (!dataValida(filtri.dataA)) e.dataA = 'Inserisci una data di fine valida.'
    if (!e.dataDa && !e.dataA) {
      if (filtri.dataDa > filtri.dataA) e.dataA = 'La data di fine non può precedere quella di inizio.'
      else {
        scelti.dataDa = filtri.dataDa
        scelti.dataA = filtri.dataA
      }
    }
  }
  return { errori: e, scelti }
}

async function cerca() {
  const { errori: e, scelti } = validaFiltri()
  errori.value = e
  if (Object.keys(e).length > 0) {
    await nextTick()
    erroreFiltriBox.value?.focus()
    return
  }
  await esegui(scelti)
}

async function esegui(scelti) {
  ultimiFiltri = scelti
  const id = idPaziente()
  if (id === null) return
  const mia = nuovaLettura()
  const ticket = store.inizioLettura()
  sequenzaElenco += 1
  elenco.stato = 'caricamento'
  elenco.errore = ''
  try {
    const voci = await fetchPrenotazioniPaziente(session.protectedRequest, { idPaziente: id, ...scelti })
    if (!attuale(mia)) return
    elenco.ticket = ticket
    elenco.voci = voci
    if (voci.some(x => x.stato === 'NON_EROGATA')) caricaRimborsi()
    elenco.applicati = scelti
    elenco.stato = 'pronto'
  } catch (error) {
    if (!attuale(mia) || error instanceof StaleResponseError) return
    elenco.errore = messaggio(error)
    elenco.stato = 'errore'
  }
}

const descrizioneFiltri = computed(() => {
  const a = elenco.applicati
  if (!a) return ''
  const parti = []
  const it = (d) => d.split('-').reverse().join('/')
  if (a.stato) parti.push(`stato: ${ETICHETTE_STATO[a.stato]}`)
  if (a.data) parti.push(`data: ${it(a.data)}`)
  if (a.dataDa) parti.push(`dal ${it(a.dataDa)} al ${it(a.dataA)}`)
  return parti.length ? parti.join(', ') : 'nessun filtro'
})

// Copia locale per la presentazione: nessuna modifica all'ordine delle API o dello store.
const prenotazioniOrdinate = computed(() => [...elenco.voci].sort((a, b) => a.idPrenotazione - b.idPrenotazione))

const erroriElenco = computed(() => Object.values(errori.value))

onMounted(() => esegui({}))
</script>

<template>
  <h1>Le mie prenotazioni</h1>
  <p><RouterLink to="/disponibilita">Richiedi un nuovo appuntamento</RouterLink></p>
  <CompletaRichiesta @rileggi="esegui(ultimiFiltri)" />
  <div v-if="scelta.state.ultimaCreata" ref="nuovaRichiestaBox" tabindex="-1" class="notice" role="status" data-nuova-richiesta>
    <p><strong>Richiesta inviata — Prenotazione n. {{ scelta.state.ultimaCreata }}.</strong> Non è ancora un appuntamento confermato: attendi la conferma della segreteria.</p>
    <RouterLink :to="`/paziente/prenotazioni/${scelta.state.ultimaCreata}`">Apri la nuova richiesta</RouterLink>
  </div>
  <p class="muted">Orari espressi nell'orario della struttura (fuso orario da confermare).</p>

  <section class="card" aria-labelledby="titolo-filtri">
    <h2 id="titolo-filtri">Filtri</h2>
    <form class="form" novalidate @submit.prevent="cerca">
      <div v-if="erroriElenco.length" ref="erroreFiltriBox" class="notice error" role="alert" tabindex="-1">
        <p>Controlla i filtri indicati.</p>
      </div>
      <div class="field">
        <label for="filtro-stato">Stato</label>
        <select id="filtro-stato" v-model="filtri.stato">
          <option value="">Tutti gli stati</option>
          <option v-for="s in STATI" :key="s" :value="s">{{ ETICHETTE_STATO[s] }}</option>
        </select>
      </div>
      <fieldset class="fieldset-form">
        <legend>Periodo dell'appuntamento</legend>
        <div class="choices">
          <label><input v-model="filtri.periodo" type="radio" name="periodo" value="tutte" /> Tutte le date</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo" value="singola" /> Una data</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo" value="intervallo" /> Un intervallo di date</label>
        </div>
        <div v-if="filtri.periodo === 'singola'" class="field">
          <label for="filtro-data">Data</label>
          <input id="filtro-data" v-model="filtri.data" type="date" min="1000-01-01" max="9999-12-30"
                 :aria-invalid="errori.data ? 'true' : undefined"
                 :aria-describedby="errori.data ? 'errore-data' : undefined" />
          <p v-if="errori.data" id="errore-data" class="field-error">{{ errori.data }}</p>
        </div>
        <template v-else-if="filtri.periodo === 'intervallo'">
          <div class="field">
            <label for="filtro-dal">Dal</label>
            <input id="filtro-dal" v-model="filtri.dataDa" type="date" min="1000-01-01" max="9999-12-30"
                   :aria-invalid="errori.dataDa ? 'true' : undefined"
                   :aria-describedby="errori.dataDa ? 'errore-dal' : undefined" />
            <p v-if="errori.dataDa" id="errore-dal" class="field-error">{{ errori.dataDa }}</p>
          </div>
          <div class="field">
            <label for="filtro-al">Al</label>
            <input id="filtro-al" v-model="filtri.dataA" type="date" min="1000-01-01" max="9999-12-30"
                   :aria-invalid="errori.dataA ? 'true' : undefined"
                   :aria-describedby="errori.dataA ? 'errore-al' : undefined" />
            <p v-if="errori.dataA" id="errore-al" class="field-error">{{ errori.dataA }}</p>
          </div>
        </template>
      </fieldset>
      <div>
        <button type="submit" class="button">Cerca</button>
      </div>
    </form>
  </section>

  <LegendaPrenotazioni />
  <section aria-labelledby="titolo-elenco" :aria-busy="elenco.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-elenco" tabindex="-1">Prenotazioni</h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento delle prenotazioni…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare le prenotazioni. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="esegui(ultimiFiltri)">Riprova</button></div>
    </div>
    <template v-else>
      <p class="muted" role="status">{{ elenco.voci.length }} prenotazioni trovate ({{ descrizioneFiltri }}).</p>
      <p v-if="elenco.voci.length === 0">
        {{ descrizioneFiltri === 'nessun filtro' ? 'Non hai ancora prenotazioni.' : 'Nessuna prenotazione per i filtri scelti.' }}
      </p>
      <ul v-else class="items" aria-label="Elenco delle prenotazioni">
        <li v-for="p in prenotazioniOrdinate" :key="p.idPrenotazione" :class="['card', 'prenotazione-riga', 'stato-prenotazione', { 'nuova-richiesta': p.idPrenotazione === scelta.state.ultimaCreata }]" :style="stileStatoAgenda(p.stato)" data-prenotazione>
          <div class="prenotazione-dati">
          <header class="prenotazione-intestazione">
          <p class="numero-prenotazione"><strong>Prenotazione n. {{ p.idPrenotazione }}</strong><span v-if="p.idPrenotazione === scelta.state.ultimaCreata"> · Nuova richiesta</span></p>
          <h3 class="item-title">{{ nomePrestazione(p) }}</h3>
          <p data-campo="stato"><span class="badge-prenotazione">{{ etichettaStato(p.stato) }}</span></p>
          </header>
          <dl class="facts prenotazione-informazioni">
            <div><dt>Data e orario della struttura</dt><dd data-campo="quando">{{ dataOrario(p.inizio, p.fine) }}</dd></div>
            <div v-for="m in mediciDaMostrare(p)" :key="m.etichetta"><dt>{{ m.etichetta }}</dt><dd data-campo="medico">{{ m.nome }}</dd></div>
            <div v-if="p.stanza"><dt>Stanza</dt><dd data-campo="stanza">{{ p.stanza.nome ?? 'Stanza assegnata' }}</dd></div>
            <div><dt>Prezzo</dt><dd>{{ formatEuro(p.prezzo) }}</dd></div>
          </dl>
          <p v-if="store.annullamentoBloccato(p.idPrenotazione)" class="small">
            <strong>Esito di un annullamento da verificare.</strong>
          </p>
          </div>
          <PagamentoPrenotazione :prenotazione="p" :ticket="elenco.ticket" :rimborsi="rimborsi" :ricarica-rimborsi="caricaRimborsi"
            @aggiornata="nuova => { elenco.voci = elenco.voci.map(x => x.idPrenotazione === nuova.idPrenotazione ? nuova : x) }" />
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.prenotazione-riga { container: card-prenotazione / inline-size; display: flex; flex-direction: column; gap: 1rem; }
.prenotazione-intestazione { min-width: 0; }
.prenotazione-intestazione > p:last-child { margin-block-end: 0; }
.prenotazione-informazioni { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); gap: .75rem 1.5rem; margin-block: 1rem 0; }
.prenotazione-dati { min-width: 0; }
.numero-prenotazione { margin: 0 0 0.5rem; color: var(--color-muted); }
.nuova-richiesta { outline: 2px solid var(--color-primary); outline-offset: .15rem; }
</style>
