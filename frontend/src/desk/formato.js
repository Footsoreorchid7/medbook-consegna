import { ETICHETTE_MODALITA, ETICHETTE_PAGAMENTO, ETICHETTE_RIMBORSO } from '../prenotazioni/economia.js'
import { dataOrario, etichettaStato } from '../prenotazioni/formato.js'
import { formatEuro } from '../utils/money.js'
import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'

/** Formati condivisi delle pagine desk . Orari della struttura, senza conversioni. */
export const nome = (p) => (p ? `${p.nome} ${p.cognome}` : 'Non indicato')
export const quando = (x) => dataOrario(x.inizio, x.fine)
export const dataOra = (x) => `${formatLocalDate(x)}, ${formatLocalTime(x)}`
export const prestazione = (x) => x.prestazione?.nome ?? 'Prestazione non configurata'
export const stanza = (x) => (x.stanza ? (x.stanza.nome ?? 'Stanza assegnata') : 'Non assegnata')

/** Operazione sulle richieste non applicata, per tipo. */
const NON_APPLICATA = {
  conferma: 'la conferma non risulta applicata', rifiuta: 'il rifiuto non risulta applicato', annulla: "l'annullamento non risulta applicato",
}
/** Operazione sulle segnalazioni, per tipo. */
const RICHIESTA_SEGNALAZIONE = { sostituisci: 'la sostituzione richiesta', 'annulla-segnalata': "l'annullamento richiesto" }
const STATO_SEGNALAZIONE = { RICHIESTA: '«Richiesta»', CONFERMATA: '«Confermata»' }

/**
 * Messaggio di una verifica manuale: descrive il riscontro corrente (`voce`, se la risorsa è
 * ancora nell'elenco), mai attribuito con certezza al tentativo né a un operatore. Un nuovo invio
 * resta possibile solo con dati aggiornati, nuova scelta e conferma esplicite.
 */
export function testoVerifica(x, esito, voce = null) {
  const r = x.riepilogo
  const n = x.idRisorsa
  const nuovo = 'Un nuovo invio è possibile solo sui dati aggiornati, con una nuova scelta e conferma.'
  switch (`${x.verificaSu.startsWith('disponibilita:') ? 'disponibilita' : x.verificaSu}:${esito}`) {
    case 'richieste:presente':
      return `Al momento della verifica la prenotazione n. ${n} è ancora tra le richieste in attesa, nello stato «Richiesta»: ${NON_APPLICATA[x.tipo]}. ${nuovo}`
    case 'richieste:assente':
      return `La prenotazione n. ${n} non è più tra le richieste in attesa: non è possibile stabilire se sia stata confermata, rifiutata o annullata, né da chi.`
    case 'segnalazioni:medico-sostituto':
      return `Al momento della verifica la prenotazione n. ${n} risulta assegnata a ${nome(voce.indisponibile)}, il sostituto richiesto, `
        + `che ha a sua volta segnalato la propria indisponibilità (stato ${STATO_SEGNALAZIONE[voce.stato]}). È una nuova segnalazione, distinta dal tentativo verificato: `
        + `il riscontro non stabilisce con certezza chi abbia eseguito la sostituzione. Per gestire la nuova segnalazione usa i dati aggiornati dell'elenco, con una nuova scelta e conferma.`
    case 'segnalazioni:invariata':
      return `Al momento della verifica la prenotazione n. ${n} è ancora tra le segnalazioni con lo stesso medico indisponibile (${nome(voce.indisponibile)}) `
        + `e lo stesso stato (${STATO_SEGNALAZIONE[voce.stato]}) rilevati al momento del tentativo: il riscontro corrente non mostra ${RICHIESTA_SEGNALAZIONE[x.tipo]}. ${nuovo}`
    case 'segnalazioni:cambiata':
      return `Al momento della verifica la prenotazione n. ${n} è tra le segnalazioni con medico ${nome(voce.indisponibile)} e stato ${STATO_SEGNALAZIONE[voce.stato]}, `
        + (x.tipo === 'sostituisci'
          ? `diversi sia da quelli rilevati al momento del tentativo (${nome(r.indisponibile)}, ${STATO_SEGNALAZIONE[r.stato]}) sia dal sostituto richiesto`
          : `diversi da quelli rilevati al momento del tentativo (${nome(r.indisponibile)}, ${STATO_SEGNALAZIONE[r.stato]})`)
        + `: non è possibile stabilire quali operazioni siano avvenute né da chi. ${nuovo}`
    case 'segnalazioni:assente':
      return `La prenotazione n. ${n} non è più tra le segnalazioni pendenti: non è possibile stabilire quale operazione sia avvenuta né da chi.`
    case 'rimborsi:presente':
      return `Al momento della verifica il rimborso n. ${n} è ancora tra le richieste da decidere, nello stato «Richiesto»: la decisione non risulta registrata. ${nuovo}`
    case 'rimborsi:assente':
      return `Il rimborso n. ${n} non è più tra le richieste da decidere: non è possibile stabilire se sia stato approvato o respinto, né da chi.`
    case 'disponibilita:come-richiesto':
      return `La disponibilità n. ${n} risulta configurata con ${r.prestazione.nome} e ${r.stanza.nome}. Questo non prova chi abbia eseguito la configurazione.`
    case 'disponibilita:diversa':
      return `La disponibilità n. ${n} risulta configurata con valori diversi da quelli richiesti: non è più configurabile. Non è possibile stabilire chi l'abbia configurata.`
    case 'disponibilita:non-configurata':
      return `Al momento della verifica la disponibilità n. ${n} è ancora senza prestazione né stanza: la configurazione non risulta applicata. ${nuovo}`
    case 'disponibilita:assente':
      return `La disponibilità n. ${n} non risulta più nell'elenco del medico: l'esito non può essere stabilito.`
    default:
      return 'Verifica completata.'
  }
}

