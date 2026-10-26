<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { confrontaRimborsi, fetchRimborsi } from '../api/rimborsi.js'
import { ETICHETTE_RIMBORSO, NOTE_RIMBORSO, dataOra } from '../prenotazioni/economia.js'
import { usePrenotazioni } from '../prenotazioni/store.js'
import { useLetturaPaziente } from '../prenotazioni/useLetturaPaziente.js'
import { formatEuro } from '../utils/money.js'

const TONO_STATO = Object.freeze({ RICHIESTO: 'ambra', IN_ELABORAZIONE: 'ambra', RESPINTO: 'rosso', FALLITO: 'rosso', RIMBORSATO: 'verde', APPROVATO: 'blu' })
const store = usePrenotazioni()
const elenco = reactive({ stato: 'caricamento', voci: [], errore: '' })
const avviso = ref(null)
const avvisoBox = ref(null)
const titoloElenco = ref(null)
const bottoneRiprova = ref(null)

// Recenza delle righe per pagamento: ticket della risposta da cui deriva la riga (o la sua
// assenza). `base` è il ticket dell'ultimo elenco completo adottato; `versioni` registra gli
// ambiti aggiornati dopo da una riconciliazione limitata a una prenotazione.
let base = 0
let versioni = new Map()
const versione = (idPagamento) => versioni.get(idPagamento) ?? base

const { session, idPaziente, nuovaLettura, attuale, smontato } = useLetturaPaziente({
  azzera() {
    elenco.voci = []
    elenco.stato = 'caricamento'
    avviso.value = null
    base = 0
    versioni = new Map()
  },
  ricarica: () => carica(),
})

function messaggio(error) {
  if (error instanceof NetworkError) return 'Il server non è raggiungibile.'
  if (error instanceof ProtocolError) return 'Il server ha restituito una risposta non valida.'
  return 'Il server ha restituito un errore.'
}

/** @returns {Promise<{mia: number, ok: boolean} | undefined>} esito della lettura, solo se ancora attuale */
async function carica() {
  if (idPaziente() === null) return
  const mia = nuovaLettura()
  const avvisoPrecedente = avviso.value
  elenco.stato = 'caricamento'
  elenco.errore = ''
  const ticket = store.inizioLettura()
  try {
    const voci = await fetchRimborsi(session.protectedRequest)
    // Lettura autorevole: aggiorna i riscontri condivisi secondo l'ordine dei ticket.
    const applicati = store.registraRimborsi(voci, ticket)
    if (!attuale(mia)) return
    // Gli ambiti aggiornati da una riconciliazione più recente di questa lettura restano invariati.
    const recenti = new Set([...versioni].filter(([, n]) => n > ticket.n).map(([id]) => id))
    elenco.voci = [...voci.filter((r) => !recenti.has(r.idPagamento)), ...elenco.voci.filter((r) => recenti.has(r.idPagamento))]
      .sort(confrontaRimborsi)
    base = ticket.n
    versioni = new Map([...versioni].filter(([id]) => recenti.has(id)))
    elenco.stato = 'pronto'
    // Rimuove solo l'invito coperto da questa lettura, mai un avviso arrivato nel frattempo.
    if (avviso.value === avvisoPrecedente && avvisoPrecedente?.aggiornabile
        && (applicati.has(avvisoPrecedente.idPagamento)
          || (store.state.rimborsiApplicatoGlobale === ticket.n
            && !store.state.ambitiRimborso.some((x) => x.idPagamento === avvisoPrecedente.idPagamento
              && Math.max(x.applicato, x.barriera) > ticket.n)))) {
      avviso.value = { ...avvisoPrecedente, aggiornabile: false }
    }
    return { mia, ok: true }
  } catch (error) {
    if (!attuale(mia) || error instanceof StaleResponseError) return
    base = 0
    versioni = new Map()
    elenco.voci = []
    elenco.errore = messaggio(error)
    elenco.stato = 'errore'
    return { mia, ok: false }
  }
}

