<script setup>
import { useRoute, useRouter } from 'vue-router'
import { stileStatoAgenda } from '../medico/statiAgenda.js'
import { etichettaStato } from '../prenotazioni/formato.js'
import LegendaPrenotazioni from '../components/LegendaPrenotazioni.vue'
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { dataFiltroValida, fetchMedici, fetchRichiesteConsultazione } from '../api/desk.js'
import { fetchPrestazioni } from '../api/catalogo.js'
import EsitiDesk from '../components/EsitiDesk.vue'
import StatoElencoDesk from '../components/StatoElencoDesk.vue'
import { chiavePrenotazione } from '../desk/store.js'
import { TESTO_ERRORE_VERIFICA, TESTO_SUPERATA, dataOra, nome, prestazione, quando, stanza, testoVerifica } from '../desk/formato.js'
import { ESITI_COMUNI, useElencoDesk } from '../desk/useElencoDesk.js'
import { formatEuro } from '../utils/money.js'

/**
 * Consultazione richieste per stato; gestione soltanto per RICHIESTA. Conferma, rifiuto e annullamento con
 * l'operatore della sessione. Una richiesta che scompare dall'elenco dopo un esito incerto non
 * indica quale operazione sia avvenuta.
 */
const route = useRoute(), router = useRouter()
const STATI_FILTRO = { RICHIESTA: 'Da gestire', CONFERMATA: 'Confermate', RIFIUTATA: 'Rifiutate', ANNULLATA: 'Annullate', TUTTE: 'Tutte' }
const trattenute = ref([])
const filtri = reactive({ stato: 'RICHIESTA', medicoId: '', prestazioneId: '', periodo: 'tutte', data: '', dataDa: '', dataA: '' })
const errori = ref({})
const erroreFiltriBox = ref(null)
const opzioni = reactive({ medici: [], prestazioni: [] })
const applicati = ref({})
let percorsoApplicato = ''
function aggiornaRotta(query) {
  const destinazione = { path: '/desk/richieste', query }
  percorsoApplicato = router.resolve(destinazione).fullPath
  router.replace(destinazione)
}

const d = useElencoDesk({ azzera: () => { trattenute.value = [] }, ricarica: () => rileggi() })
const { store, session, elenco, azione, avviso, avvisoBox, titoloElenco, impostaTitolo, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi } = d

const completa = () => Object.keys(applicati.value).every(k => k === 'stato') && ['RICHIESTA', 'TUTTE'].includes(applicati.value.stato ?? 'RICHIESTA')
const rileggi = () => {
  const scelti = { ...applicati.value }
  const stato = scelti.stato ?? 'RICHIESTA'
  const ids = [...trattenute.value]
  if (ids.length) scelti.stato = 'TUTTE'
  return d.leggi({ lista: 'richieste', completa: completa(),
    leggi: async () => {
      const voci = await fetchRichiesteConsultazione(session.protectedRequest, scelti)
      return voci.filter(v => stato === 'TUTTE' || v.stato === stato || (v.stato === 'RIFIUTATA' && ids.includes(v.idPrenotazione)))
    },
    // La riconciliazione resta limitata all'elenco pendente completo, mai allo storico.
    applica: (voci, ticket) => store.applica('richieste', voci.filter(v => v.stato === 'RICHIESTA'), ticket, completa()),
  })
}
const fuoriFiltro = r => (applicati.value.stato ?? 'RICHIESTA') !== 'TUTTE' && r.stato !== (applicati.value.stato ?? 'RICHIESTA')

