# 프로젝트 기준 문서

이 문서는 Infrastructure Monitoring Service의 현재 기능, 구조, 처리 흐름과 검증 상태를 관리합니다. 기능과 실행 방식이 바뀔 때 README 및 관련 설계 문서도 함께 갱신합니다.

초기 구성부터 시행착오·검증·JPA 전환·성능 개선까지의 누적 기록은 [작업 이력](WORK_HISTORY.md)에서 관리합니다. 이후 프로젝트 변경·검증·설계 결정이 있는 작업은 [저장소 작업 규칙](../AGENTS.md)에 따라 이력에 새 항목을 추가하고 이 기준 문서도 함께 갱신합니다.

## 목적과 범위

Mock 인프라 데이터를 실제 DB에 저장하고 대용량 리소스 탐색 성능을 검증하는 독립 과제입니다. 목표 규모는 Cluster 20개, Node 200개, Namespace 2,000개, Pod 100,000개입니다. 실제 Kubernetes 연동은 하지 않습니다.

## 현재 구조와 처리 흐름

```text
scripts/seed.sql → PostgreSQL의 4개 계층 테이블
Vue 건수·계층·목록·상세·검색 화면 → Vite /api 프록시 → Spring Boot → JPA DTO 조회
목록 응답 → 최근 5페이지 캐시 → 보이는 행 + 오버스캔만 DOM 표시
탐색·검색 요청 → 요청값·커서 검증 → JPA/Hibernate 관계 JOIN → DTO·경로·다음 커서 반환
Spring Boot 기동 → Flyway 마이그레이션 → Hibernate Entity/스키마 validate
Gradle Wrapper·strict lockfile → JUnit 통합 테스트 → Testcontainers PostgreSQL → Flyway 스키마 확인
선택형 mock 프로필 → JpaTransactionManager·native CTE → 상태·속성·updated_at 변경
                                      → 같은 트랜잭션의 resource_change_event
화면 폴링 → 현재 조회 창·펼친 부모·선택 상세 재조회 → 키·선택·스크롤 유지
조회 범위 선택 → Cluster·Namespace 소속 검증 → 범위 JOIN·이름·상태 SQL
Actuator·Micrometer → Prometheus → 고정 PromQL → 운영 관측 카드·추이 차트
전체 Compose → 내부 DB·백엔드·seed·NGINX·선택형 Prometheus
CI / 로컬 Jenkins → 전체 Git SHA 고정·새 체크아웃 → 단계별 검증
  → SHA/build 이미지·실제 컨테이너 ID 확인 → E2E·인증·복구 → 증거·정리
선택형 인증 → Keycloak PKCE 로그인 → JWT 검증 → client role·그룹 → JPA Cluster 조건/범위 집계
격리 인증 CI → tmpfs DB·임시 Keycloak 계정 → 실제 로그인/권한 → 안전한 JUnit → 정리
Prometheus 3경보 → Alertmanager → 독립 inbox firing/resolved
관리자 경보 API/UI → 알려진 규칙의 상태·조건·대응, 소스/평가 오류는 확인 불가
```

건수·목록·하위 목록·상세·경로·검색·상태 필터 API와 화면, 가상 리스트, 제한된 캐시, 수동·자동 갱신, 선택형 Mock 상태 변경을 구현했습니다. 실제 PostgreSQL 17에 10만 건을 적재했고 Testcontainers와 실제 API의 Chromium E2E를 검증했습니다. 실제 DB에 연결한 CPU 4x·캐시 OFF 반복 측정, 실행 계획과 메모리 보조 지표를 [성능 결과](PERFORMANCE_RESULTS.md)에 기록했습니다.

| 기능 | 서버 구현 | 화면·실제 DB 검증 |
|---|---|---|
| 리소스 규모 | 20 Cluster·200 Node·2,000 Namespace·100,000 Pod | 실제 적재·재적재·브라우저 건수 확인 |
| 목록·하위 탐색 | 부모별 조회, ID 커서, 최대 100개 | Testcontainers·Chromium E2E 통과 |
| 상세·경로 | 유형별 속성과 최대 4단계 경로 | 실제 속성·경로·트리 위치 확인 |
| 검색·필터 | 이름 부분 검색·상태·유형 조건 | 실제 DB 검색·필터·빈 결과 확인 |
| 응답·입력 정책 | 요청·커서 검증, 400·404·503 | 정식 단위/MVC·DB 테스트 통과 |
| 수동·자동 갱신 | 기존 조회 API 재사용 | DB 변경 자동 반영·선택·스크롤 유지 확인 |
| Mock 변경 | 기본 비활성, 최대 116행 트랜잭션 | 배치·롤백·커서·속성·재시작 상한 검증 |
| 콘솔 UI | 기존 API 사용, 추가 집계·변경 API 없음 | 유형 바로가기·상세 탭·단축키·모바일 접기 검증 |
| 요청 대기 제한 | 서버 SQL별 3초, 클라이언트 요청별 10초 | 시간 초과·사용자 취소 구분·타이머 정리 검증 |
| 추가 성능 검증 | 제품 API 재사용, 외부 서비스 없음 | 검색 100회·계층 펼침 160회·자동 갱신 20회; 실제 탭 메모리는 Mac 잠금으로 미확인 |
| Cluster·Namespace 범위 | 목록·검색·상태 조합, 소속·범위 커서 검증 | 실제 DB·화면·교차 범위 오류 E2E 확인 |
| 상태·속성 변경 이력 | Flyway V3·변경과 이력의 원자적 커밋·제한 보관 | 커밋·롤백·커서·정리·상세 패널 검증 |
| 운영 관측 | Actuator·Micrometer·Prometheus·고정 PromQL | 실제 수집·카드 10개·P95 차트 2개·장애·응답 제한 검증 |
| 전체 Compose·CI/Jenkins | 전체 이미지·독립 DB·격리 CI·로컬 agent | 실제 Jenkins 파이프라인 성공·컨테이너 정리·익명 차단 검증 |
| 커밋 고정 CI | 전체 SHA·tree·빌드별 독립 checkout·공통 단계·이미지 ID 근거 | 미커밋 소스 거부·실행 이미지 일치 검증, 상세는 [CI 재현성](CI_REPRODUCIBILITY.md) |
| JPA Entity·DTO projection | 5개 Entity·부모 LAZY·OSIV off·keyset·readOnly | 실제 DB SQL 횟수·Entity 로딩 0·생성 SQL 인덱스·timeout 검증 |
| Keycloak 세 계정·Cluster 권한 | ADMIN 전체·A 1~10·B 11~20, issuer/sub·역할/그룹 기반 JPA 제한 | 실제 JWT/JWKS+DB 12개·실제 Keycloak E2E 5개, 전체/반쪽 집계·교차 접근·로그아웃·잘못된 claim 검증 |
| 인증 관리자 성능 | 실제 PKCE·SSO·JWT·ADMIN 전체 조회, 비밀값 미저장 도구 | CPU4x·캐시OFF 40회, SSO 재진입 최대 330.03ms·탐색 목표 초과 0; 로그인 제출 후 최대 638.02ms 별도 기록 |
| 인증 CI | 별도 Keycloak·tmpfs DB·임시 계정·안전한 JUnit·정리 | Jenkins 15에서 실제 로그인 5개·권한·이미지 일치·정리·서버 포함 JUnit 112개 |
| 경보·독립 알림 | Prometheus 세 규칙·Alertmanager·ADMIN 읽기 API/UI·내부 수신기 | 9시나리오·실제 CI 백엔드 중단/재기동·firing/resolved 수신·10만 데이터 보존 |

