# Infrastructure Monitoring Service

10만 개 이상의 Mock Pod를 실제 PostgreSQL에 저장하고 계층 탐색, 검색, 필터 및 상태 갱신을 제공하는 기술 과제입니다. 기존 프로젝트와 독립적인 코드베이스입니다.

현재 기능·구조는 [프로젝트 기준 문서](docs/PROJECT_GUIDE.md), 초기 구성부터의 구현·검증·시행착오는 [작업 이력](docs/WORK_HISTORY.md)에 정리합니다. 이후 작업도 [저장소 작업 규칙](AGENTS.md)에 따라 이력에 계속 추가합니다.

## 현재 구현 범위

- Java 21 / Spring Boot 3.5 / JPA·Hibernate 백엔드
- Gradle Wrapper·Kotlin DSL·의존성 잠금·공식 배포/Wrapper 체크섬 검사
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
- DB 풀 연결 대기 3초·검사 1초, 풀 고갈의 안전한 503·연결 반환 후 복구 검증
- 실제 DB·Chrome 반복 성능 측정 및 실행 계획 검증 스크립트
- 초기 지역화 비용·선택 화면 코드·렌더링 최적화, 동일 조건 초기 진입 반복 검증
- Cluster·Namespace 범위와 이름·상태 필터 조합
- 상세 패널의 상태·속성 변경 이력, 제한된 보관·커서 조회
- Actuator·Micrometer·Prometheus 기반 운영 카드와 P95 추이 차트
- 백엔드·정적 프론트·DB·Prometheus 전체 Compose 및 격리 CI/Jenkins
- 선택형 Keycloak 로그인·관리자/A/B 계정·Cluster 범위 권한, 동일한 10만 Pod 공유
- 만료 시각 누락·잘못된 권한 claim 형식을 거부하는 access-token 검증

최종 보완은 새 OSS를 추가하지 않고 실제 재현된 인증 경계와 DB 대기/실패 응답을 강화했습니다. 과제·공고 대조, 선택하지 않은 기능과 수동 성능 검증의 한계는 [요구 대조와 안정성 검토](docs/REQUIREMENTS_AND_HARDENING_REVIEW.md)에 정리했습니다.

2026-10-16 공개 이미지용 Docker 클라이언트를 격리한 **Jenkins 빌드 15 SUCCESS**를 확인했습니다. 서버 107개(실제 PostgreSQL 44 포함)·프론트 77개·빠른 검사 46개·기본 브라우저 11개·실제 Keycloak 5개·이미지 4개·실제 장애/복구·컨테이너 정리를 통과했습니다. 전역 Docker 설정과 기존 컨테이너 18개를 보존했으며 전체 Docker 재시작은 하지 않았습니다. [현재 실행 근거](docs/verification/2026-10-16-public-image-ci.json), [남은 수동 확인](docs/REMAINING_PERMISSIONS.md)을 참고합니다. 이전 빌드 12/13의 중단 기록은 별도 이력으로 유지합니다.

실제 PostgreSQL 17에서 Flyway V1~V3, Pod 10만 건 적재·재적재와 조회를 검증했습니다. JPA 전환 후 전체 DB·API 테스트와 Compose·Prometheus를 검증했고, 이후 초기 진입을 추가 최적화했습니다. CPU 4x·캐시 OFF·실제 DB에서 인증 추가 전 초기 진입 60회 모두 500ms 이내(최대 468.8ms)였습니다. 이후 실제 Keycloak 관리자 로그인·SSO 재진입을 별도 40회 측정해 재진입 최대 330.03ms와 탐색 목표 초과 0을 확인했습니다. 비밀번호 제출 후 첫 로그인 최대 638.02ms는 별도 기록이며 로그인 전체 500ms 보장은 아닙니다. 중간 버전의 실패 표본도 보관합니다. [인증 성능](docs/AUTHENTICATION_PERFORMANCE.md), [초기 성능 개선](docs/STARTUP_PERFORMANCE.md), [JPA 전환](docs/JPA_QUERY_DESIGN.md), [최신 테스트·CI 결과](docs/PROJECT_GUIDE.md)를 참고합니다.

## 실행

필수: Java 21, Node.js 22.12+, Docker Compose. 백엔드는 Gradle 8.14.3 Wrapper를 포함하므로 전역 Maven/Gradle 설치가 필요 없습니다. 첫 실행에는 배포·의존성 다운로드 네트워크가 필요합니다. [Gradle 전환·잠금·검증](docs/GRADLE_BUILD.md)을 참고합니다.