function validaFiltri() {
  const e = {}
  const scelti = {}
  if (!Object.hasOwn(STATI_FILTRO, filtri.stato)) e.stato = 'Scegli uno stato valido.'
  else if (filtri.stato !== 'RICHIESTA') scelti.stato = filtri.stato
  if (filtri.medicoId) scelti.medicoId = Number(filtri.medicoId)
  if (filtri.prestazioneId) scelti.prestazioneId = Number(filtri.prestazioneId)
  if (filtri.periodo === 'singola') {
    if (!dataFiltroValida(filtri.data)) e.data = 'Inserisci una data valida.'
    else scelti.data = filtri.data
  } else if (filtri.periodo === 'intervallo') {
    if (filtri.dataDa && !dataFiltroValida(filtri.dataDa)) e.dataDa = 'Inserisci una data di inizio valida.'
    if (filtri.dataA && !dataFiltroValida(filtri.dataA)) e.dataA = 'Inserisci una data di fine valida.'
    if (!filtri.dataDa && !filtri.dataA) e.dataDa = 'Indica almeno una data.'
    if (!e.dataDa && !e.dataA) {
      if (filtri.dataDa && filtri.dataA && filtri.dataDa > filtri.dataA) e.dataA = 'La data di fine non può precedere quella di inizio.'
      else {
        if (filtri.dataDa) scelti.dataDa = filtri.dataDa
        if (filtri.dataA) scelti.dataA = filtri.dataA
      }
    }
  }
  return { errori: e, scelti }
}

async function cerca() {
  const { errori: e, scelti } = validaFiltri()
  errori.value = e
  if (Object.keys(e).length) {
    await nextTick()
    erroreFiltriBox.value?.focus()
    return
  }
  applicati.value = scelti
  d.nuovoContesto()
  aggiornaRotta(Object.fromEntries(Object.entries(scelti).map(([k,v]) => [k,String(v)])))
  await rileggi()
}

const erroriFiltri = computed(() => Object.values(errori.value))
const descrizioneFiltri = computed(() => (applicati.value.stato ?? 'RICHIESTA') === 'RICHIESTA'
  ? (Object.keys(applicati.value).length ? 'richieste in attesa filtrate' : 'tutte le richieste in attesa')
  : `${STATI_FILTRO[applicati.value.stato]} — filtri applicati`)

// ---------------------------------------------------------------- azioni

const TITOLI = { conferma: 'Confermi la richiesta?', rifiuta: 'Rifiuti la richiesta?', annulla: 'Annulli la richiesta?' }
const PULSANTI = { conferma: 'Conferma richiesta', rifiuta: 'Rifiuta richiesta', annulla: 'Annulla richiesta' }
const NOTE = {
  conferma: "Il server verifica disponibilità configurata, appuntamento futuro, medico operativo e impegni compatibili.",
  rifiuta: 'La richiesta passerà a «Rifiutata».',
  annulla: 'La richiesta passerà a «Annullata».',
}
const ESEGUITE = { conferma: 'confermata', rifiuta: 'rifiutata', annulla: 'annullata' }

const voceCorrente = (id) => elenco.voci.find((v) => v.idPrenotazione === id) ?? null
const operabile = (r) => r.stato === 'RICHIESTA' && !store.bloccata(chiavePrenotazione(r.idPrenotazione))
/** Una conferma vale per la versione letta: se la richiesta cambia, va riaperta. */
const stessaVersione = (a, b) => a && b && a.stato === b.stato && a.indisponibilitaSegnalata === b.indisponibilitaSegnalata
  && a.medico.id === b.medico.id && a.inizio.date === b.inizio.date && a.inizio.hour === b.inizio.hour && a.inizio.minute === b.inizio.minute

function apriAzione(tipo, r) {
  if (!operabile(r)) return
  apri({ tipo, id: r.idPrenotazione, voce: r, fase: 'conferma' }, `${tipo}-${r.idPrenotazione}`)
}

