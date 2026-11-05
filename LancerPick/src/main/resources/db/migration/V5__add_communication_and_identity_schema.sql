-- Remaining shared schema; no new API, broker, email or batch server is enabled.

ALTER TABLE members ADD email_verified_at DATETIME(6) NULL,
 ADD auth_version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_members_auth_version CHECK (auth_version >= 0);
ALTER TABLE cancellation_requests ADD UNIQUE KEY uk_cancellation_id_order (id,order_id);
ALTER TABLE deliveries ADD UNIQUE KEY uk_deliveries_id_order (id,order_id);
ALTER TABLE wallet_topups ADD recovery_attempt_count INT NOT NULL DEFAULT 0,
 ADD next_recovery_at DATETIME(6) NULL, ADD recovery_lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD recovery_lease_until DATETIME(6) NULL,
 ADD KEY ix_topup_recovery (status,next_recovery_at,id),
 ADD CONSTRAINT ck_topup_recovery_count CHECK (recovery_attempt_count>=0),
 ADD CONSTRAINT ck_topup_recovery_lease CHECK ((recovery_lease_token IS NULL AND recovery_lease_until IS NULL) OR (recovery_lease_token IS NOT NULL AND recovery_lease_until IS NOT NULL));


CREATE TABLE order_chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_chat_sequence(order_id,sequence),
    UNIQUE KEY uk_chat_request(order_id,sender_id,client_message_id),
    CONSTRAINT fk_chat_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_chat_sender FOREIGN KEY (sender_id) REFERENCES members(id),
    CONSTRAINT ck_chat_sequence CHECK (sequence>0),
    CONSTRAINT ck_chat_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 2000 AND CHAR_LENGTH(TRIM(content))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(60) NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_owner CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_outbox_event(event_id),
    KEY ix_outbox_due(status,next_attempt_at,id),
    CONSTRAINT ck_outbox_state CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_outbox_numbers CHECK (attempt_count>=0 AND schema_version>=1),
    CONSTRAINT ck_outbox_lease CHECK ((status='PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_outbox_complete CHECK ((status='COMPLETED' AND processed_at IS NOT NULL) OR (status<>'COMPLETED' AND processed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(60) NOT NULL,
    order_id BIGINT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_notification_event(recipient_id,event_id,type),
    KEY ix_notification_list(recipient_id,created_at DESC,id DESC),
    KEY ix_notification_unread(recipient_id,read_at,id),
    CONSTRAINT fk_notification_member FOREIGN KEY (recipient_id) REFERENCES members(id),
    CONSTRAINT fk_notification_event FOREIGN KEY (event_id) REFERENCES outbox_events(event_id),
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_notification_read CHECK (read_at IS NULL OR read_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE account_challenges (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    purpose VARCHAR(24) NOT NULL,
    member_id BIGINT NULL,
    email VARCHAR(254) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    credential_version BIGINT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    delivery_status VARCHAR(16) NOT NULL,
    last_error_code VARCHAR(100) NULL,
    UNIQUE KEY uk_challenge_hash(token_hash),
    KEY ix_challenge_email(purpose,email,issued_at),
    KEY ix_challenge_expiry(expires_at,id),
    CONSTRAINT fk_challenge_member FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT ck_challenge_purpose CHECK ((purpose='SIGNUP_EMAIL' AND member_id IS NULL AND credential_version IS NULL) OR (purpose IN ('EXISTING_EMAIL','PASSWORD_RESET') AND member_id IS NOT NULL AND credential_version IS NOT NULL AND credential_version>=0)),
    CONSTRAINT ck_challenge_time CHECK (expires_at>issued_at AND (verified_at IS NULL OR (verified_at>=issued_at AND verified_at<expires_at)) AND (consumed_at IS NULL OR (consumed_at>=issued_at AND consumed_at<expires_at))),
    CONSTRAINT ck_challenge_delivery CHECK (delivery_status IN ('PENDING','SENT','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_request_limits (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(30) NOT NULL,
    subject_hmac BINARY(32) NOT NULL,
    window_start DATETIME(6) NOT NULL,
    request_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_auth_limit(scope,subject_hmac,window_start),
    KEY ix_auth_limit_expiry(expires_at,id),
    CONSTRAINT ck_auth_limit_count CHECK (request_count>=0),
    CONSTRAINT ck_auth_limit_time CHECK (expires_at>window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_inquiries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requester_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    cancellation_request_id BIGINT NULL,
    type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    subject VARCHAR(150) NOT NULL,
    assigned_admin_id BIGINT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_inquiry_id_order(id,order_id),
    KEY ix_inquiry_requester(requester_id,created_at,id),
    KEY ix_inquiry_queue(status,created_at,id),
    CONSTRAINT fk_inquiry_member FOREIGN KEY (requester_id) REFERENCES members(id),
    CONSTRAINT fk_inquiry_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_inquiry_cancel FOREIGN KEY (cancellation_request_id,order_id) REFERENCES cancellation_requests(id,order_id),
    CONSTRAINT fk_inquiry_admin FOREIGN KEY (assigned_admin_id) REFERENCES members(id),
    CONSTRAINT ck_inquiry_type CHECK ((type='CANCELLATION_REVIEW' AND order_id IS NOT NULL AND cancellation_request_id IS NOT NULL) OR (type='GENERAL' AND cancellation_request_id IS NULL)),
    CONSTRAINT ck_inquiry_status CHECK ((status IN ('OPEN','IN_REVIEW') AND resolved_at IS NULL) OR (status='CLOSED' AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_inquiry_time CHECK (updated_at>=created_at AND (resolved_at IS NULL OR (resolved_at>=created_at AND updated_at>=resolved_at)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    inquiry_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_support_message(inquiry_id,sender_id,client_message_id),
    KEY ix_support_message_list(inquiry_id,id),
    CONSTRAINT fk_support_message_inquiry FOREIGN KEY (inquiry_id) REFERENCES support_inquiries(id),
    CONSTRAINT fk_support_message_sender FOREIGN KEY (sender_id) REFERENCES members(id),
    CONSTRAINT ck_support_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 5000 AND CHAR_LENGTH(TRIM(content))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
