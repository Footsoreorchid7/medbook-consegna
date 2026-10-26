/** Testi della gestione utenti . */

export const RUOLO = Object.freeze({ AMMINISTRATORE: 'Amministratore', OPERATORE_DESK: 'Operatore desk' })
export const STATO_ACCESSO = Object.freeze({ ATTIVO: 'Attivo', DISABILITATO: 'Disabilitato' })
export const ruolo = (r) => (r ? RUOLO[r] : 'Nessun ruolo gestionale')
export const nome = (u) => `${u.nome} ${u.cognome}`
export const altroRuolo = (r) => (r === 'AMMINISTRATORE' ? 'OPERATORE_DESK' : 'AMMINISTRATORE')
export const altroStato = (s) => (s === 'ATTIVO' ? 'DISABILITATO' : 'ATTIVO')

export const OPERAZIONI = Object.freeze({ crea: 'Creazione dell\'account', ruolo: 'Cambio di ruolo', stato: 'Cambio dello stato di accesso' })

/** Descrizione di un tentativo incerto: solo dati del tentativo, mai la password. */
export function descriviIncerta(x) {
  const r = x.riepilogo
  if (x.tipo === 'crea') return `account ${r.email} (${r.nome} ${r.cognome}, ${RUOLO[r.ruolo]})`
  const da = x.tipo === 'ruolo' ? RUOLO[r.precedente] : STATO_ACCESSO[r.precedente]
  const a = x.tipo === 'ruolo' ? RUOLO[r.richiesto] : STATO_ACCESSO[r.richiesto]
  return `utente n. ${x.idUtente} (${r.nome} ${r.cognome}), da «${da}» a «${a}»`
}

const NUOVO = 'Un nuovo invio è possibile solo sui dati aggiornati, con una nuova scelta e conferma.'

/**
 * Messaggio di una verifica: riscontro corrente letto dalla ricerca amministrativa, mai
 * attribuito con certezza al tentativo né a un amministratore.
 */
export function testoVerifica(x, esito, voce) {
  const r = x.riepilogo
  if (x.tipo === 'crea') {
    return esito === 'presente'
      ? `Al momento della verifica esiste l'account n. ${voce.idUtente} con email ${voce.email} (${ruolo(voce.ruolo)}, ${STATO_ACCESSO[voce.statoAccesso]}). `
        + 'Questo non prova che sia stato creato dal tentativo: controlla i dati prima di altre operazioni.'
      : `Al momento della verifica nessun account risulta con email ${r.email}: la creazione non risulta applicata. ${NUOVO}`
  }
  if (esito === 'assente') return `L'utente n. ${x.idUtente} non risulta nella ricerca: l'esito non può essere stabilito.`
  const etichetta = x.tipo === 'ruolo' ? 'il ruolo' : 'lo stato di accesso'
  const valore = x.tipo === 'ruolo' ? ruolo(voce.ruolo) : STATO_ACCESSO[voce.statoAccesso]
  const base = `Al momento della verifica ${etichetta} dell'utente n. ${x.idUtente} (${nome(voce)}) è «${valore}»`
  if (esito === 'come-richiesto') return `${base}, come richiesto. Questo non prova chi abbia eseguito la modifica.`
  if (esito === 'precedente') return `${base}, come prima del tentativo: la modifica non risulta applicata. ${NUOVO}`
  return `${base}, diverso sia dal valore precedente sia da quello richiesto: non è possibile stabilire quali operazioni siano avvenute né da chi. ${NUOVO}`
}

export const TESTO_SUPERATA = 'Il risultato della verifica è stato superato da dati più recenti e non è stato usato. Il blocco resta: ripeti la verifica.'
export const TESTO_NON_CONCLUSIVA = 'La verifica non è conclusiva: troppi risultati per questa email. Il blocco resta: controlla con una ricerca più precisa e ripeti la verifica.'
export const TESTO_INCOERENTE = "Nessuna conclusione: l'account trovato con questa email risulta, in una lettura più recente, con un'altra email o assente. L'email potrebbe appartenere ad altri account. Il blocco resta: ripeti la verifica."
export const TESTO_ERRORE_VERIFICA = 'Verifica non riuscita: la ricerca non è leggibile. Riprova più tardi.'

/** Messaggi comuni degli esiti delle scritture. */
export function testoEsito(esito) {
  switch (esito.kind) {
    case 'rifiutata': return 'Operazione non autorizzata. Il tuo account potrebbe non essere più un amministratore attivo: se il problema persiste, esci e accedi di nuovo.'
    case 'password-incerta': return "Le operazioni sono sospese finché non verifichi l'esito del cambio password."
    case 'occupato': return "Un'operazione su questo utente è già in corso."
    case 'bloccata': return "L'esito di un'operazione precedente su questo utente o su questa email è ancora da verificare."
    case 'attesa': return `Il server ha chiesto di attendere: nessun invio, nuove operazioni possibili tra ${esito.retryAfter} secondi.`
    case 'non-valida': return 'Richiesta non valida: controlla i dati.'
    case 'conflitto':
    case 'non-trovata': return `Operazione rifiutata dal server${esito.serverMessage ? `: ${esito.serverMessage}` : '.'} Gli elenchi vengono riletti.`
    case 'incerto': return "Non è stato possibile stabilire l'esito. L'operazione non è stata ripetuta: verificala negli esiti da verificare prima di altre operazioni."
      + (esito.retryAfter ? ` Il server ha chiesto di attendere ${esito.retryAfter} secondi prima di nuove operazioni; la scadenza non chiarisce l'esito.` : '')
    default: return 'Operazione non eseguita.'
  }
}
