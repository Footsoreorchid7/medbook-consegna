<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchMedicoDesk, fetchMedici, fetchStanze } from '../api/desk.js'
import { configurazionePresente } from '../api/disponibilitaMedico.js'
import { leggiDisponibilitaMedici } from '../desk/lettureDisponibilita.js'
import EsitiDesk from '../components/EsitiDesk.vue'
import StatoElencoDesk from '../components/StatoElencoDesk.vue'
import { chiaveSlot, listaDisponibilita } from '../desk/store.js'
import { TESTO_ERRORE_VERIFICA, TESTO_SUPERATA, nome, testoVerifica } from '../desk/formato.js'
import { ESITI_COMUNI, useElencoDesk } from '../desk/useElencoDesk.js'
import { formatLocalDate, formatLocalTime, parseLocalDateTime } from '../utils/datetime.js'

/**
 * Configurazione delle disponibilità : per il medico scelto, assegnazione unica di
 * prestazione e stanza a uno slot non configurato. Le prestazioni proposte sono solo quelle per
 * cui il medico risulta abilitato nel dettaglio; il backend verifica abilitazione, durata e
 * occupazione della stanza. Nessuna riconfigurazione.
 */
const medici = reactive({ stato: 'caricamento', voci: [] })
const selectMedico = ref(null)
const riprovaMedici = ref(null)
/** Sequenza dei caricamenti dei medici: vale solo la risposta dell'ultimo, nella stessa epoca. */
let letturaMedici = 0
const selezionato = ref('')
/** Medico, prestazioni autorizzate e stanze lette per il contesto corrente. */
const ausiliari = reactive({ stato: 'nessuno', medico: null, prestazioni: [], stanze: [], errore: '' })
const scelta = reactive({ prestazione: '', stanza: '' })
const erroreScelta = ref('')
const campoPrestazione = ref(null)
const impostaCampo = (el) => { campoPrestazione.value = el }
let idMedico = null

const d = useElencoDesk({
  azzera: () => Object.assign(ausiliari, { stato: 'nessuno', medico: null, prestazioni: [], stanze: [], errore: '' }),
  ricarica: () => {
    // Cambio di epoca: un caricamento dei medici in corso o fallito riparte nella nuova sessione.
    if (medici.stato !== 'pronto') caricaMedici()
    else carica()
  },
})
const { store, session, elenco, azione, avviso, avvisoBox, titoloElenco, impostaTitolo, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi } = d

const quando = (s) => {
  const f = (n) => { const p = parseLocalDateTime(n); const sec = n.slice(17, 19); return `${formatLocalDate(p)}, ${formatLocalTime(p)}${sec !== '00' ? `:${sec}` : ''}` }
  return `dal ${f(s.inizio)} al ${f(s.fine)}`
}
const rileggi = () => {
  if (medici.stato !== 'pronto') return Promise.resolve(null)
  const ctx = d.contestoCorrente()
  const dottori = idMedico === null ? [...medici.voci] : medici.voci.filter(m => m.idUtente === idMedico)
  let gruppi = []
  return d.leggi({ lista: 'disponibilita-filtrate', completa: true,
    leggi: async () => {
      gruppi = await leggiDisponibilitaMedici(session.protectedRequest, dottori, () => d.corrente(ctx))
      return gruppi.flatMap(g => g.voci.map(s => ({ ...s, medico: g.medico })))
        .sort((a, b) => a.inizio.localeCompare(b.inizio) || a.idDisponibilita - b.idDisponibilita)
    },
    applica: (_, ticket) => {
      if (!gruppi.every(g => store.valida(listaDisponibilita(g.medico.idUtente), ticket))) return false
      return gruppi.every(g => store.applica(listaDisponibilita(g.medico.idUtente), g.voci, ticket, true))
    },
    valida: ticket => dottori.every(m => store.valida(listaDisponibilita(m.idUtente), ticket)),
  })
}
const visibili = computed(() => elenco.voci.filter(s => !s.configurata))
const configurabili = computed(() => visibili.value.filter(s => !configurazionePresente(s)).length)

