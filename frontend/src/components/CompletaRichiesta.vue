<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchDisponibilitaPrenotabili } from '../api/catalogo.js'
import { StaleResponseError } from '../api/http.js'
import { sceltaStore } from '../prenotazioni/scelta.js'
import { dataOrario, etichettaStato } from '../prenotazioni/formato.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import { formatEuro } from '../utils/money.js'

const emit = defineEmits(['rileggi'])
const session = useSession()
const scelta = sceltaStore(session)
const store = usePrenotazioni()
const slot = ref(null)
const fase = ref('inattiva')
const messaggio = ref('')
const messaggioBox = ref(null)
const titolo = ref(null)
let sequenza = 0, controller = null, smontato = false
const selezione = computed(() => scelta.state.scelta)
const paziente = () => session.state.status === STATO.AUTENTICATO && session.capabilities.value.paziente
const bloccata = computed(() => !paziente() || session.state.busy || session.state.passwordUncertain
  || store.state.invioInCorso !== null || (selezione.value && store.richiestaBloccata(selezione.value.idDisponibilita)))
const ricerca = computed(() => ({ name: 'disponibilita', query: selezione.value?.prestazioneId ? { prestazioneId: String(selezione.value.prestazioneId) } : {} }))
const firma = s => JSON.stringify([s.idDisponibilita, s.idPrestazione, s.idMedico, s.nomePrestazione, s.nomeMedico, s.cognomeMedico, s.inizio, s.fine, s.prezzo])

function contesto() {
  const numero = ++sequenza, epoca = session.state.epoch, originale = selezione.value
  controller?.abort(); controller = new AbortController()
  const signal = controller.signal
  return { originale, epoca, signal, attuale: () => !smontato && numero === sequenza && epoca === session.state.epoch && selezione.value === originale && paziente() }
}
async function leggi(ctx) {
  const s = ctx.originale
  const voci = await fetchDisponibilitaPrenotabili({ prestazioneId: s.prestazioneId, data: s.data }, { signal: ctx.signal, isCurrent: ctx.attuale })
  if (!ctx.attuale()) return null
  return voci.find(x => x.idDisponibilita === s.idDisponibilita && (!s.prestazioneId || x.idPrestazione === s.prestazioneId) && (!s.data || x.inizio.date === s.data)) ?? null
}
async function focalizza(ctx) {
  await nextTick()
  if (!ctx.attuale()) return
  const active = document.activeElement
  if (!active || active === document.body || !active.isConnected) (messaggioBox.value ?? titolo.value)?.focus()
}
async function carica({ focus = false } = {}) {
  if (!selezione.value || !paziente()) return
  const ctx = contesto()
  slot.value = null; messaggio.value = ''; fase.value = 'caricamento'
  try {
    const s = await leggi(ctx)
    if (!ctx.attuale()) return
    slot.value = s; fase.value = s ? 'pronta' : 'assente'
  } catch (error) {
    if (!ctx.attuale() || error instanceof StaleResponseError) return
    fase.value = 'errore'; messaggio.value = 'Non è stato possibile verificare l’orario scelto. Riprova senza inviare la richiesta.'
  }
  if (focus && ctx.attuale()) await focalizza(ctx)
}

/** Anche il clic di conferma esegue prima una GET: dati cambiati richiedono una nuova conferma. */
async function invia() {
  if (fase.value !== 'pronta' || !slot.value || bloccata.value) return
  const confermato = slot.value
  const idPaziente = session.state.user.idUtente
  const ctx = contesto()
  fase.value = 'verifica-invio'; messaggio.value = ''
  let fresco
  try { fresco = await leggi(ctx) } catch (error) {
    if (!ctx.attuale() || error instanceof StaleResponseError) return
    fase.value = 'errore'; messaggio.value = 'Verifica non riuscita: nessuna richiesta è stata inviata. Riprova la lettura.'
    await focalizza(ctx); return
  }
  if (!ctx.attuale()) return
  if (!fresco) { slot.value = null; fase.value = 'assente'; await focalizza(ctx); return }
  if (firma(fresco) !== firma(confermato)) {
    slot.value = fresco; fase.value = 'pronta'
    messaggio.value = 'I dati dell’orario sono cambiati. Controlla il riepilogo aggiornato e conferma di nuovo.'
    await focalizza(ctx); return
  }
  if (bloccata.value) { fase.value = 'pronta'; return }
  fase.value = 'invio'
  const esito = await store.richiedi(fresco)
  // Il successo certo resta condiviso anche se la vista è stata smontata. La memoria
  // della scelta verifica ancora epoca/identità e non cancella una scelta successiva.
  if (esito.kind === 'inviata') {
    scelta.creata(ctx.originale, esito.prenotazione.idPrenotazione, ctx.epoca, idPaziente)
    return
  }
  if (!ctx.attuale()) return
  if (esito.kind === 'incerto') {
    fase.value = 'pronta'; messaggio.value = 'L’esito è incerto: il server potrebbe aver registrato la richiesta. Usa la verifica manuale qui sotto, senza ripetere l’invio.'
  } else if (['conflitto', 'non-trovata'].includes(esito.kind)) {
    fase.value = 'assente'; slot.value = null
  } else {
    fase.value = 'pronta'
    messaggio.value = esito.kind === 'password-incerta' ? 'Verifica prima l’esito del cambio password.' : 'La richiesta non può essere inviata. Controlla lo stato della sessione e riprova solo quando consentito.'
  }
  await focalizza(ctx)
}

async function abbandona() {
  const epoca = session.state.epoch
  scelta.cancella()
  const mia = sequenza
  await nextTick()
  if (!smontato && mia === sequenza && session.state.epoch === epoca && !selezione.value && document.activeElement === document.body) {
    document.getElementById('titolo-elenco')?.focus()
  }
}

