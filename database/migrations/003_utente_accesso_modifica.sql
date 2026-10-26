-- UC-26, MySQL 8.4: eseguire solo nel database selezionato e dopo autorizzazione.
-- Aggiunge a utente: stato_accesso (ATTIVO/DISABILITATO), generazione_autenticazione,
-- modificato_da (chiave esterna autoreferenziale ON DELETE SET NULL) e modificato_il.
-- Gli utenti esistenti ricevono ATTIVO e generazione 0 dai DEFAULT; i campi di tracciamento
-- restano NULL; questa migrazione non ricostruisce metadati delle operazioni precedenti.
-- Non crea tabelle, non converte charset, non modifica altre tabelle, non esegue UPDATE sui dati
-- e non corregge definizioni incompatibili: le segnala con SIGNAL prima di qualsiasi ALTER.
-- Privilegi: SELECT, ALTER e REFERENCES su utente; CREATE ROUTINE e ALTER ROUTINE per creare ed
-- eliminare la procedura temporanea; EXECUTE per CALL, se automatic_sp_privileges non lo concede.
-- DDL con commit impliciti: DROP/CREATE PROCEDURE e ALTER TABLE confermano anche una transazione
-- eventualmente aperta nella sessione, e nessuna transazione può annullare un ALTER TABLE.
-- Tutte le verifiche precedono qualsiasi ALTER; gli elementi mancanti sono aggiunti con un solo
-- ALTER TABLE (DDL atomico InnoDB). Dopo un errore la procedura può restare presente:
-- la prossima esecuzione rimuove esclusivamente questa procedura di migrazione.
-- Riesecuzione: idempotente con definizioni compatibili e dati validi; i valori già presenti
-- (stati, generazioni, autori, date) non vengono mai modificati né azzerati.
-- CHECK con confronto esatto: stato_accesso COLLATE utf8mb4_0900_bin (NO PAD). Con la collation
-- ai_ci della tabella il CHECK ammetterebbe 'attivo' e 'ÀTTIVO'; utf8mb4_bin (PAD SPACE)
-- ammetterebbe 'ATTIVO '. Così il database accetta soltanto 'ATTIVO' e 'DISABILITATO'.
-- Dominio applicativo della generazione: 0..9223372036854775807 (Long.MAX_VALUE).
-- Nessun CHECK su modificato_da: MySQL lo vieta sulle colonne usate da un'azione SET NULL (3823).
DROP PROCEDURE IF EXISTS medbook_uc26_migrate_003;
DELIMITER $$
CREATE PROCEDURE medbook_uc26_migrate_003()
BEGIN
    DECLARE stato_presente INT DEFAULT 0;
    DECLARE generazione_presente INT DEFAULT 0;
    DECLARE autore_presente INT DEFAULT 0;
    DECLARE data_presente INT DEFAULT 0;
    DECLARE check_presente INT DEFAULT 0;
    DECLARE fk_presente INT DEFAULT 0;
    DECLARE definizione_valida INT DEFAULT 0;
    DECLARE valori_non_ammessi INT DEFAULT 0;
    DECLARE modifiche VARCHAR(1000) DEFAULT '';

    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: selezionare esplicitamente il database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente'
                     AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: tabella utente InnoDB mancante';
    END IF;
    -- ADD COLUMN eredita charset e collation della tabella: nessuna conversione automatica.
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES t
                   JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY a
                     ON a.COLLATION_NAME = t.TABLE_COLLATION
                   WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'utente'
                     AND a.CHARACTER_SET_NAME = 'utf8mb4') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: tabella utente non utf8mb4; conversione manuale non eseguita';
    END IF;

    -- stato_accesso: VARCHAR(20) NOT NULL DEFAULT 'ATTIVO', charset e collation della tabella.
    SELECT COUNT(*) INTO stato_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'stato_accesso';
    IF stato_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.COLUMNS c
        JOIN information_schema.TABLES t
          ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'utente' AND c.COLUMN_NAME = 'stato_accesso'
          AND LOWER(c.COLUMN_TYPE) = 'varchar(20)' AND c.IS_NULLABLE = 'NO'
          AND CAST(c.COLUMN_DEFAULT AS BINARY) = CAST('ATTIVO' AS BINARY) AND c.EXTRA = ''
          AND c.CHARACTER_SET_NAME = 'utf8mb4' AND c.COLLATION_NAME = t.TABLE_COLLATION;
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: stato_accesso incompatibile; richiesti VARCHAR(20) NOT NULL DEFAULT ATTIVO';
        END IF;
        -- La collation ai_ci ammetterebbe varianti come 'attivo': confronto binario, nessuna correzione.
        SELECT COUNT(*) INTO valori_non_ammessi FROM utente
            WHERE CAST(stato_accesso AS BINARY) NOT IN (CAST('ATTIVO' AS BINARY), CAST('DISABILITATO' AS BINARY));
        IF valori_non_ammessi > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: valori di stato_accesso non canonici negli utenti esistenti';
        END IF;
    END IF;

    -- generazione_autenticazione: BIGINT UNSIGNED NOT NULL DEFAULT 0, valori entro Long.MAX_VALUE.
    SELECT COUNT(*) INTO generazione_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'generazione_autenticazione';
    IF generazione_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'generazione_autenticazione'
          AND LOWER(COLUMN_TYPE) = 'bigint unsigned' AND IS_NULLABLE = 'NO'
          AND CAST(COLUMN_DEFAULT AS BINARY) = CAST('0' AS BINARY) AND EXTRA = '';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: generazione_autenticazione incompatibile; richiesti BIGINT UNSIGNED NOT NULL DEFAULT 0';
        END IF;
        SELECT COUNT(*) INTO valori_non_ammessi FROM utente
            WHERE generazione_autenticazione > 9223372036854775807;
        IF valori_non_ammessi > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: generazione_autenticazione oltre il dominio applicativo';
        END IF;
    END IF;

    -- modificato_da: INT UNSIGNED NULL senza default; ogni valore deve riferire un utente esistente.
    SELECT COUNT(*) INTO autore_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'modificato_da';
    IF autore_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'modificato_da'
          AND LOWER(COLUMN_TYPE) = 'int unsigned' AND IS_NULLABLE = 'YES'
          AND COLUMN_DEFAULT IS NULL AND EXTRA = '';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: modificato_da incompatibile; richiesto INT UNSIGNED NULL senza default';
        END IF;
        SELECT COUNT(*) INTO valori_non_ammessi FROM utente u
            WHERE u.modificato_da IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM utente r WHERE r.id_utente = u.modificato_da);
        IF valori_non_ammessi > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: modificato_da riferisce utenti inesistenti';
        END IF;
    END IF;

    -- modificato_il: DATETIME senza frazioni, NULL senza default e senza ON UPDATE.
    SELECT COUNT(*) INTO data_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'modificato_il';
    IF data_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'modificato_il'
          AND LOWER(DATA_TYPE) = 'datetime' AND LOWER(COLUMN_TYPE) = 'datetime'
          AND DATETIME_PRECISION = 0 AND IS_NULLABLE = 'YES'
          AND COLUMN_DEFAULT IS NULL AND EXTRA = '';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: modificato_il incompatibile; richiesto DATETIME NULL senza default ne ON UPDATE';
        END IF;
    END IF;

    -- CHECK chk_utente_stato_accesso: confronto byte per byte con la forma canonica memorizzata,
    -- ((`stato_accesso` collate utf8mb4_0900_bin) in (_cs\'ATTIVO\',_cs\'DISABILITATO\')). Un CHECK
    -- senza la collation binaria è rifiutato. L'introducer dipende dal charset del client che ha
    -- creato il CHECK; sono ammessi solo charset testuali, mai _binary.
    -- CHAR(92) è il backslash memorizzato: nessuna dipendenza da NO_BACKSLASH_ESCAPES.
    SELECT COUNT(*) INTO check_presente FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'utente'
          AND CONSTRAINT_NAME = 'chk_utente_stato_accesso';
    IF check_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        JOIN (SELECT 'utf8mb4' AS cs UNION ALL SELECT 'utf8mb3' UNION ALL SELECT 'latin1' UNION ALL SELECT 'ascii') i
          ON CAST(cc.CHECK_CLAUSE AS BINARY) = CAST(CONCAT('((`stato_accesso` collate utf8mb4_0900_bin) in (_', i.cs, CHAR(92), '''ATTIVO', CHAR(92),
                 ''',_', i.cs, CHAR(92), '''DISABILITATO', CHAR(92), '''))') AS BINARY)
        WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'utente'
          AND tc.CONSTRAINT_NAME = 'chk_utente_stato_accesso'
          AND tc.CONSTRAINT_TYPE = 'CHECK' AND tc.ENFORCED = 'YES';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: CHECK incompatibile; richiesti confronto utf8mb4_0900_bin su ATTIVO,DISABILITATO ed ENFORCED';
        END IF;
    ELSEIF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'chk_utente_stato_accesso') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: nome CHECK gia utilizzato da un altro oggetto';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS tc
               JOIN information_schema.CHECK_CONSTRAINTS cc
                 ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
               WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'utente'
                 AND tc.CONSTRAINT_TYPE = 'CHECK' AND tc.CONSTRAINT_NAME <> 'chk_utente_stato_accesso'
                 AND (cc.CHECK_CLAUSE LIKE '%`stato_accesso`%' OR cc.CHECK_CLAUSE LIKE '%`generazione_autenticazione`%'
                      OR cc.CHECK_CLAUSE LIKE '%`modificato_da`%' OR cc.CHECK_CLAUSE LIKE '%`modificato_il`%')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: altro CHECK sulle colonne UC-26 non previsto';
    END IF;

    -- Chiave esterna fk_utente_modificato_da: soltanto modificato_da -> utente(id_utente),
    -- ON UPDATE RESTRICT e ON DELETE SET NULL.
    SELECT COUNT(*) INTO fk_presente FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'utente'
          AND CONSTRAINT_NAME = 'fk_utente_modificato_da';
    IF fk_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.REFERENTIAL_CONSTRAINTS rc
        WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.CONSTRAINT_NAME = 'fk_utente_modificato_da'
          AND rc.TABLE_NAME = 'utente' AND rc.REFERENCED_TABLE_NAME = 'utente'
          AND rc.UPDATE_RULE = 'RESTRICT' AND rc.DELETE_RULE = 'SET NULL'
          AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k
               WHERE k.CONSTRAINT_SCHEMA = DATABASE() AND k.CONSTRAINT_NAME = 'fk_utente_modificato_da'
                 AND k.TABLE_NAME = 'utente') = 1
          AND EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k
               WHERE k.CONSTRAINT_SCHEMA = DATABASE() AND k.CONSTRAINT_NAME = 'fk_utente_modificato_da'
                 AND k.TABLE_NAME = 'utente' AND k.COLUMN_NAME = 'modificato_da'
                 AND k.REFERENCED_TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME = 'utente'
                 AND k.REFERENCED_COLUMN_NAME = 'id_utente');
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: chiave esterna incompatibile; richiesti modificato_da -> utente(id_utente), RESTRICT, SET NULL';
        END IF;
    ELSEIF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'fk_utente_modificato_da') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: nome chiave esterna gia utilizzato da un altro oggetto';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k
               WHERE k.CONSTRAINT_SCHEMA = DATABASE() AND k.TABLE_NAME = 'utente'
                 AND k.REFERENCED_TABLE_NAME IS NOT NULL
                 AND k.CONSTRAINT_NAME <> 'fk_utente_modificato_da'
                 AND k.COLUMN_NAME IN ('stato_accesso', 'generazione_autenticazione', 'modificato_da', 'modificato_il')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC26: altra chiave esterna sulle colonne UC-26 non prevista';
    END IF;

    -- Tutte le verifiche sugli oggetti esistenti precedono qualsiasi ALTER.
    IF stato_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD COLUMN stato_accesso VARCHAR(20) NOT NULL DEFAULT ''ATTIVO''');
    END IF;
    IF generazione_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD COLUMN generazione_autenticazione BIGINT UNSIGNED NOT NULL DEFAULT 0');
    END IF;
    IF autore_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD COLUMN modificato_da INT UNSIGNED NULL');
    END IF;
    IF data_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD COLUMN modificato_il DATETIME NULL');
    END IF;
    IF check_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD CONSTRAINT chk_utente_stato_accesso',
            ' CHECK (stato_accesso COLLATE utf8mb4_0900_bin IN (_utf8mb4''ATTIVO'',_utf8mb4''DISABILITATO''))');
    END IF;
    IF fk_presente = 0 THEN
        SET modifiche = CONCAT(modifiche, ', ADD CONSTRAINT fk_utente_modificato_da',
            ' FOREIGN KEY (modificato_da) REFERENCES utente(id_utente) ON UPDATE RESTRICT ON DELETE SET NULL');
    END IF;
    IF modifiche <> '' THEN
        -- Un solo ALTER TABLE: nessuno stato intermedio visibile in caso di errore.
        SET @medbook_uc26_003_ddl = CONCAT('ALTER TABLE utente', SUBSTRING(modifiche, 2));
        PREPARE medbook_uc26_003_stmt FROM @medbook_uc26_003_ddl;
        EXECUTE medbook_uc26_003_stmt;
        DEALLOCATE PREPARE medbook_uc26_003_stmt;
        SET @medbook_uc26_003_ddl = NULL;
    END IF;
    SELECT stato_presente = 0 AS stato_aggiunto, generazione_presente = 0 AS generazione_aggiunta,
           autore_presente = 0 AS autore_aggiunto, data_presente = 0 AS data_aggiunta,
           check_presente = 0 AS check_aggiunto, fk_presente = 0 AS fk_aggiunta;
END$$
DELIMITER ;
CALL medbook_uc26_migrate_003();
DROP PROCEDURE medbook_uc26_migrate_003;