let letturaAusiliari = 0
async function caricaAusiliari(id = idMedico) {
  const mia = ++letturaAusiliari
  const origine = azione.value
  const ctx = d.contestoCorrente()
  ausiliari.stato = 'caricamento'
  try {
    const [dettaglio, stanze] = await Promise.all([fetchMedicoDesk(session.protectedRequest, id), fetchStanze(session.protectedRequest)])
    if (!d.corrente(ctx) || (mia !== letturaAusiliari || (idMedico === null && azione.value !== origine))) return
    Object.assign(ausiliari, { stato: 'pronto', medico: dettaglio.medico, prestazioni: dettaglio.prestazioni, stanze, errore: '' })
  } catch {
    if (!d.corrente(ctx) || (mia !== letturaAusiliari || (idMedico === null && azione.value !== origine))) return
    Object.assign(ausiliari, { stato: 'errore', medico: null, prestazioni: [], stanze: [], errore: 'Non è stato possibile leggere abilitazioni del medico e stanze.' })
  }
}

function carica() {
  rileggi()
  if (idMedico !== null) caricaAusiliari()
}

/** Nuovo medico: nuovo contesto (anche tornando a un medico già visto). */
function cambiaMedico() {
  idMedico = selezionato.value ? Number(selezionato.value) : null
  d.nuovoContesto()
  carica()
}

const puoConfigurare = computed(() => puoAgire.value && ausiliari.stato === 'pronto')
const voceCorrente = (id) => elenco.voci.find((v) => v.idDisponibilita === id) ?? null
const configurabile = (s) => !configurazionePresente(s) && !store.bloccata(chiaveSlot(s.idDisponibilita))
function configurazione(s) {
  if (s.configurata) return `Configurata: ${s.prestazione.nome ?? 'prestazione assegnata'}, ${s.stanza.nome ?? 'stanza assegnata'}`
  if (configurazionePresente(s)) return 'Configurazione parziale: non configurabile'
  return 'Da configurare'
}

async function apriConfigurazione(s) {
  scelta.prestazione = ''
  scelta.stanza = ''
  erroreScelta.value = ''
  if (!puoAgire.value || !configurabile(s)) return
  const ctx = d.contestoCorrente()
  if (await apri({ tipo: 'configura', id: s.idDisponibilita, voce: s, fase: 'scelta' }, `configura-${s.idDisponibilita}`)) {
    if (!d.corrente(ctx) || azione.value?.id !== s.idDisponibilita) return
    if (idMedico === null) await caricaAusiliari(s.medico.idUtente)
    await nextTick()
    if (d.corrente(ctx)) campoPrestazione.value?.focus()
  }
}

async function prosegui() {
  const ctx = d.contestoCorrente()
  const a = azione.value
  if (a?.fase !== 'scelta' || !puoConfigurare.value) return
  const p = ausiliari.prestazioni.find((x) => String(x.idPrestazione) === scelta.prestazione)
  const st = ausiliari.stanze.find((x) => String(x.idStanza) === scelta.stanza)
  if (!p || !st) {
    erroreScelta.value = 'Scegli una prestazione autorizzata e una stanza.'
    await nextTick()
    if (d.corrente(ctx) && azione.value === a) campoPrestazione.value?.focus()
    return
  }
  azione.value = { ...a, fase: 'conferma', prestazione: p, stanza: st }
  await nextTick()
  if (d.corrente(ctx) && azione.value?.id === a.id && azione.value?.fase === 'conferma') d.titoloConferma.value?.focus()
}

