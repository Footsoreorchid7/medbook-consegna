<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { FILTRI_RIMBORSI_DESK, fetchRimborsiDesk, fetchStoricoRimborsiDesk } from '../api/desk.js'
import EsitiDesk from '../components/EsitiDesk.vue'
import StatoElencoDesk from '../components/StatoElencoDesk.vue'
import { chiaveRimborso } from '../desk/store.js'
import { TESTO_ERRORE_VERIFICA, TESTO_SUPERATA, dataOra, nome, prestazione, quando, testoVerifica, testoVerificaDesk } from '../desk/formato.js'
import { ESITI_COMUNI, useElencoDesk } from '../desk/useElencoDesk.js'
import { ETICHETTE_RIMBORSO, NOTE_RIMBORSO } from '../prenotazioni/economia.js'
import { formatEuro } from '../utils/money.js'

// Lo storico filtrato non alimenta il registro dei pendenti usato dalle riconciliazioni.
const route = useRoute()
const router = useRouter()
const filtroRotta = valore => typeof valore === 'string' && Object.hasOwn(FILTRI_RIMBORSI_DESK, valore) ? valore : 'TUTTI'
const filtro = ref(filtroRotta(route.query.stato))
const decisi = ref([])
const TONI = { RICHIESTO: 'ambra', IN_ELABORAZIONE: 'ambra', APPROVATO: 'blu', RESPINTO: 'rosso', FALLITO: 'rosso', RIMBORSATO: 'verde' }
const d = useElencoDesk({ azzera: () => { decisi.value = [] }, ricarica: () => rileggi() })
const { store, session, elenco, azione, avviso, avvisoBox, titoloElenco, impostaTitolo, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi } = d
const rileggi = () => d.leggi({
  lista: filtro.value === 'RICHIESTO' ? 'rimborsi' : `rimborsi-storico:${filtro.value}`, completa: true,
  leggi: () => filtro.value === 'RICHIESTO' ? fetchRimborsiDesk(session.protectedRequest) : fetchStoricoRimborsiDesk(session.protectedRequest, filtro.value),
})
function cambiaFiltro() {
  const stato = filtro.value === 'TUTTI' ? undefined : filtro.value
  if (route.query.stato === stato) return cerca()
  // Sospendi subito le vecchie azioni, prima della navigazione asincrona.
  d.nuovoContesto()
  return router.replace({ query: { ...route.query, stato } })
}
function cerca() { d.nuovoContesto(); return rileggi() }
function aggiorna() { decisi.value = []; return d.recupera(rileggi) }
const voci = computed(() => {
  const lette = elenco.stato === 'pronto' ? elenco.voci : []
  // Il riscontro della decisione appena confermata prevale su una lettura precedente invalidata.
  const ids = new Set(decisi.value.map(r => r.idRimborso))
  return [...lette.filter(r => !ids.has(r.idRimborso)), ...decisi.value]
})
const fuoriFiltro = r => filtro.value !== 'TUTTI' && r.stato !== filtro.value

const voceCorrente = (id) => elenco.voci.find((v) => v.idRimborso === id) ?? null
const operabile = (r) => r.stato === 'RICHIESTO' && !store.bloccata(chiaveRimborso(r.idRimborso))
const MODALITA = { ONLINE: 'Online (simulato)', DESK_POS: 'In sede, POS', DESK_CONTANTI: 'In sede, contanti' }

function apriDecisione(tipo, r) {
  if (!puoAgire.value || !libero.value || !operabile(r) || voceCorrente(r.idRimborso) !== r) return
  apri({ tipo, id: r.idRimborso, voce: r, fase: 'conferma' }, `${tipo}-${r.idRimborso}`)
}