/**
 * Aggiornamento richiesto dall'utente («Aggiorna l'elenco», «Riprova»). Se il pulsante premuto è
 * scomparso e il focus è rimasto sul documento, va al titolo dell'elenco dopo un successo o a
 * «Riprova» dopo un errore. Nessuno spostamento se la lettura è superata (lettura più recente,
 * cambio di epoca, smontaggio) o se l'utente ha già portato il focus altrove.
 */
async function aggiorna() {
  const esito = await carica()
  if (!esito) return
  await nextTick()
  if (!attuale(esito.mia)) return
  const attivo = document.activeElement
  if (attivo && attivo !== document.body) return
  ;(esito.ok ? titoloElenco : bottoneRiprova).value?.focus()
}

/**
 * Applica il risultato di una riconciliazione limitata a una prenotazione: inserisce, aggiorna o
 * rimuove soltanto le righe dei pagamenti coperti e più recenti di quelle mostrate; le altre
 * prenotazioni restano invariate. L'ordine segue il contratto del backend.
 * @returns {boolean} true se tutta la copertura è stata applicata
 */
function adottaCopertura(copertura) {
  if (!copertura || elenco.stato === 'errore') return false
  const ids = new Set(copertura.idPagamenti.filter((id) => copertura.ticket > versione(id)))
  elenco.voci = [
    ...elenco.voci.filter((r) => !ids.has(r.idPagamento)),
    ...copertura.voci.filter((r) => ids.has(r.idPagamento)),
  ].sort(confrontaRimborsi)
  for (const id of ids) versioni.set(id, copertura.ticket)
  return ids.size === copertura.idPagamenti.length
}

/** Verifica manuale di una richiesta incerta: aggiorna la lista solo nell'ambito validato. */
async function verifica(x) {
  avviso.value = null
  const epoca = session.state.epoch
  const esito = await store.verificaRimborso(x.idPrenotazione)
  if (smontato() || session.state.epoch !== epoca) return
  let aggiornabile = esito.kind === 'superata'
  if (esito.kind === 'trovato' || esito.kind === 'assente') aggiornabile = !adottaCopertura(esito.copertura)
  const testi = {
    trovato: esito.rimborso
      ? `Per la prenotazione n. ${x.idPrenotazione} risulta una richiesta di rimborso, stato «${ETICHETTE_RIMBORSO[esito.rimborso.stato]}».`
      : `Per la prenotazione n. ${x.idPrenotazione} risulta una richiesta di rimborso, secondo i dati più recenti.`,
    superata: `Il risultato della verifica per la prenotazione n. ${x.idPrenotazione} è stato superato da dati più recenti e non è stato usato. Ripeti la verifica.`,
    assente: `Per la prenotazione n. ${x.idPrenotazione} non risulta una richiesta di rimborso. Questo non prova che l'invio non sia stato registrato: un nuovo invio è possibile dal dettaglio della prenotazione, solo con una scelta esplicita e una nuova conferma.`,
    errore: "Verifica non riuscita: l'elenco dei rimborsi non è leggibile. Riprova più tardi.",
  }
  if (testi[esito.kind]) {
    avviso.value = { tipo: esito.kind === 'errore' ? 'errore' : 'info', testo: testi[esito.kind], aggiornabile, idPagamento: x.idPagamento }
    await nextTick()
    if (!smontato()) avvisoBox.value?.focus()
  }
}

onMounted(carica)
</script>

