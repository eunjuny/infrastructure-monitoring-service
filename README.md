# Infrastructure Monitoring Service

10만 개 이상의 Mock Pod를 실제 PostgreSQL에 저장하고 계층 탐색, 검색, 필터 및 상태 갱신을 제공하는 기술 과제입니다. 기존 프로젝트와 독립적인 코드베이스입니다.

## 현재 구현 범위

- Java 21 / Spring Boot 3.5 백엔드 초기 구성
- Vue 3 / TypeScript / Vite 프론트엔드 초기 구성
- PostgreSQL 17 및 Flyway 계층 스키마, 조회 인덱스
- 재실행 가능한 SQL 데이터 생성: Cluster 20 / Node 200 / Namespace 2,000 / Pod 100,000
- DB 저장 건수를 조회하는 API와 초기 화면
- 유형별·부모별 목록, 하위 탐색, 상세·경로, 이름 검색·상태 필터 API
- 조건에 연결된 ID 커서, 최대 100개 응답, 검색용 pg_trgm 인덱스
- 지연 로딩 계층 탐색, 상세·상위 경로, 이름 검색·상태 필터 화면 연결
- 고정 높이 가상 리스트, 최대 500개 목록 캐시, 순차 페이지 탐색
- 콘솔형 탐색기·유형 바로가기·열 중심 목록·닫기 가능한 상세 탭·모바일 계층 접기
- 수동 새로고침: 현재 페이지·선택 상세 갱신 및 오류·재시도 표시
- 자동 갱신: 현재 조회 창 폴링, 숨김·오프라인 중지, 실패 시 지수 백오프
- 선택형 Mock 프로필: 작은 트랜잭션 배치로 상태·CPU·메모리·재시작 횟수 변경
- 요청별 10초 타임아웃·취소 구분·재시도 안내
- 실제 DB·Chrome 반복 성능 측정 및 실행 계획 검증 스크립트

