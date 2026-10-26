<script setup>
import { statoAgendaUI } from '../medico/statiAgenda.js'
import NotificaInformativa from '../components/NotificaInformativa.vue'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { GIORNI_MASSIMI, coperturaDesk, dataFiltroValida, fetchMedici, fetchPrenotazioniDesk } from '../api/desk.js'
import { importoValido } from '../api/pagamenti.js'
import EsitiDesk from '../components/EsitiDesk.vue'
import StatoElencoDesk from '../components/StatoElencoDesk.vue'
import { chiavePrenotazione } from '../desk/store.js'
import {
  TESTI_NON_PROPONIBILE, TESTO_SUPERATA, nome, prestazione, quando, stanza, testoVerificaDesk,
} from '../desk/formato.js'
import { ESITI_COMUNI, useElencoDesk } from '../desk/useElencoDesk.js'
import { ETICHETTE_MODALITA, ETICHETTE_PAGAMENTO, ETICHETTE_RIMBORSO, NOTE_RIMBORSO, pagamentoRimborsabile } from '../prenotazioni/economia.js'
import { etichettaStato } from '../prenotazioni/formato.js'
import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'
import { formatEuro } from '../utils/money.js'

/**
 * Prenotazioni gestite : lettura GET /api/prenotazioni/desk per un periodo scelto
 * dall'operatore (nessuna data ricavata dall'orologio del browser) e operazioni su una
 * CONFERMATA: incasso in sede, annullamento, esito negativo. I vincoli orari li decide il server.
 */
const filtri = reactive({ periodo: 'singola', data: '', dataDa: '', dataA: '', medicoId: '' })
const errori = ref({})
const erroreFiltriBox = ref(null)
const opzioni = reactive({ medici: [] })
/** Copertura dell'elenco mostrato (api.coperturaDesk), null prima della prima ricerca. */
const copertura = ref(null)

const d = useElencoDesk({ ricarica: () => rileggi() })
const { store, session, elenco, azione, avviso, avvisoBox, titoloElenco, impostaTitolo, puoAgire, invalidata, inAggiornamento, libero, apri, annulla, chiudi } = d

function rileggi() {
  const c = copertura.value
  if (!c) return undefined
  return d.leggi({
    lista: c.chiave, completa: true,
    leggi: () => fetchPrenotazioniDesk(session.protectedRequest, c),
    applica: (voci, ticket) => store.applicaPrenotazioni(c, voci, ticket),
  })
}

function validaFiltri() {
  const e = {}
  let scelti = null
  if (filtri.periodo === 'singola') {
    if (!dataFiltroValida(filtri.data)) e.data = 'Inserisci una data valida.'
    else scelti = { data: filtri.data }
  } else {
    if (!dataFiltroValida(filtri.dataDa)) e.dataDa = 'Inserisci una data di inizio valida.'
    if (!dataFiltroValida(filtri.dataA)) e.dataA = 'Inserisci una data di fine valida.'
    if (!e.dataDa && !e.dataA) {
      if (filtri.dataDa > filtri.dataA) e.dataA = 'La data di fine non può precedere quella di inizio.'
      else scelti = { dataDa: filtri.dataDa, dataA: filtri.dataA }
    }
  }
  if (!scelti) return { errori: e, copertura: null }
  try {
    return { errori: e, copertura: coperturaDesk({ ...scelti, medicoId: filtri.medicoId ? Number(filtri.medicoId) : null }) }
  } catch {
    return { errori: { dataA: `Il periodo può comprendere al massimo ${GIORNI_MASSIMI} giorni, estremi inclusi.` }, copertura: null }
  }
}

async function cerca() {
  const { errori: e, copertura: c } = validaFiltri()
  errori.value = e
  if (!c) {
    await nextTick()
    erroreFiltriBox.value?.focus()
    return
  }
  copertura.value = c
  aperti.clear()
  d.nuovoContesto()
  await rileggi()
}

const erroriFiltri = computed(() => Object.values(errori.value))
const giornoTesto = (data) => formatLocalDate({ year: Number(data.slice(0, 4)), month: Number(data.slice(5, 7)), day: Number(data.slice(8, 10)) })
const descrizioneCopertura = computed(() => {
  const c = copertura.value
  if (!c) return ''
  const periodo = c.dataDa === c.dataA ? `del ${giornoTesto(c.dataDa)}` : `dal ${giornoTesto(c.dataDa)} al ${giornoTesto(c.dataA)}`
  if (c.medicoId === null) return `prenotazioni gestite ${periodo}`
  const m = opzioni.medici.find((x) => x.idUtente === c.medicoId)
  return `prenotazioni gestite ${periodo}, medico effettivo ${m ? nome(m) : `n. ${c.medicoId}`}`
})

// ---------------------------------------------------------------- presentazione (solo lettura dei dati mostrati)

/** Orario compatto: i secondi compaiono solo se diversi da zero (chiave «aaaa-mm-ggThh:mm:ss.f»). */
const ora = (x) => (x.chiave.slice(17, 19) === '00' ? formatLocalTime(x) : `${formatLocalTime(x)}:${x.chiave.slice(17, 19)}`)
const giornoBreve = (x) => { const [g, data] = formatLocalDate(x).split(' '); return `${g.slice(0, 3)} ${data}` }
const dataOra = (x) => `${formatLocalDate(x)}, ${ora(x)}`
const orario = (v) => (v.inizio.date === v.fine.date ? `${ora(v.inizio)}–${ora(v.fine)}` : `${ora(v.inizio)} – ${giornoBreve(v.fine)}, ${ora(v.fine)}`)

