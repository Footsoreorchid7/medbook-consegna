-- UC-20, MySQL 8.4: eseguire solo nel database selezionato e dopo autorizzazione.
-- Non crea tabelle e non corregge dati/definizioni incompatibili.
-- Richiede ALTER e CREATE/ALTER ROUTINE per la procedura temporanea della migrazione.
-- DDL con commit impliciti. Dopo un errore la procedura può restare presente:
-- la prossima esecuzione rimuove esclusivamente questa procedura di migrazione.
DROP PROCEDURE IF EXISTS medbook_uc20_migrate_001;
DELIMITER $$
CREATE PROCEDURE medbook_uc20_migrate_001()
BEGIN
    DECLARE colonna_presente INT DEFAULT 0;
    DECLARE check_presente INT DEFAULT 0;
    DECLARE definizione_valida INT DEFAULT 0;
    DECLARE colonna_aggiunta BOOLEAN DEFAULT FALSE;
    DECLARE check_aggiunto BOOLEAN DEFAULT FALSE;

    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC20: selezionare esplicitamente il database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prenotazione'
                     AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC20: tabella prenotazione InnoDB mancante';
    END IF;

    SELECT COUNT(*) INTO colonna_presente FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prenotazione'
          AND COLUMN_NAME = 'indisponibilita_segnalata';
    IF colonna_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prenotazione'
              AND COLUMN_NAME = 'indisponibilita_segnalata'
              AND LOWER(COLUMN_TYPE) = 'tinyint(1)' AND IS_NULLABLE = 'NO'
              AND COLUMN_DEFAULT = '0' AND EXTRA = '';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC20: colonna incompatibile; richiesti TINYINT(1) NOT NULL DEFAULT 0';
        END IF;
    END IF;

    SELECT COUNT(*) INTO check_presente FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'prenotazione'
          AND CONSTRAINT_NAME = 'chk_prenotazione_indisponibilita';
    IF check_presente > 0 THEN
        SELECT COUNT(*) INTO definizione_valida
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'prenotazione'
          AND tc.CONSTRAINT_NAME = 'chk_prenotazione_indisponibilita'
          AND tc.CONSTRAINT_TYPE = 'CHECK' AND tc.ENFORCED = 'YES'
          AND REGEXP_REPLACE(LOWER(cc.CHECK_CLAUSE), '[[:space:]`()]+', '')
              = 'indisponibilita_segnalatain0,1';
        IF definizione_valida <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC20: CHECK incompatibile; richiesti IN (0,1) ed ENFORCED';
        END IF;
    ELSEIF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE()
                     AND CONSTRAINT_NAME = 'chk_prenotazione_indisponibilita') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UC20: nome CHECK gia utilizzato da un altro oggetto';
    END IF;

    -- Tutte le verifiche sugli oggetti esistenti precedono qualsiasi ALTER.
    IF colonna_presente = 0 THEN
        ALTER TABLE prenotazione ADD COLUMN indisponibilita_segnalata BOOLEAN NOT NULL DEFAULT FALSE;
        SET colonna_aggiunta = TRUE;
    END IF;
    IF check_presente = 0 THEN
        ALTER TABLE prenotazione ADD CONSTRAINT chk_prenotazione_indisponibilita
            CHECK (indisponibilita_segnalata IN (0,1));
        SET check_aggiunto = TRUE;
    END IF;
    SELECT colonna_aggiunta, check_aggiunto;
END$$
DELIMITER ;
CALL medbook_uc20_migrate_001();
DROP PROCEDURE medbook_uc20_migrate_001;
