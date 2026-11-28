package com.example.lancerpick.wallet.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Spring Batch가 소유한 BATCH_JOB_EXECUTION 행의 버전 펜스 전용 매핑. 중단 실행 정리에서 조건부 VERSION 증가(JPQL 일괄 수정)에만
 * 쓰고, 읽기·저장은 Spring Batch API로 한다. 다른 컬럼은 매핑하지 않는다.
 */
@Entity
@Table(name = "`BATCH_JOB_EXECUTION`")
public class BatchJobExecutionFence {
  @Id
  @Column(name = "`JOB_EXECUTION_ID`", insertable = false, updatable = false)
  private Long id;

  @Column(name = "`VERSION`", insertable = false, updatable = false)
  private Long version;

  @Column(name = "`STATUS`", length = 10, insertable = false, updatable = false)
  private String status;

  protected BatchJobExecutionFence() {}
}