async function conferma() {
  const a = azione.value
  const s = a && voceCorrente(a.id)
  if (!a || a.fase !== 'conferma' || !puoConfigurare.value || !s || !configurabile(s)) return
  const ctx = d.contestoCorrente()
  if (s.inizio !== a.voce.inizio || s.fine !== a.voce.fine) {
    chiudi()
    await d.mostra(ctx, 'errore', 'Lo slot è cambiato: controlla la riga e riapri la configurazione.')
    return
  }
  azione.value = { ...a, fase: 'invio' }
  const esito = await store.configura(s, ausiliari.medico, a.prestazione, a.stanza)
  if (!d.corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  chiudi()
  switch (esito.kind) {
    case 'eseguita':
      await d.mostra(ctx, 'successo', `Disponibilità n. ${s.idDisponibilita} configurata con ${a.prestazione.nome} e ${a.stanza.nome}.`)
      break
    case 'conflitto':
    case 'non-trovata':
    case 'non-valida':
      await d.mostra(ctx, 'errore', `Configurazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} L'elenco viene riletto.`)
      break
    case 'configurata':
      await d.mostra(ctx, 'errore', 'La disponibilità risulta già configurata: non è riconfigurabile.')
      break
    case 'incerto':
      await d.mostra(ctx, 'errore', "Non è stato possibile stabilire l'esito della configurazione. Non è stata ripetuta: verificala negli esiti da verificare.")
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
    if (idMedico === null || idMedico === x.riepilogo.medico.idUtente) rileggi()
    await d.mostra(ctx, 'info', testoVerifica(x, esito.esito, esito.voce))
  } else if (esito.kind === 'superata') await d.mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await d.mostra(ctx, 'errore', TESTO_ERRORE_VERIFICA)
}
const descriviIncerta = (x) => `disponibilità n. ${x.idRisorsa} di ${nome(x.riepilogo.medico)} (${quando(x.riepilogo.slot)}), richiesta ${x.riepilogo.prestazione.nome} e ${x.riepilogo.stanza.nome}`

/**
 * Elenco dei medici selezionabili. Nessun nuovo tentativo automatico: dopo un errore, «Riprova».
 * Una risposta superata da un caricamento più recente, da un cambio di epoca o dallo smontaggio
 * viene scartata.
 */
async function caricaMedici({ manuale = false } = {}) {
  if (d.idOperatore() === null) return
  const mia = ++letturaMedici
  const epoca = session.state.epoch
  const attuale = () => mia === letturaMedici && !d.smontato() && epoca === session.state.epoch
  medici.stato = 'caricamento'
  try {
    const voci = await fetchMedici(session.protectedRequest)
    if (!attuale()) return
    Object.assign(medici, { stato: 'pronto', voci })
    carica()
  } catch {
    if (!attuale()) return
    medici.stato = 'errore'
  }
  if (!manuale) return
  await nextTick()
  // Il pulsante premuto non esiste più: il focus va al nuovo controllo.
  if (attuale() && document.activeElement === document.body) (medici.stato === 'pronto' ? selectMedico : riprovaMedici).value?.focus()
}
function riprova() {
  // Un solo caricamento alla volta: il pulsante scompare al primo clic.
  if (medici.stato !== 'errore') return
  caricaMedici({ manuale: true })
}

onMounted(() => caricaMedici())
</script>

<template>
  <h1>Configurazione delle disponibilità</h1>
  <p class="muted">
    Assegnazione di prestazione e stanza agli slot non configurati di un medico. La configurazione è unica: uno slot già
    configurato, anche parzialmente, non si riconfigura. Orari espressi nell'orario della struttura.
  </p>

  <section class="card" aria-labelledby="titolo-scelta-medico">
    <h2 id="titolo-scelta-medico">Filtri</h2>
    <p v-if="medici.stato === 'caricamento'" role="status">Caricamento dei medici…</p>
    <div v-else-if="medici.stato === 'errore'" class="notice error" role="alert" data-errore-medici>
      <p>Non è stato possibile leggere l'elenco dei medici. Le disponibilità non sono complete e non è disponibile un totale.</p>
      <div class="actions"><button ref="riprovaMedici" type="button" class="button" @click="riprova">Riprova</button></div>
    </div>
    <div v-else class="field">
      <label for="desk-medico">Medico</label>
      <select id="desk-medico" ref="selectMedico" v-model="selezionato" @change="cambiaMedico">
        <option value="">Tutti i medici</option>
        <option v-for="m in medici.voci" :key="m.idUtente" :value="String(m.idUtente)">{{ nome(m) }}{{ m.stato === 'INATTIVO' ? ' (inattivo)' : '' }}</option>
      </select>
    </div>
  </section>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-desk>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-desk>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <EsitiDesk :tipi="['configura']" :descrizione="descriviIncerta" @verifica="verifica" />

  <section v-if="medici.stato === 'pronto'" aria-labelledby="titolo-slot-desk" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-slot-desk" ref="titoloElenco" tabindex="-1">Disponibilità da configurare</h2>
    <p class="small">Tutte le date, anche passate · {{ selezionato ? 'medico selezionato' : 'tutti i medici, anche inattivi' }}. La validità degli orari viene verificata dal server.</p>
    <p v-if="ausiliari.stato === 'caricamento'" role="status">Caricamento delle prestazioni autorizzate e delle stanze…</p>
    <p v-if="ausiliari.stato === 'pronto'" class="small" data-abilitazioni>
      Prestazioni autorizzate: {{ ausiliari.prestazioni.length ? ausiliari.prestazioni.map((p) => p.nome).join(', ') : 'nessuna' }}.
    </p>
    <div v-else-if="ausiliari.stato === 'errore'" class="notice error" role="alert">
      <p>{{ ausiliari.errore }} La configurazione è sospesa.</p>
      <div class="actions"><button type="button" class="button" @click="caricaAusiliari(azione?.voce.medico.idUtente ?? idMedico)">Riprova</button></div>
    </div>
    <StatoElencoDesk :elenco="elenco" :in-aggiornamento="inAggiornamento" :invalidata="invalidata" oggetto="le disponibilità (se una lettura fallisce, l’elenco è incompleto: nessun totale disponibile)" @rileggi="d.recupera(rileggi)" />
    <template v-if="elenco.stato === 'pronto'">
      <p class="muted" role="status"><template v-if="elenco.aggiornata && !invalidata">{{ configurabili }} slot da configurare.</template><template v-else>Dati non aggiornati: totale non disponibile.</template></p>
      <p v-if="visibili.length === 0 && elenco.aggiornata && !invalidata">Nessuna disponibilità da configurare nella copertura indicata.</p>
      <ul v-else class="items" aria-label="Disponibilità da configurare e configurazioni parziali">
        <li v-for="s in visibili" :key="s.idDisponibilita" class="card" data-slot-desk :data-id="s.idDisponibilita">
          <h3 class="item-title">{{ nome(s.medico) }} · Slot n. {{ s.idDisponibilita }}</h3>
          <p data-campo="quando">{{ quando(s) }}</p>
          <p class="small" data-campo="configurazione">{{ configurazione(s) }} — disponibilità n. {{ s.idDisponibilita }}</p>
          <template v-if="!configurazionePresente(s)">
            <p v-if="store.bloccata(`slot:${s.idDisponibilita}`)" class="small"><strong>Configurazione in corso o da verificare.</strong></p>
            <div v-else-if="azione?.id === s.idDisponibilita && azione.fase === 'scelta'" class="form" data-scelta-configurazione>
              <div class="field">
                <label :for="`prestazione-${s.idDisponibilita}`">Prestazione autorizzata</label>
                <select :id="`prestazione-${s.idDisponibilita}`" :ref="impostaCampo" v-model="scelta.prestazione"
                        :aria-invalid="erroreScelta ? 'true' : undefined" :aria-describedby="erroreScelta ? `errore-scelta-${s.idDisponibilita}` : undefined">
                  <option value="">Scegli una prestazione</option>
                  <option v-for="p in ausiliari.prestazioni" :key="p.idPrestazione" :value="String(p.idPrestazione)">{{ p.nome }} ({{ p.durata }} min)</option>
                </select>
              </div>
              <div class="field">
                <label :for="`stanza-${s.idDisponibilita}`">Stanza</label>
                <select :id="`stanza-${s.idDisponibilita}`" v-model="scelta.stanza">
                  <option value="">Scegli una stanza</option>
                  <option v-for="st in ausiliari.stanze" :key="st.idStanza" :value="String(st.idStanza)">{{ st.nome }}</option>
                </select>
              </div>
              <p v-if="erroreScelta" :id="`errore-scelta-${s.idDisponibilita}`" class="field-error">{{ erroreScelta }}</p>
              <p class="small">Il server verifica abilitazione, durata della prestazione e occupazione della stanza.</p>
              <div class="actions">
                <button type="button" class="button" :disabled="!puoConfigurare" @click="prosegui">Prosegui</button>
                <button type="button" class="button secondary" @click="annulla">Indietro</button>
              </div>
            </div>
            <div v-else-if="azione?.id === s.idDisponibilita" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${s.idDisponibilita}`"
                 :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" data-conferma="configura">
              <h4 :id="`titolo-conferma-${s.idDisponibilita}`" :ref="impostaTitolo" tabindex="-1">Confermi la configurazione?</h4>
              <p>Medico: <strong>{{ nome(ausiliari.medico) }}</strong>. Slot: <strong>{{ quando(s) }}</strong> (n. {{ s.idDisponibilita }}).</p>
              <p>Prestazione: <strong data-campo="prestazione">{{ azione.prestazione.nome }}</strong>. Stanza: <strong data-campo="stanza">{{ azione.stanza.nome }}</strong>.</p>
              <p class="small">La configurazione è unica e non potrà essere modificata con queste funzioni.</p>
              <div class="actions">
                <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoConfigurare" @click="conferma">
                  {{ azione.fase === 'invio' ? 'Configurazione in corso…' : 'Conferma configurazione' }}
                </button>
                <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
              </div>
            </div>
            <div v-else class="actions">
              <button type="button" class="button" :data-origine="`configura-${s.idDisponibilita}`" :disabled="!puoAgire || !libero"
                      @click="apriConfigurazione(s)">Configura<span class="visually-hidden"> lo slot {{ quando(s) }}</span></button>
            </div>
          </template>
        </li>
      </ul>
    </template>
  </section>
</template>
