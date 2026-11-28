package com.example.lancerpick.wallet.batch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * 이 서버 프로세스 안에서 지금 실행 중인 충전 복구 실행 ID. Job 리스너로 시작 때 넣고 끝날 때(실패 포함) 뺀다.
 *
 * <p>Batch 메타데이터의 STARTED만으로는 "실제로 도는 중"과 "프로세스가 죽어 남은 기록"을 구분할 수 없다. 이 목록에 있으면 이 서버에서
 * 아직 살아 있는 실행이므로 중단 실행 정리 대상에서 뺀다. 다른 서버 프로세스의 실행은 알 수 없으므로 마지막 갱신 시각 기준을 함께 쓴다.
 */
public class TopupRecoveryLocalExecutions implements JobExecutionListener {
  private final Set<Long> running = ConcurrentHashMap.newKeySet();

  @Override
  public void beforeJob(JobExecution execution) {
    running.add(execution.getId());
  }

  @Override
  public void afterJob(JobExecution execution) {
    running.remove(execution.getId());
  }

  public boolean isRunningHere(long executionId) {
    return running.contains(executionId);
  }
}
