<script setup>
import { stileStatoAgenda } from '../medico/statiAgenda.js'
import CalendarioAgenda from '../components/CalendarioAgenda.vue'
import { todayLocal } from '../utils/datetime.js'
import { periodoMese } from '../medico/calendario.js'
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { MAX_GIORNI_AGENDA, dataAgendaValida, fetchAgenda, giorniTra } from '../api/medico.js'
import RefertoCaricamento from '../components/RefertoCaricamento.vue'
import { refertabile, registrabile, segnalabile, useMedico } from '../medico/store.js'
import { dataOrario, etichettaStato, nomePrestazione } from '../prenotazioni/formato.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'

/**
 * Agenda del medico : lettura per giornata o intervallo, registrazione della visita e
 * segnalazione di indisponibilità; il caricamento del referto è nel componente di riga.
 *
 * Contesto locale: un oggetto nuovo a ogni nuova ricerca e a ogni cambio di epoca. Ogni
 * operazione lo cattura all'avvio e, dopo ogni await, applica messaggi, focus e selezioni
 * solo se è ancora quello corrente. Le riletture dopo un esito restano nello stesso contesto.
 * Lo store conserva comunque l'esito del tentativo originario.
 */
const store = useMedico()

const filtri = reactive({ periodo: 'oggi', data: '', dataDa: '', dataA: '' })
const errori = ref({})
const erroreFiltriBox = ref(null)
/** aggiornata: i dati mostrati sono confermati dall'ultima lettura (non in rilettura). */
const elenco = reactive({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, applicati: null })
const selezionati = ref([])
// Nessuna data civile della struttura è esposta: il browser sceglie solo il mese iniziale.
const meseAgenda = ref(todayLocal().slice(0, 7))
const giornoAgenda = ref('')
const multipla = ref(false)
const vociVisibili = computed(() => giornoAgenda.value ? elenco.voci.filter(a => a.inizio.date === giornoAgenda.value) : meseAgenda.value ? [] : elenco.voci)
async function scegliMese(mese) {
  const periodo = periodoMese(mese)
  if (!periodo) return
  meseAgenda.value = mese
  giornoAgenda.value = ''
  await leggi({ dataDa: periodo.dataDa, dataA: periodo.dataA }, { nuovo: true })
}
function scegliGiorno(data) {
  if (!data.startsWith(`${meseAgenda.value}-`) || !elenco.aggiornata || fase.value === 'invio') return
  contesto = {}
  chiave.value += 1
  azione.value = null
  fase.value = 'nessuna'
  avviso.value = null
  origine = null
  selezionati.value = []
  giornoAgenda.value = data
}
async function segnalaSingola(a) {
  if (!puoAgire.value || fase.value !== 'nessuna' || !selezionabile(a)) return
  selezionati.value = [a.idPrenotazione]
  await apri({ tipo: 'segnalazione', singola: a.idPrenotazione })
}
/** Conferma aperta: { tipo: 'visita', id } oppure { tipo: 'segnalazione' }; fase 'conferma' | 'invio'. */
const azione = ref(null)
const fase = ref('nessuna')
const avviso = ref(null)
const avvisoBox = ref(null)
const titoloElenco = ref(null)
const titoloConferma = ref(null)
// Una sola conferma alla volta: il titolo è registrato con un ref funzione, anche dentro v-for.
const impostaTitolo = (el) => { titoloConferma.value = el }
/** Chiave dei componenti di riga: cambia con il contesto, così vengono rimontati. */
const chiave = ref(0)
let contesto = {}
let origine = null
let ultimiFiltri = {}

function nuovoContesto() {
  giornoAgenda.value = ''
  contesto = {}
  elenco.voci = []
  elenco.stato = 'caricamento'
  elenco.errore = ''
  elenco.aggiornata = false
  elenco.applicati = null
  selezionati.value = []
  azione.value = null
  fase.value = 'nessuna'
  avviso.value = null
  origine = null
  chiave.value += 1
}

const { session, idUtente: idMedico, nuovaLettura, attuale, smontato } = useLetturaProfilo('medico', {
  azzera: nuovoContesto,
  ricarica: () => leggi(ultimiFiltri),
})

const corrente = (ctx) => ctx === contesto && !smontato()
const medico = computed(() => idMedico())

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

async function mostra(ctx, tipo, testo, extra = {}) {
  if (!corrente(ctx)) return
  avviso.value = { tipo, testo, ...extra }
  await nextTick()
  if (tipo !== 'successo' && corrente(ctx)) avvisoBox.value?.focus()
}

