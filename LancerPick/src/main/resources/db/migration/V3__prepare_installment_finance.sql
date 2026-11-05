-- Expansion only. Existing orders/payments stay LEGACY_V1/FULL.
-- Contract creation and installment HTTP commands are not enabled by this migration.
ALTER TABLE orders
    ADD COLUMN policy_version VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'LEGACY_V1',
    ADD UNIQUE KEY uk_orders_id_policy (id, policy_version),
    ADD CONSTRAINT ck_orders_policy CHECK (policy_version IN ('LEGACY_V1', 'CONTRACT_V2')),
    ADD CONSTRAINT ck_orders_v2_price CHECK (policy_version <> 'CONTRACT_V2' OR price_amount >= 10000);

ALTER TABLE payments
    ADD COLUMN order_policy_version VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'LEGACY_V1',
    ADD COLUMN installment_type VARCHAR(16) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'FULL',
    ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0;

UPDATE payments SET refunded_amount = amount WHERE status = 'REFUNDED';

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_order_policy FOREIGN KEY (order_id, order_policy_version)
        REFERENCES orders (id, policy_version),
    ADD CONSTRAINT ck_payments_installment_policy CHECK (
        (order_policy_version = 'LEGACY_V1' AND installment_type = 'FULL')
        OR (order_policy_version = 'CONTRACT_V2' AND installment_type IN ('DEPOSIT', 'BALANCE'))),
    ADD CONSTRAINT ck_payments_refunded_amount CHECK (
        (status = 'PAID' AND refunded_amount = 0)
        OR (status = 'REFUNDED' AND refunded_amount = amount));

-- Keep uk_payments_order and the existing full-refund CHECKs for now.
-- Replace them only together with contract-state checks and installment services.
