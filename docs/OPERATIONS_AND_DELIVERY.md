# 범위 필터·변경 이력·Prometheus·Compose CI

이 문서는 독립 Infrastructure Monitoring Service의 확장 기능과 로컬 실행·검증 경계를 설명합니다. 실제 Kubernetes 클러스터에는 연결하지 않습니다.

개발·CI 공통 규칙은 [개발 가이드](DEVELOPMENT_GUIDE.md), 검사 단계와 한계는 [품질 게이트](QUALITY_GATES.md)를 따릅니다. 전체 `scripts/ci.sh`는 컨테이너 생성 전에 `bash scripts/check.sh fast`로 문서·일부 소스 안전 경계·마이그레이션 변경과 게이트 자체 회귀 테스트를 검사합니다. 이 절차 추가는 새 Jenkins/GitHub 실행 성공을 뜻하지 않으며 기존 빌드 결과와 구분합니다.

## 1. Cluster / Namespace 범위 필터

현재 기본 15만 Pod에서 이름·상태만 검색하면 다른 클러스터의 동일한 이름도 섞일 수 있습니다. 화면의 `조회 범위`에서 Cluster와 Namespace를 지정하면 해당 범위 안에서 목록·이름 검색·상태 필터를 함께 적용합니다.

```text
조회 범위 열기 → Cluster 목록 지연 조회
Cluster 선택 → 해당 Cluster의 Namespace 검색·페이지 조회
Namespace 선택 → Pod 목록 범위 적용
이름·상태 변경 → 범위는 유지, 페이지·커서 초기화
```

- Cluster 목록은 초기 화면에서 추가로 요청하지 않습니다. Namespace는 최대 100개씩 조회하고 이름 검색과 다음 페이지를 제공합니다.
- 서버는 `clusterId`, `namespaceId`를 바인딩한 SQL 조건으로 적용합니다. Namespace와 Cluster의 소속 관계, 부모 조건과의 충돌을 검증합니다.
- Namespace 범위는 Namespace 또는 Pod 조회에 사용합니다. 전체 유형 검색에서는 이 두 유형만 검색합니다. Cluster/Node 조회와 함께 지정하면 400입니다.
- 없는 범위는 404, 서로 모순된 조건은 400입니다. 커서도 범위에 연결하므로 범위가 달라지면 재사용할 수 없습니다.
- 이는 탐색 필터이며 사용자 권한·멀티 테넌시를 제공하는 기능은 아닙니다.

예: `GET /api/resources?type=POD&clusterId=2&namespaceId=101&limit=100`. 현재 기본 seed에서는 Cluster 2의 Namespace 101 아래 Pod 100개를 조회합니다. 이름·상태 조건을 추가하면 이 100개 안에서만 검색합니다.

## 2. 상태·속성 변경 이력

현재 값만으로는 상태 변화의 순서를 확인하기 어렵습니다. Mock 배치가 리소스를 변경할 때 변경 전후 상태와 속성을 같은 트랜잭션으로 `resource_change_event`에 기록합니다. Flyway V3가 테이블과 검색·정리 인덱스를 생성합니다.

```text
Mock 배치 → 변경 대상 행 잠금 → 변경 전 상태·속성 확보
          → 리소스 갱신 → 변경 후 상태·속성과 이력 INSERT
          → 모두 성공하면 COMMIT / 실패하면 모두 ROLLBACK
상세 패널 → 변경 이력 열기 → 최신 20개 → 이전 이력 커서 조회
```

이력에는 유형·리소스 ID·시각·이전/다음 상태·이전/다음 속성·발생원 `MOCK_SIMULATOR`를 저장합니다. Node CPU·메모리나 Pod 재시작 횟수 변화도 확인합니다. 리소스 상태가 같더라도 속성 변화가 있으면 기록합니다. 읽기 API는 `GET /api/resources/{type}/{id}/events?limit=20&beforeId=...`이며 최대 100개, ID 내림차순입니다.

