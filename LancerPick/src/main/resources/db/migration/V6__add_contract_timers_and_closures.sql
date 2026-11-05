-- State/timer/closure storage only. CONTRACT_V2 API remains disabled.
-- Do not release payment uniqueness/full-refund constraints before installment services.
ALTER TABLE orders ADD inspection_approved_at DATETIME(6) NULL,
 ADD terminated_at DATETIME(6) NULL, ADD chat_sequence BIGINT NOT NULL DEFAULT 0,
 DROP CHECK ck_orders_status, DROP CHECK ck_orders_acceptance,
 DROP CHECK ck_orders_paid_status, DROP CHECK ck_orders_unpaid_status;
ALTER TABLE orders
 ADD CONSTRAINT ck_orders_status CHECK (status IN ('REQUESTED','AWAITING_PAYMENT','IN_PROGRESS','AWAITING_CONFIRMATION','REVISION_IN_PROGRESS','COMPLETED','REJECTED','CANCELLED','AWAITING_CONTRACT','AWAITING_BALANCE','TERMINATED')),
 ADD CONSTRAINT ck_orders_legacy_states CHECK (policy_version <> 'LEGACY_V1' OR status NOT IN ('AWAITING_CONTRACT','AWAITING_BALANCE','TERMINATED')),
 ADD CONSTRAINT ck_orders_acceptance CHECK (
  (status IN ('REQUESTED','REJECTED') AND accepted_at IS NULL)
  OR (status IN ('AWAITING_CONTRACT','AWAITING_PAYMENT','IN_PROGRESS','AWAITING_CONFIRMATION','REVISION_IN_PROGRESS','AWAITING_BALANCE','COMPLETED','TERMINATED') AND accepted_at IS NOT NULL)
  OR status='CANCELLED'),
 ADD CONSTRAINT ck_orders_paid_status CHECK (status NOT IN ('IN_PROGRESS','AWAITING_CONFIRMATION','REVISION_IN_PROGRESS','AWAITING_BALANCE','COMPLETED','TERMINATED') OR paid_at IS NOT NULL),
 ADD CONSTRAINT ck_orders_unpaid_status CHECK (status NOT IN ('REQUESTED','AWAITING_CONTRACT','AWAITING_PAYMENT','REJECTED') OR paid_at IS NULL),
 ADD CONSTRAINT ck_orders_terminated CHECK ((status='TERMINATED' AND terminated_at IS NOT NULL) OR (status<>'TERMINATED' AND terminated_at IS NULL)),
 ADD CONSTRAINT ck_orders_inspection CHECK ((inspection_approved_at IS NULL OR (paid_at IS NOT NULL AND inspection_approved_at >= paid_at AND updated_at >= inspection_approved_at)) AND (status<>'AWAITING_BALANCE' OR inspection_approved_at IS NOT NULL)),
 ADD CONSTRAINT ck_orders_terminated_time CHECK (terminated_at IS NULL OR (paid_at IS NOT NULL AND terminated_at >= paid_at AND updated_at >= terminated_at)),
 ADD CONSTRAINT ck_orders_chat_sequence CHECK (chat_sequence >= 0);

