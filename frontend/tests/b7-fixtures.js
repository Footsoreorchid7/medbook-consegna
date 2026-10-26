// Dati sintetici per i test nessuna identità reale.
import { utente } from './helpers.js'

export const OPERATORE = 3
export const desk = (extra = {}) => utente({ idUtente: OPERATORE, nome: 'Dario', cognome: 'Sintetico', paziente: false, medico: false, ruoloGestionale: 'OPERATORE_DESK', ...extra })
export const admin = () => utente({ idUtente: 1, nome: 'Ada', cognome: 'Sintetica', paziente: false, medico: false, ruoloGestionale: 'AMMINISTRATORE' })

export const richiesta = (id, extra = {}) => ({
  indisponibilitaSegnalata: false,
  idPrenotazione: id,
  stato: 'RICHIESTA',
  dataOraRichiesta: '2026-10-19T08:00:00',
  dataOraInizio: '2026-11-02T09:00:00',
  dataOraFine: '2026-11-02T10:00:00',
  prezzo: 80,
  idPaziente: 70 + id,
  nomePaziente: 'Paola',
  cognomePaziente: `Prova${id}`,
  idMedico: 4,
  nomeMedico: 'Elena',
  cognomeMedico: 'Sintetica',
  idPrestazione: 2,
  nomePrestazione: 'Visita sintetica',
  durataPrestazione: 30,
  idStanza: 3,
  nomeStanza: 'Ambulatorio 1',
  ...extra,
})

/** Segnalazione pendente CONFERMATA (effettivo = titolare 4) oppure RICHIESTA (senza effettivo). */
export const segnalazione = (id, extra = {}) => ({
  idPaziente: 70 + id, nomePaziente: 'Paola', cognomePaziente: `Prova${id}`,
  idPrenotazione: id,
  stato: 'CONFERMATA',
  idDisponibilita: 100 + id,
  dataOraInizio: '2026-11-03T09:00:00',
  dataOraFine: '2026-11-03T10:00:00',
  idMedicoTitolare: 4,
  nomeMedicoTitolare: 'Elena',
  cognomeMedicoTitolare: 'Sintetica',
  idMedicoEffettivo: 4,
  nomeMedicoEffettivo: 'Elena',
  cognomeMedicoEffettivo: 'Sintetica',
  idPrestazione: 2,
  nomePrestazione: 'Visita sintetica',
  idStanza: 3,
  nomeStanza: 'Ambulatorio 1',
  indisponibilitaSegnalata: true,
  ...extra,
})
export const segnalazioneRichiesta = (id, extra = {}) => segnalazione(id, {
  stato: 'RICHIESTA', idMedicoEffettivo: null, nomeMedicoEffettivo: null, cognomeMedicoEffettivo: null, ...extra,
})

export const rimborsoDesk = (id, extra = {}) => ({
  idRimborso: id,
  idPrenotazione: 20 + id,
  idPagamento: 40 + id,
  stato: 'RICHIESTO',
  importo: 80,
  motivazione: 'Visita non erogata',
  richiestoIl: '2026-10-18T10:00:00',
  verificatoIl: null,
  idPaziente: 70,
  nomePaziente: 'Paola',
  cognomePaziente: 'Prova',
  idPrestazione: 2,
  nomePrestazione: 'Visita sintetica',
  dataOraInizio: '2026-10-15T09:00:00',
  dataOraFine: '2026-10-15T10:00:00',
  modalitaPagamento: 'ONLINE',
  ...extra,
})

export const medicoDesk = (id, extra = {}) => ({
  idUtente: id, nome: 'Medico', cognome: `Sintetico${id}`, specializzazione: 'Cardiologia', numeroIscrizioneOrdine: `ORD-${id}`, stato: 'ATTIVO', ...extra,
})
export const prestazione = (id, extra = {}) => ({ idPrestazione: id, nome: `Prestazione ${id}`, descrizione: null, durata: 30, prezzo: 80, ...extra })
export const dettaglio = (id, prestazioni = [prestazione(2)], extra = {}) => ({ medico: medicoDesk(id, extra), prestazioniAutorizzate: prestazioni, paziente: false })
export const stanzaDesk = (id) => ({ idStanza: id, nome: `Stanza ${id}`, descrizione: null })

export const gestione = (id, stato, extra = {}) => ({ idPrenotazione: id, stato, idOperatore: OPERATORE, dataOraGestione: '2026-10-19T11:00:00', ...extra })
export const decisione = (id, stato, extra = {}) => ({ idRimborso: id, stato, idOperatore: OPERATORE, verificatoIl: '2026-10-19T11:00:00', ...extra })

export const RICHIESTE = 'GET /api/prenotazioni/richieste'
export const SEGNALAZIONI = 'GET /api/prenotazioni/segnalazioni-indisponibilita'
export const RIMBORSI = 'GET /api/rimborsi/richieste'
export const MEDICI = 'GET /api/medici'
export const STANZE = 'GET /api/stanze'
export const PRESTAZIONI = 'GET /api/prestazioni'
export const OP = (id, tipo) => `POST /api/prenotazioni/${id}/${tipo}`
export const SOSTITUISCI = (id) => `POST /api/prenotazioni/${id}/sostituisci-medico`
export const DECIDI = (id, tipo) => `POST /api/rimborsi/${id}/${tipo}`
export const CONFIGURA = (id) => `PUT /api/disponibilita/${id}/configurazione`
export const DISPONIBILITA = (id) => `GET /api/disponibilita?medicoId=${id}`
export const MEDICO = (id) => `GET /api/medici/${id}`
