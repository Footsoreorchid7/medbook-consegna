<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { adminStore } from '../admin/store.js'
import { fetchPazienti, errorePasswordPaziente } from '../api/pazientiDesk.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'

const filtri = reactive({ idPaziente: '', nome: '', cognome: '', email: '' })
const righe = ref([]), cursore = ref(null), applicati = ref({}), caricamento = ref(false), errore = ref(''), messaggio = ref('')
const successo = ref('')
const ticket = ref(null), scelto = ref(null), conferma = ref(null), password = ref(''), ripeti = ref(''), erroreModulo = ref('')
const titolo = ref(null), titoloConferma = ref(null), primoCampo = ref(null), avviso = ref(null)
let ctx = 0, lettura = 0, vivo = true, origine = null
const { session, idUtente } = useLetturaProfilo('desk', { azzera, ricarica: () => carica() })
const store = adminStore(session)
const ora = ref(Date.now())
const timer = setInterval(() => { ora.value = Date.now() }, 1000)
onBeforeUnmount(() => { vivo = false; ctx++; lettura++; pulisci(); clearInterval(timer) })
const attesa = computed(() => store.attesaResidua(ora.value))
const attuali = computed(() => !caricamento.value && ticket.value && store.valida('pazienti', ticket.value))
const incertezze = computed(() => idUtente() === null ? [] : store.state.incerte.filter(x => x.tipo === 'password-paziente'))
const bloccato = p => store.bloccata(`utente:${p.idPaziente}`)
const puo = p => idUtente() !== null && attuali.value && !session.state.busy && !session.state.passwordUncertain && !bloccato(p) && !attesa.value && !p.medico && !p.gestionale
const corrente = (c, e) => vivo && c === ctx && e === session.state.epoch && idUtente() !== null
const perso = () => !document.activeElement || document.activeElement === document.body || !document.activeElement.isConnected
function pulisci() { password.value = ''; ripeti.value = '' }
function chiudiLocale() { scelto.value = null; conferma.value = null; erroreModulo.value = ''; pulisci() }
function azzera() { ctx++; lettura++; righe.value = []; ticket.value = null; cursore.value = null; caricamento.value = false; errore.value = ''; messaggio.value = ''; successo.value = ''; chiudiLocale() }
watch(filtri, () => { ctx++; lettura++; caricamento.value = false; ticket.value = null; cursore.value = null; chiudiLocale(); messaggio.value = ''; successo.value = '' }, { flush: 'sync' })
watch(attuali, ok => { if (!ok) chiudiLocale() }, { flush: 'sync' })
async function carica(altre = false) {
  if (idUtente() === null || (altre && (!attuali.value || !cursore.value))) return null
  const query = altre ? { ...applicati.value } : Object.fromEntries(Object.entries(filtri).filter(([, v]) => v !== ''))
  if (query.idPaziente && !/^[1-9][0-9]{0,9}$/.test(query.idPaziente)) { errore.value = 'ID paziente non valido.'; return null }
  const after = altre ? cursore.value : null
  const c = ++ctx, e = session.state.epoch, n = ++lettura
  chiudiLocale(); caricamento.value = true; errore.value = ''; ticket.value = null
  const t = store.inizioLettura()
  try {
    const pagina = await fetchPazienti(session.protectedRequest, query, after)
    if (!corrente(c, e) || n !== lettura) return null
    if (!store.valida('pazienti', t)) { errore.value = 'Lettura superata: aggiorna l’elenco prima di operare.'; return c }
    righe.value = altre ? [...righe.value, ...pagina.risultati] : pagina.risultati
    cursore.value = pagina.prossimoDopoId; applicati.value = query; ticket.value = t
  } catch {
    if (!corrente(c, e) || n !== lettura) return null
    errore.value = 'Non è stato possibile caricare i pazienti. Puoi riprovare.'
  } finally { if (corrente(c, e) && n === lettura) caricamento.value = false }
  return c
}
async function aggiorna() {
  const e = session.state.epoch, c = await carica()
  await nextTick()
  if (c !== null && corrente(c, e) && perso()) (errore.value ? avviso.value : titolo.value)?.focus()
}
async function apri(p, event) {
  if (!puo(p)) return
  chiudiLocale(); scelto.value = { ...p }; origine = event.currentTarget
  const c = ++ctx, e = session.state.epoch
  await nextTick(); if (corrente(c, e)) primoCampo.value?.focus()
}
async function chiudi() {
  const c = ++ctx, e = session.state.epoch
  chiudiLocale(); await nextTick()
  if (corrente(c, e)) (origine?.isConnected && !origine.disabled ? origine : titolo.value)?.focus()
}
async function prosegui() {
  if (!scelto.value || !puo(scelto.value)) return
  erroreModulo.value = errorePasswordPaziente(password.value) || (password.value !== ripeti.value ? 'Le password non coincidono.' : '')
  if (erroreModulo.value) return
  conferma.value = { ...scelto.value }
  const c = ctx, e = session.state.epoch
  await nextTick(); if (corrente(c, e)) titoloConferma.value?.focus()
}
async function indietro() {
  conferma.value = null
  const c = ctx, e = session.state.epoch
  await nextTick(); if (corrente(c, e)) primoCampo.value?.focus()
}
async function invia() {
  const p = conferma.value
  if (!p || !puo(p)) return
  const c = ctx, e = session.state.epoch, segreto = password.value
  pulisci(); conferma.value = null
  const risultato = await store.reimpostaPasswordPaziente(p, segreto)
  if (!corrente(c, e)) return
  if (risultato.kind === 'eseguita') {
    chiudiLocale()
    const nuovo = await carica()
    if (nuovo === null || !corrente(nuovo, e)) return
    successo.value = `Password di ${p.nome} ${p.cognome} reimpostata. Le sessioni precedenti sono invalidate; email e profili non sono cambiati.`
  } else {
    messaggio.value = risultato.kind === 'incerto'
      ? 'Esito del reset incerto: la password potrebbe essere cambiata. Nessun reinvio automatico. Una lettura del paziente non può verificarlo.'
      : 'Reset non completato o non consentito. Aggiorna l’elenco e controlla lo stato della sessione prima di un nuovo tentativo.'
  }
  const finale = ctx
  await nextTick(); if (corrente(finale, e) && perso()) (successo.value && !errore.value ? titolo.value : avviso.value)?.focus()
}
async function nuovoReset(x) {
  if (!window.confirm('Il precedente reset resta incerto: una lettura non verifica la password. Consentire un nuovo reset, da compilare e confermare esplicitamente?')) return
  if (store.consentiNuovoResetPaziente(x.idUtente)) await aggiorna()
}
onMounted(() => carica())
</script>

