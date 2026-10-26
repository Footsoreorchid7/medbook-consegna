/**
 * Unico wrapper fetch del frontend.
 *
 * Non conosce router né sessione: CSRF, controllo di attualità (epoca) e segnale di
 * annullamento arrivano come opzioni. Non naviga, non rilegge, non ritenta: ogni
 * decisione resta al chiamante. Nessun log: password, cookie, token e corpi non
 * vengono mai scritti in console. Origin non viene mai impostato manualmente.
 */

const SAFE_METHODS = new Set(['GET', 'HEAD'])
const JSON_TYPE = /^application\/(?:[\w.+-]+\+)?json\s*(?:;|$)/i

/** Risposta HTTP non 2xx. `serverMessage` è solo da mostrare, mai da interpretare. */
export class HttpError extends Error {
  constructor(status, { serverMessage = null, retryAfter = null, write = false } = {}) {
    super(`HTTP ${status}`)
    this.name = 'HttpError'
    this.status = status
    this.serverMessage = serverMessage
    this.retryAfter = retryAfter
    this.write = write
  }
}

/** Nessuna risposta utilizzabile. Per una scrittura l'esito sul server è sconosciuto. */
export class NetworkError extends Error {
  constructor({ write = false, aborted = false } = {}) {
    super('Errore di rete')
    this.name = 'NetworkError'
    this.write = write
    this.aborted = aborted
  }
}

/** Risposta 2xx che non rispetta il formato atteso. */
export class ProtocolError extends Error {
  constructor(detail = 'Risposta non conforme') {
    super(detail)
    this.name = 'ProtocolError'
  }
}

/**
 * La richiesta appartiene a una sessione o vista non più attuale: il risultato è
 * scartato. Per una scrittura non significa che il server non l'abbia eseguita.
 */
export class StaleResponseError extends Error {
  constructor({ write = false } = {}) {
    super('Risposta obsoleta')
    this.name = 'StaleResponseError'
    this.write = write
  }
}

/**
 * Retry-After in secondi (delta-seconds) o come data HTTP. Restituisce i secondi di
 * attesa (>= 0) oppure null se l'header manca o non è interpretabile.
 */
export function parseRetryAfter(value, now = Date.now()) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (/^\d+$/.test(text)) {
    const seconds = Number(text)
    return Number.isSafeInteger(seconds) ? seconds : null
  }
  if (!/GMT$/.test(text)) return null
  const time = Date.parse(text)
  if (Number.isNaN(time)) return null
  return Math.max(0, Math.ceil((time - now) / 1000))
}

const FALLBACK_ORIGIN = 'http://medbook.invalid'

function appOrigin() {
  const origin = globalThis.location?.origin
  return typeof origin === 'string' && /^https?:\/\//.test(origin) ? origin : FALLBACK_ORIGIN
}

/**
 * Confina ogni richiesta sotto /api/ dell'origine dell'applicazione, prima di fetch
 * e quindi prima di allegare il CSRF. Il percorso grezzo deve essere relativo
 * assoluto (/api/...), senza segmenti vuoti, dot-segment (anche codificati come %2e),
 * slash o backslash codificati, frammenti o caratteri di controllo. L'URL normalizzato
 * deve poi restare sulla stessa origine, con pathname identico e sotto /api/.
 * La query viene conservata.
 * @returns {string} percorso relativo da passare a fetch
 */
export function resolveApiPath(path) {
  const invalid = () => new TypeError('Percorso API non valido.')
  if (typeof path !== 'string' || path.length > 2048 || !path.startsWith('/api/')
      || path.includes('\\') || path.includes('#') || /[\u0000- \u007f]/.test(path)) {
    throw invalid()
  }
  const queryStart = path.indexOf('?')
  const rawPath = queryStart === -1 ? path : path.slice(0, queryStart)
  for (const segment of rawPath.slice(1).split('/')) {
    let decoded
    try {
      decoded = decodeURIComponent(segment)
    } catch {
      throw invalid()
    }
    if (decoded === '' || decoded === '.' || decoded === '..' || /[/\\]/.test(decoded)) throw invalid()
  }
  const origin = appOrigin()
  let url
  try {
    url = new URL(path, origin)
  } catch {
    throw invalid()
  }
  if (url.origin !== origin || url.pathname !== rawPath || !url.pathname.startsWith('/api/')) throw invalid()
  return url.pathname + url.search
}

function isJson(contentType) {
  return typeof contentType === 'string' && JSON_TYPE.test(contentType)
}

function mediaType(contentType) {
  return typeof contentType === 'string' ? contentType.split(';', 1)[0].trim().toLowerCase() : ''
}

