import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../src/router/index.js'
import { resolveSafeRedirect } from '../src/router/guards.js'
import { STATO, createSession } from '../src/session/session.js'
import { installFetch, jsonResponse, meOk, scriptedFetch, unauthorized, utente } from './helpers.js'

// Rotte solo di test per le guardie per capacità: non esistono nel prodotto.
const Vuota = { render: () => null }
const ROTTE_TEST = ['paziente', 'medico', 'desk', 'admin'].map((capability) => ({
  path: `/test/${capability}`, name: `test-${capability}`, component: Vuota, meta: { capability },
}))

let script

beforeEach(() => {
  script = installFetch(scriptedFetch())
})

function setup() {
  const session = createSession()
  const router = createAppRouter({ session, history: createMemoryHistory(), extraRoutes: ROTTE_TEST })
  return { session, router }
}

async function navigate(router, path) {
  await router.push(path).catch(() => {})
  return router.currentRoute.value
}

describe('guardie', () => {
  it('attendono il bootstrap prima di decidere', async () => {
    const handle = script.defer()
    const { router, session } = setup()
    const navigation = router.push('/area')
    await vi.waitFor(() => expect(session.state.status).toBe(STATO.VERIFICA))
    expect(router.currentRoute.value.matched).toHaveLength(0)
    handle.resolve(meOk())
    await navigation
    expect(router.currentRoute.value.name).toBe('area')
  })

  it('anonimo verso area riservata: accesso con ritorno', async () => {
    script.reply(unauthorized())
    const { router } = setup()
    const route = await navigate(router, '/area')
    expect(route.name).toBe('accesso')
    expect(route.query.redirect).toBe('/area')
  })

  it('errore di verifica distinto da anonimo', async () => {
    script.networkError()
    const { router } = setup()
    const route = await navigate(router, '/area')
    expect(route.name).toBe('errore-sessione')
    expect(route.query.redirect).toBe('/area')
  })

  it('pagina di errore sessione inaccessibile se la verifica è riuscita', async () => {
    script.reply(unauthorized())
    const { router } = setup()
    expect((await navigate(router, '/errore-sessione')).name).toBe('home')
  })

  it('autenticato su /accesso: ritorno sicuro o area', async () => {
    script.reply(meOk())
    const { router } = setup()
    expect((await navigate(router, '/accesso?redirect=/area')).name).toBe('area')
    expect((await navigate(router, '/accesso?redirect=//esterno.example')).name).toBe('area')
  })

  it('medico e paziente accedono a entrambe le capacità, non a desk o admin', async () => {
    script.reply(meOk(utente({ paziente: true, medico: true })))
    const { router } = setup()
    expect((await navigate(router, '/test/paziente')).name).toBe('test-paziente')
    expect((await navigate(router, '/test/medico')).name).toBe('test-medico')
    expect((await navigate(router, '/test/desk')).name).toBe('accesso-negato')
    expect((await navigate(router, '/test/admin')).name).toBe('accesso-negato')
  })

  it('amministratore non accede alle rotte desk', async () => {
    script.reply(meOk(utente({ paziente: false, ruoloGestionale: 'AMMINISTRATORE' })))
    const { router } = setup()
    expect((await navigate(router, '/test/admin')).name).toBe('test-admin')
    expect((await navigate(router, '/test/desk')).name).toBe('accesso-negato')
  })

  it('operatore desk non accede alle rotte admin', async () => {
    script.reply(meOk(utente({ paziente: false, ruoloGestionale: 'OPERATORE_DESK' })))
    const { router } = setup()
    expect((await navigate(router, '/test/desk')).name).toBe('test-desk')
    expect((await navigate(router, '/test/admin')).name).toBe('accesso-negato')
  })

  it('pagine pubbliche e non trovata senza redirect', async () => {
    script.reply(unauthorized())
    const { router } = setup()
    expect((await navigate(router, '/')).name).toBe('home')
    expect((await navigate(router, '/accesso-negato')).name).toBe('accesso-negato')
    expect((await navigate(router, '/inesistente/percorso')).name).toBe('non-trovata')
  })

  it('nessun ciclo di redirect in alcuno stato', async () => {
    const warn = vi.spyOn(console, 'warn')
    for (const response of [() => unauthorized(), () => meOk(utente({ paziente: false })), null]) {
      if (response) script.reply(response())
      else script.networkError()
      const { router } = setup()
      const guard = vi.fn()
      router.beforeEach(guard)
      for (const path of ['/area', '/accesso', '/errore-sessione', '/accesso-negato', '/test/desk', '/x']) {
        guard.mockClear()
        await navigate(router, path)
        expect(guard.mock.calls.length).toBeLessThanOrEqual(2)
      }
    }
    expect(warn).not.toHaveBeenCalled()
  })

  it('perdita della sessione chiude la vista riservata', async () => {
    script.reply(meOk())
    const { router, session } = setup()
    await navigate(router, '/area')
    script.reply(unauthorized())
    await session.protectedRequest({ path: '/api/stanze' }).catch(() => {})
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('accesso'))
    expect(router.currentRoute.value.query.redirect).toBe('/area')
  })

  it('il logout chiude la vista riservata senza destinazione di ritorno', async () => {
    script.reply(meOk())
    const { router, session } = setup()
    await navigate(router, '/area')
    script.reply(jsonResponse(200, { message: 'Logout effettuato.' }))
    await session.logout()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })
})

describe('resolveSafeRedirect', () => {
  it('accetta solo destinazioni interne riconosciute e autorizzate', async () => {
    script.reply(unauthorized())
    const { router } = setup()
    const caps = { paziente: true, medico: false, desk: false, admin: false }
    expect(resolveSafeRedirect(router, '/area', caps)).toBe('/area')
    expect(resolveSafeRedirect(router, '/test/paziente', caps)).toBe('/test/paziente')
    for (const raw of [
      undefined, ['/area'], 'https://esterno.example/', '//esterno.example', '/\\esterno.example',
      'javascript:alert(1)', 'area', '/accesso', '/errore-sessione', '/accesso-negato', '/inesistente',
      '/test/desk', '/area\n', '/' + 'a'.repeat(600),
    ]) {
      expect(resolveSafeRedirect(router, raw, caps)).toBe('/area')
    }
    expect(resolveSafeRedirect(router, '/inesistente', null, '/')).toBe('/')
  })
})
