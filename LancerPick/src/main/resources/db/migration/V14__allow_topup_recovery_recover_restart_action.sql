-- K6 관리자 작업에 "프로세스 종료로 실행 중 상태에 남은 충전 복구 실행 정리 후 재시작"을 추가한다.
-- 대상은 기존 재시작과 같은 Batch 실행 ID다. 기존 행의 값은 바꾸지 않는다.

-- 1) 명령·대상 종류 쌍 CHECK에 새 명령을 추가한다.
ALTER TABLE admin_action_logs
    DROP CHECK ck_admin_action_pair;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT ck_admin_action_pair CHECK (
      (action = 'TOPUP_RECHECK' AND target_type = 'WALLET_TOPUP' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RECOVER_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'CONTRACT_PDF_REGENERATE' AND target_type = 'CONTRACT' AND order_id IS NOT NULL));

-- 2) 진행 중 UNIQUE를 명령이 아닌 대상 기준으로 바꾼다. 같은 Batch 실행에 일반 재시작과 정리 후 재시작이 동시에
--    진행되면 Spring Batch의 재시작 중복 검사가 동시 요청을 막지 못해 새 실행이 둘 생긴다(통합 테스트로 재현).
--    다른 명령은 명령과 대상 종류가 1:1이므로 진행 중 판정이 바뀌지 않는다. 완료된 행은 NULL이라 기존 행과 충돌하지 않는다.
--    진행 중(REQUESTED) 행 두 개가 같은 대상에 이미 있으면 이 단계가 실패한다(기존 V13 UNIQUE상 명령이 달라야 가능, 현재 없음).
ALTER TABLE admin_action_logs
    DROP INDEX uk_admin_action_in_progress;

ALTER TABLE admin_action_logs
    MODIFY COLUMN in_progress_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (
        CASE WHEN status = 'REQUESTED' THEN CONCAT(target_type, ':', target_id) END
      ) STORED;

ALTER TABLE admin_action_logs
    ADD UNIQUE KEY uk_admin_action_in_progress (in_progress_key);
