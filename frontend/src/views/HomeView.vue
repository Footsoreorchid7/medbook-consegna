<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchPrestazioni } from '../api/catalogo.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import BootstrapErrorNotice from '../components/BootstrapErrorNotice.vue'
import { STATO } from '../session/session.js'
import { useSession } from '../session/useSession.js'
import { formatEuro } from '../utils/money.js'

const session = useSession()
const retrying = ref(false)
const failedAgain = ref(false)

// La home è pubblica: dopo il recupero resta qui e mostra l'azione adatta al nuovo stato.
async function retry() {
  if (retrying.value) return
  retrying.value = true
  try {
    await session.retryBootstrap()
    failedAgain.value = session.state.status === STATO.ERRORE
  } catch {
    // Verifica già in corso.
  } finally {
    retrying.value = false
  }
}

const statoCatalogo = ref('caricamento')
const evidenza = ref([])
const erroreCatalogo = ref('')
const titoloCatalogo = ref(null)
const riprovaCatalogo = ref(null)
let controller = null

// Stesso client e stessa protezione delle letture pubbliche del catalogo: abort e attualità
// per istanza/richiesta/epoca, anche se una risposta tardiva ignora l'annullamento.
async function caricaCatalogo({ focus = false } = {}) {
  controller?.abort()
  const richiesta = new AbortController()
  controller = richiesta
  const epoca = session.state.epoch
  const attuale = () => controller === richiesta && session.state.epoch === epoca
  statoCatalogo.value = 'caricamento'
  erroreCatalogo.value = ''
  try {
    const voci = await fetchPrestazioni({ signal: richiesta.signal, isCurrent: attuale })
    if (!attuale()) return
    evidenza.value = voci.slice(0, 3)
    statoCatalogo.value = 'pronto'
  } catch (error) {
    if (!attuale() || error instanceof StaleResponseError) return
    erroreCatalogo.value = error instanceof NetworkError ? 'Il server non è raggiungibile.'
      : error instanceof ProtocolError ? 'Il server ha restituito una risposta non valida.'
        : 'Il server ha restituito un errore.'
    statoCatalogo.value = 'errore'
  }
  if (focus && attuale()) {
    await nextTick()
    if (!attuale()) return
    const active = document.activeElement
    if (!active || active === document.body || !active.isConnected) {
      ;(statoCatalogo.value === 'errore' ? riprovaCatalogo.value : titoloCatalogo.value)?.focus()
    }
  }
}

const descrizioneBreve = (testo) => {
  if (!testo?.trim()) return 'Informazioni e dettagli nel catalogo delle prestazioni.'
  const caratteri = Array.from(testo)
  return caratteri.length <= 160 ? testo : `${caratteri.slice(0, 160).join('').trimEnd()}…`
}

onMounted(() => caricaCatalogo())
watch(() => session.state.epoch, () => { if (controller) caricaCatalogo() }, { flush: 'sync' })
onBeforeUnmount(() => { const precedente = controller; controller = null; precedente?.abort() })

const passi = [
  { titolo: 'Consulta prestazioni e disponibilità.', testo: 'Esplora il catalogo e cerca gli appuntamenti disponibili.' },
  { titolo: 'Invia la richiesta di appuntamento.', testo: 'Accedi con il tuo profilo paziente e scegli una disponibilità.' },
  { titolo: 'Attendi la conferma della segreteria.', testo: 'La richiesta diventa un appuntamento confermato dopo la verifica della segreteria.' },
]
const servizi = [
  { titolo: 'I tuoi appuntamenti', testo: 'Segui lo stato delle richieste e consulta i dettagli dei tuoi appuntamenti.', link: 'Gestisci gli appuntamenti', icona: 'M5 5h14v15H5z M8 3v4 M16 3v4 M5 10h14 M9 14h2 M14 14h2 M9 17h2' },
  { titolo: 'Pagamenti online simulati', testo: 'Dopo la conferma, simula il pagamento online e consulta lo storico. Nessun addebito reale.', link: 'Vai alle tue prenotazioni', icona: 'M3 6h18v13H3z M3 10h18 M6 15h4' },
  { titolo: 'Referti disponibili', testo: 'Consulta e scarica i referti disponibili dal dettaglio delle tue visite.', link: 'Consulta le tue visite', icona: 'M6 3h8l4 4v14H6z M13 3v5h5 M9 12h6 M9 16h6' },
]
</script>

