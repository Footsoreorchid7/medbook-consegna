/** Testi della gestione dei medici . */

import { RUOLO } from './formato.js'

export const STATO_MEDICO = Object.freeze({ ATTIVO: 'Attivo', INATTIVO: 'Inattivo' })
/** Stato operativo del medico, distinto dallo stato di accesso dell'account. Un valore anomalo è mostrato com'è. */
export const statoMedico = (m) => STATO_MEDICO[m.stato] ?? `Valore non riconosciuto («${m.stato}»)`
export const nome = (m) => `${m.nome} ${m.cognome}`
export const ruoloGestionale = (r) => (r ? RUOLO[r] : 'Nessuno')

export const OPERAZIONI_MEDICO = Object.freeze({
  'password-medico': 'Reset della password',
  'medico-crea': 'Creazione del medico', collega: 'Collegamento del profilo medico', personali: 'Modifica dei dati personali',
  professionali: 'Modifica dei dati professionali', 'stato-medico': 'Cambio dello stato operativo', abilitazione: "Modifica di un'abilitazione",
})

/**
 * Possibili cause di un 409: il server risponde sempre «Operazione in conflitto.» senza dettaglio,
 * quindi la pagina elenca le cause previste dal contratto senza sceglierne una.
 */
export const CAUSE_CONFLITTO = Object.freeze({
  'medico-crea': 'email o numero di iscrizione già presenti',
  collega: 'profilo medico già presente, ruolo gestionale, account non attivo o numero di iscrizione già presente',
  personali: 'email già presente, account con ruolo gestionale o stato del medico non valido',
  professionali: 'numero di iscrizione già presente o stato del medico non valido',
  'stato-medico': 'prenotazioni bloccanti, account non attivo per la riattivazione o stato del medico non valido',
  abilitazione: 'abilitazione già presente o assente, disponibilità o prenotazioni associate alla prestazione',
})

/** Descrizione di un tentativo incerto: solo dati del tentativo, mai la password. */
export function descriviIncertaMedico(x) {
  const r = x.riepilogo
  switch (x.tipo) {
    case 'password-medico': return `account del medico n. ${x.idUtente} (${r.nome} ${r.cognome})`
    case 'medico-crea': return `medico ${r.email} (${r.nome} ${r.cognome}, n. iscrizione ${r.numeroIscrizioneOrdine})`
    case 'collega': return `utente n. ${x.idUtente} (${r.nome} ${r.cognome}), n. iscrizione ${r.richiesto.numeroIscrizioneOrdine}`
    case 'personali': return `medico n. ${x.idUtente}, dati personali da «${r.precedente.nome} ${r.precedente.cognome}, ${r.precedente.email}» a «${r.richiesto.nome} ${r.richiesto.cognome}, ${r.richiesto.email}»`
    case 'professionali': return `medico n. ${x.idUtente} (${r.nome} ${r.cognome}), dati professionali da «${r.precedente.specializzazione}, ${r.precedente.numeroIscrizioneOrdine}» a «${r.richiesto.specializzazione}, ${r.richiesto.numeroIscrizioneOrdine}»`
    case 'stato-medico': return `medico n. ${x.idUtente} (${r.nome} ${r.cognome}), stato operativo da «${STATO_MEDICO[r.precedente] ?? r.precedente}» a «${STATO_MEDICO[r.richiesto]}»`
    case 'abilitazione': return `medico n. ${x.idUtente} (${r.nome} ${r.cognome}), ${r.operazione === 'ASSEGNA' ? 'assegnazione' : 'rimozione'} di «${r.nomePrestazione}»`
    default: return `operazione n. ${x.idUtente ?? ''}`
  }
}

const NUOVO = 'Un nuovo invio è possibile solo sui dati aggiornati, con una nuova scelta e conferma.'

/** Messaggio di verifica: riscontro corrente, mai attribuito con certezza al tentativo. */
export function testoVerificaMedico(x, esito, voce) {
  const r = x.riepilogo
  const id = voce?.idUtente ?? x.idUtente
  if (x.tipo === 'medico-crea') {
    if (esito === 'assente') return `Al momento della verifica nessun account risulta con email ${r.email}: la creazione non risulta applicata. ${NUOVO}`
    if (esito === 'senza-profilo') return `Al momento della verifica esiste l'account n. ${id} con email ${voce.email}, senza profilo medico: la creazione del medico non risulta applicata. L'account potrebbe essere preesistente. ${NUOVO}`
    return `Al momento della verifica esiste il medico n. ${id} con email ${voce.email}${esito === 'come-richiesto' ? ' e i dati richiesti' : ', con dati diversi da quelli richiesti'}. Questo non prova che sia stato creato dal tentativo.`
  }
  if (esito === 'assente') {
    return x.tipo === 'collega'
      ? `Al momento della verifica l'utente n. ${id} non ha un profilo medico: il collegamento non risulta applicato. ${NUOVO}`
      : `Il profilo medico n. ${id} non risulta più: l'esito non può essere stabilito.`
  }
  const base = `Al momento della verifica il medico n. ${id} (${nome(voce)})`
  const desc = {
    collega: 'ha un profilo medico',
    personali: `ha dati personali «${voce.nome} ${voce.cognome}, ${voce.email}»`,
    professionali: `ha dati professionali «${voce.specializzazione}, ${voce.numeroIscrizioneOrdine}»`,
    'stato-medico': `è nello stato operativo «${statoMedico(voce)}»`,
    abilitazione: `${(esito === 'come-richiesto') === (r.operazione === 'ASSEGNA') ? 'è' : 'non è'} abilitato a «${r.nomePrestazione}»`,
  }[x.tipo]
  if (esito === 'come-richiesto') return `${base} ${desc}, come richiesto. Questo non prova chi abbia eseguito l'operazione.`
  if (x.tipo === 'collega') return `${base} ha un profilo medico con dati professionali diversi da quelli richiesti: non è possibile stabilire quali operazioni siano avvenute né da chi.`
  if (esito === 'precedente') return `${base} ${desc}, come prima del tentativo: l'operazione non risulta applicata. ${NUOVO}`
  return `${base} ${desc}, diversamente sia dal valore precedente sia da quello richiesto: non è possibile stabilire quali operazioni siano avvenute né da chi. ${NUOVO}`
}