실제 PostgreSQL 17에서 Flyway V1·V2, Pod 10만 건 적재·재적재와 조회를 검증했습니다. 백엔드 69개, 화면·클라이언트 56개, 실제 API Chromium E2E 7개가 통과했습니다. 자동 갱신과 Mock 변경도 구현했습니다. CPU 4x·캐시 OFF에서 20회 반복 측정과 100페이지 추가 탐색을 수행했습니다. [결과·개선 내용·측정 한계](docs/PERFORMANCE_RESULTS.md)를 참고합니다.

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
npm ci
npm run dev
```

화면: http://127.0.0.1:5174 / 백엔드: http://127.0.0.1:8081 / DB: 127.0.0.1:5544.

Compose의 자격 정보는 개인 정보가 아닌 격리된 로컬 과제 DB 전용 개발 값입니다. 운영에서는 별도 시크릿과 인증을 적용해야 합니다. 모든 공개 포트는 루프백에 바인딩합니다.

### 상태 변경 데모

기본 백엔드는 데이터를 변경하지 않습니다. 이 과제 Mock DB에서만 아래 프로필을 사용합니다. 이미 실행한 기본 백엔드는 종료한 뒤 다시 실행합니다.

```bash
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=mock
```

시작 10초 후부터 배치 완료 후 5초마다 Cluster 1개·Node 5개·Namespace 10개·Pod 100개 이하를 순차 변경합니다. 실제 Kubernetes 수치가 아닙니다. 화면 자동 갱신은 기본 활성이고 해제할 수 있습니다. 숨긴 탭과 오프라인에서는 중지하며 건수 집계는 정기 조회하지 않습니다. [상세 설계](docs/LIVE_UPDATES.md)를 참고합니다.

## 구조

```text
backend/     REST API, DB 조회, Flyway, 테스트
frontend/    화면 및 API 클라이언트
scripts/     서비스와 분리된 데이터 생성 SQL
docs/        요구사항, 설계, 성능 검증 기준
compose.yaml 로컬 PostgreSQL
```

## 기술 선택

공고의 Java 17 이상, Spring Boot 3.x 및 RDBMS 요구와 과제의 실제 대량 데이터 조회 조건에 맞춰 Java 21, Spring Boot 3.5, PostgreSQL을 선택했습니다. JPA 의존성은 초기 구성에 남아 있지만 현재 조회는 명시적인 JDBC SQL과 DTO를 사용합니다. Vue는 탐색 상태와 상세 패널을 구성합니다. 목록이 고정 높이이므로 작은 가상 리스트로 DOM 수를 제한하고 서버 페이지·캐시 상한을 별도로 적용합니다. 초기에 계획한 TanStack Virtual은 적용하지 않고 미사용 의존성을 제거했습니다. [화면 설계](docs/FRONTEND_RENDERING.md)를 참고합니다. 인증, 메시지 브로커, 실제 Kubernetes 연동은 필수 요구사항이 아니므로 초기 범위에서 제외합니다.

## 화면 사용

1. 처음에는 전체 Pod의 첫 100개와 Cluster의 첫 50개를 요청합니다. 전체 수는 별도 건수 API로 표시합니다.
2. 왼쪽 `+`로 직속 하위 항목을 조회하고, `→`로 가운데 목록의 조회 범위를 해당 부모로 바꿉니다.
3. 가운데 이름 검색은 250ms 디바운스 후 선택한 유형 전체를 검색합니다. 검색어가 있으면 `모든 유형`도 선택할 수 있습니다. 상태는 서버에서 함께 필터링합니다.
4. 행을 누르면 오른쪽에 상세·속성·전체 경로가 표시됩니다. `계층에서 위치 보기`는 조상을 펼치고 검색된 항목으로 이동합니다.
5. 아래쪽 스크롤 또는 `다음 100개`로 순차 탐색합니다. 최근 5페이지만 유지하므로 오래된 앞부분은 `처음부터`로 다시 조회합니다. 임의 위치 점프는 제공하지 않습니다.
6. `새로고침`은 현재 창을 다시 받아 한 번에 교체합니다. 자동 갱신은 완료 5초 뒤 같은 범위를 다시 조회합니다. 실패 시 기존 화면을 유지하고 최대 60초 간격으로 재시도합니다. 선택 항목과 스크롤을 유지합니다.
7. 유형별 좌측 메뉴·건수 카드로 전역 목록을 바로 조회할 수 있습니다. 상태 요약은 현재 조회창만 집계합니다. `/`로 검색 포커스, `Escape`로 상세 닫기, 개요·관계 탭은 좌우 방향키로 이동합니다. 모바일 계층은 기본 접힘이며 버튼으로 펼칩니다.
8. 요청이 10초 동안 완료되지 않으면 시간 초과 안내를 표시합니다. 초기 조회는 `다시 조회`, 갱신은 기존 화면을 유지한 채 재시도합니다. 이 제한은 개별 요청 기준이며 전체 갱신 그룹의 제한은 아닙니다.

## 검증

```bash
mvn -f backend/pom.xml test
cd frontend
npm run typecheck
npm test
npm run build
npm audit --audit-level=moderate
```

백엔드 테스트는 실제 PostgreSQL Testcontainers를 포함하며 Docker가 필요합니다. Vitest·jsdom은 대체 API 응답을 사용하고, Chromium E2E는 실제 DB·백엔드에 연결합니다. `npm ci`, 타입 검사, 정식 빌드, 전체 테스트와 npm audit 0건을 확인했습니다. 설치 버전은 package-lock.json으로 고정합니다. 외부 AI·클라우드 API를 사용하지 않습니다.

브라우저 E2E는 Compose DB에 seed를 적재하고 백엔드 8081을 실행한 상태에서 진행합니다. 마지막 테스트는 이 Mock DB의 Pod 1 상태를 직접 변경해 폴링을 확인하므로 실사용 DB에는 실행하지 않습니다.

```bash
cd frontend
npx playwright install chromium
npm run build
npm run test:e2e
```

CPU 4x·캐시 OFF 성능 측정은 프로덕션 preview 5174가 켜진 상태에서 실행합니다. 이 스크립트도 과제 DB의 Pod 1 상태를 변경합니다. 원본 JSON·trace는 Git 제외 경로 `frontend/.validation/performance/`에 생성합니다.

```bash
cd frontend
PERF_LABEL=local-check npm run test:performance
```

대표 SQL 실행 계획은 프로젝트 루트에서 확인합니다.

```bash
docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/explain.sql
```

- [구현 계획과 설계](docs/IMPLEMENTATION_PLAN.md)
- [성능 측정 프로토콜](docs/PERFORMANCE.md)
- [실제 성능 결과·개선 전후·남은 점검](docs/PERFORMANCE_RESULTS.md)
- [프로젝트 기준 문서와 초기 검증 결과](docs/PROJECT_GUIDE.md)
- [1단계 구동 검증 보고서](docs/VALIDATION_REPORT.md)
- [리소스 API 계약](docs/API.md)
- [2단계 조회 구현·검증 보고서](docs/STAGE2_REPORT.md)
- [화면 렌더링·캐시·요청 처리 설계](docs/FRONTEND_RENDERING.md)
- [3단계 화면 연결·검증 보고서](docs/STAGE3_REPORT.md)
- [Mock 상태 변경·자동 갱신 설계](docs/LIVE_UPDATES.md)
- [실제 DB·브라우저·자동 갱신 검증 결과](docs/STAGE4_REPORT.md)
- [운영 도구 참고·콘솔 UI 설계 및 검증](docs/UI_DESIGN.md)

## 다음 구현

1. 실제 Chrome 작업관리자 탭 메모리와 수동 스크롤·자동 갱신의 Performance 기록 최종 확인
2. 짧고 흔한 검색어·모든 유형 검색 등 추가 경계 시나리오의 반복 측정
3. 과제 가이드에 맞춰 순차 커서 탐색 UX와 실행·검증 증거 최종 점검
4. 기존 GitHub 이력 확인·원격 연결 후 푸시, 깨끗한 체크아웃에서 설치·기동 재현 및 제출 자료 정리

현재 변경사항은 초기 구성부터 기능·검증·문서의 다섯 단계 로컬 커밋으로 정리했습니다. 푸시는 하지 않았으며 로컬 저장소의 원격 연결은 별도 확인이 필요합니다. [단계별 기록](docs/PROJECT_GUIDE.md#단계별-커밋과-제출-전-상태)을 참고합니다.
