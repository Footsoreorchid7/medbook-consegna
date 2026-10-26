// Dati sintetici per i test cataloghi: nessuna identità o credenziale reale.
export { admin, desk } from './b9a-fixtures.js'

export const stanza = (id, extra = {}) => ({ idStanza: id, nome: `Ambulatorio ${id}`, descrizione: null, ...extra })
export const prestazioneAdmin = (id, extra = {}) => ({ idPrestazione: id, nome: `Visita ${id}`, descrizione: null, durata: 30, prezzo: 80, ...extra })

export const STANZE = 'GET /api/stanze'
export const CREA_STANZA = 'POST /api/stanze'
export const STANZA = (id) => `PUT /api/stanze/${id}`
export const PRESTAZIONI = 'GET /api/prestazioni'
export const CREA_PRESTAZIONE = 'POST /api/prestazioni'
export const PRESTAZIONE = (id) => `PUT /api/prestazioni/${id}`