읽기 경로는 `ResourceHistoryController → ResourceHistoryService → ResourceService.detail(존재·Cluster 접근 확인) → ResourceHistoryRepository.list`입니다. Service가 입력 검증과 `limit+1` 조회·페이지/커서 조립을 맡고, HTTP 입력 바인딩은 Controller에 남깁니다. 이력 행 자체에는 Cluster 권한 정보가 없으므로 접근 확인 이전에 이력을 읽지 않습니다. 기존 `detail`/Repository의 읽기 전용 트랜잭션 경계를 유지해 페이지 검증은 DB 연결 없이 처리합니다. 연결 풀이 고갈되어도 잘못된 페이지는 400, 유효한 조회는 기존 안전한 503이며 연결 반환 후 다시 조회할 수 있습니다.

### 보관 정책

- 기본 보관은 **24시간 이내이며 전체 리소스 합산 최신 20,000개**입니다. 둘 중 먼저 도달하는 조건으로 오래된 이력을 정리합니다.
- 60초마다 최대 5,000개를 삭제합니다. 따라서 배치 사이 또는 정리 실패·적체 시 일시적으로 상한을 초과할 수 있습니다. 엄격한 실시간 개수 제한이 아닙니다.
- 기본 Mock는 약 5초마다 최대 116개를 변경하므로 수량 제한에 먼저 도달할 수 있습니다. 전체 변경을 영구 보존하는 감사 로그가 아닙니다. 오래전에 변경된 Pod에는 현재 보관 중인 이력이 없을 수 있습니다.
- 상태 변경은 `mock` 프로필에서만 실행합니다. 이력 정리는 기본 모드에서도 실행합니다.
- seed와 직접 SQL 변경은 이력을 만들지 않습니다. 실제 Kubernetes Event·OOM 원인·사용자 감사 이력으로 설명하지 않습니다.

설정: `monitoring.history.retention-hours`, `max-events`, `cleanup-batch`. 장기 보관을 늘리면 DB 디스크와 인덱스 비용을 함께 검토해야 합니다.

## 3. Prometheus 운영 관측

Mock 리소스의 CPU 값과 **서비스 자체의 실제 운영 지표**를 구분합니다. Spring Boot Actuator + Micrometer가 계측하고 Prometheus가 시계열로 수집합니다. 화면의 `운영 관측`은 DB 집계가 아닌 Prometheus 조회 결과를 표시합니다.

```text
Spring Boot HTTP / JPA 조회 / Mock 배치 / JVM / HikariCP
     → Micrometer 계측 → 관리 포트 /actuator/prometheus
     → Prometheus 수집·보관
     → 서버의 고정 PromQL 조회 → 운영 카드 10개·P95 추이 차트 2개
```

| 표시 | 의미 |
|---|---|
| 수집 대상 상태 | `up=1`은 수집 성공, `up=0`은 수집 실패. 누락/비유한 값은 표본 없음, 예상 밖의 유한 값은 확인 불가 |
| 최근 5분 요청 수 | 리소스 API Counter의 `increase`, 추정 증가량 |
| API P95 | 리소스 HTTP 처리 시간 Histogram의 최근 5분 근사 95백분위 |
| DB 조회 P95 | 목록·검색 JPA Repository 호출 구간 Timer의 최근 5분 근사 95백분위 |
| 최근 5분 5xx | 리소스 API 서버 오류 Counter 증가량 |
| JVM Heap | 실제 백엔드 JVM heap 사용량 |
| DB 활성 연결 | HikariCP 활성 연결 수, 전체 DB 연결 수와 다름 |
| 최근 5분 Mock 변경 행 | 커밋 성공 후 누적 Counter의 추정 증가량 |
| 최근 5분 Mock 실패 | 실패 배치 Counter 증가량 |
| Mock 배치 P95 | 트랜잭션 처리를 포함한 배치 Timer의 95백분위 |

