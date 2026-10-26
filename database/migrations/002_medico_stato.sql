-- UC-23, MySQL 8.4: eseguire solo nel database selezionato e dopo autorizzazione.
-- Aggiunge medico.stato (ATTIVO/INATTIVO); i medici esistenti ricevono ATTIVO dal DEFAULT.
-- Non crea tabelle, non converte charset, non modifica altre colonne o dati e non corregge
-- definizioni incompatibili: le segnala con SIGNAL prima di qualsiasi ALTER.
-- Privilegi: SELECT e ALTER su medico; CREATE ROUTINE e ALTER ROUTINE per creare ed eliminare la
-- procedura temporanea; EXECUTE per CALL, se automatic_sp_privileges non lo concede al creatore.
-- DDL con commit impliciti: DROP/CREATE PROCEDURE e ALTER TABLE confermano anche una transazione
-- eventualmente aperta nella sessione, e nessuna transazione può annullare un ALTER TABLE.
-- Tutte le verifiche precedono qualsiasi ALTER; colonna e CHECK mancanti sono aggiunti con un solo
-- ALTER TABLE (DDL atomico InnoDB). Dopo un errore la procedura può restare presente:
-- la prossima esecuzione rimuove esclusivamente questa procedura di migrazione.
-- Riesecuzione: è idempotente solo con valori canonici. Il CHECK, con collation ai_ci, ammette anche
-- varianti come 'attivo' inserite manualmente; la riesecuzione le rifiuta con SIGNAL senza correggerle.
DROP PROCEDURE IF EXISTS medbook_uc23_migrate_002;
DELIMITER $$
CREATE PROCEDURE medbook_uc23_migrate_002()
BEGIN
    DECLARE colonna_presente INT DEFAULT 0;
    DECLARE check_presente INT DEFAULT 0;
    DECLARE definizione_valida INT DEFAULT 0;
    DECLARE valori_non_ammessi INT DEFAULT 0;
    DECLARE colonna_aggiunta BOOLEAN DEFAULT FALSE;
    DECLARE check_aggiunto BOOLEAN DEFAULT FALSE;

    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: selezionare esplicitamente il database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medico'
                     AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: tabella medico InnoDB mancante';
    END IF;
    -- ADD COLUMN eredita charset e collation della tabella: con un charset diverso la colonna creata
    -- sarebbe rifiutata dalla verifica successiva. Nessuna conversione automatica.
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES t
                   JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY a
                     ON a.COLLATION_NAME = t.TABLE_COLLATION
                   WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'medico'
                     AND a.CHARACTER_SET_NAME = 'utf8mb4') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: tabella medico non utf8mb4; conversione manuale non eseguita';
    END IF;

    SELECT COUNT(*) INTO colonna_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medico' AND COLUMN_NAME = 'stato';
    IF colonna_presente > 0 THEN
        -- Stesso charset e collation della tabella: la migrazione non cambia la collation.
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.COLUMNS c
        JOIN information_schema.TABLES t
          ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'medico' AND c.COLUMN_NAME = 'stato'
          AND LOWER(c.COLUMN_TYPE) = 'varchar(20)' AND c.IS_NULLABLE = 'NO'
          AND CAST(c.COLUMN_DEFAULT AS BINARY) = CAST('ATTIVO' AS BINARY) AND c.EXTRA = ''
          AND c.CHARACTER_SET_NAME = 'utf8mb4' AND c.COLLATION_NAME = t.TABLE_COLLATION;
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: colonna incompatibile; richiesti VARCHAR(20) NOT NULL DEFAULT ATTIVO';
        END IF;
        -- Il CHECK con collation ai_ci ammetterebbe anche valori non canonici: confronto binario.
        SELECT COUNT(*) INTO valori_non_ammessi FROM medico
            WHERE CAST(stato AS BINARY) NOT IN (CAST('ATTIVO' AS BINARY), CAST('INATTIVO' AS BINARY));
        IF valori_non_ammessi > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: valori di stato non ammessi nei medici esistenti';
        END IF;
    END IF;

    SELECT COUNT(*) INTO check_presente FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'medico'
          AND CONSTRAINT_NAME = 'chk_medico_stato';
    IF check_presente > 0 THEN
        -- Nessuna normalizzazione del testo: confronto byte per byte con la forma canonica che MySQL
        -- memorizza, (`stato` in (_cs\'ATTIVO\',_cs\'INATTIVO\')). L'introducer dipende dal charset
        -- del client che ha creato il CHECK; sono ammessi solo charset testuali, mai _binary.
        -- CHAR(92) è il backslash memorizzato: nessuna dipendenza da NO_BACKSLASH_ESCAPES.
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        JOIN (SELECT 'utf8mb4' AS cs UNION ALL SELECT 'utf8mb3' UNION ALL SELECT 'latin1' UNION ALL SELECT 'ascii') i
          ON CAST(cc.CHECK_CLAUSE AS BINARY) = CAST(CONCAT('(`stato` in (_', i.cs, CHAR(92), '''ATTIVO', CHAR(92),
                 ''',_', i.cs, CHAR(92), '''INATTIVO', CHAR(92), '''))') AS BINARY)
        WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'medico'
          AND tc.CONSTRAINT_NAME = 'chk_medico_stato'
          AND tc.CONSTRAINT_TYPE = 'CHECK' AND tc.ENFORCED = 'YES';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: CHECK incompatibile; richiesti stato IN (ATTIVO,INATTIVO) ed ENFORCED';
        END IF;
    ELSEIF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'chk_medico_stato') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: nome CHECK gia utilizzato da un altro oggetto';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS tc
               JOIN information_schema.CHECK_CONSTRAINTS cc
                 ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
               WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'medico'
                 AND tc.CONSTRAINT_TYPE = 'CHECK' AND tc.CONSTRAINT_NAME <> 'chk_medico_stato'
                 AND cc.CHECK_CLAUSE LIKE '%`stato`%') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC23: altro CHECK su medico.stato non previsto';
    END IF;

    -- Tutte le verifiche sugli oggetti esistenti precedono qualsiasi ALTER.
    IF colonna_presente = 0 THEN
        ALTER TABLE medico
            ADD COLUMN stato VARCHAR(20) NOT NULL DEFAULT 'ATTIVO',
            ADD CONSTRAINT chk_medico_stato CHECK (stato IN (_utf8mb4'ATTIVO',_utf8mb4'INATTIVO'));
        SET colonna_aggiunta = TRUE;
        SET check_aggiunto = TRUE;
    ELSEIF check_presente = 0 THEN
        ALTER TABLE medico ADD CONSTRAINT chk_medico_stato CHECK (stato IN (_utf8mb4'ATTIVO',_utf8mb4'INATTIVO'));
        SET check_aggiunto = TRUE;
    END IF;
    SELECT colonna_aggiunta, check_aggiunto;
END$$
DELIMITER ;
CALL medbook_uc23_migrate_002();
DROP PROCEDURE medbook_uc23_migrate_002;
