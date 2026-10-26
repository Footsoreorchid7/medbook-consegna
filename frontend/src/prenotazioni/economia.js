import { STATI_BLOCCANTI, importoValido } from '../api/pagamenti.js'
import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'

/** Etichette di pagamenti e rimborsi. FALLITO non è mai un incasso; APPROVATO non è una restituzione. */
export const ETICHETTE_MODALITA = Object.freeze({
  ONLINE: 'Online (simulato)',
  DESK_POS: 'In sede, POS',
  DESK_CONTANTI: 'In sede, contanti',
})

export const ETICHETTE_PAGAMENTO = Object.freeze({
  IN_ATTESA: 'In attesa',
  PAGATO: 'Pagato',
  FALLITO: 'Non riuscito (nessun importo incassato)',
})

export const ETICHETTE_RIMBORSO = Object.freeze({
  RICHIESTO: 'Richiesto, in attesa di decisione',
  APPROVATO: 'Approvato',
  RESPINTO: 'Respinto',
  IN_ELABORAZIONE: 'In elaborazione',
  RIMBORSATO: 'Rimborsato',
  FALLITO: 'Rimborso non riuscito',
})

/** Spiegazione dello stato del rimborso, dove l'etichetta da sola potrebbe essere fraintesa. */
export const NOTE_RIMBORSO = Object.freeze({
  RICHIESTO: 'La richiesta sarà valutata dalla segreteria.',
  APPROVATO: 'La richiesta è stata approvata: questo non significa che il denaro sia già stato restituito. La restituzione dell’importo sarà gestita dal centro medico al di fuori di MedBook.',
  RESPINTO: 'La richiesta non è stata accolta.',
  IN_ELABORAZIONE: 'La restituzione è in elaborazione e non risulta ancora completata.',
  RIMBORSATO: 'La restituzione risulta eseguita.',
  FALLITO: 'La restituzione non è riuscita.',
})

export const dataOra = (x) => `${formatLocalDate(x)}, ${formatLocalTime(x)}`

/**
 * Lettura utilizzabile per autorizzare un'azione: riuscita e confermata dall'ultima richiesta
 * (`aggiornata`). Dati visibili durante una rilettura in background non bastano.
 */
const valida = (lettura) => lettura?.stato === 'pronto' && lettura.aggiornata === true

/**
 * Condizioni mostrabili per proporre il pagamento online, con soli dati letti dal server:
 * prenotazione CONFERMATA con prezzo valido e storico aggiornato senza pagamenti PAGATO o
 * IN_ATTESA. Non garantisce l'eseguibilità: il backend controlla anche ciò che il paziente
 * non vede. Se lo storico non è verificato, il pagamento non è proposto.
 * @param {{stato:string, prezzo:number}} prenotazione
 * @param {{stato:string, voci:Array}} storico lettura dei pagamenti
 */
export function pagamentoProponibile(prenotazione, storico) {
  return prenotazione?.stato === 'CONFERMATA' && importoValido(prenotazione.prezzo)
    && valida(storico) && !storico.voci.some((x) => STATI_BLOCCANTI.includes(x.stato))
}

/**
 * Pagamento per cui proporre la richiesta di rimborso, oppure null. Requisiti allineati al
 * backend: prenotazione NON_EROGATA; tra i pagamenti non FALLITO uno solo, PAGATO, ONLINE e
 * dell'importo della prenotazione; nessuna richiesta di rimborso già presente per quel
 * pagamento. Richiede storico pagamenti ed elenco rimborsi entrambi verificati.
 */
export function pagamentoRimborsabile(prenotazione, storico, rimborsi) {
  if (prenotazione?.stato !== 'NON_EROGATA' || !valida(storico) || !valida(rimborsi)) return null
  const validi = storico.voci.filter((x) => x.stato !== 'FALLITO')
  if (validi.length !== 1) return null
  const [pagamento] = validi
  if (pagamento.stato !== 'PAGATO' || pagamento.modalita !== 'ONLINE' || pagamento.importo !== prenotazione.prezzo) return null
  if (rimborsi.voci.some((r) => r.idPagamento === pagamento.idPagamento)) return null
  return pagamento
}

/** Stati in cui possono esistere pagamenti: prima della conferma non ce ne sono. */
export const haStoricoPagamenti = (stato) => stato !== 'RICHIESTA' && stato !== 'RIFIUTATA'