// ---------------------------------------------------------------- lettura

/**
 * Lettura dell'agenda. `nuovo` apre un nuovo contesto (nuova ricerca); altrimenti è una
 * rilettura nello stesso contesto: i dati restano visibili ma le azioni sono sospese.
 */
async function leggi(scelti, { nuovo = false } = {}) {
  if (nuovo) nuovoContesto()
  ultimiFiltri = scelti
  const id = idMedico()
  if (id === null) return
  const ctx = contesto
  const mia = nuovaLettura()
  elenco.aggiornata = false
  elenco.errore = ''
  if (elenco.stato !== 'pronto') elenco.stato = 'caricamento'
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchAgenda(session.protectedRequest, { medicoId: id, ...scelti })
    if (!attuale(mia) || !corrente(ctx)) return
    // Periodo coperto dalla lettura: senza date è la giornata corrente del server (dedotta dalle voci).
    const periodo = scelti.data ? { da: scelti.data, a: scelti.data } : scelti.dataDa ? { da: scelti.dataDa, a: scelti.dataA } : null
    store.applicaAgenda(voci, ticket, periodo)
    elenco.voci = voci
    elenco.applicati = scelti
    elenco.stato = 'pronto'
    elenco.aggiornata = true
    // La selezione resta solo per appuntamenti ancora presenti e segnalabili.
    selezionati.value = selezionati.value.filter((x) => voci.some((v) => v.idPrenotazione === x && selezionabile(v)))
  } catch (error) {
    if (!attuale(mia) || !corrente(ctx) || error instanceof StaleResponseError) return
    elenco.errore = messaggio(error)
    elenco.stato = 'errore'
  }
}

const rileggi = () => leggi(ultimiFiltri)

