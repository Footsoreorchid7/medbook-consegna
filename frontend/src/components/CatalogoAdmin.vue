<script setup>
import NotificaInformativa from './NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import { StaleResponseError } from '../api/http.js'
import { TESTO_ERRORE_VERIFICA, TESTO_SUPERATA, testoEsito } from '../admin/formato.js'
import { usePaginaAdmin } from '../admin/usePaginaAdmin.js'
import { useModuloModifica } from '../admin/useModuloModifica.js'

/**
 * Pagina amministrativa di un catalogo : elenco completo, creazione, modifica completa (PUT)
 * ed esiti da verificare. Unico riscontro: l'elenco completo (nessuna lettura singola).
 * Dati visibili e dati idonei alle azioni sono distinti: un esito certo successivo alla lettura
 * (anche da un'istanza precedente) sospende le azioni e chiude le conferme.
 */
const props = defineProps({ config: { type: Object, required: true } })
const c = props.config
const titoloElenco = ref(null)
const p = usePaginaAdmin({ azzera, ricarica: () => leggi(), titoloRiserva: () => titoloElenco.value })
const { store, session, avviso, avvisoBox, messaggioLettura, attesa, puoScrivere, azione, libero, apri, annulla, chiudi, impostaTitolo } = p
const campiK = c.campi.map((x) => x.k)

const focusPerso = () => !document.activeElement || document.activeElement === document.body || !document.activeElement.isConnected
async function mostra(ctx, tipo, testo) {
  if (!p.corrente(ctx)) return
  avviso.value = { tipo, testo }
  await nextTick()
  if (tipo !== 'successo' && p.corrente(ctx) && focusPerso()) avvisoBox.value?.focus()
}

// ---------------------------------------------------------------- elenco

const VUOTO = () => ({ stato: 'caricamento', voci: [], errore: '', aggiornata: false, superata: false })
const elenco = reactive(VUOTO())
const ticket = shallowRef(null)
let seq = 0
async function leggi() {
  if (p.idAdmin() === null) return
  const ctx = p.contestoCorrente()
  const mia = ++seq
  Object.assign(elenco, { errore: '', aggiornata: false, superata: false })
  if (elenco.stato !== 'pronto') elenco.stato = 'caricamento'
  const t = store.inizioLettura()
  try {
    const voci = await c.leggi(session.protectedRequest)
    if (mia !== seq || !p.corrente(ctx)) return
    if (!store.valida(c.ambito, t)) {
      elenco.superata = true
      if (elenco.stato !== 'pronto') elenco.stato = 'superata'
      return
    }
    Object.assign(elenco, { stato: 'pronto', voci, aggiornata: true })
    ticket.value = t
    store.registraCatalogo(c.ambito, voci, t)
    if (modulo.stato.id !== null) modulo.precompila(voci.find((v) => c.idDi(v) === modulo.stato.id))
  } catch (error) {
    if (mia !== seq || !p.corrente(ctx) || error instanceof StaleResponseError) return
    Object.assign(elenco, { stato: 'errore', errore: messaggioLettura(error) })
  }
}
const valido = () => elenco.stato === 'pronto' && elenco.aggiornata && store.valida(c.ambito, ticket.value)
const invalidato = computed(() => elenco.stato === 'pronto' && elenco.aggiornata && !store.valida(c.ambito, ticket.value))
const voceDi = (id) => elenco.voci.find((v) => c.idDi(v) === id) ?? null
const bloccata = (v) => store.bloccata(c.chiaveRisorsa(c.idDi(v)))
const lista = (e) => Object.values(e)
const descr = (e, k, id) => (e[k] ? id : undefined)

// ---------------------------------------------------------------- creazione

