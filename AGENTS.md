# AI 에이전트 작업 지침

이 저장소에서 일하는 AI 에이전트(Claude Code, Codex)의 역할과 작업 흐름을 정한다. Codex는 이 파일을, Claude Code는 이 파일을 불러오는 [CLAUDE.md](CLAUDE.md)를 읽는다. 두 도구가 같은 규칙을 따르도록 공통 내용은 이 파일 한 곳에서만 관리한다.

팀원의 담당·Git·PR·리뷰 규칙은 [docs/team-roles.md](docs/team-roles.md)를 따른다. 이 파일은 그 규칙 위에서 AI 도구 사이의 분담만 정한다.

## 역할 분담

| 도구 | 담당 | 하지 않는 일 |
| --- | --- | --- |
| **Claude Code** | 코딩 작업: 요구사항 분석, 설계(API·DB·화면 구조), 코드 작성, 작성한 코드의 기본 검사, PR 작성 | 자기 작업을 최종 검증 완료로 표시하지 않는다 |
| **Codex** | 검증과 미비된 개발 작업: 코드 리뷰, 테스트 작성·실행, 빌드·CI 확인, 요구사항 대비 누락 확인, 남은 구현 보완 | 합의된 설계(API 계약·DB 구조·화면 흐름)를 단독으로 바꾸지 않는다 |

- "미비된 개발 작업"은 이미 정해진 설계 범위 안에서 빠진 부분을 채우는 일이다. 예: 누락된 예외 처리·입력 검증, 빠진 테스트, 설계대로 연결되지 않은 화면·API, 리뷰에서 나온 수정.
- 설계 자체를 바꿔야 하는 문제(API 응답 형식, 테이블·컬럼, 트랜잭션 경계, 화면 흐름 변경 등)를 발견하면 Codex는 직접 고치지 않고 근거와 함께 보고한다. 설계 변경은 Claude Code가 맡는다.
- 사람이 직접 다른 분담을 지시하면 그 지시를 따른다.

## 작업 흐름

```
요구사항 → Claude Code 설계·구현 → PR(인수인계 메모) → Codex 리뷰·검증·보완 → 검증 보고 → 사람이 승인·병합
```

1. **Claude Code**는 최신 main에서 작업 브랜치를 만들고 설계와 구현을 진행한다. 변경과 관련된 검사(아래 [검증 명령](#검증-명령))를 직접 실행한 뒤 PR을 연다.
2. PR 본문에는 team-roles.md의 PR 항목에 더해 아래 **인수인계 메모**를 남긴다.
3. **Codex**는 PR의 변경 범위를 리뷰하고 검증 명령을 실행한다. 누락된 테스트·예외 처리 등 설계 범위 안의 보완은 같은 브랜치에 커밋으로 추가한다.
4. **Codex**는 아래 **검증 보고**를 PR에 남긴다. 설계 변경이 필요한 항목은 보고만 하고 Claude Code에 넘긴다.
5. 병합은 team-roles.md의 조건(작성자 외 1명 이상 승인, 필수 CI 통과, Squash merge)에 따라 사람이 결정한다. AI 도구는 PR을 직접 병합하지 않는다.

### 인수인계 메모 (Claude Code → Codex)

```markdown
## 인수인계
- 설계 요약: 무엇을 왜 이렇게 설계했는지
- 변경 범위: 화면 / API / DB·Flyway / 공통 코드
- 실행한 검사: 명령과 결과 (실행하지 못한 검사는 이유와 함께)
- 검증 요청: Codex가 특히 확인할 부분 (동시성, 금액, 권한, 예외 경로 등)
- 남은 작업: 아직 구현하지 않은 부분과 범위
```

### 검증 보고 (Codex → PR)

```markdown
## 검증 보고
- 실행한 검사: 명령, 환경(로컬 DB·CI 등), 결과
- 리뷰 결과: 문제와 위치, 심각도
- 보완한 내용: Codex가 추가한 커밋과 이유
- 설계 변경 필요: 직접 고치지 않고 넘기는 항목
- 미검증 범위: 자동 테스트로 확인하지 못한 부분 (브라우저, 실제 토스·Batch 등)
```

## 공통 규칙

- 답변, 보고, PR 본문, 코드 리뷰 의견은 **한국어**로 작성한다. 커밋 메시지는 기존 형식(`type: 변경 내용`, 한국어 설명)을 따른다.
- main에 직접 Push하지 않는다. 다른 사람이 Push한 공유 브랜치의 이력을 Rebase하거나 강제 Push하지 않는다.
- 이미 main에 있는 Flyway 마이그레이션(`LancerPick/src/main/resources/db/migration/`)은 수정·삭제·이름 변경하지 않고 다음 버전 파일을 추가한다. CI가 이를 검사한다. DB 변경 설계는 team-roles.md의 DB 담당 규칙을 따른다.
- 비밀번호, 토큰, API 키, 개인 접속 정보(`local-application.properties` 등)를 커밋하지 않는다.
- 자동 테스트 통과를 브라우저·실제 DB·외부 연동의 종단 간 검증 완료로 표현하지 않는다. 확인하지 못한 부분은 **확인 필요**로 적는다.
- 문서는 [docs/README.md](docs/README.md)의 관리 원칙을 따른다. 요청 없이 새 Markdown 문서를 만들지 않고, 해당 주제 문서의 섹션을 갱신한다.

## 검증 명령

프런트엔드(`LancerPickFrontend`, Node.js 24):

```powershell
npm ci
npm run test:api
npm run typecheck
npm run lint
npm run build
```

백엔드(`LancerPick`, Java 25). 테스트 전용 DB와 `TEST_DB_URL`·`TEST_DB_USERNAME`·`TEST_DB_PASSWORD`가 필요하다. 준비 방법은 [테스트 DB 안내](docs/architecture.md#environment)를 따른다.

```powershell
.\gradlew.bat clean build   # Windows
./gradlew clean build       # macOS·Linux
```

CI(`.github/workflows/ci.yml`)는 위 검사와 마이그레이션 불변성 검사를 실행한다. 로컬에서 실행할 수 없는 검사는 해당 커밋의 GitHub Actions 결과로 확인하고, 어느 쪽으로 확인했는지 보고에 적는다.
