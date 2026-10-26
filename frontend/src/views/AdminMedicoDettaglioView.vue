<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { HttpError, StaleResponseError } from '../api/http.js'
import { fetchPrestazioni } from '../api/catalogo.js'
import { fetchMedicoAdmin, validaPersonali, validaProfessionali } from '../api/medici.js'
import { erroreNuovaPassword } from '../api/utenti.js'
import { parseIdRisorsa } from '../api/prenotazioni.js'
import { TESTO_ERRORE_VERIFICA, TESTO_INCOERENTE, TESTO_NON_CONCLUSIVA, TESTO_SUPERATA, testoEsito } from '../admin/formato.js'
import { CAUSE_CONFLITTO, OPERAZIONI_MEDICO, STATO_MEDICO, descriviIncertaMedico, nome, ruoloGestionale, statoMedico, testoVerificaMedico } from '../admin/formatoMedici.js'
import { TIPI_MEDICO, chiaveMedico, chiaveUtente } from '../admin/store.js'
import { usePaginaAdmin } from '../admin/usePaginaAdmin.js'
import { formatEuro } from '../utils/money.js'

/**
 * Dettaglio amministrativo di un medico : dati, abilitazioni, modifica dei dati personali o
 * professionali (varianti esclusive), stato operativo e abilitazioni. Ogni conferma vale per la
 * versione letta; un esito certo successivo alla lettura sospende le azioni fino alla rilettura.
 */
const route = useRoute()
const titolo = ref(null)
const p = usePaginaAdmin({ azzera, ricarica: () => carica(), titoloRiserva: () => titolo.value })
const { store, session, avviso, avvisoBox, mostra, messaggioLettura, attesa, puoScrivere, azione, libero, apri, annulla, chiudi, impostaTitolo } = p

const id = computed(() => parseIdRisorsa(String(route.params.id ?? '')))
const VUOTO = () => ({ stato: 'caricamento', medico: null, prestazioni: [], errore: '', aggiornata: false, superata: false })
const dettaglio = reactive(VUOTO())
const ticket = shallowRef(null)
let seq = 0

async function carica() {
  const idMedico = id.value
  if (idMedico === null) {
    Object.assign(dettaglio, { ...VUOTO(), stato: 'invalido' })
    return
  }
  if (p.idAdmin() === null) return
  const ctx = p.contestoCorrente()
  const mia = ++seq
  Object.assign(dettaglio, { errore: '', aggiornata: false, superata: false })
  if (dettaglio.stato !== 'pronto') dettaglio.stato = 'caricamento'
  const t = store.inizioLettura()
  try {
    const d = await fetchMedicoAdmin(session.protectedRequest, idMedico)
    if (mia !== seq || !p.corrente(ctx)) return
    if (!store.valida(chiaveMedico(idMedico), t)) {
      dettaglio.superata = true
      if (dettaglio.stato !== 'pronto') dettaglio.stato = 'superata'
      return
    }
    Object.assign(dettaglio, { stato: 'pronto', medico: d.medico, prestazioni: d.prestazioni, aggiornata: true })
    ticket.value = t
    store.registraDettaglio(d, t)
    precompila()
  } catch (error) {
    if (mia !== seq || !p.corrente(ctx) || error instanceof StaleResponseError) return
    if (error instanceof HttpError && error.status === 404) {
      store.registraAssenzaMedico(idMedico, t)
      Object.assign(dettaglio, { ...VUOTO(), stato: 'non-trovato' })
      return
    }
    Object.assign(dettaglio, { stato: 'errore', errore: messaggioLettura(error) })
  }
}
const valido = () => dettaglio.stato === 'pronto' && dettaglio.aggiornata && store.valida(chiaveMedico(id.value), ticket.value)
const invalidato = computed(() => dettaglio.stato === 'pronto' && dettaglio.aggiornata && !store.valida(chiaveMedico(id.value), ticket.value))
const bloccato = computed(() => id.value !== null && store.bloccata(chiaveUtente(id.value)))
const puoAgire = computed(() => puoScrivere.value && valido() && !bloccato.value && libero.value)

// Catalogo per le assegnazioni: solo prestazioni esistenti e non già abilitate.
const catalogo = reactive({ stato: 'caricamento', voci: [] })
async function leggiCatalogo() {
  try {
    const voci = await fetchPrestazioni()
    if (!p.smontato()) Object.assign(catalogo, { stato: 'pronto', voci })
  } catch {
    if (!p.smontato()) catalogo.stato = 'errore'
  }
}
const assegnabili = computed(() => catalogo.voci.filter((c) => !dettaglio.prestazioni.some((x) => x.idPrestazione === c.idPrestazione)))
const daAssegnare = ref('')

