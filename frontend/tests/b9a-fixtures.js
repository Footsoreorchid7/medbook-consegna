// Dati sintetici per i test gestione utenti: nessuna identità o credenziale reale.
import { utente } from './helpers.js'

export const ADMIN = 1
export const admin = (extra = {}) => utente({ idUtente: ADMIN, nome: 'Ada', cognome: 'Sintetica', paziente: false, medico: false, ruoloGestionale: 'AMMINISTRATORE', ...extra })
export const desk = () => utente({ idUtente: 3, nome: 'Dario', cognome: 'Sintetico', paziente: false, medico: false, ruoloGestionale: 'OPERATORE_DESK' })

/** AccountInternoResponse (8 campi). */
export const account = (id, extra = {}) => ({
  idUtente: id, nome: 'Nome', cognome: `Prova${id}`, email: `utente${id}@example.test`,
  ruoloGestionale: 'OPERATORE_DESK', paziente: false, medico: false, statoAccesso: 'ATTIVO', ...extra,
})
/** UtenteAmministrativoResponse (7 campi). */
export const amministrativo = (id, extra = {}) => ({
  idUtente: id, nome: 'Nome', cognome: `Prova${id}`, codiceFiscale: null, email: `utente${id}@example.test`,
  ruoloGestionale: 'OPERATORE_DESK', statoAccesso: 'ATTIVO', ...extra,
})
export const pagina = (risultati, prossimoDopoId = null) => ({ risultati, prossimoDopoId })

/** Codice fiscale sintetico valido (checksum calcolato dal validatore). */
export const CF = 'SNTPLA85D52Z999'

export const GESTIONALI = 'GET /api/utenti'
export const CREA = 'POST /api/utenti'
export const RUOLO = (id) => `PUT /api/utenti/${id}/ruolo`
export const STATO = (id) => `PUT /api/utenti/${id}/stato`
export const RICERCA = (query) => `GET /api/utenti/ricerca?${query}`
export const PASSWORD = 'Segreta-di-prova-123'
