package com.example.lancerpick.admin.repository;

import com.example.lancerpick.admin.entity.AdminActionLog;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {
  Optional<AdminActionLog> findByAdminIdAndRequestKey(long adminId, String requestKey);

  /**
   * 같은 대상(대상 종류·ID)의 진행 중 요청. V14부터 진행 중 UNIQUE가 대상 기준이라 명령이 달라도(Batch 실행의 일반 재시작과 정리 후 재시작)
   * 한 건만 있다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select a from AdminActionLog a where a.targetType=:targetType and a.targetId=:targetId"
          + " and a.status=com.example.lancerpick.admin.entity.AdminActionStatus.REQUESTED")
  Optional<AdminActionLog> findPendingForUpdate(
      @Param("targetType") String targetType, @Param("targetId") long targetId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AdminActionLog a where a.id=:id")
  Optional<AdminActionLog> findByIdForUpdate(@Param("id") long id);
}
