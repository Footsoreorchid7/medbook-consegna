import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, reactive, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificaInformativa from '../src/components/NotificaInformativa.vue'
import SessionNotices from '../src/components/SessionNotices.vue'
import { AVVISO, STATO } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'

let wrapper
beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.replaceChildren()
  vi.useRealTimers()
})
const passa = (ms) => vi.advanceTimersByTimeAsync(ms)
function monta(temporanea = true) {
  wrapper = mount(defineComponent({
    components: { NotificaInformativa },
    setup() { return { messaggio: ref({ testo: 'Operazione completata.' }), temporanea } },
    template: `<main><h1 tabindex="-1">La pagina</h1><button data-altro>Altro</button>
      <NotificaInformativa v-if="messaggio" :notifica="messaggio" :temporanea="temporanea" @close="messaggio = null"><p>{{ messaggio.testo }}</p></NotificaInformativa></main>`,
  }), { attachTo: document.body })
  return wrapper.getComponent(NotificaInformativa)
}
const x = () => wrapper.get('button[aria-label="Chiudi avviso"]')
const avviso = () => wrapper.find('[role="status"]')

describe('notifiche informative comuni', () => {
  it('X accessibile senza pulsante testuale, scadenza dopo esattamente 5 secondi', async () => {
    monta()
    expect(x().text()).toBe('×')
    expect(wrapper.text()).not.toContain('Chiudi')
    await passa(4999)
    expect(avviso().exists()).toBe(true)
    await passa(1)
    expect(avviso().exists()).toBe(false)
  })
  it('avviso persistente: nessun timer, X accessibile e recupero del focus', async () => {
    monta(false)
    await passa(30000)
    expect(avviso().exists()).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    expect(x().attributes('aria-label')).toBe('Chiudi avviso')
    x().element.focus()
    await x().trigger('click')
    expect(avviso().exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('h1').element)
  })
  it('la comparsa e la scadenza non spostano il focus', async () => {
    const altro = document.createElement('button')
    document.body.append(altro)
    altro.focus()
    monta()
    expect(document.activeElement).toBe(altro)
    await passa(5000)
    expect(document.activeElement).toBe(altro)
  })
  it('il puntatore sospende il tempo residuo, senza ricominciare da cinque secondi', async () => {
    monta()
    await passa(2000)
    await avviso().trigger('pointerenter')
    await passa(10000)
    expect(avviso().exists()).toBe(true)
    await avviso().trigger('pointerleave')
    await passa(2999)
    expect(avviso().exists()).toBe(true)
    await passa(1)
    expect(avviso().exists()).toBe(false)
  })
  it('il focus sospende il timer; uscire con il puntatore non basta finché resta il focus', async () => {
    monta()
    await passa(1000)
    await avviso().trigger('pointerenter')
    x().element.focus()
    await avviso().trigger('pointerleave')
    await passa(10000)
    expect(avviso().exists()).toBe(true)
    wrapper.get('[data-altro]').element.focus()
    await passa(3999)
    expect(avviso().exists()).toBe(true)
    await passa(1)
    expect(avviso().exists()).toBe(false)
  })
  it('spostarsi fra controlli interni non riavvia il timer', async () => {
    wrapper = mount(NotificaInformativa, {
      props: { notifica: 'ok' }, slots: { default: '<p>Fatto</p><button data-interno>Dettagli</button>' }, attachTo: document.body,
    })
    x().element.focus()
    wrapper.get('[data-interno]').element.focus()
    await passa(10000)
    expect(avviso().exists()).toBe(true)
    expect(wrapper.emitted('close')).toBeUndefined()
  })
  it('X da tastiera recupera il focus sul titolo dopo l’aggiornamento DOM', async () => {
    monta()
    x().element.focus()
    await x().trigger('click')
    expect(avviso().exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('h1').element)
    await passa(10000)
    expect(document.activeElement).toBe(wrapper.get('h1').element)
  })
  it('chiusura senza focus sulla X non sottrae quello su un altro controllo', async () => {
    monta()
    wrapper.get('[data-altro]').element.focus()
    await x().trigger('click')
    expect(document.activeElement).toBe(wrapper.get('[data-altro]').element)
  })
  it('rispetta il focus spostato dall’utente fra clic e aggiornamento DOM', async () => {
    monta()
    x().element.focus()
    const chiusura = x().trigger('click')
    wrapper.get('[data-altro]').element.focus()
    await chiusura
    expect(document.activeElement).toBe(wrapper.get('[data-altro]').element)
  })
  it('smontaggio durante la chiusura non sposta il focus e libera il timer', async () => {
    monta()
    const altro = document.createElement('button')
    document.body.append(altro)
    x().element.focus()
    const chiusura = x().trigger('click')
    wrapper.unmount()
    wrapper = null
    altro.focus()
    await chiusura
    await passa(10000)
    expect(document.activeElement).toBe(altro)
    expect(vi.getTimerCount()).toBe(0)
  })
  it('una nuova notifica riceve il proprio timer completo', async () => {
    monta()
    await passa(4000)
    wrapper.vm.messaggio = { testo: 'Secondo successo.' }
    await nextTick()
    await passa(4999)
    expect(avviso().text()).toContain('Secondo successo.')
    await passa(1)
    expect(avviso().exists()).toBe(false)
  })
  it('un contesto sostitutivo non viene chiuso né focalizzato dal vecchio clic', async () => {
    monta()
    x().element.focus()
    const chiusura = x().trigger('click')
    wrapper.vm.messaggio = { testo: 'Nuovo contesto.' }
    await chiusura
    await nextTick()
    expect(avviso().text()).toContain('Nuovo contesto.')
    expect(document.activeElement).not.toBe(wrapper.get('h1').element)
  })
})