## 기술과 실행 경계

개발 과정은 [개발 가이드라인](DEVELOPMENT_GUIDE.md)에 따라 계층 책임·코드 스타일·API/JPA/프론트·보안·자원 상한·검증·문서화를 함께 관리합니다. AGENTS의 착수 지침과 Python 표준 라이브러리 기반 [품질 게이트](QUALITY_GATES.md)를 연결했습니다. `bash scripts/check.sh fast`는 문서·일부 위험 패턴·설정·마이그레이션 불변성과 게이트 자체 테스트를 검사하고, 전체 CI도 먼저 실행합니다. 정식 AST 검사·포매터·보안 감사가 아니며 기존 구조 예외와 미검증 항목은 가이드에 명시합니다. 새 OSS/유료 서비스 없이 적용하며 서비스 API·DB·화면 동작은 변경하지 않습니다.

Java 21 / Gradle 8.14.3 Wrapper·Kotlin DSL / Spring Boot 3.5 / Spring Data JPA starter·Hibernate 6.6 / PostgreSQL 17 / Flyway / Vue 3 / TypeScript / Vite / Testcontainers / Vitest / Vue Test Utils / jsdom / Actuator / Micrometer / Prometheus / Docker Compose / Jenkins를 사용합니다. 행 높이가 고정된 현재 화면에는 작은 가상 리스트를 구현했고 미사용 TanStack Virtual은 제거했습니다. 가변 높이·복잡한 스크롤 요구가 생기면 라이브러리 도입을 재검토합니다.

초기 Maven은 사용자 요청으로 Gradle에 전환했습니다. 기존 Java·Spring Boot BOM·JPA·테스트·seed fixture는 유지하고 Wrapper 공식 URL/체크섬·strict lockfile·Java toolchain으로 실행 버전을 통제합니다. Docker·CI·Jenkins·호스트 실행 스크립트도 같은 Wrapper와 새 `build` 산출물/테스트 경로를 사용합니다. 빌드 도구 변경에 따른 속도 개선은 주장하지 않습니다. [전환 이유·구성·실행·검증](GRADLE_BUILD.md)을 참고하며 과거 Maven 실행 결과는 이력으로 보존합니다.

초기 JDBC 조회는 공고의 JPA/Hibernate 요구와 사용자 요청에 따라 EntityManager·DTO constructor projection으로 전환했습니다. Entity 그래프를 API에 반환하지 않으며 부모 LAZY·OSIV off·readOnly·keyset·최대 101개 내부 반환량을 적용합니다. 실제 DB에서 Pod 목록 SQL 1회·Entity 로딩 0을 검증합니다. 전체 유형 검색은 JDBC 단일 UNION과 달리 최대 4개의 제한된 쿼리를 depth 순서로 실행하며 합산 상한도 101개입니다. 관계형 DTO 조회와 Mock의 원자적 native CTE를 구분하고 대량 변경은 JpaTransactionManager 아래 실행합니다. [전환 이유·실제 SQL·인덱스·제약](JPA_QUERY_DESIGN.md), [운영 경계](OPERATIONS_AND_DELIVERY.md)를 참고합니다.

V2는 PostgreSQL의 pg_trgm 및 이름 GIN 인덱스, 부모·ID와 상태·ID 인덱스를 추가합니다. 대표 EXPLAIN ANALYZE에서 선택적인 이름 검색은 GIN, 상태·ID 커서 조회는 인덱스 사용을 확인했습니다. 단문 미일치 검색은 순차 스캔을 사용했으며 모든 검색어의 GIN 사용을 보장하지 않습니다. 각 SQL에는 3초 제한, 클라이언트에는 응답 본문 처리까지 개별 요청 10초 제한을 적용합니다. 전체 요청 그룹의 지연 목표를 보장하는 수치는 아닙니다.

기존 프로젝트와 충돌을 줄이기 위해 호스트 개발 화면 5174, API 8081, 관리 포트 8086, DB 5544, Prometheus 9094를 사용하며 루프백에 바인딩합니다. 전체 Compose는 화면 5175·API 8082·Prometheus 9092와 별도 DB 볼륨을 사용하고 관리 포트·DB는 내부 전용입니다. 개발 DB의 기본 자격 정보는 실사용 계정 정보가 아닙니다. 인터넷 운영 배포는 범위 밖이며, 선택형 인증 환경은 아래 별도 실행 모드를 사용합니다.

