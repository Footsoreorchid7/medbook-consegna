<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue'
import { RouterLink, onBeforeRouteLeave } from 'vue-router'
import { StaleResponseError } from '../api/http.js'
import { fetchPrestazioni } from '../api/catalogo.js'
import { STATI_MEDICO, fetchMediciAdmin, validaCollegamento, validaNuovoMedico } from '../api/medici.js'
import { fetchRicerca, validaFiltri } from '../api/utenti.js'
import { RUOLO, STATO_ACCESSO, TESTO_ERRORE_VERIFICA, TESTO_INCOERENTE, TESTO_NON_CONCLUSIVA, TESTO_SUPERATA, testoEsito } from '../admin/formato.js'
import { CAUSE_CONFLITTO, OPERAZIONI_MEDICO, STATO_MEDICO, descriviIncertaMedico, nome, ruoloGestionale, statoMedico, testoVerificaMedico } from '../admin/formatoMedici.js'
import { MEDICI, RICERCA, TIPI_MEDICO, chiaveEmail, chiaveUtente } from '../admin/store.js'
import { usePaginaAdmin } from '../admin/usePaginaAdmin.js'

/**
 * Medici : elenco con proiezione amministrativa e filtri, creazione di un medico, collegamento
 * di un profilo medico a un utente esistente scelto con la ricerca amministrativa .
 * Lo stato mostrato è lo stato operativo del medico, non lo stato di accesso dell'account.
 */
const titoloElenco = ref(null)
const p = usePaginaAdmin({ azzera, ricarica: () => leggiElenco(), titoloRiserva: () => titoloElenco.value })
const { store, session, avviso, avvisoBox, mostra, messaggioLettura, attesa, puoScrivere, azione, libero, apri, annulla, chiudi, impostaTitolo } = p

// La visibilità locale non modifica pending, incertezze o attese dello store.
const modulo = ref(null)
const pulsanteCrea = ref(null)
const pulsanteCollega = ref(null)
let revisioneModulo = 0
const invioInCorso = computed(() => azione.value?.fase === 'invio')
const compilato = () => modulo.value === 'crea'
  ? !!password.value || Object.values(nuovo).some(v => v !== '')
  : modulo.value === 'collega' && (scelto.value !== null || Object.values(filtriUtente).some(v => v !== '') || Object.values(professionali).some(v => v !== ''))
const consentiAbbandono = () => !compilato() || window.confirm('Chiudere il modulo ed eliminare i dati compilati? Un’operazione già inviata non viene annullata.')
function pulisciModulo() {
  password.value = ''
  Object.assign(nuovo, VUOTO_CREA())
  erroriCrea.value = {}
  seqRicerca += 1
  ricercaCtx = {}
  ticketUtenti = new Map()
  Object.assign(filtriUtente, VUOTI_RICERCA())
  Object.assign(ricerca, RICERCA_VUOTA())
  Object.assign(professionali, { specializzazione: '', numeroIscrizioneOrdine: '' })
  scelto.value = null
  erroriRicerca.value = {}
  erroriCollega.value = {}
  if (!invioInCorso.value) chiudi()
}
async function apriModulo(tipo) {
  if (invioInCorso.value || modulo.value === tipo || !consentiAbbandono()) return
  const ctx = p.contestoCorrente()
  const mia = ++revisioneModulo
  pulisciModulo()
  modulo.value = tipo
  await nextTick()
  if (p.corrente(ctx) && mia === revisioneModulo && modulo.value === tipo)
    document.querySelector(tipo === 'crea' ? '#medico-nome' : '#collega-idUtente')?.focus()
}
async function chiudiModulo() {
  if (!consentiAbbandono()) return
  const ctx = p.contestoCorrente()
  const origine = modulo.value === 'crea' ? pulsanteCrea : pulsanteCollega
  const mia = ++revisioneModulo
  pulisciModulo()
  modulo.value = null
  await nextTick()
  if (p.corrente(ctx) && mia === revisioneModulo && modulo.value === null) {
    // Il pulsante d'origine resta focalizzabile anche se una POST è ancora pendente.
    origine.value?.focus()
  }
}
onBeforeRouteLeave(() => consentiAbbandono())
onBeforeUnmount(() => { password.value = ''; revisioneModulo += 1; seqRicerca += 1 })

// ---------------------------------------------------------------- catalogo (filtri)

const catalogo = reactive({ stato: 'caricamento', voci: [] })
async function leggiCatalogo() {
  try {
    const voci = await fetchPrestazioni()
    if (p.smontato()) return
    Object.assign(catalogo, { stato: 'pronto', voci })
  } catch {
    if (!p.smontato()) catalogo.stato = 'errore'
  }
}

// ---------------------------------------------------------------- elenco

const filtri = reactive({ stato: '', idPrestazione: '' })
const ELENCO_VUOTO = () => ({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false, filtri: {} })
const elenco = reactive(ELENCO_VUOTO())
const elencoTicket = shallowRef(null)
let seqElenco = 0

