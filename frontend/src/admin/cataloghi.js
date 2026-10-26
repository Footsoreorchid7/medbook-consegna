import {
  fetchPrestazioniAdmin, fetchStanzeAdmin, prezzoTesto, validaPrestazione, validaStanza,
} from '../api/catalogoAdmin.js'
import { PRESTAZIONI, STANZE, chiaveNuovaPrestazione, chiaveNuovaStanza, chiavePrestazione, chiaveStanza } from './store.js'

/**
 * Configurazione delle pagine dei cataloghi . La meccanica comune è in CatalogoAdmin.vue;
 * qui campi, contratti e testi propri di stanze e prestazioni.
 */

const euro = (c) => `${prezzoTesto(c)} €`
const desc = (d) => (d === null ? 'nessuna descrizione' : `descrizione «${d}»`)
const NUOVO = 'Un nuovo invio è possibile solo sui dati aggiornati, con una nuova scelta e conferma.'

export const STANZE_CONFIG = Object.freeze({
  chiave: 'stanze',
  titolo: 'Stanze',
  singolare: 'stanza',
  intro: 'Ambulatori in cui si configurano le disponibilità. Il nome è unico: il server rifiuta un nome già presente. Non esistono eliminazione né stato attivo/inattivo.',
  ambito: STANZE,
  tipi: ['stanza-crea', 'stanza-modifica'],
  idDi: (s) => s.idStanza,
  leggi: fetchStanzeAdmin,
  campi: [
    { k: 'nome', etichetta: 'Nome', aiuto: 'Da 1 a 100 caratteri; spazi ai bordi rimossi dal server.' },
    { k: 'descrizione', etichetta: 'Descrizione (facoltativa)', area: true, aiuto: 'Al massimo 255 caratteri. Lasciala vuota per nessuna descrizione; il testo viene salvato com\'è.' },
  ],
  daRisorsa: (s) => ({ nome: s.nome, descrizione: s.descrizione ?? '' }),
  valida: validaStanza,
  fatti: (s) => [['Descrizione', s.descrizione ?? 'Nessuna', true]],
  riassunto: (d) => `«${d.nome}», ${desc(d.descrizione)}`,
  chiaveNuova: (d) => chiaveNuovaStanza(d.nome),
  chiaveRisorsa: chiaveStanza,
  crea: (store, dati) => store.creaStanza(dati),
  aggiorna: (store, voce, dati) => store.aggiornaStanza(voce, dati),
  versione: (a, b) => a.nome === b.nome && a.descrizione === b.descrizione,
  successo: (r, nuova) => `${nuova ? 'Stanza creata' : 'Il server conferma la stanza'} n. ${r.idStanza}: «${r.nome}», ${desc(r.descrizione)}.`,
  conflitto: 'Possibile causa indicata dal contratto: nome di stanza già presente.',
  incerta: (x) => (x.tipo === 'stanza-crea' ? `nuova stanza «${x.riepilogo.richiesto.nome}»` : `stanza n. ${x.riepilogo.idRisorsa}, da «${x.riepilogo.precedente.nome}» a «${x.riepilogo.richiesto.nome}»`),
  verifica(x, esito, voce) {
    if (x.tipo === 'stanza-crea') {
      return esito === 'presente'
        ? `Al momento della verifica esiste la stanza n. ${voce.idStanza} con nome «${voce.nome}» (${desc(voce.descrizione)}). Questo non prova che sia stata creata dal tentativo: il nome potrebbe essere preesistente.`
        : `Al momento della verifica nessuna stanza ha nome «${x.riepilogo.richiesto.nome}»: la creazione non risulta applicata. ${NUOVO}`
    }
    return testoModifica('La stanza', x.riepilogo.idRisorsa, esito, voce && `«${voce.nome}», ${desc(voce.descrizione)}`)
  },
})