function montaSessione(notice, status = STATO.ANONIMO) {
  const state = reactive({ status, notice, busy: false })
  const session = { state, clearNotice: vi.fn(() => { state.notice = null }), recoverLogout: vi.fn() }
  wrapper = mount(SessionNotices, { global: { provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  return session
}
describe('selezione degli avvisi di sessione', () => {
  it.each([AVVISO.LOGOUT_CONFERMATO, AVVISO.SESSIONE_ASSENTE])('chiude soltanto l’informazione di logout %s', async (notice) => {
    const session = montaSessione(notice)
    expect(x().exists()).toBe(true)
    await passa(5000)
    expect(session.clearNotice).toHaveBeenCalledOnce()
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(wrapper.text()).toBe('')
  })
  it.each([AVVISO.SESSIONE_SCADUTA, AVVISO.PASSWORD_NUOVO_ACCESSO, AVVISO.PASSWORD_INCERTA_NUOVO_ACCESSO])('mantiene l’avviso di sicurezza %s', async (notice) => {
    const session = montaSessione(notice)
    const testo = wrapper.text()
    await passa(30000)
    expect(wrapper.text()).toBe(testo)
    expect(session.clearNotice).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-label="Chiudi avviso"]').exists()).toBe(true)
  })
  it('la X della sessione scaduta chiude solo il messaggio, senza ripristinare accesso o sbloccare incertezze', async () => {
    const session = montaSessione(AVVISO.SESSIONE_SCADUTA)
    session.state.loginUncertain = true
    session.state.passwordUncertain = true
    await x().trigger('click')
    expect(session.state.notice).toBeNull()
    expect(session.state.status).toBe(STATO.ANONIMO)
    expect(session.state.loginUncertain).toBe(true)
    expect(session.state.passwordUncertain).toBe(true)
    expect(session.recoverLogout).not.toHaveBeenCalled()
  })
  it('mantiene logout non confermato e il recupero manuale', async () => {
    const session = montaSessione(null, STATO.LOGOUT_NON_CONFERMATO)
    await passa(30000)
    expect(wrapper.get('[role="alert"]').text()).toContain('non è stata confermata')
    expect(wrapper.get('button').text()).toBe('Verifica e completa la disconnessione')
    expect(session.recoverLogout).not.toHaveBeenCalled()
    expect(session.clearNotice).not.toHaveBeenCalled()
  })
})