async function leggiElenco() {
  if (p.idAdmin() === null) return
  const ctx = p.contestoCorrente()
  const mia = ++seqElenco
  const applicati = { stato: filtri.stato, idPrestazione: filtri.idPrestazione ? Number(filtri.idPrestazione) : null }
  const stessi = elenco.stato === 'pronto' && elenco.filtri.stato === applicati.stato && elenco.filtri.idPrestazione === applicati.idPrestazione
  Object.assign(elenco, { errore: '', aggiornata: false, superata: false })
  if (!stessi) Object.assign(elenco, { stato: 'caricamento', voci: [], filtri: applicati })
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchMediciAdmin(session.protectedRequest, applicati)
    if (mia !== seqElenco || !p.corrente(ctx)) return
    if (!store.valida(MEDICI, ticket)) {
      elenco.superata = true
      if (elenco.stato !== 'pronto') elenco.stato = 'superata'
      return
    }
    Object.assign(elenco, { stato: 'pronto', voci, filtri: applicati, aggiornata: true })
    elencoTicket.value = ticket
    store.registraMedici(voci, ticket, applicati)
  } catch (error) {
    if (mia !== seqElenco || !p.corrente(ctx) || error instanceof StaleResponseError) return
    Object.assign(elenco, { stato: 'errore', errore: messaggioLettura(error) })
  }
}
const elencoInvalidato = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && !store.valida(MEDICI, elencoTicket.value))
const descrizioneFiltri = computed(() => {
  const f = elenco.filtri
  const parti = []
  if (f.stato) parti.push(`stato operativo ${STATO_MEDICO[f.stato]}`)
  if (f.idPrestazione) parti.push(`abilitati a «${catalogo.voci.find((x) => x.idPrestazione === f.idPrestazione)?.nome ?? `prestazione n. ${f.idPrestazione}`}»`)
  return parti.length ? parti.join(', ') : 'nessuno (tutti i medici)'
})

// ---------------------------------------------------------------- creazione

const VUOTO_CREA = () => ({ nome: '', cognome: '', email: '', specializzazione: '', numeroIscrizioneOrdine: '' })
const nuovo = reactive(VUOTO_CREA())
/** Password del nuovo medico: solo in questo campo, mai nello store, nei riepiloghi o nei messaggi. */
const password = ref('')
const erroriCrea = ref({})
const riepilogoCrea = ref(null)

async function proseguiCrea() {
  if (modulo.value !== 'crea' || !libero.value || !puoScrivere.value) return
  const { dati, errori } = validaNuovoMedico({ ...nuovo, password: password.value })
  erroriCrea.value = errori
  if (dati && store.bloccata(chiaveEmail(dati.email))) erroriCrea.value = { email: "Un'operazione precedente su questa email è ancora in corso o da verificare." }
  if (Object.keys(erroriCrea.value).length) {
    await nextTick()
    riepilogoCrea.value?.focus()
    return
  }
  await apri({ tipo: 'crea', dati, valori: Object.freeze({ ...nuovo }), modulo: revisioneModulo, idonea: () => true }, 'crea-medico')
}

const focusPerso = () => !document.activeElement || document.activeElement === document.body || !document.activeElement.isConnected
async function confermaCrea() {
  const a = azione.value
  if (modulo.value !== 'crea' || a?.tipo !== 'crea' || a.fase !== 'conferma' || !puoScrivere.value) return
  const ctx = p.contestoCorrente()
  const segreto = password.value
  password.value = ''
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.creaMedico(a.dati, segreto)
  if (!p.corrente(ctx)) return
  if (['eseguita', 'conflitto'].includes(esito.kind)) leggiElenco()
  chiudi()
  if (esito.kind === 'eseguita') {
    const m = esito.risultato
    const chiudibile = modulo.value === 'crea' && a.modulo === revisioneModulo && password.value === ''
      && Object.keys(a.valori).every(k => nuovo[k] === a.valori[k])
    if (chiudibile) {
      Object.assign(nuovo, VUOTO_CREA())
      erroriCrea.value = {}
      modulo.value = null
    }
    const generazione = revisioneModulo
    await mostra(ctx, 'successo', `Medico n. ${m.idUtente} creato: ${nome(m)}, ${m.email}, stato operativo «Attivo». `
      + "Nessun accesso automatico: comunica la password all'interessato con un canale sicuro, l'interfaccia non la conserva.")
    if (chiudibile && p.corrente(ctx) && generazione === revisioneModulo && modulo.value === null && focusPerso()) pulsanteCrea.value?.focus()
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    avviso.value = { tipo: 'errore', testo: `${testoConCause(esito, 'medico-crea')} La password è stata cancellata dal modulo.` }
    await nextTick()
    if (p.corrente(ctx) && focusPerso()) avvisoBox.value?.focus()
  }
}