선택형 `compose.auth.yaml`은 Keycloak Server 26.8.1·keycloak-js 26.2.4·Spring Security Resource Server와 별도 identity/app DB를 사용합니다. 서비스 admin은 전체 10만 Pod, user-a는 Cluster 1~10의 5만 Pod, user-b는 Cluster 11~20의 5만 Pod를 공유 데이터에서 조회합니다. 계정별 데이터 복제나 추가 Flyway 테이블 없이 초기 배정은 Keycloak client role/그룹으로 관리합니다. 조회·검색·건수·상세·경로·이력을 서버에서 제한하고 운영 관측은 ADMIN만 제공합니다. 인증 UI 5176·API 8083·Keycloak 8183·Prometheus 9095는 루프백이며 시작은 `python3 scripts/auth-local.py`입니다. 랜덤 비밀번호는 Git 제외 0600 파일, 토큰은 브라우저 메모리에만 보관합니다. [계정·보안·실행·검증·제약](AUTHENTICATION.md)을 참고합니다. 관리자 권한 변경 UI·운영 TLS/공개 배포·실제 Kubernetes RBAC는 포함하지 않습니다.

리소스 변경과 이력은 같은 트랜잭션이며 이력 보관은 전체 합산 최신 20,000개 또는 24시간입니다. 60초마다 최대 5,000개씩 정리하므로 일시 상한 초과가 가능하고 영구 감사 로그는 아닙니다. 기본 모드도 이력 정리는 수행합니다. Prometheus는 7일/256MB 제한으로 보관하며 UI는 고정 PromQL 결과만 사용합니다. 표본 부족·장애를 DB 값으로 대체하지 않습니다. [상세 제약](OPERATIONS_AND_DELIVERY.md)을 참고합니다.

## 현재 검증 결과

### 2026-10-16 요구 대조와 안정성 보완

과제 PDF 4페이지·채용 공고와 현재 구현을 대조해 필수 근거 없는 새 OSS/제품 기능은 추가하지 않습니다. 대신 실제 서명 토큰의 exp 누락·잘못된 groups/roles 형식 차단과 DB 풀 대기 3초/검사 1초·트랜잭션 시작 실패 503·연결 반환 후 복구를 보강했습니다. 근거·재현·미검증 수동 성능 구간은 [검토 문서](REQUIREMENTS_AND_HARDENING_REVIEW.md)에 기록합니다. 보완은 WH-025, 중단/재시도는 WH-026, 최종 전체 CI는 WH-027에 구분합니다.

**현재 최신 전체 검증은 Jenkins 빌드 15 SUCCESS입니다.** 공개 이미지용 임시 Docker 설정과 macOS Testcontainers socket 매핑을 적용한 fixture 커밋의 새 체크아웃에서 빠른 검사 46·서버 107(실제 PostgreSQL 44)·프론트 77·기본 브라우저 11·실제 Keycloak 5·경보 9시나리오·장애/해소 알림을 통과했습니다. 이미지 4개의 label/ID와 기본/인증 실행 컨테이너가 일치하고 JUnit 112·실패/skip 0·cleanup 통과·CI 컨테이너/임시 Docker 설정 잔존 0을 확인했습니다. 전역 Docker config hash와 기존 컨테이너 18개의 실행 ID를 보존했습니다. [최종 실행 근거](verification/2026-10-16-public-image-ci.json)를 참고합니다. Docker 전체 재시작·개인 키체인 변경은 하지 않았으며, 개인 도우미 자체의 원인 해결이나 수동 메모리/FPS 성능 검증을 뜻하지 않습니다.

이전 빌드 12에서 서버 107개(실제 PostgreSQL 44 포함)·프론트 77개·타입/빌드/audit·오프라인 39개가 통과했습니다. 이미지 메타데이터 단계의 Docker 자격증명 도우미 응답 대기로 해당 빌드를 안전 중단했고 `ABORTED`·cleanup 통과·CI 컨테이너 잔존 0을 확인했습니다. 당시 새 이미지·브라우저·인증·복구는 미완료였으며, [이전 실행 근거](verification/2026-10-16-hardening.json)를 보존합니다.

권한 승인 후 **빌드 13**도 서버 107(실제 PostgreSQL 44)·프론트 77·빠른 검사 39·타입/빌드·audit 0건을 통과했지만 이미지 단계 도우미 대기가 재발해 `ABORTED`로 중단했습니다. cleanup 통과·CI 컨테이너 0·알려진 비밀값/JWT 콘솔 미노출을 확인했습니다. 화면 접근 회복과 Docker 빌드 문제를 같은 원인으로 단정하지 않습니다. [당시 재실행 근거](verification/2026-10-16-ci-retry.json), WH-026을 보존하며 이후 빌드 15 전체 성공과 구분합니다. 수동 탭 메모리/FPS는 [별도 미확인 항목](REMAINING_PERMISSIONS.md)입니다.

### 후속 확장: 인증 CI와 운영 경보

CI 실행자는 Jenkins로 통일했습니다. GitHub Actions workflow는 제거해 중복 검증을 하지 않습니다. Jenkins는 개발 폴더의 가변 파일 대신 전체 커밋 SHA의 독립 체크아웃을 검증합니다. 빌드별 이미지 태그/label·실제 Image ID와 소스 tree를 연결하고 변경/불일치는 실패시킵니다. `ci.sh`는 같은 검증의 로컬 재현 명령입니다. [실행 정책·근거·제약](CI_REPRODUCIBILITY.md)을 참고합니다. 원본 미커밋 작업은 보존하며 fixture 검증과 원격 SCM/webhook 연결을 구분합니다.