수집 성공은 관리 endpoint의 지표 응답을 읽었다는 뜻이며 모든 API·DB 기능의 정상을 보장하지 않습니다. 카드 표현만 바꾸고 API의 숫자형 `up` 계약은 유지합니다. Prometheus가 리소스 테이블을 직접 조회하는 구조가 아니라 HTTP 계측·JVM 런타임·HikariCP 상태를 Actuator에서 읽습니다. heap과 활성 연결은 Mock 값이 아닙니다. 별도 PostgreSQL exporter는 구성하지 않았습니다.

P95는 느린 요청이나 예외를 제거한 평균이 아닙니다. 정의한 구간의 약 95%가 그 이하라는 분포 지표이며 현재 API 조회식은 성공 상태만 골라내지 않습니다. 나머지 5%의 지연과 오류도 별도로 확인해야 합니다. heap은 GC 후 추세·최대치·부하와 함께 판단하고 활성 연결은 현재 빌려준 풀 연결 수로 읽습니다. 상세 예시와 모드/기술 선택 근거는 [질문별 학습 문서](STUDY_QUESTIONS.md)에 정리합니다.

API·DB P95 차트는 최근 30분을 표시합니다. DB Timer는 JPA 조회 구성·SQL 왕복·DTO 조립을 포함한 Repository 호출 구간이며 순수 DB 내부 실행 시간은 아닙니다. 전체 유형 검색은 최대 4개의 SQL을 합친 구간으로 기록합니다. 범위 유효성 검증과 모든 상세 SQL은 포함하지 않으며 HTTP Timer와 구간이 다릅니다. P95는 Histogram 버킷으로 계산한 근삿값이고 브라우저 렌더링 시간이나 과제의 500ms 목표와 같지 않습니다. `increase`는 수집 간격을 외삽하므로 정수 Counter도 소수로 나올 수 있습니다.

표본 부족·NaN은 값 없음, Prometheus 장애는 연결 오류로 표시합니다. 임의의 DB 값이나 가짜 0으로 대체하지 않습니다. 실제 요청 표본이 있지만 5xx 시계열만 없을 때는 오류 0을 표시합니다. 화면을 닫거나 숨기면 폴링을 중지하고 실패 시 백오프합니다. 운영 화면에서는 리소스 목록의 정기 폴링을 중지합니다.

### 안전 경계와 자원 제한

- 브라우저는 `/api/operations/metrics`만 요청합니다. PromQL·대상 URL을 전달할 수 없습니다. 서버에 정의한 10개 지표와 두 시계열만 조회합니다.
- `PrometheusOperations`는 고정 허용 목록으로 instant/range PromQL을 클래스 초기화 때 한 번 조립·URL 인코딩합니다. 매번 조회 시각만 계산하며 최신 응답을 캐싱하지 않습니다. 정상 조회당 기존처럼 instant 1회·range 1회, 합계 2회 요청하고 값·시계열 변환을 별도 메서드로 처리합니다. 조회식·표시 지표·시간 범위는 변경하지 않습니다.
- Prometheus 대상은 설정된 루프백 또는 Compose 내부 `prometheus`만 허용하며 리다이렉트를 따르지 않습니다. 응답별 본문 1MiB, 본문 수신까지 3초, 범위 시계열 지표별 최대 31점을 제한합니다.
- 리소스 이름·ID·검색어를 지표 label에 넣지 않아 시계열 수 증가를 막습니다.
- Actuator는 API와 별도 관리 포트 8086입니다. 호스트에서는 루프백, 전체 Compose에서는 내부망만 사용하고 NGINX는 `/actuator`를 공개하지 않습니다.
- Prometheus 기본 보관은 **7일 또는 256MB 제한**입니다. 볼륨이 있으므로 컨테이너 재시작 후 유지되지만 보관 기한·용량 제한이나 볼륨 삭제 시 사라집니다.
- 기본 과제 모드의 화면/API에는 사용자 인증이 없습니다. 별도 `compose.auth.yaml`은 [Keycloak/Cluster 권한](AUTHENTICATION.md)을 적용합니다. 루프백 바인딩은 인터넷 공개 인증을 대체하지 않으며 인증 모드도 공개 운영용 TLS·접근 제어 검토가 필요합니다.

