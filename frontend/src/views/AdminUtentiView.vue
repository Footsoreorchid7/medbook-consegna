<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { HttpError, NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { LIMITE_PAGINA, RUOLI, RUOLI_RICERCA, STATI_ACCESSO, fetchGestionali, fetchRicerca, validaFiltri, validaNuovoAccount } from '../api/utenti.js'
import {
  OPERAZIONI, RUOLO, STATO_ACCESSO, TESTO_ERRORE_VERIFICA, TESTO_INCOERENTE, TESTO_NON_CONCLUSIVA, TESTO_SUPERATA, altroRuolo, altroStato,
  descriviIncerta, nome, ruolo, testoEsito, testoVerifica,
} from '../admin/formato.js'
import { ELENCO, RICERCA, TIPI_MEDICO, TIPI_UTENTE, chiaveEmail, chiaveUtente, useAdmin } from '../admin/store.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'

/**
 * Gestione amministrativa degli utenti .
 * - «Account gestionali»: GET /api/utenti, tutti gli account con ruolo gestionale.
 * - «Ricerca utenti»: GET /api/utenti/ricerca, ricerca amministrativa con almeno un filtro
 *   identificativo e paginazione keyset; non è un elenco generale.
 * - «Nuovo account gestionale»: POST /api/utenti con conferma; la password resta solo nel campo
 *   del modulo e viene svuotata a ogni tentativo.
 * Le due fonti hanno proiezioni diverse: i profili paziente/medico esistono solo nell'elenco
 * gestionale e non vengono mai dedotti. Dati visibili e dati idonei alle azioni sono distinti:
 * un esito certo successivo alla lettura (anche da un'altra istanza) sospende le azioni.
 */
const store = useAdmin()
let contesto = {}
const { session, idUtente: idAdmin, smontato } = useLetturaProfilo('admin', { azzera: nuovoContesto, ricarica: () => leggiGestionali() })
const corrente = (ctx) => ctx === contesto && !smontato()

const avviso = ref(null)
const avvisoBox = ref(null)
const titoloElenco = ref(null)
const titoloRicerca = ref(null)
const titoloCrea = ref(null)
const titoloConferma = ref(null)
const impostaTitolo = (el) => { titoloConferma.value = el }
const riepilogoRicerca = ref(null)
const riepilogoCrea = ref(null)

function messaggioLettura(error) {
  if (error instanceof HttpError && error.status === 403) return 'Accesso non autorizzato: il tuo account potrebbe non essere più un amministratore attivo.'
  if (error instanceof HttpError && error.status === 400) return 'Filtri non accettati dal server: controlla i valori indicati.'
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

// ---------------------------------------------------------------- attesa chiesta dal server (Retry-After)

const ora = ref(Date.now())
let timer = null
const attesa = computed(() => store.attesaResidua(ora.value))
watch(() => store.state.attesaFino, (fino) => {
  clearInterval(timer)
  timer = null
  ora.value = Date.now()
  if (fino === null) return
  timer = setInterval(() => {
    ora.value = Date.now()
    if (store.attesaResidua(ora.value) === 0) { clearInterval(timer); timer = null }
  }, 1000)
}, { immediate: true })
onBeforeUnmount(() => clearInterval(timer))

// ---------------------------------------------------------------- elenco degli account gestionali

const elenco = reactive({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false })
const elencoTicket = shallowRef(null)
let seqElenco = 0

async function leggiGestionali() {
  if (idAdmin() === null) return
  const ctx = contesto
  const mia = ++seqElenco
  const ticket = store.inizioLettura()
  elenco.aggiornata = false
  elenco.superata = false
  elenco.errore = ''
  if (elenco.stato !== 'pronto') elenco.stato = 'caricamento'
  try {
    const voci = await fetchGestionali(session.protectedRequest)
    if (mia !== seqElenco || !corrente(ctx)) return
    // Una lettura superata da un esito certo successivo al suo avvio non diventa l'elenco su cui agire.
    if (!store.valida(ELENCO, ticket)) {
      elenco.superata = true
      if (elenco.stato !== 'pronto') elenco.stato = 'superata'
      return
    }
    elenco.voci = voci
    elencoTicket.value = ticket
    store.registraElenco(voci, ticket)
    elenco.stato = 'pronto'
    elenco.aggiornata = true
  } catch (error) {
    if (mia !== seqElenco || !corrente(ctx) || error instanceof StaleResponseError) return
    elenco.errore = messaggioLettura(error)
    elenco.stato = 'errore'
  }
}
const elencoValido = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && store.valida(ELENCO, elencoTicket.value))
const elencoInvalidato = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && !store.valida(ELENCO, elencoTicket.value))
const elencoInAggiornamento = computed(() => elenco.stato === 'pronto' && !elenco.aggiornata && !elenco.superata)

// ---------------------------------------------------------------- ricerca amministrativa

const VUOTI = () => ({ idUtente: '', email: '', cognome: '', codiceFiscale: '', ruolo: '', statoAccesso: '' })
const filtri = reactive(VUOTI())
const erroriFiltri = ref({})
const RICERCA_VUOTA = () => ({ stato: 'nessuna', filtri: null, voci: [], prossimo: null, ticket: null, errore: '', aggiornata: false, superata: false, altri: '', erroreAltri: '' })
const ricerca = reactive(RICERCA_VUOTA())
/** Contesto della ricerca: nuovo a ogni ricerca, così le pagine di ricerche diverse non si mescolano. */
let ricercaCtx = {}

