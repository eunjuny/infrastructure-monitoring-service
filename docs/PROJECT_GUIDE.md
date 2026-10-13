# 프로젝트 기준 문서

이 문서는 Infrastructure Monitoring Service의 현재 기능, 구조, 처리 흐름과 검증 상태를 관리합니다. 기능과 실행 방식이 바뀔 때 README 및 관련 설계 문서도 함께 갱신합니다.

## 목적과 범위

Mock 인프라 데이터를 실제 DB에 저장하고 대용량 리소스 탐색 성능을 검증하는 독립 과제입니다. 목표 규모는 Cluster 20개, Node 200개, Namespace 2,000개, Pod 100,000개입니다. 실제 Kubernetes 연동은 하지 않습니다.

## 현재 구조와 처리 흐름

```text
scripts/seed.sql → PostgreSQL의 4개 계층 테이블
Vue 초기 화면 → Vite /api 프록시 → Spring Boot → SQL 건수 조회 → 화면 표시
Spring Boot 기동 → Flyway 마이그레이션 → 테이블 및 인덱스 생성
JUnit 통합 테스트 → Testcontainers PostgreSQL → Flyway 스키마 확인
```

현재 작성된 기능은 리소스 건수 API, 초기 요약 화면, 스키마, SQL 데이터 생성과 스키마 테스트입니다. 지연 로딩 탐색, 상세, 검색, 필터, 가상 스크롤 및 상태 갱신은 미구현입니다. 데이터 생성 파일이 있다는 사실과 실제 10만 건 적재 검증은 구분합니다.

## 기술과 실행 경계

Java 21 / Spring Boot 3.5 / PostgreSQL 17 / Flyway / Vue 3 / TypeScript / Vite / Testcontainers를 구성했습니다. TanStack Virtual 의존성을 추가했으나 화면에 아직 적용하지 않았습니다. JPA 의존성을 구성했지만 현재 API는 JdbcTemplate으로 건수만 조회합니다.

기존 프로젝트와 충돌을 줄이기 위해 화면 5174, API 8081, DB 5544를 사용하며 루프백에 바인딩합니다. 개발 DB의 공개된 기본 자격 정보는 실사용 계정 정보가 아닙니다. 인터넷 운영 배포 및 사용자 인증은 초기 범위 밖입니다.

## 초기 검증 결과 — 2026-10-13

| 항목 | 결과 |
|---|---|
| package.json / tsconfig.json 문법 | 통과 |
| Maven XML 파싱 | 통과 |
| docker compose config --quiet | 통과 |
| git diff --cached --check | 통과 |
| 생성 규칙의 계층 수 계산 | 20 / 200 / 2,000 / 100,000 확인 |
| Maven 테스트 | 미실행: 필요한 의존성이 로컬 캐시에 없음 |
| npm 설치 및 프론트 빌드 | 미실행: 오프라인 캐시에 의존성이 없음 |
| 외부 의존성 다운로드 | 현재 세션에서 Maven 저장소 DNS 조회 실패 |
| Docker / Testcontainers / 실제 DB 적재 | 현재 세션의 Docker 소켓 접근 제한으로 미검증 |
| 화면 E2E 및 성능 목표 | 미검증 |

구성 문법 검증은 실행·컴파일·통합 테스트 통과를 의미하지 않습니다. 네트워크 및 Docker 접근이 가능한 환경에서 의존성 설치, 빌드, Flyway, 10만 건 적재, 재실행 시 중복 방지와 통합 테스트를 먼저 확인해야 합니다. npm 잠금 파일도 정상 설치 이후 생성·커밋합니다.

## 설계와 다음 단계

- [구현 계획과 필수 설계 항목](IMPLEMENTATION_PLAN.md)
- [성능 측정 기준](PERFORMANCE.md)

다음은 실제 구동 검증 후 부모별 목록·상세·경로 API, 지연 로딩·가상 스크롤, 검색·필터, 상태 변화·갱신 순으로 구현합니다. 필수 조건을 검증하기 전에 추가 OSS를 늘리지 않습니다.