export const PRESTAZIONI_CONFIG = Object.freeze({
  chiave: 'prestazioni',
  titolo: 'Prestazioni',
  singolare: 'prestazione',
  intro: 'Catalogo delle prestazioni prenotabili. Il nome non è unico. Le prenotazioni già richieste conservano il prezzo registrato alla richiesta: un nuovo prezzo vale per le richieste successive. La durata non può cambiare se esistono disponibilità associate alla prestazione. Non esistono eliminazione né stato attivo/inattivo.',
  ambito: PRESTAZIONI,
  tipi: ['prestazione-crea', 'prestazione-modifica'],
  idDi: (p) => p.idPrestazione,
  leggi: fetchPrestazioniAdmin,
  campi: [
    { k: 'nome', etichetta: 'Nome', aiuto: 'Da 1 a 150 caratteri; spazi ai bordi rimossi dal server. Il nome non deve essere unico.' },
    { k: 'descrizione', etichetta: 'Descrizione (facoltativa)', area: true, aiuto: 'Lasciala vuota per nessuna descrizione; il testo viene salvato com\'è. L\'intera richiesta non può superare 8192 byte.' },
    { k: 'durata', etichetta: 'Durata (minuti, da 1 a 60)', numerico: 'numeric', aiuto: 'Numero intero di minuti. Non deve dividere esattamente l\'ora.' },
    { k: 'prezzo', etichetta: 'Prezzo (€)', numerico: 'decimal', aiuto: 'Ad esempio 80 oppure 80,50 (anche 80.50). Al massimo due decimali, nessun separatore delle migliaia: un valore con più decimali è rifiutato, non arrotondato.' },
  ],
  daRisorsa: (p) => ({ nome: p.nome, descrizione: p.descrizione ?? '', durata: String(p.durata), prezzo: prezzoTesto(p.centesimi) }),
  valida: validaPrestazione,
  fatti: (p) => [['Durata', `${p.durata} minuti`], ['Prezzo', euro(p.centesimi)], ['Descrizione', p.descrizione ?? 'Nessuna', true]],
  riassunto: (d) => `«${d.nome}», ${d.durata} minuti, ${euro(d.centesimi)}, ${desc(d.descrizione)}`,
  chiaveNuova: chiaveNuovaPrestazione,
  chiaveRisorsa: chiavePrestazione,
  crea: (store, dati, noti) => store.creaPrestazione(dati, noti),
  aggiorna: (store, voce, dati) => store.aggiornaPrestazione(voce, dati),
  versione: (a, b) => a.nome === b.nome && a.descrizione === b.descrizione && a.durata === b.durata && a.centesimi === b.centesimi,
  successo: (r, nuova) => `${nuova ? 'Prestazione creata' : 'Il server conferma la prestazione'} n. ${r.idPrestazione}: «${r.nome}», ${r.durata} minuti, ${euro(r.centesimi)}, ${desc(r.descrizione)}.`
    + (nuova ? '' : ' Le prenotazioni già richieste conservano il prezzo registrato alla richiesta.'),
  conflitto: 'Possibile causa indicata dal contratto: la durata non può cambiare quando esistono disponibilità associate.',
  incerta: (x) => (x.tipo === 'prestazione-crea' ? `nuova prestazione «${x.riepilogo.richiesto.nome}», ${x.riepilogo.richiesto.durata} minuti, ${euro(x.riepilogo.richiesto.centesimi)}`
    : `prestazione n. ${x.riepilogo.idRisorsa} («${x.riepilogo.precedente.nome}»)`),
  verifica(x, esito, voce, trovate) {
    if (x.tipo === 'prestazione-crea') {
      if (esito === 'assente') return `Al momento della verifica nessuna prestazione ha esattamente i dati richiesti: la creazione non risulta applicata. ${NUOVO}`
      const ids = trovate.map((p) => `n. ${p.idPrestazione}`).join(', ')
      if (esito === 'nuova') return `Al momento della verifica esistono prestazioni con esattamente i dati richiesti (${ids}), almeno una assente prima dell'invio. Questo non prova che sia stata creata dal tentativo: il nome non è unico.`
      if (esito === 'solo-preesistenti') return `Al momento della verifica le sole prestazioni con i dati richiesti (${ids}) esistevano già prima dell'invio: la creazione non risulta applicata. ${NUOVO}`
      return `Al momento della verifica esistono prestazioni con esattamente i dati richiesti (${ids}); non è possibile distinguere quelle preesistenti dall'eventuale nuova, né attribuirle al tentativo.`
    }
    return testoModifica('La prestazione', x.riepilogo.idRisorsa, esito, voce && `«${voce.nome}», ${voce.durata} minuti, ${euro(voce.centesimi)}, ${desc(voce.descrizione)}`)
  },
})

function testoModifica(soggetto, id, esito, attuale) {
  if (esito === 'assente') return `${soggetto} n. ${id} non risulta nell'elenco: l'esito non può essere stabilito.`
  const base = `Al momento della verifica ${soggetto.toLowerCase()} n. ${id} risulta ${attuale}`
  if (esito === 'come-richiesto') return `${base}, come richiesto. Questo non prova chi abbia eseguito la modifica.`
  if (esito === 'precedente') return `${base}, come prima del tentativo: la modifica non risulta applicata. ${NUOVO}`
  return `${base}, diversamente sia dai dati precedenti sia da quelli richiesti: non è possibile stabilire quali operazioni siano avvenute né da chi. ${NUOVO}`
}