async function cerca() {
  const { filtri: validi, errori } = validaFiltri(filtri)
  erroriFiltri.value = errori
  if (Object.keys(errori).length) {
    await nextTick()
    riepilogoRicerca.value?.focus()
    return
  }
  avviso.value = null
  await eseguiRicerca(validi)
}

/** Ricerca dalla prima pagina: un cambio di filtri azzera sempre il cursore. */
async function eseguiRicerca(validi) {
  if (idAdmin() === null) return
  const ctx = contesto
  const rc = {}
  ricercaCtx = rc
  const precedenti = ricerca.stato === 'pronto' && ricerca.filtri === validi
  Object.assign(ricerca, { filtri: validi, errore: '', aggiornata: false, superata: false, altri: '', erroreAltri: '' })
  if (!precedenti) Object.assign(ricerca, { stato: 'caricamento', voci: [], prossimo: null, ticket: null })
  const ticket = store.inizioLettura()
  try {
    const pagina = await fetchRicerca(session.protectedRequest, validi, 0, LIMITE_PAGINA)
    if (rc !== ricercaCtx || !corrente(ctx)) return
    if (!store.valida(RICERCA, ticket)) {
      ricerca.superata = true
      if (ricerca.stato !== 'pronto') ricerca.stato = 'superata'
      return
    }
    Object.assign(ricerca, { stato: 'pronto', voci: pagina.risultati, prossimo: pagina.prossimoDopoId, ticket, aggiornata: true })
    store.registraRicerca(validi, 0, pagina, ticket)
  } catch (error) {
    if (rc !== ricercaCtx || !corrente(ctx) || error instanceof StaleResponseError) return
    Object.assign(ricerca, { stato: 'errore', voci: [], prossimo: null, ticket: null, errore: messaggioLettura(error) })
  }
}

/** Pagina successiva della stessa ricerca, dal cursore restituito dal server. */
async function caricaAltri() {
  if (ricerca.stato !== 'pronto' || ricerca.prossimo === null || ricerca.altri === 'caricamento' || !ricercaValida.value) return
  const ctx = contesto
  const rc = ricercaCtx
  const dopo = ricerca.prossimo
  const ticket = store.inizioLettura()
  ricerca.altri = 'caricamento'
  ricerca.erroreAltri = ''
  try {
    const pagina = await fetchRicerca(session.protectedRequest, ricerca.filtri, dopo, LIMITE_PAGINA)
    if (rc !== ricercaCtx || !corrente(ctx) || ricerca.prossimo !== dopo) return
    ricerca.altri = ''
    // Pagine precedenti e successive devono appartenere allo stesso stato: dopo un esito certo si ripete la ricerca.
    if (!store.valida(RICERCA, ticket) || !store.valida(RICERCA, ricerca.ticket)) return
    ricerca.voci = [...ricerca.voci, ...pagina.risultati]
    ricerca.prossimo = pagina.prossimoDopoId
    store.registraRicerca(ricerca.filtri, dopo, pagina, ticket)
  } catch (error) {
    if (rc !== ricercaCtx || !corrente(ctx) || error instanceof StaleResponseError) return
    ricerca.altri = 'errore'
    ricerca.erroreAltri = messaggioLettura(error)
  }
}

const ricercaValida = computed(() => ricerca.stato === 'pronto' && ricerca.aggiornata && store.valida(RICERCA, ricerca.ticket))
const ricercaInvalidata = computed(() => ricerca.stato === 'pronto' && ricerca.aggiornata && !store.valida(RICERCA, ricerca.ticket))
const ripetiRicerca = () => { if (ricerca.filtri) eseguiRicerca(ricerca.filtri) }
function azzeraFiltri() {
  Object.assign(filtri, VUOTI())
  erroriFiltri.value = {}
}
const descrizioneFiltri = computed(() => {
  const f = ricerca.filtri
  if (!f) return ''
  const parti = []
  if (f.idUtente) parti.push(`ID ${f.idUtente}`)
  if (f.email) parti.push(`email che inizia con «${f.email}»`)
  if (f.cognome) parti.push(`cognome che inizia con «${f.cognome}»`)
  if (f.codiceFiscale) parti.push(`codice fiscale ${f.codiceFiscale}`)
  if (f.ruolo) parti.push(f.ruolo === 'NESSUNO' ? 'senza ruolo gestionale' : `ruolo ${RUOLO[f.ruolo]}`)
  if (f.statoAccesso) parti.push(`stato ${STATO_ACCESSO[f.statoAccesso]}`)
  return parti.join(', ')
})

// ---------------------------------------------------------------- azioni e conferme

/** Conferma aperta: { tipo: 'ruolo'|'stato'|'crea', lista, id, voce, nuovo, dati, fase: 'conferma'|'invio' }. */
const azione = ref(null)
let origine = null
const puoScrivere = computed(() => idAdmin() !== null && !session.state.passwordUncertain && !session.state.busy && attesa.value === 0)
const valida = (lista) => (lista === ELENCO ? elencoValido.value : ricercaValida.value)
const puoAgireSu = (lista) => puoScrivere.value && valida(lista)
const libero = computed(() => azione.value === null)
const io = (u) => u.idUtente === idAdmin()

// Una conferma aperta su dati non più idonei (rilettura, esito certo altrove, attesa, sessione) si chiude subito.
watch(() => {
  const a = azione.value
  if (!a || a.fase === 'invio') return false
  return a.lista ? !puoAgireSu(a.lista) : !puoScrivere.value
}, (chiudere) => {
  if (!chiudere) return
  const lista = azione.value?.lista
  // Il focus dentro la conferma che si chiude va al titolo dell'elenco; altrove non si sposta.
  const dentro = (e) => e === document.body || !e?.isConnected || e.closest?.('[data-conferma]') !== null
  const daSpostare = dentro(document.activeElement)
  azione.value = null
  origine = null
  nextTick(() => {
    if (smontato() || !daSpostare || !dentro(document.activeElement)) return
    ;(lista === RICERCA ? titoloRicerca : lista === ELENCO ? titoloElenco : titoloCrea).value?.focus()
  })
}, { flush: 'sync' })