## 4. 실행 모드

제출 시연의 단일 시작 명령·정지·계정 확인과 개발 환경 준비는 [실행 가이드](RUNNING.md)에 정리합니다. 아래 모드를 동시에 실행할 필요는 없습니다.

| 모드 | UI | API | 관리 포트 | Prometheus | DB |
|---|---|---|---|---|---|
| 호스트 개발 | 5174 | 8081 | 8086 루프백 | 9094 | 5544 공개 로컬 개발 DB |
| 전체 Compose | 5175 | 8082 | 8086 내부 전용 | 9092 | 별도 볼륨·내부 전용 |
| 격리 CI | 5185 | 8089 | 내부 전용 | 9093 | CI 전용 볼륨 |
| Jenkins | 8182 | 해당 없음 | 해당 없음 | 해당 없음 | Jenkins 전용 볼륨 |
| 선택형 인증 | 5176 | 8083 | 8086 내부 전용 | 9095 | PostgreSQL 한 서버·monitoring/identity 별도 DB, Keycloak 8183 |

모든 공개 포트는 `127.0.0.1`에 바인딩합니다. 전체 Compose는 개발 DB를 재사용하지 않습니다. 각각 seed된 Mock 데이터이며, 한쪽 데이터 변경이 다른 모드에 자동 반영되지 않습니다.

전체 Compose:

```bash
docker compose -f compose.runtime.yaml --profile monitoring up -d --build
docker compose -f compose.runtime.yaml --profile monitoring ps
```

DB health → Flyway 백엔드 health → 15만 건 idempotent seed 완료 → NGINX 화면 순서로 기동합니다. JDK+Gradle Wrapper/Node 빌드 단계를 분리하고 백엔드·NGINX는 비 root 사용자로 실행합니다. `monitoring` 프로필을 빼면 Prometheus는 실행하지 않으며 운영 화면은 연결 오류를 표시합니다. runtime 백엔드는 Mock 프로필로 시작합니다. [Gradle 전환](GRADLE_BUILD.md)을 참고합니다. 기존 seed 서비스가 이미 완료된 환경은 backend/DB health 확인 후 `docker compose -f compose.runtime.yaml run --rm --no-deps seed`를 명시적으로 다시 실행합니다. 기존 행은 덮어쓰지 않습니다.

호스트 백엔드는 `bash scripts/run-local-backend.sh`로 기동할 수 있습니다. Gradle bootJar를 `.local/runtime/`의 고유 파일명으로 복사해 실행하므로 CI가 `build`를 다시 빌드해도 실행 중인 JAR가 덮어써지지 않습니다. CI와 병행할 때 `build/libs` JAR를 직접 실행하지 않습니다. 일반 개발에는 `./backend/gradlew -p backend bootRun`도 가능합니다.

## 5. CI / Jenkins

