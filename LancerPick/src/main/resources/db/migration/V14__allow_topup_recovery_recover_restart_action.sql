-- K6 관리자 작업에 "프로세스 종료로 실행 중 상태에 남은 충전 복구 실행 정리 후 재시작"을 추가한다.
-- 기존 행·열·인덱스는 바꾸지 않고 명령·대상 종류 쌍 CHECK만 넓힌다. 대상은 기존 재시작과 같은 Batch 실행 ID다.
ALTER TABLE admin_action_logs
    DROP CHECK ck_admin_action_pair;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT ck_admin_action_pair CHECK (
      (action = 'TOPUP_RECHECK' AND target_type = 'WALLET_TOPUP' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RECOVER_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'CONTRACT_PDF_REGENERATE' AND target_type = 'CONTRACT' AND order_id IS NOT NULL));
