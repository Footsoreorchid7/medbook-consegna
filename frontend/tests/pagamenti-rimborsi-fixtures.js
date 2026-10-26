// Dati sintetici per i test nessuna identità o dato economico reale.
import { appuntamento } from './prenotazioni-paziente-fixtures.js'

export const confermata = (id = 12, extra = {}) => appuntamento(id, {
  stato: 'CONFERMATA', idMedicoEffettivo: 4, nomeMedicoEffettivo: 'Elena', cognomeMedicoEffettivo: 'Sintetica', prezzo: 80, ...extra,
})

export const pagamento = (id, extra = {}) => ({
  idPagamento: id, idPrenotazione: 12, modalita: 'ONLINE', importo: 80, stato: 'PAGATO',
  creatoIl: '2026-10-16T10:00:00', pagatoIl: '2026-10-16T10:00:00', ...extra,
})

export const fallito = (id, extra = {}) => pagamento(id, { stato: 'FALLITO', pagatoIl: null, ...extra })

export const rimborso = (id, extra = {}) => ({
  idRimborso: id, idPrenotazione: 12, idPagamento: 5, stato: 'RICHIESTO', importo: 80, motivazione: 'Visita non erogata',
  richiestoIl: '2026-10-17T09:00:00', verificatoIl: null, ...extra,
})

export const referto = (extra = {}) => ({ idReferto: 3, idPrenotazione: 12, nomeFile: 'referto-sintetico.pdf', caricatoIl: '2026-10-18T11:30:00', ...extra })

export const PDF = '%PDF-1.7\n% documento sintetico\n%%EOF\n'
export const pdfResponse = (body = PDF, headers = {}) => new Response(body, {
  status: 200, headers: { 'Content-Type': 'application/pdf', 'Content-Length': String(new TextEncoder().encode(body).length), ...headers },
})
