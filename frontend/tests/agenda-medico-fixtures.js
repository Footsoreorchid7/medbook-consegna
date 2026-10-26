// Dati sintetici per i test nessuna identità, dato clinico o documento reale.
import { utente } from './helpers.js'

export const MEDICO = 4

export const medico = (extra = {}) => utente({ idUtente: MEDICO, nome: 'Elena', cognome: 'Sintetica', paziente: false, medico: true, ...extra })

/** Voce di AppuntamentoAgendaResponse; per default CONFERMATA del medico 4 come effettivo. */
export const voce = (id, extra = {}) => ({
  indisponibilitaSegnalata: false,
  refertoPresente: false,
  idPrenotazione: id,
  idDisponibilita: 100 + id,
  stato: 'CONFERMATA',
  dataOraInizio: '2026-10-19T09:00:00',
  dataOraFine: '2026-10-19T10:00:00',
  idMedicoTitolare: MEDICO,
  idMedicoEffettivo: MEDICO,
  idPrestazione: 2,
  nomePrestazione: 'Visita sintetica',
  durataPrestazione: 60,
  idStanza: 3,
  nomeStanza: 'Ambulatorio 1',
  idPaziente: 70 + id,
  nomePaziente: 'Paola',
  cognomePaziente: `Prova${id}`,
  ...extra,
})

export const richiesta = (id, extra = {}) => voce(id, { stato: 'RICHIESTA', idMedicoEffettivo: null, ...extra })
export const effettuata = (id, extra = {}) => voce(id, { stato: 'EFFETTUATA', ...extra })

export const AGENDA = `GET /api/prenotazioni/agenda?medicoId=${MEDICO}`
export const agendaGiorno = (data) => `${AGENDA}&data=${data}`
export const agendaPeriodo = (da, a) => `${AGENDA}&dataDa=${da}&dataA=${a}`
export const VISITA = (id) => `POST /api/prenotazioni/${id}/registra-visita`
export const SEGNALA = 'POST /api/prenotazioni/segnala-indisponibilita'
export const REFERTO = (id) => `POST /api/prenotazioni/${id}/referto`

export const registrata = (id) => ({ idPrenotazione: id, stato: 'EFFETTUATA' })
export const caricato = (id, extra = {}) => ({
  idReferto: 9, idPrenotazione: id, nomeFile: 'referto-00000000-0000-0000-0000-000000000000.pdf', caricatoIl: '2026-10-19T11:00:00', ...extra,
})

/** PDF sintetico minimo come File (la firma basta ai controlli locali; il backend resta autorevole). */
export const pdf = (nome = 'referto-sintetico.pdf', contenuto = '%PDF-1.7\n% documento sintetico\n%%EOF\n') =>
  new File([contenuto], nome, { type: 'application/pdf' })
