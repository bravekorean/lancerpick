package com.example.lancerpick.admin.service;

import com.example.lancerpick.admin.entity.AdminAction;
import com.example.lancerpick.admin.service.AdminActionAuditService.Outcome;
import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.wallet.service.TopupRecoveryJobService;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 후보 재검사 트랜잭션을 끝낸 다음 Spring Batch의 재시작/중복 실행 검사를 사용한다.
 *
 * <p>중단 실행 정리({@link #recoverAndRestart})는 실행 버전을 기록한 뒤 감사 claim 트랜잭션에서 후보를 재검사하고, 버전이 그대로이며 여전히
 * 중단 상태일 때만 버전 펜스를 걸고 recover한 뒤 restart를 호출한다. 감사 이력의 대상별 진행 중 UNIQUE가 같은 실행에 대한 동시 정리 요청을 막는다.
 */
@Service
@Profile("!demo")
@Transactional(propagation = Propagation.NEVER)
public class AdminBatchRestartService {
  private final AdminActionExecutionGuard guard;
  private final AdminFinanceQueryService finance;
  private final ObjectProvider<TopupRecoveryJobService> jobs;
  private final TransactionTemplate transaction;

  public AdminBatchRestartService(
      AdminActionExecutionGuard guard,
      AdminFinanceQueryService finance,
      ObjectProvider<TopupRecoveryJobService> jobs,
      PlatformTransactionManager manager) {
    this.guard = guard;
    this.finance = finance;
    this.jobs = jobs;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Outcome restart(long adminId, long logId, long executionId) throws Exception {
    transaction.executeWithoutResult(ignored -> {
      guard.require(adminId, logId, AdminAction.TOPUP_RECOVERY_RESTART, executionId);
      if (!finance.recoveryJob(adminId, executionId).restart().eligible())
        throw new BusinessException(
            HttpStatus.CONFLICT, "ADMIN_ACTION_NOT_ELIGIBLE", "현재 재시작할 수 없는 작업입니다.");
    });
    var service = jobs.getIfAvailable();
    if (service == null) return Outcome.BATCH_RESTART_REJECTED;
    service.restart(executionId);
    // Job 종료/지급 완료가 아닌 재시작 호출 수락 결과다. 최신 실행 상태는 기존 조회 API로 확인한다.
    return Outcome.BATCH_RESTART_ACCEPTED;
  }

  public Outcome recoverAndRestart(long adminId, long logId, long executionId) throws Exception {
    var service = jobs.getIfAvailable();
    if (service == null) return Outcome.BATCH_RECOVER_REJECTED;
    // 재검사 직전의 실행 버전을 기록한다. 이후 한 번이라도 갱신되면(재검사 중 포함) recover 단계가 거절한다.
    // Job 서비스는 트랜잭션 밖 전용(NEVER)이라 claim 트랜잭션 전에 읽는다.
    var checked = service.snapshot(executionId);
    transaction.executeWithoutResult(ignored -> {
      guard.require(adminId, logId, AdminAction.TOPUP_RECOVERY_RECOVER_RESTART, executionId);
      if (!finance.recoveryJob(adminId, executionId).recoverRestart().eligible())
        throw new BusinessException(
            HttpStatus.CONFLICT, "ADMIN_ACTION_NOT_ELIGIBLE", "현재 정리할 수 있는 중단 실행이 아닙니다.");
    });
    try {
      service.recoverInterrupted(executionId, checked);
    } catch (JobRestartException notInterrupted) {
      // 재검사에서 중단 실행이 아니었다. 메타데이터는 바뀌지 않았다.
      return Outcome.BATCH_RECOVER_REJECTED;
    }
    try {
      service.restart(executionId);
    } catch (JobExecutionAlreadyRunningException
        | JobInstanceAlreadyCompleteException
        | JobRestartException
        | InvalidJobParametersException rejected) {
      // 이미 FAILED로 정리됐다. 일반 재시작 명령으로 다시 시도할 수 있다.
      return Outcome.BATCH_RECOVERED_RESTART_REJECTED;
    }
    return Outcome.BATCH_RECOVER_RESTART_ACCEPTED;
  }
}
