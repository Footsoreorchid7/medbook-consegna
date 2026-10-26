<script setup>
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchMedici, fetchSegnalazioni } from '../api/desk.js'
import EsitiDesk from '../components/EsitiDesk.vue'
import StatoElencoDesk from '../components/StatoElencoDesk.vue'
import { chiavePrenotazione } from '../desk/store.js'
import { TESTO_ERRORE_VERIFICA, TESTO_SUPERATA, nome, prestazione, quando, stanza, testoVerifica } from '../desk/formato.js'
import { ESITI_COMUNI, useElencoDesk } from '../desk/useElencoDesk.js'

/**
 * Segnalazioni di indisponibilità pendenti : sostituzione del medico o annullamento.
 * L'elenco identifica il paziente senza dati clinici. Una segnalazione che scompare non prova quale azione sia
 * avvenuta; non esiste una lettura della singola prenotazione.
 */
const d = useElencoDesk({ ricarica: () => rileggi() })
const { store, session, elenco, azione, avviso, avvisoBox, titoloElenco, impostaTitolo, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi } = d
const rileggi = () => d.leggi({ lista: 'segnalazioni', completa: true, leggi: () => fetchSegnalazioni(session.protectedRequest) })

const voceCorrente = (id) => elenco.voci.find((v) => v.idPrenotazione === id) ?? null
const operabile = (s) => !store.bloccata(chiavePrenotazione(s.idPrenotazione))
const stessaVersione = (a, b) => a && b && a.stato === b.stato && a.indisponibile.id === b.indisponibile.id && a.idDisponibilita === b.idDisponibilita

// Sostituzione: candidati letti al momento, solo come proposta; il backend verifica impegni e idoneità.
const sostituti = ref({ stato: 'nessuno', voci: [], errore: '' })
const scelto = ref('')
const erroreScelta = ref('')
const campoSostituto = ref(null)
const impostaCampo = (el) => { campoSostituto.value = el }

async function apriSostituzione(s) {
  scelto.value = ''
  erroreScelta.value = ''
  sostituti.value = { stato: 'caricamento', voci: [], errore: '' }
  if (!await apri({ tipo: 'sostituisci', id: s.idPrenotazione, voce: s, fase: 'scelta' }, `sostituisci-${s.idPrenotazione}`)) return
  const ctx = d.contestoCorrente()
  const a = azione.value
  try {
    const medici = await fetchMedici(session.protectedRequest, { stato: 'ATTIVO', idPrestazione: s.prestazione.id })
    if (!d.corrente(ctx) || azione.value !== a) return
    sostituti.value = { stato: 'pronto', voci: medici.filter((m) => m.idUtente !== s.indisponibile.id), errore: '' }
  } catch {
    if (!d.corrente(ctx) || azione.value !== a) return
    sostituti.value = { stato: 'errore', voci: [], errore: "Non è stato possibile leggere l'elenco dei medici." }
  }
  await nextTick()
  if (d.corrente(ctx) && azione.value === a) campoSostituto.value?.focus()
}

async function proseguiSostituzione() {
  const a = azione.value
  if (a?.tipo !== 'sostituisci' || a.fase !== 'scelta' || !puoAgire.value) return
  const m = sostituti.value.voci.find((x) => String(x.idUtente) === scelto.value)
  if (!m) {
    erroreScelta.value = 'Scegli il medico sostituto.'
    await nextTick()
    campoSostituto.value?.focus()
    return
  }
  azione.value = { ...a, fase: 'conferma', sostituto: m }
  await nextTick()
  d.titoloConferma.value?.focus()
}

function apriAnnullamento(s) {
  apri({ tipo: 'annulla', id: s.idPrenotazione, voce: s, fase: 'conferma' }, `annulla-${s.idPrenotazione}`)
}