최신 검증은 fixture 커밋 독립 체크아웃의 **Jenkins 빌드 15 SUCCESS**입니다. 서버 107(실제 PostgreSQL 44)·프론트 77·빠른 검사 46·기본 E2E 11·실제 Keycloak 5·경보 9시나리오·실제 복구와 SHA/build/이미지/실행 컨테이너 일치를 통과했습니다. 공개 이미지용 CI 클라이언트를 개인 키체인과 분리하고 macOS의 호스트/VM socket 차이를 처리했습니다. 기존 서비스·설정은 보존하며 Docker 전체를 재시작하지 않았습니다. [최신 실행 근거](verification/2026-10-16-public-image-ci.json), [분리 설계와 한계](CI_REPRODUCIBILITY.md#공개-이미지와-개인-docker-설정-분리)를 참고합니다. 원본 커밋·푸시와 원격 SCM/webhook은 별도이며 이전 빌드 11과 도입 당시 기록은 보존합니다.

후속 확장은 [인증 CI](AUTHENTICATION_CI.md)와 [경보 규칙·실제 중단/복구 검증](ALERTING_AND_RECOVERY.md)입니다. promtool/amtool 검사와 기본 화면 검증 후 CI 백엔드만 중단해 firing·독립 수신·재기동·resolved·10만 데이터 보존을 확인합니다. 이후 별도 CI Keycloak에서 실제 로그인/권한을 검증하고 CI 컨테이너를 정리합니다. 인증 JUnit/요약과 복구 수치 요약을 수집합니다. Keycloak은 Jenkins 인증/배포 실행자가 아닌 서비스 로그인 검증 대상이며 기존 개발·인증 DB를 초기화하거나 외부 메일을 보내지 않습니다. 아래 도입 당시 검증과 최신 실행 결과는 구분합니다.

`bash scripts/ci.sh`는 새 깨끗한 Git 체크아웃에서 다음 흐름을 로컬 재현합니다. CI 실행자는 Jenkins 하나이며 같은 단계 함수를 호출합니다. Actions workflow는 제거했습니다. [커밋 고정·이미지 추적](CI_REPRODUCIBILITY.md)을 참고합니다.

```text
전체 SHA/tree 기록 → 빠른 검사·경보 규칙 → Gradle Wrapper clean build (실제 PostgreSQL)
 → npm ci / Vitest / 타입 검사 / Vite / npm audit
 → SHA/build 태그·label 이미지 빌드 / 격리 Compose / seed·첫 Mock 커밋·실행 이미지 확인
 → 실제 API Chromium E2E / Prometheus up 확인
 → 실제 장애/복구·독립 알림 → 격리 Keycloak E2E → 소스·이미지 최종 확인
 → 성공·실패 시 CI 컨테이너·네트워크 정리 / provenance·JUnit 수집
```

CI 프로젝트명은 `infrastructure-monitoring-ci` 또는 제한된 접미사만 허용합니다. 개발·runtime 프로젝트에 `down`하지 않습니다. 자동 `down -v`는 사용하지 않아 CI 볼륨은 남습니다. 이 파이프라인은 로컬 컨테이너 실행 검증이며 퍼블릭 클라우드 배포·운영 롤백·GitOps까지 구현한 것은 아닙니다.

Jenkins는 `python3 scripts/jenkins-local.py`로 controller·로컬 agent·검증 job을 구성하고 파이프라인을 실행합니다. 호스트 Java 21·Node 22·Docker와 Python/Git이 필요하며 Gradle은 저장소 Wrapper를 사용합니다. JUnit XML은 `backend/build/test-results/test`에서 수집합니다. controller는 루프백 8182, 익명 API 접근은 차단하며 controller executor는 0입니다. WebSocket agent가 호스트에서 실행합니다.

- controller에는 Docker socket을 마운트하지 않습니다. **agent는 현재 호스트 사용자와 Docker 권한을 갖습니다.** 신뢰하는 로컬 소스만 실행하고 외부 PR을 이 agent에서 무검증 실행하지 않습니다.
- 관리자 비밀번호와 agent secret은 `.local/`의 Git 제외 파일에 0600 권한으로 저장합니다. 값은 콘솔이나 문서에 출력하지 않습니다. 부트스트랩 scriptText는 인증된 로컬 관리자 작업입니다.
- Jenkinsfile은 신뢰하는 로컬 Git 저장소의 전체 SHA를 빌드별 독립 공간에 clone·checkout합니다. 미커밋 파일은 포함하지 않으며 원격 SCM 자격 증명·webhook은 아직 연결하지 않았습니다.
- Jenkins에서 테스트 결과·커밋/tree·build/image ID·실행 이미지 확인 산출물을 보관합니다. GitHub는 소스 저장소로 사용할 수 있지만 Actions CI는 운영하지 않습니다. Jenkins 원격 SCM/webhook은 아직 별도 연결 대상입니다.
- 이미지 버전 태그와 npm 잠금 파일을 사용합니다. 모든 컨테이너·Jenkins plugin이 digest/버전 고정된 완전 재현 빌드인 것은 아닙니다.

## 6. 초기 JDBC 선택과 현재 JPA 전환

초기에는 고정된 4단계 계층의 읽기 중심 요구에 맞춰 JdbcTemplate으로 필요한 열·JOIN·keyset을 직접 관리했습니다. JDBC가 JPA보다 항상 빠르다는 판단은 아니었습니다.

현재는 공고의 JPA/Hibernate 요구와 사용자 요청에 따라 Entity 매핑·JPA DTO projection으로 전환했습니다. 부모 LAZY·Entity 응답 금지·OSIV off·readOnly·keyset·조회량 상한을 유지합니다. 전체 유형 검색은 기존 단일 UNION 대신 최대 4개의 제한된 DTO 쿼리를 사용하고 최대 101개까지만 반환합니다. Mock의 원자적 CTE와 보관 삭제는 JPA native query로 유지합니다. 실제 DB에서 SQL 횟수·Entity 로딩 0·인덱스·timeout을 검증합니다. [전환 설계·제약·검증](JPA_QUERY_DESIGN.md)을 참고합니다. 아래 빌드 4와 `feature-extension` 측정은 JPA 전환 전 JDBC 기준이며 최신 JPA 결과와 구분합니다.

## 검증 근거 — 2026-10-14

후속 인증 추가의 **Jenkins 빌드 8 SUCCESS**: Gradle 96개·Vitest 73개·기본 실제 API E2E 11개, 이미지·격리 Compose·Prometheus·정리를 확인했습니다. 별도 인증 프로젝트에서는 실제 Keycloak E2E 5개로 관리자/A/B와 교차 조회·로그아웃을 검증했습니다. [인증 문서](AUTHENTICATION.md)를 참고합니다. 빌드 8은 기존 비인증 runtime 회귀와 서버 인증 테스트를 포함하며 실제 Keycloak 브라우저 환경은 별도 실행입니다. 아래 빌드 7·6 등은 이전 단계 기록입니다.

**Gradle 전환 후 최신 Jenkins 빌드 7 SUCCESS**: Wrapper 기반 clean build의 87개·Vitest 66개·실제 API Chromium 11개, 이미지·격리 Compose·Prometheus·CI 정리를 통과했습니다. 새 `build/test-results/test` XML 수집·익명 차단·비밀값 미노출을 확인했습니다. 호스트 Gradle 실행 스크립트도 기동·health·기존 10만 건을 확인했습니다. 빌드 속도·브라우저 성능은 이번 전환에서 비교하지 않았습니다. [Gradle 기록](GRADLE_BUILD.md)을 참고합니다. 아래 빌드 6·5·4는 Maven 당시 기록입니다.

초기 진입 추가 개선 당시 **Jenkins 빌드 6 SUCCESS**: Maven 87개·Vitest 66개·실제 API Chromium 11개, 전체 이미지·격리 Compose·Prometheus·정리를 재통과했습니다. 최종 초기 진입은 총 60회 모두 500ms 이내였습니다. [성능·기능·중간 실패 기록](STARTUP_PERFORMANCE.md)을 참고합니다. 아래 빌드 5·4는 해당 단계의 기록으로 유지합니다.

최신 JPA 버전은 **Jenkins 빌드 5 SUCCESS**, Maven 87개(실제 PostgreSQL 30개 포함)·Vitest 61개·실제 API Chromium 10개를 통과했습니다. 이미지 빌드·격리 Compose·Prometheus up·CI 정리·비밀값 미노출도 확인했습니다. JPA의 실제 SQL·N+1·매핑·timeout 근거는 [전환 문서](JPA_QUERY_DESIGN.md)를 참고합니다. 아래 기존 79개·빌드 4·`feature-extension` 성능은 JPA 전환 전 기록입니다.

JPA 버전의 기본 브라우저 성능도 동일 조건으로 재측정했습니다. 초기 20회 중 첫 1회만 500ms를 초과했으며 다른 탐색 목표 초과는 없었습니다. [전후 비교·측정 한계](JPA_QUERY_DESIGN.md#전환-전후-브라우저-성능)를 참고합니다.

- Maven 79개 통과: 실제 PostgreSQL Testcontainers 22개 포함, 실패·오류·skip 0.
- Vitest 61개·타입 검사·프로덕션 빌드 통과, 실제 DB/API Chromium E2E 10개 통과.
- Flyway V1~V3·10만 건 seed·범위 필터·이력 커밋/롤백/정리·고정 PromQL·응답 상한/시간 제한 검증.
- 전체 Compose의 DB·백엔드·NGINX·Prometheus를 기동하고 실제 `up=1` 및 운영 지표 응답을 확인했습니다.
- 로컬 Jenkins **빌드 4 SUCCESS**: Maven 79개·Vitest 61개·Chromium 10개·전체 이미지 빌드·격리 Compose·Prometheus up 확인·CI 정리를 완료했습니다. 익명 접근 차단·콘솔 비밀값 미노출도 검사했습니다.
- 이전 CPU 4x 성능 보고서는 확장 전 기준입니다. 확장 후 아래 동일 조건으로 다시 측정했으며 실제 Chrome 작업관리자 탭 메모리는 Mac 잠금으로 미확인입니다.

### 확장 후 기존 탐색 성능 재측정

`PERF_LABEL=feature-extension npm run test:performance`로 호스트 프로덕션 preview·실제 PostgreSQL에서 CPU 4x·캐시 OFF·1440×1000·20회씩 측정했습니다. 아래는 기존 탐색 동작 회귀 확인이며 새 범위 필터와 운영 지표의 성능 목표를 검증한 수치는 아닙니다.

| 동작 | P95(ms) | 최대(ms) | 목표 초과 |
|---|---:|---:|---:|
| 초기 진입 | 366.3 | 645.3 | 1/20 (500ms 목표) |
| 미캐시 펼침 | 45.9 | 57.7 | 0/20 |
| 캐시 펼침 | 30.4 | 30.9 | 0/20 |
| 접기 | 29.9 | 30.5 | 0/20 |
| 선택적 이름 검색 | 66.3 | 68.2 | 0/20 |
| 상태 필터 | 50.0 | 83.3 | 0/20 |
| 현재 페이지 갱신 | 60.8 | 61.2 | 0/20 |
| 5페이지 갱신 | 80.9 | 81.1 | 0/20 |

첫 초기 진입에서 422ms long task가 관측됐습니다. 정확한 원인을 단정하지 않으며 첫 진입 JS 실행·렌더링 추가 프로파일링이 필요합니다. 표본을 제외하거나 전부 통과했다고 표현하지 않습니다. 측정 오류는 0, 합성 스크롤 compositor는 약 59.85FPS, 추가 100페이지 후 캐시 500개·표시 행 14개였습니다. 합성 FPS와 renderer RSS는 실제 사용자 스크롤·Chrome 작업관리자 탭 메모리를 대체하지 않습니다.

원본 JSON·trace는 Git 제외 `frontend/.validation/performance/feature-extension*.json`, 휴대 가능한 요약은 [측정·CI 요약](performance/2026-10-14-feature-summary.json)에 저장했습니다. 다음 성능 개선은 첫 진입 실행 구간 분석과 비초기 화면 코드 지연 로딩 검토입니다.

API 세부 계약은 [API.md](API.md), 전체 기능은 [PROJECT_GUIDE.md](PROJECT_GUIDE.md), 초기 성능 근거는 [PERFORMANCE_RESULTS.md](PERFORMANCE_RESULTS.md)를 참고합니다.
