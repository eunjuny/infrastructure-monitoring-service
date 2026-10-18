# 프로젝트 기준 문서

## 서비스와 데이터

PostgreSQL에 저장된 Cluster20개·Node200개·Namespace2,000개·Pod150,000개를 탐색하는 인프라 모니터링 서비스입니다. 계층은 과제의 드릴다운 모델인 Cluster → Node → Namespace → Pod를 따릅니다. 실제 Kubernetes에서 Namespace가 Node의 하위 오브젝트라는 뜻은 아닙니다.

admin은 전체, user1은 Cluster1~10의100,000 Pod, user2는 Cluster11~20의50,000 Pod를 조회합니다. Node100개에 GPU2개씩 총200개 장치가 있고 Node 상세에서 확인합니다.

현재 리소스는 제공된 시나리오 데이터입니다. 실제 Kubernetes API·kubelet·DCGM 수집기는 연결하지 않았습니다. Node 요청량은 저장된 시나리오 값이며 Pod 컨테이너 요청량의 실시간 합산이 아닙니다.

## 전체 구조

```text
Vue 3 → NGINX 정적 화면·/api 프록시 → Spring Boot REST API → JPA DTO → monitoring DB
브라우저 → Keycloak OIDC·PKCE 로그인 → JWT → 서버의 서명·역할·Cluster 범위 검증
           Keycloak → 같은 PostgreSQL 서버의 identity DB

Micrometer·Actuator ← Prometheus 수집·시계열·경보 평가
운영 관측 API → 고정 PromQL → Prometheus → 관리자 화면
Prometheus → Alertmanager → 독립 수신기 / SMTP 설정 시 관리자 메일

Git 전체 SHA → Jenkins 독립 체크아웃 → 테스트·이미지 → 격리 Compose·실제 로그인
```

인증 Compose의7개 상시 서비스는 frontend·backend·db·keycloak·prometheus·alertmanager·alert-receiver입니다. db-init·auth-import·seed는 일회성 준비 작업입니다. 웹 주소는 http://127.0.0.1:5176 이며 실행과 계정 준비는 README의 단일 경로를 따릅니다. 개발용 다른 모드는 [실행 안내](RUNNING.md)에서 구분합니다.

## 기능과 처리 흐름

| 기능 | 처리 |
|---|---|
| 목록·계층 | 유형·부모·범위를 검증한 뒤 필요한 열과 최대100개를 조회. 계층은 펼칠 때 로드 |
| 검색·필터 | 이름·유형·상태·Cluster·Namespace 조건 적용. 경로 응답과 트리 위치 이동 |
| 상세·진단 | 유형별 속성·조상 경로, Node 자원/GPU·Pod 컨테이너·현재 이벤트를 제한 조회 |
| 사용자별 접근 | 서버가 검증한 JWT 역할·그룹으로 모든 목록·상세·검색·집계를 제한 |
| 상태 갱신 | 목록·펼친 계층·상세는 기본5초. 총수는 집계 완료 후60초가 지난 다음 갱신 작업에서 재조회 |
| 요청 수명 | 숨김·오프라인·자동 갱신 해제 시 중단. 취소·응답 순서 검증·실패 백오프·기존 데이터 보존 |
| 변경 이력 | 상태/속성 변경 전후를 별도 테이블에 저장하고 keyset으로 조회 |
| 운영 관측 | API·DB 조회·JVM·연결 풀 지표와 추이·경보를 ADMIN에 제공 |
| 경보 메일 | ADMIN 팝업에서 수신 주소·발송 여부 설정. SMTP는 로컬 비밀 설정, 발송은 Alertmanager |
| CI | Gradle/Testcontainers·Vitest·실제 Keycloak E2E·커밋/이미지 일치 검증 |

상태 생성 배치는 유형별 ID 커서로5초마다 Cluster1·Node5·Namespace10·Pod100개를 변경합니다. 리소스·컨테이너·현재 이벤트·이력은 같은 DB 트랜잭션에 저장합니다. 외부 Kubernetes 자원을 변경하거나 DB 실패 시 외부 자원을 되돌리는 기능은 없습니다.

## 기술과 자원 경계