async function conferma() {
  const a = azione.value
  const r = a && voceCorrente(a.id)
  if (!a || a.fase !== 'conferma' || !puoAgire.value || !r || !operabile(r)) return
  const ctx = d.contestoCorrente()
  if (!stessaVersione(a.voce, r)) {
    chiudi()
    await d.mostra(ctx, 'errore', 'I dati della richiesta sono cambiati: controlla la riga e riapri la conferma.')
    return
  }
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.decidiRichiesta(a.tipo, r)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'eseguita' && a.tipo === 'rifiuta') {
    trattenute.value = [...new Set([...trattenute.value, r.idPrenotazione])]
    aggiornaRotta({ ...route.query, evidenzia: trattenute.value.map(String) })
    elenco.voci = elenco.voci.map(v => v.idPrenotazione === r.idPrenotazione ? Object.freeze({ ...v, stato: esito.risultato.stato }) : v)
  }
  if (['eseguita', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  chiudi()
  switch (esito.kind) {
    case 'eseguita':
      await d.mostra(ctx, 'successo', `Richiesta n. ${r.idPrenotazione} ${ESEGUITE[a.tipo]}.`)
      break
    case 'conflitto':
    case 'non-trovata':
    case 'non-valida':
      await d.mostra(ctx, 'errore', `Operazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} L'elenco viene riletto.`)
      break
    case 'segnalata':
      await d.mostra(ctx, 'errore', "Indisponibilità segnalata: la conferma ordinaria non è possibile. Gestisci la richiesta da «Segnalazioni».")
      break
    case 'incerto':
      await d.mostra(ctx, 'errore', "Non è stato possibile stabilire l'esito. L'operazione non è stata ripetuta: verificala negli esiti da verificare prima di altre operazioni su questa richiesta.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await d.mostra(ctx, 'errore', ESITI_COMUNI[esito.kind] ?? ESITI_COMUNI.rifiutata)
  }
}

async function verifica(x) {
  const ctx = d.contestoCorrente()
  avviso.value = null
  const esito = await store.verifica(x.chiave)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await d.mostra(ctx, 'info', testoVerifica(x, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await d.mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await d.mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}
const descriviIncerta = (x) => `prenotazione n. ${x.idRisorsa} (${nome(x.riepilogo.paziente)}, ${quando(x.riepilogo)})`

async function caricaOpzioni() {
  try {
    const [medici, prestazioni] = await Promise.all([fetchMedici(session.protectedRequest), fetchPrestazioni()])
    if (d.smontato()) return
    opzioni.medici = medici
    opzioni.prestazioni = prestazioni
  } catch {
    // Filtri facoltativi: senza opzioni restano disponibili periodo e lettura completa.
  }
}

function caricaDaRotta() {
  percorsoApplicato = route.fullPath
  d.nuovoContesto()
  Object.assign(filtri, { stato: 'RICHIESTA', medicoId: '', prestazioneId: '', periodo: 'tutte', data: '', dataDa: '', dataA: '' })
  // Lo stato e i filtri della consultazione sono nella rotta, senza dati personali.
  const q = route.query
  const evidenziate = Array.isArray(q.evidenzia) ? q.evidenzia : [q.evidenzia]
  trattenute.value = [...new Set(evidenziate.filter(v => typeof v === 'string' && /^[1-9][0-9]*$/.test(v) && Number(v) <= 2147483647).map(Number))]
  for (const k of ['medicoId', 'prestazioneId', 'data', 'dataDa', 'dataA']) {
    if (typeof q[k] === 'string') filtri[k] = q[k]
  }
  if (typeof q.stato === 'string') filtri.stato = q.stato
  filtri.periodo = filtri.data ? 'singola' : filtri.dataDa || filtri.dataA ? 'intervallo' : 'tutte'
  const validati = validaFiltri()
  if (Object.keys(validati.errori).length) {
    errori.value = validati.errori
    elenco.stato = 'errore'
    elenco.errore = 'Controlla i filtri indicati nella rotta.'
  } else {
    applicati.value = validati.scelti
    rileggi()
  }
}
watch(() => route.fullPath, path => {
  if (route.path === '/desk/richieste' && path !== percorsoApplicato) caricaDaRotta()
})
onMounted(() => { caricaDaRotta(); caricaOpzioni() })
</script>

<template>
  <h1>Richieste di prenotazione</h1>
  <p class="muted">Consulta le richieste per stato; solo quelle ancora in attesa possono essere gestite qui. Orari espressi nell'orario della struttura (fuso orario da confermare).</p>

  <section class="card" aria-labelledby="titolo-filtri-richieste">
    <h2 id="titolo-filtri-richieste">Filtri</h2>
    <form class="form" novalidate @submit.prevent="cerca">
      <div class="field"><label for="richieste-stato">Stato delle richieste</label>
        <select id="richieste-stato" v-model="filtri.stato"><option v-for="(label, stato) in STATI_FILTRO" :key="stato" :value="stato">{{ label }}</option></select>
      </div>
      <div v-if="erroriFiltri.length" ref="erroreFiltriBox" class="notice error" role="alert" tabindex="-1"><p>Controlla i filtri indicati.</p></div>
      <div class="field">
        <label for="filtro-medico">Medico titolare</label>
        <select id="filtro-medico" v-model="filtri.medicoId">
          <option value="">Tutti i medici</option>
          <option v-for="m in opzioni.medici" :key="m.idUtente" :value="String(m.idUtente)">{{ nome(m) }}</option>
        </select>
      </div>
      <div class="field">
        <label for="filtro-prestazione">Prestazione</label>
        <select id="filtro-prestazione" v-model="filtri.prestazioneId">
          <option value="">Tutte le prestazioni</option>
          <option v-for="p in opzioni.prestazioni" :key="p.idPrestazione" :value="String(p.idPrestazione)">{{ p.nome }}</option>
        </select>
      </div>
      <fieldset class="fieldset-form">
        <legend>Data dell'appuntamento</legend>
        <div class="choices">
          <label><input v-model="filtri.periodo" type="radio" name="periodo-richieste" value="tutte" /> Tutte le date</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo-richieste" value="singola" /> Una data</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo-richieste" value="intervallo" /> Un intervallo</label>
        </div>
        <div v-if="filtri.periodo === 'singola'" class="field">
          <label for="richieste-data">Data</label>
          <input id="richieste-data" v-model="filtri.data" type="date" :aria-invalid="errori.data ? 'true' : undefined" :aria-describedby="errori.data ? 'errore-richieste-data' : undefined" />
          <p v-if="errori.data" id="errore-richieste-data" class="field-error">{{ errori.data }}</p>
        </div>
        <template v-else-if="filtri.periodo === 'intervallo'">
          <div class="field">
            <label for="richieste-dal">Dal</label>
            <input id="richieste-dal" v-model="filtri.dataDa" type="date" :aria-invalid="errori.dataDa ? 'true' : undefined" :aria-describedby="errori.dataDa ? 'errore-richieste-dal' : undefined" />
            <p v-if="errori.dataDa" id="errore-richieste-dal" class="field-error">{{ errori.dataDa }}</p>
          </div>
          <div class="field">
            <label for="richieste-al">Al (incluso)</label>
            <input id="richieste-al" v-model="filtri.dataA" type="date" :aria-invalid="errori.dataA ? 'true' : undefined" :aria-describedby="errori.dataA ? 'errore-richieste-al' : undefined" />
            <p v-if="errori.dataA" id="errore-richieste-al" class="field-error">{{ errori.dataA }}</p>
          </div>
        </template>
      </fieldset>
      <div><button type="submit" class="button">Cerca</button></div>
    </form>
  </section>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-desk>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-desk>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <EsitiDesk :tipi="['conferma', 'rifiuta', 'annulla']" :descrizione="descriviIncerta" @verifica="verifica" />

  <section aria-labelledby="titolo-richieste" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-richieste" ref="titoloElenco" tabindex="-1">Richieste</h2>
    <StatoElencoDesk :elenco="elenco" :in-aggiornamento="inAggiornamento" :invalidata="invalidata" oggetto="le richieste" @rileggi="d.recupera(rileggi)" />
    <template v-if="elenco.stato === 'pronto' || (elenco.stato === 'errore' && elenco.voci.length)">
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 richiesta' : `${elenco.voci.length} richieste` }} ({{ descrizioneFiltri }}).</p>
      <p v-if="session.state.passwordUncertain" class="small">Le operazioni sono sospese finché non verifichi l'esito del cambio password.</p>
      <p v-if="elenco.voci.length === 0">{{ (applicati.stato ?? 'RICHIESTA') === 'RICHIESTA' ? 'Nessuna richiesta in attesa.' : 'Nessuna richiesta per i filtri applicati.' }}</p>
      <LegendaPrenotazioni />
      <ul v-if="elenco.voci.length" class="items" aria-label="Richieste in attesa">
        <li v-for="r in elenco.voci" :key="r.idPrenotazione" class="card stato-prenotazione" :style="stileStatoAgenda(r.stato)" data-richiesta :data-id="r.idPrenotazione">
          <h3 class="item-title">{{ nome(r.paziente) }} <span class="numero-richiesta">— Prenotazione n. {{ r.idPrenotazione }}</span></h3>
          <p><span class="badge-prenotazione" data-stato-richiesta>{{ etichettaStato(r.stato) }}</span></p>
          <p v-if="fuoriFiltro(r)" class="small" data-fuori-filtro>Non corrisponde più al filtro applicato: è mantenuta come richiesta evidenziata. Alla prossima ricerca varranno i filtri selezionati.</p>
          <dl class="facts">
            <div><dt>Data e orario</dt><dd class="orario-richiesta" data-campo="quando">{{ quando(r) }}</dd></div>
            <div><dt>Medico titolare</dt><dd data-campo="medico">{{ nome(r.medico) }}</dd></div>
            <div><dt>Prestazione</dt><dd data-campo="prestazione">{{ prestazione(r) }}</dd></div>
            <div><dt>Stanza</dt><dd data-campo="stanza">{{ stanza(r) }}</dd></div>
            <div><dt>Prezzo</dt><dd data-campo="prezzo">{{ formatEuro(r.prezzo) }}</dd></div>
            <div><dt>Richiesta del</dt><dd>{{ dataOra(r.richiesta) }}</dd></div>
            <div><dt>Prenotazione</dt><dd>n. {{ r.idPrenotazione }}</dd></div>
          </dl>
          <p v-if="r.indisponibilitaSegnalata" class="small" data-campo="indisponibilita">
            <strong>Indisponibilità segnalata dal medico:</strong> la conferma ordinaria non è possibile; gestiscila da
            <RouterLink to="/desk/segnalazioni">Segnalazioni</RouterLink>.
          </p>
          <p v-if="r.stato !== 'RICHIESTA'" class="small">Sola consultazione: richiesta già gestita.</p>
          <p v-else-if="!operabile(r)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <div v-else-if="azione?.id === r.idPrenotazione" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${r.idPrenotazione}`"
               :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
            <h4 :id="`titolo-conferma-${r.idPrenotazione}`" :ref="impostaTitolo" tabindex="-1">{{ TITOLI[azione.tipo] }}</h4>
            <p>{{ nome(r.paziente) }} — {{ prestazione(r) }}, {{ quando(r) }}, medico {{ nome(r.medico) }}. Prenotazione n. {{ r.idPrenotazione }}.</p>
            <p class="small">{{ NOTE[azione.tipo] }}</p>
            <div class="actions">
              <button type="button" class="button" :class="{ 'azione-conferma': azione.tipo === 'conferma', 'azione-rifiuta': azione.tipo === 'rifiuta' }" :disabled="azione.fase === 'invio' || !puoAgire" @click="conferma">
                {{ azione.fase === 'invio' ? 'Operazione in corso…' : PULSANTI[azione.tipo] }}
              </button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button v-if="!r.indisponibilitaSegnalata" type="button" class="button azione-conferma" :data-origine="`conferma-${r.idPrenotazione}`"
                    :disabled="!puoAgire || !libero" @click="apriAzione('conferma', r)">Conferma<span class="visually-hidden"> la richiesta di {{ nome(r.paziente) }}</span></button>
            <button type="button" class="button azione-rifiuta" :data-origine="`rifiuta-${r.idPrenotazione}`" :disabled="!puoAgire || !libero"
                    @click="apriAzione('rifiuta', r)">Rifiuta<span class="visually-hidden"> la richiesta di {{ nome(r.paziente) }}</span></button>
            <button type="button" class="button secondary" :data-origine="`annulla-${r.idPrenotazione}`" :disabled="!puoAgire || !libero"
                    @click="apriAzione('annulla', r)">Annulla<span class="visually-hidden"> la richiesta di {{ nome(r.paziente) }}</span></button>
          </div>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.numero-richiesta { font-weight: 600; overflow-wrap: anywhere; }
.orario-richiesta { font-size: 1.0625rem; font-weight: 700; }
[data-richiesta] .actions .button { min-height: 2.75rem; min-width: min(100%, 9rem); white-space: normal; text-align: center; }
[data-richiesta] .actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 9rem), 1fr)); gap: .625rem; }
</style>
