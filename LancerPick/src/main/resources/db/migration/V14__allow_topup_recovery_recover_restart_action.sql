-- K6 관리자 작업에 "프로세스 종료로 실행 중 상태에 남은 충전 복구 실행 정리 후 재시작"을 추가한다.
-- 대상은 기존 재시작과 같은 Batch 실행 ID다. 기존 행의 저장 값은 바꾸지 않는다.
--
-- 한 ALTER TABLE 문으로 실행해 CHECK·진행 중 UNIQUE가 빠진 중간 상태가 다른 세션에 보이지 않게 한다
-- (여러 문으로 나누면 UNIQUE를 지운 뒤 다시 만들기 전까지 같은 대상의 중복 접수를 DB가 막지 못한다).
--
-- 1) 명령·대상 종류 쌍 CHECK에 새 명령을 추가한다(같은 이름으로 교체).
-- 2) 진행 중 UNIQUE를 명령이 아닌 대상 기준으로 바꾼다. 같은 Batch 실행에 일반 재시작과 정리 후 재시작이 동시에
--    진행되면 Spring Batch의 재시작 중복 검사가 동시 요청을 막지 못해 새 실행이 둘 생긴다(통합 테스트로 재현).
--    다른 명령은 명령과 대상 종류가 1:1이므로 진행 중 판정이 바뀌지 않는다. 완료된 행은 NULL이라 기존 행과 충돌하지 않는다.
--    진행 중(REQUESTED) 행 두 개가 같은 대상에 이미 있으면 실패한다(기존 V13 UNIQUE상 명령이 달라야 가능, 현재 없음).
--    생성 열 변경은 테이블 복사(COPY)로 실행되며 그동안 쓰기는 대기한다.
ALTER TABLE admin_action_logs
    DROP CHECK ck_admin_action_pair,
    ADD CONSTRAINT ck_admin_action_pair CHECK (
      (action = 'TOPUP_RECHECK' AND target_type = 'WALLET_TOPUP' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'TOPUP_RECOVERY_RECOVER_RESTART' AND target_type = 'BATCH_JOB_EXECUTION' AND order_id IS NULL) OR
      (action = 'CONTRACT_PDF_REGENERATE' AND target_type = 'CONTRACT' AND order_id IS NOT NULL)),
    DROP INDEX uk_admin_action_in_progress,
    MODIFY COLUMN in_progress_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (
        CASE WHEN status = 'REQUESTED' THEN CONCAT(target_type, ':', target_id) END
      ) STORED,
    ADD UNIQUE KEY uk_admin_action_in_progress (in_progress_key);
