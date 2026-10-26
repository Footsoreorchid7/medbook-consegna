// Dati sintetici per i test gestione medici: nessuna identità o credenziale reale.
export { ADMIN, admin, amministrativo, desk, pagina, PASSWORD, RICERCA } from './b9a-fixtures.js'

/** MedicoResponse amministrativa (9 campi). */
export const medicoAdmin = (id, extra = {}) => ({
  idUtente: id, nome: 'Elena', cognome: `Medica${id}`, email: `medico${id}@example.test`, specializzazione: 'Cardiologia',
  numeroIscrizioneOrdine: `ORD-${id}`, stato: 'ATTIVO', paziente: false, ruoloGestionale: null, ...extra,
})
export const prestazione = (id, extra = {}) => ({ idPrestazione: id, nome: `Prestazione ${id}`, descrizione: null, durata: 30, prezzo: 80, ...extra })
export const dettaglioAdmin = (id, prestazioni = [prestazione(2)], extra = {}) => ({ medico: medicoAdmin(id, extra), prestazioniAutorizzate: prestazioni })

export const MEDICI = 'GET /api/medici'
export const MEDICI_Q = (q) => `GET /api/medici?${q}`
export const MEDICO = (id) => `GET /api/medici/${id}`
export const CREA_MEDICO = 'POST /api/medici'
export const COLLEGA = (id) => `POST /api/medici/${id}/collegamento`
export const DATI = (id) => `PUT /api/medici/${id}`
export const STATO_MEDICO = (id) => `PUT /api/medici/${id}/stato`
export const ABILITA = (id) => `PUT /api/medici/${id}/prestazioni`
export const CATALOGO = 'GET /api/prestazioni'
export const conflitto = () => new Response(JSON.stringify({ error: 'Operazione in conflitto.' }), { status: 409, headers: { 'Content-Type': 'application/json' } })