- Java21·Spring Boot3.5·Gradle Wrapper: 타입 있는 REST API와 잠금 파일 기반 빌드.
- PostgreSQL17·JPA/Hibernate·Flyway V1~V6: 직접 부모 FK·제약·인덱스·스키마 이행. Entity 그래프 대신 DTO projection.
- Vue3·TypeScript·Vite: 가상 리스트·제한 캐시·응답 타입·화면 상태·요청 수명 관리.
- Keycloak: Authorization Code + PKCE, issuer·audience·서명·시간·sub·client role 검증.
- Prometheus·Alertmanager: 앱 계측과 시계열 저장·경보 평가·알림 전달 분리.
- Compose·Jenkins: 의존 순서에 따른 실행과 격리된 커밋 고정 검증.

목록/검색 응답100개·다음 페이지 판단용 내부 합산101개, 목록 캐시5페이지·500개, 트리24페이지·동시 펼침8개로 제한합니다. SQL별3초와 HTTP 본문 처리까지 요청별10초는 서로 다른 대기 경계입니다.

비밀번호1234는 로컬 초기 사용자 세 계정에만 적용합니다. DB·bootstrap·CI·내부 제어 비밀값은 별도 생성해 Git 제외 파일로 관리합니다. 관리 포트는 웹 프록시에 노출하지 않습니다. 인터넷 공개용 TLS·고정 도메인·운영 Keycloak·HA는 별도 구성 대상입니다.

## 설계·실행 문서

| 주제 | 문서 |
|---|---|
| API·오류·커서 | [API](API.md) |
| 계층·DTO·인덱스·트랜잭션 | [JPA](JPA_QUERY_DESIGN.md) |
| 가상 리스트·캐시·비동기 화면 | [렌더링](FRONTEND_RENDERING.md) |
| 폴링·배치·실패 처리 | [상태 갱신](LIVE_UPDATES.md) |
| GPU·컨테이너·이벤트·수식 | [리소스 진단](RESOURCE_DIAGNOSTICS.md) |
| OIDC·JWT·권한 | [인증](AUTHENTICATION.md) |
| 운영·경보·메일 | [관측](OPERATIONS_AND_DELIVERY.md), [경보](ALERTING_AND_RECOVERY.md), [메일](ALERT_EMAIL.md) |
| 컨테이너·DB·계정 | [실행](RUNNING.md), [DB](SHARED_AUTH_DATABASE.md), [계정·테마](USER_SCOPES_AND_LOGIN_THEME.md) |
| CI·인증 검증 | [커밋 고정 CI](CI_REPRODUCIBILITY.md), [인증 CI](AUTHENTICATION_CI.md) |
| 개발·빌드·검사 | [개발 규칙](DEVELOPMENT_GUIDE.md), [Gradle](GRADLE_BUILD.md), [품질 게이트](QUALITY_GATES.md) |
| 성능 조건·결과·원본 | [측정](PERFORMANCE.md), [반복 결과](FINAL_PERFORMANCE_REVIEW.md), [Chrome 원본](CHROME_PERFORMANCE_CAPTURE.md) |

## 검증 상태와 제약

서버143개, 프론트113개, 실제 Keycloak 브라우저15개는 해당 기능 변경 시 실행한 결과입니다. 빠른 게이트는 소스/문서·도구 회귀만 실행하며 전체 기능·브라우저·성능을 대체하지 않습니다.

README의 Chrome 원본과20회 반복 결과는 명시한 실행의 표본을 기준으로 합니다. 후속 현재 코드의 설치 Chrome20회·번들 Chromium5회 응답 목표 초과는0건이었으나 이전 단발 초과의 원인·재발 방지까지 확정한 것은 아닙니다. 순간 FPS·RAF·headless 반복·실제 DevTools 기록의 경계는 [성능 문서](PERFORMANCE.md)를 따릅니다.

동시 다중 사용자 부하, 운영 인증/시크릿 관리, 실제 인프라 수집, 내구성 있는 알림함과 클라우드 운영 배포는 현재 검증 범위에 포함하지 않습니다.

## 문서 관리

현재 기능·계약·운영 정책과 재현에 필요한 문서는 공개 docs에 관리합니다. 학습 메모·작업일지·단계별 진단 원본은 Git 제외 경로에 보존하며 공개 문서에서 연결하지 않습니다. 문서 분류는 실행 기능·테스트 기준·성능 수치를 변경하지 않습니다.