const TONI_STATO = { CONFERMATA: 'blu', EFFETTUATA: 'verde', PAZIENTE_ASSENTE: statoAgendaUI('PAZIENTE_ASSENTE').colore, NON_EROGATA: 'rosso' }
const tonoStato = (s) => TONI_STATO[s] ?? 'grigio'
const stileBadge = (stato) => {
  if (stato !== 'PAZIENTE_ASSENTE') return undefined
  const palette = statoAgendaUI(stato)
  return { backgroundColor: palette.fondo, color: palette.testo, borderColor: palette.accento }
}

/**
 * Riepilogo dei pagamenti e rimborsi letti, senza nuove regole: pagati, in attesa e non riusciti
 * restano distinti, e l'assenza di pagamenti è dichiarata. Lo storico completo è nel dettaglio.
 */
function riepilogoPagamento(v) {
  if (v.pagamenti.length === 0) return [{ testo: 'Nessun pagamento', tono: 'neutro' }]
  const righe = []
  for (const p of v.pagamenti.filter((x) => x.stato === 'PAGATO')) righe.push({ testo: `Pagato · ${ETICHETTE_MODALITA[p.modalita]}`, tono: 'verde' })
  for (const p of v.pagamenti.filter((x) => x.stato === 'IN_ATTESA')) righe.push({ testo: `In attesa · ${ETICHETTE_MODALITA[p.modalita]}`, tono: 'ambra' })
  const falliti = v.pagamenti.filter((x) => x.stato === 'FALLITO').length
  if (falliti) righe.push({ testo: falliti === 1 ? '1 tentativo non riuscito' : `${falliti} tentativi non riusciti`, tono: 'neutro' })
  for (const r of v.rimborsi) righe.push({ testo: `Rimborso: ${ETICHETTE_RIMBORSO[r.stato]}`, tono: 'neutro' })
  return righe
}

/** Dettagli aperti, per prenotazione: stato della sola presentazione, azzerato a ogni nuova ricerca. */
const aperti = reactive(new Set())
const idDettaglio = (v) => `dettaglio-prenotazione-${v.idPrenotazione}`
/** Un dettaglio con una conferma aperta non si chiude: la conferma si chiude con «Indietro». */
const confermaIn = (v) => azione.value?.id === v.idPrenotazione
function commuta(v) {
  if (!aperti.has(v.idPrenotazione)) aperti.add(v.idPrenotazione)
  else if (!confermaIn(v)) aperti.delete(v.idPrenotazione)
}

// ---------------------------------------------------------------- condizioni (stesse dello store )

const ATTIVI = ['PAGATO', 'IN_ATTESA']
function motivoIncasso(v) {
  if (v.indisponibilitaSegnalata) return 'segnalata'
  if (v.pagamenti.some((p) => ATTIVI.includes(p.stato))) return 'pagamento-presente'
  if (!importoValido(v.prezzo)) return 'nessun-importo'
  return null
}
const motivoAnnullamento = (v) => (v.pagamenti.some((p) => p.stato !== 'FALLITO') ? 'pagamenti-bloccanti' : null)
const motivoEsito = (v) => (v.pagamenti.some((p) => p.stato === 'IN_ATTESA') ? 'pagamento-in-attesa' : null)

/** Informazione sul rimborso, con le regole esistenti: nessun nuovo diritto. */
function notaRimborso(v) {
  if (v.stato === 'PAZIENTE_ASSENTE') return "L'assenza del paziente non dà diritto a rimborso."
  if (v.stato !== 'NON_EROGATA') return null
  const lettura = (voci) => ({ stato: 'pronto', aggiornata: true, voci })
  if (pagamentoRimborsabile(v, lettura(v.pagamenti), lettura(v.rimborsi))) {
    return 'Il paziente può chiedere il rimborso del pagamento online riuscito; la richiesta spetta al paziente.'
  }
  if (v.pagamenti.some((p) => p.stato === 'PAGATO' && p.modalita !== 'ONLINE')) return 'Gli incassi in sede non sono rimborsabili tramite MedBook.'
  return null
}

// ---------------------------------------------------------------- azioni

const voceCorrente = (id) => elenco.voci.find((v) => v.idPrenotazione === id) ?? null
const operabile = (v) => !store.bloccata(chiavePrenotazione(v.idPrenotazione))
/** Versione letta su cui vale una conferma: stato, segnalazione, prezzo, medico, orari, pagamenti e rimborsi. */
const versione = (v) => JSON.stringify([v.stato, v.indisponibilitaSegnalata, v.prezzo, v.effettivo?.id ?? null, v.inizio.chiave, v.fine.chiave,
  v.pagamenti.map((p) => [p.idPagamento, p.stato]), v.rimborsi.map((r) => [r.idRimborso, r.stato])])

const TITOLI = {
  incassa: 'Registri un incasso in sede?', 'annulla-confermata': 'Annulli la prenotazione confermata?', 'mancata-erogazione': 'Registri la mancata erogazione?',
}
const ESITI_SCELTA = Object.freeze([
  { valore: 'PAZIENTE_ASSENTE', etichetta: 'Paziente assente', nota: 'Il paziente non si è presentato. Non dà diritto a rimborso.' },
  {
    valore: 'NON_EROGATA', etichetta: 'Non erogata',
    nota: "La prestazione non è stata erogata per indisponibilità del medico, senza prestazione sostitutiva. Se risulta un pagamento online riuscito il paziente può chiedere il rimborso; gli incassi in sede non sono rimborsabili tramite MedBook. Chiude anche un'eventuale segnalazione di indisponibilità.",
  },
])
const MODALITA_SCELTA = Object.freeze([{ valore: 'DESK_CONTANTI', etichetta: 'Contanti' }, { valore: 'DESK_POS', etichetta: 'POS' }])

function apriAzione(tipo, v) {
  apri({ tipo, id: v.idPrenotazione, voce: v, versione: versione(v), fase: 'conferma', scelta: '', errore: '' }, `${tipo}-${v.idPrenotazione}`)
}

