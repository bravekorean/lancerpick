-- Contract persistence only; no CONTRACT_V2 order endpoint or payment activation.

CREATE TABLE order_contracts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    version INT NOT NULL,
    template_version VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot INT NULL,
    special_terms TEXT NOT NULL,
    canonical_content JSON NOT NULL,
    content_hash BINARY(32) NOT NULL,
    file_storage_key VARCHAR(255) NULL,
    file_backend VARCHAR(20) NULL,
    file_size BIGINT NULL,
    file_hash BINARY(32) NULL,
    generation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by BIGINT NOT NULL,
    signed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_contract_version(order_id,version),
    UNIQUE KEY uk_contract_current(order_id,active_slot),
    UNIQUE KEY uk_contract_generation(generation_key),
    UNIQUE KEY uk_contract_file(file_storage_key),
    CONSTRAINT fk_contract_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_contract_creator FOREIGN KEY (created_by) REFERENCES members(id),
    CONSTRAINT ck_contract_state CHECK (status IN ('DRAFT','GENERATING','READY','SIGNED','FAILED','SUPERSEDED')),
    CONSTRAINT ck_contract_current CHECK ((status='SUPERSEDED' AND active_slot IS NULL) OR (status<>'SUPERSEDED' AND active_slot IS NOT NULL AND active_slot=1)),
    CONSTRAINT ck_contract_version CHECK (version>=1),
    CONSTRAINT ck_contract_file CHECK (status NOT IN ('READY','SIGNED') OR (file_storage_key IS NOT NULL AND file_backend IS NOT NULL AND file_size IS NOT NULL AND file_size>0 AND file_hash IS NOT NULL)),
    CONSTRAINT ck_contract_signed CHECK ((status='SIGNED' AND signed_at IS NOT NULL) OR (status<>'SIGNED' AND signed_at IS NULL)),
    CONSTRAINT ck_contract_times CHECK (updated_at>=created_at AND (signed_at IS NULL OR (signed_at>=created_at AND updated_at>=signed_at)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE contract_signatures (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    party_role VARCHAR(10) NOT NULL,
    signed_content_hash BINARY(32) NOT NULL,
    signed_file_hash BINARY(32) NOT NULL,
    signed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_signature_role(contract_id,party_role),
    UNIQUE KEY uk_signature_member(contract_id,member_id),
    CONSTRAINT fk_signature_contract FOREIGN KEY (contract_id) REFERENCES order_contracts(id),
    CONSTRAINT fk_signature_member FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT ck_signature_role CHECK (party_role IN ('BUYER','SELLER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