```bash
docker compose up -d db
./backend/gradlew -p backend bootRun
```

백엔드 기동 후 별도 터미널에서 데이터를 생성합니다. Flyway가 먼저 테이블을 생성해야 합니다.

```bash
docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/seed.sql
cd frontend
npm ci
npm run dev
```

화면: http://127.0.0.1:5174 / 백엔드: http://127.0.0.1:8081 / DB: 127.0.0.1:5544.
Health: http://127.0.0.1:8086/actuator/health (관리 포트는 API와 별개입니다).

Prometheus와 경보를 함께 개발할 때는 `docker compose --profile monitoring up -d --build db prometheus alertmanager alert-receiver`를 실행합니다. Prometheus는 http://127.0.0.1:9094 에서 관리 포트 8086을 수집합니다. 운영 관측 메뉴는 실제 지표·경보를 표시하며 표본 부족·연결 장애를 구분합니다. Alertmanager 운영 도구는 루프백 9194이며 수신기에는 호스트 포트를 공개하지 않습니다.

Compose의 자격 정보는 개인 정보가 아닌 격리된 로컬 과제 DB 전용 개발 값입니다. 운영에서는 별도 시크릿과 인증을 적용해야 합니다. 모든 공개 포트는 루프백에 바인딩합니다.

### 상태 변경 데모

기본 백엔드는 리소스 상태를 변경하지 않지만 오래된 변경 이력은 정리합니다. 이 과제 Mock DB에서만 아래 프로필을 사용합니다. 이미 실행한 기본 백엔드는 종료한 뒤 다시 실행합니다.

```bash
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=mock'
```

시작 10초 후부터 배치 완료 후 5초마다 Cluster 1개·Node 5개·Namespace 10개·Pod 100개 이하를 순차 변경합니다. 실제 Kubernetes 수치가 아닙니다. 화면 자동 갱신은 기본 활성이고 해제할 수 있습니다. 숨긴 탭과 오프라인에서는 중지하며 건수 집계는 정기 조회하지 않습니다. [상세 설계](docs/LIVE_UPDATES.md)를 참고합니다.

CI와 호스트 백엔드를 동시에 사용하려면 `bash scripts/run-local-backend.sh`를 권장합니다. 빌드 결과를 고유 이름의 JAR로 복사해 Mock 프로필로 실행하므로 CI 재빌드가 실행 중인 파일을 덮어쓰지 않습니다.

### 전체 Compose와 CI / Jenkins

```bash
docker compose -f compose.runtime.yaml --profile monitoring up -d --build
```

전체 컨테이너 화면: http://127.0.0.1:5175 / API: http://127.0.0.1:8082 / Prometheus: http://127.0.0.1:9092.
개발 모드와 **별도 DB 볼륨**을 사용하고 seed를 자동 적재합니다. 관리 포트·DB는 컨테이너 내부에만 노출합니다. 따라서 5174와 5175의 데이터 변경은 서로 공유되지 않습니다.

```bash
(cd frontend && npm ci && npx playwright install chromium)
# 전체 CI는 새로 만든 깨끗한 Git 체크아웃에서 실행합니다.
bash scripts/ci.sh
python3 scripts/jenkins-local.py --revision HEAD
```

CI는 전체 커밋 SHA를 고정하고 테스트·버전 이미지·기본 E2E·실제 장애 복구·Keycloak E2E를 단계별 실행합니다. Jenkins는 새 독립 체크아웃을 만들고, SHA/build/image ID와 실제 컨테이너의 일치를 결과로 남깁니다. **미커밋 변경은 포함하지 않으며 자동 커밋도 하지 않습니다.** 아직 커밋에 새 CI 파일이 없다면 먼저 명시적으로 커밋해야 합니다. 개발 중에는 `bash scripts/check.sh fast/full`을 사용합니다. [커밋 고정 CI 실행·근거](docs/CI_REPRODUCIBILITY.md)를 참고합니다.

