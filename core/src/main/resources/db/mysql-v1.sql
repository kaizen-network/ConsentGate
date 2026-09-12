-- Install once in an empty, dedicated database using a setup account.
-- MySQL/MariaDB DDL is not rolled back as one transaction. Back up before upgrades.
CREATE TABLE cg_schema_history (version INTEGER PRIMARY KEY, applied_at VARCHAR(40) NOT NULL) ENGINE=InnoDB;
CREATE TABLE cg_player_locks (player_uuid VARBINARY(36) NOT NULL, scope VARBINARY(64) NOT NULL, PRIMARY KEY(player_uuid,scope)) ENGINE=InnoDB;
CREATE TABLE cg_document_revisions (
    scope VARBINARY(64) NOT NULL, document_id VARBINARY(64) NOT NULL, version VARBINARY(256) NOT NULL,
    locale VARBINARY(64) NOT NULL, content_hash VARBINARY(64) NOT NULL, title VARCHAR(128) NOT NULL,
    content_snapshot MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(scope,document_id,version,locale)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE cg_acceptance_events (
    event_id VARBINARY(101) PRIMARY KEY, player_uuid VARBINARY(36) NOT NULL, scope VARBINARY(64) NOT NULL,
    document_id VARBINARY(64) NOT NULL, version VARBINARY(256), locale VARBINARY(64), content_hash VARBINARY(64),
    decision VARCHAR(9) NOT NULL, decided_at VARCHAR(40) NOT NULL, method VARBINARY(32) NOT NULL,
    CHECK(decision IN ('granted','withdrawn')),
    CHECK(decision='withdrawn' OR (version IS NOT NULL AND locale IS NOT NULL AND content_hash IS NOT NULL)),
    FOREIGN KEY(scope,document_id,version,locale) REFERENCES cg_document_revisions(scope,document_id,version,locale),
    INDEX cg_acceptance_events_player(player_uuid,scope,decided_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE cg_acceptance_state (
    player_uuid VARBINARY(36) NOT NULL, scope VARBINARY(64) NOT NULL, document_id VARBINARY(64) NOT NULL,
    version VARBINARY(256), locale VARBINARY(64), content_hash VARBINARY(64), decision VARCHAR(9) NOT NULL,
    decided_at VARCHAR(40) NOT NULL, event_id VARBINARY(101) NOT NULL,
    PRIMARY KEY(player_uuid,scope,document_id), FOREIGN KEY(event_id) REFERENCES cg_acceptance_events(event_id),
    CHECK(decision IN ('granted','withdrawn')),
    CHECK(decision='withdrawn' OR (version IS NOT NULL AND locale IS NOT NULL AND content_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE cg_audit_events (
    request_id VARBINARY(36) PRIMARY KEY, player_uuid VARBINARY(36) NOT NULL, scope VARBINARY(64) NOT NULL,
    action VARBINARY(32) NOT NULL, payload MEDIUMTEXT NOT NULL, decided_at VARCHAR(40) NOT NULL, method VARBINARY(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO cg_schema_history(version,applied_at) VALUES(1,UTC_TIMESTAMP(6));
