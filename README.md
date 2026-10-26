# MedBook

Applicazione dimostrativa per un centro medico: catalogo e disponibilità pubblici,
richieste di appuntamento, agenda medico, referti PDF, pagamenti simulati, rimborsi
amministrativi e gestione degli account.

I profili paziente e medico possono coesistere sullo stesso account. L'operatore desk
gestisce appuntamenti, incassi e rimborsi; l'amministratore gestisce utenti e cataloghi,
senza ereditare i permessi del desk. L'approvazione di un rimborso non esegue la restituzione
del denaro. Nessuna transazione bancaria reale viene effettuata.

## Struttura

- `src/main`: backend Java e configurazione di base.
- `src/test`: test unitari, integrazione, E2E e fixture SQL sintetiche.
- `frontend`: applicazione Vue e test.
- `database`: schema corrente e migrazioni incrementali.
- `scripts/e2e`: preparazione e rimozione controllata di un MySQL temporaneo per gli E2E.

Non sono inclusi ambienti Docker dell'applicazione né dati dimostrativi precaricati.

## Prerequisiti

- JDK 21 e Maven; la verifica locale usa Maven 3.10.0. Non è incluso un wrapper.
- Node `^22.12.0 || ^24.0.0 || >=26.0.0`, npm e `package-lock.json`.
- MySQL 8.4, con schema InnoDB e charset utf8mb4.
- qpdf **esattamente 12.4.2**, controllato dal backend all'avvio; PDFBox 3.0.8 è una dipendenza Maven.
- Filesystem POSIX per i referti, con rinomina atomica nello stesso filesystem.
- Per gli E2E: Docker, Bash, Python 3, OpenSSL e netcat (`nc`).

Jackson gestisce il JSON, JDBC accede a MySQL, HttpServer del JDK espone le API.
Il frontend usa Vue 3, Vue Router e Vite. Non viene usato Spring.

## Configurazione

Il backend legge `src/main/resources/application.properties` dal classpath.
Gli override d'ambiente espliciti prevalgono sulle proprietà; un override vuoto è rifiutato.
`.env.example` è soltanto un modello: **né Java né Maven caricano automaticamente `.env`**.
Esportare le variabili nel terminale che avvia Java. Non versionare segreti.

| Variabile | Valore predefinito / significato |
|---|---|
| `MEDBOOK_DB_URL` | `db.url`: `jdbc:mysql://localhost:3306/medbook` |
| `MEDBOOK_DB_USERNAME` | `db.username`: `medbook_user` |
| `MEDBOOK_DB_PASSWORD` | Obbligatoria; non viene rimossa la spaziatura dalla password |
| `MEDBOOK_HTTP_HOST` | `127.0.0.1`; indirizzo risolvibile, override esplicito per altre interfacce |
| `MEDBOOK_HTTP_PORT` | `8080`; intero 1–65535 |
| `MEDBOOK_AUTH_MODE` | `https`; per lo sviluppo HTTP locale impostare esplicitamente `local-http` |
| `MEDBOOK_AUTH_ORIGIN` | Obbligatoria, origine esatta senza percorso né slash finale |
| `MEDBOOK_REFERTI_DIRECTORY` | Obbligatoria, directory assoluta privata esterna al progetto |
| `MEDBOOK_QPDF_EXECUTABLE` | Obbligatoria, percorso assoluto dell'eseguibile qpdf 12.4.2 |
| `MEDBOOK_SIMULATED_PAYMENT_OUTCOME` | `PAGATO`; alternativa `FALLITO` per la simulazione |

L'override del listener non cambia l'origine ammessa o la politica dei cookie.
La modalità HTTP locale accetta soltanto origini `localhost` o `127.0.0.1`.
In modalità HTTPS i cookie sono Secure; occorre un reverse proxy fidato, non fornito qui.
Non esporre direttamente il backend come se fosse un server HTTPS.

## Preparazione MySQL e primo amministratore

Seguire [database/README.md](database/README.md) per verificare la destinazione,
creare lo schema corrente e predisporre un utente applicativo con privilegi DML.
Non usare il database applicativo per i test. Le migrazioni sono destinate a database
precedenti, non sono una sequenza da applicare automaticamente dopo lo schema corrente.