async function sceltaMancante(a) {
  azione.value = { ...a, errore: a.tipo === 'incassa' ? 'Scegli la modalità del pagamento ricevuto.' : "Scegli l'esito da registrare." }
  await nextTick()
  document.querySelector(`input[name="scelta-${a.id}"]:not(:disabled)`)?.focus()
}

async function conferma() {
  const a = azione.value
  const v = a && voceCorrente(a.id)
  if (!a || a.fase !== 'conferma' || !puoAgire.value || !v || !operabile(v)) return
  const ctx = d.contestoCorrente()
  if (a.versione !== versione(v)) {
    chiudi()
    await d.mostra(ctx, 'errore', "I dati della prenotazione sono cambiati: controlla la scheda e riapri l'operazione.")
    return
  }
  if (a.tipo !== 'annulla-confermata' && !a.scelta) {
    await sceltaMancante(a)
    return
  }
  azione.value = { ...a, fase: 'invio', errore: '' }
  const esito = a.tipo === 'incassa' ? await store.incassa(v, a.scelta)
    : a.tipo === 'annulla-confermata' ? await store.annullaConfermata(v) : await store.registraMancataErogazione(v, a.scelta)
  if (!d.corrente(ctx)) return
  if (['eseguita', 'conflitto', 'non-trovata', 'non-valida'].includes(esito.kind)) rileggi()
  chiudi()
  switch (esito.kind) {
    case 'eseguita':
      await d.mostra(ctx, 'successo', a.tipo === 'incassa'
        ? `Incasso di ${formatEuro(v.prezzo)} (${ETICHETTE_MODALITA[a.scelta]}) registrato per la prenotazione n. ${v.idPrenotazione}.`
        : a.tipo === 'annulla-confermata' ? `Prenotazione n. ${v.idPrenotazione} annullata.`
          : `Registrato per la prenotazione n. ${v.idPrenotazione}: «${etichettaStato(a.scelta)}».`)
      break
    case 'conflitto':
    case 'non-trovata':
    case 'non-valida':
      await d.mostra(ctx, 'errore', `Operazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} `
        + "Se il rifiuto dipende dall'orario dell'appuntamento potrai riprovare quando la condizione sarà soddisfatta. L'elenco viene riletto.")
      break
    case 'incerto':
      await d.mostra(ctx, 'errore', "Non è stato possibile stabilire l'esito. L'operazione non è stata ripetuta: verificala negli esiti da verificare prima di altre operazioni su questa prenotazione.")
      break
    case 'sessione-assente':
    case 'obsoleto':
      break
    default:
      await d.mostra(ctx, 'errore', TESTI_NON_PROPONIBILE[esito.kind] ?? ESITI_COMUNI[esito.kind] ?? ESITI_COMUNI.rifiutata)
  }
}

async function verifica(x) {
  const ctx = d.contestoCorrente()
  avviso.value = null
  const esito = await store.verifica(x.chiave)
  if (!d.corrente(ctx)) return
  if (esito.kind === 'verificata') {
    // La verifica legge il giorno dell'appuntamento: l'elenco mostrato si rilegge con i propri filtri.
    rileggi()
    await d.mostra(ctx, 'info', testoVerificaDesk(esito))
  } else if (esito.kind === 'superata') await d.mostra(ctx, 'info', TESTO_SUPERATA)
  else if (esito.kind === 'errore') await d.mostra(ctx, 'errore', "Verifica non riuscita: le prenotazioni del giorno non sono leggibili. Riprova più tardi.")
}

function descriviIncerta(x) {
  const v = x.riepilogo.voce
  const base = `prenotazione n. ${x.idRisorsa} (${nome(v.paziente)}, ${quando(v)})`
  if (x.tipo === 'incassa') return `${base}, ${formatEuro(x.riepilogo.importo)} ${ETICHETTE_MODALITA[x.riepilogo.modalita] ?? ''}`.trim()
  if (x.tipo === 'mancata-erogazione') return `${base}, esito richiesto «${etichettaStato(x.riepilogo.esito)}»`
  return base
}

async function caricaOpzioni() {
  try {
    const medici = await fetchMedici(session.protectedRequest)
    if (!d.smontato()) opzioni.medici = medici
  } catch {
    // Filtro facoltativo: senza elenco dei medici resta la ricerca per periodo.
  }
}

onMounted(caricaOpzioni)
</script>