/** 409 generico: elenca le cause previste dal contratto senza sceglierne una. */
function testoConCause(esito, tipo) {
  const base = testoEsito(esito)
  return esito.kind === 'conflitto' ? `${base} Possibili cause: ${CAUSE_CONFLITTO[tipo]}.` : base
}

// ---------------------------------------------------------------- collegamento a un utente esistente

const VUOTI_RICERCA = () => ({ idUtente: '', email: '', cognome: '', codiceFiscale: '' })
const filtriUtente = reactive(VUOTI_RICERCA())
const erroriRicerca = ref({})
const riepilogoRicerca = ref(null)
const RICERCA_VUOTA = () => ({ stato: 'nessuna', filtri: null, voci: [], prossimo: null, errore: '', ticket: null, altri: '', erroreAltri: '' })
const ricerca = reactive(RICERCA_VUOTA())
/** Ticket della pagina che ha restituito ogni utente (per la validità della scelta). */
let ticketUtenti = new Map()
let seqRicerca = 0
/** Contesto della ricerca applicata: nuovo a ogni ricerca, così pagine di ricerche diverse non si mescolano. */
let ricercaCtx = {}
/** Utente scelto: vista amministrativa gestione utenti con il ticket della pagina che l'ha restituito. */
const scelto = shallowRef(null)
const professionali = reactive({ specializzazione: '', numeroIscrizioneOrdine: '' })
const erroriCollega = ref({})
const riepilogoCollega = ref(null)

async function cercaUtente() {
  if (modulo.value !== 'collega' || invioInCorso.value) return
  const { filtri: validi, errori } = validaFiltri(filtriUtente)
  erroriRicerca.value = errori
  if (Object.keys(errori).length) {
    await nextTick()
    riepilogoRicerca.value?.focus()
    return
  }
  await eseguiRicercaUtenti(validi)
}

/** Prima pagina della ricerca: un cambio di filtri (o una ripetizione) azzera sempre il cursore. */
async function eseguiRicercaUtenti(validi) {
  if (modulo.value !== 'collega' || invioInCorso.value) return
  const ctx = p.contestoCorrente()
  const mia = ++seqRicerca
  const rc = {}
  ricercaCtx = rc
  scelto.value = null
  ticketUtenti = new Map()
  Object.assign(ricerca, { ...RICERCA_VUOTA(), stato: 'caricamento', filtri: validi })
  const ticket = store.inizioLettura()
  try {
    const pagina = await fetchRicerca(session.protectedRequest, validi)
    if (mia !== seqRicerca || rc !== ricercaCtx || !p.corrente(ctx)) return
    if (!store.valida(RICERCA, ticket)) {
      Object.assign(ricerca, { stato: 'errore', errore: 'Il risultato è stato superato da operazioni più recenti: ripeti la ricerca.' })
      return
    }
    for (const u of pagina.risultati) ticketUtenti.set(u.idUtente, ticket)
    Object.assign(ricerca, { stato: 'pronto', voci: pagina.risultati, prossimo: pagina.prossimoDopoId, ticket })
    store.registraRicerca(validi, 0, pagina, ticket)
  } catch (error) {
    if (mia !== seqRicerca || rc !== ricercaCtx || !p.corrente(ctx) || error instanceof StaleResponseError) return
    Object.assign(ricerca, { stato: 'errore', errore: messaggioLettura(error) })
  }
}

/** La ricerca applicata resta estendibile e utilizzabile solo finché nessun esito certo successivo la supera. */
const ricercaValida = computed(() => ricerca.stato === 'pronto' && store.valida(RICERCA, ricerca.ticket))
const ricercaInvalidata = computed(() => ricerca.stato === 'pronto' && !store.valida(RICERCA, ricerca.ticket))
const ripetiRicercaUtenti = () => { if (ricerca.filtri) eseguiRicercaUtenti(ricerca.filtri) }

/** Pagina successiva della stessa ricerca, dal cursore restituito dal server; mai su una ricerca superata. */
async function altriUtenti() {
  if (modulo.value !== 'collega' || invioInCorso.value || !ricercaValida.value || ricerca.prossimo === null || ricerca.altri === 'caricamento') return
  const ctx = p.contestoCorrente()
  const rc = ricercaCtx
  const dopo = ricerca.prossimo
  const ticket = store.inizioLettura()
  ricerca.altri = 'caricamento'
  ricerca.erroreAltri = ''
  try {
    const pagina = await fetchRicerca(session.protectedRequest, ricerca.filtri, dopo)
    if (rc !== ricercaCtx || !p.corrente(ctx) || ricerca.prossimo !== dopo) return
    ricerca.altri = ''
    if (!store.valida(RICERCA, ticket) || !store.valida(RICERCA, ricerca.ticket)) return
    for (const u of pagina.risultati) ticketUtenti.set(u.idUtente, ticket)
    ricerca.voci = [...ricerca.voci, ...pagina.risultati]
    ricerca.prossimo = pagina.prossimoDopoId
    store.registraRicerca(ricerca.filtri, dopo, pagina, ticket)
  } catch (error) {
    if (rc !== ricercaCtx || !p.corrente(ctx) || error instanceof StaleResponseError) return
    Object.assign(ricerca, { altri: 'errore', erroreAltri: messaggioLettura(error) })
  }
}

