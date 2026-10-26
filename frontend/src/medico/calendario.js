/** Calendario civile: nessuna interpretazione dei LocalDateTime come istanti UTC. */
export const MESI = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre']
export function periodoMese(mese) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(mese) || Number(mese.slice(0, 4)) < 1000) return null
  const [anno, numero] = mese.split('-').map(Number)
  const giorni = numero === 2 ? (anno % 4 === 0 && (anno % 100 !== 0 || anno % 400 === 0) ? 29 : 28) : [4, 6, 9, 11].includes(numero) ? 30 : 31
  // Il contratto agenda non ammette il giorno finale 9999-12-31.
  if (mese === '9999-12') return null
  return { dataDa: `${mese}-01`, dataA: `${mese}-${giorni}`, giorni }
}
export function spostaMese(mese, delta) {
  if (!periodoMese(mese)) return null
  const [anno, numero] = mese.split('-').map(Number)
  const indice = anno * 12 + numero - 1 + delta
  const prossimo = `${String(Math.floor(indice / 12)).padStart(4, '0')}-${String(indice % 12 + 1).padStart(2, '0')}`
  return periodoMese(prossimo) ? prossimo : null
}
export function celleMese(mese) {
  const periodo = periodoMese(mese)
  if (!periodo) return []
  const [anno, numero] = mese.split('-').map(Number)
  // Solo calcolo del giorno della settimana di una data civile, mai conversione di orari API.
  const vuote = (new Date(Date.UTC(anno, numero - 1, 1)).getUTCDay() + 6) % 7
  return [...Array(vuote).fill(null), ...Array.from({ length: periodo.giorni }, (_, i) => `${mese}-${String(i + 1).padStart(2, '0')}`)]
}