// Reset amministrativo: segreti soltanto nei campi locali e nella richiesta corrente.
const resetAperto = ref(false)
const nuovaPassword = ref('')
const ripetiPassword = ref('')
const erroreReset = ref('')
const pulisciPassword = () => { nuovaPassword.value = ''; ripetiPassword.value = '' }
onBeforeUnmount(pulisciPassword)
async function apriReset() {
  if (resetAperto.value || !puoAgire.value) return
  const ctx = p.contestoCorrente()
  resetAperto.value = true
  pulisciPassword(); erroreReset.value = ''
  await nextTick()
  if (p.corrente(ctx) && resetAperto.value) document.querySelector('#reset-nuova')?.focus()
}
async function chiudiReset() {
  const ctx = p.contestoCorrente()
  resetAperto.value = false; pulisciPassword(); erroreReset.value = ''
  if (azione.value?.tipo === 'reset-password' && azione.value.fase !== 'invio') chiudi()
  await nextTick()
  if (p.corrente(ctx) && !resetAperto.value) {
    const b = document.querySelector('[data-apri-reset]')
    ;(b && !b.disabled ? b : titolo.value)?.focus()
  }
}
async function proseguiReset() {
  if (!resetAperto.value || !puoAgire.value) return
  erroreReset.value = erroreNuovaPassword(nuovaPassword.value) || (nuovaPassword.value !== ripetiPassword.value ? 'Le password non coincidono.' : '')
  if (erroreReset.value) return
  const m = dettaglio.medico
  await apri({ tipo: 'reset-password', medico: { ...m }, idonea: () => valido() && id.value === m.idUtente && !bloccato.value }, 'reset-password')
}
const focusPersoReset = () => !document.activeElement || document.activeElement === document.body || !document.activeElement.isConnected
async function confermaReset() {
  const a = azione.value
  if (!resetAperto.value || a?.tipo !== 'reset-password' || a.fase !== 'conferma' || !puoScrivere.value || !a.idonea()) return
  const ctx = p.contestoCorrente()
  const segreto = nuovaPassword.value
  pulisciPassword()
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.reimpostaPasswordMedico(a.medico, segreto)
  if (!p.corrente(ctx)) return
  chiudi()
  if (esito.kind === 'eseguita') {
    resetAperto.value = false
    if (a.medico.idUtente === session.state.user?.idUtente) { await session.logout(); return }
    carica()
    await mostra(ctx, 'successo', `Password dell'account del medico n. ${a.medico.idUtente} reimpostata. Tutte le sessioni precedenti sono invalidate. Nessun accesso automatico.`)
    if (p.corrente(ctx) && !resetAperto.value && focusPersoReset()) {
      const b = document.querySelector('[data-apri-reset]')
      ;(b && !b.disabled ? b : titolo.value)?.focus()
    }
  } else if (!['obsoleto','sessione-assente'].includes(esito.kind)) {
    avviso.value = { tipo: 'errore', testo: esito.kind === 'incerto'
      ? 'Esito del reset incerto. La password inviata è stata cancellata: non ripetere il tentativo alla cieca. La lettura del medico non verifica quale password sia salvata.' : testoEsito(esito) }
    await nextTick()
    if (p.corrente(ctx) && focusPersoReset()) avvisoBox.value?.focus()
  }
}
function nuovoReset(x) {
  if (x.tipo !== 'password-medico' || x.idUtente !== id.value) return
  if (!window.confirm('Il reset precedente resta di esito ignoto. Vuoi consentire un nuovo reset esplicito? Dovrai rileggere il medico, inserire una nuova password e confermare.')) return
  if (store.consentiNuovoReset(x.idUtente)) carica()
}

// ---------------------------------------------------------------- moduli

const personali = reactive({ nome: '', cognome: '', email: '' })
const professionali = reactive({ specializzazione: '', numeroIscrizioneOrdine: '' })
const erroriPersonali = ref({})
const erroriProfessionali = ref({})
/**
 * Base di ogni campo dei moduli: il valore letto da cui è partita la modifica, legato all'ID del
 * medico. Una rilettura aggiorna i campi non modificati e conserva quelli modificati (con la
 * loro base originale); il contesto nuovo (altro medico, epoca) azzera moduli e basi.
 */