/** La scelta vale finché la ricerca e la pagina che l'ha restituita non sono superate per quell'utente. */
const sceltaValida = () => scelto.value !== null && scelto.value.rc === ricercaCtx && store.valida(RICERCA, ricerca.ticket)
  && store.valida(RICERCA, scelto.value.ticket) && store.valida(chiaveUtente(scelto.value.utente.idUtente), scelto.value.ticket)
const sceltaSuperata = computed(() => scelto.value !== null && !sceltaValida())

async function scegli(u) {
  if (modulo.value !== 'collega' || !libero.value) return
  const ctx = p.contestoCorrente()
  const mia = revisioneModulo
  if (store.bloccata(chiaveUtente(u.idUtente)) || !ricercaValida.value || !ticketUtenti.has(u.idUtente)) return
  scelto.value = { utente: u, ticket: ticketUtenti.get(u.idUtente), rc: ricercaCtx }
  Object.assign(professionali, { specializzazione: '', numeroIscrizioneOrdine: '' })
  erroriCollega.value = {}
  await nextTick()
  if (p.corrente(ctx) && mia === revisioneModulo && modulo.value === 'collega' && scelto.value?.utente === u) document.querySelector('#collega-specializzazione')?.focus()
}

async function proseguiCollega() {
  if (modulo.value !== 'collega' || !libero.value || !puoScrivere.value || !sceltaValida()) return
  const { dati, errori } = validaCollegamento(professionali)
  erroriCollega.value = errori
  if (!dati) {
    await nextTick()
    riepilogoCollega.value?.focus()
    return
  }
  await apri({ tipo: 'collega', utente: scelto.value.utente, dati, idonea: () => sceltaValida() && !store.bloccata(chiaveUtente(scelto.value.utente.idUtente)) }, 'collega')
}