Con database configurato, da un **terminale interattivo**:

```sh
read -r -s MEDBOOK_DB_PASSWORD
export MEDBOOK_DB_PASSWORD
mvn compile exec:exec@bootstrap-amministratore
```

Il comando mostra la destinazione, richiede conferma e acquisisce senza eco la password
del primo amministratore. Non crea nulla se esiste già un account gestionale.
Non accetta password negli argomenti. Gli altri account si gestiscono tramite le funzioni
applicative autorizzate; la registrazione pubblica crea un paziente. Non sono fornite
credenziali predefinite o account della precedente demo locale.

## Avvio locale

Preparare fuori dal progetto una directory privata, senza symlink e con padre esistente:

```sh
mkdir -m 700 "$HOME/medbook-referti"
export MEDBOOK_REFERTI_DIRECTORY="$HOME/medbook-referti"
export MEDBOOK_QPDF_EXECUTABLE='<PERCORSO_ASSOLUTO_QPDF_12_4_2>'
export MEDBOOK_AUTH_MODE=local-http
export MEDBOOK_AUTH_ORIGIN=http://localhost:5173
# Esportare anche la password e gli eventuali override MySQL indicati sopra.
mvn compile exec:exec
```

Il backend ascolta su `127.0.0.1:8080` per default. Il comando Maven avvia la classe
`it.medbook.Main` con le dipendenze runtime; non è previsto un JAR autonomo eseguibile.
Arrestare il processo dal suo terminale. Le sessioni in memoria decadono al riavvio.

In un secondo terminale:

```sh
cd frontend
npm ci
npm run dev
```

Aprire **http://localhost:5173**. Vite inoltra `/api` a `127.0.0.1:8080` senza riscrivere
Origin e cookie. Se si cambia porta backend occorre allineare il proxy in
`frontend/vite.config.js`; se si cambia origine browser, allineare anche l'autenticazione.
Non alternare `localhost` e `127.0.0.1` nel browser.

## Test e build

```sh
mvn test
cd frontend
npm ci
npm test
npm run build
```

I test Java senza database vengono eseguiti normalmente. Le integrazioni MySQL richiedono
configurazione separata e vengono saltate quando manca: un risultato Maven positivo non
prova, da solo, che siano state eseguite. Prerequisiti e isolamento sono descritti in
[scripts/e2e/README.md](scripts/e2e/README.md). La build frontend genera `frontend/dist`;
`vite preview` non fornisce il proxy di sviluppo e non costituisce un ambiente completo.

## Referti e limiti

Il medico effettivo carica un PDF esterno; non si genera un referto nell'applicazione.
Il documento deve superare i controlli PDF, inclusa la validazione qpdf. Il file deve essere non vuoto, al massimo 10 MiB.
I referti sono unici e non modificabili dalle funzioni attuali. Il paziente proprietario
accede a metadati e download autenticati; non esistono URL pubblici dello storage.

Il backend richiede qpdf e storage anche se non si caricano documenti. Le directory
`staging`, `documents`, `scratch` sono private (0700), i file 0600. Non montarle come cartelle
pubbliche. Database e referti devono essere salvati e ripristinati insieme; una copia del
solo database non contiene i PDF. Non esiste una pulizia automatica sicura di ogni file orfano.
La validazione dei PDF non è un antivirus. Usare esclusivamente dati e documenti sintetici.

Le date/ore locali delle API non dichiarano un fuso geografico; la data HTTP non basta a
determinarlo. Le regole temporali sono verificate dal backend, non dall'orologio del browser.
Questa consegna è dimostrativa e non certifica idoneità clinica o messa in produzione.

## Licenze

Non viene assegnata una licenza al codice originale in questa preparazione.
Le dipendenze mantengono le proprie licenze: consultare gli artefatti Maven e npm
originali. Non sono inclusi binari, font o librerie di terzi copiati manualmente;
prima di redistribuire un pacchetto eseguibile conservarne anche le attribuzioni richieste.
