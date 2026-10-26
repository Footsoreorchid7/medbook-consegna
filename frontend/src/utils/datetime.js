/**
 * Date e orari del backend sono LocalDateTime senza fuso ("2026-10-15T09:00:00"): sono
 * l'orario locale della struttura. Vengono trattati come testo, scomponendo la stringa:
 * niente new Date(testo), toISOString() o conversioni UTC che sposterebbero data o ora.
 * Il fuso clinico resta da confermare.
 */

const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/
const LOCAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/
const GIORNI = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato']

function validDate(year, month, day) {
  if (month < 1 || month > 12 || day < 1) return false
  // Calendario puro, in UTC: serve solo a conoscere la lunghezza del mese.
  return day <= new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/** @returns {{date:string, year:number, month:number, day:number, hour:number, minute:number}|null} */
export function parseLocalDateTime(value) {
  if (typeof value !== 'string') return null
  const m = LOCAL_DATE_TIME.exec(value)
  if (!m) return null
  const [year, month, day, hour, minute] = m.slice(1, 6).map(Number)
  const second = m[6] === undefined ? 0 : Number(m[6])
  if (!validDate(year, month, day) || hour > 23 || minute > 59 || second > 59) return null
  return { date: `${m[1]}-${m[2]}-${m[3]}`, year, month, day, hour, minute }
}

/** Data ISO locale (yyyy-mm-dd) valida, ad esempio da un input type="date". */
export function isLocalDate(value) {
  if (typeof value !== 'string') return false
  const m = LOCAL_DATE.exec(value)
  return !!m && validDate(Number(m[1]), Number(m[2]), Number(m[3]))
}

const pad = (n) => String(n).padStart(2, '0')

/** "mercoledì 15/10/2026" dalla parte data, senza fusi. */
export function formatLocalDate(parsed) {
  const weekday = GIORNI[new Date(Date.UTC(parsed.year, parsed.month - 1, parsed.day)).getUTCDay()]
  return `${weekday} ${pad(parsed.day)}/${pad(parsed.month)}/${parsed.year}`
}

/** "09:30" dalla parte orario, senza fusi. */
export function formatLocalTime(parsed) {
  return `${pad(parsed.hour)}:${pad(parsed.minute)}`
}

/** Data odierna del browser come yyyy-mm-dd, letta con i getter locali (mai toISOString). */
export function todayLocal(now = new Date()) {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}