const MODULI = { personali, professionali }
const basi = reactive({ personali: null, professionali: null })
const modificato = (modulo, k) => basi[modulo] !== null && MODULI[modulo][k] !== basi[modulo].valori[k]
/**
 * Dopo un salvataggio riuscito (contesto e medico ancora quelli del tentativo): la base di ogni
 * campo diventa il valore salvato dalla risposta validata (già normalizzato dal backend); solo i
 * campi ancora uguali ai valori inviati prendono il valore salvato. Le modifiche fatte dopo
 * l'invio, anche con la PUT pendente, restano e risultano modificate rispetto al valore salvato.
 */
function riallinea(tentativo, salvato) {
  const base = basi[tentativo.modulo]
  if (!base || base.id !== tentativo.id || salvato.idUtente !== tentativo.id) return
  const campi = MODULI[tentativo.modulo]
  const valori = { ...base.valori }
  for (const k of Object.keys(campi)) {
    valori[k] = salvato[k]
    if (campi[k] === tentativo.valori[k]) campi[k] = salvato[k]
  }
  basi[tentativo.modulo] = { id: tentativo.id, valori }
}
/** Campo modificato il cui valore sul server è cambiato dopo l'inizio della modifica. */
const cambiatoSulServer = (modulo, k) => modificato(modulo, k) && dettaglio.medico && basi[modulo].valori[k] !== dettaglio.medico[k]
function precompila() {
  const m = dettaglio.medico
  for (const [modulo, campi] of Object.entries(MODULI)) {
    const base = basi[modulo]?.id === m.idUtente ? basi[modulo] : null
    const valori = {}
    for (const k of Object.keys(campi)) {
      // Conservato solo se modificato e diverso dal nuovo valore letto (altrimenti è già allineato).
      if (base && campi[k] !== base.valori[k] && campi[k] !== m[k]) valori[k] = base.valori[k]
      else { campi[k] = m[k]; valori[k] = m[k] }
    }
    basi[modulo] = { id: m.idUtente, valori }
  }
  if (!Object.keys(personali).some((k) => modificato('personali', k))) erroriPersonali.value = {}
  if (!Object.keys(professionali).some((k) => modificato('professionali', k))) erroriProfessionali.value = {}
}

/** La conferma vale per la versione letta: stesso medico, stessi valori dei campi coinvolti. */
const stessaVersione = (m, campi) => dettaglio.medico && campi.every((k) => dettaglio.medico[k] === m[k])
const CAMPI = { personali: ['nome', 'cognome', 'email'], professionali: ['specializzazione', 'numeroIscrizioneOrdine'], stato: ['stato'], abilitazione: [] }
const idonea = (tipo, m) => () => valido() && !bloccato.value && stessaVersione(m, CAMPI[tipo])

async function prosegui(tipo) {
  if (!puoAgire.value) return
  const personale = tipo === 'personali'
  const { dati, errori } = personale ? validaPersonali(personali) : validaProfessionali(professionali)
  // Una modifica partita da un valore poi cambiato sul server non si unisce in silenzio alla nuova versione.
  const m0 = dettaglio.medico
  for (const k of Object.keys(MODULI[tipo])) {
    if (!cambiatoSulServer(tipo, k)) continue
    errori[k] = `Il valore sul server è cambiato durante la modifica (ora «${m0[k]}»): controlla il campo e prosegui di nuovo.`
    basi[tipo].valori[k] = m0[k]
  }
  if (personale) erroriPersonali.value = errori
  else erroriProfessionali.value = errori
  if (Object.keys(errori).length) {
    await nextTick()
    document.querySelector(`[data-errori="${tipo}"]`)?.focus()
    return
  }
  const m = dettaglio.medico
  await apri({ tipo, medico: m, dati, idonea: idonea(tipo, m) }, tipo)
}
const apriStato = () => {
  const m = dettaglio.medico
  if (puoAgire.value && !m.statoAnomalo) apri({ tipo: 'stato', medico: m, nuovo: m.stato === 'ATTIVO' ? 'INATTIVO' : 'ATTIVO', idonea: idonea('stato', m) }, 'stato')
}
const apriRimozione = (x) => {
  if (puoAgire.value) apri({ tipo: 'abilitazione', medico: dettaglio.medico, operazione: 'RIMUOVI', prestazione: x, idonea: () => valido() && !bloccato.value && dettaglio.prestazioni.some((v) => v.idPrestazione === x.idPrestazione) }, `rimuovi-${x.idPrestazione}`)
}
const apriAssegnazione = () => {
  const x = catalogo.voci.find((c) => String(c.idPrestazione) === daAssegnare.value)
  if (puoAgire.value && x) apri({ tipo: 'abilitazione', medico: dettaglio.medico, operazione: 'ASSEGNA', prestazione: x, idonea: () => valido() && !bloccato.value && !dettaglio.prestazioni.some((v) => v.idPrestazione === x.idPrestazione) }, 'assegna')
}