async function readError(response, write) {
  let serverMessage = null
  try {
    const text = await response.text()
    if (text && isJson(response.headers.get('Content-Type'))) {
      const payload = JSON.parse(text)
      if (payload && typeof payload === 'object' && !Array.isArray(payload) && typeof payload.error === 'string') {
        serverMessage = payload.error
      }
    }
  } catch {
    // Corpo d'errore illeggibile o non JSON: resta valido lo stato HTTP.
  }
  return new HttpError(response.status, {
    serverMessage,
    retryAfter: parseRetryAfter(response.headers.get('Retry-After')),
    write,
  })
}

/**
 * @param {object} options
 * @param {string} options.path percorso relativo che inizia con /api/
 * @param {string} [options.method]
 * @param {*} [options.json] corpo JSON (imposta Content-Type: application/json)
 * @param {Blob|ArrayBuffer} [options.body] corpo binario, richiede contentType
 * @param {string} [options.contentType]
 * @param {string|null} [options.csrfToken] inviato solo sui metodi di scrittura
 * @param {'json'|'blob'} [options.responseType]
 * @param {string} [options.expectedType] media type atteso in modalità blob
 * @param {AbortSignal} [options.signal]
 * @param {() => boolean} [options.isCurrent] false = risposta obsoleta
 * @returns {Promise<{status:number, headers:Headers, hasBody:boolean, data:*}>}
 *   `hasBody` false (data undefined) per 204 o corpo vuoto, distinto da `{}`.
 */
export async function request({
  path,
  method = 'GET',
  json,
  body,
  contentType,
  csrfToken = null,
  responseType = 'json',
  expectedType = 'application/pdf',
  signal,
  isCurrent,
}) {
  const target = resolveApiPath(path)
  const verb = method.toUpperCase()
  const write = !SAFE_METHODS.has(verb)
  if (json !== undefined && body !== undefined) throw new TypeError('Corpo ambiguo.')

  const headers = new Headers()
  headers.set('Accept', responseType === 'blob' ? `${expectedType}, application/json;q=0.5` : 'application/json')
  let payload
  if (json !== undefined) {
    payload = JSON.stringify(json)
    headers.set('Content-Type', 'application/json')
  } else if (body !== undefined) {
    if (!contentType) throw new TypeError('Content-Type obbligatorio per un corpo binario.')
    payload = body
    headers.set('Content-Type', contentType)
  }
  if (write && csrfToken) headers.set('X-CSRF-Token', csrfToken)

  const stale = () => typeof isCurrent === 'function' && !isCurrent()

  let response
  try {
    response = await fetch(target, {
      method: verb,
      headers,
      body: payload,
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal,
    })
  } catch (error) {
    if (stale()) throw new StaleResponseError({ write })
    throw new NetworkError({ write, aborted: error?.name === 'AbortError' })
  }
  if (stale()) throw new StaleResponseError({ write })

  // Gli errori HTTP si leggono prima di qualsiasi lettura binaria.
  if (!response.ok) {
    const error = await readError(response, write)
    if (stale()) throw new StaleResponseError({ write })
    throw error
  }

  const result = { status: response.status, headers: response.headers, hasBody: false, data: undefined }
  try {
    if (responseType === 'blob') {
      if (response.status === 204 || response.status === 205) throw new ProtocolError('Documento assente')
      if (mediaType(response.headers.get('Content-Type')) !== expectedType) {
        throw new ProtocolError('Tipo di documento inatteso')
      }
      const blob = await response.blob()
      if (blob.size === 0) throw new ProtocolError('Documento vuoto')
      result.hasBody = true
      result.data = blob
    } else {
      let text = ''
      if (response.status === 204 || response.status === 205) {
        // Corpo vuoto consumato comunque: se resta aperto, il browser registra la richiesta come
        // interrotta. Lettura non attesa: esito e tempi della richiesta non dipendono dal corpo.
        response.text().catch(() => {})
      } else {
        text = await response.text()
      }
      if (text.length > 0) {
        if (!isJson(response.headers.get('Content-Type'))) throw new ProtocolError('Content-Type inatteso')
        try {
          result.data = JSON.parse(text)
        } catch {
          throw new ProtocolError('JSON non valido')
        }
        result.hasBody = true
      }
    }
  } catch (error) {
    if (stale()) throw new StaleResponseError({ write })
    if (error instanceof ProtocolError) throw error
    throw new NetworkError({ write, aborted: error?.name === 'AbortError' })
  }
  if (stale()) throw new StaleResponseError({ write })
  return result
}
