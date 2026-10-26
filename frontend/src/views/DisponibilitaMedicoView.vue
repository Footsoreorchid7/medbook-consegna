<script setup>
import CalendarioAgenda from '../components/CalendarioAgenda.vue'
import { periodoMese } from '../medico/calendario.js'
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { MAX_SLOT, configurazionePresente, fetchDisponibilita, fineSlot, normalizzaDataOra, validaIntervallo } from '../api/disponibilitaMedico.js'
import { useDisponibilitaMedico } from '../medico/disponibilita.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'
import { formatLocalDate, formatLocalTime, parseLocalDateTime, todayLocal } from '../utils/datetime.js'

/**
 * Disponibilità del medico : elenco personale, creazione, modifica dell'orario ed
 * eliminazione. La configurazione (prestazione e stanza) è della segreteria.
 *
 * Contesto locale: un oggetto nuovo a ogni cambio di epoca. Ogni operazione lo cattura
 * all'avvio e, dopo ogni await, applica messaggi, focus e reset solo se è ancora quello
 * corrente. Lo store conserva comunque l'esito del tentativo originario.
 */
const store = useDisponibilitaMedico()
const route = useRoute()

/**
 * aggiornata: i dati mostrati sono confermati dall'ultima lettura (non in rilettura).
 * superata: l'ultima lettura è stata scartata dallo store (avviata prima di un esito certo o di
 * una lettura più recente): i dati eventualmente mostrati non sono aggiornati e serve una
 * rilettura manuale. stato 'superata': nessun dato da mostrare.
 */
const elenco = reactive({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false })
const modulo = reactive({ dataInizio: '', inizio: '', dataFine: '', fine: '' })
const dataCollegamentoNonValida = ref(false)
const vista = ref(route.query.vista === 'elenco' ? 'elenco' : 'calendario')
const mese = ref(todayLocal().slice(0, 7))
const giorno = ref('')
const vociCalendario = computed(() => elenco.voci.map(s => ({ inizio: { date: s.inizio.slice(0, 10) } })))
const vociVisibili = computed(() => vista.value === 'elenco' ? elenco.voci : giorno.value ? elenco.voci.filter(s => s.inizio.slice(0, 10) === giorno.value) : [])
const erroriModulo = ref({})
const erroreModuloBox = ref(null)
/** Operazione aperta: { tipo: 'crea'|'orario'|'elimina', id?, fase: 'modulo'|'conferma'|'invio', riepilogo? }. */
const azione = ref(null)
const nuovoInizio = ref('')
const orarioModificato = ref(false)
const erroreOrario = ref('')
const avviso = ref(null)
const avvisoBox = ref(null)
const titoloElenco = ref(null)
const titoloConferma = ref(null)
const impostaTitolo = (el) => { titoloConferma.value = el }
const campoOrario = ref(null)
const impostaCampoOrario = (el) => { campoOrario.value = el }
let contesto = {}
let origine = null

function nuovoContesto() {
  giorno.value = ''
  contesto = {}
  elenco.voci = []
  elenco.stato = 'caricamento'
  elenco.errore = ''
  elenco.aggiornata = false
  elenco.superata = false
  azione.value = null
  avviso.value = null
  origine = null
}

const { session, idUtente: idMedico, nuovaLettura, attuale, smontato } = useLetturaProfilo('medico', {
  azzera: nuovoContesto,
  ricarica: () => leggi(),
})
const corrente = (ctx) => ctx === contesto && !smontato()

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

async function mostra(ctx, tipo, testo) {
  if (!corrente(ctx)) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && corrente(ctx)) avvisoBox.value?.focus()
}

/** Le scelte di presentazione chiudono le vecchie conferme senza alterare lo store. */
function cambiaPresentazione(cambio) {
  if (azione.value?.fase === 'invio') return
  contesto = {}
  azione.value = null
  avviso.value = null
  origine = null
  cambio()
  // GET completo, indipendente dal mese: si sostituiscono solo letture ancora pendenti/non valide.
  if (elenco.stato !== 'pronto' || !elenco.aggiornata) leggi()
}
function scegliMese(valore) {
  if (!periodoMese(valore)) return
  cambiaPresentazione(() => { mese.value = valore; giorno.value = '' })
}
function scegliGiorno(valore) {
  if (!elenco.aggiornata || !valore.startsWith(`${mese.value}-`)) return
  cambiaPresentazione(() => { giorno.value = valore })
}
function scegliVista(valore) {
  if (!['calendario', 'elenco'].includes(valore) || valore === vista.value) return
  cambiaPresentazione(() => { vista.value = valore; giorno.value = '' })
}
async function aggiungiGiorno() {
  if (!giorno.value || azione.value?.fase === 'invio') return
  cambiaPresentazione(() => {
    Object.assign(modulo, { dataInizio: giorno.value, dataFine: giorno.value, inizio: '', fine: '' })
    erroriModulo.value = {}
  })
  const ctx = contesto
  await nextTick()
  if (corrente(ctx)) document.getElementById('disponibilita-inizio')?.focus()
}