공개 이미지 조회가 개인 Docker 자격증명 도우미에서 대기하는 문제에 대해 CI 단계별 임시 클라이언트 설정을 도입했습니다. 전역 config/로그인 정보를 복사·수정하지 않고 기존 로컬 Unix socket과 설치된 Buildx/Compose만 사용합니다. TLS 검증 해제나 Docker 전체 재시작 없이 공개 익명 조회를 분리하며, 원격/TLS override·사설 레지스트리 로그인/push는 이 모드의 범위가 아닙니다. 임시 설정/Buildx 상태는 정상·예외 종료 시 삭제합니다. [이유·보안·실행 제한](CI_REPRODUCIBILITY.md#공개-이미지와-개인-docker-설정-분리), WH-027을 참고합니다.

CI의 Keycloak은 Jenkins 로그인 도구가 아니라 **검증 대상 서비스 의존성**입니다. 기존 검증 뒤 별도 인증 CI 프로젝트에서 실제 PKCE·ADMIN 전체/A·B 격리·401/403/404·로그아웃을 확인하고 비밀값 없는 JUnit/요약을 수집합니다. Compose 2.24.4+ 오버레이의 앱/identity tmpfs DB·새 CI 계정을 사용해 개발/인증 환경을 보존합니다. 공개 issuer/내부 JWKS·redirect·역할 매핑의 조합을 검증하며 실제 클라우드 배포나 레지스트리 접근용 자격 증명과 구분합니다. [구성·흐름·실행·정리](AUTHENTICATION_CI.md)를 참고합니다.

추가 운영 OSS는 Alertmanager 0.34.0입니다. Prometheus 세 경보 규칙의 그룹화·알림 간격·해소 전달을 맡깁니다. 백엔드 수집 실패(대상 시계열 없음 포함), 최소 요청 수를 동반한 API 5xx 비율, Mock 배치 반복 실패를 1분 지속 후 경보로 전환합니다. 관리자 API/UI는 고정 rules API의 inactive/pending/firing을 표시하고 규칙 누락·평가 오류·소스 장애를 확인 불가로 구분합니다. 지표/경보는 공통 제한 HTTP client를 사용합니다. Alertmanager가 앱과 독립된 비루트·읽기전용 내부 수신기에 전달하고 최근 200개 이벤트만 메모리에 보관합니다. 외부 SMTP·영구 감사·자동 복구·호스트 전체 장애 감지는 포함하지 않습니다. [데이터 흐름·API·보안·조건·대응·검증](ALERTING_AND_RECOVERY.md)을 참고합니다.

전체 CI는 규칙 검사 → 기존 서버/프론트/이미지/실제 화면 → CI 백엔드 중단·firing·독립 수신·재기동·resolved·10만 데이터 보존 → 별도 Keycloak 로그인 검증 → 정리입니다. 실제 실행 결과와 실패/제약은 [작업 이력](WORK_HISTORY.md)의 WH-021/022에 기록합니다. 아래 기존 수치는 각 시점의 기록이며 새 실행과 혼동하지 않습니다.

이전 Jenkins 빌드 11은 당시 fixture Git 커밋의 새 독립 체크아웃에서 Gradle 102개·프론트 77개·기본 E2E 11개·실제 Keycloak 5개·규칙 9시나리오·실제 장애/해소 알림을 통과했습니다. 전체 SHA/tree·이미지 4개의 label/ID·실제 기본/인증 컨테이너 Image ID를 확인했고, JUnit 107개·실패/skip 0·컨테이너 정리·비밀값 미노출을 확인했습니다. [당시 커밋 고정 실행 근거](verification/2026-10-15-commit-pinned-ci.json)를 보존합니다. 원본 Git은 미커밋 상태를 보존하며 GitHub 실행은 미검증입니다. 이전 빌드 8/10은 WH-019/022에 보존합니다. 아래 과거 성능은 이번 CI 구조 변경의 재측정 결과가 아닙니다.

이후 인증 Compose의 관리자 성능을 별도 40회 측정했습니다. Keycloak 로그인·SSO 쿠키 왕복·코드 교환·JWT 검증을 포함한 SSO 재진입은 최대 330.03ms, 미캐시 펼침 78.90ms·검색 119.50ms·필터 70.10ms·읽기 전용 갱신 109.20ms로 목표 초과·측정 오류 0입니다. 매회 ADMIN과 실제 20/200/2,000/100,000 건을 검증했습니다. 비밀번호 제출부터의 로그인 최대 638.02ms는 별도 보존하며 전체 로그인 500ms 보장은 아닙니다. 실제 Keycloak E2E 5개도 다시 통과했습니다. [인증 성능 구간·전체 표본·제한](AUTHENTICATION_PERFORMANCE.md)을 참고합니다. 이번에 자동 갱신 변경값 전파·5페이지·FPS·탭 메모리 또는 A/B·동시 부하는 재측정하지 않았습니다.

독립 소스는 `/Users/yeob-eunjun/eunjuny/project/infrastructure-monitoring-service`로 옮겼습니다. 기존 Auknowlog 소스와 의존성을 변경하지 않고 별도 node_modules·package-lock.json을 설치했습니다. Mock 프로필은 과제 DB를 변경하므로 실사용 데이터에 적용하지 않습니다. 변경용 HTTP API를 노출하지 않습니다.

UI는 Headlamp·Rancher·Lens의 탐색 패턴을 참고해 좌측 유형 메뉴·계층, 중앙 열 중심 목록, 우측 상세 탭으로 개선했습니다. 현재 조회창 상태 수는 전체 DB 집계와 구분하고 Node meter는 API 현재값만 사용합니다. 모바일은 계층 기본 접힘·세로 상세 배치이며 미구현 운영 메뉴는 추가하지 않습니다. 새 UI OSS 의존성은 없습니다. [이유·참고 출처·상세 검증](UI_DESIGN.md)을 참고합니다.

| 항목 | 결과 |
|---|---|
| 정식 Gradle 전체 테스트 | 빌드 15 clean build 107개 통과, 실패·오류·skip 0; Java 21·Boot 3.5.16 유지 |
| 실제 PostgreSQL Testcontainers | 기존 30개 + 실제 서명/JWKS·인증 범위 12개 + 풀 고갈/설정 2개, 총 44개 포함 |
| Compose DB·백엔드 실행 | PostgreSQL 17.11, health UP, V1~V3 성공, Pod 100,000개 |
| 프론트 재현 설치 | npm ci 통과, 독립 설치·잠금 파일 |
| 타입 검사·프로덕션 빌드 | vue-tsc·Vite 통과 |
| 화면·클라이언트·폴링 단위 테스트 | 경보 상태/장애/안전 텍스트·인증·토큰/시간 제한·로그아웃 포함 Vitest 77개 통과 |
| 실제 DB·API Chromium E2E | 11개 통과, 탐색·반응형·범위·이력·Prometheus와 선택 청크 지연 로딩·날짜 표시 포함 |
| 실제 Keycloak Chromium | 격리 CI에 연결한 5개 통과, 3계정·PKCE·반쪽 조회·운영/경보 교차 차단·토큰 비영속·커서·로그아웃 |
| npm audit | 0건, Vitest 수정 버전 적용 |
| 개발 하네스 | 기존 39+공개 Docker 설정 격리 7=46개, 빌드 15와 최종 문서 게이트 통과 |
| 가이드 성능 측정 | CPU 4x·캐시 OFF, 20회씩 2회 반복·합성 프레임·메모리·SQL 계획 기록; 상세 판정은 성능 보고서 |
| 전체 Compose·운영 지표 | 이미지 빌드·서비스 health·독립 DB seed·Prometheus up=1 확인 |
| 로컬 Jenkins | fixture 커밋 독립 checkout의 빌드 15 SUCCESS, 8단계·이미지 4개·실제 실행 이미지 확인·기본/인증/복구·정리, 익명 차단·비밀값 미노출; JUnit 112개 |
| CI 실행자 단일화 | Jenkins만 사용, Actions workflow 제거·로컬 재현 유지; 오프라인 46개·전체 빌드 15 검증 |
| JPA 전환 전 탐색 성능 | JDBC·CPU 4x·캐시 OFF 20회, 초기 P95 366.3ms·최대 645.3ms·1회 초과, 나머지 탐색 목표 초과 0 |
| JPA 전환 후 탐색 성능 | 같은 조건 20회, 초기 P95 304.8ms·최대 652.6ms·1회 초과, 검색 P95 69.5ms·갱신 62.6ms·나머지 목표 초과 0 |
| JPA 추가 경계 성능 | 검색 100회·다중 부모 160회·최대 창 자동 갱신 20회, 오류·목표 초과 0, 전체 유형+상태 최대 188.7ms·자동 반영 최대 128.2ms |
| 초기 진입 추가 개선 | 날짜·건수 지역화 제거·선택 화면 코드 분리·초기 I/O 중첩·폰트 스택 조정 후 20회×3 총 60회 모두 500ms 이내, 최대 468.8/458.0/446.1ms |

[후속 검증 보고서](STAGE4_REPORT.md), [성능 검증 보고서](PERFORMANCE_RESULTS.md)에 확장 전 명령과 근거를 기록했습니다. 최신 확장 검증은 [운영·CI 문서](OPERATIONS_AND_DELIVERY.md)를 기준으로 합니다. 테스트 실행 시간은 브라우저 성능 목표 달성 수치가 아닙니다. 가상 목록의 초기 동기 높이 측정과 불필요한 스크롤 초기화를 줄이고 날짜 포매터를 재사용했습니다. 측정 도구는 검증 환경에서만 삽입되며 운영 화면 번들에 포함하지 않습니다.

JPA 전환 전 Jenkins 빌드 4는 79개·61개·10개 테스트 및 전체 이미지·격리 runtime·Prometheus 확인까지 성공했습니다. 같은 JDBC 버전의 확장 후 성능 재측정 첫 진입 1회는 500ms를 초과했고 422ms long task가 있었습니다. 이 표본을 제거하지 않았습니다. [전환 전 측정·CI 요약](performance/2026-10-14-feature-summary.json)을 참고하며 JPA 결과와 구분합니다.

JPA 전환 후 Jenkins 빌드 5에서 전체 검증을 재통과했습니다. 호스트 개발 백엔드와 전체 Compose를 JPA 버전으로 재기동하고 Hibernate 6.6.53.Final·EntityManagerFactory 초기화·health UP·Pod 100,000개·Flyway V1~V3 유지를 확인했습니다. 이력·데이터 볼륨을 삭제하거나 DB를 새로 생성하지 않았습니다.

JPA 전환 후 기본 탐색도 CPU 4x·캐시 OFF로 동작별 20회 재측정했습니다. 초기 진입 첫 1회는 652.6ms로 목표를 초과했고 419ms browser long task가 있었습니다. 다른 기본 동작에는 측정 오류·목표 초과가 없었습니다. 짧은/전체 유형 검색 100회·다중 부모 펼침 160회·최대 창 자동 갱신 20회도 재통과했습니다. 서버 부팅·모든 최초 쿼리 비용·실제 Chrome 작업관리자 메모리 합격을 뜻하지 않습니다. [전환 전후 비교](JPA_QUERY_DESIGN.md#전환-전후-브라우저-성능), [최신 요약](performance/2026-10-14-jpa-summary.json)을 참고합니다.

그 후 사용자 요청으로 초기 진입을 추가 개선했습니다. 날짜·건수 지역화 초기화 제거, 상세·운영·이력 코드 지연 로딩, 닫힌 범위 DOM 억제, 초기 I/O 중첩, 로컬 기본 폰트로 조정했습니다. 데이터·측정 기준은 유지했으며 최종 코드의 초기 전용 40회·전체 동작 포함 20회(총 60회)에서 오류·500ms 초과가 없었습니다. 중간 코드의 619.3/504.2ms 실패 표본도 남겨 두었습니다. [초기 성능 개선 근거](STARTUP_PERFORMANCE.md), [요약 JSON](performance/2026-10-14-startup-summary.json)을 참고합니다.

최신 Jenkins 빌드 6에서도 백엔드 87개·프론트 66개·실제 API E2E 11개와 전체 이미지·격리 Compose·Prometheus를 재검증했습니다. 동적 청크는 해당 화면을 선택할 때만 요청하며 상세·경로·탭·이력·운영·모바일 흐름이 유지됩니다. 초기 진입 목표 통과와 Chrome 작업관리자 탭 메모리 미확인은 별개의 판정입니다.

후속 원격 검증에서 CPU 4x·캐시 OFF로 단문·흔한 검색·전체 유형·상태 조건을 각 20회(100회), 8개 부모 펼침을 20회(160클릭), 목록 500개·펼친 부모 8개·선택 상세의 자동 갱신을 20회 측정했습니다. 최대 검색 102.4ms·펼침 62.8ms·자동 갱신 111.6ms, 오류·목표 초과는 없었습니다. 5초 폴링 대기는 반영 시간과 분리했습니다. 제품 코드·API·DB 스키마는 변경하지 않았습니다. 프론트 56개·Chromium 7개·타입 검사·빌드도 재통과했습니다. Mac 잠금으로 Chrome 작업관리자 확인은 아직 못 했으며, JS heap을 탭 메모리로 대체하지 않았습니다. [추가 결과·실행 방법·원격 제약](PERFORMANCE_EDGE_RESULTS.md)을 참고합니다. 이 후속 검증 도구·문서 변경은 아직 커밋하지 않았습니다.

## 단계별 커밋과 제출 전 상태

2026-10-14 후속 Gradle 전환은 Wrapper 8.14.3·strict lockfile·Kotlin DSL과 새 build 산출물 경로를 사용합니다. 기존 122개 모듈 버전 유지·Gradle 전용 테스트 launcher 추가를 확인했고, Jenkins 빌드 7에서 87개·66개·11개, 이미지/격리 Compose/Prometheus/정리를 통과했습니다. 현재 호스트도 Gradle JAR로 기동해 health UP·10만 Pod·기존 V1~V3를 확인했습니다. 이전 Maven·빌드 6 수치를 새 실행으로 바꾸지 않습니다. [전환 근거](GRADLE_BUILD.md)를 참고합니다.

2026-10-14에 변경사항을 다음 다섯 단계로 정리했습니다. 로컬 저장소는 기존 커밋·원격 설정이 없던 상태였으므로 초기 스냅샷부터 기록했습니다. 작업 파일을 되돌리거나 기존 GitHub 이력을 덮어쓰지 않았습니다.

| 단계 | 내용 |
|---|---|
| 1 | 초기 구성·Flyway·10만 건 seed·조회 API·백엔드 테스트 |
| 2 | Mock 상태 변경·제한 배치·트랜잭션·정책 및 실제 DB 테스트 |
| 3 | 가상화 탐색 콘솔·검색·필터·상세·모바일·자동 갱신·요청 보호 |
| 4 | 실제 DB Chromium E2E·성능 측정 스크립트·SQL 계획·측정 근거 |
| 5 | README·기준 문서·설계·검증 결과 최신화 |

커밋 전 Maven 69개·Vitest 56개·Chromium E2E 7개·타입 검사·빌드·npm audit 0건을 재확인했습니다. 의심되는 API 키·개인키 패턴과 공백 오류도 확인했습니다. 빌드 산출물, 로컬 DB, node_modules, 원본 trace 및 임시 캡처는 커밋 대상이 아닙니다.

초기 단계에서는 푸시하지 않고 후속 확장·JPA·초기 성능 개선·문서·Gradle·선택형 인증·커밋 고정 CI를 미커밋 상태로 검증했습니다. 2026-10-16 사용자 요청으로 빌드 15와 같은 실행 소스를 기능 `9d07d32`·CI `211e350`과 통합 문서로 나누어 커밋하고 비공개 origin/main에 게시합니다. 원격 초기 이력은 현재 소스 tree를 유지하는 병합으로 보존하며 강제 푸시는 하지 않습니다. 실제 게시 확인은 WH-028에 기록합니다. 새 Git metadata는 Windows Wrapper의 CRLF만 보존하며 실행 코드는 검증한 소스 그대로입니다. 원격 SCM/webhook 자동 CI와 수동 Chrome 메모리/FPS·제출 자료 정리는 별도입니다.

## 초기 제한 기록 — 2026-10-13

아래는 이전 환경의 기록입니다. Docker·네트워크·포트 제한은 2026-10-14 작업에서 해소했으며 현재 상태는 위 표를 기준으로 합니다.

| 항목 | 결과 |
|---|---|
| package.json / tsconfig.json 문법 | 통과 |
| Maven XML 파싱 | 통과 |
| docker compose config --quiet | 통과 |
| git diff --cached --check | 통과 |
| 생성 규칙의 계층 수 계산 | 20 / 200 / 2,000 / 100,000 확인 |
| Maven 테스트 | 미실행: 필요한 의존성이 로컬 캐시에 없음 |
| 정식 npm 설치 및 타입 검사 | 미실행: 오프라인 캐시에 의존성이 없음 |
| 캐시 도구를 이용한 Vite 프로덕션 번들 | 통과, 정식 설치·타입 검사는 별도 |
| 외부 의존성 다운로드 | 현재 세션에서 Maven 저장소 DNS 조회 실패 |
| Docker / Testcontainers / 실제 DB 적재 | 현재 세션의 Docker 소켓 접근 제한으로 미검증 |
| PostgreSQL 16.11 임시 DB 초기화 | 공유 메모리 생성 권한 제한으로 실패 |
| 화면 E2E 및 성능 목표 | 미검증 |
| 2단계 전체 main Java 컴파일 | 캐시 라이브러리 환경에서 통과, 지정 POM 빌드는 별도 |
| 요청값·커서·서비스·MVC·SQL 바인딩 단위 테스트 | 캐시 라이브러리 환경에서 40개 통과 |
| 2단계 PostgreSQL 통합 테스트 | 12개 작성, Docker 제한으로 실행하지 못함 |
| 3단계 화면·API 클라이언트·가상화 테스트 | 캐시 Vitest 3.2.7 환경 33개 통과; DB 미사용 |
| 3단계 Vite 번들 | 캐시 Vite 7.3.6 환경 통과; vue-tsc 타입 검사는 별도 |
| 3단계 로컬 개발 서버 | 127.0.0.1:5174 listen EPERM으로 실행 실패 |

초기 기록과 후속 정식 검증을 구분합니다. 이후 정식 설치·빌드·DB·재적재·브라우저 검증을 완료했습니다. 커밋·푸시는 별도 사용자 요청 시 진행합니다.

## 설계와 다음 단계

- [초기 구성부터 현재까지의 작업 이력·시행착오·후속 기록 양식](WORK_HISTORY.md)
- [개발 가이드라인·계층 책임·코드/보안/검증 규칙](DEVELOPMENT_GUIDE.md)
- [품질 게이트·개발 하네스·명령과 자동 검사 한계](QUALITY_GATES.md)
- [Maven → Gradle 전환·Wrapper·의존성 잠금·CI와 결과 경로](GRADLE_BUILD.md)
- [선택형 Keycloak·관리자/A/B·클러스터 권한·비밀값·실제 테스트](AUTHENTICATION.md)
- [실제 Keycloak 관리자 로그인·SSO·10만 데이터 탐색 성능](AUTHENTICATION_PERFORMANCE.md)
- [구현 계획과 필수 설계 항목](IMPLEMENTATION_PLAN.md)
- [성능 측정 기준](PERFORMANCE.md)
- [성능 결과·안정성 보강·SQL 실행 계획](PERFORMANCE_RESULTS.md)
- [추가 검색·다중 계층·자동 갱신과 원격 메모리 검증 상태](PERFORMANCE_EDGE_RESULTS.md)
- [1단계 구동 검증과 보강된 통합 테스트](VALIDATION_REPORT.md)
- [리소스 API 계약](API.md)
- [2단계 구현과 검증 근거](STAGE2_REPORT.md)
- [프론트 렌더링·캐시·탐색 설계](FRONTEND_RENDERING.md)
- [3단계 화면 연결·검증 근거](STAGE3_REPORT.md)
- [Mock 상태 변화·자동 갱신 설계](LIVE_UPDATES.md)
- [실제 DB·브라우저 후속 검증](STAGE4_REPORT.md)
- [콘솔 UI 참고·설계·검증](UI_DESIGN.md)
- [범위 필터·상태 이력·Prometheus·전체 Compose·CI/Jenkins](OPERATIONS_AND_DELIVERY.md)
- [JPA 전환·Entity/DTO 분리·N+1·인덱스·트랜잭션 검증](JPA_QUERY_DESIGN.md)
- [초기 진입 개선·CPU 프로파일·실패/최종 반복 측정](STARTUP_PERFORMANCE.md)

추가 검색·다중 계층·자동 갱신 반복 측정은 완료했습니다. 다음은 Mac 잠금 해제 후 Chrome 작업관리자의 실제 탭 메모리 확인과 수동 스크롤 기록입니다. 전체 10만 행 임의 위치 점프는 없는 순차 커서 UX라는 제한도 제출 전에 확인합니다. 현재 측정에서 DB 변경이나 추가 OSS 도입을 요구할 병목은 확인하지 않았습니다.

## 변경 이력

- 2026-10-16: 사용자 요청에 따른 안정화 버전 분리 커밋·원격 초기 이력 보존 병합·비공개 main 게시. 실행 소스/비밀값/산출물 경계 재확인, Windows Wrapper의 원본 CRLF를 위한 경로별 Git attribute 추가. 실제 반영 상태는 WH-028에 기록.

- 2026-10-16: 공개 이미지 CI의 Docker 설정/Buildx 상태 격리·macOS Testcontainers socket 매핑. 빠른 검사 46개·Jenkins 빌드 15 전체 SUCCESS, 서버 107·프론트 77·브라우저 11·실제 Keycloak 5·복구·이미지/정리 검증. 전역 설정·기존 컨테이너 18개 보존, 전체 Docker 재시작 없음. WH-027에 기록.

- 2026-10-16: 사용자 요청으로 동일 고정 소스의 Jenkins 빌드 13 재실행. 서버 107·프론트 77·빠른 검사 39 재통과, 이미지 단계 도우미 대기 재발로 해당 빌드만 중단·정리. 화면 접근 권한 회복과 빌드 문제를 구분해 WH-026 및 실행 근거에 기록. 제품 코드·설정·데이터 변경 없음.

- 2026-10-16: 과제·공고 대조 후 토큰 만료/권한 claim 구조 검증과 연결 풀 대기·트랜잭션 시작 실패 처리 보완. 실제 RSA/JWKS·PostgreSQL 회귀와 전체 Jenkins를 통해 검증하고 WH-025에 누적합니다. 새 OSS·데이터 마이그레이션·사업 기능 없음.

- 2026-10-15: 사용자 선택으로 Jenkins 단일 CI 운영. Actions workflow 제거·실행자 허용 목록 정리·로컬 재현 유지, 오프라인 39개·빠른 검사 통과. 전체 파이프라인의 이전 빌드 11 검증과 이번 실행자 정책 변경 검증을 구분합니다. [WH-024](WORK_HISTORY.md#wh-024--jenkins-단일-ci로-통일)에 기록합니다.

- 2026-10-15: 실제 인증/권한을 격리 CI에 연결하고 tmpfs DB·임시 계정·안전한 JUnit·정리를 구성했습니다. Prometheus 세 규칙·Alertmanager·독립 수신기·관리자 경보 API/UI·규칙/실제 복구 검증을 추가했습니다. 실제 실행 근거는 WH-021/022와 관련 설계 문서에 누적합니다.

- 2026-10-15: 관리자 실제 PKCE 로그인·SSO 재진입·10만 Pod 탐색의 비밀값 미저장 성능 도구와 runner 명령 추가. 독립 Chromium 20회×2의 재진입/탐색 목표 초과·오류 0, 첫 로그인 638.02ms 별도 보존, 실제 Keycloak E2E 5개 재통과. 서비스·DB·인증 정책 변경 없음. 인증 성능 문서와 WH-020에 기록.
- 2026-10-15 실행 검증: 선택형 Keycloak·관리자/A/B 계정 및 Cluster별 JPA 권한 추가. 기본 10만 Pod 유지, 관리자 전체·A 1~10·B 11~20 공유 조회. client role·JWT·범위/집계·커서·로그아웃·비밀값 검증, Gradle 96개·Vitest 73개·기본 E2E 11개·실제 Keycloak E2E 5개 및 Jenkins 빌드 8 성공. 추가 테이블·기존 DB 초기화 없음. 상세는 인증 문서와 WH-019에 기록.
- 2026-10-13: 초기 구성 및 구동 검증 제한 기록.
- 2026-10-13: 2단계 리소스 조회·경로·검색 API, V2 검색 인덱스, 입력·커서 정책, 단위 및 DB 통합 테스트 코드 추가. 캐시 라이브러리 환경의 40개 단위/MVC 테스트 통과와 실제 DB 미검증을 구분.
- 2026-10-13: 3단계 계층·상세·검색·필터 화면과 API 연결, 고정 높이 가상화·최대 500개 목록 캐시·수동 갱신 추가. 화면·클라이언트 테스트 33개와 번들 확인, 타입 검사·실제 DB·브라우저·성능 미검증 기록.
- 2026-10-14: 독립 폴더·의존성 설치, 실제 DB·정식 빌드·69개 백엔드·44개 프론트·5개 E2E 검증 완료. 선택형 Mock 배치 및 제한된 폴링·중지·백오프 구현. Vitest 취약점 수정 버전·잠금 파일 적용.
- 2026-10-14: Headlamp·Rancher·Lens 패턴을 참고한 콘솔 UI, 유형 바로가기·열 목록·조회창 집계·상세 탭·단축키·모바일 계층 접기 적용. 프론트 50개·실제 API E2E 7개 재검증. API·DB 변경 없음.
- 2026-10-14: 실제 DB 연결 CPU 4x·캐시 OFF 반복 성능 측정, 100페이지 탐색 메모리·프레임·대표 실행 계획 확인. 초기 동기 레이아웃·불필요한 스크롤 초기화 제거, 날짜 포매터 재사용, 개별 HTTP 10초 제한·취소 구분 추가. 프론트 56개·실제 API E2E 7개 검증. 백엔드 API·DB 스키마 변경 없음.
- 2026-10-14: 전체 테스트·빌드·비밀정보 패턴·공백 오류 재점검 후 변경사항을 5개 단계별 로컬 커밋으로 정리. 원격 연결·푸시는 별도 작업으로 유지.
- 2026-10-14: 원격 환경의 추가 검증 도구·명령·측정 근거 추가. 실제 검색 100회·다중 부모 펼침 160회·최대 조회창 자동 갱신 20회 통과, 폴링 대기와 처리 시간 분리. Mac 잠금으로 실제 탭 메모리는 미확인. 제품 동작 변경 없음.
- 2026-10-14: Cluster·Namespace 범위 필터, Flyway V3 및 원자적 변경 이력·제한 보관, Actuator·Micrometer·Prometheus 관측 화면, 전체 컨테이너화·격리 CI·로컬 Jenkins 구현. 미사용 JPA 의존성 제거, 호스트 런타임 JAR와 CI 빌드 파일 분리. Maven 79개·Vitest 61개·실제 API E2E 10개 및 로컬 Jenkins 성공 검증. GitHub 실행·인터넷 배포·실제 Kubernetes 연동은 미구현. 이번 확장은 미커밋 상태.
- 2026-10-14: 사용자 요청에 따른 JPA/Hibernate 전환. 기존 5개 테이블 Entity 매핑·DTO projection·OSIV off·readOnly·LAZY·keyset·schema validate 적용. 전체 유형 검색은 최대 4회/합산 101개로 변경, Mock/이력 정리는 JPA native query로 유지. 실제 PostgreSQL의 SQL 횟수·Entity 로딩 0·생성 SQL GIN 인덱스·timeout·JpaTransactionManager 검증 8개 추가. DB 데이터·Flyway V1~V3·API 계약 유지.
- 2026-10-14: 초기 진입 성능 개선. 고정 로컬 날짜·정수 건수, 상세/운영/이력 코드 분리, 닫힌 옵션 렌더 억제, setup 단계 I/O, 로컬 기본 폰트 적용. 초기 CPU4x·캐시OFF 총 60회 모두 500ms 이내 확인, Jenkins 빌드 6의 87개·66개·11개 테스트 재통과. 중간 실패 표본과 프로파일 도구를 함께 보관. 백엔드·API·DB 변경 없음.
- 2026-10-14: WORK_HISTORY.md에 전체 과제 작업을 기능·이유·검증·실패·남은 점검·Git 상태로 통합하고 AGENTS.md에 후속 작업 이력 추가를 완료 조건으로 설정. README와 기준 문서에 연결. 문서 전용 변경으로 링크·내용·비밀정보·공백 검사 수행, 기능 테스트 재실행 없음.
- 2026-10-14: 개발 가이드·품질 게이트·EditorConfig 제정. AGENTS에 착수/완료 절차를 연결하고 Python 표준 도구로 문서·일부 위험 패턴·ORM 설정·Flyway 변경을 검사. 게이트 정상/실패 회귀 테스트와 빠른 검사를 CI 첫 단계에 연결. 기존 소스 예외·정규식 검사 한계 명시, 서비스 동작·API·DB 변경 없음.
- 2026-10-14: 사용자 요청으로 Maven에서 Gradle 8.14.3 Wrapper·Kotlin DSL로 전환. Java/Boot BOM·87개 테스트·seed fixture 유지, strict lockfile·Wrapper 무결성 검사·bootJar 경로 적용. Docker·CI·Jenkins·호스트 실행·GitHub 보고서 경로 동기화. 실제 파이프라인 결과는 Gradle 전환 문서·작업 이력에 별도 기록.