async function conferma() {
  const a = azione.value
  const r = a && voceCorrente(a.id)
  if (!a || a.fase !== 'conferma' || !puoAgire.value || !r || !operabile(r)) return
  const ctx = d.contestoCorrente()
  if (r.importo !== a.voce.importo || r.idPagamento !== a.voce.idPagamento) {
    chiudi()
    await d.mostra(ctx, 'errore', 'I dati del rimborso sono cambiati: controlla la riga e riapri la conferma.')
    return
  }
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.decidiRimborso(a.tipo, r)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'eseguita') {
    decisi.value = [...decisi.value.filter(v => v.idRimborso !== r.idRimborso), {
      ...r, stato: esito.risultato.stato, verificato: esito.risultato.verificato,
      operatore: { id: esito.risultato.idOperatore },
    }]
  }
  if (['eseguita', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  chiudi()
  switch (esito.kind) {
    case 'eseguita':
      await d.mostra(ctx, 'successo', a.tipo === 'approva'
        ? `Rimborso n. ${r.idRimborso} approvato. L'approvazione non indica che il denaro sia già stato restituito.`
        : `Rimborso n. ${r.idRimborso} respinto.`)
      break
    case 'conflitto':
    case 'non-trovata':
    case 'non-valida':
      await d.mostra(ctx, 'errore', `Decisione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} L'elenco viene riletto.`)
      break
    case 'incerto':
      await d.mostra(ctx, 'errore', "Non è stato possibile stabilire l'esito della decisione. Non è stata ripetuta: verificala negli esiti da verificare prima di decidere di nuovo.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await d.mostra(ctx, 'errore', ESITI_COMUNI[esito.kind] ?? ESITI_COMUNI.rifiutata)
  }
}

async function verifica(x) {
  const ctx = d.contestoCorrente()
  avviso.value = null
  const esito = await store.verifica(x.chiave)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await d.mostra(ctx, 'info', testoVerifica(x, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await d.mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await d.mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}
const descriviIncerta = (x) => `rimborso n. ${x.idRisorsa} (${nome(x.riepilogo.paziente)}, ${formatEuro(x.riepilogo.importo)})`

/**
 * Recupero di una decisione incerta: solo con prenotazione e giorno dell'appuntamento noti.
 * Legge le prenotazioni gestite di quel giorno senza filtro medico; l'elenco dei rimborsi
 * mostrato non viene sostituito, si rilegge con la propria lettura.
 */
const recuperabile = (x) => x.riepilogo?.idPrenotazione != null && x.riepilogo?.inizio != null
async function recupera(x) {
  const ctx = d.contestoCorrente()
  avviso.value = null
  const esito = await store.verificaRimborsoDesk(x.chiave)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'verificata') {
    rileggi()
    await d.mostra(ctx, 'info', testoVerificaDesk(esito))
  } else if (esito.kind === 'superata') await d.mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await d.mostra(ctx, 'errore', 'Verifica non riuscita: le prenotazioni del giorno non sono leggibili. Riprova più tardi.')
}

watch(() => route.query.stato, valore => { filtro.value = filtroRotta(valore); cerca() })
onMounted(rileggi)
</script>

<template>
  <h1>Rimborsi</h1>
  <p class="muted">
    Consulta le richieste pendenti e lo storico delle decisioni. «Approvato» indica una richiesta accolta, non una restituzione già eseguita.
  </p>

  <form class="card form" @submit.prevent="cerca">
    <div class="field">
    <label for="filtro-rimborsi-desk">Stato dei rimborsi</label>
    <select id="filtro-rimborsi-desk" v-model="filtro" @change="cambiaFiltro">
      <option v-for="(testo, valore) in FILTRI_RIMBORSI_DESK" :key="valore" :value="valore">{{ testo }}</option>
    </select>
    </div>
    <div class="actions"><button type="button" class="button secondary" :disabled="elenco.stato === 'caricamento' || inAggiornamento" @click="aggiorna">Aggiorna l'elenco</button></div>
  </form>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-desk>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-desk>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <EsitiDesk :tipi="['approva', 'respingi']" :descrizione="descriviIncerta" :recupero="recuperabile" @verifica="verifica" @recupera="recupera" />

  <section aria-labelledby="titolo-rimborsi-desk" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-rimborsi-desk" ref="titoloElenco" tabindex="-1">Richieste di rimborso</h2>
    <StatoElencoDesk :elenco="elenco" :in-aggiornamento="inAggiornamento" :invalidata="invalidata" oggetto="i rimborsi" @rileggi="aggiorna" />
    <template v-if="elenco.stato === 'pronto' || decisi.length">
      <p v-if="elenco.stato === 'pronto'" class="muted" role="status">{{ elenco.voci.length === 1 ? '1 richiesta' : `${elenco.voci.length} richieste` }}.</p>
      <p v-if="elenco.stato === 'pronto' && voci.length === 0">{{ filtro === 'RICHIESTO' ? 'Nessuna richiesta di rimborso da decidere.' : 'Nessun rimborso corrisponde al filtro selezionato.' }}</p>
      <ul v-else class="items" aria-label="Rimborsi">
        <li v-for="r in voci" :key="r.idRimborso" class="card" data-rimborso-desk :data-id="r.idRimborso">
          <h3 class="item-title">Rimborso n. {{ r.idRimborso }} · {{ nome(r.paziente) }}</h3>
          <span :class="['badge-stato', `badge-${TONI[r.stato]}`]">{{ ETICHETTE_RIMBORSO[r.stato] }}</span>
          <p v-if="fuoriFiltro(r)" class="notice small">Decisione confermata in questa vista: non corrisponde più al filtro «{{ FILTRI_RIMBORSI_DESK[filtro] }}». La card resterà visibile fino alla prossima ricerca o aggiornamento.</p>
          <p v-if="r.stato === 'APPROVATO'" class="small">{{ NOTE_RIMBORSO.APPROVATO }}</p>
          <dl class="facts">
            <div><dt>Importo</dt><dd data-campo="importo">{{ formatEuro(r.importo) }}</dd></div>
            <div><dt>Pagamento</dt><dd>n. {{ r.idPagamento }}{{ r.modalita ? `, ${MODALITA[r.modalita] ?? r.modalita}` : '' }}</dd></div>
            <div><dt>Prenotazione</dt><dd>n. {{ r.idPrenotazione }}</dd></div>
            <div><dt>Prestazione</dt><dd>{{ prestazione(r) }}</dd></div>
            <div v-if="r.inizio && r.fine"><dt>Appuntamento</dt><dd>{{ quando(r) }}</dd></div>
            <div><dt>Richiesto il</dt><dd>{{ dataOra(r.richiesto) }}</dd></div>
            <div v-if="r.verificato"><dt>Decisione del</dt><dd>{{ dataOra(r.verificato) }}</dd></div>
            <div v-if="r.operatore"><dt>Operatore della decisione</dt><dd>{{ r.operatore.nome && r.operatore.cognome ? nome(r.operatore) : `Operatore n. ${r.operatore.id}` }}</dd></div>
          </dl>
          <section class="motivazione-riquadro" :aria-labelledby="`motivazione-${r.idRimborso}`">
            <h4 :id="`motivazione-${r.idRimborso}`">Motivazione</h4>
            <p data-campo="motivazione">{{ r.motivazione }}</p>
          </section>
          <template v-if="r.stato === 'RICHIESTO'">
          <p v-if="!operabile(r)" class="small"><strong>Decisione in corso o da verificare.</strong></p>
          <div v-else-if="azione?.id === r.idRimborso" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${r.idRimborso}`"
               :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
            <h4 :id="`titolo-conferma-${r.idRimborso}`" :ref="impostaTitolo" tabindex="-1">
              {{ azione.tipo === 'approva' ? 'Approvi la richiesta di rimborso?' : 'Respingi la richiesta di rimborso?' }}
            </h4>
            <p>{{ nome(r.paziente) }}, {{ formatEuro(r.importo) }}, pagamento n. {{ r.idPagamento }}, prenotazione n. {{ r.idPrenotazione }}.</p>
            <p class="small">{{ azione.tipo === 'approva' ? "L'approvazione non esegue la restituzione del denaro." : 'La richiesta passerà a «Respinto».' }}</p>
            <div class="actions">
              <button type="button" :class="['button', { 'azione-rimborso': azione.tipo === 'approva' }]" :disabled="azione.fase === 'invio' || !puoAgire" @click="conferma">
                {{ azione.fase === 'invio' ? 'Decisione in corso…' : azione.tipo === 'approva' ? 'Approva' : 'Respingi' }}
              </button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button type="button" class="button azione-rimborso" :data-origine="`approva-${r.idRimborso}`" :disabled="!puoAgire || !libero"
                    @click="apriDecisione('approva', r)">Approva<span class="visually-hidden"> il rimborso di {{ nome(r.paziente) }}</span></button>
            <button type="button" class="button secondary" :data-origine="`respingi-${r.idRimborso}`" :disabled="!puoAgire || !libero"
                    @click="apriDecisione('respingi', r)">Respingi<span class="visually-hidden"> il rimborso di {{ nome(r.paziente) }}</span></button>
          </div>
          </template>
        </li>
      </ul>
    </template>
  </section>
</template>