/** Filtri: giornata corrente del server (nessuna data), data singola oppure intervallo ≤ 31 giorni. */
function validaFiltri() {
  const e = {}
  const scelti = {}
  if (filtri.periodo === 'singola') {
    if (!dataAgendaValida(filtri.data)) e.data = 'Inserisci una data valida.'
    else scelti.data = filtri.data
  } else if (filtri.periodo === 'intervallo') {
    if (!dataAgendaValida(filtri.dataDa)) e.dataDa = 'Inserisci una data di inizio valida.'
    if (!dataAgendaValida(filtri.dataA)) e.dataA = 'Inserisci una data di fine valida.'
    if (!e.dataDa && !e.dataA) {
      if (filtri.dataDa > filtri.dataA) e.dataA = 'La data di fine non può precedere quella di inizio.'
      else if (giorniTra(filtri.dataDa, filtri.dataA) >= MAX_GIORNI_AGENDA) e.dataA = `L'intervallo può comprendere al massimo ${MAX_GIORNI_AGENDA} giorni.`
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
  meseAgenda.value = ''
  giornoAgenda.value = ''
  await leggi(scelti, { nuovo: true })
}

const it = (d) => d.split('-').reverse().join('/')
const descrizionePeriodo = computed(() => {
  const a = elenco.applicati
  if (!a) return ''
  if (a.data) return `giornata del ${it(a.data)}`
  if (a.dataDa) return `dal ${it(a.dataDa)} al ${it(a.dataA)}`
  return 'giornata corrente secondo il server'
})
const erroriFiltri = computed(() => Object.values(errori.value))

// ---------------------------------------------------------------- idoneità

/** Azioni autorizzabili solo con l'agenda confermata dall'ultima lettura. */
const puoAgire = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && medico.value !== null
  && !session.state.passwordUncertain && !session.state.busy)
const inAggiornamento = computed(() => elenco.stato === 'pronto' && !elenco.aggiornata)

const segnalata = (a) => a.indisponibilitaSegnalata || store.segnalazioneNota(a.idPrenotazione)
function selezionabile(a) {
  const m = idMedico()
  return m !== null && segnalabile(a, m) && !store.segnalazioneNota(a.idPrenotazione) && !store.segnalazioneBloccata(a.idPrenotazione)
    && !store.visitaImpedisceSegnalazione(a.idPrenotazione)
}
function proponiVisita(a) {
  const m = idMedico()
  return m !== null && registrabile(a, m) && !store.visitaBloccata(a.idPrenotazione) && !store.segnalazioneNota(a.idPrenotazione)
    && !store.state.visiteInCorso.includes(a.idPrenotazione) && !store.segnalazioneBloccata(a.idPrenotazione)
}
const proponiReferto = (a) => medico.value !== null && refertabile(a, medico.value)
const voceCorrente = (id) => elenco.voci.find((v) => v.idPrenotazione === id) ?? null
const selezione = computed(() => elenco.voci.filter((v) => selezionati.value.includes(v.idPrenotazione)))
const nome = (p) => `${p.nome} ${p.cognome}`
/** Etichette dal punto di vista del medico: una RICHIESTA attende la conferma della segreteria. */
const statoMedico = (stato) => (stato === 'RICHIESTA' ? 'Richiesta, in attesa di conferma della segreteria' : etichettaStato(stato))
const stanza = (a) => (a.stanza ? (a.stanza.nome ?? 'Stanza assegnata') : 'Non assegnata')

function cambiaSelezione(a, event) {
  if (!selezionabile(a)) return
  const id = a.idPrenotazione
  const resto = selezionati.value.filter((x) => x !== id)
  selezionati.value = event.target.checked ? [...resto, id] : resto
}

// ---------------------------------------------------------------- conferme

/** Controllo d'origine di una conferma: sostituito dalla conferma stessa, va ritrovato per chiave. */
const chiaveOrigine = (a) => (a.tipo === 'visita' ? `visita-${a.id}` : a.singola ? `segnalazione-${a.singola}` : 'segnalazione')

async function apri(nuova) {
  if (!puoAgire.value || fase.value === 'invio') return
  origine = chiaveOrigine(nuova)
  avviso.value = null
  azione.value = nuova
  fase.value = 'conferma'
  const ctx = contesto
  await nextTick()
  if (corrente(ctx)) {
    titoloConferma.value?.focus()
    if (nuova.singola) titoloConferma.value?.scrollIntoView?.({ block: 'center' })
  }
}

/** Chiude la conferma senza scritture e riporta il focus al controllo d'origine, o al titolo. */
async function annulla() {
  azione.value = null
  fase.value = 'nessuna'
  const ctx = contesto
  const o = origine
  origine = null
  await nextTick()
  if (!corrente(ctx)) return
  const b = o ? titoloElenco.value?.parentElement?.querySelector(`[data-origine="${o}"]`) : null
  ;(b && !b.disabled ? b : titoloElenco.value)?.focus()
}

// Una conferma aperta sui dati precedenti non resta utilizzabile durante una rilettura.
watch(puoAgire, (valido) => {
  if (valido || fase.value !== 'conferma') return
  azione.value = null
  fase.value = 'nessuna'
  origine = null
  nextTick(() => { if (!smontato() && document.activeElement === document.body) titoloElenco.value?.focus() })
}, { flush: 'sync' })

const confermaVisita = computed(() => (azione.value?.tipo === 'visita' ? voceCorrente(azione.value.id) : null))

const ESITI_VISITA = {
  conflitto: "Visita non registrata: lo stato della prenotazione, il termine dello slot, una segnalazione di indisponibilità o un pagamento in attesa non lo consentono. L'agenda è stata riletta.",
  'non-trovata': "Visita non registrata: la prenotazione non risulta assegnata a te come medico effettivo. L'agenda è stata riletta.",
  'non-valida': 'Richiesta di registrazione non valida.',
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  occupato: 'Una registrazione per questa prenotazione è già in corso.',
  bloccata: "L'esito di un tentativo precedente è ancora da verificare.",
  'non-registrabile': 'La prenotazione non è più registrabile secondo i dati letti.',
}

async function confermaRegistrazione() {
  const a = confermaVisita.value
  if (!a || fase.value !== 'conferma' || !puoAgire.value || !proponiVisita(a)) return
  const ctx = contesto
  fase.value = 'invio'
  const esito = await store.registraVisita(a)
  if (!corrente(ctx)) return
  // Dopo un esito certo l'idoneità decade subito: la rilettura parte nello stesso passo.
  if (['registrata', 'conflitto', 'non-trovata'].includes(esito.kind)) rileggi()
  azione.value = null
  fase.value = 'nessuna'
  origine = null
  switch (esito.kind) {
    case 'registrata':
      await mostra(ctx, 'successo', `Visita registrata: la prenotazione n. ${a.idPrenotazione} risulta «${etichettaStato('EFFETTUATA')}».`)
      break
    case 'incerto':
      await mostra(ctx, 'errore', `Non è stato possibile stabilire se la visita della prenotazione n. ${a.idPrenotazione} sia stata registrata. Non è stata ripetuta: usa «Verifica nell'agenda» negli esiti da verificare prima di qualsiasi nuovo tentativo.`)
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra(ctx, 'errore', ESITI_VISITA[esito.kind] ?? ESITI_VISITA.rifiutata)
  }
}

const ESITI_SEGNALAZIONE = {
  conflitto: "Nessuna segnalazione registrata: almeno un appuntamento non è più segnalabile (stato cambiato o appuntamento già iniziato). L'operazione è atomica: nessun appuntamento del gruppo è stato segnalato. L'agenda è stata riletta.",
  'non-trovata': "Nessuna segnalazione registrata: almeno un appuntamento non risulta di tua competenza. L'operazione è atomica: nessun appuntamento del gruppo è stato segnalato. L'agenda è stata riletta.",
  'non-valida': 'Selezione non valida: scegli almeno un appuntamento, senza ripetizioni.',
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  occupato: 'Una segnalazione è già in corso.',
  bloccata: "Per almeno un appuntamento l'esito di una segnalazione precedente è ancora da verificare.",
  'non-segnalabile': 'Almeno un appuntamento selezionato non è più segnalabile secondo i dati letti.',
}

async function confermaSegnalazione() {
  const scelti = selezione.value
  if (azione.value?.tipo !== 'segnalazione' || fase.value !== 'conferma' || !puoAgire.value
      || scelti.length === 0 || scelti.length !== selezionati.value.length || !scelti.every(selezionabile)) return
  const ctx = contesto
  fase.value = 'invio'
  const esito = await store.segnala(scelti)
  if (!corrente(ctx)) return
  if (['segnalate', 'conflitto', 'non-trovata'].includes(esito.kind)) rileggi()
  azione.value = null
  fase.value = 'nessuna'
  origine = null
  if (esito.kind === 'segnalate' || esito.kind === 'incerto') selezionati.value = []
  switch (esito.kind) {
    case 'segnalate':
      await mostra(ctx, 'successo', `Indisponibilità segnalata per ${scelti.length === 1 ? '1 appuntamento' : `${scelti.length} appuntamenti`}. La segreteria gestirà ciascun appuntamento; gli orari restano occupati.`)
      break
    case 'incerto':
      await mostra(ctx, 'errore', "Non è stato possibile stabilire se la segnalazione sia stata registrata. Non è stata ripetuta: gli appuntamenti interessati restano bloccati fino alla verifica nell'agenda, negli esiti da verificare.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra(ctx, 'errore', ESITI_SEGNALAZIONE[esito.kind] ?? ESITI_SEGNALAZIONE.rifiutata)
  }
}

// ---------------------------------------------------------------- verifiche manuali

const ESITO_STATO = {
  segnalata: 'risulta segnalata',
  'non-segnalata': 'al momento non risulta segnalata (la segnalazione potrebbe non essere stata registrata oppure essere già stata gestita dalla segreteria): puoi selezionarla di nuovo solo con una scelta esplicita',
  superata: 'il risultato della verifica è stato superato da dati più recenti e non è stato usato: resta bloccata, ripeti la verifica',
  'altro-stato': 'non è più segnalabile',
  assente: "non compare più nell'agenda: l'esito non può essere stabilito e resta bloccata",
}

async function verificaVisita(x) {
  const ctx = contesto
  avviso.value = null
  const esito = await store.verificaVisita(x.idPrenotazione)
  if (!corrente(ctx)) return
  const n = x.idPrenotazione
  if (esito.kind === 'trovata') {
    rileggi()
    const stato = esito.appuntamento.stato
    const testo = stato === 'EFFETTUATA'
      ? `Nell'agenda la prenotazione n. ${n} risulta «${etichettaStato(stato)}». Lo stato non dimostra che la registrazione sia avvenuta con il tuo tentativo.`
      : stato === 'CONFERMATA'
        ? `Nell'agenda la prenotazione n. ${n} risulta ancora «${etichettaStato(stato)}»: la visita non risulta registrata. Puoi registrarla solo con una nuova scelta esplicita e una nuova conferma.`
        : `Nell'agenda la prenotazione n. ${n} risulta «${etichettaStato(stato)}»: la registrazione non è proponibile.`
    await mostra(ctx, 'info', testo)
  } else if (esito.kind === 'assente') {
    await mostra(ctx, 'info', `La prenotazione n. ${n} non compare nell'agenda della sua giornata: l'esito non può essere stabilito e un nuovo invio resta bloccato.`)
  } else if (esito.kind === 'superata') {
    await mostra(ctx, 'info', `Il risultato della verifica per la prenotazione n. ${n} è stato superato da dati più recenti e non è stato usato. Un nuovo invio resta bloccato: ripeti la verifica.`)
  } else if (esito.kind === 'errore') {
    await mostra(ctx, 'errore', "Verifica non riuscita: l'agenda non è leggibile. Riprova più tardi.")
  }
}

async function verificaSegnalazione(x) {
  const ctx = contesto
  avviso.value = null
  const esito = await store.verificaSegnalazione(x.chiave)
  if (!corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await mostra(ctx, 'info', esito.pendenti.length === 0
      ? 'Verifica completata: lo stato attuale di ciascun appuntamento è indicato negli esiti da verificare. Non è possibile stabilire con certezza se dipenda dal tuo tentativo.'
      : "Verifica completata: alcuni appuntamenti restano bloccati (non compaiono più nell'agenda o il risultato è stato superato da dati più recenti). Lo stato degli altri è indicato negli esiti da verificare.")
  } else if (esito.kind === 'errore') {
    await mostra(ctx, 'errore', "Verifica non riuscita: l'agenda non è leggibile. Riprova più tardi.")
  }
}

const daVerificare = computed(() => store.state.visiteIncerte.length + store.state.segnalazioniIncerte.length + store.state.refertiIncerti.length > 0)
const descrizione = (r) => `${nome(r.paziente)}, ${nomePrestazione(r)}, ${dataOrario(r.inizio, r.fine)}`

onMounted(() => scegliMese(meseAgenda.value))
</script>

<template>
  <h1>Agenda</h1>
  <p class="muted">Orari espressi nell'orario della struttura (fuso orario da confermare). I dati dei pazienti restano solo in questa pagina.</p>

  <CalendarioAgenda :mese="meseAgenda" :selezionato="giornoAgenda" :voci="elenco.voci" :aggiornato="elenco.aggiornata"
                    @mese="scegliMese" @giorno="scegliGiorno" />
  <details class="filtri-avanzati"><summary>Ricerca per giornata o intervallo</summary>
  <section class="card" aria-labelledby="titolo-filtri-agenda">
    <h2 id="titolo-filtri-agenda">Periodo</h2>
    <form class="form" novalidate @submit.prevent="cerca">
      <div v-if="erroriFiltri.length" ref="erroreFiltriBox" class="notice error" role="alert" tabindex="-1">
        <p>Controlla il periodo indicato.</p>
      </div>
      <fieldset class="fieldset-form">
        <legend>Appuntamenti da mostrare</legend>
        <div class="choices">
          <label><input v-model="filtri.periodo" type="radio" name="periodo-agenda" value="oggi" /> Giornata corrente</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo-agenda" value="singola" /> Una data</label>
          <label><input v-model="filtri.periodo" type="radio" name="periodo-agenda" value="intervallo" /> Un intervallo (massimo {{ MAX_GIORNI_AGENDA }} giorni)</label>
        </div>
        <p v-if="filtri.periodo === 'oggi'" class="small">La giornata corrente è quella della struttura, stabilita dal server.</p>
        <div v-else-if="filtri.periodo === 'singola'" class="field">
          <label for="agenda-data">Data</label>
          <input id="agenda-data" v-model="filtri.data" type="date" min="1000-01-01" max="9999-12-30"
                 :aria-invalid="errori.data ? 'true' : undefined" :aria-describedby="errori.data ? 'errore-agenda-data' : undefined" />
          <p v-if="errori.data" id="errore-agenda-data" class="field-error">{{ errori.data }}</p>
        </div>
        <template v-else>
          <div class="field">
            <label for="agenda-dal">Dal</label>
            <input id="agenda-dal" v-model="filtri.dataDa" type="date" min="1000-01-01" max="9999-12-30"
                   :aria-invalid="errori.dataDa ? 'true' : undefined" :aria-describedby="errori.dataDa ? 'errore-agenda-dal' : undefined" />
            <p v-if="errori.dataDa" id="errore-agenda-dal" class="field-error">{{ errori.dataDa }}</p>
          </div>
          <div class="field">
            <label for="agenda-al">Al</label>
            <input id="agenda-al" v-model="filtri.dataA" type="date" min="1000-01-01" max="9999-12-30"
                   :aria-invalid="errori.dataA ? 'true' : undefined" :aria-describedby="errori.dataA ? 'errore-agenda-al' : undefined" />
            <p v-if="errori.dataA" id="errore-agenda-al" class="field-error">{{ errori.dataA }}</p>
          </div>
        </template>
      </fieldset>
      <div><button type="submit" class="button">Mostra</button></div>
    </form>
  </section>

  </details>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-agenda>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-agenda>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <section v-if="daVerificare" class="card" aria-labelledby="titolo-da-verificare" data-da-verificare>
    <h2 id="titolo-da-verificare">Esiti da verificare</h2>
    <div v-for="x in store.state.visiteIncerte" :key="`v-${x.idPrenotazione}`" class="notice warning" data-visita-incerta>
      <p><strong>Registrazione della visita non determinata</strong> per la prenotazione n. {{ x.idPrenotazione }} ({{ descrizione(x.riepilogo) }}).</p>
      <p>Il tentativo non è stato ripetuto e un nuovo invio è bloccato fino alla verifica.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'assente'">Una verifica precedente non ha trovato la prenotazione nell'agenda: l'esito non è stato dedotto.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verificaVisita(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : "Verifica nell'agenda" }}
        </button>
      </div>
    </div>
    <div v-for="x in store.state.segnalazioniIncerte" :key="`s-${x.chiave}`" class="notice warning" data-segnalazione-incerta>
      <p><strong>Segnalazione di indisponibilità non determinata</strong> per {{ x.riepiloghi.length === 1 ? '1 appuntamento' : `${x.riepiloghi.length} appuntamenti` }}.</p>
      <ul>
        <li v-for="r in x.riepiloghi" :key="r.idPrenotazione">
          Prenotazione n. {{ r.idPrenotazione }} — {{ descrizione(r) }}<template v-if="x.esiti?.[r.idPrenotazione]">: {{ ESITO_STATO[x.esiti[r.idPrenotazione].kind] }}</template>
        </li>
      </ul>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-if="x.pendenti.length" class="small">Gli appuntamenti senza esito verificato restano bloccati.</p>
      <div class="actions">
        <button v-if="x.pendenti.length" type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verificaSegnalazione(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : "Verifica nell'agenda" }}
        </button>
        <button v-else type="button" class="button secondary" @click="store.chiudiSegnalazioneIncerta(x.chiave, store.state.owner)">Rimuovi dall'elenco</button>
      </div>
    </div>
    <div v-for="x in store.state.refertiIncerti" :key="`r-${x.idPrenotazione}`" class="notice warning" data-referto-incerto>
      <p><strong>Caricamento del referto non determinato</strong> per la prenotazione n. {{ x.idPrenotazione }} ({{ descrizione(x.riepilogo) }}).</p>
      <p>L'agenda può indicare la presenza di un documento, ma non permette di attribuirlo con certezza a questo tentativo. Il caricamento non è dichiarato fallito, non è stato ripetuto e un nuovo caricamento resta bloccato in questa sessione.</p>
    </div>
  </section>

  <section aria-labelledby="titolo-agenda" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-agenda" ref="titoloElenco" tabindex="-1">Appuntamenti<template v-if="giornoAgenda"> del {{ it(giornoAgenda) }}</template></h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento dell'agenda…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare l'agenda. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="rileggi">Riprova</button></div>
    </div>
    <template v-else>
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 appuntamento' : `${elenco.voci.length} appuntamenti` }} ({{ descrizionePeriodo }}).</p>
      <p v-if="inAggiornamento" class="small" role="status" data-aggiornamento>Aggiornamento dell'agenda in corso: le azioni sono sospese.</p>
      <p v-if="session.state.passwordUncertain" class="small">
        Le operazioni sono sospese finché non verifichi l'esito del cambio password.
        <RouterLink to="/area/password">Verifica il cambio password</RouterLink>
      </p>
      <p v-if="giornoAgenda" class="actions">
        <RouterLink v-if="fase === 'nessuna'" class="button secondary" :to="{ name: 'disponibilita-medico', query: { data: giornoAgenda } }">Aggiungi disponibilità</RouterLink>
        <span v-else class="button secondary" aria-disabled="true">Aggiungi disponibilità</span>
      </p>
      <p v-if="elenco.voci.length === 0">Nessun appuntamento nel periodo scelto.</p>
      <template v-else>
        <p v-if="meseAgenda && !giornoAgenda" class="small">Seleziona un giorno per mostrare gli appuntamenti.</p>
        <p v-if="giornoAgenda && !vociVisibili.length">Nessun appuntamento nella giornata selezionata.</p>
        <label v-if="!meseAgenda || giornoAgenda" class="choice-inline"><input v-model="multipla" type="checkbox" :disabled="fase !== 'nessuna'" /> Modalità selezione multipla per indisponibilità</label>
        <div v-if="multipla && !azione?.singola" class="card" data-pannello-segnalazione>
          <h3 class="small">Segnalazione di indisponibilità</h3>
          <p class="small">
            Riguarda soltanto gli appuntamenti selezionati: non crea un periodo di assenza e non blocca gli orari liberi.
            Sono segnalabili le richieste di cui sei titolare e gli appuntamenti confermati di cui sei il medico effettivo.
          </p>
          <div v-if="azione?.tipo === 'segnalazione'" class="confirm" role="group" aria-labelledby="titolo-conferma-segnalazione"
               :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-segnalazione>
            <h4 id="titolo-conferma-segnalazione" :ref="impostaTitolo" tabindex="-1">Confermi la segnalazione di indisponibilità?</h4>
            <ul>
              <li v-for="a in selezione" :key="a.idPrenotazione">Prenotazione n. {{ a.idPrenotazione }} — {{ nome(a.paziente) }}, {{ nomePrestazione(a) }}, {{ dataOrario(a.inizio, a.fine) }}</li>
            </ul>
            <p class="small">La segnalazione è unica per tutto il gruppo: se un appuntamento non è segnalabile, nessuno viene segnalato. La segreteria gestirà ciascun appuntamento.</p>
            <div class="actions">
              <button type="button" class="button" :disabled="fase === 'invio' || !puoAgire" @click="confermaSegnalazione">
                {{ fase === 'invio' ? 'Segnalazione in corso…' : 'Conferma segnalazione' }}
              </button>
              <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="annulla">Annulla</button>
            </div>
          </div>
          <div v-else class="actions">
            <button type="button" class="button secondary" :disabled="!puoAgire || selezionati.length === 0 || fase !== 'nessuna'"
                    data-origine="segnalazione" @click="apri({ tipo: 'segnalazione' })">
              Segnala indisponibilità ({{ selezionati.length }})
            </button>
          </div>
        </div>
        <ul v-if="!meseAgenda || giornoAgenda" class="items" aria-label="Appuntamenti dell'agenda">
          <li v-for="a in vociVisibili" :key="a.idPrenotazione" class="card appuntamento-stato" :style="stileStatoAgenda(a.stato)" data-appuntamento :data-id="a.idPrenotazione">
            <h3 class="item-title">{{ nome(a.paziente) }}</h3>
            <dl class="facts">
              <div><dt>Data e orario della struttura</dt><dd data-campo="quando">{{ dataOrario(a.inizio, a.fine) }}</dd></div>
              <div><dt>Prestazione</dt><dd data-campo="prestazione">{{ nomePrestazione(a) }}<template v-if="a.durata"> ({{ a.durata }} min)</template></dd></div>
              <div><dt>Stanza</dt><dd data-campo="stanza">{{ stanza(a) }}</dd></div>
              <div><dt>Stato</dt><dd data-campo="stato"><span class="badge-agenda">{{ statoMedico(a.stato) }}</span></dd></div>
              <div><dt>Prenotazione</dt><dd>n. {{ a.idPrenotazione }}</dd></div>
            </dl>
            <p v-if="store.state.segnalazioneInCorso.includes(a.idPrenotazione)" class="small" role="status">Segnalazione in corso: attendi il suo esito.</p>
            <p v-if="segnalata(a)" class="small" data-campo="indisponibilita"><strong>Indisponibilità segnalata</strong>, in attesa di gestione della segreteria.</p>
            <label v-if="multipla && selezionabile(a)" class="choice-inline">
              <input type="checkbox" :checked="selezionati.includes(a.idPrenotazione)" :disabled="!puoAgire || fase !== 'nessuna'"
                     @change="cambiaSelezione(a, $event)" />
              <span>Seleziona per la segnalazione<span class="visually-hidden"> dell'appuntamento di {{ nome(a.paziente) }}, {{ dataOrario(a.inizio, a.fine) }}</span></span>
            </label>
            <div v-if="!multipla && selezionabile(a)" class="actions">
              <button class="button azione-segnalazione" type="button" :disabled="!puoAgire || fase !== 'nessuna'" :data-origine="`segnalazione-${a.idPrenotazione}`" @click="segnalaSingola(a)">Segnala indisponibilità</button>
            </div>
            <div v-if="azione?.tipo === 'segnalazione' && azione.singola === a.idPrenotazione"
                 class="confirm conferma-singola" role="group" aria-labelledby="titolo-conferma-segnalazione"
                 :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-segnalazione>
              <h4 id="titolo-conferma-segnalazione" :ref="impostaTitolo" tabindex="-1">Confermi la segnalazione di indisponibilità?</h4>
              <p>Prenotazione n. {{ a.idPrenotazione }} — <strong>{{ nome(a.paziente) }}</strong>, {{ nomePrestazione(a) }}, {{ dataOrario(a.inizio, a.fine) }}.</p>
              <p class="small">La segreteria gestirà questo appuntamento; l'orario resta occupato.</p>
              <div class="actions">
                <button type="button" class="button" :disabled="fase === 'invio' || !puoAgire" @click="confermaSegnalazione">
                  {{ fase === 'invio' ? 'Segnalazione in corso…' : 'Conferma segnalazione' }}
                </button>
                <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="annulla">Indietro</button>
              </div>
            </div>
            <template v-if="proponiVisita(a)">
              <div v-if="azione?.tipo === 'visita' && azione.id === a.idPrenotazione" class="confirm" role="group"
                   :aria-labelledby="`titolo-conferma-visita-${a.idPrenotazione}`" :aria-busy="fase === 'invio' ? 'true' : 'false'" data-conferma-visita>
                <h4 :id="`titolo-conferma-visita-${a.idPrenotazione}`" :ref="impostaTitolo" tabindex="-1">Confermi la registrazione della visita?</h4>
                <p>Paziente: <strong>{{ nome(a.paziente) }}</strong>. {{ nomePrestazione(a) }}, {{ dataOrario(a.inizio, a.fine) }}, stanza: {{ stanza(a) }}. Prenotazione n. {{ a.idPrenotazione }}.</p>
                <p class="small">La prenotazione diventerà «Effettuata». Il server verifica che lo slot sia terminato, che tu sia ancora il medico effettivo e che non ci siano pagamenti in attesa.</p>
                <div class="actions">
                  <button type="button" class="button" :disabled="fase === 'invio' || !puoAgire" @click="confermaRegistrazione">
                    {{ fase === 'invio' ? 'Registrazione in corso…' : 'Conferma registrazione' }}
                  </button>
                  <button type="button" class="button secondary" :disabled="fase === 'invio'" @click="annulla">Annulla</button>
                </div>
              </div>
              <div v-else class="actions">
                <button type="button" class="button azione-visita" :disabled="!puoAgire || fase !== 'nessuna'" :data-origine="`visita-${a.idPrenotazione}`" @click="apri({ tipo: 'visita', id: a.idPrenotazione })">
                  Registra visita<span class="visually-hidden"> di {{ nome(a.paziente) }}, {{ dataOrario(a.inizio, a.fine) }}</span>
                </button>
              </div>
            </template>
            <p v-else-if="store.state.visiteInCorso.includes(a.idPrenotazione)" class="small" role="status">Registrazione della visita in corso: attendi il suo esito.</p>
            <p v-else-if="store.visitaIncerta(a.idPrenotazione)" class="small"><strong>Registrazione della visita da verificare.</strong></p>
            <p v-else-if="registrabile(a, medico) && store.segnalazioneBloccata(a.idPrenotazione)" class="small" data-visita-sospesa>
              Registrazione della visita sospesa finché l'esito della segnalazione non è verificato.
            </p>
            <RefertoCaricamento v-if="proponiReferto(a)" :key="`referto-${chiave}-${a.idPrenotazione}`" :appuntamento="a"
                                :abilitato="puoAgire && fase === 'nessuna'" @rileggi="rileggi" />
          </li>
        </ul>
      </template>
    </template>
  </section>
</template>

<style scoped>
.conferma-singola { grid-column: 1 / -1; inline-size: 100%; min-inline-size: 0; margin-block: .75rem; }
.appuntamento-stato { background: var(--agenda-fondo, white); border-color: var(--agenda-accento, #cbd3e2); border-inline-start-width: .3rem; }
.badge-agenda { display: inline-block; padding: .2rem .45rem; border: 1px solid var(--agenda-accento); border-radius: .35rem; color: var(--agenda-testo); background: var(--agenda-fondo); font-weight: 600; }
[data-appuntamento] :deep(.actions .button),
[data-appuntamento] :deep(.referto-medico > div > .button) {
  width: min(100%, 20rem);
  min-height: 2.75rem;
  white-space: normal;
  overflow-wrap: anywhere;
  text-align: center;
}
</style>
