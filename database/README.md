# Schema e migrazioni

Target di esecuzione: MySQL 8.4, InnoDB, `utf8mb4_0900_ai_ci`.
Lo schema corrente è `schema.sql`; contiene `CREATE DATABASE ... medbook` e `USE medbook`.
Non importarlo alla cieca su un server con dati da conservare.

Prima delle scritture verificare host, porta, catalogo, versione e UUID:

```sql
SELECT @@hostname, @@port, @@server_uuid, @@version, DATABASE();
```

La porta interna MySQL non prova la porta Docker pubblicata: verificare anche container,
etichetta di proprietà, mount e binding lato host quando si usa un'istanza isolata.

## Nuova installazione

Su una destinazione nuova esplicitamente scelta e verificata:

```sh
mysql --host='<HOST>' --port='<PORTA>' --user='<UTENTE_SCHEMA>' -p < database/schema.sql
```

Lanciare il comando dalla radice del progetto. Il file crea tabelle, vincoli e indici,
non account MySQL, utenti applicativi o dati dimostrativi. Se si sceglie un nome catalogo
diverso, adattare esplicitamente le due istruzioni iniziali e la configurazione runtime.
Non eseguire le migrazioni per installare da zero uno schema già corrente.

Predisporre separatamente l'utente applicativo, con password privata e privilegi
`SELECT, INSERT, UPDATE, DELETE` sul solo catalogo applicativo; il backend non richiede
privilegi DDL o accesso a `performance_schema`. Non collegarsi come root per il normale uso.
Creare il primo amministratore con il comando interattivo del [README principale](../README.md).

La demo Docker esegue questi passi da sola al primo avvio con volume vuoto
(`docker/mysql/10-medbook.sh`): schema corrente, utente applicativo con soli privilegi DML e un
catalogo `medbook_demo` che marca l'installazione dimostrativa. Vedi il README principale.

## Database precedenti

- `001_prenotazione_indisponibilita.sql`: flag di indisponibilità della prenotazione.
- `002_medico_stato.sql`: stato operativo medico.
- `003_utente_accesso_modifica.sql`: stato di accesso, generazione e metadati dell'account.

Applicare soltanto la migrazione pertinente dopo backup e verifica della struttura di partenza,
nel catalogo selezionato. Sono presenti procedure temporanee e DDL con commit impliciti:
leggere le precondizioni e i privilegi dichiarati nei file. Una transazione esterna non
rende annullabile un ALTER. Le procedure rifiutano strutture incompatibili; non sono uno
strumento di correzione automatica dei dati.

`src/test/resources/database/schema-pre003.sql` è una fixture DDL senza dati, precedente
alla 003 e autonoma dalla storia Git. Serve esclusivamente ai test di migrazione.
Non usarla per una nuova installazione applicativa.

Database e archivio dei referti formano un insieme: conservarli con un backup coordinato.
Non distribuire dump del database personale o di una demo già utilizzata.
