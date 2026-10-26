const EURO = new Intl.NumberFormat('it-IT', { style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * Solo formattazione dell'importo ricevuto dal backend (DECIMAL con due decimali):
 * nessun calcolo economico nel frontend.
 */
export function formatEuro(value) {
  return typeof value === 'number' && Number.isFinite(value) ? EURO.format(value) : '—'
}
