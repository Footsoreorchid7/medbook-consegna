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
- `Dockerfile`, `compose.yaml`, `docker/`, `scripts/demo`: demo locale con Docker e dati fittizi.

## Demo locale con Docker

Ambiente dimostrativo da usare solo sul proprio computer: dati interamente fittizi, pagamenti
simulati, nessuna transazione reale. Usa HTTP sul loopback con `MEDBOOK_AUTH_MODE=local-http` e
**non è una configurazione adatta alla pubblicazione online** (servirebbero HTTPS, un reverse proxy
fidato e una revisione della sicurezza operativa).

Servizi: MySQL 8.4.13, backend Java 21 (Temurin 21.0.12.1) con qpdf 12.4.2 compilato dal sorgente
ufficiale con SHA-256 verificato, server web nginx 1.30.6 che serve il frontend compilato e inoltra
`/api` al backend. Il browser usa una sola origine, `http://localhost:<porta>`. Solo il servizio web
è pubblicato, sul loopback; database e backend restano su reti interne di Compose. Backend e web
girano con utenti non privilegiati, file system in sola lettura e senza capability.

### Prerequisiti

- Docker Engine con Compose v2 (verificato con Docker Desktop, Engine 29.8.2 e Compose 5.5.1).
- Bash e `od` per lo script di configurazione; circa 4 GB liberi per immagini (1,2 GB) e cache di build.
- Verifica effettuata su `linux/amd64` (Mac Intel). Le immagini di base sono pubblicate anche per
  `linux/arm64` e qpdf viene compilato dal sorgente, ma l'esecuzione su arm64 non è stata provata.

### Configurazione

Dalla radice del progetto:

```sh
scripts/demo/configura.sh            # porta predefinita 8088
scripts/demo/configura.sh --porta 8090   # in alternativa, altra porta
```

Lo script crea `.env` (porta, fuso orario, codice dell'installazione) e la cartella privata
`.medbook-demo/` con le password MySQL e le credenziali degli account dimostrativi, generate a
caso. Non sovrascrive file esistenti; entrambi sono esclusi da Git e dal contesto di build.
Il database usa tre account distinti: `root` solo locale al container per l'inizializzazione,
`medbook_app` con i soli privilegi DML per l'applicazione, `medbook_dati` per il caricamento
dei dati dimostrativi.

### Build, primo avvio e dati dimostrativi

```sh
docker compose up -d --build --wait
docker compose run --rm dati
```

Il primo comando costruisce le immagini (la compilazione di qpdf richiede alcuni minuti) e
attende che i servizi risultino pronti. Con il volume vuoto, MySQL crea schema, utenti e un
marcatore dell'installazione. Il secondo comando carica i dati fittizi usando i servizi
dell'applicazione: account, stanze, prestazioni, abilitazioni, disponibilità, appuntamenti in
tutti gli stati, pagamenti simulati (anche un tentativo non riuscito), rimborsi e un referto PDF
sintetico. Le date sono calcolate rispetto al momento del caricamento, nel fuso `MEDBOOK_TZ`
(predefinito `Europe/Rome`, lo stesso usato dal backend): una settimana di storico e circa due
settimane di disponibilità future. I dati non vengono aggiornati ai riavvii successivi; per
riportarli al giorno corrente usare il reset.

Il caricamento agisce solo sul database marcato da questa configurazione e non si ripete: una
seconda esecuzione risponde «Dati dimostrativi già caricati: nessuna modifica». Se viene
interrotto, la demo resta nello stato `DATI_FALLITI` e il caricamento successivo chiede il reset.
Stato corrente: `docker compose run --rm dati stato-dati`.

Aprire **http://localhost:8088** (o la porta scelta). Usare `localhost`, non `127.0.0.1`: l'origine
ammessa dal backend è esattamente `http://localhost:<porta>`.

### Account e percorso dimostrativo

Le password sono in `.medbook-demo/credenziali-demo.txt` (righe `ruolo email password`):

```sh
cat .medbook-demo/credenziali-demo.txt
```

| Ruolo nel file | Account | Cosa provare |
|---|---|---|
| `paziente` | Giulia Neri | Prenotazioni in tutti gli stati; pagamento di un appuntamento confermato; referto da scaricare; rimborso approvato e uno in attesa; nuova richiesta e annullamento. |
| `paziente2` | Paolo Rinaldi | Pagamento non riuscito da ritentare; rimborso respinto; paziente assente. |
| `cardiologia` | Marta Galli | Agenda; appuntamento trascorso da registrare e referto da caricare; segnalazione di indisponibilità; disponibilità. |
| `dermatologia` | Luca Moretti | Visite effettuate senza referto, su cui provare il caricamento. |
| `medicina-generale` | Elena Conti | Medico sostituto per la cardiologia. |
| `medico-paziente` | Sara Fontana | Account con entrambi i profili: agenda e prenotazioni personali. |
| `desk` | Davide Sartori | Richieste da confermare o rifiutare, segnalazione da risolvere con sostituzione, rimborso da decidere, esiti da registrare, slot da configurare, incassi in sede, pazienti. |
| `amministratore` | Alessandra Ferri | Utenti e ruoli, medici e abilitazioni, stanze, prestazioni e prezzi. |

Email, codici fiscali e nomi sono inventati; i codici fiscali usano il codice comune non
assegnato `Z999` e superano soltanto la validazione formale dell'applicazione.

### Arresto, riavvio e persistenza

```sh
docker compose stop        # arresto, container conservati
docker compose start       # riavvio
docker compose down        # rimozione dei container: i volumi restano
docker compose up -d --wait
```

Database e referti stanno nei volumi `medbook-demo_mysql-dati` e `medbook-demo_referti` e
sopravvivono ad arresti, riavvii e ricreazione dei container. Le sessioni sono in memoria: dopo un
riavvio del backend occorre accedere di nuovo. Log: `docker compose logs backend` (o `mysql`, `web`).

### Reset esplicito

```sh
scripts/demo/reset.sh --progetto medbook-demo
docker compose up -d --wait
docker compose run --rm dati
```

Il reset verifica che il progetto Compose configurato coincida con quello indicato e che i suoi
volumi siano quelli attesi, mostra cosa verrà eliminato e chiede di digitare il nome del progetto.
Rimuove solo container, reti e volumi di questo progetto (database e referti insieme); non tocca
immagini, `.env` e `.medbook-demo/`, né risorse di altri progetti. Non esiste una procedura di
backup e ripristino: per conservare una demo, database e volume dei referti vanno salvati insieme.

### Problemi comuni

- **Porta occupata**: scegliere un'altra porta modificando `MEDBOOK_WEB_PORT` in `.env`, poi
  `docker compose up -d --wait`.
- **Accesso rifiutato dal browser**: aprire l'indirizzo con `localhost` e la porta configurata.
- **Configurazione assente**: Compose segnala `MEDBOOK_DEMO_INSTALLAZIONE` mancante o un segreto
  inesistente; eseguire `scripts/demo/configura.sh`.
- **Inizializzazione di MySQL non riuscita** (`docker compose logs mysql`): il volume resta
  parzialmente inizializzato e non viene ripreso automaticamente; correggere la causa ed eseguire
  il reset.
- **Memoria insufficiente durante la build di qpdf**: la compilazione usa 2 processi paralleli,
  riducibili con `docker compose build --build-arg QPDF_JOBS=1`.

## Prerequisiti senza Docker

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
