# Verifiche isolate

## Test unitari e integrazioni MySQL

Dalla radice del progetto: `mvn test`. Le integrazioni non configurate sono saltate.
Non indirizzare mai le fixture al database applicativo o alla demo.
Per le integrazioni ordinarie predisporre un MySQL Docker 8.4 nuovo e dedicato e verificare
container, volume, binding loopback, UUID e cataloghi prima delle scritture.

| Variabile | Uso |
|---|---|
| `MEDBOOK_TEST_DB_URL` | `jdbc:mysql://127.0.0.1:<PORTA>/medbook_test`, porta 1024–65535 diversa da 3306, senza parametri URL |
| `MEDBOOK_TEST_DB_USERNAME` | Utente esclusivo dei test |
| `MEDBOOK_TEST_DB_PASSWORD` | Password esclusiva dei test |
| `MEDBOOK_TEST_DB_SERVER_UUID` | UUID MySQL verificato; impostarlo sempre |
| `MEDBOOK_TEST_BOOTSTRAP_DB_URL` | Catalogo `medbook_test_bootstrap` sullo stesso server, schema corrente, svuotato dai test |
| `MEDBOOK_TEST_PRE003_DB_URL` | Catalogo `medbook_test_pre003`, fixture pre003, alterato e ripristinato dai test |
| `MEDBOOK_QPDF_EXECUTABLE` | Percorso assoluto qpdf 12.4.2 per le verifiche PDF reali |

Il catalogo principale ammette anche `medbook_test_<suffisso>`. Importare soltanto le
tabelle nello specifico catalogo di test verificato, eliminando dal DDL le istruzioni
CREATE DATABASE/USE. L'utente di test necessita dei privilegi sulle fixture, inclusi
quelli DDL per le prove di bootstrap, e di SELECT su `performance_schema.data_locks` e
`performance_schema.data_lock_waits`. Questi privilegi non vanno concessi all'utente applicativo.
Alcuni test richiedono esplicitamente UUID e qpdf; riportare gli skip, senza contarli come successi.

## End-to-end

Servono Bash, Docker, Python 3, OpenSSL, netcat e Java 21. Lo script crea soltanto
container e volumi nuovi, pubblicando MySQL sul loopback. Non avvia la demo dell'applicazione.
Scegliere una porta libera diversa da 3306 e una directory nuova esterna al progetto:

```sh
bash scripts/e2e/mysql-e2e.sh crea --schema nuovo --porta 34091 --stato "$HOME/medbook-test-nuovo"
bash scripts/e2e/mysql-e2e.sh verifica --stato "$HOME/medbook-test-nuovo"
(
  . "$HOME/medbook-test-nuovo/e2e.env"
  export MEDBOOK_QPDF_EXECUTABLE='<PERCORSO_ASSOLUTO_QPDF_12_4_2>'
  MEDBOOK_E2E_MODE=obbligatoria mvn -Dtest='it.medbook.e2e.*Test' test
)
bash scripts/e2e/mysql-e2e.sh elimina --stato "$HOME/medbook-test-nuovo"
```

Per provare lo schema migrato usare `--schema migrato`, una seconda porta libera e una
nuova directory. Il template deriva dalla fixture SQL pre003 inclusa, seguita dalla 003;
non richiede commit precedenti né un repository Git. Eseguire la stessa suite anche qui.
Non condividere il server E2E con le integrazioni ordinarie: i controlli verificano i cataloghi ammessi.

Le credenziali casuali restano nei file privati dello stato, non vanno copiate nel progetto.
`elimina` controlla l'appartenenza e rimuove soltanto container e volume registrati;
leggere l'esito del comando prima di considerare conclusa la pulizia.

Le classi E2E richiedono `MEDBOOK_E2E_*` dal file generato. Con modalità facoltativa e
configurazione assente vengono saltate; con `MEDBOOK_E2E_MODE=obbligatoria` la configurazione
mancante o incoerente fallisce. Eseguirle in sequenza, senza parallelismo Surefire.
Le fixture verificano nuovamente catalogo e UUID nelle connessioni e non caricano dati reali.
