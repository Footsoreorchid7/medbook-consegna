-- MedBook - Schema fisico MySQL consolidato
-- Target: MySQL 8.0+
-- Charset: utf8mb4

CREATE DATABASE IF NOT EXISTS medbook
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE medbook;

CREATE TABLE utente (
    id_utente INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    cognome VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    ruolo_gestionale VARCHAR(20) NULL,
    CONSTRAINT uq_utente_email UNIQUE (email),
    CONSTRAINT chk_utente_ruolo
        CHECK (ruolo_gestionale IS NULL OR ruolo_gestionale IN ('AMMINISTRATORE','OPERATORE_DESK'))
) ENGINE=InnoDB;

CREATE TABLE medico (
    id_utente INT UNSIGNED PRIMARY KEY,
    specializzazione VARCHAR(150) NOT NULL,
    numero_iscrizione_ordine VARCHAR(50) NOT NULL,
    stato VARCHAR(20) NOT NULL DEFAULT 'ATTIVO',
    CONSTRAINT uq_medico_numero_ordine UNIQUE (numero_iscrizione_ordine),
    CONSTRAINT chk_medico_stato
        CHECK (stato IN ('ATTIVO','INATTIVO')),
    CONSTRAINT fk_medico_utente
        FOREIGN KEY (id_utente) REFERENCES utente(id_utente)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE paziente (
    id_utente INT UNSIGNED PRIMARY KEY,
    codice_fiscale CHAR(16) NOT NULL,
    data_nascita DATE NOT NULL,
    telefono VARCHAR(20) NULL,
    CONSTRAINT uq_paziente_codice_fiscale UNIQUE (codice_fiscale),
    CONSTRAINT fk_paziente_utente
        FOREIGN KEY (id_utente) REFERENCES utente(id_utente)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE prestazione (
    id_prestazione INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    descrizione TEXT NULL,
    durata SMALLINT UNSIGNED NOT NULL,
    prezzo DECIMAL(10,2) NOT NULL,
    CONSTRAINT chk_prestazione_durata CHECK (durata BETWEEN 1 AND 60),
    CONSTRAINT chk_prestazione_prezzo CHECK (prezzo >= 0)
) ENGINE=InnoDB;

CREATE TABLE medico_prestazione (
    id_medico INT UNSIGNED NOT NULL,
    id_prestazione INT UNSIGNED NOT NULL,
    PRIMARY KEY (id_medico, id_prestazione),
    CONSTRAINT fk_mp_medico
        FOREIGN KEY (id_medico) REFERENCES medico(id_utente)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_mp_prestazione
        FOREIGN KEY (id_prestazione) REFERENCES prestazione(id_prestazione)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE stanza (
    id_stanza INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    descrizione VARCHAR(255) NULL,
    CONSTRAINT uq_stanza_nome UNIQUE (nome)
) ENGINE=InnoDB;

CREATE TABLE disponibilita (
    id_disponibilita INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    data_ora_inizio DATETIME NOT NULL,
    data_ora_fine DATETIME NOT NULL,
    id_medico INT UNSIGNED NOT NULL,
    id_prestazione INT UNSIGNED NULL,
    id_stanza INT UNSIGNED NULL,
    CONSTRAINT chk_disponibilita_intervallo CHECK (data_ora_fine > data_ora_inizio),
    CONSTRAINT fk_disponibilita_medico
        FOREIGN KEY (id_medico) REFERENCES medico(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_disponibilita_medico_prestazione
        FOREIGN KEY (id_medico, id_prestazione)
        REFERENCES medico_prestazione(id_medico, id_prestazione)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_disponibilita_stanza
        FOREIGN KEY (id_stanza) REFERENCES stanza(id_stanza)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE prenotazione (
    id_prenotazione INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_paziente INT UNSIGNED NOT NULL,
    id_disponibilita INT UNSIGNED NOT NULL,
    gestita_da INT UNSIGNED NULL,
    id_medico_effettivo INT UNSIGNED NULL,
    stato VARCHAR(20) NOT NULL DEFAULT 'RICHIESTA',
    indisponibilita_segnalata BOOLEAN NOT NULL DEFAULT FALSE,
    data_ora_richiesta DATETIME NOT NULL,
    data_ora_gestione DATETIME NULL,
    prezzo DECIMAL(10,2) NOT NULL,
    CONSTRAINT chk_prenotazione_stato
        CHECK (stato IN ('RICHIESTA','CONFERMATA','RIFIUTATA','ANNULLATA',
                         'EFFETTUATA','PAZIENTE_ASSENTE','NON_EROGATA')),
    CONSTRAINT chk_prenotazione_indisponibilita
        CHECK (indisponibilita_segnalata IN (0,1)),
    CONSTRAINT chk_prenotazione_prezzo CHECK (prezzo >= 0),
    CONSTRAINT chk_prenotazione_gestione
        CHECK ((gestita_da IS NULL AND data_ora_gestione IS NULL)
            OR (gestita_da IS NOT NULL AND data_ora_gestione IS NOT NULL)),
    CONSTRAINT fk_prenotazione_paziente
        FOREIGN KEY (id_paziente) REFERENCES paziente(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prenotazione_disponibilita
        FOREIGN KEY (id_disponibilita) REFERENCES disponibilita(id_disponibilita)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prenotazione_gestita_da
        FOREIGN KEY (gestita_da) REFERENCES utente(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prenotazione_medico_effettivo
        FOREIGN KEY (id_medico_effettivo) REFERENCES medico(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE pagamento (
    id_pagamento INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_prenotazione INT UNSIGNED NOT NULL,
    registrato_da INT UNSIGNED NULL,
    modalita VARCHAR(20) NOT NULL,
    importo DECIMAL(10,2) NOT NULL,
    stato VARCHAR(20) NOT NULL DEFAULT 'IN_ATTESA',
    provider_payment_id VARCHAR(255) NULL,
    creato_il DATETIME NOT NULL,
    pagato_il DATETIME NULL,
    CONSTRAINT chk_pagamento_modalita
        CHECK (modalita IN ('ONLINE','DESK_POS','DESK_CONTANTI')),
    CONSTRAINT chk_pagamento_stato
        CHECK (stato IN ('IN_ATTESA','PAGATO','FALLITO')),
    CONSTRAINT chk_pagamento_importo CHECK (importo > 0),
    CONSTRAINT chk_pagamento_registrante
        CHECK ((modalita = 'ONLINE' AND registrato_da IS NULL)
            OR (modalita IN ('DESK_POS','DESK_CONTANTI') AND registrato_da IS NOT NULL)),
    CONSTRAINT chk_pagamento_data
        CHECK ((stato = 'PAGATO' AND pagato_il IS NOT NULL)
            OR (stato <> 'PAGATO' AND pagato_il IS NULL)),
    CONSTRAINT fk_pagamento_prenotazione
        FOREIGN KEY (id_prenotazione) REFERENCES prenotazione(id_prenotazione)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pagamento_registrato_da
        FOREIGN KEY (registrato_da) REFERENCES utente(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE referto (
    id_referto INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_prenotazione INT UNSIGNED NOT NULL,
    id_medico_caricamento INT UNSIGNED NOT NULL,
    nome_file VARCHAR(255) NOT NULL,
    percorso_file VARCHAR(500) NOT NULL,
    caricato_il DATETIME NOT NULL,
    CONSTRAINT uq_referto_prenotazione UNIQUE (id_prenotazione),
    CONSTRAINT fk_referto_prenotazione
        FOREIGN KEY (id_prenotazione) REFERENCES prenotazione(id_prenotazione)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_referto_medico
        FOREIGN KEY (id_medico_caricamento) REFERENCES medico(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE rimborso (
    id_rimborso INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_pagamento INT UNSIGNED NOT NULL,
    verificato_da INT UNSIGNED NULL,
    stato VARCHAR(20) NOT NULL DEFAULT 'RICHIESTO',
    importo DECIMAL(10,2) NOT NULL,
    richiesto_il DATETIME NOT NULL,
    verificato_il DATETIME NULL,
    motivazione VARCHAR(500) NOT NULL,
    provider_refund_id VARCHAR(255) NULL,
    rimborsato_il DATETIME NULL,
    CONSTRAINT uq_rimborso_pagamento UNIQUE (id_pagamento),
    CONSTRAINT chk_rimborso_stato
        CHECK (stato IN ('RICHIESTO','APPROVATO','RESPINTO',
                         'IN_ELABORAZIONE','RIMBORSATO','FALLITO')),
    CONSTRAINT chk_rimborso_importo CHECK (importo > 0),
    CONSTRAINT chk_rimborso_verifica
        CHECK ((verificato_da IS NULL AND verificato_il IS NULL)
            OR (verificato_da IS NOT NULL AND verificato_il IS NOT NULL)),
    CONSTRAINT chk_rimborso_data
        CHECK ((stato = 'RIMBORSATO' AND rimborsato_il IS NOT NULL)
            OR (stato <> 'RIMBORSATO' AND rimborsato_il IS NULL)),
    CONSTRAINT fk_rimborso_pagamento
        FOREIGN KEY (id_pagamento) REFERENCES pagamento(id_pagamento)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_rimborso_verificato_da
        FOREIGN KEY (verificato_da) REFERENCES utente(id_utente)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE INDEX idx_disponibilita_medico_periodo
    ON disponibilita (id_medico, data_ora_inizio, data_ora_fine);

CREATE INDEX idx_disponibilita_stanza_periodo
    ON disponibilita (id_stanza, data_ora_inizio, data_ora_fine);

CREATE INDEX idx_prenotazione_disponibilita_stato
    ON prenotazione (id_disponibilita, stato);

CREATE INDEX idx_pagamento_prenotazione_stato
    ON pagamento (id_prenotazione, stato);

-- Regole intenzionalmente demandate al service Java:
-- 1. Generazione di slot esatti di 60 minuti.
-- 2. Prenotabilità solo se id_prestazione e id_stanza sono valorizzati.
-- 3. Assenza di sovrapposizioni per medico e, se valorizzata, stanza.
-- 4. Una sola prenotazione occupante per slot, con locking transazionale.
-- 5. Corrette transizioni degli stati di prenotazione/pagamento/rimborso.
-- 6. Un solo pagamento PAGATO per prenotazione.
-- 7. Ruoli autorizzati alle operazioni di Segreteria.
-- 8. Medico effettivo abilitato alla prestazione.
-- 9. Regole di ammissibilità del rimborso e importo totale.