async function confermaCollega() {
  const a = azione.value
  if (modulo.value !== 'collega' || a?.tipo !== 'collega' || a.fase !== 'conferma' || !puoScrivere.value || !a.idonea()) return
  const ctx = p.contestoCorrente()
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.collega(a.utente, a.dati)
  if (!p.corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata'].includes(esito.kind)) leggiElenco()
  chiudi()
  if (esito.kind === 'eseguita') {
    const m = esito.risultato
    scelto.value = null
    Object.assign(ricerca, RICERCA_VUOTA())
    await mostra(ctx, 'successo', `Profilo medico collegato all'utente n. ${m.idUtente} (${nome(m)}): stato operativo «Attivo»`
      + `${m.paziente ? ', profilo paziente conservato' : ''}. Apri il dettaglio dall'elenco per abilitazioni e altri dati.`)
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    await mostra(ctx, 'errore', testoConCause(esito, 'collega'))
  }
}

// ---------------------------------------------------------------- esiti da verificare

const incerte = computed(() => store.state.incerte.filter((x) => TIPI_MEDICO.includes(x.tipo)))
async function verifica(x) {
  const ctx = p.contestoCorrente()
  avviso.value = null
  const lettura = await store.leggiVerifica(x.chiave)
  if (!p.corrente(ctx)) return
  // Esito costruito ora, sul riscontro corrente.
  const esito = lettura.kind === 'letta' ? store.esitoVerifica(x.chiave, lettura) : lettura
  if (esito.kind === 'verificata') {
    leggiElenco()
    await mostra(ctx, 'info', testoVerificaMedico(esito.incerta, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'non-conclusiva') await mostra(ctx, 'info', TESTO_NON_CONCLUSIVA)
  else if (esito.kind === 'incoerente') await mostra(ctx, 'info', TESTO_INCOERENTE)
  else if (esito.kind === 'errore') await mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}

// ---------------------------------------------------------------- contesto

function azzera() {
  modulo.value = null
  revisioneModulo += 1
  Object.assign(professionali, { specializzazione: '', numeroIscrizioneOrdine: '' })
  seqElenco += 1
  seqRicerca += 1
  ricercaCtx = {}
  ticketUtenti = new Map()
  Object.assign(elenco, ELENCO_VUOTO())
  elencoTicket.value = null
  Object.assign(nuovo, VUOTO_CREA())
  password.value = ''
  erroriCrea.value = {}
  Object.assign(filtriUtente, VUOTI_RICERCA())
  Object.assign(ricerca, RICERCA_VUOTA())
  erroriRicerca.value = {}
  scelto.value = null
  erroriCollega.value = {}
}

onMounted(() => {
  leggiCatalogo()
  leggiElenco()
})
const lista = (errori) => Object.values(errori)
const descr = (errori, campo, id) => (errori[campo] ? id : undefined)
</script>

<template>
  <div class="medici-intestazione">
    <h1>Medici e abilitazioni</h1>
    <div class="actions">
      <button ref="pulsanteCrea" type="button" class="button" data-apri-medico :aria-expanded="modulo === 'crea'"
              aria-controls="modulo-medico" :aria-disabled="invioInCorso || undefined" @click="apriModulo('crea')">Nuovo medico</button>
      <button ref="pulsanteCollega" type="button" class="button secondary" data-apri-collegamento :aria-expanded="modulo === 'collega'"
              aria-controls="modulo-collegamento" :aria-disabled="invioInCorso || undefined" @click="apriModulo('collega')">Collega a un utente esistente</button>
    </div>
  </div>
  <p v-if="!modulo && invioInCorso" class="notice" role="status" data-operazione-medico-pendente>
    Operazione in corso: la chiusura del modulo non interrompe la richiesta. Attendi l'esito prima di un nuovo invio.
  </p>
  <p class="muted">
    Anagrafica amministrativa dei medici, creazione e collegamento del profilo medico a un utente esistente. Lo stato mostrato è
    lo stato operativo del medico (attivo o inattivo), distinto dallo stato di accesso dell'account, gestito in
    <RouterLink to="/admin/utenti">Gestione utenti</RouterLink>.
  </p>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-admin>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-admin>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>
  <p v-if="attesa > 0" class="notice warning" role="status" data-attesa>
    Il server ha chiesto di attendere: nuove operazioni possibili tra {{ attesa }} secondi. Nessun tentativo viene ripetuto
    automaticamente e la scadenza non chiarisce l'esito di operazioni già inviate.
  </p>

  <section v-if="incerte.length" class="card" aria-labelledby="titolo-esiti-medici" data-da-verificare>
    <h2 id="titolo-esiti-medici">Esiti da verificare</h2>
    <div v-for="x in incerte" :key="x.chiave" class="notice warning" data-esito-incerto :data-chiave="x.chiave">
      <p><strong>{{ OPERAZIONI_MEDICO[x.tipo] }} dall'esito non determinato</strong>: {{ descriviIncertaMedico(x) }}.</p>
      <p v-if="x.tipo !== 'password-medico'">Il tentativo non è stato ripetuto; altre operazioni sulla stessa persona o email sono bloccate fino alla verifica.</p>
      <p v-else>Il tentativo non è stato ripetuto; le operazioni sullo stesso account restano bloccate finché non scegli esplicitamente come procedere.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica non è stato usato perché superato: ripeti la verifica.</p>
      <p v-else-if="x.verifica === 'non-conclusiva'">La verifica precedente non è stata conclusiva.</p>
      <p v-else-if="x.verifica === 'incoerente'">La verifica precedente ha trovato riscontri non coerenti tra loro: ripetila.</p>
      <p v-if="x.tipo === 'password-medico'">Una lettura non verifica la password salvata. <RouterLink :to="`/admin/medici/${x.idUtente}`">Apri il medico per gestire il reset incerto</RouterLink>.</p>
      <div v-else class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verifica(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica' }}
        </button>
      </div>
    </div>
  </section>

  <!-- ------------------------------------------------------------ nuovo medico -->
  <section v-if="modulo === 'crea'" id="modulo-medico" class="card" aria-labelledby="titolo-nuovo-medico">
    <div class="medici-intestazione"><h2 id="titolo-nuovo-medico">Nuovo medico</h2>
      <button type="button" class="button secondary" data-chiudi-modulo @click="chiudiModulo">Chiudi</button></div>
    <p class="small">Crea un account con profilo medico attivo. Il medico non accede automaticamente; la password non viene conservata dall'interfaccia.</p>
    <div v-if="azione?.tipo === 'crea'" class="confirm" role="group" aria-labelledby="titolo-conferma-crea-medico"
         :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="crea-medico">
      <h3 id="titolo-conferma-crea-medico" :ref="impostaTitolo" tabindex="-1">Confermi la creazione del medico?</h3>
      <dl class="facts">
        <div><dt>Nome</dt><dd>{{ azione.dati.nome }} {{ azione.dati.cognome }}</dd></div>
        <div><dt>Email</dt><dd data-campo="email">{{ azione.dati.email }}</dd></div>
        <div><dt>Specializzazione</dt><dd>{{ azione.dati.specializzazione }}</dd></div>
        <div><dt>Iscrizione all'ordine</dt><dd>{{ azione.dati.numeroIscrizioneOrdine }}</dd></div>
        <div><dt>Password</dt><dd>impostata nel modulo (non mostrata)</dd></div>
      </dl>
      <div class="actions">
        <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaCrea">
          {{ azione.fase === 'invio' ? 'Creazione in corso…' : 'Crea medico' }}
        </button>
        <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
      </div>
    </div>
    <form class="form" novalidate @submit.prevent="proseguiCrea">
      <div v-if="lista(erroriCrea).length" ref="riepilogoCrea" tabindex="-1" class="notice error" role="alert" data-errori-crea>
        <p>Controlla i dati indicati.</p>
        <ul><li v-for="e in lista(erroriCrea)" :key="e">{{ e }}</li></ul>
      </div>
      <div v-for="[campo, etichetta, tipo] in [['nome', 'Nome', 'text'], ['cognome', 'Cognome', 'text'], ['email', 'Email', 'email'], ['specializzazione', 'Specializzazione', 'text'], ['numeroIscrizioneOrdine', 'Numero di iscrizione all\'ordine', 'text']]"
           :key="campo" class="field">
        <label :for="`medico-${campo}`">{{ etichetta }}</label>
        <input :id="`medico-${campo}`" v-model="nuovo[campo]" :type="tipo" autocomplete="off"
               :aria-invalid="erroriCrea[campo] ? 'true' : undefined" :aria-describedby="descr(erroriCrea, campo, `errore-medico-${campo}`)" />
        <p v-if="erroriCrea[campo]" :id="`errore-medico-${campo}`" class="field-error">{{ erroriCrea[campo] }}</p>
      </div>
      <div class="field">
        <label for="medico-password">Password iniziale (da 12 a 1024 caratteri)</label>
        <input id="medico-password" v-model="password" type="password" autocomplete="new-password"
               :aria-invalid="erroriCrea.password ? 'true' : undefined" :aria-describedby="descr(erroriCrea, 'password', 'errore-medico-password')" />
        <p v-if="erroriCrea.password" id="errore-medico-password" class="field-error">{{ erroriCrea.password }}</p>
      </div>
      <div class="actions">
        <button type="submit" class="button" data-origine="crea-medico" :disabled="!puoScrivere || !libero">Prosegui</button>
      </div>
    </form>
  </section>

  <!-- ------------------------------------------------------------ collegamento -->
  <section v-if="modulo === 'collega'" id="modulo-collegamento" class="card" aria-labelledby="titolo-collega">
    <div class="medici-intestazione"><h2 id="titolo-collega">Collega il profilo medico a un utente esistente</h2>
      <button type="button" class="button secondary" data-chiudi-modulo @click="chiudiModulo">Chiudi</button></div>
    <p class="small">
      Cerca l'utente con la ricerca amministrativa. La ricerca non indica i profili paziente o medico: il server rifiuta il collegamento
      se il profilo medico esiste già, se l'utente ha un ruolo gestionale, se l'account non è attivo o se il numero di iscrizione è già usato.
      Un eventuale profilo paziente viene conservato.
    </p>
    <form class="form" novalidate @submit.prevent="cercaUtente">
      <div v-if="lista(erroriRicerca).length" ref="riepilogoRicerca" tabindex="-1" class="notice error" role="alert" data-errori-ricerca>
        <p>Controlla i filtri indicati.</p>
        <ul><li v-for="e in lista(erroriRicerca)" :key="e">{{ e }}</li></ul>
      </div>
      <div v-for="[campo, etichetta] in [['idUtente', 'ID utente'], ['email', 'Email (inizio)'], ['cognome', 'Cognome (inizio)'], ['codiceFiscale', 'Codice fiscale']]" :key="campo" class="field">
        <label :for="`collega-${campo}`">{{ etichetta }}</label>
        <input :id="`collega-${campo}`" v-model="filtriUtente[campo]" autocomplete="off"
               :aria-invalid="erroriRicerca[campo] ? 'true' : undefined" :aria-describedby="descr(erroriRicerca, campo, `errore-collega-${campo}`)" />
        <p v-if="erroriRicerca[campo]" :id="`errore-collega-${campo}`" class="field-error">{{ erroriRicerca[campo] }}</p>
      </div>
      <div class="actions"><button type="submit" class="button secondary">Cerca utente</button></div>
    </form>
    <p v-if="ricerca.stato === 'caricamento'" role="status">Ricerca in corso…</p>
    <p v-else-if="ricerca.stato === 'errore'" class="field-error" role="alert">{{ ricerca.errore }}</p>
    <template v-else-if="ricerca.stato === 'pronto'">
      <p class="muted" role="status" data-esito-ricerca-utenti>
        {{ ricerca.voci.length === 0 ? 'Nessun utente trovato.' : `${ricerca.voci.length} ${ricerca.voci.length === 1 ? 'utente mostrato' : 'utenti mostrati'}${ricerca.prossimo !== null ? ', altri disponibili' : ''}.` }}
      </p>
      <div v-if="ricercaInvalidata" class="notice warning" role="status" data-ricerca-utenti-invalidata>
        <p>I risultati potrebbero non essere più aggiornati: un'operazione conclusa dopo la ricerca riguarda gli utenti. Scelta e pagine successive sono sospese.</p>
        <div class="actions"><button type="button" class="button" @click="ripetiRicercaUtenti">Ripeti la ricerca</button></div>
      </div>
      <ul v-if="ricerca.voci.length" class="items" aria-label="Utenti trovati">
        <li v-for="u in ricerca.voci" :key="u.idUtente" class="card" data-utente-trovato :data-id="u.idUtente">
          <h3 class="item-title">{{ nome(u) }}</h3>
          <dl class="facts">
            <div><dt>Email</dt><dd>{{ u.email }}</dd></div>
            <div><dt>Codice fiscale</dt><dd>{{ u.codiceFiscale ?? 'Non presente' }}</dd></div>
            <div><dt>Ruolo gestionale</dt><dd>{{ u.ruolo ? RUOLO[u.ruolo] : 'Nessuno' }}</dd></div>
            <div><dt>Stato di accesso</dt><dd>{{ STATO_ACCESSO[u.statoAccesso] }}</dd></div>
            <div><dt>Utente</dt><dd>n. {{ u.idUtente }}</dd></div>
          </dl>
          <p class="small">Profili paziente e medico: non indicati dalla ricerca.</p>
          <p v-if="store.bloccata(chiaveUtente(u.idUtente))" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <div v-else class="actions">
            <button type="button" class="button secondary" :data-origine="`scegli-${u.idUtente}`" :disabled="!libero || !ricercaValida" @click="scegli(u)">
              Scegli<span class="visually-hidden"> {{ nome(u) }}</span>
            </button>
          </div>
        </li>
      </ul>
      <div v-if="ricerca.prossimo !== null" class="actions">
        <button type="button" class="button secondary" :disabled="ricerca.altri === 'caricamento' || !ricercaValida" @click="altriUtenti">
          {{ ricerca.altri === 'caricamento' ? 'Caricamento…' : 'Mostra altri utenti' }}
        </button>
      </div>
      <p v-if="ricerca.altri === 'errore'" class="field-error" role="alert">Pagina successiva non caricata. {{ ricerca.erroreAltri }}</p>
    </template>

    <div v-if="scelto" class="card" data-utente-scelto>
      <h3>Utente scelto: {{ nome(scelto.utente) }} (n. {{ scelto.utente.idUtente }})</h3>
      <p class="small">{{ scelto.utente.email }}{{ scelto.utente.ruolo ? `, ruolo gestionale ${RUOLO[scelto.utente.ruolo]}: il server rifiuterà il collegamento` : '' }}{{ scelto.utente.statoAccesso !== 'ATTIVO' ? ', account disabilitato: il server rifiuterà il collegamento' : '' }}.</p>
      <p v-if="sceltaSuperata" class="notice warning" role="status" data-scelta-superata>I dati dell'utente scelto potrebbero essere superati da operazioni più recenti: ripeti la ricerca.</p>
      <div v-if="azione?.tipo === 'collega'" class="confirm" role="group" aria-labelledby="titolo-conferma-collega"
           :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="collega">
        <h4 id="titolo-conferma-collega" :ref="impostaTitolo" tabindex="-1">Confermi il collegamento del profilo medico?</h4>
        <p>Utente n. {{ azione.utente.idUtente }}: <strong>{{ nome(azione.utente) }}</strong>, {{ azione.utente.email }}.</p>
        <p data-campo="professionali">Specializzazione «{{ azione.dati.specializzazione }}», iscrizione all'ordine «{{ azione.dati.numeroIscrizioneOrdine }}».</p>
        <p class="small">Il profilo medico nasce attivo; un eventuale profilo paziente viene conservato.</p>
        <div class="actions">
          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaCollega">
            {{ azione.fase === 'invio' ? 'Collegamento in corso…' : 'Collega profilo medico' }}
          </button>
          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
        </div>
      </div>
      <form v-else class="form" novalidate @submit.prevent="proseguiCollega">
        <div v-if="lista(erroriCollega).length" ref="riepilogoCollega" tabindex="-1" class="notice error" role="alert" data-errori-collega>
          <p>Controlla i dati indicati.</p>
          <ul><li v-for="e in lista(erroriCollega)" :key="e">{{ e }}</li></ul>
        </div>
        <div class="field">
          <label for="collega-specializzazione">Specializzazione</label>
          <input id="collega-specializzazione" v-model="professionali.specializzazione" autocomplete="off"
                 :aria-invalid="erroriCollega.specializzazione ? 'true' : undefined" :aria-describedby="descr(erroriCollega, 'specializzazione', 'errore-collega-spec')" />
          <p v-if="erroriCollega.specializzazione" id="errore-collega-spec" class="field-error">{{ erroriCollega.specializzazione }}</p>
        </div>
        <div class="field">
          <label for="collega-numero">Numero di iscrizione all'ordine</label>
          <input id="collega-numero" v-model="professionali.numeroIscrizioneOrdine" autocomplete="off"
                 :aria-invalid="erroriCollega.numeroIscrizioneOrdine ? 'true' : undefined" :aria-describedby="descr(erroriCollega, 'numeroIscrizioneOrdine', 'errore-collega-numero')" />
          <p v-if="erroriCollega.numeroIscrizioneOrdine" id="errore-collega-numero" class="field-error">{{ erroriCollega.numeroIscrizioneOrdine }}</p>
        </div>
        <div class="actions">
          <button type="submit" class="button" data-origine="collega" :disabled="!puoScrivere || !libero || sceltaSuperata">Prosegui</button>
          <button type="button" class="button secondary" @click="scelto = null">Annulla la scelta</button>
        </div>
      </form>
    </div>
  </section>  <!-- ------------------------------------------------------------ elenco -->
  <section class="card" aria-labelledby="titolo-elenco-medici" :aria-busy="elenco.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-elenco-medici" ref="titoloElenco" tabindex="-1">Elenco dei medici</h2>
    <form class="form" novalidate @submit.prevent="leggiElenco">
      <div class="field">
        <label for="medici-stato">Stato operativo</label>
        <select id="medici-stato" v-model="filtri.stato">
          <option value="">Tutti</option>
          <option v-for="s in STATI_MEDICO" :key="s" :value="s">{{ STATO_MEDICO[s] }}</option>
        </select>
      </div>
      <div class="field">
        <label for="medici-prestazione">Abilitati alla prestazione</label>
        <select id="medici-prestazione" v-model="filtri.idPrestazione" :disabled="catalogo.stato !== 'pronto'">
          <option value="">Qualsiasi</option>
          <option v-for="c in catalogo.voci" :key="c.idPrestazione" :value="String(c.idPrestazione)">{{ c.nome }}</option>
        </select>
        <p v-if="catalogo.stato === 'errore'" class="small">Catalogo non disponibile: filtro per prestazione sospeso.</p>
      </div>
      <div class="actions"><button type="submit" class="button">Filtra</button></div>
    </form>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento in corso…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare i medici. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="leggiElenco">Riprova</button></div>
    </div>
    <div v-else-if="elenco.stato === 'superata'" class="notice warning" role="status" data-elenco-superato>
      <p>L'elenco letto è stato superato da operazioni più recenti e non è stato usato.</p>
      <div class="actions"><button type="button" class="button" @click="leggiElenco">Aggiorna l'elenco</button></div>
    </div>
    <template v-else>
      <p v-if="!elenco.aggiornata && !elenco.superata" class="small" role="status">Aggiornamento dell'elenco in corso.</p>
      <div v-if="elenco.superata || elencoInvalidato" class="notice warning" role="status" data-elenco-invalidato>
        <p>L'elenco mostrato potrebbe non essere più aggiornato: un'operazione conclusa dopo la sua lettura riguarda i medici.</p>
        <div class="actions"><button type="button" class="button" @click="leggiElenco">Aggiorna l'elenco</button></div>
      </div>
      <p class="muted" role="status" data-esito-elenco>Filtri: {{ descrizioneFiltri }}. {{ elenco.voci.length === 1 ? '1 medico' : `${elenco.voci.length} medici` }}.</p>
      <ul v-if="elenco.voci.length" class="items" aria-label="Medici">
        <li v-for="m in elenco.voci" :key="m.idUtente" class="card" data-medico-admin :data-id="m.idUtente">
          <h3 class="item-title"><RouterLink :to="`/admin/medici/${m.idUtente}`">{{ nome(m) }}</RouterLink></h3>
          <dl class="facts medici-dati">
            <div class="medici-email"><dt>Email</dt><dd data-campo="email">{{ m.email }}</dd></div>
            <div><dt>Specializzazione</dt><dd>{{ m.specializzazione }}</dd></div>
            <div><dt>Iscrizione all'ordine</dt><dd>{{ m.numeroIscrizioneOrdine }}</dd></div>
            <div><dt>Stato operativo</dt><dd data-campo="stato">{{ statoMedico(m) }}</dd></div>
            <div><dt>Profilo paziente</dt><dd data-campo="paziente">{{ m.paziente ? 'Sì' : 'No' }}</dd></div>
            <div v-if="m.ruolo"><dt>Ruolo gestionale</dt><dd data-campo="ruolo">{{ ruoloGestionale(m.ruolo) }}</dd></div>
          </dl>
        </li>
      </ul>
      <p v-else>Nessun medico per questi filtri.</p>
    </template>
  </section>


</template>

<style scoped>
.medici-intestazione { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
.medici-intestazione h1, .medici-intestazione h2 { margin: 0; }
.medici-intestazione .actions { margin: 0; }
.medici-intestazione .button { max-width: 100%; white-space: normal; }
[data-medico-admin] { container-type: inline-size; }
.medici-dati { grid-template-columns: repeat(7, minmax(0, 1fr)); gap: .75rem 1.25rem; }
.medici-dati > div { min-width: 0; }
.medici-dati dd { overflow-wrap: anywhere; }
.medici-email { grid-column: span 2; }
@container (max-width: 60rem) { .medici-dati { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@container (max-width: 38rem) { .medici-dati { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@container (max-width: 24rem) {
  .medici-dati { grid-template-columns: minmax(0, 1fr); }
  .medici-email { grid-column: auto; }
}
</style>