async function apri(nuova, chiaveOrigine) {
  if (!libero.value || (nuova.lista ? !puoAgireSu(nuova.lista) : !puoScrivere.value)) return
  if (nuova.id !== undefined && store.bloccata(chiaveUtente(nuova.id))) return
  origine = chiaveOrigine
  avviso.value = null
  azione.value = { ...nuova, fase: 'conferma' }
  await nextTick()
  if (!smontato()) titoloConferma.value?.focus()
}

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

const vociDi = (lista) => (lista === ELENCO ? elenco.voci : ricerca.voci)
const operabile = (u) => !store.bloccata(chiaveUtente(u.idUtente))

const apriRuolo = (lista, u) => apri({ tipo: 'ruolo', lista, id: u.idUtente, voce: u, nuovo: altroRuolo(u.ruolo) }, `ruolo-${lista}-${u.idUtente}`)
const apriStato = (lista, u) => apri({ tipo: 'stato', lista, id: u.idUtente, voce: u, nuovo: altroStato(u.statoAccesso) }, `stato-${lista}-${u.idUtente}`)

/** Rilettura delle fonti dopo un esito certo: elenco gestionale e ricerca corrente (dalla prima pagina). */
function rileggiTutto() {
  leggiGestionali()
  ripetiRicerca()
}