<template>
  <div class="home">
    <section class="home-hero" aria-labelledby="home-titolo">
      <div class="home-intro">
        <p class="home-eyebrow"><span class="home-marchio" aria-hidden="true">+</span> Centro Medico MedBook</p>
        <h1 id="home-titolo">Le tue visite, organizzate <span>con semplicità</span></h1>
        <p class="home-lead">Consulta prestazioni e disponibilità, invia le tue richieste di appuntamento e gestiscile online, in un unico spazio.</p>
        <div class="home-azioni">
          <RouterLink class="button" to="/prestazioni">Scopri le prestazioni <span aria-hidden="true">↗</span></RouterLink>
          <RouterLink class="button secondary" to="/disponibilita">Consulta le disponibilità</RouterLink>
        </div>
        <p class="home-demo">Centro dimostrativo — dati di esempio</p>
      </div>
      <div class="home-illustrazione" aria-hidden="true">
        <svg viewBox="0 0 420 340" fill="none" focusable="false">
          <circle cx="220" cy="170" r="145" fill="#e4eaff" />
          <circle cx="220" cy="170" r="119" stroke="#cbd5ff" stroke-dasharray="4 7" />
          <path d="M34 245h25m-12-12v24M342 53h20m-10-10v20" stroke="#8393e4" stroke-width="2" stroke-linecap="round" />
          <rect x="103" y="75" width="226" height="220" rx="19" fill="#d0d9ff" />
          <rect x="91" y="63" width="226" height="220" rx="19" fill="white" stroke="#b7c5f5" />
          <path d="M91 111h226" stroke="#d8e0f5" />
          <path d="M143 51v25m123-25v25" stroke="#3641f5" stroke-width="7" stroke-linecap="round" />
          <rect x="114" y="85" width="66" height="5" rx="2.5" fill="#c4cef0" />
          <g fill="#eff2fc">
            <rect x="114" y="132" width="38" height="32" rx="7" /><rect x="161" y="132" width="38" height="32" rx="7" />
            <rect x="208" y="132" width="38" height="32" rx="7" /><rect x="255" y="132" width="38" height="32" rx="7" />
            <rect x="114" y="173" width="38" height="32" rx="7" /><rect x="161" y="173" width="38" height="32" rx="7" />
            <rect x="255" y="173" width="38" height="32" rx="7" />
            <rect x="114" y="214" width="38" height="32" rx="7" /><rect x="161" y="214" width="38" height="32" rx="7" />
          </g>
          <rect x="208" y="173" width="38" height="32" rx="7" fill="#3641f5" />
          <path d="m218 189 6 6 12-13" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <rect x="233" y="226" width="145" height="64" rx="13" fill="white" stroke="#b7c5f5" />
          <circle cx="259" cy="258" r="13" fill="#eef2ff" />
          <path d="M259 251v14m-7-7h14" stroke="#3641f5" stroke-width="2" stroke-linecap="round" />
          <path d="M283 251h71m-71 13h46" stroke="#c0cbed" stroke-width="5" stroke-linecap="round" />
          <circle cx="338" cy="133" r="24" fill="#3641f5" />
          <path d="M338 123v20m-10-10h20" stroke="white" stroke-width="3" stroke-linecap="round" />
        </svg>
      </div>
    </section>

    <div class="home-sessione">
      <p v-if="session.state.status === STATO.VERIFICA" role="status">Verifica della sessione in corso…</p>
      <BootstrapErrorNotice v-else-if="session.state.status === STATO.ERRORE" :retrying="retrying" :failed-again="failedAgain" @retry="retry" />
    </div>

    <section class="home-sezione" aria-labelledby="home-prestazioni" :aria-busy="statoCatalogo === 'caricamento' ? 'true' : 'false'">
      <div class="home-sezione-testa">
        <div><p class="home-eyebrow">Il catalogo</p><h2 id="home-prestazioni" ref="titoloCatalogo" tabindex="-1">Prestazioni in evidenza</h2></div>
        <RouterLink class="home-link" to="/prestazioni">Tutte le prestazioni <span aria-hidden="true">→</span></RouterLink>
      </div>
      <p v-if="statoCatalogo === 'caricamento'" class="home-stato" role="status">Caricamento delle prestazioni…</p>
      <div v-else-if="statoCatalogo === 'errore'" class="notice error" role="alert" data-home-errore-catalogo>
        <p>Non è stato possibile caricare le prestazioni in evidenza. {{ erroreCatalogo }}</p>
        <button ref="riprovaCatalogo" class="button secondary" type="button" @click="caricaCatalogo({ focus: true })">Riprova il catalogo</button>
      </div>
      <p v-else-if="evidenza.length === 0" class="home-stato" role="status">Nessuna prestazione disponibile al momento.</p>
      <ul v-else class="home-griglia home-prestazioni" aria-label="Prestazioni in evidenza">
        <li v-for="p in evidenza" :key="p.idPrestazione" class="home-prestazione" data-home-prestazione>
          <span class="home-segno" aria-hidden="true">+</span>
          <h3>{{ p.nome }}</h3>
          <p>{{ descrizioneBreve(p.descrizione) }}</p>
          <div class="home-prezzo"><span>Prezzo</span><strong>{{ formatEuro(p.prezzo) }}</strong></div>
        </li>
      </ul>
    </section>

    <section class="home-sezione home-percorso" aria-labelledby="home-come-funziona">
      <p class="home-eyebrow">Un passo alla volta</p>
      <h2 id="home-come-funziona">Come funziona</h2>
      <ol class="home-griglia home-passi">
        <li v-for="(passo, i) in passi" :key="passo.titolo">
          <span class="home-numero" aria-hidden="true">0{{ i + 1 }}</span>
          <h3>{{ passo.titolo }}</h3><p>{{ passo.testo }}</p>
        </li>
      </ol>
    </section>

    <section class="home-sezione" aria-labelledby="home-servizi">
      <p class="home-eyebrow">Lo spazio del paziente</p>
      <h2 id="home-servizi">Servizi online</h2>
      <ul class="home-griglia home-servizi">
        <li v-for="s in servizi" :key="s.titolo">
          <svg class="home-icona" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path :d="s.icona" /></svg>
          <h3>{{ s.titolo }}</h3><p>{{ s.testo }}</p>
          <RouterLink v-if="session.state.status === STATO.AUTENTICATO && session.capabilities.value.paziente" class="home-link" to="/paziente/prenotazioni">{{ s.link }} <span aria-hidden="true">→</span></RouterLink>
        </li>
      </ul>
      <div v-if="session.state.status === STATO.AUTENTICATO" class="home-accesso">
        <p>Ritrova le funzioni disponibili per il tuo profilo.</p>
        <RouterLink class="button" to="/area">Vai all'area riservata</RouterLink>
      </div>
      <div v-else-if="session.state.status === STATO.ANONIMO && !session.state.loginUncertain" class="home-accesso">
        <p>Accedi per gestire le tue richieste e consultare i servizi del tuo profilo.</p>
        <div class="home-azioni"><RouterLink class="button" to="/accesso">Accedi</RouterLink><RouterLink class="home-link" to="/registrazione">Crea un account paziente <span aria-hidden="true">→</span></RouterLink></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home { display: grid; gap: 2.5rem; }