<template>
  <h1>I miei rimborsi</h1>
  <p>
    Richieste di rimborso per pagamenti online di prestazioni non erogate. Un rimborso «Approvato» indica una
    richiesta accolta, non una restituzione già eseguita.
  </p>
  <p><RouterLink to="/paziente/prenotazioni">Le mie prenotazioni</RouterLink></p>

  <div v-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'">
    <p>{{ avviso.testo }}</p>
    <div v-if="avviso.aggiornabile" class="actions" data-aggiorna-elenco>
      <p class="small">L'elenco mostrato potrebbe non riflettere i dati più recenti.</p>
      <button type="button" class="button secondary" @click="aggiorna">Aggiorna l'elenco</button>
    </div>
  </div>

  <section v-if="store.state.rimborsiIncerti.length" class="card" aria-labelledby="titolo-rimborsi-incerti">
    <h2 id="titolo-rimborsi-incerti">Richieste da verificare</h2>
    <div v-for="x in store.state.rimborsiIncerti" :key="x.idPrenotazione" class="notice warning" data-rimborso-incerto>
      <p>
        <strong>Esito non determinato</strong> per la richiesta di rimborso della prenotazione n. {{ x.idPrenotazione }}
        (pagamento n. {{ x.idPagamento }}, {{ formatEuro(x.importo) }}).
      </p>
      <p v-if="x.verifica === 'assente'">Una verifica precedente non ha trovato la richiesta: questo non prova che l'invio sia fallito.</p>
      <p v-else-if="x.verifica === 'errore'">Verifica non riuscita. Riprova più tardi.</p>
      <p v-else-if="x.verifica === 'superata'">Il risultato della verifica è stato superato da dati più recenti: ripeti la verifica.</p>
      <div class="actions">
        <button type="button" class="button" :disabled="x.verifica === 'in-corso'" @click="verifica(x)">
          {{ x.verifica === 'in-corso' ? 'Verifica in corso…' : 'Verifica nell\'elenco' }}
        </button>
        <RouterLink class="button secondary" :to="`/paziente/prenotazioni/${x.idPrenotazione}`">Apri la prenotazione</RouterLink>
      </div>
    </div>
  </section>

  <section aria-labelledby="titolo-rimborsi" :aria-busy="elenco.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-rimborsi" ref="titoloElenco" tabindex="-1">Richieste</h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento dei rimborsi…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare i rimborsi. {{ elenco.errore }}</p>
      <div class="actions"><button ref="bottoneRiprova" type="button" class="button" @click="aggiorna">Riprova</button></div>
    </div>
    <p v-else-if="elenco.voci.length === 0" role="status">Non hai richieste di rimborso.</p>
    <ul v-else class="items" aria-label="Elenco delle richieste di rimborso">
      <li v-for="r in elenco.voci" :key="r.idRimborso" class="card" data-rimborso>
        <h3 class="item-title">Prenotazione n. {{ r.idPrenotazione }}</h3>
        <dl class="facts">
          <div><dt>Stato</dt><dd data-campo="stato-rimborso"><span :class="['badge-stato', `badge-${TONO_STATO[r.stato]}`]">{{ ETICHETTE_RIMBORSO[r.stato] }}</span></dd></div>
          <div><dt>Importo</dt><dd>{{ formatEuro(r.importo) }}</dd></div>
          <div><dt>Richiesto il</dt><dd>{{ dataOra(r.richiesto) }}</dd></div>
          <div v-if="r.verificato"><dt>Deciso il</dt><dd>{{ dataOra(r.verificato) }}</dd></div>
          <div><dt>Pagamento</dt><dd>n. {{ r.idPagamento }}</dd></div>
        </dl>
        <div class="rimborso-motivazione motivazione-riquadro">
          <p class="rimborso-etichetta">Motivazione</p>
          <p class="rimborso-testo" data-campo="motivazione">{{ r.motivazione }}</p>
        </div>
        <p class="small" data-campo="nota-stato">{{ NOTE_RIMBORSO[r.stato] }}</p>
        <p class="small">
          <RouterLink :to="`/paziente/prenotazioni/${r.idPrenotazione}`">
            Dettagli<span class="visually-hidden"> della prenotazione n. {{ r.idPrenotazione }}</span>
          </RouterLink>
        </p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.rimborso-motivazione { margin-block: 1rem .75rem; }
.rimborso-etichetta { margin: 0 0 .375rem; font-weight: 600; }
.rimborso-testo { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
