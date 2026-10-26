import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, watch } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { useElencoDesk } from '../src/desk/useElencoDesk.js'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { desk } from './b7-fixtures.js'
import { installFetch, meOk, routedFetch } from './helpers.js'

/**
 * Recupero del focus di useElencoDesk  isolato dalla pagina: una lettura che
 * sostituisce quella manuale dopo il primo controllo del recupero e prima di quello successivo a
 * nextTick annulla il recupero.
 */
let wrapper
let net
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })

async function monta() {
  net.on('GET /api/auth/me', meOk(desk()))
  const session = createSession()
  await session.ensureBootstrap()
  const router = createAppRouter({ session, history: createMemoryHistory() })
  let api
  const opzioni = { sostituisci: false }
  const Harness = defineComponent({
    setup() {
      const d = useElencoDesk({ ricarica: () => {} })
      watch(() => d.elenco.stato, () => {
        if (!opzioni.sostituisci) return
        opzioni.sostituisci = false
        // Accodata dopo il flush: si esegue dopo il primo controllo del recupero e prima del secondo.
        nextTick(() => { opzioni.sostituita = true; d.leggi({ lista: 'harness', completa: true, leggi: () => new Promise(() => {}) }) })
      })
      api = d
      return () => h('div', [
        h('h2', { ref: d.titoloElenco, tabindex: -1, id: 'titolo-harness' }, 'Elenco'),
        h('button', { type: 'button', 'data-origine': 'aggiorna' }, 'Aggiorna'),
      ])
    },
  })
  wrapper = mount(Harness, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { d: api, opzioni }
}

describe('recupero del focus legato alla lettura manuale', () => {
  it('controllo: lettura riuscita e focus perso → titolo dell’elenco', async () => {
    const { d } = await monta()
    document.activeElement?.blur()
    await d.recupera(() => d.leggi({ lista: 'harness', completa: true, leggi: async () => [] }))
    await flushPromises()
    expect(document.activeElement.id).toBe('titolo-harness')
  })

  it('lettura sostituita dopo il primo controllo e prima del focus dopo nextTick: nessuno spostamento', async () => {
    const { d, opzioni } = await monta()
    document.activeElement?.blur()
    opzioni.sostituisci = true
    await d.recupera(() => d.leggi({ lista: 'harness', completa: true, leggi: async () => [] }))
    await flushPromises()
    expect(opzioni.sostituita).toBe(true)
    expect(document.activeElement).toBe(document.body)
  })

  it('focus portato dall’utente su un altro controllo: rispettato', async () => {
    const { d } = await monta()
    const bottone = wrapper.get('[data-origine="aggiorna"]').element
    bottone.focus()
    await d.recupera(() => d.leggi({ lista: 'harness', completa: true, leggi: async () => [] }))
    await flushPromises()
    expect(document.activeElement).toBe(bottone)
  })

  it('smontaggio prima della conclusione: nessun recupero', async () => {
    const { d } = await monta()
    let concludi
    const recupero = d.recupera(() => d.leggi({ lista: 'harness', completa: true, leggi: () => new Promise((r) => { concludi = r }) }))
    wrapper.unmount()
    wrapper = null
    concludi([])
    await recupero
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
  })
})