.home > *, .home-griglia > *, .home-hero > * { min-width: 0; }
.home h2 { margin: 0 0 1.25rem; font-size: 1.35rem; letter-spacing: -0.025em; }
.home h3 { margin: 0 0 0.5rem; font-size: 1rem; line-height: 1.4; }
.home p { margin: 0; }
.home h1, .home h3, .home p, .home a { overflow-wrap: anywhere; }
.home-hero { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); align-items: center; gap: 1rem; padding: 2.5rem; border: 1px solid #dce3f5; border-radius: 1rem; background: linear-gradient(120deg, #fff 25%, #f0f3ff); }
.home-eyebrow { color: var(--color-muted); font-size: 0.75rem; font-weight: 600; letter-spacing: 0.06em; }
.home-intro > .home-eyebrow { display: flex; align-items: center; gap: 0.625rem; margin-bottom: 1.25rem; }
.home-marchio { display: inline-grid; place-items: center; width: 1.625rem; height: 1.625rem; flex: none; border-radius: 0.5rem; color: #fff; background: var(--color-primary); font-size: 1.25rem; line-height: 1; }
.home h1 { max-width: 18ch; margin: 0 0 1.25rem; font-size: clamp(1.875rem, 3.2vw, 2.875rem); font-weight: 700; letter-spacing: -0.045em; line-height: 1.13; }
.home h1 span { color: var(--color-primary); }
.home .home-lead { max-width: 48ch; color: var(--color-muted); font-size: 0.9375rem; line-height: 1.7; }
.home-azioni { display: flex; flex-wrap: wrap; align-items: center; gap: 0.75rem; }
.home-intro .home-azioni { margin-top: 1.5rem; }
.home-azioni .button { padding: 0.625rem 1rem; }
.home-azioni .button span { margin-left: 0.4rem; }
.home .home-demo { margin-top: 1.5rem; color: var(--color-muted); font-size: 0.75rem; }
.home-illustrazione svg { display: block; width: 100%; height: auto; }
.home-sessione:empty { display: none; }
.home-sezione-testa { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.75rem 1rem; margin-bottom: 1.25rem; }
.home-sezione .home-eyebrow { margin-bottom: 0.5rem; }
.home-sezione-testa h2 { margin: 0; }
.home-link { font-weight: 600; text-underline-offset: 0.2em; }
.home-link span { display: inline-block; margin-left: 0.25rem; }
.home-griglia { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1rem; margin: 0; padding: 0; list-style: none; }
.home-prestazione { display: flex; flex-direction: column; padding: 1.5rem; border: 1px solid var(--color-border); border-radius: 0.75rem; background: var(--color-surface); }
.home-segno { display: inline-grid; place-items: center; align-self: flex-start; width: 2rem; height: 2rem; margin-bottom: 1.25rem; border-radius: 0.625rem; background: var(--color-primary-soft); color: var(--color-primary); font-size: 1.375rem; }
.home-prestazione p, .home-servizi p, .home-passi p { color: var(--color-muted); line-height: 1.65; }
.home-prezzo { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 0.25rem 0.75rem; margin-top: auto; padding-top: 1.5rem; }
.home-prezzo span { color: var(--color-muted); font-size: 0.75rem; }
.home-prezzo strong { font-size: 1.125rem; font-weight: 600; overflow-wrap: anywhere; }
.home-percorso { padding: 2rem; background: var(--color-primary-soft); border: 1px solid #dce3f5; border-radius: 0.875rem; }
.home-passi { gap: 1.75rem; }
.home-numero { display: block; margin-bottom: 0.75rem; color: var(--color-primary); font-size: 0.875rem; font-weight: 700; }
.home-passi h3 { max-width: 25ch; }
.home-servizi > li { padding: 1.25rem 0; }
.home-icona { display: block; width: 1.75rem; height: 1.75rem; margin-bottom: 1rem; color: var(--color-primary); }
.home-servizi .home-link { display: inline-block; margin-top: 1rem; }
.home-accesso { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1rem; margin-top: 1.25rem; padding-top: 1.5rem; border-top: 1px solid var(--color-border); }
.home-accesso p { color: var(--color-muted); }
.home-stato { padding: 1.5rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 0.75rem; color: var(--color-muted); }
@media (max-width: 68rem) {
  .home-hero { padding: 1.75rem; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); }
  .home-azioni { align-items: stretch; }
  .home-prestazione { padding: 1.125rem; }
}
@media (max-width: 55rem) {
  .home-hero { grid-template-columns: minmax(0, 1fr); }
  .home-illustrazione { max-width: 19rem; width: 100%; justify-self: center; }
  .home-griglia { grid-template-columns: minmax(0, 1fr); }
  .home { gap: 2rem; }
  .home-percorso { padding: 1.5rem; }
  .home-prestazione { padding: 1.5rem; }
  .home-servizi { gap: 0; }
  .home-servizi > li + li { border-top: 1px solid var(--color-border); }
}
@media (max-width: 25rem) {
  .home-hero { padding: 1.25rem; }
  .home-percorso { padding: 1.25rem; }
  .home-azioni { flex-direction: column; }
  .home-azioni > * { width: 100%; }
}
</style>
