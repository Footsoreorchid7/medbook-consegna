import { watch } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { AVVISO, STATO } from '../session/session.js'
import { createGuard, isReserved } from './guards.js'

const routes = [
  { path: '/desk/pazienti', name: 'desk-pazienti', component: () => import('../views/DeskPazientiView.vue'), meta: { capability: 'desk', title: 'Pazienti' } },
  {
    path: '/paziente/prenotazioni/:id/pagamenti/:pagamentoId/riepilogo',
    name: 'riepilogo-pagamento', component: () => import('../views/RiepilogoPagamentoView.vue'),
    meta: { capability: 'paziente', title: 'Riepilogo del pagamento' },
  },
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: 'MedBook' } },
  {
    path: '/accesso',
    name: 'accesso',
    component: () => import('../views/LoginView.vue'),
    meta: { guestOnly: true, title: 'Accesso' },
  },
  {
    path: '/area',
    name: 'area',
    component: () => import('../views/AreaView.vue'),
    meta: { requiresAuth: true, title: 'Area riservata' },
  },
  {
    path: '/prestazioni',
    name: 'prestazioni',
    component: () => import('../views/PrestazioniView.vue'),
    meta: { title: 'Prestazioni' },
  },
  {
    path: '/disponibilita',
    name: 'disponibilita',
    component: () => import('../views/DisponibilitaView.vue'),
    meta: { title: 'Disponibilità' },
  },
  {
    path: '/registrazione',
    name: 'registrazione',
    component: () => import('../views/RegistrazioneView.vue'),
    meta: { guestOnly: true, title: 'Registrazione' },
  },
  {
    path: '/area/password',
    name: 'cambio-password',
    component: () => import('../views/CambioPasswordView.vue'),
    meta: { requiresAuth: true, title: 'Cambio password' },
  },
  {
    path: '/paziente/prenota',
    name: 'prenota',
    component: () => import('../views/PrenotaView.vue'),
    meta: { capability: 'paziente', title: 'Richiedi un appuntamento' },
  },
  {
    path: '/paziente/prenotazioni',
    name: 'prenotazioni',
    component: () => import('../views/PrenotazioniView.vue'),
    meta: { capability: 'paziente', title: 'Le mie prenotazioni' },
  },
  {
    path: '/paziente/prenotazioni/:id',
    name: 'prenotazione-dettaglio',
    component: () => import('../views/PrenotazioneDettaglioView.vue'),
    meta: { capability: 'paziente', title: 'Dettaglio prenotazione' },
  },
  {
    path: '/paziente/rimborsi',
    name: 'rimborsi',
    component: () => import('../views/RimborsiView.vue'),
    meta: { capability: 'paziente', title: 'I miei rimborsi' },
  },
  {
    path: '/medico/agenda',
    name: 'agenda-medico',
    component: () => import('../views/AgendaView.vue'),
    meta: { capability: 'medico', title: 'Agenda' },
  },
  {
    path: '/medico/disponibilita',
    name: 'disponibilita-medico',
    component: () => import('../views/DisponibilitaMedicoView.vue'),
    meta: { capability: 'medico', title: 'Le mie disponibilità' },
  },
  {
    path: '/desk/richieste',
    name: 'desk-richieste',
    component: () => import('../views/DeskRichiesteView.vue'),
    meta: { capability: 'desk', title: 'Richieste di prenotazione' },
  },
  {
    path: '/desk/segnalazioni',
    name: 'desk-segnalazioni',
    component: () => import('../views/DeskSegnalazioniView.vue'),
    meta: { capability: 'desk', title: 'Segnalazioni di indisponibilità' },
  },
  {
    path: '/desk/prenotazioni',
    name: 'desk-prenotazioni',
    component: () => import('../views/DeskPrenotazioniView.vue'),
    meta: { capability: 'desk', title: 'Prenotazioni gestite' },
  },
  {
    path: '/desk/rimborsi',
    name: 'desk-rimborsi',
    component: () => import('../views/DeskRimborsiView.vue'),
    meta: { capability: 'desk', title: 'Rimborsi' },
  },
  {
    path: '/desk/disponibilita',
    name: 'desk-disponibilita',
    component: () => import('../views/DeskDisponibilitaView.vue'),
    meta: { capability: 'desk', title: 'Configurazione delle disponibilità' },
  },
  {
    path: '/desk/medici',
    name: 'desk-medici',
    component: () => import('../views/DeskMediciView.vue'),
    meta: { capability: 'desk', title: 'Medici' },
  },
  {
    path: '/desk/medici/:id',
    name: 'desk-medico',
    component: () => import('../views/DeskMedicoDettaglioView.vue'),
    meta: { capability: 'desk', title: 'Dettaglio medico' },
  },
  {
    path: '/admin/utenti',
    name: 'admin-utenti',
    component: () => import('../views/AdminUtentiView.vue'),
    meta: { capability: 'admin', title: 'Gestione utenti' },
  },
  {
    path: '/admin/medici',
    name: 'admin-medici',
    component: () => import('../views/AdminMediciView.vue'),
    meta: { capability: 'admin', title: 'Medici' },
  },
  {
    path: '/admin/medici/:id',
    name: 'admin-medico',
    component: () => import('../views/AdminMedicoDettaglioView.vue'),
    meta: { capability: 'admin', title: 'Dettaglio medico' },
  },
  {
    path: '/admin/stanze',
    name: 'admin-stanze',
    component: () => import('../views/AdminStanzeView.vue'),
    meta: { capability: 'admin', title: 'Stanze' },
  },
  {
    path: '/admin/prestazioni',
    name: 'admin-prestazioni',
    component: () => import('../views/AdminPrestazioniView.vue'),
    meta: { capability: 'admin', title: 'Prestazioni' },
  },
  {
    path: '/accesso-negato',
    name: 'accesso-negato',
    component: () => import('../views/AccessoNegatoView.vue'),
    meta: { title: 'Accesso negato' },
  },
  {
    path: '/errore-sessione',
    name: 'errore-sessione',
    component: () => import('../views/ErroreSessioneView.vue'),
    meta: { sessionError: true, title: 'Verifica della sessione non riuscita' },
  },
]

