/** Presentazione comune: non estende gli stati restituiti dal contratto agenda. */
export const STATI_PRENOTAZIONI_UI = Object.freeze([
  { stato: 'RICHIESTA', nome: 'Richiesta', breve: 'Rich.', colore: 'ambra', fondo: '#fff0c2', testo: '#633600', accento: '#9a6700' },
  { stato: 'CONFERMATA', nome: 'Confermata', breve: 'Conf.', colore: 'blu', fondo: '#eef2ff', testo: '#263ea5', accento: '#425bc0' },
  { stato: 'EFFETTUATA', nome: 'Effettuata', breve: 'Eff.', colore: 'verde', fondo: '#def7ee', testo: '#085d4d', accento: '#16765f' },
  { stato: 'RIFIUTATA', nome: 'Rifiutata', breve: 'Rif.', colore: 'rosso', fondo: '#fef3f2', testo: '#912018', accento: '#b42318' },
  { stato: 'ANNULLATA', nome: 'Annullata', breve: 'Ann.', colore: 'grigio', fondo: '#e4e7ec', testo: '#344054', accento: '#667085' },
  { stato: 'PAZIENTE_ASSENTE', nome: 'Paziente assente', breve: 'Ass.', colore: 'rosso', fondo: '#fef3f2', testo: '#912018', accento: '#b42318' },
  { stato: 'NON_EROGATA', nome: 'Non erogata', breve: 'N.e.', colore: 'viola', fondo: '#f3e8ff', testo: '#6b218a', accento: '#8841a4' },
].map(Object.freeze))
export const STATI_AGENDA_UI = Object.freeze(STATI_PRENOTAZIONI_UI.filter(s => s.stato !== 'RIFIUTATA'))
export const statoAgendaUI = (stato) => STATI_PRENOTAZIONI_UI.find(s => s.stato === stato)
export function stileStatoAgenda(stato) {
  const s = statoAgendaUI(stato)
  return s ? { '--agenda-fondo': s.fondo, '--agenda-testo': s.testo, '--agenda-accento': s.accento } : {}
}