const incerte = computed(() => store.state.richiesteIncerte)
const riscontro = ref(null)
async function verifica(r) {
  const epoca = session.state.epoch, mia = sequenza
  const esito = await store.verificaRichiesta(r.idDisponibilita)
  if (smontato || epoca !== session.state.epoch || mia !== sequenza || !paziente()) return
  if (esito.kind === 'trovata') {
    riscontro.value = { id: esito.prenotazione.idPrenotazione, stato: esito.prenotazione.stato }
    emit('rileggi')
  }
}
function ripeti(r) {
  if (!paziente() || session.state.busy || store.state.invioInCorso !== null
      || !['assente', 'solo-chiuse'].includes(store.richiestaIncerta(r.idDisponibilita)?.verifica)) return
  scelta.scegli({ idDisponibilita: r.idDisponibilita, prestazioneId: r.riepilogo.idPrestazione ?? null, data: r.riepilogo.inizio.date })
}
watch(() => [selezione.value, session.state.epoch], () => {
  sequenza += 1; controller?.abort(); slot.value = null; fase.value = 'inattiva'; messaggio.value = ''; riscontro.value = null
  // Una rotazione aggiorna identità/stato nello stesso passo sincrono.
  const mia = sequenza
  queueMicrotask(() => { if (!smontato && mia === sequenza && selezione.value && paziente()) carica() })
}, { flush: 'sync', immediate: true })
onBeforeUnmount(() => { smontato = true; sequenza += 1; controller?.abort() })
</script>

<template>
  <section v-if="selezione && paziente()" class="card" aria-labelledby="completa-richiesta" data-completa-richiesta :aria-busy="['caricamento', 'verifica-invio', 'invio'].includes(fase) ? 'true' : 'false'">
    <h2 id="completa-richiesta" ref="titolo" tabindex="-1">Completa la richiesta</h2>
    <p>Hai scelto un solo appuntamento. L’invio richiede la tua conferma; la segreteria dovrà poi valutarlo.</p>
    <p v-if="['caricamento', 'verifica-invio'].includes(fase)" role="status">Verifica della disponibilità in corso…</p>
    <div v-if="messaggio || fase === 'assente'" ref="messaggioBox" class="notice warning" role="alert" tabindex="-1">
      <p v-if="fase === 'assente'">Lo slot scelto non è più disponibile oppure non può essere richiesto. Torna alla ricerca per scegliere un altro orario.</p>
      <p v-else>{{ messaggio }}</p>
    </div>
    <dl v-if="slot" class="facts testo-dati">
      <div><dt>Prestazione</dt><dd>{{ slot.nomePrestazione }}</dd></div>
      <div><dt>Medico</dt><dd>{{ slot.nomeMedico }} {{ slot.cognomeMedico }}</dd></div>
      <div><dt>Data e ora della struttura</dt><dd>{{ dataOrario(slot.inizio, slot.fine) }}</dd></div>
      <div><dt>Prezzo</dt><dd>{{ formatEuro(slot.prezzo) }}</dd></div>
    </dl>
    <p v-if="session.state.passwordUncertain" class="notice warning">Le richieste sono sospese. <RouterLink to="/area/password">Verifica il cambio password</RouterLink>.</p>
    <p v-if="store.state.invioInCorso !== null" role="status">Una richiesta di appuntamento è in corso di invio.</p>
    <div class="actions">
      <button v-if="slot" type="button" class="button" :disabled="fase !== 'pronta' || bloccata" @click="invia">{{ fase === 'invio' ? 'Invio in corso…' : 'Conferma e invia la richiesta' }}</button>
      <button v-if="fase === 'errore'" type="button" class="button secondary" @click="carica({ focus: true })">Riprova la verifica</button>
      <RouterLink class="button secondary" :to="ricerca" @click="scelta.cancella()">Torna alla ricerca</RouterLink>
      <button v-if="fase !== 'invio'" type="button" class="link-button" @click="abbandona">Abbandona la scelta</button>
    </div>
  </section>
  <p v-if="riscontro && paziente()" class="notice" role="status">Per l’orario verificato risulta una tua prenotazione nello stato {{ etichettaStato(riscontro.stato) }}; questo non attribuisce l’esito al tentativo. <RouterLink :to="`/paziente/prenotazioni/${riscontro.id}`">Prenotazione n. {{ riscontro.id }}</RouterLink></p>
  <section v-if="incerte.length && paziente()" class="card" aria-labelledby="completa-incerte">
    <h2 id="completa-incerte">Richieste da verificare</h2>
    <div v-for="r in incerte" :key="r.idDisponibilita" class="notice warning" role="alert" data-completa-incerta>
      <p><strong>Esito della richiesta non determinato.</strong> {{ r.riepilogo.nomePrestazione }}, {{ dataOrario(r.riepilogo.inizio, r.riepilogo.fine) }}. Il server potrebbe averla registrata.</p>
      <p v-if="r.verifica === 'assente' || r.verifica === 'solo-chiuse'">La verifica non ha trovato una prenotazione attiva per lo slot. Questo non prova che l’invio sia fallito. Un nuovo tentativo richiede una nuova scelta, riverifica e conferma.</p>
      <p v-if="r.verifica === 'errore'">Verifica non riuscita. Puoi riprovare manualmente.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="r.verifica === 'in-corso' || session.state.busy" @click="verifica(r)">Verifica nelle mie prenotazioni</button>
        <button v-if="r.verifica === 'assente' || r.verifica === 'solo-chiuse'" type="button" class="button secondary" :disabled="store.state.invioInCorso !== null || session.state.busy" @click="ripeti(r)">Scegli di nuovo questo slot</button>
      </div>
    </div>
  </section>
</template>