<template>
  <div class="pagina-desk">
    <header class="intestazione">
      <h1 tabindex="-1">Prenotazioni gestite</h1>
      <p class="sottotitolo">Prenotazioni confermate e loro esiti (annullate, effettuate, paziente assente, non erogate), con pagamenti e rimborsi.</p>
    </header>

    <details class="informazioni" data-informazioni>
      <summary>Informazioni operative</summary>
      <div class="informazioni-corpo">
        <p>Orari espressi nell'orario della struttura (fuso orario da confermare).</p>
        <div class="notice" data-vincoli>
          <p>
            I vincoli orari sono verificati dal server: l'annullamento è accettato solo prima dell'inizio dell'appuntamento, la mancata
            erogazione solo dopo la fine dello slot. Registra l'incasso prima che il medico registri la visita: una prenotazione
            «Effettuata» non accetta più pagamenti.
          </p>
        </div>
      </div>
    </details>

    <section class="card pannello" aria-labelledby="titolo-filtri-prenotazioni">
      <h2 id="titolo-filtri-prenotazioni">Periodo e medico</h2>
      <form class="form filtri" novalidate @submit.prevent="cerca">
        <div v-if="erroriFiltri.length" ref="erroreFiltriBox" class="notice error errore-filtri" role="alert" tabindex="-1"><p>Controlla i filtri indicati.</p></div>
        <fieldset class="fieldset-form periodo">
          <legend>Data dell'appuntamento (obbligatoria)</legend>
          <div class="choices">
            <label><input v-model="filtri.periodo" type="radio" name="periodo-prenotazioni" value="singola" /> Una data</label>
            <label><input v-model="filtri.periodo" type="radio" name="periodo-prenotazioni" value="intervallo" /> Un intervallo (al massimo {{ GIORNI_MASSIMI }} giorni)</label>
          </div>
          <div class="date">
            <div v-if="filtri.periodo === 'singola'" class="field">
              <label for="prenotazioni-data">Data</label>
              <input id="prenotazioni-data" v-model="filtri.data" type="date" :aria-invalid="errori.data ? 'true' : undefined"
                     :aria-describedby="errori.data ? 'errore-prenotazioni-data' : undefined" />
              <p v-if="errori.data" id="errore-prenotazioni-data" class="field-error">{{ errori.data }}</p>
            </div>
            <template v-else>
              <div class="field">
                <label for="prenotazioni-dal">Dal</label>
                <input id="prenotazioni-dal" v-model="filtri.dataDa" type="date" :aria-invalid="errori.dataDa ? 'true' : undefined"
                       :aria-describedby="errori.dataDa ? 'errore-prenotazioni-dal' : undefined" />
                <p v-if="errori.dataDa" id="errore-prenotazioni-dal" class="field-error">{{ errori.dataDa }}</p>
              </div>
              <div class="field">
                <label for="prenotazioni-al">Al (incluso)</label>
                <input id="prenotazioni-al" v-model="filtri.dataA" type="date" :aria-invalid="errori.dataA ? 'true' : undefined"
                       :aria-describedby="errori.dataA ? 'errore-prenotazioni-al' : undefined" />
                <p v-if="errori.dataA" id="errore-prenotazioni-al" class="field-error">{{ errori.dataA }}</p>
              </div>
            </template>
          </div>
        </fieldset>
        <div class="field medico">
          <label for="prenotazioni-medico">Medico effettivo (facoltativo)</label>
          <select id="prenotazioni-medico" v-model="filtri.medicoId" aria-describedby="nota-prenotazioni-medico">
            <option value="">Tutti i medici</option>
            <option v-for="m in opzioni.medici" :key="m.idUtente" :value="String(m.idUtente)">{{ nome(m) }}</option>
          </select>
          <p id="nota-prenotazioni-medico" class="small">Il filtro usa il medico che eroga la prestazione, anche dopo una sostituzione.</p>
        </div>
        <div class="invio"><button type="submit" class="button">Cerca</button></div>
      </form>
    </section>

    <NotificaInformativa v-if="avviso?.tipo === 'successo' && !session.state.passwordUncertain"
                          :notifica="avviso" @close="avviso = null" data-avviso-desk>
      <p class="testo-dati">{{ avviso.testo }}</p>
    </NotificaInformativa>
    <div v-else-if="avviso" ref="avvisoBox" tabindex="-1" :class="['notice', 'testo-dati', avviso.tipo === 'errore' ? 'error' : '']"
         :role="avviso.tipo === 'errore' ? 'alert' : 'status'" data-avviso-desk>
      <p>{{ avviso.testo }}</p>
      <p v-if="session.state.passwordUncertain"><RouterLink to="/area/password">Verifica il cambio password</RouterLink></p>
    </div>

    <EsitiDesk :tipi="['incassa', 'annulla-confermata', 'mancata-erogazione']" :descrizione="descriviIncerta" @verifica="verifica" />

    <section class="card pannello elenco" aria-labelledby="titolo-prenotazioni" :aria-busy="copertura && (elenco.stato === 'caricamento' || inAggiornamento) ? 'true' : 'false'">
      <h2 id="titolo-prenotazioni" ref="titoloElenco" tabindex="-1">Prenotazioni</h2>
      <p v-if="!copertura" data-senza-periodo>Indica una data o un intervallo e scegli «Cerca».</p>
      <template v-else>
        <StatoElencoDesk :elenco="elenco" :in-aggiornamento="inAggiornamento" :invalidata="invalidata" oggetto="le prenotazioni" @rileggi="d.recupera(rileggi)" />
        <template v-if="elenco.stato === 'pronto'">
          <p class="muted conteggio" role="status">{{ elenco.voci.length === 1 ? '1 prenotazione' : `${elenco.voci.length} prenotazioni` }} ({{ descrizioneCopertura }}).</p>
          <p v-if="session.state.passwordUncertain" class="small">Le operazioni sono sospese finché non verifichi l'esito del cambio password.</p>
          <p v-if="elenco.voci.length === 0">Nessuna prenotazione gestita nel periodo indicato.</p>
          <table v-else class="tabella" aria-labelledby="titolo-prenotazioni" data-tabella-prenotazioni>
            <colgroup>
              <col class="c-data ampia" /><col class="c-paziente" /><col class="c-prestazione ampia" /><col class="c-stato ampia" />
              <col class="c-pagamento ampia" /><col class="c-importo ampia" /><col class="c-azioni" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col" class="solo-ampio col-data">Data e ora</th>
                <th scope="col" class="col-paziente">Paziente<span class="solo-stretto">, data, prestazione e stato</span></th>
                <th scope="col" class="solo-ampio">Prestazione e medico</th>
                <th scope="col" class="solo-ampio col-stato">Stato</th>
                <th scope="col" class="solo-ampio col-pagamento">Pagamento</th>
                <th scope="col" class="solo-ampio col-importo">Importo</th>
                <th scope="col" class="col-azioni">Azioni</th>
              </tr>
            </thead>
            <tbody v-for="v in elenco.voci" :key="v.idPrenotazione" data-prenotazione-desk :data-id="v.idPrenotazione"
                   :class="{ aperta: aperti.has(v.idPrenotazione) }">
              <tr class="riepilogo">
                <td class="solo-ampio" data-campo="quando">
                  <time class="giorno" :datetime="v.inizio.testo">{{ giornoBreve(v.inizio) }}</time>
                  <span class="secondaria">{{ orario(v) }}</span>
                </td>
                <th scope="row" class="paziente">
                  <span class="numero-prenotazione" data-campo="numero-prenotazione">n. {{ v.idPrenotazione }}</span>
                  <span class="testo-dati">{{ nome(v.paziente) }}</span>
                  <span class="solo-stretto secondaria">{{ giornoBreve(v.inizio) }}, {{ orario(v) }}</span>
                  <span class="solo-stretto secondaria testo-dati">{{ prestazione(v) }}</span>
                  <span class="solo-stretto">
                    <span :class="['badge', `tono-${tonoStato(v.stato)}`]" :style="stileBadge(v.stato)">{{ etichettaStato(v.stato) }}</span>
                    <span v-if="v.indisponibilitaSegnalata" class="segnale">Indisponibilità segnalata</span>
                  </span>
                </th>
                <td class="solo-ampio">
                  <span class="testo-dati" data-campo="prestazione">{{ prestazione(v) }}</span>
                  <span class="secondaria testo-dati">{{ v.effettivo ? nome(v.effettivo) : 'Medico non assegnato' }}</span>
                </td>
                <td class="solo-ampio">
                  <span :class="['badge', `tono-${tonoStato(v.stato)}`]" :style="stileBadge(v.stato)" data-campo="stato">{{ etichettaStato(v.stato) }}</span>
                  <span v-if="v.indisponibilitaSegnalata" class="segnale">Indisponibilità segnalata</span>
                </td>
                <td class="solo-ampio" data-campo="riepilogo-pagamento">
                  <span v-for="(r, i) in riepilogoPagamento(v)" :key="i" :class="['voce-pagamento', `tono-${r.tono}`]">{{ r.testo }}</span>
                </td>
                <td class="solo-ampio importo" data-campo="prezzo">{{ formatEuro(v.prezzo) }}</td>
                <td class="azioni-riga">
                  <button type="button" class="dettagli" :data-dettagli="v.idPrenotazione"
                          :aria-expanded="aperti.has(v.idPrenotazione) ? 'true' : 'false'" :aria-controls="idDettaglio(v)"
                          :aria-disabled="aperti.has(v.idPrenotazione) && confermaIn(v) ? 'true' : undefined"
                          :aria-describedby="aperti.has(v.idPrenotazione) && confermaIn(v) ? `nota-dettagli-${v.idPrenotazione}` : undefined"
                          @click="commuta(v)">
                    Dettagli<span class="visually-hidden"> della prenotazione n. {{ v.idPrenotazione }}, {{ nome(v.paziente) }}</span>
                  </button>
                  <span v-if="aperti.has(v.idPrenotazione) && confermaIn(v)" :id="`nota-dettagli-${v.idPrenotazione}`" class="visually-hidden">
                    Conferma aperta: chiudila con «Indietro».
                  </span>
                  <span v-if="v.stato === 'CONFERMATA' && !operabile(v)" class="segnale tono-ambra">In corso o da verificare</span>
                </td>
              </tr>
              <tr :id="idDettaglio(v)" v-show="aperti.has(v.idPrenotazione)" class="dettaglio">
                <td colspan="7">
                  <div v-if="aperti.has(v.idPrenotazione)" class="dettaglio-corpo">
                    <dl class="facts">
                      <div><dt>Prenotazione</dt><dd>n. {{ v.idPrenotazione }}</dd></div>
                      <div><dt>Data e orario</dt><dd>{{ quando(v) }}</dd></div>
                      <div><dt>Medico titolare</dt><dd class="testo-dati" data-campo="titolare">{{ nome(v.titolare) }}</dd></div>
                      <div><dt>Medico effettivo</dt><dd class="testo-dati" data-campo="effettivo">{{ v.effettivo ? nome(v.effettivo) : 'Non assegnato' }}</dd></div>
                      <div><dt>Stanza</dt><dd class="testo-dati" data-campo="stanza">{{ stanza(v) }}</dd></div>
                      <div class="solo-stretto"><dt>Prezzo</dt><dd>{{ formatEuro(v.prezzo) }}</dd></div>
                    </dl>
                    <p v-if="v.indisponibilitaSegnalata" class="small" data-campo="indisponibilita">
                      <strong>Indisponibilità segnalata dal medico:</strong> gestiscila da <RouterLink to="/desk/segnalazioni">Segnalazioni</RouterLink>.
                    </p>
                    <div v-for="pg in v.pagamenti.filter(p => p.stato === 'PAGATO')" :key="pg.idPagamento" class="pagamento-registrato" :data-riepilogo-pagato="pg.idPagamento">
                      <h3>Pagamento registrato · {{ formatEuro(pg.importo) }} · {{ pg.modalita === 'DESK_CONTANTI' ? 'Contanti' : pg.modalita === 'DESK_POS' ? 'POS' : ETICHETTE_MODALITA[pg.modalita] }}</h3>
                      <p class="small">Pagamento n. {{ pg.idPagamento }} · {{ dataOra(pg.pagato) }}<template v-if="pg.registratoDa !== null"> · Operatore n. {{ pg.registratoDa }}</template></p>
                    </div>
                    <details class="approfondimento">
                      <summary>Informazioni di gestione</summary>
                    <p class="small" data-campo="gestione">
                      <template v-if="v.gestione">
                        Ultima gestione desk registrata: operatore n. {{ v.gestitaDa }}, {{ dataOra(v.gestione) }}. Non indica necessariamente chi ha
                        determinato lo stato attuale.
                      </template>
                      <template v-else>Nessuna gestione desk registrata.</template>
                    </p>

                    </details>
                    <details class="approfondimento">
                      <summary>Storico pagamenti e rimborsi</summary>
                    <div class="storico">
                      <div>
                        <h3>Pagamenti</h3>
                        <p v-if="v.pagamenti.length === 0" class="small" data-campo="pagamenti">Nessun pagamento registrato.</p>
                        <ul v-else class="small testo-dati righe" data-campo="pagamenti">
                          <li v-for="pg in v.pagamenti" :key="pg.idPagamento" :data-pagamento="pg.idPagamento">
                            n. {{ pg.idPagamento }}: {{ ETICHETTE_MODALITA[pg.modalita] }}, {{ formatEuro(pg.importo) }}, <strong>{{ ETICHETTE_PAGAMENTO[pg.stato] }}</strong>;
                            creato il {{ dataOra(pg.creato) }}<template v-if="pg.pagato">, pagato il {{ dataOra(pg.pagato) }}</template><template
                              v-if="pg.registratoDa !== null">, registrato in sede dall'operatore n. {{ pg.registratoDa }}</template>.
                          </li>
                        </ul>
                      </div>
                      <div>
                        <h3>Rimborsi</h3>
                        <p v-if="v.rimborsi.length === 0" class="small">Nessun rimborso registrato.</p>
                        <ul v-else class="small testo-dati righe" data-campo="rimborsi">
                          <li v-for="rb in v.rimborsi" :key="rb.idRimborso" :data-rimborso="rb.idRimborso">
                            n. {{ rb.idRimborso }} per il pagamento n. {{ rb.idPagamento }}: <strong>{{ ETICHETTE_RIMBORSO[rb.stato] }}</strong>, {{ formatEuro(rb.importo) }};
                            richiesto il {{ dataOra(rb.richiesto) }}<template v-if="rb.verificato">, deciso il {{ dataOra(rb.verificato) }} dall'operatore n. {{ rb.verificatoDa }}</template>.
                            {{ NOTE_RIMBORSO[rb.stato] }}
                            <template v-if="rb.stato === 'RICHIESTO'">La decisione si prende in <RouterLink to="/desk/rimborsi">Rimborsi da decidere</RouterLink>.</template>
                          </li>
                        </ul>
                      </div>
                    </div>
                    </details>
                    <p v-if="notaRimborso(v)" class="small" data-campo="nota-rimborso">{{ notaRimborso(v) }}</p>

                    <div v-if="v.stato === 'CONFERMATA'" class="operazioni">
                      <h3>Operazioni</h3>
                      <p v-if="!operabile(v)" class="small"><strong>Operazione in corso o da verificare.</strong></p>
                      <div v-else-if="azione?.id === v.idPrenotazione" class="confirm" role="group" :aria-labelledby="`titolo-conferma-${v.idPrenotazione}`"
                           :aria-busy="azione.fase === 'invio' ? 'true' : 'false'" :data-conferma="azione.tipo">
                        <h4 :id="`titolo-conferma-${v.idPrenotazione}`" :ref="impostaTitolo" tabindex="-1">{{ TITOLI[azione.tipo] }}</h4>
                        <p class="testo-dati">{{ nome(v.paziente) }} — {{ prestazione(v) }}, {{ quando(v) }}, medico {{ nome(v.effettivo) }}. Prenotazione n. {{ v.idPrenotazione }}.</p>
                        <template v-if="azione.tipo === 'incassa'">
                          <p>Importo: <strong data-campo="importo-incasso">{{ formatEuro(v.prezzo) }}</strong> (prezzo della prenotazione).</p>
                          <fieldset class="fieldset-form" :aria-describedby="azione.errore ? `errore-scelta-${v.idPrenotazione}` : undefined">
                            <legend>Modalità del pagamento ricevuto</legend>
                            <div class="choices">
                              <label v-for="m in MODALITA_SCELTA" :key="m.valore">
                                <input v-model="azione.scelta" type="radio" :name="`scelta-${v.idPrenotazione}`" :value="m.valore" :disabled="azione.fase === 'invio'" />
                                {{ m.etichetta }}
                              </label>
                            </div>
                          </fieldset>
                          <p class="small">Registra un pagamento già ricevuto in sede: non avvia una transazione POS e non movimenta denaro.</p>
                        </template>
                        <template v-else-if="azione.tipo === 'mancata-erogazione'">
                          <fieldset class="fieldset-form" :aria-describedby="azione.errore ? `errore-scelta-${v.idPrenotazione}` : undefined">
                            <legend>Esito da registrare</legend>
                            <label v-for="e in ESITI_SCELTA" :key="e.valore" class="opzione-esito">
                              <input v-model="azione.scelta" type="radio" :name="`scelta-${v.idPrenotazione}`" :value="e.valore"
                                     :disabled="azione.fase === 'invio' || (e.valore === 'PAZIENTE_ASSENTE' && v.indisponibilitaSegnalata)"
                                     :aria-labelledby="`titolo-esito-${e.valore}-${v.idPrenotazione}`"
                                     :aria-describedby="`nota-esito-${e.valore}-${v.idPrenotazione}`" />
                              <span class="testo-opzione">
                                <strong :id="`titolo-esito-${e.valore}-${v.idPrenotazione}`">{{ e.etichetta }}</strong>
                                <span :id="`nota-esito-${e.valore}-${v.idPrenotazione}`" class="small descrizione-esito">
                                  {{ e.nota }}<template v-if="e.valore === 'PAZIENTE_ASSENTE' && v.indisponibilitaSegnalata"> Non disponibile: c'è un'indisponibilità del medico ancora da gestire.</template>
                                </span>
                              </span>
                            </label>
                          </fieldset>
                          <p class="small">Il server la accetta solo dopo la fine dello slot.</p>
                        </template>
                        <template v-else>
                          <p class="small">
                            La prenotazione passerà a «Annullata» e non sarà più erogata. Il server la accetta solo prima dell'inizio dell'appuntamento;
                            non risultano pagamenti registrati o in attesa, quindi non ci sono importi da rimborsare.
                          </p>
                        </template>
                        <p v-if="azione.errore" :id="`errore-scelta-${v.idPrenotazione}`" class="field-error" role="alert">{{ azione.errore }}</p>
                        <div class="actions">
                          <button type="button" class="button" :disabled="azione.fase === 'invio' || !puoAgire" @click="conferma">
                            {{ azione.fase === 'invio' ? 'Operazione in corso…' : { incassa: 'Registra incasso', 'annulla-confermata': 'Annulla prenotazione', 'mancata-erogazione': 'Registra esito' }[azione.tipo] }}
                          </button>
                          <button type="button" class="button secondary" :disabled="azione.fase === 'invio'" @click="annulla">Indietro</button>
                        </div>
                      </div>
                      <template v-else>
                        <div class="actions">
                          <button v-if="!motivoIncasso(v)" type="button" class="button" :data-origine="`incassa-${v.idPrenotazione}`" :disabled="!puoAgire || !libero"
                                  @click="apriAzione('incassa', v)">Registra incasso<span class="visually-hidden"> per {{ nome(v.paziente) }}</span></button>
                          <button v-if="!motivoAnnullamento(v)" type="button" class="button secondary" :data-origine="`annulla-confermata-${v.idPrenotazione}`"
                                  :disabled="!puoAgire || !libero" @click="apriAzione('annulla-confermata', v)">Annulla prenotazione<span class="visually-hidden"> di {{ nome(v.paziente) }}</span></button>
                        </div>
                        <p v-if="v.pagamenti.some(p => ['PAGATO', 'IN_ATTESA'].includes(p.stato))" class="small" data-pagamento-presente>Pagamento già presente: nuovo incasso e annullamento non disponibili.</p>
                        <ul v-if="!v.pagamenti.some(p => ['PAGATO', 'IN_ATTESA'].includes(p.stato)) && (motivoIncasso(v) || motivoAnnullamento(v))" class="small" data-campo="non-disponibili">
                          <li v-if="motivoIncasso(v)">Incasso non disponibile: {{ TESTI_NON_PROPONIBILE[motivoIncasso(v)] }}</li>
                          <li v-if="motivoAnnullamento(v)">Annullamento non disponibile: {{ TESTI_NON_PROPONIBILE[motivoAnnullamento(v)] }}</li>
                        </ul>
                        <div class="esito-prestazione">
                          <h4>Esito della prestazione</h4>
                          <p v-if="motivoEsito(v)" class="small">Mancata erogazione non disponibile: {{ TESTI_NON_PROPONIBILE[motivoEsito(v)] }}</p>
                          <button v-if="!motivoEsito(v)" type="button" class="button secondary" :data-origine="`mancata-erogazione-${v.idPrenotazione}`"
                                  :disabled="!puoAgire || !libero" @click="apriAzione('mancata-erogazione', v)">Registra mancata erogazione<span class="visually-hidden"> per {{ nome(v.paziente) }}</span></button>
                        </div>

                      </template>
                    </div>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </template>
    </section>
  </div>
