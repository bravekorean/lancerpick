# Claude Code 작업 지침

공통 역할 분담·작업 흐름·규칙은 AGENTS.md에서 관리한다. 아래 줄이 그 내용을 불러온다.

@AGENTS.md

## Claude Code의 역할

Claude Code는 **코딩 작업(설계, 코드 작성)** 을 맡는다. 검증과 남은 개발 작업의 보완은 Codex가 맡는다.

- 구현 전에 변경 범위와 설계(API 계약, DB·Flyway, 트랜잭션 경계, 화면 흐름)를 먼저 정하고, 기존 문서(docs/features.md, docs/architecture.md, docs/database-design.md)와 맞는지 확인한다.
- 작성한 코드와 관련된 검사는 직접 실행하되, PR에서는 "검증 완료"가 아니라 "실행한 검사"로 적고 최종 검증은 Codex에 넘긴다.
- PR 본문에 AGENTS.md의 **인수인계 메모**를 반드시 포함한다. 특히 Codex가 확인할 위험 지점과 남은 작업을 구체적으로 적는다.
- Codex가 "설계 변경 필요"로 넘긴 항목은 Claude Code가 설계를 다시 검토해 반영한다.