<template>
  <h1>Pazienti</h1>
  <p>Elenco riservato alla segreteria. Cerca per ID esatto o per inizio di nome, cognome ed email; i filtri si combinano.</p>
  <section v-for="x in incertezze" :key="x.chiave" class="notice warning" role="status" data-reset-incerto>
    <h2>Reset da verificare fuori da MedBook · paziente n. {{ x.idUtente }}</h2>
    <p>{{ x.riepilogo.nome }} {{ x.riepilogo.cognome }} · {{ x.riepilogo.email }}. Esito incerto: la password potrebbe essere cambiata. La ricerca non prova quale password è salvata.</p>
    <button class="button secondary" type="button" @click="nuovoReset(x)">Consenti un nuovo reset</button>
  </section>
  <p v-if="attesa" class="notice warning" role="status">Attendi {{ attesa }} secondi prima di altre scritture. L’attesa non risolve eventuali esiti incerti.</p>
  <form class="card ricerca-pazienti" novalidate @submit.prevent="aggiorna">
    <div v-for="[k, label] in [['idPaziente','ID paziente'],['nome','Nome'],['cognome','Cognome'],['email','Email']]" :key="k" class="field">
      <label :for="`pazienti-${k}`">{{ label }}</label><input :id="`pazienti-${k}`" v-model="filtri[k]" type="text" :inputmode="k === 'idPaziente' ? 'numeric' : undefined" autocomplete="off" />
    </div>
    <div class="actions"><button class="button" type="submit" :disabled="caricamento">Cerca pazienti</button></div>
  </form>
  <NotificaInformativa v-if="successo" :notifica="successo" :destinazione="() => titolo" @close="successo = ''"><p>{{ successo }}</p></NotificaInformativa>
  <div ref="avviso" tabindex="-1" :role="errore ? 'alert' : 'status'" :class="['notice', errore ? 'error' : '']" v-if="errore || messaggio"><p>{{ errore }}</p><p>{{ messaggio }}</p></div>
  <h2 ref="titolo" tabindex="-1">Risultati</h2>
  <p v-if="caricamento" role="status">Caricamento pazienti…</p>
  <div v-if="!attuali && !caricamento" class="notice warning"><p>Le azioni sono sospese: aggiorna l’elenco prima di scegliere un paziente.</p><button class="button" @click="aggiorna">Riprova / Aggiorna elenco</button></div>
  <p v-if="attuali && !righe.length">Nessun paziente corrisponde alla ricerca.</p>
  <ul class="items">
    <li v-for="p in righe" :key="p.idPaziente" class="card">
      <h3>Paziente n. {{ p.idPaziente }} · {{ p.nome }} {{ p.cognome }}</h3>
      <p class="email-paziente">{{ p.email }}</p><p>Stato di accesso: {{ p.statoAccesso }}</p>
      <p v-if="p.medico">Account anche medico: il reset dell’intero account è riservato all’amministratore, dalla scheda medico.</p>
      <p v-else-if="p.gestionale">Account con ruolo gestionale: reset non consentito al desk.</p>
      <button v-else class="button secondary" type="button" :disabled="!puo(p)" @click="apri(p, $event)">Reimposta password</button>
      <section v-if="scelto?.idPaziente === p.idPaziente" class="reset-paziente" aria-labelledby="titolo-reset-paziente">
        <h4 id="titolo-reset-paziente">Reimposta password · {{ p.nome }} {{ p.cognome }} · n. {{ p.idPaziente }}</h4>
        <p>L’email rimane invariata. Dopo il successo tutte le sessioni del destinatario saranno invalidate. Il reset non riabilita account disabilitati.</p>
        <div v-if="conferma" class="confirm" role="group" aria-labelledby="conferma-reset-paziente">
          <h4 id="conferma-reset-paziente" :ref="el => titoloConferma = el" tabindex="-1">Confermi per {{ conferma.nome }} {{ conferma.cognome }} · n. {{ conferma.idPaziente }} · {{ conferma.email }}?</h4>
          <p>La password non viene mostrata. Cambierà la password dell’account e tutte le sessioni precedenti saranno invalidate.</p>
          <div class="actions"><button class="button" type="button" :disabled="!puo(p)" @click="invia">Conferma reset password</button><button class="button secondary" type="button" @click="indietro">Indietro</button><button class="button secondary" type="button" @click="chiudi">Chiudi</button></div>
        </div>
        <form v-else class="form" novalidate @submit.prevent="prosegui">
          <div class="field"><label for="paziente-password">Nuova password (8–1024 caratteri)</label><input id="paziente-password" :ref="el => primoCampo = el" v-model="password" type="password" autocomplete="new-password" :aria-invalid="!!erroreModulo" aria-describedby="errore-reset-paziente" /></div>
          <div class="field"><label for="paziente-ripeti">Ripeti nuova password</label><input id="paziente-ripeti" v-model="ripeti" type="password" autocomplete="new-password" :aria-invalid="!!erroreModulo" aria-describedby="errore-reset-paziente" /></div>
          <p id="errore-reset-paziente" role="alert">{{ erroreModulo }}</p>
          <div class="actions"><button class="button" type="submit" :disabled="!puo(p)">Prosegui</button><button class="button secondary" type="button" @click="chiudi">Chiudi</button></div>
        </form>
      </section>
    </li>
  </ul>
  <button v-if="cursore" type="button" class="button secondary" :disabled="!attuali" @click="carica(true)">Carica altri pazienti</button>
</template>
<style scoped>
.ricerca-pazienti { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1rem; }
.ricerca-pazienti .actions { grid-column: 1 / -1; }
.email-paziente { overflow-wrap: anywhere; }
.reset-paziente { margin-top: 1rem; }
@media (max-width: 55rem) { .ricerca-pazienti { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 35rem) { .ricerca-pazienti { grid-template-columns: minmax(0, 1fr); } }
</style>