async function confermaUtente() {
  const a = azione.value
  if (!a || a.fase !== 'conferma' || (a.tipo !== 'ruolo' && a.tipo !== 'stato') || !puoAgireSu(a.lista)) return
  const u = vociDi(a.lista).find((v) => v.idUtente === a.id) ?? null
  if (!u || !operabile(u)) return
  const ctx = contesto
  // La conferma vale per la versione letta: ruolo e stato devono essere quelli mostrati all'apertura.
  if (u.ruolo !== a.voce.ruolo || u.statoAccesso !== a.voce.statoAccesso) {
    chiudi()
    await mostra(ctx, 'errore', "I dati dell'utente sono cambiati: controlla la riga e riapri la conferma.")
    return
  }
  azione.value = { ...a, fase: 'invio' }
  const esito = a.tipo === 'ruolo' ? await store.cambiaRuolo(u, a.nuovo) : await store.cambiaStato(u, a.nuovo)
  if (!corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata'].includes(esito.kind)) rileggiTutto()
  chiudi()
  if (esito.kind === 'eseguita') {
    const r = esito.risultato
    await mostra(ctx, 'successo', a.tipo === 'ruolo'
      ? `Il server conferma per l'utente n. ${r.idUtente} (${nome(r)}) il ruolo «${RUOLO[r.ruolo]}». Gli elenchi vengono riletti.`
      : `Il server conferma per l'utente n. ${r.idUtente} (${nome(r)}) lo stato di accesso «${STATO_ACCESSO[r.statoAccesso]}». `
        + "Lo stato operativo di un eventuale profilo medico non cambia. Gli elenchi vengono riletti.")
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    await mostra(ctx, 'errore', testoEsito(esito))
  }
}

function chiudi() {
  azione.value = null
  origine = null
}

// ---------------------------------------------------------------- nuovo account gestionale

const moduloAperto = ref(false)
const pulsanteNuovo = ref(null)
const primoCampo = ref(null)
let sequenzaModulo = 0
async function apriModulo() {
  if (moduloAperto.value) return
  const ctx = contesto
  const mia = ++sequenzaModulo
  moduloAperto.value = true
  await nextTick()
  if (corrente(ctx) && moduloAperto.value && mia === sequenzaModulo) (primoCampo.value ?? titoloCrea.value)?.focus()
}
async function chiudiModulo() {
  const ctx = contesto
  const mia = ++sequenzaModulo
  password.value = ''
  erroriCrea.value = {}
  moduloAperto.value = false
  // Una POST già inviata appartiene allo store: nascondere il modulo non la annulla.
  if (azione.value?.tipo === 'crea' && azione.value.fase !== 'invio') chiudi()
  await nextTick()
  if (corrente(ctx) && !moduloAperto.value && mia === sequenzaModulo) pulsanteNuovo.value?.focus()
}
onBeforeUnmount(() => { password.value = ''; sequenzaModulo += 1 })

const VUOTO_CREA = () => ({ nome: '', cognome: '', email: '', ruolo: '' })
const nuovo = reactive(VUOTO_CREA())
/** Password del nuovo account: solo in questo campo, mai nello store, nei riepiloghi o nei messaggi. */
const password = ref('')
const erroriCrea = ref({})

async function proseguiCrea() {
  if (!moduloAperto.value || !libero.value || !puoScrivere.value) return
  const { dati, errori } = validaNuovoAccount({ ...nuovo, password: password.value })
  erroriCrea.value = errori
  if (!dati) {
    await nextTick()
    riepilogoCrea.value?.focus()
    return
  }
  if (store.bloccata(chiaveEmail(dati.email))) {
    erroriCrea.value = { email: "Un tentativo precedente per questa email è ancora da verificare: verificalo prima di riprovare." }
    await nextTick()
    riepilogoCrea.value?.focus()
    return
  }
  await apri({ tipo: 'crea', lista: null, dati }, 'crea')
}

async function confermaCrea() {
  const a = azione.value
  if (!moduloAperto.value || !a || a.tipo !== 'crea' || a.fase !== 'conferma' || !puoScrivere.value) return
  const ctx = contesto
  // La password lascia il modulo a ogni tentativo, qualunque sia l'esito.
  const segreto = password.value
  password.value = ''
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.crea(a.dati, segreto)
  if (!corrente(ctx)) return
  if (['eseguita', 'conflitto'].includes(esito.kind)) rileggiTutto()
  chiudi()
  if (esito.kind === 'eseguita') {
    const r = esito.risultato
    Object.assign(nuovo, VUOTO_CREA())
    erroriCrea.value = {}
    await mostra(ctx, 'successo', `Account n. ${r.idUtente} creato: ${r.email}, ${RUOLO[r.ruolo]}, stato «Attivo». `
      + "Nessun accesso automatico: comunica la password all'interessato con un canale sicuro, l'interfaccia non la conserva.")
  } else if (esito.kind === 'non-valida') {
    await mostra(ctx, 'errore', 'Dati non accettati dal server: controlla nome, cognome, email, ruolo e password (da reinserire).')
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    await mostra(ctx, 'errore', `${testoEsito(esito)} La password è stata cancellata dal modulo.`)
  }
}

// ---------------------------------------------------------------- esiti da verificare

// Solo gli esiti di questa pagina; quelli dei medici  si verificano nella gestione medici.
const incerte = computed(() => store.state.incerte.filter((x) => TIPI_UTENTE.includes(x.tipo)))
const incerteMedici = computed(() => store.state.incerte.some((x) => TIPI_MEDICO.includes(x.tipo)))
async function verifica(x) {
  const ctx = contesto
  avviso.value = null
  const lettura = await store.leggiVerifica(x.chiave)
  if (!corrente(ctx)) return
  // L'esito si costruisce ora, sul riscontro corrente: una lettura applicata non è per questo ancora attuale.
  const esito = lettura.kind === 'letta' ? store.esitoVerifica(x.chiave, lettura) : lettura
  if (esito.kind === 'verificata') {
    rileggiTutto()
    await mostra(ctx, 'info', testoVerifica(esito.incerta, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'non-conclusiva') await mostra(ctx, 'info', TESTO_NON_CONCLUSIVA)
  else if (esito.kind === 'incoerente') await mostra(ctx, 'info', TESTO_INCOERENTE)
  else if (esito.kind === 'errore') await mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}

// ---------------------------------------------------------------- contesto

/** Cambio di epoca o identità: nessun dato, conferma o password della sessione precedente. */
function nuovoContesto() {
  contesto = {}
  moduloAperto.value = false
  sequenzaModulo += 1
  ricercaCtx = {}
  seqElenco += 1
  Object.assign(elenco, { stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false })
  elencoTicket.value = null
  Object.assign(ricerca, RICERCA_VUOTA())
  Object.assign(filtri, VUOTI())
  erroriFiltri.value = {}
  Object.assign(nuovo, VUOTO_CREA())
  password.value = ''
  erroriCrea.value = {}
  azione.value = null
  origine = null
  avviso.value = null
}

onMounted(leggiGestionali)

const erroreFiltriElenco = computed(() => Object.values(erroriFiltri.value))
const erroreCreaElenco = computed(() => Object.values(erroriCrea.value))
const descrittore = (errori, campo, id) => (errori[campo] ? id : undefined)
</script>

<template>
  <div class="admin-utenti">
  <div class="utenti-intestazione">
    <h1>Gestione utenti</h1>
    <button ref="pulsanteNuovo" type="button" class="button" data-apri-account
            :aria-expanded="moduloAperto" aria-controls="modulo-nuovo-account" @click="apriModulo">Nuovo account gestionale</button>
  </div>
  <p class="muted">
    Account gestionali, ricerca amministrativa degli utenti e creazione di account per amministratori e operatori desk.
    Le regole su ruoli e stati (ultimo amministratore, auto-modifica, profili medici) sono applicate dal server.
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

  <p v-if="incerteMedici" class="notice warning" role="status" data-incerte-medici>
    Alcune operazioni sui medici sono da verificare in <RouterLink to="/admin/medici">Medici e abilitazioni</RouterLink>: gli account interessati restano bloccati fino alla verifica.
  </p>
  <p v-if="!moduloAperto && azione?.tipo === 'crea' && azione.fase === 'invio'" role="status" class="notice" data-creazione-pendente>
    Creazione dell'account in corso. La chiusura del modulo non interrompe la richiesta; non ripetere l'invio.
  </p>
  <!-- ------------------------------------------------------------ nuovo account gestionale -->
  <section v-if="moduloAperto" id="modulo-nuovo-account" class="card" aria-labelledby="titolo-nuovo-account">
    <div class="utenti-intestazione"><h2 id="titolo-nuovo-account" ref="titoloCrea" tabindex="-1">Nuovo account gestionale</h2>
      <button type="button" class="button secondary" data-chiudi-account @click="chiudiModulo">Chiudi</button></div>
    <p class="small">
      Crea un account di amministratore o operatore desk. Il nuovo utente non accede automaticamente; la password non viene
      conservata dall'interfaccia e va comunicata con un canale sicuro.
    </p>
    <div v-if="azione?.tipo === 'crea'" class="confirm" role="group" aria-labelledby="titolo-conferma-crea"
         :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="crea">
      <h3 id="titolo-conferma-crea" :ref="impostaTitolo" tabindex="-1">Confermi la creazione dell'account?</h3>
      <dl class="facts">
        <div><dt>Nome</dt><dd data-campo="nome">{{ azione.dati.nome }} {{ azione.dati.cognome }}</dd></div>
        <div><dt>Email</dt><dd data-campo="email">{{ azione.dati.email }}</dd></div>
        <div><dt>Ruolo</dt><dd data-campo="ruolo">{{ RUOLO[azione.dati.ruolo] }}</dd></div>
        <div><dt>Password</dt><dd>impostata nel modulo (non mostrata)</dd></div>
      </dl>
      <div class="actions">
        <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaCrea">
          {{ azione.fase === 'invio' ? 'Creazione in corso…' : 'Crea account' }}
        </button>
        <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
      </div>
    </div>
    <form v-else class="form" novalidate @submit.prevent="proseguiCrea">
      <div v-if="erroreCreaElenco.length" ref="riepilogoCrea" tabindex="-1" class="notice error" role="alert" data-errori-crea>
        <p>Controlla i dati indicati.</p>
        <ul><li v-for="e in erroreCreaElenco" :key="e">{{ e }}</li></ul>
      </div>
      <div class="field">
        <label for="nuovo-nome">Nome</label>
        <input id="nuovo-nome" ref="primoCampo" v-model="nuovo.nome" autocomplete="off" :aria-invalid="erroriCrea.nome ? 'true' : undefined"
               :aria-describedby="descrittore(erroriCrea, 'nome', 'errore-nuovo-nome')" />
        <p v-if="erroriCrea.nome" id="errore-nuovo-nome" class="field-error">{{ erroriCrea.nome }}</p>
      </div>
      <div class="field">
        <label for="nuovo-cognome">Cognome</label>
        <input id="nuovo-cognome" v-model="nuovo.cognome" autocomplete="off" :aria-invalid="erroriCrea.cognome ? 'true' : undefined"
               :aria-describedby="descrittore(erroriCrea, 'cognome', 'errore-nuovo-cognome')" />
        <p v-if="erroriCrea.cognome" id="errore-nuovo-cognome" class="field-error">{{ erroriCrea.cognome }}</p>
      </div>
      <div class="field">
        <label for="nuovo-email">Email</label>
        <input id="nuovo-email" v-model="nuovo.email" type="email" autocomplete="off" spellcheck="false"
               :aria-invalid="erroriCrea.email ? 'true' : undefined" :aria-describedby="descrittore(erroriCrea, 'email', 'errore-nuovo-email')" />
        <p v-if="erroriCrea.email" id="errore-nuovo-email" class="field-error">{{ erroriCrea.email }}</p>
      </div>
      <div class="field">
        <label for="nuovo-password">Password iniziale (da 12 a 1024 caratteri)</label>
        <input id="nuovo-password" v-model="password" type="password" autocomplete="new-password"
               :aria-invalid="erroriCrea.password ? 'true' : undefined" :aria-describedby="descrittore(erroriCrea, 'password', 'errore-nuovo-password')" />
        <p v-if="erroriCrea.password" id="errore-nuovo-password" class="field-error">{{ erroriCrea.password }}</p>
      </div>
      <fieldset class="field utenti-ruoli" :aria-invalid="erroriCrea.ruolo ? 'true' : undefined" :aria-describedby="descrittore(erroriCrea, 'ruolo', 'errore-nuovo-ruolo')">
        <legend>Ruolo</legend>
        <label v-for="r in RUOLI" :key="r"><input v-model="nuovo.ruolo" type="radio" name="nuovo-ruolo" :value="r" /> {{ RUOLO[r] }}</label>
        <p v-if="erroriCrea.ruolo" id="errore-nuovo-ruolo" class="field-error">{{ erroriCrea.ruolo }}</p>
      </fieldset>
      <div class="actions">
        <button type="submit" class="button" :data-origine="'crea'" :disabled="!puoScrivere || !libero">Prosegui</button>
      </div>
    </form>
  </section>
  <!-- ------------------------------------------------------------ ricerca amministrativa -->
  <section class="card" aria-labelledby="titolo-ricerca-utenti" :aria-busy="ricerca.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-ricerca-utenti" ref="titoloRicerca" tabindex="-1">Ricerca utenti</h2>
    <p class="small">
      Ricerca amministrativa di qualsiasi account, anche paziente. Serve almeno un filtro tra ID, email, cognome e codice fiscale;
      email e cognome cercano per inizio. I risultati arrivano a pagine di {{ LIMITE_PAGINA }}, senza totale.
    </p>
    <form class="form utenti-filtri" novalidate @submit.prevent="cerca">
      <div v-if="erroreFiltriElenco.length" ref="riepilogoRicerca" tabindex="-1" class="notice error" role="alert" data-errori-ricerca>
        <p>Controlla i filtri indicati.</p>
        <ul><li v-for="e in erroreFiltriElenco" :key="e">{{ e }}</li></ul>
      </div>
      <div class="field">
        <label for="ricerca-id">ID utente</label>
        <input id="ricerca-id" v-model="filtri.idUtente" inputmode="numeric" autocomplete="off"
               :aria-invalid="erroriFiltri.idUtente ? 'true' : undefined" :aria-describedby="descrittore(erroriFiltri, 'idUtente', 'errore-ricerca-id')" />
        <p v-if="erroriFiltri.idUtente" id="errore-ricerca-id" class="field-error">{{ erroriFiltri.idUtente }}</p>
      </div>
      <div class="field">
        <label for="ricerca-email">Email (inizio, almeno 3 caratteri)</label>
        <input id="ricerca-email" v-model="filtri.email" type="text" autocomplete="off" spellcheck="false"
               :aria-invalid="erroriFiltri.email ? 'true' : undefined" :aria-describedby="descrittore(erroriFiltri, 'email', 'errore-ricerca-email')" />
        <p v-if="erroriFiltri.email" id="errore-ricerca-email" class="field-error">{{ erroriFiltri.email }}</p>
      </div>
      <div class="field">
        <label for="ricerca-cognome">Cognome (inizio, almeno 2 caratteri)</label>
        <input id="ricerca-cognome" v-model="filtri.cognome" type="text" autocomplete="off"
               :aria-invalid="erroriFiltri.cognome ? 'true' : undefined" :aria-describedby="descrittore(erroriFiltri, 'cognome', 'errore-ricerca-cognome')" />
        <p v-if="erroriFiltri.cognome" id="errore-ricerca-cognome" class="field-error">{{ erroriFiltri.cognome }}</p>
      </div>
      <div class="field">
        <label for="ricerca-cf">Codice fiscale</label>
        <input id="ricerca-cf" v-model="filtri.codiceFiscale" type="text" autocomplete="off" spellcheck="false"
               :aria-invalid="erroriFiltri.codiceFiscale ? 'true' : undefined" :aria-describedby="descrittore(erroriFiltri, 'codiceFiscale', 'errore-ricerca-cf')" />
        <p v-if="erroriFiltri.codiceFiscale" id="errore-ricerca-cf" class="field-error">{{ erroriFiltri.codiceFiscale }}</p>
      </div>
      <div class="field">
        <label for="ricerca-ruolo">Ruolo (facoltativo)</label>
        <select id="ricerca-ruolo" v-model="filtri.ruolo">
          <option value="">Qualsiasi</option>
          <option v-for="r in RUOLI_RICERCA" :key="r" :value="r">{{ r === 'NESSUNO' ? 'Nessun ruolo gestionale' : RUOLO[r] }}</option>
        </select>
      </div>
      <div class="field">
        <label for="ricerca-stato">Stato di accesso (facoltativo)</label>
        <select id="ricerca-stato" v-model="filtri.statoAccesso">
          <option value="">Qualsiasi</option>
          <option v-for="s in STATI_ACCESSO" :key="s" :value="s">{{ STATO_ACCESSO[s] }}</option>
        </select>
      </div>
      <div class="actions">
        <button type="submit" class="button">Cerca</button>
        <button type="button" class="button secondary" @click="azzeraFiltri">Svuota i filtri</button>
      </div>
    </form>

    <p v-if="ricerca.stato === 'caricamento'" role="status">Ricerca in corso…</p>
    <div v-else-if="ricerca.stato === 'errore'" class="notice error" role="alert" data-errore-ricerca>
      <p>Ricerca non riuscita. {{ ricerca.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="ripetiRicerca">Riprova</button></div>
    </div>
    <div v-else-if="ricerca.stato === 'superata'" class="notice warning" role="status" data-ricerca-superata>
      <p>Il risultato è stato superato da operazioni più recenti e non è stato usato.</p>
      <div class="actions"><button type="button" class="button" @click="ripetiRicerca">Ripeti la ricerca</button></div>
    </div>
    <template v-else-if="ricerca.stato === 'pronto'">
      <p v-if="!ricerca.aggiornata && !ricerca.superata" class="small" role="status">Aggiornamento della ricerca in corso: le azioni sono sospese.</p>
      <div v-if="ricerca.superata" class="notice warning" role="status" data-ricerca-superata>
        <p>I risultati mostrati non sono aggiornati: l'ultima ricerca è stata superata e non è stata usata. Le azioni sono sospese.</p>
        <div class="actions"><button type="button" class="button" @click="ripetiRicerca">Ripeti la ricerca</button></div>
      </div>
      <div v-if="ricercaInvalidata" class="notice warning" role="status" data-ricerca-invalidata>
        <p>I risultati mostrati potrebbero non essere più aggiornati: un'operazione conclusa dopo la ricerca riguarda gli utenti. Le azioni e le pagine successive sono sospese finché non ripeti la ricerca.</p>
        <div class="actions"><button type="button" class="button" @click="ripetiRicerca">Ripeti la ricerca</button></div>
      </div>
      <p class="muted" role="status" data-esito-ricerca>
        Filtri applicati: {{ descrizioneFiltri }}.
        {{ ricerca.voci.length === 0 ? 'Nessun utente trovato.' : `${ricerca.voci.length} ${ricerca.voci.length === 1 ? 'risultato mostrato' : 'risultati mostrati'}${ricerca.prossimo !== null ? ', altri disponibili' : ''}.` }}
      </p>
      <ul v-if="ricerca.voci.length" class="items" aria-label="Risultati della ricerca">
        <li v-for="u in ricerca.voci" :key="u.idUtente" class="card" data-risultato :data-id="u.idUtente">
          <h3 class="item-title">{{ nome(u) }}<span v-if="io(u)" class="muted"> (il tuo account)</span></h3>
          <dl class="facts utenti-dati">
            <div class="utenti-email"><dt>Email</dt><dd data-campo="email">{{ u.email }}</dd></div>
            <div><dt>Codice fiscale</dt><dd data-campo="cf">{{ u.codiceFiscale ?? 'Non presente' }}</dd></div>
            <div><dt>Ruolo</dt><dd data-campo="ruolo">{{ ruolo(u.ruolo) }}</dd></div>
            <div><dt>Stato di accesso</dt><dd data-campo="stato">{{ STATO_ACCESSO[u.statoAccesso] }}</dd></div>
            <div><dt>Utente</dt><dd>n. {{ u.idUtente }}</dd></div>
          </dl>
          <p class="small" data-campo="profili">Profili paziente e medico: non indicati dalla ricerca.</p>
          <p v-if="!operabile(u)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <div v-else-if="azione?.lista === 'ricerca' && azione.id === u.idUtente" class="confirm" role="group"
               :aria-labelledby="`titolo-conferma-ricerca-${u.idUtente}`" :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
            <h4 :id="`titolo-conferma-ricerca-${u.idUtente}`" :ref="impostaTitolo" tabindex="-1">
              {{ azione.tipo === 'ruolo' ? 'Confermi il cambio di ruolo?' : "Confermi il cambio dello stato di accesso?" }}
            </h4>
            <p>Utente n. {{ u.idUtente }}: <strong>{{ nome(u) }}</strong>, {{ u.email }}.</p>
            <p v-if="azione.tipo === 'ruolo'" data-campo="transizione">Ruolo: da «{{ RUOLO[u.ruolo] }}» a «{{ RUOLO[azione.nuovo] }}».</p>
            <p v-else data-campo="transizione">Stato di accesso: da «{{ STATO_ACCESSO[u.statoAccesso] }}» a «{{ STATO_ACCESSO[azione.nuovo] }}».</p>
            <p class="small">Il server può rifiutare l'operazione (ad esempio per l'ultimo amministratore attivo, per il tuo stesso account o per un profilo medico attivo).</p>
            <div class="actions">
              <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgireSu('ricerca')" @click="confermaUtente">
                {{ azione.fase === 'invio' ? 'Operazione in corso…' : azione.tipo === 'ruolo' ? 'Cambia ruolo' : azione.nuovo === 'DISABILITATO' ? 'Disabilita accesso' : 'Riattiva accesso' }}
              </button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button v-if="u.ruolo" type="button" class="button secondary" :data-origine="`ruolo-ricerca-${u.idUtente}`" :disabled="!puoAgireSu('ricerca') || !libero"
                    @click="apriRuolo('ricerca', u)">Rendi {{ RUOLO[altroRuolo(u.ruolo)].toLowerCase() }}<span class="visually-hidden"> {{ nome(u) }}</span></button>
            <button type="button" class="button secondary" :data-origine="`stato-ricerca-${u.idUtente}`" :disabled="!puoAgireSu('ricerca') || !libero"
                    @click="apriStato('ricerca', u)">{{ u.statoAccesso === 'ATTIVO' ? 'Disabilita accesso' : 'Riattiva accesso' }}<span class="visually-hidden"> di {{ nome(u) }}</span></button>
          </div>
          <p v-if="!u.ruolo && operabile(u)" class="small">Senza ruolo gestionale: il ruolo non si assegna da questa pagina.</p>
        </li>
      </ul>
      <div v-if="ricerca.prossimo !== null" class="actions">
        <button type="button" class="button secondary" :disabled="ricerca.altri === 'caricamento' || !ricercaValida" @click="caricaAltri">
          {{ ricerca.altri === 'caricamento' ? 'Caricamento…' : 'Mostra altri risultati' }}
        </button>
      </div>
      <p v-if="ricerca.altri === 'errore'" class="field-error" role="alert">Pagina successiva non caricata. {{ ricerca.erroreAltri }}</p>
    </template>
  </section>

  <section v-if="incerte.length" class="card" aria-labelledby="titolo-esiti-admin" data-da-verificare>
    <h2 id="titolo-esiti-admin">Esiti da verificare</h2>
    <div v-for="x in incerte" :key="x.chiave" class="notice warning" data-esito-incerto :data-chiave="x.chiave">
      <p><strong>{{ OPERAZIONI[x.tipo] }} dall'esito non determinato</strong>: {{ descriviIncerta(x) }}.</p>
      <p>Il tentativo non è stato ripetuto; altre operazioni sulla stessa {{ x.tipo === 'crea' ? 'email' : 'persona' }} sono bloccate fino alla verifica, che usa la ricerca amministrativa.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <p v-else-if="x.verifica === 'non-conclusiva'">La verifica precedente non è stata conclusiva.</p>
      <p v-else-if="x.verifica === 'incoerente'">La verifica precedente ha trovato riscontri non coerenti tra loro: ripetila.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verifica(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica' }}
        </button>
      </div>
    </div>
  </section>

  <!-- ------------------------------------------------------------ account gestionali -->
  <section class="card" aria-labelledby="titolo-gestionali" :aria-busy="elenco.stato === 'caricamento' || elencoInAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-gestionali" ref="titoloElenco" tabindex="-1">Account gestionali</h2>
    <p class="small">Tutti gli account con ruolo di amministratore o operatore desk, anche disabilitati.</p>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento in corso…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare gli account gestionali. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="leggiGestionali">Riprova</button></div>
    </div>
    <div v-else-if="elenco.stato === 'superata'" class="notice warning" role="status" data-elenco-superato>
      <p>L'elenco letto è stato superato da operazioni più recenti e non è stato usato.</p>
      <div class="actions"><button type="button" class="button" @click="leggiGestionali">Aggiorna l'elenco</button></div>
    </div>
    <template v-else>
      <p v-if="elencoInAggiornamento" class="small" role="status" data-aggiornamento>Aggiornamento dell'elenco in corso: le azioni sono sospese.</p>
      <div v-if="elenco.superata" class="notice warning" role="status" data-elenco-superato>
        <p>L'elenco mostrato non è aggiornato: l'ultima lettura è stata superata e non è stata usata. Le azioni sono sospese.</p>
        <div class="actions"><button type="button" class="button" @click="leggiGestionali">Aggiorna l'elenco</button></div>
      </div>
      <div v-if="elencoInvalidato" class="notice warning" role="status" data-elenco-invalidato>
        <p>L'elenco mostrato potrebbe non essere più aggiornato: un'operazione conclusa dopo la sua lettura riguarda questi account. Le azioni sono sospese finché non aggiorni l'elenco.</p>
        <div class="actions"><button type="button" class="button" @click="leggiGestionali">Aggiorna l'elenco</button></div>
      </div>
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 account gestionale' : `${elenco.voci.length} account gestionali` }}.</p>
      <ul class="items" aria-label="Account gestionali">
        <li v-for="u in elenco.voci" :key="u.idUtente" class="card" data-gestionale :data-id="u.idUtente">
          <h3 class="item-title">{{ nome(u) }}<span v-if="io(u)" class="muted"> (il tuo account)</span></h3>
          <dl class="facts utenti-dati">
            <div class="utenti-email"><dt>Email</dt><dd data-campo="email">{{ u.email }}</dd></div>
            <div><dt>Ruolo</dt><dd data-campo="ruolo">{{ RUOLO[u.ruolo] }}</dd></div>
            <div><dt>Stato di accesso</dt><dd data-campo="stato">{{ STATO_ACCESSO[u.statoAccesso] }}</dd></div>
            <div><dt>Profili</dt><dd data-campo="profili">Paziente: {{ u.paziente ? 'sì' : 'no' }}; medico: {{ u.medico ? 'sì' : 'no' }}</dd></div>
            <div><dt>Utente</dt><dd>n. {{ u.idUtente }}</dd></div>
          </dl>
          <p v-if="u.medico" class="small">Il profilo medico ha un proprio stato operativo, distinto dallo stato di accesso e non gestito in questa pagina.</p>
          <p v-if="!operabile(u)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <div v-else-if="azione?.lista === 'gestionali' && azione.id === u.idUtente" class="confirm" role="group"
               :aria-labelledby="`titolo-conferma-gestionali-${u.idUtente}`" :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
            <h4 :id="`titolo-conferma-gestionali-${u.idUtente}`" :ref="impostaTitolo" tabindex="-1">
              {{ azione.tipo === 'ruolo' ? 'Confermi il cambio di ruolo?' : "Confermi il cambio dello stato di accesso?" }}
            </h4>
            <p>Utente n. {{ u.idUtente }}: <strong>{{ nome(u) }}</strong>, {{ u.email }}.</p>
            <p v-if="azione.tipo === 'ruolo'" data-campo="transizione">Ruolo: da «{{ RUOLO[u.ruolo] }}» a «{{ RUOLO[azione.nuovo] }}».</p>
            <p v-else data-campo="transizione">Stato di accesso: da «{{ STATO_ACCESSO[u.statoAccesso] }}» a «{{ STATO_ACCESSO[azione.nuovo] }}».</p>
            <p class="small">Il server può rifiutare l'operazione (ad esempio per l'ultimo amministratore attivo, per il tuo stesso account o per un profilo medico).</p>
            <div class="actions">
              <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgireSu('gestionali')" @click="confermaUtente">
                {{ azione.fase === 'invio' ? 'Operazione in corso…' : azione.tipo === 'ruolo' ? 'Cambia ruolo' : azione.nuovo === 'DISABILITATO' ? 'Disabilita accesso' : 'Riattiva accesso' }}
              </button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button type="button" class="button secondary" :data-origine="`ruolo-gestionali-${u.idUtente}`" :disabled="!puoAgireSu('gestionali') || !libero"
                    @click="apriRuolo('gestionali', u)">Rendi {{ RUOLO[altroRuolo(u.ruolo)].toLowerCase() }}<span class="visually-hidden"> {{ nome(u) }}</span></button>
            <button type="button" class="button secondary" :data-origine="`stato-gestionali-${u.idUtente}`" :disabled="!puoAgireSu('gestionali') || !libero"
                    @click="apriStato('gestionali', u)">{{ u.statoAccesso === 'ATTIVO' ? 'Disabilita accesso' : 'Riattiva accesso' }}<span class="visually-hidden"> di {{ nome(u) }}</span></button>
          </div>
        </li>
      </ul>
    </template>
  </section>

  </div>
</template>

<style scoped>
.utenti-intestazione { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
.utenti-intestazione h1, .utenti-intestazione h2 { margin: 0; }
.utenti-filtri { max-width: none; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: start; }
.utenti-filtri > .actions, .utenti-filtri > .notice { grid-column: 1 / -1; }
.admin-utenti .facts > div { min-width: 0; }
.admin-utenti .facts dd { overflow-wrap: anywhere; }
[data-risultato], [data-gestionale] { container-type: inline-size; }
.utenti-dati { grid-template-columns: repeat(6, minmax(0, 1fr)); }
.utenti-email { grid-column: span 2; }
@container (max-width: 55rem) { .utenti-dati { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@container (max-width: 38rem) { .utenti-dati { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@container (max-width: 24rem) {
  .utenti-dati { grid-template-columns: minmax(0, 1fr); }
  .utenti-email { grid-column: auto; }
}
.utenti-ruoli label { display: flex; align-items: center; gap: .625rem; padding-block: .375rem; cursor: pointer; }
.utenti-ruoli input[type="radio"] { width: 1.1875rem; height: 1.1875rem; min-height: 0; padding: 0; margin: 0; flex: 0 0 1.1875rem; }
@media (max-width: 40rem) { .utenti-filtri { grid-template-columns: minmax(0, 1fr); } }
</style>