const nuovoAperto = ref(false)
const pulsanteNuovo = ref(null)
let contestoNuovo = 0
async function apriNuovo() {
  if (nuovoAperto.value) return
  const ctx = p.contestoCorrente(), mia = ++contestoNuovo
  nuovoAperto.value = true
  await nextTick()
  if (p.corrente(ctx) && mia === contestoNuovo && nuovoAperto.value)
    (document.querySelector(`#${c.chiave}-nuovo-${campiK[0]}`) ?? document.querySelector(`#titolo-nuovo-${c.chiave}`))?.focus()
}
async function chiudiNuovo() {
  const ctx = p.contestoCorrente(), mia = ++contestoNuovo
  nuovoAperto.value = false
  if (azione.value?.tipo === 'crea' && azione.value.fase !== 'invio') chiudi()
  await nextTick()
  if (p.corrente(ctx) && mia === contestoNuovo && !nuovoAperto.value) pulsanteNuovo.value?.focus()
}
const nuovo = reactive(Object.fromEntries(campiK.map((k) => [k, ''])))
const erroriNuovo = ref({})
const riepilogoNuovo = ref(null)
async function proseguiNuovo() {
  if (!nuovoAperto.value || !libero.value || !puoScrivere.value) return
  const { dati, errori } = c.valida(nuovo)
  erroriNuovo.value = errori
  if (dati && store.bloccata(c.chiaveNuova(dati))) {
    erroriNuovo.value = { [campiK[0]]: `Un tentativo precedente con questi dati è ancora in corso o da verificare: verificalo prima di riprovare.` }
  }
  if (Object.keys(erroriNuovo.value).length) {
    await nextTick()
    riepilogoNuovo.value?.focus()
    return
  }
  await apri({ tipo: 'crea', dati, modulo: contestoNuovo, valori: Object.freeze({ ...nuovo }), idonea: () => !store.bloccata(c.chiaveNuova(dati)) }, `crea-${c.chiave}`)
}
async function confermaNuovo() {
  const a = azione.value
  if (!nuovoAperto.value || a?.tipo !== 'crea' || a.fase !== 'conferma' || !puoScrivere.value || !a.idonea()) return
  const ctx = p.contestoCorrente()
  // ID già noti nell'elenco valido al momento dell'invio: distinguono un'eventuale nuova corrispondenza.
  const noti = valido() ? elenco.voci.map(c.idDi) : null
  azione.value = { ...a, fase: 'invio' }
  const esito = await c.crea(store, a.dati, noti)
  if (!p.corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata'].includes(esito.kind)) leggi()
  chiudi()
  if (esito.kind === 'eseguita') {
    const chiudibile = nuovoAperto.value && a.modulo === contestoNuovo && campiK.every(k => nuovo[k] === a.valori[k])
    if (chiudibile) {
      Object.assign(nuovo, Object.fromEntries(campiK.map((k) => [k, ''])))
      erroriNuovo.value = {}
      nuovoAperto.value = false
    }
    const generazione = contestoNuovo
    await mostra(ctx, 'successo', `${c.successo(esito.risultato, true)} L'elenco viene riletto.`)
    if (chiudibile && p.corrente(ctx) && generazione === contestoNuovo && !nuovoAperto.value && focusPerso()) pulsanteNuovo.value?.focus()
  } else if (!['sessione-assente', 'obsoleto'].includes(esito.kind)) {
    await mostra(ctx, 'errore', testoErrore(esito))
  }
}
const testoErrore = (esito) => (esito.kind === 'conflitto' ? `${testoEsito(esito)} ${c.conflitto}` : testoEsito(esito))

// ---------------------------------------------------------------- modifica

const modulo = useModuloModifica(campiK, c.daRisorsa)
const erroriModifica = ref({})
async function apriModifica(v) {
  if (!libero.value) return
  modulo.apri(c.idDi(v), v)
  erroriModifica.value = {}
  await nextTick()
  document.querySelector(`#${c.chiave}-modifica-${campiK[0]}`)?.focus()
}
async function chiudiModifica() {
  if (azione.value?.tipo === 'modifica') return
  const id = modulo.stato.id
  modulo.chiudi()
  erroriModifica.value = {}
  await nextTick()
  document.querySelector(`[data-origine="modifica-${c.chiave}-${id}"]`)?.focus()
}
const puoModificare = (v) => puoScrivere.value && valido() && !bloccata(v)
async function proseguiModifica() {
  const v = voceDi(modulo.stato.id)
  if (!v || !puoModificare(v) || !libero.value) return
  const { dati, errori } = c.valida(modulo.valori)
  // Un campo modificato il cui valore sul server è cambiato va ricontrollato prima di confermare.
  Object.assign(errori, modulo.conflitti(v, (ora) => `Il valore sul server è cambiato durante la modifica (ora «${ora}»): controlla il campo e prosegui di nuovo.`))
  erroriModifica.value = errori
  if (Object.keys(errori).length) {
    await nextTick()
    document.querySelector(`[data-errori-modifica="${c.chiave}"]`)?.focus()
    return
  }
  await apri({ tipo: 'modifica', voce: v, dati, tentativo: modulo.istantanea(), idonea: () => valido() && !bloccata(v) && c.versione(voceDi(c.idDi(v)) ?? {}, v) }, `prosegui-${c.chiave}`)
}
async function confermaModifica() {
  const a = azione.value
  if (a?.tipo !== 'modifica' || a.fase !== 'conferma' || !puoScrivere.value || !a.idonea()) return
  const ctx = p.contestoCorrente()
  const snap = a.tentativo
  if (modulo.contesto() !== snap.contesto) return
  azione.value = { ...a, fase: 'invio' }
  const esito = await c.aggiorna(store, a.voce, a.dati)
  if (!p.corrente(ctx)) return
  const stessoModulo = modulo.contesto() === snap.contesto
  const chiudibile = stessoModulo && campiK.every(k => modulo.valori[k] === snap.valori[k])
  if (esito.kind === 'eseguita' && stessoModulo) {
    modulo.riallinea(snap, esito.risultato)
    elenco.voci = elenco.voci.map(v => c.idDi(v) === c.idDi(esito.risultato) ? esito.risultato : v)
    if (chiudibile) { modulo.chiudi(); erroriModifica.value = {} }
  }
  if (['eseguita', 'conflitto', 'non-trovata'].includes(esito.kind)) leggi()
  chiudi()
  // Modulo cambiato nel frattempo (altra risorsa, A → B → A): nessun messaggio né focus tardivo.
  if (!stessoModulo || ['sessione-assente', 'obsoleto'].includes(esito.kind)) return
  if (esito.kind === 'eseguita') await mostra(ctx, 'successo', `${c.successo(esito.risultato, false)} L'elenco viene riletto.`)
  else await mostra(ctx, 'errore', testoErrore(esito))
  if (esito.kind === 'eseguita' && chiudibile && p.corrente(ctx) && modulo.stato.id === null && focusPerso()) {
    document.querySelector(`[data-voce-catalogo][data-id="${c.idDi(a.voce)}"] h3`)?.focus()
  }
}

// ---------------------------------------------------------------- esiti da verificare

const incerte = computed(() => store.state.incerte.filter((x) => c.tipi.includes(x.tipo)))
async function verifica(x) {
  const ctx = p.contestoCorrente()
  avviso.value = null
  const lettura = await store.leggiVerifica(x.chiave)
  if (!p.corrente(ctx)) return
  const esito = lettura.kind === 'letta' ? store.esitoVerifica(x.chiave, lettura) : lettura
  if (esito.kind === 'verificata') {
    leggi()
    await mostra(ctx, 'info', c.verifica(esito.incerta, esito.esito, esito.voce, esito.trovate))
  } else if (esito.kind === 'superata') await mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}

// ---------------------------------------------------------------- contesto

function azzera() {
  nuovoAperto.value = false
  contestoNuovo += 1
  seq += 1
  Object.assign(elenco, VUOTO())
  ticket.value = null
  Object.assign(nuovo, Object.fromEntries(campiK.map((k) => [k, ''])))
  erroriNuovo.value = {}
  modulo.chiudi()
  erroriModifica.value = {}
}
onMounted(leggi)
</script>

<template>
  <div class="catalogo-intestazione">
    <h1>{{ c.titolo }}</h1>
    <button ref="pulsanteNuovo" type="button" class="button" data-apri-nuovo :aria-expanded="nuovoAperto"
            :aria-controls="`modulo-nuovo-${c.chiave}`" @click="apriNuovo">Nuova {{ c.singolare }}</button>
  </div>
  <p v-if="!nuovoAperto && azione?.tipo === 'crea' && azione.fase === 'invio'" role="status" class="notice">Creazione in corso: la chiusura del modulo non interrompe la richiesta.</p>
  <p class="muted">{{ c.intro }}</p>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-admin>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-admin>
    <p class="testo-dati">{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>
  <p v-if="attesa > 0" class="notice warning" role="status" data-attesa>
    Il server ha chiesto di attendere: nuove operazioni possibili tra {{ attesa }} secondi. Nessun tentativo viene ripetuto
    automaticamente e la scadenza non chiarisce l'esito di operazioni già inviate.
  </p>

  <section v-if="incerte.length" class="card" :aria-labelledby="`titolo-esiti-${c.chiave}`" data-da-verificare>
    <h2 :id="`titolo-esiti-${c.chiave}`">Esiti da verificare</h2>
    <div v-for="x in incerte" :key="x.chiave" class="notice warning" data-esito-incerto :data-chiave="x.chiave">
      <p class="testo-dati"><strong>{{ x.tipo.endsWith('crea') ? 'Creazione' : 'Modifica' }} dall'esito non determinato</strong>: {{ c.incerta(x) }}.</p>
      <p>Il tentativo non è stato ripetuto; la verifica rilegge l'elenco completo. Altre operazioni sulla stessa risorsa sono bloccate fino alla verifica.</p>
      <p v-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica non è stato usato perché superato: ripeti la verifica.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verifica(x)">{{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica' }}</button>
      </div>
    </div>
  </section>

  <section v-if="nuovoAperto" :id="`modulo-nuovo-${c.chiave}`" class="card" :aria-labelledby="`titolo-nuovo-${c.chiave}`">
    <div class="catalogo-intestazione"><h2 :id="`titolo-nuovo-${c.chiave}`" tabindex="-1">Nuova {{ c.singolare }}</h2>
      <button type="button" class="button secondary" data-chiudi-nuovo @click="chiudiNuovo">Chiudi</button></div>
    <div v-if="azione?.tipo === 'crea'" class="confirm" role="group" :aria-labelledby="`titolo-conferma-nuovo-${c.chiave}`"
         :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="crea">
      <h3 :id="`titolo-conferma-nuovo-${c.chiave}`" :ref="impostaTitolo" tabindex="-1">Confermi la creazione?</h3>
      <p class="testo-dati" data-campo="riepilogo">{{ c.riassunto(azione.dati) }}.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaNuovo">{{ azione.fase === 'invio' ? 'Creazione in corso…' : 'Crea' }}</button>
        <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
      </div>
    </div>
    <form class="form" novalidate :data-modulo-nuovo="c.chiave" @submit.prevent="proseguiNuovo">
      <div v-if="lista(erroriNuovo).length" ref="riepilogoNuovo" tabindex="-1" class="notice error" role="alert" data-errori-nuovo>
        <p>Controlla i dati indicati.</p><ul class="testo-dati"><li v-for="e in lista(erroriNuovo)" :key="e">{{ e }}</li></ul>
      </div>
      <div v-for="f in c.campi" :key="f.k" class="field">
        <label :for="`${c.chiave}-nuovo-${f.k}`">{{ f.etichetta }}</label>
        <component :is="f.area ? 'textarea' : 'input'" :id="`${c.chiave}-nuovo-${f.k}`" :value="nuovo[f.k]" autocomplete="off" @input="nuovo[f.k] = $event.target.value" :inputmode="f.numerico"
                   :aria-invalid="erroriNuovo[f.k] ? 'true' : undefined" :aria-describedby="erroriNuovo[f.k] ? `errore-${c.chiave}-nuovo-${f.k}` : `aiuto-${c.chiave}-nuovo-${f.k}`" />
        <p :id="`aiuto-${c.chiave}-nuovo-${f.k}`" class="small">{{ f.aiuto }}</p>
        <p v-if="erroriNuovo[f.k]" :id="`errore-${c.chiave}-nuovo-${f.k}`" class="field-error testo-dati">{{ erroriNuovo[f.k] }}</p>
      </div>
      <div class="actions"><button type="submit" class="button" :data-origine="`crea-${c.chiave}`" :disabled="!puoScrivere || !libero">Prosegui</button></div>
    </form>
  </section>  <section class="card" :aria-labelledby="`titolo-elenco-${c.chiave}`" :aria-busy="elenco.stato === 'caricamento' ? 'true' : 'false'">
    <h2 :id="`titolo-elenco-${c.chiave}`" ref="titoloElenco" tabindex="-1">Elenco</h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento in corso…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare l'elenco. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="leggi">Riprova</button></div>
    </div>
    <div v-else-if="elenco.stato === 'superata'" class="notice warning" role="status" data-elenco-superato>
      <p>L'elenco letto è stato superato da operazioni più recenti e non è stato usato.</p>
      <div class="actions"><button type="button" class="button" @click="leggi">Aggiorna l'elenco</button></div>
    </div>
    <template v-else>
      <p v-if="!elenco.aggiornata && !elenco.superata" class="small" role="status">Aggiornamento dell'elenco in corso: le azioni sono sospese.</p>
      <div v-if="elenco.superata || invalidato" class="notice warning" role="status" data-elenco-invalidato>
        <p>L'elenco mostrato potrebbe non essere più aggiornato: un'operazione conclusa dopo la lettura riguarda questo catalogo. Le azioni sono sospese finché non aggiorni l'elenco.</p>
        <div class="actions"><button type="button" class="button" @click="leggi">Aggiorna l'elenco</button></div>
      </div>
      <p class="muted" role="status">{{ elenco.voci.length }} {{ elenco.voci.length === 1 ? c.singolare : c.titolo.toLowerCase() }}.</p>
      <ul v-if="elenco.voci.length" class="items" :aria-label="c.titolo">
        <li v-for="v in elenco.voci" :key="c.idDi(v)" class="card" data-voce-catalogo :data-id="c.idDi(v)">
          <h3 class="item-title testo-dati" tabindex="-1" data-campo="nome">{{ v.nome }}</h3>
          <dl class="facts">
            <div v-for="[etichetta, valore, dati] in c.fatti(v)" :key="etichetta"><dt>{{ etichetta }}</dt><dd :class="{ 'testo-dati': dati }">{{ valore }}</dd></div>
            <div><dt>Identificativo</dt><dd>n. {{ c.idDi(v) }}</dd></div>
          </dl>
          <p v-if="bloccata(v)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <!-- Il modulo aperto resta modificabile anche durante il salvataggio: gli input successivi sono conservati. -->
          <template v-if="modulo.aperto(c.idDi(v))">
            <div v-if="azione?.tipo === 'modifica'" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${c.chiave}`"
                 :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="modifica">
              <h4 :id="`titolo-conferma-${c.chiave}`" :ref="impostaTitolo" tabindex="-1">Confermi la modifica?</h4>
              <p class="testo-dati" data-campo="transizione">Da {{ c.riassunto(azione.voce) }} a {{ c.riassunto(azione.dati) }}.</p>
              <div class="actions">
                <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoScrivere" @click="confermaModifica">{{ azione.fase === 'invio' ? 'Salvataggio in corso…' : 'Salva' }}</button>
                <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
              </div>
            </div>
            <form class="form" novalidate :data-modulo-modifica="c.chiave" @submit.prevent="proseguiModifica">
              <div v-if="lista(erroriModifica).length" tabindex="-1" class="notice error" role="alert" :data-errori-modifica="c.chiave">
                <p>Controlla i dati indicati.</p><ul class="testo-dati"><li v-for="e in lista(erroriModifica)" :key="e">{{ e }}</li></ul>
              </div>
              <div v-for="f in c.campi" :key="f.k" class="field">
                <label :for="`${c.chiave}-modifica-${f.k}`">{{ f.etichetta }}</label>
                <component :is="f.area ? 'textarea' : 'input'" :id="`${c.chiave}-modifica-${f.k}`" :value="modulo.valori[f.k]" autocomplete="off" @input="modulo.valori[f.k] = $event.target.value" :inputmode="f.numerico"
                           :aria-invalid="erroriModifica[f.k] ? 'true' : undefined" :aria-describedby="erroriModifica[f.k] ? `errore-${c.chiave}-modifica-${f.k}` : `aiuto-${c.chiave}-modifica-${f.k}`" />
                <p :id="`aiuto-${c.chiave}-modifica-${f.k}`" class="small">{{ f.aiuto }}</p>
                <p v-if="erroriModifica[f.k]" :id="`errore-${c.chiave}-modifica-${f.k}`" class="field-error testo-dati">{{ erroriModifica[f.k] }}</p>
                <p v-else-if="modulo.modificato(f.k)" class="small" :data-modificato="f.k">Modificato: conservato anche se l'elenco viene riletto.</p>
              </div>
              <div class="actions">
                <button type="submit" class="button" :data-origine="`prosegui-${c.chiave}`" :disabled="!puoModificare(v) || !libero">Prosegui</button>
                <button type="button" class="button secondary" :disabled="azione?.tipo === 'modifica'" @click="chiudiModifica">Chiudi</button>
              </div>
            </form>
          </template>
          <div v-else-if="!bloccata(v)" class="actions">
            <button type="button" class="button secondary" :data-origine="`modifica-${c.chiave}-${c.idDi(v)}`" :disabled="!puoModificare(v) || !libero"
                    @click="apriModifica(v)">Modifica<span class="visually-hidden"> {{ v.nome }}</span></button>
          </div>
        </li>
      </ul>
      <p v-else>Nessuna voce nel catalogo.</p>
    </template>
  </section>


</template>

<style scoped>
.catalogo-intestazione { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
.catalogo-intestazione h1, .catalogo-intestazione h2 { margin: 0; }
</style>