const TIPO_INCERTO = { personali: 'personali', professionali: 'professionali', stato: 'stato-medico', abilitazione: 'abilitazione' }

async function conferma() {
  const a = azione.value
  if (!a || a.fase !== 'conferma' || !puoScrivere.value || !a.idonea()) return
  const ctx = p.contestoCorrente()
  // Snapshot immutabile del tentativo: dati inviati (a.dati) e valori del modulo al momento dell'invio.
  const tentativo = a.tipo in MODULI ? Object.freeze({ modulo: a.tipo, id: a.medico.idUtente, valori: Object.freeze({ ...MODULI[a.tipo] }) }) : null
  azione.value = { ...a, fase: 'invio' }
  let esito
  if (a.tipo === 'personali') esito = await store.aggiornaMedico(a.medico, 'PERSONALI', a.dati)
  else if (a.tipo === 'professionali') esito = await store.aggiornaMedico(a.medico, 'PROFESSIONALI', a.dati)
  else if (a.tipo === 'stato') esito = await store.cambiaStatoMedico(a.medico, a.nuovo)
  else esito = await store.abilitazione(a.medico, a.operazione, a.prestazione)
  if (!p.corrente(ctx)) return
  if (esito.kind === 'eseguita' && tentativo) riallinea(tentativo, esito.risultato)
  if (['eseguita', 'conflitto', 'non-trovata'].includes(esito.kind)) carica()
  chiudi()
  if (esito.kind === 'eseguita') {
    const r = esito.risultato
    const testi = {
      personali: () => `Il server conferma i dati personali «${r.nome} ${r.cognome}, ${r.email}». Sono i dati dell'account, condivisi con l'eventuale profilo paziente.`,
      professionali: () => `Il server conferma i dati professionali «${r.specializzazione}, ${r.numeroIscrizioneOrdine}».`,
      stato: () => `Il server conferma lo stato operativo «${statoMedico(r)}». Lo stato di accesso dell'account non cambia.`,
      abilitazione: () => `Il server conferma ${a.operazione === 'ASSEGNA' ? "l'assegnazione" : 'la rimozione'} dell'abilitazione a «${a.prestazione.nome}».`,
    }
    daAssegnare.value = ''
    await mostra(ctx, 'successo', `${testi[a.tipo]()} Il dettaglio viene riletto.`)
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    const base = testoEsito(esito)
    await mostra(ctx, 'errore', esito.kind === 'conflitto' ? `${base} Possibili cause: ${CAUSE_CONFLITTO[TIPO_INCERTO[a.tipo]]}.` : base)
  }
}

// ---------------------------------------------------------------- esiti da verificare