CI 실행자는 Jenkins 하나로 통일했습니다. GitHub Actions workflow는 제거해 중복 자동 실행을 하지 않습니다. `ci.sh`는 Jenkins와 별도인 두 번째 CI가 아니라 동일 검증의 로컬 재현 명령입니다. CI 컨테이너는 정리하지만 기존 볼륨은 삭제하지 않습니다. Jenkins는 http://127.0.0.1:8182 에서 실행합니다. 관리자 비밀값은 Git 제외 `.local/jenkins-admin-password`에 저장하며 출력하지 않습니다. 로컬 agent는 호스트/Docker 권한으로 신뢰하는 코드만 실행해야 합니다. [실행 경계·보관·CI 상세](docs/OPERATIONS_AND_DELIVERY.md)를 참고합니다.

공개 이미지용 CI는 단계별 임시 Docker 클라이언트 설정으로 개인 키체인/로그인 정보와 분리됩니다. 기존 로컬 Unix socket·설치된 Buildx/Compose만 사용하고 전역 설정이나 실행 중인 서버를 변경하지 않습니다. 사설 레지스트리·원격 Docker·TLS override는 지원하지 않으며 익명 rate limit은 실패로 처리합니다. [설계와 제한](docs/CI_REPRODUCIBILITY.md#공개-이미지와-개인-docker-설정-분리)을 참고합니다.

## 선택형 Keycloak 인증 화면

인증 단계만 검증하려면 `bash scripts/ci-auth.sh`, 전체 CI는 `bash scripts/ci.sh`를 실행합니다. 전체 CI에는 경보 규칙 검사·격리 백엔드의 실제 중단/해소 알림·CI 전용 Keycloak 로그인/권한 검증을 포함합니다. 인증 CI는 Compose 2.24.4 이상과 tmpfs DB·새 임시 계정을 사용하며 기존 로컬 계정/볼륨을 초기화하지 않습니다. [인증과 CI/배포의 관계](docs/AUTHENTICATION_CI.md)를 참고합니다.

관리자 `운영 관측`에 현재 경보 조건·지속 확인·발생 상태와 대응 방법을 추가했습니다. 소스/규칙 오류는 "경보 상태 확인 불가"입니다. 앱 중단 시에는 화면도 조회되지 않을 수 있어 Alertmanager가 독립 내부 수신기에 알립니다. 수신기는 최근 200개 이벤트를 메모리에 유지하는 로컬 데모이며 외부 메일·영구 장애 이력이 아닙니다. 외부 SMTP/클라우드 호출은 없습니다. `bash scripts/test-alert-rules.sh`로 규칙만 검사할 수 있습니다. [경보 조건·안전 경계·복구 검증](docs/ALERTING_AND_RECOVERY.md)을 참고합니다.

```bash
python3 scripts/auth-local.py
python3 scripts/test-auth.py
PERF_LABEL=auth-admin-local PERF_REPETITIONS=20 python3 scripts/test-auth.py --performance
```

화면은 http://127.0.0.1:5176/ 입니다. 서비스 계정은 `admin`(전체), `user-a`(Cluster 1~10), `user-b`(Cluster 11~20)이며 데이터는 총 10만 Pod를 공유합니다. 각 비밀번호는 Git 제외 `.local/auth/credentials.json`(0600)에 생성되고 출력하지 않습니다. 기본 과제/성능 환경과 인증 환경 DB·포트를 분리합니다. [설계·실행·보안 경계](docs/AUTHENTICATION.md)를 먼저 확인합니다. 운영용 공개 배포 설정은 아닙니다.

관리자 성능 명령은 실제 PKCE 로그인·SSO 재진입·탐색을 측정하며 비밀번호/토큰/trace를 저장하지 않습니다. 매 실행에 새 `PERF_LABEL`을 지정합니다. 읽기 전용 갱신이며 DB 값을 직접 바꾸지 않습니다. [측정 구간·결과·제한](docs/AUTHENTICATION_PERFORMANCE.md)을 참고합니다.

## 구조

```text
backend/     REST API, DB 조회, Flyway, 테스트
frontend/    화면 및 API 클라이언트
scripts/     서비스와 분리된 데이터 생성 SQL
docs/        요구사항, 설계, 성능 검증 기준
compose.yaml 로컬 PostgreSQL·선택형 Prometheus
compose.runtime.yaml 전체 애플리케이션·격리 DB·선택형 Prometheus
compose.jenkins.yaml 로컬 Jenkins controller
infra/       Prometheus·Jenkins 설정
```

## 기술 선택

공고의 Java 17 이상, Spring Boot 3.x·JPA/Hibernate·RDBMS 요구에 맞춰 Java 21, Spring Boot 3.5, JPA/Hibernate, PostgreSQL을 사용합니다. 과제 가이드 자체는 백엔드 기술을 제한하지 않습니다. 초기 JDBC 조회를 Entity 매핑과 DTO projection으로 전환했으며 필요한 열·계층 JOIN·keyset·최대 반환량은 유지합니다. Entity를 API로 반환하지 않고 OSIV를 비활성화했으며 Flyway 스키마를 validate합니다. Mock의 원자적 변경/이력과 제한된 이력 삭제는 JPA native query를 사용합니다. [전환 이유·N+1 방지·실제 SQL 검증](docs/JPA_QUERY_DESIGN.md)을 참고합니다. Vue는 탐색 상태와 상세 패널을 구성합니다. 고정 높이 가상 리스트·서버 페이지·캐시 상한을 적용하고 미사용 TanStack Virtual은 제거했습니다. [화면 설계](docs/FRONTEND_RENDERING.md)를 참고합니다. 사용자 요청으로 선택형 Keycloak/표준 Resource Server 권한 모드를 추가했으며 기본 과제 모드와 구분합니다. 메시지 브로커·실제 Kubernetes 연동은 제외합니다.

## 화면 사용

1. 처음에는 전체 Pod의 첫 100개와 Cluster의 첫 50개를 요청합니다. 전체 수는 별도 건수 API로 표시합니다.
2. 왼쪽 `+`로 직속 하위 항목을 조회하고, `→`로 가운데 목록의 조회 범위를 해당 부모로 바꿉니다.
3. 가운데 이름 검색은 250ms 디바운스 후 선택한 유형을 검색합니다. `조회 범위`에서 Cluster·Namespace를 지정할 수 있으며 이름·상태를 바꿔도 범위를 유지합니다. 검색어가 있으면 `모든 유형`도 선택할 수 있습니다. 상태는 서버에서 함께 필터링합니다.
4. 행을 누르면 오른쪽에 상세·속성·전체 경로가 표시됩니다. `계층에서 위치 보기`는 조상을 펼치고 검색된 항목으로 이동합니다.
5. 아래쪽 스크롤 또는 `다음 100개`로 순차 탐색합니다. 최근 5페이지만 유지하므로 오래된 앞부분은 `처음부터`로 다시 조회합니다. 임의 위치 점프는 제공하지 않습니다.
6. `새로고침`은 현재 창을 다시 받아 한 번에 교체합니다. 자동 갱신은 완료 5초 뒤 같은 범위를 다시 조회합니다. 실패 시 기존 화면을 유지하고 최대 60초 간격으로 재시도합니다. 선택 항목과 스크롤을 유지합니다.
7. 유형별 좌측 메뉴·건수 카드로 전역 목록을 바로 조회할 수 있습니다. 상태 요약은 현재 조회창만 집계합니다. `/`로 검색 포커스, `Escape`로 상세 닫기, 개요·관계 탭은 좌우 방향키로 이동합니다. 모바일 계층은 기본 접힘이며 버튼으로 펼칩니다.
8. 요청이 10초 동안 완료되지 않으면 시간 초과 안내를 표시합니다. 초기 조회는 `다시 조회`, 갱신은 기존 화면을 유지한 채 재시도합니다. 이 제한은 개별 요청 기준이며 전체 갱신 그룹의 제한은 아닙니다.

## 검증

개발 작업은 [개발 가이드라인](docs/DEVELOPMENT_GUIDE.md)을 따릅니다. 새 작업 전에 AGENTS·기준 문서·관련 테스트를 확인하고 아래 빠른 검사를 실행합니다. [품질 게이트](docs/QUALITY_GATES.md)에 자동 검사 범위와 한계를 정리했습니다.

```bash
bash scripts/check.sh fast
# Docker·의존성 설치가 가능한 환경의 전체 서버/프론트 검사
bash scripts/check.sh full
```

빠른 검사는 Python 3.9+와 Git만 사용하며 서버·DB를 시작하지 않습니다. Wrapper 바이너리·공식 배포 URL/체크섬·필수 잠금 파일도 확인합니다. 전체 모드는 아래 Gradle·프론트 검사를 실행하지만 실제 화면 E2E·성능은 별도입니다. CI/Jenkins도 같은 빠른 게이트를 먼저 실행합니다.

```bash
./backend/gradlew -p backend --no-daemon --console=plain clean build
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

짧고 흔한 검색어·전체 유형 검색·8개 부모 펼침·최대 조회창의 자동 갱신을 추가 측정합니다. 자동 갱신의 폴링 대기와 화면 반영 지연을 나눠 기록하며 과제 DB의 Pod 1은 동시 수정이 없을 때 테스트 후 복원합니다.

```bash
cd frontend
PERF_LABEL=edge-check npm run test:performance:edges
```

[추가 측정 결과와 원격 환경 제약](docs/PERFORMANCE_EDGE_RESULTS.md)을 참고합니다. Mac이 잠기면 headless 검증은 가능하지만 Chrome 작업관리자의 실제 탭 메모리 확인은 잠금 해제 후 진행해야 합니다.

대표 SQL 실행 계획은 프로젝트 루트에서 확인합니다.

```bash
docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/explain.sql
```

- [구현 계획과 설계](docs/IMPLEMENTATION_PLAN.md)
- [전체 작업 이력·검증 기록·남은 과제](docs/WORK_HISTORY.md)
- [개발 가이드라인·구조/코드/보안/검증/완료 규칙](docs/DEVELOPMENT_GUIDE.md)
- [개발 하네스·품질 게이트·자동 검사 범위](docs/QUALITY_GATES.md)
- [Gradle 빌드·Wrapper·의존성 잠금·CI 전환](docs/GRADLE_BUILD.md)
- [Keycloak·세 계정·클러스터별 조회 권한·비밀값·실제 검증](docs/AUTHENTICATION.md)
- [성능 측정 프로토콜](docs/PERFORMANCE.md)
- [실제 성능 결과·개선 전후·남은 점검](docs/PERFORMANCE_RESULTS.md)
- [추가 검색·다중 계층·자동 갱신 검증](docs/PERFORMANCE_EDGE_RESULTS.md)
- [프로젝트 기준 문서와 초기 검증 결과](docs/PROJECT_GUIDE.md)
- [1단계 구동 검증 보고서](docs/VALIDATION_REPORT.md)
- [리소스 API 계약](docs/API.md)
- [JPA Entity·DTO projection·트랜잭션·N+1 및 실제 SQL 검증](docs/JPA_QUERY_DESIGN.md)
- [초기 진입 최적화·CPU profile·중간 실패와 최종 결과](docs/STARTUP_PERFORMANCE.md)
- [2단계 조회 구현·검증 보고서](docs/STAGE2_REPORT.md)
- [화면 렌더링·캐시·요청 처리 설계](docs/FRONTEND_RENDERING.md)
- [3단계 화면 연결·검증 보고서](docs/STAGE3_REPORT.md)
- [Mock 상태 변경·자동 갱신 설계](docs/LIVE_UPDATES.md)
- [실제 DB·브라우저·자동 갱신 검증 결과](docs/STAGE4_REPORT.md)
- [운영 도구 참고·콘솔 UI 설계 및 검증](docs/UI_DESIGN.md)

## 다음 점검

1. 실제 Chrome 작업관리자 탭 메모리와 수동 스크롤·자동 갱신의 Performance 기록 최종 확인
2. 추가 경계 성능 측정은 완료: 검색 100회·계층 펼침 160회·자동 갱신 20회. 결과는 [추가 보고서](docs/PERFORMANCE_EDGE_RESULTS.md)에 기록
3. 과제 가이드에 맞춰 순차 커서 탐색 UX와 실행·검증 증거 최종 점검
4. fixture Git 체크아웃 검증은 완료했습니다. 실제 제출 커밋·원격 연결 후 Jenkins의 SCM 연결과 제출 자료를 최종 확인합니다. 원격 webhook은 아직 미구현입니다.

초기 구성부터 기능·검증·문서까지 다섯 단계의 로컬 커밋이 있습니다. 이후 성능 경계 검증, 범위 필터·이력·관측·Compose CI 확장, JPA 전환, 초기 진입 개선과 작업 이력·개발 하네스·Gradle 전환은 아직 커밋하지 않았습니다. 푸시는 하지 않았으며 원격 연결은 별도 확인이 필요합니다. [전체 작업 이력](docs/WORK_HISTORY.md)과 [단계별 커밋](docs/PROJECT_GUIDE.md#단계별-커밋과-제출-전-상태)을 참고합니다.