async function conferma() {
  const a = azione.value
  const s = a && voceCorrente(a.id)
  if (!a || a.fase !== 'conferma' || !puoAgire.value || !s || !operabile(s)) return
  const ctx = d.contestoCorrente()
  if (!stessaVersione(a.voce, s)) {
    chiudi()
    await d.mostra(ctx, 'errore', 'I dati della segnalazione sono cambiati: controlla la riga e riapri la conferma.')
    return
  }
  azione.value = { ...a, fase: 'invio' }
  const esito = a.tipo === 'sostituisci' ? await store.sostituisci(s, a.sostituto.idUtente) : await store.annullaSegnalata(s)
  if (!d.corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  chiudi()
  switch (esito.kind) {
    case 'eseguita':
      await d.mostra(ctx, 'successo', a.tipo === 'sostituisci'
        ? `Prenotazione n. ${s.idPrenotazione} confermata con il medico ${nome(a.sostituto)}; la segnalazione di ${nome(s.indisponibile)} è risolta. `
          + 'Se la prenotazione ricompare tra le segnalazioni, si tratta di una nuova segnalazione da gestire sui dati aggiornati.'
        : `Prenotazione n. ${s.idPrenotazione} annullata; la segnalazione di ${nome(s.indisponibile)} è risolta.`)
      break
    case 'conflitto':
    case 'non-trovata':
    case 'non-valida':
      await d.mostra(ctx, 'errore', `Operazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} L'elenco viene riletto.`)
      break
    case 'incerto':
      await d.mostra(ctx, 'errore', "Non è stato possibile stabilire l'esito. L'operazione non è stata ripetuta: verificala negli esiti da verificare prima di altre operazioni su questa prenotazione.")
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
const descriviIncerta = (x) => `prenotazione n. ${x.idRisorsa} (${quando(x.riepilogo)}, medico indisponibile ${nome(x.riepilogo.indisponibile)})`
const statoTesto = (s) => (s.stato === 'RICHIESTA' ? 'Richiesta in attesa' : 'Confermata')

onMounted(rileggi)
</script>

<template>
  <h1>Segnalazioni di indisponibilità</h1>
  <p class="muted">
    Appuntamenti per cui il medico ha segnalato la propria indisponibilità. Si possono sostituire con un altro medico oppure
    annullare. Orari espressi nell'orario della struttura.
  </p>

  <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                        :notifica="avviso" @close="avviso = null" data-avviso-desk>
    <p class="testo-dati">{{ avviso.testo }}</p>
  </NotificaInformativa>
  <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', avviso.tipo === 'errore' ? 'error' : '']"
       :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-desk>
    <p>{{ avviso.testo }}</p>
    <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
  </div>

  <EsitiDesk :tipi="['sostituisci', 'annulla-segnalata']" :descrizione="descriviIncerta" @verifica="verifica" />

  <section aria-labelledby="titolo-segnalazioni" :aria-busy="elenco.stato === 'caricamento' || inAggiornamento ? 'true' : 'false'">
    <h2 id="titolo-segnalazioni" ref="titoloElenco" tabindex="-1">Segnalazioni pendenti</h2>
    <StatoElencoDesk :elenco="elenco" :in-aggiornamento="inAggiornamento" :invalidata="invalidata" oggetto="le segnalazioni" @rileggi="d.recupera(rileggi)" />
    <template v-if="elenco.stato === 'pronto'">
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 segnalazione' : `${elenco.voci.length} segnalazioni` }}.</p>
      <p v-if="elenco.voci.length === 0">Nessuna segnalazione pendente.</p>
      <ul v-else class="items" aria-label="Segnalazioni pendenti">
        <li v-for="s in elenco.voci" :key="s.idPrenotazione" class="card" data-segnalazione :data-id="s.idPrenotazione">
          <h3 class="item-title">Prenotazione n. {{ s.idPrenotazione }} · {{ nome(s.paziente) }}</h3>
          <p class="orario-segnalazione">{{ quando(s) }}</p>
          <dl class="facts">
            <div><dt>Stato</dt><dd data-campo="stato">{{ statoTesto(s) }}</dd></div>
            <div><dt>Medico indisponibile</dt><dd data-campo="indisponibile">{{ nome(s.indisponibile) }}</dd></div>
            <div v-if="s.effettivo && s.effettivo.id !== s.titolare.id"><dt>Medico titolare</dt><dd>{{ nome(s.titolare) }}</dd></div>
            <div><dt>Prestazione</dt><dd>{{ prestazione(s) }}</dd></div>
            <div><dt>Stanza</dt><dd>{{ stanza(s) }}</dd></div>
            <div><dt>Prenotazione</dt><dd>n. {{ s.idPrenotazione }}</dd></div>
          </dl>
          <p v-if="!operabile(s)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
          <div v-else-if="azione?.id === s.idPrenotazione && azione.fase === 'scelta'" class="form" data-scelta-sostituto>
            <p v-if="sostituti.stato === 'caricamento'" role="status">Caricamento dei medici…</p>
            <p v-else-if="sostituti.stato === 'errore'" class="field-error" role="alert">{{ sostituti.errore }}</p>
            <template v-else-if="sostituti.stato === 'pronto'">
              <div class="field">
                <label :for="`sostituto-${s.idPrenotazione}`">Medico sostituto (attivi e abilitati a {{ prestazione(s) }})</label>
                <select :id="`sostituto-${s.idPrenotazione}`" :ref="impostaCampo" v-model="scelto"
                        :aria-invalid="erroreScelta ? 'true' : undefined" :aria-describedby="erroreScelta ? `errore-sostituto-${s.idPrenotazione}` : `nota-sostituto-${s.idPrenotazione}`">
                  <option value="">Scegli un medico</option>
                  <option v-for="m in sostituti.voci" :key="m.idUtente" :value="String(m.idUtente)">{{ nome(m) }}{{ m.specializzazione ? ` — ${m.specializzazione}` : '' }}</option>
                </select>
                <p :id="`nota-sostituto-${s.idPrenotazione}`" class="small">L'elenco non garantisce la disponibilità: il server verifica impegni, abilitazione e stato del medico.</p>
                <p v-if="erroreScelta" :id="`errore-sostituto-${s.idPrenotazione}`" class="field-error">{{ erroreScelta }}</p>
              </div>
              <p v-if="sostituti.voci.length === 0" class="small">Nessun altro medico attivo risulta abilitato a questa prestazione.</p>
            </template>
            <div class="actions">
              <button type="button" class="button" :disabled="sostituti.stato !== 'pronto' || !puoAgire" @click="proseguiSostituzione">Prosegui</button>
              <button type="button" class="button secondary" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else-if="azione?.id === s.idPrenotazione" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${s.idPrenotazione}`"
               :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
            <h4 :id="`titolo-conferma-${s.idPrenotazione}`" :ref="impostaTitolo" tabindex="-1">
              {{ azione.tipo === 'sostituisci' ? 'Confermi la sostituzione del medico?' : "Confermi l'annullamento della prenotazione?" }}
            </h4>
            <p>Appuntamento: {{ quando(s) }}, {{ prestazione(s) }}. Prenotazione n. {{ s.idPrenotazione }}. Paziente: {{ nome(s.paziente) }}.</p>
            <p>Medico indisponibile: <strong>{{ nome(s.indisponibile) }}</strong>.</p>
            <p v-if="azione.tipo === 'sostituisci'">Sostituto: <strong data-campo="sostituto">{{ nome(azione.sostituto) }}</strong>. La prenotazione risulterà confermata con il sostituto.</p>
            <p v-else class="small">La prenotazione passerà a «Annullata».</p>
            <div class="actions">
              <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgire" @click="conferma">
                {{ azione.fase === 'invio' ? 'Operazione in corso…' : azione.tipo === 'sostituisci' ? 'Conferma sostituzione' : 'Annulla prenotazione' }}
              </button>
              <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
            </div>
          </div>
          <div v-else class="actions">
            <button v-if="s.prestazione" type="button" class="button" :data-origine="`sostituisci-${s.idPrenotazione}`" :disabled="!puoAgire || !libero"
                    @click="apriSostituzione(s)">Sostituisci medico<span class="visually-hidden"> per la prenotazione n. {{ s.idPrenotazione }}</span></button>
            <button type="button" class="button secondary" :data-origine="`annulla-${s.idPrenotazione}`" :disabled="!puoAgire || !libero"
                    @click="apriAnnullamento(s)">Annulla prenotazione<span class="visually-hidden"> n. {{ s.idPrenotazione }}</span></button>
          </div>
          <p v-if="!s.prestazione && operabile(s)" class="small">Sostituzione non proponibile: la disponibilità non è configurata.</p>
        </li>
      </ul>
    </template>
  </section>
</template>


<style scoped>
.orario-segnalazione { margin-block: .25rem 1rem; font-weight: 600; }
</style>