export const TESTO_SUPERATA = 'Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Il blocco resta: ripeti la verifica.'
export const TESTO_ERRORE_VERIFICA = "Verifica non riuscita: l'elenco non è leggibile. Riprova più tardi."

// ---------------------------------------------------------------- prenotazioni gestite

export const OPERAZIONI_B8 = Object.freeze({
  incassa: 'Incasso in sede',
  'annulla-confermata': 'Annullamento della prenotazione confermata',
  'mancata-erogazione': 'Registrazione della mancata erogazione',
})

const stato = (s) => `«${etichettaStato(s)}»`
const pagamento = (p) => `n. ${p.idPagamento} (${ETICHETTE_MODALITA[p.modalita]}, ${formatEuro(p.importo)}, ${ETICHETTE_PAGAMENTO[p.stato]})`

/**
 * Condizioni non soddisfatte nei dati letti: nessuna richiesta inviata. Ripetono le regole del
 * backend, che resta autorevole anche per i vincoli orari non controllati qui.
 */
export const TESTI_NON_PROPONIBILE = Object.freeze({
  'non-confermata': 'La prenotazione non è più nello stato «Confermata» secondo i dati letti: aggiorna l’elenco.',
  segnalata: "La prenotazione ha un'indisponibilità del medico ancora da gestire: gestiscila dalle segnalazioni.",
  'pagamento-presente': "Risulta già un pagamento registrato o in attesa: un nuovo incasso non è ammesso.",
  'nessun-importo': 'Il prezzo della prenotazione non prevede un importo da incassare.',
  'pagamenti-bloccanti': "Risulta un pagamento registrato o in attesa: l'annullamento non è ammesso e non è previsto un rimborso per le prenotazioni confermate già pagate.",
  'pagamento-in-attesa': "Risulta un pagamento in attesa: la mancata erogazione non può essere registrata.",
  'non-valida': 'Dati non validi: nessuna richiesta inviata.',
})

/**
 * Messaggio di una verifica (e del recupero di una decisione di rimborso tramite la lettura
 * desk). Descrive il riscontro corrente del giorno dell'appuntamento; non attribuisce l'esito al
 * tentativo né a un operatore, e l'assenza non prova che il tentativo non sia stato eseguito.
 * @param {object} risultato { esito, voce, rimborso, nuovi, incerta }
 */
export function testoVerificaDesk({ esito, voce, rimborso, nuovi = [], incerta: x }) {
  const n = x.verificaSu === 'rimborsi' ? x.riepilogo.idPrenotazione : x.idRisorsa
  const nuovo = 'Un nuovo invio è possibile solo sui dati aggiornati, con una nuova scelta e conferma.'
  const nonAttribuito = 'Il riscontro non stabilisce se derivi da questo tentativo né chi abbia eseguito l’operazione.'
  if (esito === 'assente') {
    return x.verificaSu === 'rimborsi'
      ? `Nelle prenotazioni gestite del giorno dell'appuntamento non risultano la prenotazione n. ${n} con il rimborso n. ${x.idRisorsa}: l'esito della decisione non può essere stabilito.`
      : `La prenotazione n. ${n} non risulta tra le prenotazioni gestite del giorno dell'appuntamento: l'esito non può essere stabilito e l'assenza non indica che l'operazione non sia avvenuta.`
  }
  const corrente = `Al momento della verifica la prenotazione n. ${n} è nello stato ${stato(voce.stato)}`
  switch (`${x.tipo}:${esito}`) {
    case 'incassa:nessun-nuovo':
      return `${corrente} e non risultano pagamenti diversi da quelli noti prima del tentativo: l'incasso non risulta registrato. ${nuovo}`
    case 'incassa:nuovo-pagato':
      return `${corrente} e risultano nuovi pagamenti rispetto a quelli noti prima del tentativo: ${nuovi.map(pagamento).join('; ')}. ${nonAttribuito}`
    case 'incassa:nuovo-non-pagato':
      return `${corrente} e risultano nuovi tentativi di pagamento non riusciti o in attesa: ${nuovi.map(pagamento).join('; ')}. L'incasso in sede richiesto non risulta registrato. ${nonAttribuito}`
    case 'annulla-confermata:annullata':
      return `${corrente}. ${nonAttribuito}`
    case 'annulla-confermata:confermata':
      return `${corrente}: l'annullamento non risulta applicato. ${nuovo}`
    case 'annulla-confermata:altro-stato':
      return `${corrente}, diverso da quello richiesto: non è più annullabile. ${nonAttribuito}`
    case 'mancata-erogazione:come-richiesto':
      return `${corrente}, come richiesto. ${nonAttribuito}`
    case 'mancata-erogazione:confermata':
      return `${corrente}: l'esito ${stato(x.riepilogo.esito)} non risulta registrato. ${nuovo}`
    case 'mancata-erogazione:altro-stato':
      return `${corrente}, diverso dall'esito richiesto ${stato(x.riepilogo.esito)}. ${nonAttribuito}`
    case 'approva:presente':
    case 'respingi:presente':
      return `Al momento della verifica il rimborso n. ${x.idRisorsa} risulta ancora «Richiesto»: la decisione non risulta registrata. ${nuovo}`
    case 'approva:come-richiesto':
    case 'respingi:come-richiesto':
    case 'approva:diverso':
    case 'respingi:diverso':
      return `Al momento della verifica il rimborso n. ${x.idRisorsa} risulta nello stato «${ETICHETTE_RIMBORSO[rimborso.stato]}»`
        + `${esito === 'diverso' ? ', diverso dalla decisione richiesta' : ''}. ${nonAttribuito}`
    default:
      return 'Verifica completata.'
  }
}