// ---------------------------------------------------------------- formati

/** "lunedì 20/10/2026, 09:00" (con i secondi se non nulli), orario della struttura senza conversioni. */
function quando(norm) {
  const p = parseLocalDateTime(norm)
  const s = norm.slice(17, 19)
  return `${formatLocalDate(p)}, ${formatLocalTime(p)}${s !== '00' ? `:${s}` : ''}`
}
const intervallo = (x) => `dal ${quando(x.inizio)} al ${quando(x.fine)}`
const slotTesto = (n) => (n === 1 ? '1 slot da 60 minuti' : `${n} slot da 60 minuti`)
function configurazione(s) {
  if (s.configurata) return `Configurata: ${s.prestazione.nome ?? 'prestazione assegnata'}, ${s.stanza.nome ?? 'stanza assegnata'}`
  if (configurazionePresente(s)) {
    return `Configurazione parziale: ${s.prestazione ? `prestazione ${s.prestazione.nome ?? 'assegnata'}` : 'prestazione non assegnata'}, ${s.stanza ? `stanza ${s.stanza.nome ?? 'assegnata'}` : 'stanza non assegnata'}`
  }
  return 'Da configurare (prestazione e stanza a cura della segreteria)'
}

// ---------------------------------------------------------------- lettura

async function leggi() {
  const id = idMedico()
  if (id === null) return
  const ctx = contesto
  const mia = nuovaLettura()
  elenco.aggiornata = false
  elenco.superata = false
  elenco.errore = ''
  if (elenco.stato !== 'pronto') elenco.stato = 'caricamento'
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchDisponibilita(session.protectedRequest, id)
    if (!attuale(mia) || !corrente(ctx)) return
    // Solo una lettura accettata dallo store diventa l'elenco su cui autorizzare le azioni.
    // Una lettura scartata (superata da un esito certo o da una lettura più recente) non viene
    // adottata: restano gli eventuali dati precedenti, non aggiornati, con la rilettura manuale.
    if (!store.applicaElenco(voci, ticket)) {
      elenco.superata = true
      if (elenco.stato !== 'pronto') elenco.stato = 'superata'
      return
    }
    elenco.voci = voci
    elenco.stato = 'pronto'
    elenco.aggiornata = true
  } catch (error) {
    if (!attuale(mia) || !corrente(ctx) || error instanceof StaleResponseError) return
    elenco.errore = messaggio(error)
    elenco.stato = 'errore'
  }
}
const rileggi = () => leggi()

// ---------------------------------------------------------------- idoneità

const puoAgire = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && idMedico() !== null
  && !session.state.passwordUncertain && !session.state.busy)
const inAggiornamento = computed(() => elenco.stato === 'pronto' && !elenco.aggiornata && !elenco.superata)
const libero = computed(() => azione.value === null)
const voceCorrente = (id) => elenco.voci.find((v) => v.idDisponibilita === id) ?? null
/** Modifica ed eliminazione proposte solo senza configurazione e senza operazioni pendenti sullo slot. */
const modificabile = (s) => !configurazionePresente(s) && !store.slotBloccato(s.idDisponibilita)

// Una conferma o un modulo aperti sui dati precedenti non restano utilizzabili durante una rilettura.
watch(puoAgire, (valido) => {
  if (valido || !azione.value || azione.value.fase === 'invio') return
  azione.value = null
  origine = null
  nextTick(() => { if (!smontato() && document.activeElement === document.body) titoloElenco.value?.focus() })
}, { flush: 'sync' })

async function apri(nuova, chiaveOrigine, focus = 'titolo') {
  if (!puoAgire.value || !libero.value) return
  origine = chiaveOrigine
  avviso.value = null
  azione.value = nuova
  await nextTick()
  if (smontato()) return
  ;(focus === 'orario' ? campoOrario.value : titoloConferma.value)?.focus()
}

/** Chiude senza scritture e riporta il focus al controllo d'origine, o al titolo dell'elenco. */
async function annulla() {
  if (azione.value?.fase === 'invio') return
  azione.value = null
  const o = origine
  origine = null
  await nextTick()
  if (smontato()) return
  const b = o ? document.querySelector(`[data-origine="${o}"]`) : null
  ;(b && !b.disabled ? b : titoloElenco.value)?.focus()
}