</template>

<style scoped>
/*
 * Tabella compatta con dettagli apribili. Colori, caratteri
 * e controlli vengono dal tema comune (base.css); qui solo disposizione e dettagli della tabella.
 */
.pagina-desk { container: desk / inline-size; }

.intestazione h1 { margin: 0 0 0.25rem; }
.sottotitolo { margin: 0 0 1rem; color: var(--color-muted); }
h2 { margin: 0 0 0.75rem; }

.pannello { padding: 1rem 1.25rem; }

/* Informazioni operative */
.informazioni { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 0.75rem; margin-bottom: 1rem; }
.informazioni summary { cursor: pointer; padding: 0.625rem 1.25rem; font-weight: 600; }
.informazioni-corpo { padding: 0 1.25rem 1rem; }
.informazioni-corpo > p { margin: 0 0 0.75rem; color: var(--color-muted); }
.informazioni-corpo .notice { margin-bottom: 0; }

/* Filtri: affiancati quando c'è spazio, impilati altrimenti */
.filtri { max-width: none; display: flex; flex-wrap: wrap; align-items: flex-start; gap: 1rem 1.25rem; }
.errore-filtri { flex: 1 1 100%; margin-bottom: 0; }
.periodo { flex: 2 1 22rem; gap: 0.5rem; }
.date { display: flex; flex-wrap: wrap; gap: 0.75rem 1rem; }
.date .field { flex: 1 1 10rem; }
.medico { flex: 1 1 14rem; align-self: end; }
.invio { flex: 0 0 auto; align-self: end; padding-bottom: 1.6rem; }

