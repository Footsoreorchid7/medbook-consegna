# Script sorgente (non eseguibile): richiede le funzioni dell'entrypoint MySQL.
# Inizializzazione della demo, eseguita dall'entrypoint di MySQL solo con volume dati vuoto.
# Crea schema, utenti con privilegi minimi e marcatore dell'installazione; nessun dato applicativo.
# Le password arrivano dai segreti Compose e raggiungono MySQL solo tramite stdin.

leggi_segreto() {
	local file="/run/secrets/$1"
	if [ ! -r "$file" ]; then
		mysql_error "Segreto $1 non leggibile: rigenerare la configurazione con scripts/demo/configura.sh."
	fi
	local valore
	valore="$(cat "$file")"
	if [[ ! "$valore" =~ ^[0-9a-f]{32,128}$ ]]; then
		mysql_error "Segreto $1 in formato non valido (attese cifre esadecimali)."
	fi
	printf '%s' "$valore"
}

if [[ ! "${MEDBOOK_DEMO_INSTALLAZIONE:-}" =~ ^[0-9a-f]{32}$ ]]; then
	mysql_error "MEDBOOK_DEMO_INSTALLAZIONE mancante o non valida: eseguire scripts/demo/configura.sh."
fi
password_app="$(leggi_segreto mysql_app)"
password_dati="$(leggi_segreto mysql_dati)"

mysql_note "MedBook: creazione dello schema corrente"
docker_process_sql < /medbook/schema.sql

mysql_note "MedBook: utenti applicativi e marcatore della demo"
docker_process_sql <<-SQL
	CREATE DATABASE medbook_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
	CREATE TABLE medbook_demo.installazione (
	    id TINYINT UNSIGNED PRIMARY KEY,
	    codice CHAR(32) NOT NULL,
	    server_uuid CHAR(36) NOT NULL,
	    stato VARCHAR(20) NOT NULL,
	    aggiornato_il DATETIME NOT NULL,
	    messaggio VARCHAR(255) NULL,
	    CONSTRAINT chk_installazione_unica CHECK (id = 1),
	    CONSTRAINT chk_installazione_stato
	        CHECK (stato IN ('SCHEMA_PRONTO','DATI_IN_CORSO','DATI_COMPLETATI','DATI_FALLITI'))
	) ENGINE=InnoDB;

	-- Esecuzione ordinaria: soli privilegi DML sul catalogo applicativo.
	CREATE USER 'medbook_app'@'%' IDENTIFIED BY '${password_app}';
	GRANT SELECT, INSERT, UPDATE, DELETE ON medbook.* TO 'medbook_app'@'%';

	-- Caricamento dei dati dimostrativi: DML applicativo e aggiornamento del solo marcatore.
	CREATE USER 'medbook_dati'@'%' IDENTIFIED BY '${password_dati}';
	GRANT SELECT, INSERT, UPDATE, DELETE ON medbook.* TO 'medbook_dati'@'%';
	GRANT SELECT, UPDATE ON medbook_demo.installazione TO 'medbook_dati'@'%';

	-- Ultima istruzione: senza marcatore l'inizializzazione è considerata incompleta.
	INSERT INTO medbook_demo.installazione (id, codice, server_uuid, stato, aggiornato_il)
	VALUES (1, '${MEDBOOK_DEMO_INSTALLAZIONE}', @@server_uuid, 'SCHEMA_PRONTO', NOW());
SQL
unset password_app password_dati
mysql_note "MedBook: inizializzazione completata"