// ---------------------------------------------------------------- creazione

const ERRORI_INTERVALLO = {
  inizio: { campo: 'inizio', testo: "Inserisci data e ora di inizio valide (anni 1000–9999)." },
  fine: { campo: 'fine', testo: 'Inserisci data e ora di fine valide (anni 1000–9999).' },
  ordine: { campo: 'fine', testo: "La fine deve essere successiva all'inizio." },
  durata: { campo: 'fine', testo: "L'intervallo deve durare un multiplo esatto di 60 minuti." },
  troppi: { campo: 'fine', testo: `L'intervallo può contenere al massimo ${MAX_SLOT} slot da 60 minuti.` },
}

async function prosegui() {
  const v = validaIntervallo(dataOraAlMinuto(modulo.dataInizio, modulo.inizio), dataOraAlMinuto(modulo.dataFine, modulo.fine))
  if (v.errore) {
    const e = ERRORI_INTERVALLO[v.errore]
    erroriModulo.value = { [e.campo]: e.testo }
    await nextTick()
    erroreModuloBox.value?.focus()
    return
  }
  erroriModulo.value = {}
  if (store.creazioneBloccata({ inizio: v.inizio, fine: v.fine })) {
    await mostra(contesto, 'errore', "Un'altra creazione in corso o dall'esito incerto riguarda un intervallo sovrapposto: verificane prima l'esito.")
    return
  }
  await apri({ tipo: 'crea', fase: 'conferma', riepilogo: { inizio: v.inizio, fine: v.fine, slot: v.slot } }, 'crea')
}

const ESITI_CREAZIONE = {
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  bloccata: "Un'altra creazione in corso o dall'esito incerto riguarda un intervallo sovrapposto: verificane prima l'esito.",
}

