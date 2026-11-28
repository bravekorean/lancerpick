package com.example.lancerpick.admin.entity;

public enum AdminAction {
  TOPUP_RECHECK("WALLET_TOPUP"),
  TOPUP_RECOVERY_RESTART("BATCH_JOB_EXECUTION"),
  /** 프로세스 종료로 실행 중 상태에 남은 복구 실행을 recover(FAILED 정리)한 뒤 같은 JobInstance로 재시작한다. */
  TOPUP_RECOVERY_RECOVER_RESTART("BATCH_JOB_EXECUTION"),
  CONTRACT_PDF_REGENERATE("CONTRACT");

  private final String targetType;

  AdminAction(String targetType) {
    this.targetType = targetType;
  }

  public String targetType() {
    return targetType;
  }
}