/* Elenco */
.conteggio { font-size: 0.8125rem; margin: 0 0 0.75rem; }
.tabella { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 0.8125rem; }
.tabella thead th {
  text-align: left; font-weight: 500; font-size: 0.75rem; color: var(--color-muted);
  padding: 0.5rem 0.5rem; border-bottom: 1px solid var(--color-border); background: var(--color-surface-alt);
}
/* Larghezze sulle colonne: la riga di dettaglio (colspan 7) non crea colonne senza misura. */
.c-data { width: 6.75rem; }
.c-stato { width: 7.25rem; }
.c-pagamento { width: 9rem; }
.c-importo { width: 5.5rem; }
.c-azioni { width: 6.5rem; }
.tabella thead th.col-importo { text-align: right; }
.tabella td, .tabella tbody th { padding: 0.625rem 0.5rem; vertical-align: top; text-align: left; border-bottom: 1px solid var(--color-border); }
.tabella tbody th { font-weight: 600; }
.tabella tbody.aperta .riepilogo > * { border-bottom-color: transparent; background: var(--color-surface-alt); }
.giorno, .secondaria, .voce-pagamento, .segnale { display: block; }
.giorno { font-weight: 500; }
.numero-prenotazione { display: block; font-weight: 700; margin-bottom: .2rem; }
.secondaria { color: var(--color-muted); font-weight: 400; }
.importo { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.voce-pagamento + .voce-pagamento { margin-top: 0.125rem; }

.badge {
  display: inline-block; max-width: 100%; overflow-wrap: anywhere; padding: 0.125rem 0.5rem; border-radius: 999px; font-size: 0.75rem; font-weight: 500;
  line-height: 1.4; border: 1px solid transparent;
}
.badge.tono-blu { background: #eef4ff; color: #2b3ac7; border-color: #c7d7fe; }
.badge.tono-verde { background: #ecfdf3; color: #067647; border-color: #abefc6; }
.badge.tono-ambra { background: #fffaeb; color: #93370d; border-color: #fedf89; }
.badge.tono-rosso { background: #fef3f2; color: #b42318; border-color: #fecdca; }
.badge.tono-grigio { background: #f2f4f7; color: #344054; border-color: #e4e7ec; }
.voce-pagamento.tono-verde { color: #067647; font-weight: 500; }
.voce-pagamento.tono-ambra { color: #93370d; font-weight: 500; }
.voce-pagamento.tono-neutro { color: var(--color-muted); }
.segnale { margin-top: 0.375rem; font-size: 0.75rem; font-weight: 500; color: #93370d; }

.dettagli {
  font: inherit; font-weight: 500; cursor: pointer; color: var(--color-primary);
  background: var(--color-surface); border: 1px solid var(--color-border-strong); border-radius: var(--radius); min-height: 2rem; padding: 0.25rem 0.625rem;
  display: inline-flex; align-items: center; gap: 0.375rem;
}
.dettagli::after { content: ""; width: 0.4rem; height: 0.4rem; border: solid currentColor; border-width: 0 2px 2px 0; transform: rotate(45deg) translateY(-2px); }
.dettagli[aria-expanded="true"]::after { transform: rotate(-135deg) translateY(-1px); }
.dettagli[aria-disabled="true"] { cursor: not-allowed; color: var(--color-muted); }
.dettagli:hover { border-color: var(--color-primary); }

/* Dettaglio espanso */
.dettaglio > td { background: var(--color-surface-alt); padding: 0 0.75rem 1rem; }
.dettaglio-corpo { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 1rem; display: grid; gap: 0.75rem; }
.dettaglio-corpo :is(p, ul) { margin: 0; }
.dettaglio-corpo h3 { font-size: 0.8125rem; font-weight: 600; margin: 0 0 0.375rem; }
.facts dt { font-size: 0.8125rem; }
.facts dd { font-weight: 500; }
.pagamento-registrato { background: #ecfdf3; color: #085d4d; border: 1px solid #abefc6; border-radius: .5rem; padding: .875rem; overflow-wrap: anywhere; }
.dettaglio-corpo .pagamento-registrato h3 { font-size: 1rem; }
.approfondimento > summary { cursor: pointer; font-weight: 600; padding-block: .5rem; }
.approfondimento > :not(summary) { margin-top: .5rem; }
.esito-prestazione { border-top: 1px solid var(--color-border); margin-top: .875rem; padding-top: .75rem; }
.esito-prestazione h4 { margin: 0 0 .5rem; font-size: .875rem; }
.storico { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(16rem, 100%), 1fr)); gap: 0.75rem 1.5rem; }
.righe { padding-left: 1.1rem; display: grid; gap: 0.25rem; }
.operazioni { border-top: 1px solid var(--color-border); padding-top: 0.75rem; }
.operazioni .actions { margin-top: 0.25rem; }
.operazioni > .small { margin-top: 0.5rem; }
.operazioni ul.small { padding-left: 1.1rem; }
.confirm { border: 1px solid var(--color-info-border); background: var(--color-info-bg); border-radius: 0.5rem; padding: 0.875rem 1rem; display: grid; gap: 0.5rem; }
.opzione-esito { display: grid; grid-template-columns: 1.1875rem minmax(0, 1fr); align-items: start; gap: .625rem; padding-block: .5rem; cursor: pointer; }
.opzione-esito input[type="radio"] { appearance: auto; inline-size: 1.1875rem; block-size: 1.1875rem; min-inline-size: 0; min-block-size: 0; padding: 0; margin: .125rem 0 0; accent-color: var(--color-primary); }
.opzione-esito input:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 3px; }
.opzione-esito:has(input:disabled) { cursor: default; }
.opzione-esito:has(input:disabled) strong { color: var(--color-muted); }
.descrizione-esito { display: block; margin-top: .25rem; }
.testo-opzione { min-inline-size: 0; overflow-wrap: anywhere; }
.confirm h4 { margin: 0; font-size: 0.875rem; }
.confirm p { margin: 0; }

/* Rappresentazioni: le colonne secondarie diventano testo nel riepilogo o nel dettaglio */
.solo-stretto { display: none; }
@container desk (max-width: 44rem) {
  /* Celle delle colonne secondarie: restano nella griglia (le colonne non si spostano) ma senza
     misura, invisibili e fuori dall'albero di accessibilità; i loro dati sono nel riepilogo o nel dettaglio. */
  .tabella .solo-ampio { width: 0; padding: 0; border: 0; font-size: 0; line-height: 0; visibility: hidden; overflow: hidden; }
  .tabella .solo-ampio > * { display: none; }
  .solo-stretto { display: block; }
  .tabella thead th.col-paziente .solo-stretto { display: inline; }
  col.ampia { width: 0; }
  .c-azioni { width: 6.25rem; }
  .paziente .solo-stretto + .solo-stretto { margin-top: 0.375rem; }
  .dettaglio > td { padding: 0 0.25rem 0.75rem; }
  .dettaglio-corpo { padding: 0.75rem; }
  .invio { padding-bottom: 0; }
  .pannello { padding: 1rem; }
}
</style>
