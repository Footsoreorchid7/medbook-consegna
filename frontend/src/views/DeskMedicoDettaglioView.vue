<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { HttpError, NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { fetchMedicoDesk } from '../api/desk.js'
import { parseIdRisorsa } from '../api/prenotazioni.js'
import { nome } from '../desk/formato.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'
import { adminStore } from '../admin/store.js'
import { validaProfiloPaziente } from '../utils/validazione.js'
import { formatEuro } from '../utils/money.js'
import NotificaInformativa from '../components/NotificaInformativa.vue'

const route = useRoute()
const lettura = reactive({ stato: 'caricamento', dati: null, errore: '' })
const id = computed(() => parseIdRisorsa(route.params.id))
const ticket = ref(null), aperto = ref(false), conferma = ref(null), errori = ref({}), avviso = ref(''), successo = ref('')
const campi = reactive({ codiceFiscale: '', dataNascita: '', telefono: '' })
const apriButton = ref(null), primoCampo = ref(null), titolo = ref(null), titoloConferma = ref(null), boxAvviso = ref(null)
let ctx = 0, vivo = true
const { session, nuovaLettura, attuale, idUtente } = useLetturaProfilo('desk', {
  azzera() { ctx++; chiudiLocale(); ticket.value = null; lettura.dati = null; lettura.stato = 'caricamento'; avviso.value = ''; successo.value = '' },
  ricarica: () => leggi(),
})
const store = adminStore(session)
const ora = ref(Date.now()), timer = setInterval(() => { ora.value = Date.now() }, 1000)
onBeforeUnmount(() => { vivo = false; ctx++; chiudiLocale(); clearInterval(timer) })
const attesa = computed(() => store.attesaResidua(ora.value))
const inInvio = computed(() => store.state.inCorso.includes(`utente:${id.value}`))
const aggiornata = computed(() => lettura.stato === 'pronto' && store.profiloPazienteValido(id.value, ticket.value))
const incerta = computed(() => idUtente() === null ? null : store.incerta(`utente:${id.value}`))
const linkIncerto = computed(() => incerta.value?.tipo === 'collega-paziente' ? incerta.value : null)
const puoAgire = computed(() => idUtente() !== null && aggiornata.value && !lettura.dati?.paziente && !store.bloccata(`utente:${id.value}`)
  && !session.state.busy && !session.state.passwordUncertain && !attesa.value)
const corrente = (c,e) => vivo && ctx === c && session.state.epoch === e && idUtente() !== null
const perso = () => !document.activeElement || document.activeElement === document.body || !document.activeElement.isConnected
function chiudiLocale() { aperto.value = false; conferma.value = null; errori.value = {}; Object.assign(campi,{ codiceFiscale: '', dataNascita: '', telefono: '' }) }
watch(aggiornata, ok => { if (!ok) chiudiLocale() }, { flush: 'sync' })
watch(campi, () => { conferma.value = null }, { flush: 'sync' })
async function leggi() {
  const c = ++ctx, e = session.state.epoch, mia = nuovaLettura(), target = id.value
  chiudiLocale(); ticket.value = null; lettura.dati = null
  if (target === null) { lettura.dati = null; lettura.stato = 'non-trovato'; return null }
  lettura.stato = 'caricamento'
  const t = store.inizioLettura()
  try {
    const dati = await fetchMedicoDesk(session.protectedRequest, target)
    if (!attuale(mia) || !corrente(c,e)) return null
    if (!store.registraProfiloPaziente(target,dati.paziente,t)) { lettura.stato = 'superata'; return c }
    Object.assign(lettura,{ stato: 'pronto', dati, errore: '' });ticket.value = t
  } catch (error) {
    if (!attuale(mia) || !corrente(c,e) || error instanceof StaleResponseError) return null
    if (error instanceof HttpError && error.status === 404) { lettura.dati = null; lettura.stato = 'non-trovato'; return c }
    lettura.errore = error instanceof NetworkError ? 'Il server non è raggiungibile.' : error instanceof ProtocolError ? 'Risposta non valida.' : 'Il server ha restituito un errore.'
    lettura.stato = 'errore'
  }
  return c
}
async function aggiorna() {
  const e = session.state.epoch, c = await leggi();await nextTick()
  if (c !== null && corrente(c,e) && perso()) titolo.value?.focus()
}
async function apri() {
  if (!puoAgire.value) return
  chiudiLocale();aperto.value = true
  const c = ++ctx, e = session.state.epoch
  await nextTick();if (corrente(c,e)) primoCampo.value?.focus()
}
async function chiudi() {
  const c = ++ctx,e = session.state.epoch;chiudiLocale();await nextTick()
  if (corrente(c,e)) (apriButton.value && !apriButton.value.disabled ? apriButton.value : titolo.value)?.focus()
}
async function prosegui() {
  if (!aperto.value || !puoAgire.value) return
  errori.value = validaProfiloPaziente(campi)
  if (Object.keys(errori.value).length) return
  conferma.value = Object.freeze({ medico: { ...lettura.dati.medico }, dati: { ...campi }, ticket: ticket.value })
  const c = ctx,e = session.state.epoch
  await nextTick();if (corrente(c,e)) titoloConferma.value?.focus()
}
async function indietro() {
  conferma.value = null;const c = ctx,e = session.state.epoch;await nextTick();if (corrente(c,e)) primoCampo.value?.focus()
}
async function invia() {
  const a = conferma.value
  if (!a || !puoAgire.value || !store.profiloPazienteValido(id.value,a.ticket)) return
  const c = ctx,e = session.state.epoch
  conferma.value = null
  const r = await store.collegaPaziente(a.medico,a.dati,a.ticket)
  if (!corrente(c,e)) return
  if (r.kind === 'eseguita') {
    const nuovo = await leggi();if (nuovo === null || !corrente(nuovo,e)) return
    successo.value = 'Profilo paziente aggiunto allo stesso account. ID e credenziali invariati; il nuovo accesso rileggerà i profili senza riattivare account disabilitati o medici inattivi.'
  } else {
    avviso.value = r.kind === 'incerto' ? 'Esito incerto: il profilo potrebbe essere stato aggiunto. Non reinviare automaticamente; usa la verifica manuale.'
      : r.kind === 'conflitto' ? (r.serverMessage || 'Profilo già presente o codice fiscale già utilizzato. Nessun dato sovrascritto.')
      : 'Operazione non consentita o non completata. Aggiorna il dettaglio prima di riprovare.'
    if (['conflitto','non-trovata'].includes(r.kind)) { const nuovo = await leggi();if (nuovo === null || !corrente(nuovo,e)) return }
  }
  const finale = ctx;await nextTick()
  if (corrente(finale,e) && perso()) (successo.value ? titolo.value : boxAvviso.value)?.focus()
}
async function verifica() {
  const c = ctx,e = session.state.epoch
  let r = await store.verificaCollegamentoPaziente(id.value)
  if (!corrente(c,e)) return
  const correnteProfilo = store.profiloPazienteCorrente(id.value)
  if ((r.kind === 'profilo-presente' && correnteProfilo?.presente !== true)
      || (r.kind === 'profilo-assente' && correnteProfilo?.presente !== false)) r = { kind: 'superata' }
  avviso.value = r.kind === 'profilo-presente' ? 'Il profilo paziente risulta ora presente. Questo riscontro non prova chi lo abbia aggiunto o l’esito dello specifico tentativo.'
    : r.kind === 'profilo-assente' ? 'Il profilo non risulta presente nella lettura corrente: non è una prova del fallimento del tentativo. Puoi scegliere esplicitamente un nuovo invio.'
    : r.kind === 'superata' ? 'Verifica superata da informazioni più recenti. Il blocco resta: ripeti manualmente la verifica.'
    : 'Verifica non completata. Il blocco resta e puoi riprovare.'
  if (['profilo-presente','profilo-assente'].includes(r.kind)) { const nuovo = await leggi();if (nuovo === null || !corrente(nuovo,e)) return }
  const finale = ctx;await nextTick();if (corrente(finale,e) && perso()) boxAvviso.value?.focus()
}
async function nuovoTentativo() {
  if (!window.confirm('L’assenza del profilo non prova il fallimento precedente. Consentire un nuovo tentativo da compilare e confermare, senza reinvio automatico?')) return
  if (store.consentiNuovoCollegamentoPaziente(id.value)) await aggiorna()
}
watch(() => route.params.id, () => { avviso.value = ''; successo.value = ''; leggi() }, { immediate: true, flush: 'sync' })
</script>

<template>
  <p><RouterLink to="/desk/medici">Torna ai medici</RouterLink></p>
  <h1 ref="titolo" tabindex="-1">{{ lettura.dati ? nome(lettura.dati.medico) : 'Dettaglio medico' }}</h1>
  <NotificaInformativa v-if="successo" :notifica="successo" :destinazione="() => titolo" @close="successo = ''"><p>{{ successo }}</p></NotificaInformativa>
  <div v-if="avviso" ref="boxAvviso" tabindex="-1" class="notice warning" role="status"><p>{{ avviso }}</p></div>
  <section v-if="linkIncerto" class="notice warning" role="status" data-collegamento-incerto>
    <h2>Collegamento dall’esito incerto · utente n. {{ id }}</h2>
    <p>Nessun reinvio automatico. Verifica soltanto la presenza corrente del profilo, senza attribuirla al tentativo.</p>
    <div class="actions"><button class="button" :disabled="linkIncerto.verifica === 'in-corso'" @click="verifica">Verifica profilo paziente</button>
      <button v-if="linkIncerto.verifica === 'assente-non-conclusiva'" class="button secondary" @click="nuovoTentativo">Consenti un nuovo collegamento</button></div>
  </section>
  <p v-if="attesa" class="notice warning">Attendi {{ attesa }} secondi prima di nuove scritture. L’attesa non risolve un esito incerto.</p>
  <p v-if="lettura.stato === 'caricamento'" role="status">Caricamento del medico…</p>
  <div v-else-if="lettura.stato === 'non-trovato'" class="notice error" role="alert"><p>Medico non trovato.</p></div>
  <div v-else-if="lettura.stato === 'errore'" class="notice error" role="alert"><p>Non è stato possibile caricare il medico. {{ lettura.errore }}</p><button class="button" @click="aggiorna">Riprova</button></div>
  <div v-if="lettura.stato === 'superata' || (lettura.stato === 'pronto' && !aggiornata)" class="notice warning"><p>Dati non aggiornati: le azioni sono sospese.</p><button class="button" @click="aggiorna">Aggiorna il dettaglio</button></div>
  <template v-if="lettura.dati && lettura.stato !== 'caricamento'">
    <section class="card" aria-labelledby="titolo-dati-medico">
      <h2 id="titolo-dati-medico">Dati professionali</h2>
      <dl class="facts">
        <div><dt>Specializzazione</dt><dd data-campo="specializzazione">{{ lettura.dati.medico.specializzazione ?? 'Non indicata' }}</dd></div>
        <div><dt>Iscrizione all'ordine</dt><dd data-campo="iscrizione">{{ lettura.dati.medico.numeroIscrizioneOrdine ?? 'Non indicata' }}</dd></div>
        <div><dt>Stato</dt><dd data-campo="stato">{{ lettura.dati.medico.stato === 'ATTIVO' ? 'Attivo' : 'Inattivo' }}</dd></div>
        <div><dt>Profilo paziente</dt><dd data-campo="paziente">{{ lettura.dati.paziente ? 'Presente' : 'Non presente nella lettura' }}</dd></div>
      </dl>
      <p v-if="lettura.dati.paziente">L’account ha entrambi i profili e mantiene le stesse credenziali. Il desk non può reimpostarne la password.</p>
      <button v-else-if="aggiornata" ref="apriButton" class="button secondary" :disabled="!puoAgire" @click="apri">Aggiungi profilo paziente</button>
      <section v-if="aperto" class="collega-paziente" aria-labelledby="titolo-collega-paziente">
        <h3 id="titolo-collega-paziente">Aggiungi profilo paziente · {{ nome(lettura.dati.medico) }} · utente n. {{ id }}</h3>
        <p>Stesso account: nome, cognome, email e password rimangono invariati. Il collegamento non riattiva account disabilitati o medici inattivi.</p>
        <p v-if="inInvio" role="status">Collegamento in corso… Le modifiche sono sospese.</p>
        <form v-if="!conferma" class="form" novalidate @submit.prevent="prosegui">
          <div class="field"><label for="profilo-cf">Codice fiscale *</label><input id="profilo-cf" ref="primoCampo" v-model="campi.codiceFiscale" :disabled="inInvio" autocomplete="off" :aria-invalid="!!errori.codiceFiscale" :aria-describedby="errori.codiceFiscale ? 'errore-profilo-cf' : undefined" /><p v-if="errori.codiceFiscale" id="errore-profilo-cf" class="field-error">{{ errori.codiceFiscale }}</p></div>
          <div class="field"><label for="profilo-nascita">Data di nascita *</label><input id="profilo-nascita" v-model="campi.dataNascita" :disabled="inInvio" type="date" min="1000-01-01" :aria-invalid="!!errori.dataNascita" :aria-describedby="errori.dataNascita ? 'errore-profilo-nascita' : undefined" /><p v-if="errori.dataNascita" id="errore-profilo-nascita" class="field-error">{{ errori.dataNascita }}</p></div>
          <div class="field"><label for="profilo-telefono">Telefono (facoltativo)</label><input id="profilo-telefono" v-model="campi.telefono" :disabled="inInvio" type="tel" autocomplete="off" :aria-invalid="!!errori.telefono" :aria-describedby="errori.telefono ? 'errore-profilo-telefono' : undefined" /><p v-if="errori.telefono" id="errore-profilo-telefono" class="field-error">{{ errori.telefono }}</p></div>
          <div class="actions"><button class="button" type="submit" :disabled="!puoAgire">Prosegui</button><button class="button secondary" type="button" @click="chiudi">Chiudi</button></div>
        </form>
        <div v-else class="confirm" role="group" aria-labelledby="titolo-conferma-profilo">
          <h4 id="titolo-conferma-profilo" ref="titoloConferma" tabindex="-1">Confermi per {{ nome(conferma.medico) }} · utente n. {{ conferma.medico.idUtente }}?</h4>
          <p>Codice fiscale: {{ conferma.dati.codiceFiscale }} · Data di nascita: {{ conferma.dati.dataNascita }} · Telefono: {{ conferma.dati.telefono || 'Non indicato' }}</p>
          <p>Verrà aggiunto soltanto il profilo paziente. Credenziali, stati e abilitazioni rimangono invariati.</p>
          <div class="actions"><button class="button" :disabled="!puoAgire" @click="invia">Conferma aggiunta profilo</button><button class="button secondary" @click="indietro">Indietro</button><button class="button secondary" @click="chiudi">Chiudi</button></div>
        </div>
      </section>
    </section>
    <section class="card" aria-labelledby="titolo-abilitazioni"><h2 id="titolo-abilitazioni">Prestazioni autorizzate</h2>
      <p v-if="lettura.dati.prestazioni.length === 0">Nessuna prestazione autorizzata.</p>
      <ul v-else class="links" data-abilitazioni><li v-for="p in lettura.dati.prestazioni" :key="p.idPrestazione">{{ p.nome }} — {{ p.durata }} minuti, {{ formatEuro(p.prezzo) }}</li></ul>
    </section>
  </template>
</template>
<style scoped>
.collega-paziente { margin-top: 1rem; }
</style>
