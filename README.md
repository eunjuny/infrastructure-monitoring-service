# Infrastructure Monitoring Service

10만 개 이상의 Mock Pod를 실제 PostgreSQL에 저장하고 계층 탐색, 검색, 필터 및 상태 갱신을 제공하는 기술 과제입니다. 기존 프로젝트와 독립적인 코드베이스입니다.

## 현재 구현 범위

- Java 21 / Spring Boot 3.5 백엔드 초기 구성
- Vue 3 / TypeScript / Vite 프론트엔드 초기 구성
- PostgreSQL 17 및 Flyway 계층 스키마, 조회 인덱스
- 재실행 가능한 SQL 데이터 생성: Cluster 20 / Node 200 / Namespace 2,000 / Pod 100,000
- DB 저장 건수를 조회하는 API와 초기 화면
- 테스트 및 성능 검증 계획

계층 탐색, 가상 스크롤, 검색, 필터 및 폴링 화면은 다음 구현 단계입니다. 성능 목표 달성은 아직 검증하지 않았습니다.

## 실행

필수: Java 21, Maven 3.9+, Node.js 22.12+, Docker Compose.

```bash
docker compose up -d db
mvn -f backend/pom.xml spring-boot:run
```

백엔드 기동 후 별도 터미널에서 데이터를 생성합니다. Flyway가 먼저 테이블을 생성해야 합니다.

```bash
docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/seed.sql
cd frontend
npm install
npm run dev
```

화면: http://127.0.0.1:5174 / 백엔드: http://127.0.0.1:8081 / DB: 127.0.0.1:5544.

Compose의 자격 정보는 개인 정보가 아닌 격리된 로컬 과제 DB 전용 개발 값입니다. 운영에서는 별도 시크릿과 인증을 적용해야 합니다. 모든 공개 포트는 루프백에 바인딩합니다.

## 구조

```text
backend/     REST API, DB 조회, Flyway, 테스트
frontend/    화면 및 API 클라이언트
scripts/     서비스와 분리된 데이터 생성 SQL
docs/        요구사항, 설계, 성능 검증 기준
compose.yaml 로컬 PostgreSQL
```

## 기술 선택

공고의 Java 17 이상, Spring Boot 3.x, JPA 및 RDBMS 요구와 과제의 실제 대량 데이터 조회 조건에 맞춰 Java 21, Spring Boot 3.5, PostgreSQL을 선택했습니다. JPA는 도메인 조회 확장용이며 현재 건수 조회는 명시적인 SQL을 사용합니다. Vue는 탐색 상태와 상세 패널을 구성하고, 다음 단계에서 TanStack Virtual로 DOM 수를 제한합니다. 인증, 메시지 브로커, 실제 Kubernetes 연동은 필수 요구사항이 아니므로 초기 범위에서 제외합니다.

## 검증

```bash
mvn -f backend/pom.xml test
cd frontend
npm run typecheck
npm run build
```

백엔드 테스트에 실제 PostgreSQL Testcontainers 검증이 포함되므로 Docker가 필요합니다. 초기 구성 파일은 만들어졌지만 생성 환경에서는 의존성 다운로드 및 Docker 실행이 아직 검증되지 않았습니다. 실행 성공이나 성능 수치를 가정하지 않습니다.

- [구현 계획과 설계](docs/IMPLEMENTATION_PLAN.md)
- [성능 측정 프로토콜](docs/PERFORMANCE.md)
- [프로젝트 기준 문서와 초기 검증 결과](docs/PROJECT_GUIDE.md)

## 다음 구현

1. 부모별 제한된 목록 조회 및 상세·경로 API
2. 지연 로딩 트리와 서버 페이지 기반 가상 리스트
3. 이름 검색·상태 필터·경로 이동
4. Mock 상태 변화와 부분 갱신
5. 기능 및 성능 측정, 개선 결과 기록
