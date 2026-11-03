# 판매자 프로필 관리 백엔드

작성일: 2026-11-03. 담당: 박소현. 브랜치: `codex/seller-profile`.

## 구현 범위

API 명세 3·10절을 따라 기존 seller_profiles Entity를 확장하고 JPA Repository·요청/응답 DTO·입력 검사·Service·Controller를 연결했다. 공유 회원 PK를 프로필 PK로 사용하며 새 테이블·Flyway 변경·프로필 삭제 API는 추가하지 않는다.

| API | 권한·결과 |
| --- | --- |
| GET /api/sellers/{id} | 공개 조회. 잘못된 ID는 400, 미등록 프로필은 404 |
| GET /api/me/seller-profile | 인증된 본인. 미등록 404 |
| PUT /api/me/seller-profile | 인증·CSRF 필요. 최초 생성 201, 수정 200 |

응답은 id(문자열), displayName, introduction, specialty, portfolioUrl이다. 표시 이름은 회원에서 조회하며 이메일·비밀번호·잔액을 공개하지 않는다. 회원 번호는 요청 본문·쿼리·헤더가 아닌 공통 CurrentMemberId의 인증 principal에서 얻는다. 실제 로그인 기능은 기존 인증 담당자의 구현을 사용하며 테스트 인증을 운영 코드에 넣지 않는다.

## 입력·저장 규칙

- introduction 필수 1~2000, specialty 필수 1~100 코드 포인트. 공통 TextNormalizer로 CRLF/CR을 LF로 통일하고 Unicode 앞뒤 공백을 제거한다.
- portfolioUrl은 선택, 최대 2048 코드 포인트. HTTPS·host 존재·userinfo 부재를 URI 파서로 확인한다. 서버에서 외부 주소에 접속하지 않는다.
- PUT은 전체 교체다. 소개·전문 분야를 매번 제출하고, 링크 생략·null·공백은 기존 링크를 NULL로 지운다.
- 조회는 읽기 전용 트랜잭션, 쓰기는 READ_COMMITTED 단일 트랜잭션이다. 회원 행을 먼저 PESSIMISTIC_WRITE로 잠가 같은 회원의 최초 생성·수정 경쟁을 직렬화한다.
- 신규 공유 PK Entity는 EntityManager.persist로 등록하고 기존 관리 Entity는 도메인 메서드로 수정한다. 일반 setter나 직접 JDBC 운영 코드는 추가하지 않는다.
- 공통 Clock.instant를 UTC LocalDateTime으로 변환하고 마이크로초로 절삭한다. DATETIME은 LOCAL_DATE_TIME JDBC 매핑으로 저장한다. 수정 시 created_at은 보존한다.
- demo에서는 Controller·Service를 등록하지 않는다. DB 없는 기본 HTTP 테스트에 SellerProfileService 모의 빈만 추가하고 기존 테스트 설정을 유지했다.

## 검증

- SellerProfileInputTests 18건: 필수/선택값·Unicode 정규화·이모지 길이 경계·URL 형식/길이·userinfo 차단.
- SellerProfileControllerTests 6건: 공개 응답·미인증 차단·CSRF·인증 회원 ID 사용·201/200. Service는 mock이다.
- SellerProfileIntegrationTests 12건: 실제 MySQL 저장·별도 트랜잭션 재조회·HTTP 생성/수정·누락 입력 무변경·링크 삭제·초기 시각 보존·UTC 원문·실패 롤백·4개 동시 최초 등록·ID 오류·없는 회원 차단.

통합 검사는 TEST_DB_* 전용 lancerpick_ci와 임시 MySQL 8.0.46을 사용한다. fixture로 만든 회원·프로필만 정리하며 개발 DB를 사용하지 않는다. HTTP 통합 테스트의 인증 principal은 테스트 대역이므로 실제 브라우저 로그인부터의 종단 간 검증과 구분한다. 팀 기준 MySQL 8.4·원격 CI 성공을 대신하지 않는다.

2026-11-03 에이전트 실행 결과: 프로필 전용 36건과 기존 기능을 포함한 백엔드 전체 **548건**, 실패·오류·건너뜀 0. V1/V2·기본 Hibernate validate를 포함한 `gradlew.bat build --no-daemon` 성공. build 경로의 임시 DB를 사용해 clean은 생략했다. 검사 후 임시 MySQL을 종료하고 자격 정보 파일을 제거했다. 운영 Java에는 JPA만 사용하며 직접 JDBC/네이티브 쿼리 금지 검사도 통과했다.

백엔드 폴더에서 [테스트 DB 설정](test-database.md) 후 실행한다.

```powershell
.\gradlew.bat test --tests "com.example.lancerpick.seller.*"
.\gradlew.bat build
```

프런트 프로필 관리 화면·실제 브라우저 인증 연결은 후속이다. 커밋·Push·PR 병합은 이번 검사 단계에서 하지 않는다.