ALTER TABLE order_histories MODIFY actor_id BIGINT NULL,
 ADD actor_type VARCHAR(10) NOT NULL DEFAULT 'MEMBER', ADD source_event_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD details JSON NULL, ADD UNIQUE KEY uk_order_history_source (source_event_key),
 DROP CHECK ck_order_histories_action, DROP CHECK ck_order_histories_from, DROP CHECK ck_order_histories_to,
 ADD CONSTRAINT ck_order_history_actor CHECK ((actor_type='MEMBER' AND actor_id IS NOT NULL) OR (actor_type='SYSTEM' AND actor_id IS NULL)),
 ADD CONSTRAINT ck_order_histories_action CHECK (action IN ('CREATED','ACCEPTED','REJECTED','PAID','DELIVERED','REVISION_REQUESTED','CANCELLATION_REQUESTED','CANCELLATION_APPROVED','CANCELLATION_REJECTED','CANCELLATION_WITHDRAWN','CANCELLED','COMPLETED','CONTRACT_CREATED','CONTRACT_SIGNED','DEPOSIT_PAID','INSPECTION_APPROVED','BALANCE_PAID','TIMER_STARTED','TIMER_CANCELLED','TIMER_ADMIN_STOPPED','TERMINATED','DEADLINE_NOTIFIED')),
 ADD CONSTRAINT ck_order_histories_from CHECK (from_status IS NULL OR from_status IN ('REQUESTED','AWAITING_PAYMENT','IN_PROGRESS','AWAITING_CONFIRMATION','REVISION_IN_PROGRESS','COMPLETED','REJECTED','CANCELLED','AWAITING_CONTRACT','AWAITING_BALANCE','TERMINATED')),
 ADD CONSTRAINT ck_order_histories_to CHECK (to_status IN ('REQUESTED','AWAITING_PAYMENT','IN_PROGRESS','AWAITING_CONFIRMATION','REVISION_IN_PROGRESS','COMPLETED','REJECTED','CANCELLED','AWAITING_CONTRACT','AWAITING_BALANCE','TERMINATED'));

ALTER TABLE cancellation_requests ADD resolution_source VARCHAR(10) NULL;
UPDATE cancellation_requests SET resolution_source=CASE WHEN status='WITHDRAWN' THEN 'BUYER' ELSE 'SELLER' END WHERE status<>'PENDING';
ALTER TABLE cancellation_requests ADD CONSTRAINT ck_cancellation_source CHECK (
 (status='PENDING' AND resolution_source IS NULL) OR
 (status='WITHDRAWN' AND resolution_source IS NOT NULL AND resolution_source='BUYER') OR
 (status IN ('APPROVED','REJECTED') AND resolution_source IS NOT NULL AND resolution_source IN ('SELLER','ADMIN')));
ALTER TABLE settlements ADD settlement_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
 ADD CONSTRAINT ck_settlement_kind CHECK (settlement_type='NORMAL' OR (settlement_type='NO_RESPONSE_COMPENSATION' AND fee_rate_bps=100));