async function confermaCreazione() {
  const a = azione.value
  if (a?.tipo !== 'crea' || a.fase !== 'conferma' || !puoAgire.value) return
  const ctx = contesto
  const { inizio, fine, slot } = a.riepilogo
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.crea(inizio, fine)
  if (!corrente(ctx)) return
  if (['creata', 'non-valida', 'conflitto', 'non-trovata'].includes(esito.kind)) rileggi()
  azione.value = null
  origine = null
  switch (esito.kind) {
    case 'creata':
      Object.assign(modulo, { dataInizio: '', inizio: '', dataFine: '', fine: '' })
      await mostra(ctx, 'successo', `Create ${slotTesto(slot)}, ${intervallo({ inizio, fine })}. Prestazione e stanza saranno assegnate dalla segreteria.`)
      break
    case 'non-valida':
    case 'conflitto':
    case 'non-trovata':
      await mostra(ctx, 'errore', `Creazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} Nessuna disponibilità è stata creata. L'elenco viene riletto.`)
      break
    case 'incerto':
      await mostra(ctx, 'errore', "Non è stato possibile stabilire se le disponibilità siano state create. Il tentativo non è stato ripetuto: verificalo negli esiti da verificare prima di un nuovo invio per lo stesso intervallo.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra(ctx, 'errore', ESITI_CREAZIONE[esito.kind] ?? ESITI_CREAZIONE.rifiutata)
  }
}

// ---------------------------------------------------------------- modifica ed eliminazione

async function apriOrario(s) {
  nuovoInizio.value = s.inizio.slice(0, 16)
  orarioModificato.value = false
  erroreOrario.value = ''
  await apri({ tipo: 'orario', id: s.idDisponibilita, fase: 'modulo' }, `orario-${s.idDisponibilita}`, 'orario')
}

// Il campo mostra minuti; il valore originale resta distinto fino a un evento di modifica.
const inizioProposto = computed(() => {
  const originale = azione.value?.tipo === 'orario' && voceCorrente(azione.value.id)
  if (!orarioModificato.value) return originale ? originale.inizio : null
  const n = normalizzaDataOra(nuovoInizio.value)
  return n?.endsWith(':00') ? n : null
})
const nuovaFine = computed(() => {
  if (!orarioModificato.value) return voceCorrente(azione.value?.id)?.fine ?? null
  return inizioProposto.value ? fineSlot(inizioProposto.value) : null
})

async function proseguiOrario() {
  const a = azione.value
  const s = a && voceCorrente(a.id)
  if (a?.tipo !== 'orario' || a.fase !== 'modulo' || !s) return
  const inizio = inizioProposto.value
  const fine = inizio && fineSlot(inizio)
  erroreOrario.value = !fine ? 'Inserisci data e ora di inizio valide (anni 1000–9999).' : inizio === s.inizio ? "Il nuovo orario coincide con quello attuale." : ''
  if (erroreOrario.value) {
    await nextTick()
    campoOrario.value?.focus()
    return
  }
  azione.value = { ...a, fase: 'conferma', riepilogo: { originale: { inizio: s.inizio, fine: s.fine }, nuovo: { inizio, fine } } }
  await nextTick()
  if (!smontato()) titoloConferma.value?.focus()
}

const ESITI_SLOT = {
  rifiutata: 'Operazione non autorizzata. Se il problema persiste, esci e accedi di nuovo.',
  'password-incerta': "Le operazioni sono sospese finché non verifichi l'esito del cambio password.",
  occupato: "Un'operazione su questa disponibilità è già in corso.",
  bloccata: "L'esito di un'operazione precedente su questa disponibilità è ancora da verificare.",
  configurata: 'La disponibilità risulta configurata: modifica ed eliminazione non sono disponibili.',
  invariata: 'Il nuovo orario coincide con quello attuale.',
}

async function confermaSlot() {
  const a = azione.value
  const s = a && voceCorrente(a.id)
  if (!s || !['orario', 'elimina'].includes(a.tipo) || a.fase !== 'conferma' || !puoAgire.value || !modificabile(s)) return
  const ctx = contesto
  azione.value = { ...a, fase: 'invio' }
  const esito = a.tipo === 'orario' ? await store.modificaOrario(s, a.riepilogo.nuovo.inizio) : await store.elimina(s)
  if (!corrente(ctx)) return
  // Dopo un esito certo, anche un rifiuto (400, 404, 409), si rilegge senza ripetere la scrittura.
  if (['modificata', 'eliminata', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  azione.value = null
  origine = null
  const operazione = a.tipo === 'orario' ? 'Modifica' : 'Eliminazione'
  switch (esito.kind) {
    case 'modificata':
      await mostra(ctx, 'successo', `Orario modificato: la disponibilità ora va ${intervallo(esito.slot)}.`)
      break
    case 'eliminata':
      await mostra(ctx, 'successo', `Disponibilità ${intervallo(s)} eliminata.`)
      break
    case 'conflitto':
    case 'non-valida':
      await mostra(ctx, 'errore', `${operazione} rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} L'elenco viene riletto.`)
      break
    case 'non-trovata':
      await mostra(ctx, 'errore', `${operazione} rifiutata: la disponibilità non risulta tra le tue. L'elenco viene riletto.`)
      break
    case 'incerto':
      await mostra(ctx, 'errore', `Non è stato possibile stabilire l'esito dell'operazione. Non è stata ripetuta: verificala negli esiti da verificare prima di altre operazioni su questa disponibilità.`)
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await mostra(ctx, 'errore', ESITI_SLOT[esito.kind] ?? ESITI_SLOT.rifiutata)
  }
}

// ---------------------------------------------------------------- verifiche manuali

const TESTI_CREAZIONE = {
  completa: (x) => `Nell'elenco risultano tutti gli slot dell'intervallo ${intervallo(x)}, assenti prima del tentativo. Non è possibile stabilire con certezza che siano stati creati dal tuo tentativo.`,
  parziale: (x) => `Riscontro parziale nell'intervallo ${intervallo(x)} (disponibilità già presenti, sovrapposte o incomplete): non è conclusivo. Nuove creazioni sovrapposte restano bloccate; puoi ripetere la verifica.`,
  assente: (x) => `Nell'intervallo ${intervallo(x)} non risulta alcuna disponibilità. Questo non prova che il tentativo non sia stato registrato: puoi creare di nuovo l'intervallo solo con una nuova scelta esplicita (se fosse stato registrato, il server rifiuterebbe la sovrapposizione).`,
}

async function verificaCreazione(x) {
  const ctx = contesto
  avviso.value = null
  const esito = await store.verificaCreazione(x.chiave)
  if (!corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await mostra(ctx, 'info', TESTI_CREAZIONE[esito.esito](x))
  } else if (esito.kind === 'superata') {
    await mostra(ctx, 'info', 'Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Il blocco resta: ripeti la verifica.')
  } else if (esito.kind === 'errore') {
    await mostra(ctx, 'errore', "Verifica non riuscita: l'elenco non è leggibile. Riprova più tardi.")
  }
}

function testoSlot(x, esito, s) {
  const prima = intervallo(x.originale)
  if (x.tipo === 'elimina') {
    return esito === 'assente'
      ? `La disponibilità ${prima} non risulta più nell'elenco. Questo non prova che sia stata eliminata dal tuo tentativo.`
      : `La disponibilità risulta ancora presente (${intervallo(s)}): l'eliminazione non risulta eseguita. Un nuovo tentativo è possibile solo con una nuova scelta esplicita.`
  }
  return {
    'nuovo-orario': `La disponibilità risulta con il nuovo orario (${intervallo(s ?? x.nuovo)}). Questo non prova che la modifica sia stata eseguita dal tuo tentativo.`,
    originale: `La disponibilità risulta ancora con l'orario originale (${prima}): la modifica non risulta applicata. Un nuovo tentativo è possibile solo con una nuova scelta esplicita.`,
    'altro-orario': `La disponibilità risulta con un orario diverso sia da quello originale sia da quello richiesto (${s ? intervallo(s) : ''}).`,
    assente: `La disponibilità ${prima} non risulta più nell'elenco: l'esito della modifica non può essere stabilito.`,
  }[esito]
}

async function verificaSlot(x) {
  const ctx = contesto
  avviso.value = null
  const esito = await store.verificaSlot(x.idDisponibilita)
  if (!corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await mostra(ctx, 'info', testoSlot(x, esito.esito, esito.slot))
  } else if (esito.kind === 'superata') {
    await mostra(ctx, 'info', 'Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Il blocco resta: ripeti la verifica.')
  } else if (esito.kind === 'errore') {
    await mostra(ctx, 'errore', "Verifica non riuscita: l'elenco non è leggibile. Riprova più tardi.")
  }
}

const daVerificare = computed(() => store.state.creazioniIncerte.length + store.state.slotIncerti.length > 0)
const erroriElenco = computed(() => Object.values(erroriModulo.value))

/** Solo valori nuovi al minuto: nessun arrotondamento implicito di secondi non nulli. */
function dataOraAlMinuto(data, ora) {
  if (!/^\d{2}:\d{2}(?::00)?$/.test(ora)) return null
  return normalizzaDataOra(`${data}T${ora.slice(0, 5)}:00`)
}
let collegamentoInizializzato = false
watch(() => route.query.data, (data) => {
  if (route.name !== 'disponibilita-medico') return
  const cambiato = collegamentoInizializzato
  collegamentoInizializzato = true
  if (cambiato) nuovoContesto()
  const valida = typeof data === 'string' && normalizzaDataOra(`${data}T00:00`) !== null && /^\d{4}-\d{2}-\d{2}$/.test(data)
  dataCollegamentoNonValida.value = data !== undefined && !valida
  Object.assign(modulo, { dataInizio: valida ? data : '', dataFine: valida ? data : '', inizio: '', fine: '' })
  erroriModulo.value = {}
  if (valida) { mese.value = data.slice(0, 7); giorno.value = data }
  if (cambiato) leggi()
}, { immediate: true })
onMounted(leggi)
</script>

<template>
  <h1>Le mie disponibilità</h1>
  <p class="muted">
    Orari espressi nell'orario della struttura (fuso orario da confermare). Le nuove disponibilità sono slot da 60 minuti;
    prestazione e stanza sono assegnate dalla segreteria.
  </p>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-disponibilita>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-disponibilita>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <section class="card" aria-labelledby="titolo-nuove">
    <h2 id="titolo-nuove">Nuove disponibilità</h2>
    <p v-if="dataCollegamentoNonValida" class="notice warning" role="status">La data nel collegamento non è valida: scegli le date del nuovo intervallo.</p>
    <form class="form" novalidate @submit.prevent="prosegui">
      <div v-if="erroriElenco.length" ref="erroreModuloBox" class="notice error" role="alert" tabindex="-1">
        <p>Controlla l'intervallo indicato.</p>
      </div>
      <p class="small">
        L'intervallo deve durare un multiplo esatto di 60 minuti (massimo {{ MAX_SLOT }} slot) e iniziare nel futuro:
        quest'ultimo controllo lo esegue il server con l'orario della struttura.
      </p>
      <div class="field">
        <label for="disponibilita-data-inizio">Data di inizio</label>
        <input id="disponibilita-data-inizio" v-model="modulo.dataInizio" type="date" min="1000-01-01" max="9999-12-31"
               :disabled="azione?.tipo === 'crea'" :aria-invalid="erroriModulo.inizio ? 'true' : undefined"
               :aria-describedby="erroriModulo.inizio ? 'errore-disponibilita-inizio' : undefined" />
        <label for="disponibilita-inizio">Ora di inizio</label>
        <input id="disponibilita-inizio" v-model="modulo.inizio" type="time" step="60"
               :disabled="azione?.tipo === 'crea'" :aria-invalid="erroriModulo.inizio ? 'true' : undefined"
               :aria-describedby="erroriModulo.inizio ? 'errore-disponibilita-inizio' : undefined" />
        <p v-if="erroriModulo.inizio" id="errore-disponibilita-inizio" class="field-error">{{ erroriModulo.inizio }}</p>
      </div>
      <div class="field">
        <label for="disponibilita-data-fine">Data di fine</label>
        <input id="disponibilita-data-fine" v-model="modulo.dataFine" type="date" min="1000-01-01" max="9999-12-31"
               :disabled="azione?.tipo === 'crea'" :aria-invalid="erroriModulo.fine ? 'true' : undefined"
               :aria-describedby="erroriModulo.fine ? 'errore-disponibilita-fine' : undefined" />
        <label for="disponibilita-fine">Ora di fine</label>
        <input id="disponibilita-fine" v-model="modulo.fine" type="time" step="60"
               :disabled="azione?.tipo === 'crea'" :aria-invalid="erroriModulo.fine ? 'true' : undefined"
               :aria-describedby="erroriModulo.fine ? 'errore-disponibilita-fine' : undefined" />
        <p v-if="erroriModulo.fine" id="errore-disponibilita-fine" class="field-error">{{ erroriModulo.fine }}</p>
      </div>
      <div v-if="azione?.tipo !== 'crea'">
        <button type="submit" class="button" data-origine="crea" :disabled="!puoAgire || !libero">Prosegui</button>
      </div>
    </form>
    <div v-if="azione?.tipo === 'crea'" class="confirm" role="group" aria-labelledby="titolo-conferma-creazione"
         :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma-creazione>
      <h3 id="titolo-conferma-creazione" :ref="impostaTitolo" tabindex="-1">Confermi la creazione delle disponibilità?</h3>
      <p>Intervallo: <strong>{{ intervallo(azione.riepilogo) }}</strong>, <strong data-campo="numero-slot">{{ slotTesto(azione.riepilogo.slot) }}</strong>.</p>
      <p class="small">Gli slot saranno da configurare. Il server rifiuta intervalli non futuri o sovrapposti ad altre disponibilità o appuntamenti.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgire" @click="confermaCreazione">
          {{ azione.fase === 'invio' ? 'Creazione in corso…' : 'Crea disponibilità' }}
        </button>
        <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Annulla</button>
      </div>
    </div>
  </section>

  <section v-if="daVerificare" class="card" aria-labelledby="titolo-esiti-disponibilita" data-da-verificare>
    <h2 id="titolo-esiti-disponibilita">Esiti da verificare</h2>
    <div v-for="x in store.state.creazioniIncerte" :key="`c-${x.chiave}`" class="notice warning" data-creazione-incerta>
      <p><strong>Creazione non determinata</strong> {{ intervallo(x) }} ({{ slotTesto(x.slot) }}).</p>
      <p v-if="x.esito === 'parziale'">Una verifica ha trovato un riscontro parziale, non conclusivo: nuove creazioni sovrapposte restano bloccate.</p>
      <p v-else-if="x.esito === 'assente'">Una verifica non ha trovato disponibilità nell'intervallo: non prova il fallimento; un nuovo invio è possibile solo su scelta esplicita.</p>
      <p v-else>Il tentativo non è stato ripetuto; nuove creazioni sovrapposte sono bloccate fino alla verifica.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <div class="actions">
        <button v-if="x.esito !== 'assente'" type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verificaCreazione(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : "Verifica nell'elenco" }}
        </button>
        <button v-else type="button" class="button secondary" @click="store.chiudiCreazioneIncerta(x.chiave, store.state.owner)">Rimuovi dall'elenco</button>
      </div>
    </div>
    <div v-for="x in store.state.slotIncerti" :key="`s-${x.idDisponibilita}`" class="notice warning" data-slot-incerto>
      <p>
        <strong>{{ x.tipo === 'orario' ? 'Modifica dell\'orario non determinata' : 'Eliminazione non determinata' }}</strong>
        per la disponibilità {{ intervallo(x.originale) }}<template v-if="x.nuovo">, nuovo orario richiesto {{ intervallo(x.nuovo) }}</template>.
      </p>
      <p v-if="x.esito === 'assente'">Una verifica non l'ha trovata nell'elenco: l'esito non è stato dedotto.</p>
      <p v-else>Il tentativo non è stato ripetuto; altre operazioni su questa disponibilità sono bloccate fino alla verifica.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <div class="actions">
        <button v-if="x.esito !== 'assente'" type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verificaSlot(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : "Verifica nell'elenco" }}
        </button>
        <button v-else type="button" class="button secondary" @click="store.chiudiSlotIncerto(x.idDisponibilita, store.state.owner)">Rimuovi dall'elenco</button>
      </div>
    </div>
  </section>

  <div class="actions" role="group" aria-label="Vista delle disponibilità">
    <button type="button" class="button secondary" data-vista="calendario" :aria-pressed="vista === 'calendario'" :disabled="azione?.fase === 'invio'" @click="scegliVista('calendario')">Calendario disponibilità</button>
    <button type="button" class="button secondary" data-vista="elenco" :aria-pressed="vista === 'elenco'" :disabled="azione?.fase === 'invio'" @click="scegliVista('elenco')">Elenco disponibilità</button>
  </div>
  <CalendarioAgenda v-if="vista === 'calendario'" tipo="disponibilita" :mese="mese" :selezionato="giorno" :voci="vociCalendario"
                   :aggiornato="elenco.aggiornata && !elenco.superata && azione?.fase !== 'invio'"
                   :navigazione-bloccata="azione?.fase === 'invio'" @mese="scegliMese" @giorno="scegliGiorno" />
  <section aria-labelledby="titolo-elenco-disponibilita" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-elenco-disponibilita" ref="titoloElenco" tabindex="-1">Disponibilità</h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento delle disponibilità…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare le disponibilità. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="rileggi">Riprova</button></div>
    </div>
    <div v-else-if="elenco.stato === 'superata'" class="notice warning" role="status" data-elenco-superato>
      <p>L'elenco letto è stato superato da operazioni più recenti e non è stato usato.</p>
      <div class="actions"><button type="button" class="button" @click="rileggi">Aggiorna l'elenco</button></div>
    </div>
    <template v-else>
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 disponibilità' : `${elenco.voci.length} disponibilità` }}.</p>
      <p v-if="inAggiornamento" class="small" role="status" data-aggiornamento>Aggiornamento dell'elenco in corso: le azioni sono sospese.</p>
      <div v-if="elenco.superata" class="notice warning" role="status" data-elenco-superato>
        <p>L'elenco mostrato non è aggiornato: l'ultima lettura è stata superata da operazioni più recenti e non è stata usata. Le azioni sono sospese.</p>
        <div class="actions"><button type="button" class="button" @click="rileggi">Aggiorna l'elenco</button></div>
      </div>
      <p v-if="session.state.passwordUncertain" class="small">
        Le operazioni sono sospese finché non verifichi l'esito del cambio password.
        <RouterLink to="/area/password">Verifica il cambio password</RouterLink>
      </p>
      <p v-if="elenco.voci.length === 0">Non hai disponibilità registrate.</p>
      <p v-if="vista === 'calendario' && !giorno" class="small">Seleziona un giorno per mostrare le sue disponibilità.</p>
      <div v-if="vista === 'calendario' && giorno" data-giorno-selezionato>
        <h3>Disponibilità del {{ formatLocalDate({ year: Number(giorno.slice(0, 4)), month: Number(giorno.slice(5, 7)), day: Number(giorno.slice(8, 10)) }) }}</h3>
        <p v-if="elenco.aggiornata && !elenco.superata && !vociVisibili.length">Nessuna disponibilità</p>
        <div class="actions"><button type="button" class="button secondary" :disabled="azione?.fase === 'invio'" @click="aggiungiGiorno">Aggiungi disponibilità</button></div>
      </div>
      <ul v-if="vociVisibili.length" class="items" aria-label="Elenco delle disponibilità">
        <li v-for="s in vociVisibili" :key="s.idDisponibilita" class="card" data-disponibilita :data-id="s.idDisponibilita">
          <h3 class="item-title" data-campo="quando">{{ quando(s.inizio) }}</h3>
          <dl class="facts">
            <div><dt>Fine</dt><dd data-campo="fine">{{ quando(s.fine) }}</dd></div>
            <div><dt>Configurazione</dt><dd data-campo="configurazione">{{ configurazione(s) }}</dd></div>
            <div><dt>Disponibilità</dt><dd>n. {{ s.idDisponibilita }}</dd></div>
          </dl>
          <p v-if="configurazionePresente(s)" class="small" data-non-modificabile>Con una configurazione, anche parziale, il server non consente modifica ed eliminazione.</p>
          <p v-else-if="store.state.slotInCorso.includes(s.idDisponibilita)" class="small" role="status">Operazione in corso: attendi il suo esito.</p>
          <p v-else-if="store.slotBloccato(s.idDisponibilita)" class="small"><strong>Esito di un'operazione da verificare.</strong></p>
          <template v-else>
            <p class="small">Il server verifica che la disponibilità sia futura, senza prenotazioni e senza sovrapposizioni.</p>
            <div v-if="azione?.id === s.idDisponibilita && azione.tipo === 'orario' && azione.fase === 'modulo'" class="form" data-modulo-orario>
              <div class="field">
                <label :for="`orario-${s.idDisponibilita}`">Nuovo inizio (la fine è 60 minuti dopo)</label>
                <input :id="`orario-${s.idDisponibilita}`" :ref="impostaCampoOrario" v-model="nuovoInizio" @input="orarioModificato = true" type="datetime-local" step="60"
                       min="1000-01-01T00:00" max="9999-12-31T22:59"
                       :aria-invalid="erroreOrario ? 'true' : undefined" :aria-describedby="erroreOrario ? `errore-orario-${s.idDisponibilita}` : `fine-orario-${s.idDisponibilita}`" />
                <p :id="`fine-orario-${s.idDisponibilita}`" class="small">Nuova fine: {{ nuovaFine ? quando(nuovaFine) : '—' }}</p>
                <p v-if="erroreOrario" :id="`errore-orario-${s.idDisponibilita}`" class="field-error">{{ erroreOrario }}</p>
              </div>
              <p v-if="s.inizio.slice(17, 19) !== '00' || s.fine.slice(17, 19) !== '00'" class="small" data-secondi-storici>
                L'intervallo attuale contiene secondi: {{ intervallo(s) }}. Finché non modifichi il campo resta invariato; un nuovo orario avrà secondi 00.
              </p>
              <div class="actions">
                <button type="button" class="button" :disabled="!puoAgire" @click="proseguiOrario">Prosegui</button>
                <button type="button" class="button secondary" @click="annulla">Annulla</button>
              </div>
            </div>
            <div v-else-if="azione?.id === s.idDisponibilita" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${s.idDisponibilita}`"
                 :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
              <template v-if="azione.tipo === 'orario'">
                <h4 :id="`titolo-conferma-${s.idDisponibilita}`" :ref="impostaTitolo" tabindex="-1">Confermi il nuovo orario?</h4>
                <p>Orario attuale: <strong data-campo="originale">{{ intervallo(azione.riepilogo.originale) }}</strong>.</p>
                <p v-if="azione.riepilogo.originale.inizio.slice(17, 19) !== azione.riepilogo.nuovo.inizio.slice(17, 19) || azione.riepilogo.originale.fine.slice(17, 19) !== azione.riepilogo.nuovo.fine.slice(17, 19)" class="small" data-variazione-secondi>La modifica porta i secondi dell'inizio da {{ azione.riepilogo.originale.inizio.slice(17, 19) }} a 00 e quelli della fine da {{ azione.riepilogo.originale.fine.slice(17, 19) }} a 00.</p>
                <p>Nuovo orario: <strong data-campo="nuovo">{{ intervallo(azione.riepilogo.nuovo) }}</strong>.</p>
              </template>
              <template v-else>
                <h4 :id="`titolo-conferma-${s.idDisponibilita}`" :ref="impostaTitolo" tabindex="-1">Confermi l'eliminazione della disponibilità?</h4>
                <p>Disponibilità n. {{ s.idDisponibilita }}: <strong>{{ intervallo(s) }}</strong>.</p>
              </template>
              <p class="small">Il server decide se l'operazione è possibile (disponibilità futura, senza prenotazioni né sovrapposizioni).</p>
              <div class="actions">
                <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgire" @click="confermaSlot">
                  {{ azione.fase === 'invio' ? 'Operazione in corso…' : azione.tipo === 'orario' ? 'Conferma nuovo orario' : 'Elimina disponibilità' }}
                </button>
                <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Annulla</button>
              </div>
            </div>
            <div v-else class="actions">
              <button type="button" class="button secondary" :data-origine="`orario-${s.idDisponibilita}`" :disabled="!puoAgire || !libero" @click="apriOrario(s)">
                Modifica orario<span class="visually-hidden"> della disponibilità {{ intervallo(s) }}</span>
              </button>
              <button type="button" class="button secondary" :data-origine="`elimina-${s.idDisponibilita}`" :disabled="!puoAgire || !libero"
                      @click="apri({ tipo: 'elimina', id: s.idDisponibilita, fase: 'conferma' }, `elimina-${s.idDisponibilita}`)">
                Elimina<span class="visually-hidden"> la disponibilità {{ intervallo(s) }}</span>
              </button>
            </div>
          </template>
        </li>
      </ul>
    </template>
  </section>
</template>


<style scoped>
[data-vista][aria-pressed="true"] { border-color: var(--color-primary); box-shadow: inset 0 0 0 1px var(--color-primary); font-weight: 600; }
</style>