const incerte = computed(() => store.state.incerte.filter((x) => TIPI_MEDICO.includes(x.tipo) && x.idUtente === id.value))
const altreIncerte = computed(() => store.state.incerte.some((x) => !TIPI_MEDICO.includes(x.tipo) && x.idUtente === id.value))
async function verifica(x) {
  const ctx = p.contestoCorrente()
  avviso.value = null
  const lettura = await store.leggiVerifica(x.chiave)
  if (!p.corrente(ctx)) return
  const esito = lettura.kind === 'letta' ? store.esitoVerifica(x.chiave, lettura) : lettura
  if (esito.kind === 'verificata') {
    carica()
    await mostra(ctx, 'info', testoVerificaMedico(esito.incerta, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'non-conclusiva') await mostra(ctx, 'info', TESTO_NON_CONCLUSIVA)
  else if (esito.kind === 'incoerente') await mostra(ctx, 'info', TESTO_INCOERENTE)
  else if (esito.kind === 'errore') await mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}

// ---------------------------------------------------------------- contesto

function azzera() {
  resetAperto.value = false; pulisciPassword(); erroreReset.value = ''
  seq += 1
  Object.assign(dettaglio, VUOTO())
  ticket.value = null
  Object.assign(personali, { nome: '', cognome: '', email: '' })
  Object.assign(professionali, { specializzazione: '', numeroIscrizioneOrdine: '' })
  erroriPersonali.value = {}
  erroriProfessionali.value = {}
  basi.personali = null
  basi.professionali = null
  daAssegnare.value = ''
}
// Cambio di ID (anche A → B → A): contesto nuovo, nessun effetto tardivo della vista precedente.
watch(id, () => {
  p.nuovoContesto()
  carica()
})
onMounted(() => {
  leggiCatalogo()
  carica()
})
const lista = (errori) => Object.values(errori)
const descr = (errori, campo, idErr) => (errori[campo] ? idErr : undefined)
</script>

<template>
  <p><RouterLink to="/admin/medici">Torna ai medici</RouterLink></p>
  <h1 ref="titolo" tabindex="-1">{{ dettaglio.medico ? nome(dettaglio.medico) : 'Dettaglio medico' }}</h1>

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

  <section v-if="incerte.length" class="card" aria-labelledby="titolo-esiti-medico" data-da-verificare>
    <h2 id="titolo-esiti-medico">Esiti da verificare</h2>
    <div v-for="x in incerte" :key="x.chiave" class="notice warning" data-esito-incerto :data-chiave="x.chiave">
      <p><strong>{{ OPERAZIONI_MEDICO[x.tipo] }} dall'esito non determinato</strong>: {{ descriviIncertaMedico(x) }}.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica non è stato usato perché superato: ripeti la verifica.</p>
      <template v-if="x.tipo === 'password-medico'">
        <p>Il reset non è verificabile con la ricerca utenti o la lettura del medico. Le altre operazioni sull'account restano bloccate. Verifica l'accesso con il destinatario fuori da questa pagina, oppure scegli consapevolmente un nuovo reset.</p>
        <button type="button" class="button secondary" @click="nuovoReset(x)">Consenti un nuovo reset</button>
      </template>
      <div v-else class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verifica(x)">{{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica' }}</button>
      </div>
    </div>
  </section>
  <p v-if="altreIncerte" class="notice warning" role="status" data-incerta-utenti>
    Un'operazione sull'account di questo medico è da verificare in <RouterLink to="/admin/utenti">Gestione utenti</RouterLink>: le modifiche qui sono bloccate fino alla verifica.
  </p>

  <p v-if="dettaglio.stato === 'invalido'" class="notice error" role="alert" data-id-non-valido>Medico non trovato: l'indirizzo non indica un medico valido.</p>
  <p v-else-if="dettaglio.stato === 'caricamento'" role="status">Caricamento in corso…</p>
  <div v-else-if="dettaglio.stato === 'non-trovato'" class="notice error" role="alert" data-non-trovato>
    <p>Medico non trovato.</p>
    <div class="actions"><button type="button" class="button" @click="carica">Riprova</button></div>
  </div>
  <div v-else-if="dettaglio.stato === 'errore'" class="notice error" role="alert">
    <p>Non è stato possibile caricare il medico. {{ dettaglio.errore }}</p>
    <div class="actions"><button type="button" class="button" @click="carica">Riprova</button></div>
  </div>
  <div v-else-if="dettaglio.stato === 'superata'" class="notice warning" role="status">
    <p>Il dettaglio letto è stato superato da operazioni più recenti e non è stato usato.</p>
    <div class="actions"><button type="button" class="button" @click="carica">Aggiorna</button></div>
  </div>
  <template v-else-if="dettaglio.stato === 'pronto'">
    <p v-if="!dettaglio.aggiornata && !dettaglio.superata" class="small" role="status">Aggiornamento in corso: le azioni sono sospese.</p>
    <div v-if="dettaglio.superata || invalidato" class="notice warning" role="status" data-dettaglio-invalidato>
      <p>Il dettaglio mostrato potrebbe non essere più aggiornato: un'operazione conclusa dopo la lettura riguarda questo medico. Le azioni sono sospese.</p>
      <div class="actions"><button type="button" class="button" @click="carica">Aggiorna il dettaglio</button></div>
    </div>
    <p v-if="bloccato" class="small" data-bloccato><strong>Operazione in corso o da verificare su questo account: modifiche sospese.</strong></p>

    <section class="card medico-scheda" aria-labelledby="titolo-dati-medico">
      <h2 id="titolo-dati-medico">Dati</h2>
      <dl class="facts medico-dati">
        <div class="medico-email"><dt>Email</dt><dd data-campo="email">{{ dettaglio.medico.email }}</dd></div>
        <div><dt>Specializzazione</dt><dd data-campo="specializzazione">{{ dettaglio.medico.specializzazione }}</dd></div>
        <div><dt>Iscrizione all'ordine</dt><dd data-campo="numero">{{ dettaglio.medico.numeroIscrizioneOrdine }}</dd></div>
        <div><dt>Stato operativo</dt><dd data-campo="stato">{{ statoMedico(dettaglio.medico) }}</dd></div>
        <div><dt>Profilo paziente</dt><dd data-campo="paziente">{{ dettaglio.medico.paziente ? 'Sì' : 'No' }}</dd></div>
        <div><dt>Ruolo gestionale</dt><dd data-campo="ruolo">{{ ruoloGestionale(dettaglio.medico.ruolo) }}</dd></div>
        <div><dt>Utente</dt><dd>n. {{ dettaglio.medico.idUtente }}</dd></div>
      </dl>
      <div class="actions">
        <button type="button" class="button secondary" data-apri-reset :disabled="!puoAgire" :aria-expanded="resetAperto" aria-controls="modulo-reset-medico" @click="apriReset">Reimposta password</button>
      </div>
      <section v-if="resetAperto" id="modulo-reset-medico" class="card" aria-labelledby="titolo-reset-medico">
        <h3 id="titolo-reset-medico">Reimposta password dell'account</h3>
        <p>Destinatario: {{ nome(dettaglio.medico) }} · utente n. {{ id }} · {{ dettaglio.medico.email }}.</p>
        <p>Non serve la password attuale. Cambia la password dell'intero account, anche per il profilo paziente se presente. Le sessioni del destinatario saranno invalidate; ruoli e stati non cambiano.</p>
        <div v-if="azione?.tipo === 'reset-password'" class="confirm" data-conferma="reset-password" role="group" aria-labelledby="titolo-conferma-reset">
          <h4 id="titolo-conferma-reset" :ref="impostaTitolo" tabindex="-1">Confermi il reset per {{ nome(azione.medico) }} (n. {{ azione.medico.idUtente }})?</h4>
          <p>La nuova password non viene mostrata. Il destinatario dovrà accedere con quella nuova; tutte le sessioni precedenti saranno invalidate.</p>
          <div class="actions">
            <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaReset">{{ azione.fase === 'invio' ? 'Reset in corso…' : 'Conferma reset password' }}</button>
            <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
          </div>
        </div>
        <form v-else class="form" novalidate @submit.prevent="proseguiReset">
          <div class="field"><label for="reset-nuova">Nuova password (12–1024 caratteri)</label><input id="reset-nuova" v-model="nuovaPassword" type="password" autocomplete="new-password" :aria-invalid="erroreReset ? 'true' : undefined" aria-describedby="errore-reset" /></div>
          <div class="field"><label for="reset-ripeti">Ripeti nuova password</label><input id="reset-ripeti" v-model="ripetiPassword" type="password" autocomplete="new-password" :aria-invalid="erroreReset ? 'true' : undefined" aria-describedby="errore-reset" /></div>
          <p id="errore-reset" class="field-error" role="alert">{{ erroreReset }}</p>
          <div class="actions reset-actions">
            <button type="submit" class="button" data-origine="reset-password" :disabled="!puoAgire">Prosegui</button>
            <button type="button" class="button secondary" @click="chiudiReset">Chiudi</button>
          </div>
        </form>
        <div v-if="azione?.tipo === 'reset-password'" class="actions reset-actions"><button type="button" class="button secondary" @click="chiudiReset">Chiudi</button></div>
      </section>
      <p class="small medico-nota-accesso">Lo stato di accesso dell'account non fa parte di questa scheda: si consulta in <RouterLink to="/admin/utenti">Gestione utenti</RouterLink>.</p>
      <div v-if="azione?.tipo === 'stato'" class="confirm" role="group" aria-labelledby="titolo-conferma-stato" data-conferma="stato">
        <h3 id="titolo-conferma-stato" :ref="impostaTitolo" tabindex="-1">Confermi il cambio dello stato operativo?</h3>
        <p data-campo="transizione">Medico n. {{ azione.medico.idUtente }}, {{ nome(azione.medico) }}: da «{{ STATO_MEDICO[azione.medico.stato] }}» a «{{ STATO_MEDICO[azione.nuovo] }}».</p>
        <p class="small">Il server verifica le prenotazioni bloccanti e, per la riattivazione, che l'account sia attivo. Lo stato di accesso dell'account non cambia.</p>
        <div class="actions">
          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="conferma">{{ azione.fase === 'invio' ? 'Operazione in corso…' : azione.nuovo === 'INATTIVO' ? 'Rendi inattivo' : 'Riattiva' }}</button>
          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
        </div>
      </div>
      <div v-else class="actions">
        <button v-if="!dettaglio.medico.statoAnomalo" type="button" class="button secondary" data-origine="stato" :disabled="!puoAgire" @click="apriStato">
          {{ dettaglio.medico.stato === 'ATTIVO' ? 'Rendi inattivo' : 'Riattiva' }}
        </button>
      </div>
      <p v-if="dettaglio.medico.statoAnomalo" class="small">Stato operativo non riconosciuto: il server rifiuta le modifiche finché non è corretto.</p>
    </section>

    <section class="card" aria-labelledby="titolo-abilitazioni-admin">
      <h2 id="titolo-abilitazioni-admin">Abilitazioni</h2>
      <p class="small">Il server verifica disponibilità e prenotazioni associate prima di una rimozione; anche un medico inattivo può essere abilitato o disabilitato.</p>
      <ul v-if="dettaglio.prestazioni.length" class="items" aria-label="Prestazioni autorizzate">
        <li v-for="x in dettaglio.prestazioni" :key="x.idPrestazione" class="card" data-abilitazione :data-id="x.idPrestazione">
          <p><strong>{{ x.nome }}</strong> — {{ x.durata }} minuti, {{ formatEuro(x.prezzo) }}</p>
          <div v-if="azione?.tipo === 'abilitazione' && azione.operazione === 'RIMUOVI' && azione.prestazione.idPrestazione === x.idPrestazione" class="confirm" role="group"
               :aria-labelledby="`titolo-conferma-rimuovi-${x.idPrestazione}`" data-conferma="rimuovi">
            <h3 :id="`titolo-conferma-rimuovi-${x.idPrestazione}`" :ref="impostaTitolo" tabindex="-1">Confermi la rimozione dell'abilitazione?</h3>
            <p>L'abilitazione di {{ nome(azione.medico) }} alla prestazione «{{ x.nome }}» sarà rimossa, se il server non trova dipendenze.</p>
            <div class="actions">
              <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="conferma">{{ azione.fase === 'invio' ? 'Operazione in corso…' : 'Rimuovi abilitazione' }}</button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button type="button" class="button secondary" :data-origine="`rimuovi-${x.idPrestazione}`" :disabled="!puoAgire" @click="apriRimozione(x)">
              Rimuovi<span class="visually-hidden"> l'abilitazione a {{ x.nome }}</span>
            </button>
          </div>
        </li>
      </ul>
      <p v-else>Nessuna abilitazione.</p>
      <div v-if="azione?.tipo === 'abilitazione' && azione.operazione === 'ASSEGNA'" class="confirm" role="group" aria-labelledby="titolo-conferma-assegna" data-conferma="assegna">
        <h3 id="titolo-conferma-assegna" :ref="impostaTitolo" tabindex="-1">Confermi l'assegnazione dell'abilitazione?</h3>
        <p>{{ nome(azione.medico) }} sarà abilitato a «{{ azione.prestazione.nome }}».</p>
        <div class="actions">
          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="conferma">{{ azione.fase === 'invio' ? 'Operazione in corso…' : 'Assegna abilitazione' }}</button>
          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
        </div>
      </div>
      <div v-else class="form">
        <div class="field">
          <label for="assegna-prestazione">Nuova abilitazione (dal catalogo)</label>
          <select id="assegna-prestazione" v-model="daAssegnare" :disabled="catalogo.stato !== 'pronto'">
            <option value="">Scegli una prestazione</option>
            <option v-for="c in assegnabili" :key="c.idPrestazione" :value="String(c.idPrestazione)">{{ c.nome }}</option>
          </select>
          <p v-if="catalogo.stato === 'errore'" class="small">Catalogo non disponibile: assegnazione sospesa.</p>
        </div>
        <div class="actions"><button type="button" class="button secondary" data-origine="assegna" :disabled="!puoAgire || !daAssegnare" @click="apriAssegnazione">Assegna</button></div>
      </div>
    </section>

    <section class="card" aria-labelledby="titolo-personali">
      <h2 id="titolo-personali">Dati personali</h2>
      <p class="small">Nome, cognome ed email appartengono all'account e sono condivisi con l'eventuale profilo paziente. Il server li rifiuta per un account con ruolo gestionale. La password non si modifica da qui.</p>
      <div v-if="azione?.tipo === 'personali'" class="confirm" role="group" aria-labelledby="titolo-conferma-personali" data-conferma="personali">
        <h3 id="titolo-conferma-personali" :ref="impostaTitolo" tabindex="-1">Confermi i nuovi dati personali?</h3>
        <p data-campo="transizione">Da «{{ azione.medico.nome }} {{ azione.medico.cognome }}, {{ azione.medico.email }}» a «{{ azione.dati.nome }} {{ azione.dati.cognome }}, {{ azione.dati.email }}».</p>
        <div class="actions">
          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="conferma">{{ azione.fase === 'invio' ? 'Operazione in corso…' : 'Salva dati personali' }}</button>
          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
        </div>
      </div>
      <form class="form" novalidate @submit.prevent="prosegui('personali')">
        <div v-if="lista(erroriPersonali).length" tabindex="-1" class="notice error" role="alert" data-errori="personali">
          <p>Controlla i dati indicati.</p><ul><li v-for="e in lista(erroriPersonali)" :key="e">{{ e }}</li></ul>
        </div>
        <div v-for="[campo, etichetta] in [['nome', 'Nome'], ['cognome', 'Cognome'], ['email', 'Email']]" :key="campo" class="field">
          <label :for="`personali-${campo}`">{{ etichetta }}</label>
          <input :id="`personali-${campo}`" v-model="personali[campo]" autocomplete="off"
                 :aria-invalid="erroriPersonali[campo] ? 'true' : undefined" :aria-describedby="descr(erroriPersonali, campo, `errore-personali-${campo}`)" />
          <p v-if="erroriPersonali[campo]" :id="`errore-personali-${campo}`" class="field-error">{{ erroriPersonali[campo] }}</p>
          <p v-else-if="modificato('personali', campo)" class="small" :data-modificato="campo">Modificato: conservato anche se il dettaglio viene riletto.</p>
        </div>
        <div class="actions"><button type="submit" class="button secondary" data-origine="personali" :disabled="!puoAgire">Prosegui</button></div>
      </form>
    </section>

    <section class="card" aria-labelledby="titolo-professionali">
      <h2 id="titolo-professionali">Dati professionali</h2>
      <div v-if="azione?.tipo === 'professionali'" class="confirm" role="group" aria-labelledby="titolo-conferma-professionali" data-conferma="professionali">
        <h3 id="titolo-conferma-professionali" :ref="impostaTitolo" tabindex="-1">Confermi i nuovi dati professionali?</h3>
        <p data-campo="transizione">Da «{{ azione.medico.specializzazione }}, {{ azione.medico.numeroIscrizioneOrdine }}» a «{{ azione.dati.specializzazione }}, {{ azione.dati.numeroIscrizioneOrdine }}».</p>
        <div class="actions">
          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="conferma">{{ azione.fase === 'invio' ? 'Operazione in corso…' : 'Salva dati professionali' }}</button>
          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
        </div>
      </div>
      <form class="form" novalidate @submit.prevent="prosegui('professionali')">
        <div v-if="lista(erroriProfessionali).length" tabindex="-1" class="notice error" role="alert" data-errori="professionali">
          <p>Controlla i dati indicati.</p><ul><li v-for="e in lista(erroriProfessionali)" :key="e">{{ e }}</li></ul>
        </div>
        <div v-for="[campo, etichetta] in [['specializzazione', 'Specializzazione'], ['numeroIscrizioneOrdine', 'Numero di iscrizione all\'ordine']]" :key="campo" class="field">
          <label :for="`professionali-${campo}`">{{ etichetta }}</label>
          <input :id="`professionali-${campo}`" v-model="professionali[campo]" autocomplete="off"
                 :aria-invalid="erroriProfessionali[campo] ? 'true' : undefined" :aria-describedby="descr(erroriProfessionali, campo, `errore-professionali-${campo}`)" />
          <p v-if="erroriProfessionali[campo]" :id="`errore-professionali-${campo}`" class="field-error">{{ erroriProfessionali[campo] }}</p>
          <p v-else-if="modificato('professionali', campo)" class="small" :data-modificato="campo">Modificato: conservato anche se il dettaglio viene riletto.</p>
        </div>
        <div class="actions"><button type="submit" class="button secondary" data-origine="professionali" :disabled="!puoAgire">Prosegui</button></div>
      </form>
    </section>
  </template>
</template>

<style scoped>
#modulo-reset-medico { margin-top: 1rem; }
.reset-actions { gap: .75rem; align-items: start; }
.reset-actions .button { width: auto; min-height: 2.5rem; padding: .5rem 1rem; }
.medico-scheda { container-type: inline-size; }
.medico-dati { grid-template-columns: repeat(8, minmax(0, 1fr)); gap: .75rem 1.25rem; }
.medico-dati > div { min-width: 0; grid-column: span 2; }
.medico-dati > .medico-email { grid-column: span 4; }
.medico-dati dd { overflow-wrap: anywhere; }
.medico-nota-accesso { margin-block: 1rem; }
@container (max-width: 48rem) { .medico-dati { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@container (max-width: 32rem) {
  .medico-dati { grid-template-columns: minmax(0, 1fr); }
  .medico-dati > div, .medico-dati > .medico-email { grid-column: auto; }
}
</style>