const notFound = {
  path: '/:pathMatch(.*)*',
  name: 'non-trovata',
  component: () => import('../views/NonTrovataView.vue'),
  meta: { title: 'Pagina non trovata' },
}

/**
 * Se la sessione termina mentre è aperta una vista riservata, la si chiude subito
 * (flush sincrono) e si porta l'utente alla pagina di accesso.
 */
function installSessionWatcher(router, session) {
  watch(() => [session.state.status, session.state.notice], ([status, notice]) => {
    if (status === STATO.ANONIMO && [AVVISO.LOGOUT_CONFERMATO, AVVISO.SESSIONE_ASSENTE].includes(notice)) {
      router.replace({ name: 'home' })
    }
  }, { flush: 'sync' })
  watch(() => session.state.status, (status, previous) => {
    if (previous !== STATO.AUTENTICATO || status === STATO.AUTENTICATO) return
    const current = router.currentRoute.value
    if (!isReserved(current.meta)) return
    const query = session.state.notice === AVVISO.SESSIONE_SCADUTA ? { redirect: current.fullPath } : {}
    router.replace({ name: 'accesso', query })
  }, { flush: 'sync' })
}

/**
 * @param {object} options
 * @param {object} options.session
 * @param {object} [options.history]
 * @param {Array} [options.extraRoutes] solo per i test delle guardie per capacità
 */
export function createAppRouter({ session, history = createWebHistory(), extraRoutes = [] }) {
  const router = createRouter({ history, routes: [...routes, ...extraRoutes, notFound] })
  router.beforeEach(createGuard(session, router))
  router.afterEach((to) => {
    if (typeof document !== 'undefined') {
      document.title = to.meta.title && to.meta.title !== 'MedBook' ? `${to.meta.title} · MedBook` : 'MedBook'
    }
  })
  installSessionWatcher(router, session)
  return router
}
