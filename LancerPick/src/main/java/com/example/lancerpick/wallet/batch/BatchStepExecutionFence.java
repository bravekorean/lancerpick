package com.example.lancerpick.wallet.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Spring Batch가 소유한 BATCH_STEP_EXECUTION 행의 버전 펜스 전용 매핑. {@link BatchJobExecutionFence}와 같은 제한을 따른다. */
@Entity
@Table(name = "`BATCH_STEP_EXECUTION`")
public class BatchStepExecutionFence {
  @Id
  @Column(name = "`STEP_EXECUTION_ID`", insertable = false, updatable = false)
  private Long id;

  @Column(name = "`JOB_EXECUTION_ID`", insertable = false, updatable = false)
  private Long jobExecutionId;

  @Column(name = "`VERSION`", insertable = false, updatable = false)
  private Long version;

  @Column(name = "`STATUS`", length = 10, insertable = false, updatable = false)
  private String status;

  protected BatchStepExecutionFence() {}
}