CREATE TABLE order_trade_terms (
    order_id BIGINT NOT NULL PRIMARY KEY,
    deposit_rate_bps INT NOT NULL,
    compensation_rate_bps INT NOT NULL,
    normal_fee_rate_bps INT NOT NULL,
    compensation_fee_rate_bps INT NOT NULL,
    review_wait_hours INT NOT NULL,
    response_window_hours INT NOT NULL,
    CONSTRAINT fk_trade_terms_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_trade_terms_v2 CHECK (deposit_rate_bps=5000 AND compensation_rate_bps=3000 AND normal_fee_rate_bps=1000 AND compensation_fee_rate_bps=100 AND review_wait_hours=48 AND response_window_hours=168)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE order_response_timers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    timer_type VARCHAR(24) NOT NULL,
    basis_at DATETIME(6) NOT NULL,
    eligible_at DATETIME(6) NOT NULL,
    started_by BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_slot INT NULL,
    ended_at DATETIME(6) NULL,
    ended_by BIGINT NULL,
    end_reason VARCHAR(24) NULL,
    admin_stop_reason VARCHAR(1000) NULL,
    UNIQUE KEY uk_timer_cycle(order_id,timer_type,delivery_id),
    UNIQUE KEY uk_timer_active(order_id,active_slot),
    UNIQUE KEY uk_timer_id_order(id,order_id),
    KEY ix_timer_due(status,expires_at,id),
    CONSTRAINT fk_timer_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_timer_delivery FOREIGN KEY (delivery_id,order_id) REFERENCES deliveries(id,order_id),
    CONSTRAINT fk_timer_starter FOREIGN KEY (started_by) REFERENCES members(id),
    CONSTRAINT fk_timer_ender FOREIGN KEY (ended_by) REFERENCES members(id),
    CONSTRAINT ck_timer_type CHECK (timer_type IN ('REVIEW_RESPONSE','BALANCE_PAYMENT')),
    CONSTRAINT ck_timer_window CHECK (eligible_at=TIMESTAMPADD(HOUR,48,basis_at) AND started_at>=eligible_at AND expires_at=TIMESTAMPADD(HOUR,168,started_at)),
    CONSTRAINT ck_timer_state CHECK ((status='ACTIVE' AND active_slot IS NOT NULL AND active_slot=1 AND ended_at IS NULL AND ended_by IS NULL AND end_reason IS NULL AND admin_stop_reason IS NULL) OR (status='EXPIRED' AND active_slot IS NULL AND ended_at IS NOT NULL AND ended_at>=expires_at AND ended_by IS NULL AND end_reason IS NOT NULL AND end_reason='TIMEOUT' AND admin_stop_reason IS NULL) OR (status='CANCELLED' AND active_slot IS NULL AND ended_at IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL AND end_reason IN ('BUYER_RESPONSE','PAYMENT','ADMIN_STOP','ORDER_CLOSED'))),
    CONSTRAINT ck_timer_end_time CHECK (ended_at IS NULL OR ended_at>=started_at),
    CONSTRAINT ck_timer_admin CHECK ((end_reason='ADMIN_STOP' AND admin_stop_reason IS NOT NULL AND CHAR_LENGTH(TRIM(admin_stop_reason)) BETWEEN 1 AND 1000) OR ((end_reason IS NULL OR end_reason<>'ADMIN_STOP') AND admin_stop_reason IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE order_closures (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    source_timer_id BIGINT NULL,
    source_cancellation_request_id BIGINT NULL,
    inquiry_id BIGINT NULL,
    actor_type VARCHAR(10) NOT NULL,
    actor_id BIGINT NULL,
    paid_amount BIGINT NOT NULL,
    buyer_refund_amount BIGINT NOT NULL,
    seller_gross_amount BIGINT NOT NULL,
    fee_amount BIGINT NOT NULL,
    seller_net_amount BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_closure_order(order_id),
    CONSTRAINT fk_closure_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_closure_timer FOREIGN KEY (source_timer_id,order_id) REFERENCES order_response_timers(id,order_id),
    CONSTRAINT fk_closure_cancellation FOREIGN KEY (source_cancellation_request_id,order_id) REFERENCES cancellation_requests(id,order_id),
    CONSTRAINT fk_closure_inquiry FOREIGN KEY (inquiry_id,order_id) REFERENCES support_inquiries(id,order_id),
    CONSTRAINT fk_closure_actor FOREIGN KEY (actor_id) REFERENCES members(id),
    CONSTRAINT ck_closure_sums CHECK (paid_amount BETWEEN 1 AND 1000000 AND buyer_refund_amount>=0 AND seller_gross_amount>=0 AND fee_amount>=0 AND seller_net_amount>=0 AND paid_amount=buyer_refund_amount+seller_gross_amount AND seller_gross_amount=fee_amount+seller_net_amount),
    CONSTRAINT ck_closure_kind CHECK ((reason='COMPLETED' AND actor_type='MEMBER' AND actor_id IS NOT NULL AND source_timer_id IS NULL AND source_cancellation_request_id IS NULL AND inquiry_id IS NULL AND buyer_refund_amount=0 AND fee_amount=FLOOR(seller_gross_amount*1000/10000)) OR (reason IN ('MUTUAL_CANCELLATION','ADMIN_CANCELLATION') AND actor_type='MEMBER' AND actor_id IS NOT NULL AND source_timer_id IS NULL AND source_cancellation_request_id IS NOT NULL AND buyer_refund_amount=paid_amount AND seller_gross_amount=0 AND (reason<>'ADMIN_CANCELLATION' OR inquiry_id IS NOT NULL)) OR (reason IN ('REVIEW_NO_RESPONSE','BALANCE_UNPAID') AND actor_type='SYSTEM' AND actor_id IS NULL AND source_timer_id IS NOT NULL AND source_cancellation_request_id IS NULL AND inquiry_id IS NULL AND seller_gross_amount=FLOOR(paid_amount*3000/10000) AND fee_amount=FLOOR(seller_gross_amount*100/10000)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
