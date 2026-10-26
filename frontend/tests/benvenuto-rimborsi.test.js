import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from '../src/App.vue'
import PrenotazioniView from '../src/views/PrenotazioniView.vue'
import { createAppRouter } from '../src/router/index.js'
import { createSession } from '../src/session/session.js'
import { SESSION_KEY } from '../src/session/useSession.js'
import { CARD_DESK } from '../src/desk/useDashboardDesk.js'
import { GRUPPI } from '../src/navigation/collegamenti.js'
import { ETICHETTE_RIMBORSO, NOTE_RIMBORSO } from '../src/prenotazioni/economia.js'
import { appuntamento } from './prenotazioni-paziente-fixtures.js'
import { rimborso } from './pagamenti-rimborsi-fixtures.js'
import { desk, admin } from './desk-fixtures.js'
import { installFetch, jsonResponse, meOk, routedFetch, utente } from './helpers.js'
let net, wrapper
beforeEach(() => { net = installFetch(routedFetch()) })
afterEach(() => { wrapper?.unmount(); wrapper = null })
async function monta(path, user = utente()) {
  net.on('GET /api/auth/me', meOk(user))
  const session = createSession(), router = createAppRouter({ session, history: createMemoryHistory() })
  await router.push(path); await router.isReady()
  wrapper = mount(App, { global: { plugins: [router], provide: { [SESSION_KEY]: session } }, attachTo: document.body })
  await flushPromises()
  return { router, session }
}

it('prenotazioni: ordine numerico 1, 2, 10 senza mutare il risultato API conservato nella vista', async () => {
  net.on('GET /api/prenotazioni/paziente?idPaziente=7', jsonResponse(200, [appuntamento(10), appuntamento(2), appuntamento(1)]))
  await monta('/paziente/prenotazioni')
  const voci = wrapper.findAll('[data-prenotazione]')
  expect(voci.map(x => x.get('.numero-prenotazione strong').text())).toEqual(['Prenotazione n. 1', 'Prenotazione n. 2', 'Prenotazione n. 10'])
  const sorgente = wrapper.getComponent(PrenotazioniView).vm.$.setupState.elenco.voci
  expect(sorgente.map(x => x.idPrenotazione)).toEqual([10, 2, 1])
  expect(Object.isFrozen(sorgente)).toBe(true)
})

describe('benvenuto e collegamenti condivisi', () => {
  it.each([
    ['paziente', () => utente(), ['paziente']],
    ['medico', () => utente({ paziente: false, medico: true }), ['medico']],
    ['desk', desk, ['desk']],
    ['admin', admin, ['admin']],
    ['medico+paziente', () => utente({ medico: true }), ['paziente', 'medico']],
  ])('%s: solo capacità effettive, icone locali decorative e link riconosciuti', async (_, user, capacita) => {
    const persona = user()
    const { router } = await monta('/area', persona)
    expect(wrapper.get('.area-saluto').text()).toBe(`Benvenuto, ${persona.nome} ${persona.cognome}.`)
    expect(wrapper.findAll('.area-profili [data-capacita]').map(x => x.attributes('data-capacita'))).toEqual(capacita)
    const gruppi = GRUPPI.filter(g => capacita.includes(g.capacita))
    expect(wrapper.findAll('main .area-gruppo')).toHaveLength(gruppi.length)
    for (const gruppo of gruppi) {
      const carte = wrapper.get(`[aria-labelledby="titolo-area-${gruppo.capacita}"]`).findAll('.accesso-rapido')
      const link = gruppo.capacita === 'desk' ? gruppo.link.filter(l => !CARD_DESK.some(c => c.to === l.to)) : gruppo.link
      expect(carte.map(x => x.attributes('href'))).toEqual(link.map(x => x.to))
      if (gruppo.capacita === 'desk') expect(wrapper.findAll('[data-conteggio]').map(x => x.attributes('href'))).toEqual(CARD_DESK.map(c => c.id === 'rimborsi' ? '/desk/rimborsi?stato=RICHIESTO' : c.to))
      for (let i = 0; i < carte.length; i++) {
        const c = carte[i], meta = link[i]
        expect(c.get('.accesso-titolo').text()).toBe(meta.etichetta)
        expect(c.get('.accesso-descrizione').text()).toBe(meta.descrizione)
        expect(c.get('svg').attributes('aria-hidden')).toBe('true')
        expect(c.get('svg').attributes('focusable')).toBe('false')
        expect(router.resolve(meta.to).meta.capability).toBe(gruppo.capacita)
      }
    }
    expect(wrapper.find('.area-account').exists()).toBe(false)
    expect(wrapper.find('main a[href="/area/password"]').exists()).toBe(false)
    if (!capacita.includes('desk')) expect(net.calls.map(x => x.url)).toEqual(['/api/auth/me'])
    else expect(net.calls.every(x => x.method === 'GET' && ['/api/auth/me', '/api/prenotazioni/richieste', '/api/prenotazioni/segnalazioni-indisponibilita', '/api/rimborsi/richieste', '/api/medici'].includes(x.url))).toBe(true)
  })
  it('account senza profilo: nessun accesso operativo inventato', async () => {
    await monta('/area', utente({ paziente: false, medico: false }))
    expect(wrapper.find('.accesso-rapido').exists()).toBe(false)
    expect(wrapper.text()).toContain('Nessun profilo operativo')
    expect(wrapper.find('.area-account').exists()).toBe(false)
    expect(wrapper.find('main a[href="/area/password"]').exists()).toBe(false)
  })
})

describe('rimborsi: motivazione completa e badge testuali', () => {
  it.each([
    ['RICHIESTO', 'ambra'], ['IN_ELABORAZIONE', 'ambra'], ['RESPINTO', 'rosso'],
    ['FALLITO', 'rosso'], ['RIMBORSATO', 'verde'], ['APPROVATO', 'blu'],
  ])('%s mantiene etichetta e significato del contratto', async (stato, tono) => {
    const motivazione = '  Prima riga con accento è\nSeconda riga\tcontenuto completo  '
    net.on('GET /api/rimborsi/paziente', jsonResponse(200, [rimborso(31, { stato, motivazione, verificatoIl: '2026-10-18T09:30:00' })]))
    await monta('/paziente/rimborsi')
    const card = wrapper.get('[data-rimborso]')
    const badge = card.get('[data-campo="stato-rimborso"] .badge-stato')
    expect(badge.text()).toBe(ETICHETTE_RIMBORSO[stato])
    expect(badge.classes()).toContain(`badge-${tono}`)
    expect(card.get('.rimborso-etichetta').text()).toBe('Motivazione')
    expect(card.get('[data-campo="motivazione"]').element.textContent).toBe(motivazione)
    expect(card.get('[data-campo="nota-stato"]').text()).toBe(NOTE_RIMBORSO[stato])
    expect(card.get('.facts').text()).toContain('Importo')
    expect(card.get('.facts').text()).toContain('Pagamento')
    expect(card.get('a').attributes('href')).toBe('/paziente/prenotazioni/12')
    expect(net.calls.filter(x => x.method === 'POST')).toHaveLength(0)
  })
})
