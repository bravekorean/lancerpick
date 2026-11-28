# LancerPick 문서 안내

기준일: **2026-11-10**, 정적 대조 소스: **main 3353e57**. 현재 규칙은 해당 주제 문서에서, 날짜·커밋별 검증과 대체된 설계는 change-history.md에서 읽는다. 기술 문서 8개와 별도로 자기소개서·포트폴리오 작성 기준을 관리한다.

회원 관리 후속: 관리자 회원 조회·제재 API·화면(#157)과 비밀번호 변경 권고 알림(#156)이 main에 반영됐다. [회원 제재 API·화면](features.md#members-sanctions), [현재 반영 상태](project-progress.md#current-status), [통합 검증](change-history.md#admin-member-main-integration)을 따른다. 원본 문서를 되살리지 않고 이 통합 구조에서 관리한다.

K6 관리자 재처리: [감사 DB·내부 연결 기반](features.md#admin-action-audit)과 [충전 재확인·Batch 재시작·PDF 생성 서비스 입구](features.md#admin-reprocessing)(#167), [관리자 실행/감사 목록 API·화면](features.md#admin-reprocessing-api)(#168)이 main에 반영됐다. 실제 토스·Batch·PDF 실행의 브라우저 확인은 [검증 상태](change-history.md#admin-reprocessing-api-history)와 구분한다.

## 어디부터 읽을지

| 목적 | 문서 |
| --- | --- |
| 서비스 목적·범위·기술 스택 | [project-overview.md](project-overview.md) |
| 현재 구현·남은 일·마일스톤·활성화 조건 | [project-progress.md](project-progress.md#current-status) |
| 담당·책임·초기 업무 비중·협업/리뷰 규칙 | [team-roles.md](team-roles.md) |
| 계층·인증·HTTP API·트랜잭션·실행 환경 | [architecture.md](architecture.md) |
| 도메인별 현재 규칙·API/입력·화면·오류 | [features.md](features.md) |
| 테이블·컬럼·관계·제약·인덱스·마이그레이션 | [database-design.md](database-design.md) |
| 왜 바뀌었는지·당시 검증·실패/보완·과거 계획 | [change-history.md](change-history.md) |
| 동시 결제·충전 복구 검증 결과와 원본 CSV | [금융 검증 결과 요약](change-history.md#finance-verification-summary) |
| 자기소개서·포트폴리오 작성·갱신 | [관리 기준과 작성 예시](interview-design-notes.md) · [사례별 검증 근거](#portfolio-evidence) |
| 목차·관리 원칙·원본 이동 경로 | [README.md](README.md) |

현재 구현이 궁금하면 [진행 현황](project-progress.md#current-status), 특정 기능을 수정하려면 [기능 주제별 목차](features.md#주제별-목차), 예전 테스트 수·커밋·설계 근거가 필요할 때만 [이력 목차](change-history.md#이력-목차)를 연다.

## 현재와 과거를 구분하는 원칙

- 현재 기능/설계 문서는 **주제 → 하위 기능 → 업무 규칙·API·처리 순서·화면·데이터** 순으로 읽는다. 원본 파일별로 같은 설명을 다시 나열하지 않는다.
- 구현/검증 날짜·브랜치·커밋·당시 미구현/병합 전 상태·대체된 계획은 변경 이력의 **주제·시점별 접기 섹션**에 둔다. 과거 문구가 현재 상태를 되돌리지 않는다.
- LEGACY_V1·CONTRACT_V2·CONTRACT_V3의 호환 규칙은 현재 명세에도 필요하다. 버전별로 표시하고 현재 신규 정책과 구분한다.
- 게이트 c0009f2 통과 후 #152가 V3 생성 기본값을 true로 바꿨다. V2는 false, test 프로필은 V3 false다. 과거 테스트 수를 합산하거나 현재 main 재검사 결과로 표현하지 않는다.
- 개인 MVP 35/35와 공통 완료 0/8은 2026-11-05 시트 기준이다. 전체 고도화 완료율로 확대하거나 합산하지 않는다.
- 문서끼리 충돌하면 코드·후속 합의·검증 시점을 근거로 [판단](change-history.md#conflicts)을 기록한다. 확인되지 않은 내용은 **확인 필요**라고 적는다.

<a id="portfolio-evidence"></a>

## 포트폴리오 작성 기준

2026-11-28 합의. LancerPick에서 내가 맡은 기능 중 사용자의 업무나 거래 결과에 영향을 주는 문제를 사례로 고른다. 자기소개서는 **문제·선택·검증 결과를 2~3문장**으로 압축한다. 포트폴리오는 처음 읽는 면접관이 **문제 인식 → 원인 파악 → 해결책과 대안 → 선택 이유와 비용 → 검증 결과**를 따라갈 수 있게 쓴다. 사례마다 팀의 기능 범위와 개인 기여 범위를 밝힌다.

성과는 해결하려는 문제에 맞춰 고른다. 중복 차감 방지는 결제·차감 횟수와 잔액, 배치 복구는 처리 상태와 재실행 결과, 정산 경합은 보관금과 원장의 최종 상태로 설명한다. 성능 개선은 필수 항목이 아니다. 지연이나 처리량이 실제 문제인 경우에만 측정 조건과 근거를 함께 사용한다.

### 현재 사례와 확인한 결과

| 사례 | 문제와 설계 선택 | 검증 결과와 남은 범위 |
| --- | --- | --- |
| 결제사 승인 응답 유실 | 토스가 승인했어도 응답이 끊기면 내부에서 성공 여부를 확정할 수 없다. 저장한 결제 식별자로 승인 기록을 조회하고 확인된 금액을 지갑과 원장에 반영한다. 주기 훑기만으로도 복구는 가능하지만, 현재 구현은 스케줄러에 실행 시점을 맡기고 Spring Batch에 실행 이력과 재시작 지점을 맡긴다. | 첫 건 지급 후 후보 조회에서 강제로 실패시켰다. 같은 JobInstance를 재시작했을 때 첫 건의 추가 조회·지급 없이 둘째 건을 복구했다. 토스 테스트 대역을 사용했다. [재시작 통합 테스트](../LancerPick/src/test/java/com/example/lancerpick/wallet/TopupRecoveryIntegrationTests.java#L561) · [복구 검증](change-history.md#topup-recovery-benchmark) |
| 같은 지갑의 동시 결제 | 주문별 잠금만으로는 서로 다른 주문이 같은 잔액을 동시에 쓰는 문제를 막기 어렵다. 지갑 행을 잠그고 잔액 확인과 차감을 순서대로 처리한다. 잔액을 조건부 갱신하는 방식도 후보지만 주문·보관금·원장과의 원자적 처리를 함께 검토해야 한다. | 같은 지갑으로 서로 다른 주문을 결제하면 한 건 성공, 한 건 잔액 부족이었고 잔액은 한 번만 차감됐다. 지갑 잠금이 대기 한도 안에 풀리면 결제가 이어지고, 한도를 넘으면 주문·잔액·보관금·원장이 모두 롤백되며 같은 요청의 재시도는 한 번만 차감된다. 다른 구매자 지갑의 결제는 대기 없이 끝났다. 처음에는 한도 초과가 MySQL 기본 50초 대기 뒤 HTTP 500으로 끝났고, 대기 한도를 5초로 줄여 재시도 가능한 409 `RESOURCE_BUSY`로 바꿨다([수정](change-history.md#lock-wait-resource-busy)). [공유 지갑 통합 테스트](../LancerPick/src/test/java/com/example/lancerpick/order/FullFundingTradeIntegrationTests.java#L337) · [잠금 대기 검증](change-history.md#wallet-lock-crash-recovery-check) |
| 취소 요청과 구매 확정의 경합 | 두 요청이 같은 주문을 동시에 바꾸면 취소 요청 접수와 판매자 지급이 충돌한다. 같은 주문의 변경을 순서대로 처리하고, 대기 중인 취소 요청이 있으면 확정과 지급을 보류한다. | 경합 테스트는 두 요청 중 하나만 성공하는지 확인한다. 취소 요청이 먼저 접수되면 보관금이 유지되고, 확정이 먼저 처리되면 판매자에게 지급된다. 구매 확정 이후 철회·분쟁 경로는 별도 정책이다. [경합 통합 테스트](../LancerPick/src/test/java/com/example/lancerpick/order/FullFundingTradeIntegrationTests.java#L523) |

충전 복구에서 Spring Batch를 쓰는 비용은 실행 메타데이터와 작업 상태를 관리해야 한다는 점이다. 현재는 시스템 스키마를 Flyway V9로 관리하고, Tasklet이 마지막 처리 ID를 체크포인트로 남긴다. 지급이 먼저 커밋되고 체크포인트가 저장되기 전에 멈출 수 있으므로, 다시 처리하더라도 충전 상태 확인과 원장의 유일 제약이 중복 지급을 막도록 했다. 실패로 기록된 실행의 재시작과 별개로, 프로세스가 체크포인트 저장 전에 멈춰 STARTED로 남은 실행도 확인했다. 그대로는 재시작되지 않고, `JobOperator.recover`로 FAILED 처리한 뒤 재시작하면 첫 건을 다시 지급하지 않고 둘째 건을 복구한다. 이 recover를 실행하는 관리자 경로는 아직 없고, 실제 JVM 강제 종료는 재현하지 않았다. [현재 복구 설계](features.md#finance-batch) · [중단 실행 검증](change-history.md#wallet-lock-crash-recovery-check)

### 후속 작업에서 지킬 근거 기준

- **확인된 문제, 적용한 변경, 실행해 확인한 결과, 후속 계획**을 따로 기록한다. 결과를 인용할 때는 테스트나 원본 데이터, 실행 환경과 범위를 연결한다. 이번 문서 수정은 기존 기록을 정리한 것이며 테스트를 새로 실행한 결과는 아니다.
- 대안은 당시 검토한 사실이 확인된 경우에만 당시 결정으로 쓴다. 면접 준비 중 비교한 대안은 사후 비교로 표시한다. 계획에만 있는 기능이나 검증은 완료형으로 쓰지 않는다.
- 기존 로컬 벤치마크는 당시 데이터와 실행 조건의 결과로 보존한다. 서비스 메서드 호출 시간, HTTP 응답시간, 운영 트래픽 결과는 각각 측정 범위를 적는다. [기존 측정 기록](change-history.md#finance-verification-summary)
- **100만 건 이상 더미 데이터 실험은 관련 기능 개선 후 진행할 계획**이다. 지금은 대상 테이블과 데이터 분포, 조회·결제 요청의 비율, 동시성 조건을 확정하지 않았다. 실험할 때 해당 조건과 변경 전후의 결과를 기록하고, 실행 전에는 대용량 성능 수치를 쓰지 않는다.

## 향후 문서 관리 원칙

1. **사용자가 명시적으로 요청하지 않는 한 새 Markdown 문서 파일을 만들지 않는다.** 기능마다 새 문서를 만드는 대신 아래 담당 섹션을 갱신한다.
2. 신규 기능·업무 규칙·입력·예외는 features.md의 해당 도메인, 진행 상태·TODO·미완료·선행·마일스톤은 project-progress.md에서 관리한다.
3. 시스템/API/인증/기술 설계는 architecture.md, DB 컬럼·관계·제약·인덱스는 database-design.md, 합의된 역할 변경은 team-roles.md에 반영한다.
4. 중요한 수정·개선·설계 결정·트러블슈팅·검증은 change-history.md에 날짜·커밋·환경·실행 범위·결과·한계를 함께 남긴다. 단순 코드 수정/리팩토링은 중요한 설계 변경이 없으면 문서화하지 않아도 된다.
5. 고유 정보·설계 의도·비즈니스 규칙·TODO·결정 이유·개발/검증 이력을 문서 수나 분량을 줄이려고 삭제하거나 과도하게 요약하지 않는다.
6. 중복은 동일함과 적용 범위를 확인한 뒤 한곳으로 통합한다. 서로 다른 체크리스트의 짧은 조건·역할/단계/시점이 다른 문장은 문맥을 유지한다.
7. 구현 판단은 실제 코드/설정을 따른다. 과거 설계·미구현 계획·보류/폐기 결정은 이유와 시점을 보존한다. 팀원의 담당·작업 상태를 임의 변경하지 않는다.
8. 통합 시 원본 본문·표·코드 블록과 새 위치를 대조하고 파일/앵커/외부 README 링크를 검증한다. **보존이 검증되지 않은 원본은 삭제하지 않는다.**
9. 실행 DDL의 원본은 백엔드 Flyway다. 문서에 실행 SQL 복사본을 새로 만들거나 적용된 마이그레이션을 수정하지 않는다. 후보 SQL·그림·예시는 버전/범위를 표시한다.
10. 에이전트 메모리·설정·지침은 수정/삭제하지 않는다. 2026-11-10 기술 문서 통합의 수정 범위는 docs/의 8개 Markdown과 루트·백엔드·프런트 README였다.
11. 자기소개서·포트폴리오 초안은 [면접 설계 노트](interview-design-notes.md)에서 관리한다. 기능이나 검증 결과가 바뀌면 변경 이력에 근거를 남기고, 이 문서의 사례 표와 해당 결과를 인용한 초안을 함께 갱신한다. 후속 실험은 실행 결과가 기록된 뒤 성과 문장에 반영한다.

<a id="source-map"></a>
## 원본 100개 문서의 이동 경로

1차 통합에서 기존 README는 재작성하고 나머지 99개 원본 Markdown은 통합/보존 검증 후 삭제했다. 이번에는 새 파일을 만들거나 추가 원본을 삭제하지 않고 **8개 문서 안의 배치**를 고쳤다. 아래 '현재/주제'는 첫 읽기 위치, '기록'은 원본 내용이 남아 있는 첫 기록이다. 원래 경로·행 번호는 역사적 참조로만 읽는다.

| 기존 docs/ 상대 경로 | 현재/주제 | 기록 |
| --- | --- | --- |
| `README.md` | [현재/주제](README.md#source-map) | [기록](change-history.md#b-0-0) |
| `account-trade-integration.md` | [현재/주제](change-history.md#b-12-0) | [기록](change-history.md#b-12-0) |
| `admin-action-audit-design.md` | [현재/주제](change-history.md#b-19-0) | [기록](change-history.md#b-19-0) |
| `admin-cancellation-review.md` | [현재/주제](features.md#orders-cancel) | [기록](change-history.md#b-27-0) |
| `admin-contract-pdf-inspection.md` | [현재/주제](features.md#orders-contract) | [기록](change-history.md#b-36-0) |
| `admin-finance-support.md` | [현재/주제](features.md#finance-batch) | [기록](change-history.md#b-42-0) |
| `admin-member-management.md` | [현재/주제](features.md#members-sanctions) | [기록](change-history.md#b-51-0) |
| `admin-product-moderation.md` | [현재/주제](features.md#products-manage) | [기록](change-history.md#b-12-1) |
| `advanced-db-design.md` | [현재/주제](database-design.md#extensions) | [기록](change-history.md#b-65-0) |
| `advanced-db-transitions.md` | [현재/주제](change-history.md#b-65-0) | [기록](change-history.md#b-65-0) |
| `advanced-design-four-person.md` | [현재/주제](change-history.md#b-94-0) | [기록](change-history.md#b-94-0) |
| `advanced-design-three-person.md` | [현재/주제](change-history.md#b-115-0) | [기록](change-history.md#b-115-0) |
| `advanced-design-two-person.md` | [현재/주제](change-history.md#b-139-0) | [기록](change-history.md#b-139-0) |
| `advanced-finance-foundation.md` | [현재/주제](change-history.md#b-65-0) | [기록](change-history.md#b-65-0) |
| `advanced-policy-boundary.md` | [현재/주제](change-history.md#b-65-0) | [기록](change-history.md#b-65-0) |
| `advanced-rollout-order.md` | [현재/주제](change-history.md#b-170-0) | [기록](change-history.md#b-170-0) |
| `advanced-schema-foundation.md` | [현재/주제](change-history.md#b-175-0) | [기록](change-history.md#b-175-0) |
| `advanced-team-start.md` | [현재/주제](change-history.md#b-181-0) | [기록](change-history.md#b-181-0) |
| `api-spec.md` | [현재/주제](architecture.md#api) | [기록](change-history.md#b-187-0) |
| `auth-login.md` | [현재/주제](features.md#members-session) | [기록](change-history.md#b-211-0) |
| `auto-confirm-timer.md` | [현재/주제](features.md#communication-timer) | [기록](change-history.md#b-242-1) |
| `backend-common-guide.md` | [현재/주제](architecture.md#conventions) | [기록](change-history.md#b-248-0) |
| `category-query.md` | [현재/주제](features.md#products-catalog) | [기록](change-history.md#b-12-1) |
| `contract-pdf-delivery.md` | [현재/주제](features.md#orders-contract) | [기록](change-history.md#b-259-0) |
| `current-status.md` | [현재/주제](project-progress.md#current-status) | [기록](change-history.md#b-268-0) |
| `development-rules.md` | [현재/주제](architecture.md#conventions) | [기록](change-history.md#b-327-1) |
| `erd.md` | [현재/주제](database-design.md#schema) | [기록](change-history.md#b-365-0) |
| `event-contract.md` | [현재/주제](architecture.md#events) | [기록](change-history.md#b-421-0) |
| `expansion-plan.md` | [현재/주제](change-history.md#b-65-0) | [기록](change-history.md#b-65-0) |
| `finance-persistence.md` | [현재/주제](architecture.md#persistence) | [기록](change-history.md#b-12-1) |
| `frontend-common-guide.md` | [현재/주제](architecture.md#environment) | [기록](change-history.md#b-465-0) |
| `full-funding-payment.md` | [현재/주제](features.md#finance-funds) | [기록](change-history.md#b-473-0) |
| `full-funding-policy.md` | [현재/주제](features.md#finance-funds) | [기록](change-history.md#b-479-0) |
| `git-workflow.md` | [현재/주제](team-roles.md#collaboration) | [기록](change-history.md#b-488-1) |
| `header-notification-panel.md` | [현재/주제](features.md#communication-notifications) | [기록](change-history.md#b-537-0) |
| `history/advanced-db-design-installment.md` | [현재/주제](change-history.md#b-542-0) | [기록](change-history.md#b-542-0) |
| `history/advanced-design-four-person-installment.md` | [현재/주제](change-history.md#b-559-0) | [기록](change-history.md#b-559-0) |
| `history/advanced-rollout-order-installment.md` | [현재/주제](change-history.md#b-580-0) | [기록](change-history.md#b-580-0) |
| `images/README.md` | [현재/주제](database-design.md#erd-assets) | [기록](change-history.md#b-585-4) |
| `mail-infrastructure.md` | [현재/주제](architecture.md#mail) | [기록](change-history.md#b-592-0) |
| `manual-trade-verification.md` | [현재/주제](change-history.md#b-593-0) | [기록](change-history.md#b-593-0) |
| `member-change-policy.md` | [현재/주제](features.md#members-profile) | [기록](change-history.md#b-600-0) |
| `member-sanction-foundation.md` | [현재/주제](features.md#members-sanctions) | [기록](change-history.md#b-607-0) |
| `mvp-spec.md` | [현재/주제](change-history.md#b-614-0) | [기록](change-history.md#b-614-0) |
| `my-products.md` | [현재/주제](features.md#products-manage) | [기록](change-history.md#b-637-0) |
| `notifications.md` | [현재/주제](features.md#communication-notifications) | [기록](change-history.md#b-646-0) |
| `order-cancellation-requests.md` | [현재/주제](features.md#orders-cancel) | [기록](change-history.md#b-12-1) |
| `order-chat-pending-send.md` | [현재/주제](features.md#communication-chat) | [기록](change-history.md#b-661-0) |
| `order-chat-realtime.md` | [현재/주제](features.md#communication-chat) | [기록](change-history.md#b-665-0) |
| `order-chat-storage.md` | [현재/주제](features.md#communication-chat) | [기록](change-history.md#b-672-0) |
| `order-chat-workspace.md` | [현재/주제](features.md#communication-chat) | [기록](change-history.md#b-678-0) |
| `order-contract-foundation.md` | [현재/주제](features.md#orders-contract) | [기록](change-history.md#b-684-0) |
| `order-creation.md` | [현재/주제](features.md#orders-create) | [기록](change-history.md#b-12-1) |
| `order-decision.md` | [현재/주제](features.md#orders-create) | [기록](change-history.md#b-12-1) |
| `order-delivery.md` | [현재/주제](features.md#orders-delivery) | [기록](change-history.md#b-12-1) |
| `order-entry.md` | [현재/주제](features.md#orders-create) | [기록](change-history.md#b-710-1) |
| `order-finance-ui.md` | [현재/주제](features.md#finance-funds) | [기록](change-history.md#b-65-0) |
| `order-flow-edge-check.md` | [현재/주제](change-history.md#b-720-0) | [기록](change-history.md#b-720-0) |
| `order-product-change-regression.md` | [현재/주제](change-history.md#b-726-0) | [기록](change-history.md#b-726-0) |
| `order-query.md` | [현재/주제](features.md#orders-query) | [기록](change-history.md#b-12-1) |
| `order-refund.md` | [현재/주제](features.md#orders-cancel) | [기록](change-history.md#b-65-0) |
| `order-revision.md` | [현재/주제](features.md#orders-delivery) | [기록](change-history.md#b-12-1) |
| `order-settlement.md` | [현재/주제](features.md#finance-settlement) | [기록](change-history.md#b-750-0) |
| `order-wallet-payment.md` | [현재/주제](features.md#finance-funds) | [기록](change-history.md#b-65-0) |
| `payment-status-enums.md` | [현재/주제](features.md#finance-funds) | [기록](change-history.md#b-759-0) |
| `physical-db.md` | [현재/주제](database-design.md#schema) | [기록](change-history.md#b-65-0) |
| `product-detail.md` | [현재/주제](features.md#products-catalog) | [기록](change-history.md#b-12-1) |
| `product-management.md` | [현재/주제](features.md#products-manage) | [기록](change-history.md#b-804-0) |
| `product-review-browser-check.md` | [현재/주제](change-history.md#b-809-0) | [기록](change-history.md#b-809-0) |
| `product-sale-status.md` | [현재/주제](features.md#products-manage) | [기록](change-history.md#b-816-0) |
| `product-search.md` | [현재/주제](features.md#products-catalog) | [기록](change-history.md#b-821-3) |
| `project-persistence.md` | [현재/주제](architecture.md#persistence) | [기록](change-history.md#b-12-1) |
| `project-review.md` | [현재/주제](change-history.md#b-65-0) | [기록](change-history.md#b-65-0) |
| `review-changes.md` | [현재/주제](change-history.md#b-12-1) | [기록](change-history.md#b-12-1) |
| `review/advanced-foundation-review.md` | [현재/주제](change-history.md#b-865-0) | [기록](change-history.md#b-865-0) |
| `reviews.md` | [현재/주제](features.md#products-reviews) | [기록](change-history.md#b-872-0) |
| `screen-flow.md` | [현재/주제](features.md#screens-navigation) | [기록](change-history.md#b-879-0) |
| `seller-profile.md` | [현재/주제](features.md#products-seller) | [기록](change-history.md#b-12-1) |
| `session-trade-integration.md` | [현재/주제](change-history.md#b-12-1) | [기록](change-history.md#b-12-1) |
| `settlement-period-query.md` | [현재/주제](features.md#finance-settlement) | [기록](change-history.md#b-901-0) |
| `setup-guide.md` | [현재/주제](architecture.md#environment) | [기록](change-history.md#b-12-1) |
| `sohyun-final-verification.md` | [현재/주제](change-history.md#b-937-0) | [기록](change-history.md#b-937-0) |
| `team-progress.md` | [현재/주제](project-progress.md#team-progress) | [기록](change-history.md#b-943-0) |
| `test-database.md` | [현재/주제](architecture.md#environment) | [기록](change-history.md#b-953-0) |
| `toss-test-payment-setup.md` | [현재/주제](architecture.md#environment) | [기록](change-history.md#b-12-1) |
| `ui-renewal-member-policy.md` | [현재/주제](change-history.md#b-971-0) | [기록](change-history.md#b-971-0) |
| `v3-activation-gate.md` | [현재/주제](project-progress.md#activation-gate) | [기록](change-history.md#b-997-0) |
| `v3-notices.md` | [현재/주제](features.md#communication-timer) | [기록](change-history.md#b-1020-0) |
| `verification-history.md` | [현재/주제](change-history.md#b-12-1) | [기록](change-history.md#b-12-1) |
| `wallet-initialization.md` | [현재/주제](features.md#finance-wallet) | [기록](change-history.md#b-12-1) |
| `wallet-query.md` | [현재/주제](features.md#finance-wallet) | [기록](change-history.md#b-65-0) |
| `wallet-regression-20261102.md` | [현재/주제](change-history.md#b-12-1) | [기록](change-history.md#b-12-1) |
| `wallet-session-integration.md` | [현재/주제](change-history.md#b-12-0) | [기록](change-history.md#b-12-0) |
| `wallet-topup-api.md` | [현재/주제](features.md#finance-topup) | [기록](change-history.md#b-12-1) |
| `wallet-topup-approval.md` | [현재/주제](features.md#finance-topup) | [기록](change-history.md#b-12-1) |
| `wallet-topup-failure-verification.md` | [현재/주제](change-history.md#b-12-0) | [기록](change-history.md#b-12-0) |
| `wallet-topup-frontend.md` | [현재/주제](features.md#finance-topup) | [기록](change-history.md#b-12-0) |
| `wallet-topup-recovery-batch.md` | [현재/주제](features.md#finance-batch) | [기록](change-history.md#b-1084-0) |
| `wallet-topup-schema.md` | [현재/주제](database-design.md#topups) | [기록](change-history.md#b-12-1) |
| `work-plan.md` | [현재/주제](project-progress.md#remaining) | [기록](change-history.md#b-1104-0) |
| `history/current-status-log.md` | [현재 main](project-progress.md#current-status) | [날짜별 기록](change-history.md#status) |

## main pull 후 반영 기준

#149 알림 실패 조회·#151 운영 대시보드·#152 V3 생성 기본 활성·#153 판매 중 상품 최소 금액을 포함한 main a7e889b를 기준으로 한다. 로컬 60edafd의 관리자 회원 HTTP/화면·상품 등록 제재 검사는 [로컬 브랜치 기록](change-history.md#local-admin-member-branch)으로 옮기고 기능 코드는 이번 PR에 포함하지 않는다. 새 main의 고유 문서 내용 26개 본문 블록과 이동된 current-status-log를 검증해 함께 보존했다. [main 통합 기록](change-history.md#main-sync-20261110).

## 이번 정리의 결과·검증

- 원본 본문 블록 3643개 대조: 누락 0개. 원본 코드 블록 90개 대조: 누락 0개. 제목/앵커·링크 목적지·공백 변경은 본문 손실로 계산하지 않는다.
- 내부/외부 README의 로컬 링크 1370건·대상 파일/앵커 검사: 오류 0개, 중복 명시 앵커 0개. 외부 옛 링크 47개 처리 후 남은 삭제 문서 링크 0개.
- 현재 규칙을 읽는 6개 문서(목차/이력 제외)는 562,046자 → 282,381자, 약 50% 감소. 과거 기록은 삭제하지 않고 별도 이력에 보존했다.
- 원본 본문 3643개 중 완전히 같은 긴 중복 113개를 통합했다. 짧은 체크 항목 97개는 서로 다른 적용 문맥을 유지했다.
- 역할·미완료·정책 버전·검증 한계는 유지하며 날짜/커밋별 기록은 주제·시점별 접기 섹션으로 이동했다.
- 변경 허용 범위는 docs/ Markdown 8개와 외부 README 3개뿐이다. 저장본 동일 내용·링크 및 Git 변경 범위를 재검증했다.

외부 README 3개의 옛 Markdown 링크 47개는 새 현재 주제/정확한 이력 위치로 교체하거나 최신 소개에 통합했다. “계약 API 없음”, “현재는 실시간 수신 없음”, 과거 누적 테이블 수를 현재 상태처럼 보여 주던 표현은 현재화하거나 이력으로 이동했다. 대체한 외부 소개 9개 문단은 [이력](change-history.md#external-readme-update)에 보존했다.

소스/SQL 주석에 남은 옛 docs 경로는 코드·비 Markdown 수정 금지 범위라 그대로 유지한다. 이미지·스크립트·SQL·git-commands.txt는 원래 위치에 보존한다. V1 ERD 이미지는 최신 전체 41개 테이블 그림이 아니다. 애플리케이션 빌드·DB 적용·브라우저·원격 CI는 이번 문서 정리에서 재실행하지 않았다. 문서 정리 중에는 Git commit·push·reset·clean을 실행하지 않았다. 이후 사용자 요청으로 최신 main을 pull하고 문서 전용 브랜치를 commit·push해 PR을 생성한다. reset·clean은 실행하지 않는다.

### 최신 main 통합 추가 검증

main a7e889b의 Markdown 100개 본문 7,184줄을 대조해 누락 0을 확인했다. 기존 로컬 기준의 본문 3,643블록·코드 블록 90개도 보존했다. 이전 기본값/로컬 구현 설명을 삭제하지 않고 main 통합 이력에 분리했다. 코드·테스트·설정·SQL은 main과 동일하며 이 PR에서 수정하지 않는다.
