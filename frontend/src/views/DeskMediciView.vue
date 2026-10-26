<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchPrestazioni } from '../api/catalogo.js'
import { NetworkError, ProtocolError, StaleResponseError } from '../api/http.js'
import { STATI_MEDICO, fetchMedici } from '../api/desk.js'
import { adminStore } from '../admin/store.js'
import { nome } from '../desk/formato.js'
import { useLetturaProfilo } from '../prenotazioni/useLetturaPaziente.js'

/** Medici : consultazione in sola lettura con la proiezione desk. Nessuna funzione amministrativa. */
const filtri = reactive({ stato: '', idPrestazione: '' })
const elenco = reactive({ stato: 'caricamento', voci: [], errore: '' })
const prestazioni = ref([])
let applicati = {}

const { session, nuovaLettura, attuale } = useLetturaProfilo('desk', {
  azzera() { elenco.voci = []; elenco.stato = 'caricamento' },
  ricarica: () => leggi(),
})

const account = adminStore(session)
const collegamentiIncerti = computed(() => session.capabilities.value.desk ? account.state.incerte.filter(x => x.tipo === 'collega-paziente') : [])

async function leggi() {
  const mia = nuovaLettura()
  elenco.stato = 'caricamento'
  try {
    const voci = await fetchMedici(session.protectedRequest, applicati)
    if (!attuale(mia)) return
    Object.assign(elenco, { stato: 'pronto', voci, errore: '' })
  } catch (error) {
    if (!attuale(mia) || error instanceof StaleResponseError) return
    elenco.errore = error instanceof NetworkError ? 'Il server non è raggiungibile.'
      : error instanceof ProtocolError ? 'Il server ha restituito una risposta non valida.' : 'Il server ha restituito un errore.'
    elenco.stato = 'errore'
  }
}

function cerca() {
  applicati = {
    ...(filtri.stato ? { stato: filtri.stato } : {}),
    ...(filtri.idPrestazione ? { idPrestazione: Number(filtri.idPrestazione) } : {}),
  }
  leggi()
}

onMounted(async () => {
  leggi()
  try { prestazioni.value = await fetchPrestazioni() } catch { /* filtro facoltativo */ }
})
</script>

<template>
  <section v-for="x in collegamentiIncerti" :key="x.chiave" class="notice warning" role="status">
    <p>Collegamento paziente dall’esito incerto · {{ x.riepilogo.nome }} {{ x.riepilogo.cognome }} · utente n. {{ x.idUtente }}.</p>
    <RouterLink :to="`/desk/medici/${x.idUtente}`">Apri la scheda e verifica il profilo paziente</RouterLink>
  </section>
  <h1>Medici</h1>
  <p class="muted">Consultazione dei medici e delle loro abilitazioni. Le modifiche sono riservate all'amministrazione.</p>

  <section class="card" aria-labelledby="titolo-filtri-medici">
    <h2 id="titolo-filtri-medici">Filtri</h2>
    <form class="form" novalidate @submit.prevent="cerca">
      <div class="field">
        <label for="medici-stato">Stato</label>
        <select id="medici-stato" v-model="filtri.stato">
          <option value="">Tutti</option>
          <option v-for="s in STATI_MEDICO" :key="s" :value="s">{{ s === 'ATTIVO' ? 'Attivo' : 'Inattivo' }}</option>
        </select>
      </div>
      <div class="field">
        <label for="medici-prestazione">Abilitato alla prestazione</label>
        <select id="medici-prestazione" v-model="filtri.idPrestazione">
          <option value="">Qualsiasi</option>
          <option v-for="p in prestazioni" :key="p.idPrestazione" :value="String(p.idPrestazione)">{{ p.nome }}</option>
        </select>
      </div>
      <div><button type="submit" class="button">Cerca</button></div>
    </form>
  </section>

  <section aria-labelledby="titolo-medici" :aria-busy="elenco.stato === 'caricamento' ? 'true' : 'false'">
    <h2 id="titolo-medici">Elenco</h2>
    <p v-if="elenco.stato === 'caricamento'" role="status">Caricamento dei medici…</p>
    <div v-else-if="elenco.stato === 'errore'" class="notice error" role="alert">
      <p>Non è stato possibile caricare i medici. {{ elenco.errore }}</p>
      <div class="actions"><button type="button" class="button" @click="leggi">Riprova</button></div>
    </div>
    <template v-else>
      <p class="muted" role="status">{{ elenco.voci.length === 1 ? '1 medico' : `${elenco.voci.length} medici` }}.</p>
      <p v-if="elenco.voci.length === 0">Nessun medico per i filtri scelti.</p>
      <ul v-else class="items" aria-label="Medici">
        <li v-for="m in elenco.voci" :key="m.idUtente" class="card" data-medico :data-id="m.idUtente">
          <h3 class="item-title">{{ nome(m) }}</h3>
          <dl class="facts">
            <div><dt>Specializzazione</dt><dd>{{ m.specializzazione ?? 'Non indicata' }}</dd></div>
            <div><dt>Stato</dt><dd data-campo="stato">{{ m.stato === 'ATTIVO' ? 'Attivo' : 'Inattivo' }}</dd></div>
          </dl>
          <p class="small"><RouterLink :to="`/desk/medici/${m.idUtente}`">Dettaglio e abilitazioni<span class="visually-hidden"> di {{ nome(m) }}</span></RouterLink></p>
        </li>
      </ul>
    </template>
  </section>
</template>
