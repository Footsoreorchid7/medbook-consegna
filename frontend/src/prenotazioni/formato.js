import { formatLocalDate, formatLocalTime } from '../utils/datetime.js'

/** Etichette degli stati. RICHIESTA è una richiesta in attesa, non un appuntamento confermato. */
export const ETICHETTE_STATO = Object.freeze({
  RICHIESTA: 'Richiesta inviata, in attesa di conferma',
  CONFERMATA: 'Confermata',
  RIFIUTATA: 'Rifiutata',
  ANNULLATA: 'Annullata',
  EFFETTUATA: 'Effettuata',
  PAZIENTE_ASSENTE: 'Paziente assente',
  NON_EROGATA: 'Non erogata',
})

export const etichettaStato = (stato) => ETICHETTE_STATO[stato] ?? stato

/** "venerdì 16/10/2026, 09:00–10:00": orario della struttura, senza conversioni. */
export function dataOrario(inizio, fine) {
  const giorno = formatLocalDate(inizio)
  const orario = `${formatLocalTime(inizio)}–${formatLocalTime(fine)}`
  return inizio.date === fine.date ? `${giorno}, ${orario}` : `${giorno}, ${formatLocalTime(inizio)} – ${formatLocalDate(fine)}, ${formatLocalTime(fine)}`
}

const nome = (m) => `${m.nome} ${m.cognome}`

/**
 * Medici da mostrare: un solo nome se l'effettivo manca o coincide con il titolare,
 * altrimenti titolare ed effettivo distinti.
 * @returns {Array<{etichetta:string, nome:string}>}
 */
export function mediciDaMostrare(p) {
  if (!p.effettivo || p.effettivo.id === p.titolare.id) return [{ etichetta: 'Medico', nome: nome(p.titolare) }]
  return [
    { etichetta: 'Medico titolare', nome: nome(p.titolare) },
    { etichetta: 'Medico effettivo', nome: nome(p.effettivo) },
  ]
}

export const nomePrestazione = (p) => p.nomePrestazione ?? 'Prestazione non disponibile'
