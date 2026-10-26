/**
 * Collegamenti dell'interfaccia, definiti una sola volta per la colonna di navigazione e per
 * l'area riservata. Ogni gruppo corrisponde a una capacità reale della sessione (capabilitiesOf)
 * e contiene solo rotte esistenti protette dalla stessa capacità: nessun permesso implicito
 * (l'amministratore non eredita le pagine desk). Il backend resta autorevole.
 *
 * `etichetta` è il testo dell'area riservata, `breve` quello della navigazione laterale.
 */
export const GRUPPI = Object.freeze([
  {
    capacita: 'paziente', profilo: 'Paziente', titolo: 'Prenotazioni',
    descrizione: 'Prenotazioni, pagamenti, rimborsi e referti personali.',
    link: [
      { to: '/paziente/prenota', icona: 'calendario', descrizione: 'Consulta gli orari e scegli un appuntamento da richiedere.', etichetta: 'Richiedi un appuntamento', breve: 'Richiedi un appuntamento' },
      { to: '/paziente/prenotazioni', icona: 'elenco', descrizione: 'Gestisci richieste, pagamenti e referti disponibili.', etichetta: 'Le mie prenotazioni', breve: 'Le mie prenotazioni' },
      { to: '/paziente/rimborsi', icona: 'rimborso', descrizione: 'Consulta le richieste di rimborso e il loro stato.', etichetta: 'I miei rimborsi', breve: 'I miei rimborsi' },
    ],
  },
  {
    capacita: 'medico', profilo: 'Medico', titolo: 'Attività del medico',
    descrizione: 'Agenda, disponibilità e referti delle visite.',
    link: [
      { to: '/medico/agenda', icona: 'calendario', descrizione: 'Consulta gli appuntamenti, registra le visite e carica i referti.', etichetta: 'Agenda', breve: 'Agenda' },
      { to: '/medico/disponibilita', icona: 'orari', descrizione: 'Gestisci gli slot e le disponibilità del tuo calendario.', etichetta: 'Le mie disponibilità', breve: 'Le mie disponibilità' },
    ],
  },
  {
    capacita: 'desk', profilo: 'Operatore desk', titolo: 'Attività di segreteria',
    descrizione: 'Gestione delle prenotazioni, degli incassi in sede e dei rimborsi.',
    link: [
      { to: '/desk/pazienti', icona: 'persone', descrizione: 'Cerca i pazienti e reimposta la password degli account consentiti.', etichetta: 'Pazienti', breve: 'Pazienti' },
      { to: '/desk/richieste', icona: 'elenco', descrizione: 'Esamina e gestisci le richieste di appuntamento.', etichetta: 'Richieste di prenotazione', breve: 'Richieste' },
      { to: '/desk/segnalazioni', icona: 'avviso', descrizione: 'Gestisci le indisponibilità segnalate e le sostituzioni.', etichetta: 'Segnalazioni di indisponibilità', breve: 'Segnalazioni' },
      { to: '/desk/prenotazioni', icona: 'calendario', descrizione: 'Consulta prenotazioni e gestisci incassi in sede ed esiti.', etichetta: 'Prenotazioni gestite: incassi, annullamenti ed esiti', breve: 'Prenotazioni gestite' },
      { to: '/desk/rimborsi', icona: 'rimborso', descrizione: 'Consulta lo storico dei rimborsi e decidi le richieste pendenti.', etichetta: 'Rimborsi', breve: 'Rimborsi' },
      { to: '/desk/disponibilita', icona: 'orari', descrizione: 'Assegna prestazioni e stanze alle disponibilità da configurare.', etichetta: 'Configurazione delle disponibilità', breve: 'Configura disponibilità' },
      { to: '/desk/medici', icona: 'persone', descrizione: 'Consulta medici e prestazioni abilitate.', etichetta: 'Medici', breve: 'Medici' },
    ],
  },
  {
    capacita: 'admin', profilo: 'Amministratore', titolo: 'Amministrazione',
    descrizione: 'Gestione di utenti, medici e abilitazioni, stanze e prestazioni.',
    link: [
      { to: '/admin/utenti', icona: 'persone', descrizione: 'Gestisci account, ruoli e stato di accesso degli utenti.', etichetta: 'Gestione utenti', breve: 'Utenti' },
      { to: '/admin/medici', icona: 'persone', descrizione: 'Gestisci i profili dei medici e le loro abilitazioni.', etichetta: 'Medici e abilitazioni', breve: 'Medici e abilitazioni' },
      { to: '/admin/stanze', icona: 'stanze', descrizione: 'Consulta e aggiorna le stanze del centro.', etichetta: 'Stanze', breve: 'Stanze' },
      { to: '/admin/prestazioni', icona: 'elenco', descrizione: 'Gestisci il catalogo delle prestazioni, durata e prezzo.', etichetta: 'Prestazioni', breve: 'Gestione prestazioni' },
    ],
  },
])

/** Pagine pubbliche, consultabili anche senza autenticazione. */
export const PUBBLICI = Object.freeze([
  { to: '/prestazioni', breve: 'Prestazioni' },
  { to: '/disponibilita', breve: 'Disponibilità' },
])

/** Collegamenti dell'account, disponibili con una sessione autenticata. */
export const ACCOUNT = Object.freeze([
  { to: '/area', breve: 'Area riservata' },
  { to: '/area/password', breve: 'Cambia password' },
])

/** Gruppi consentiti dalle capacità correnti, nell'ordine di definizione. */
export const gruppiConsentiti = (capacita) => GRUPPI.filter((g) => capacita?.[g.capacita] === true)

/** "Paziente · Medico": profili attivi, oppure null se l'account non ne ha. */
export function descrizioneProfili(capacita) {
  const profili = gruppiConsentiti(capacita).map((g) => g.profilo)
  return profili.length ? profili.join(' · ') : null
}

/**
 * Collegamento della pagina corrente, anche per le pagine di dettaglio sotto lo stesso percorso
 * (es. /desk/medici/5 → Medici). `esatto` distingue la pagina stessa (aria-current="page").
 */
export function corrispondenza(percorso, to) {
  if (percorso === to) return 'esatto'
  return percorso.startsWith(`${to}/`) ? 'sotto' : null
}
