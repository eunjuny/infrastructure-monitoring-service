# Infrastructure Monitoring Service

15만 개의 Mock Pod를 PostgreSQL에 저장하고, 계층 탐색·검색·상태 갱신을 제공하는 인프라 모니터링 서비스입니다. 리소스를 필요한 만큼만 조회·렌더링하며, 사용자별 조회 권한과 서비스 운영 관측을 함께 제공합니다.

> 과제에서 정의한 `Cluster → Node → Namespace → Pod` 드릴다운 모델을 사용합니다. 실제 Kubernetes 클러스터에 연결하지 않으며, 리소스 상태·사용률은 Mock 데이터입니다.

## 빠른 실행

Docker를 실행한 상태에서 **프로젝트 루트**에서 아래 명령을 실행합니다. Python 3.9+와 Docker Compose v2가 필요하며, 호스트에 Java·Node.js를 설치할 필요는 없습니다.

```bash
python3 scripts/auth-local.py
```

이미지 빌드, DB 초기화, Flyway, 15만 Pod 적재, 인증·모니터링 서비스 시작까지 처리합니다. 최초 실행은 이미지·의존성 다운로드를 위한 인터넷 연결이 필요합니다. 준비 완료 메시지 후 **[서비스 열기](http://127.0.0.1:5176/)** 에 접속합니다. 같은 명령으로 재실행하면 기존 계정·DB를 재사용합니다.

| 서비스 계정 | 조회 범위 | Pod 수 | 운영 관측 |
|---|---|---:|---|
| `admin` | 모든 Cluster | 150,000 | 사용 가능 |
| `user1` | Cluster 1~10 | 100,000 | 접근 불가 |
| `user2` | Cluster 11~20 | 50,000 | 접근 불가 |

비밀번호는 최초 실행 시 생성하는 Git 제외 파일 `.local/auth/credentials.json`에서 로컬 편집기로 확인합니다. 기존 설치는 저장된 비밀번호를 사용합니다. **이 파일과 DB 볼륨을 임의로 삭제하지 마세요.** 모든 공개 포트는 `127.0.0.1` 전용이며 외부 공개용 설정이 아닙니다.

상태 확인과 정지(데이터·계정 보존):

```bash
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml ps
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml stop
```

다른 실행 모드, 포트, 문제 해결은 [실행 가이드](docs/RUNNING.md)를 참고합니다. 이전 두 PostgreSQL 서버를 사용하는 설치만 [DB 이행 절차](docs/SHARED_AUTH_DATABASE.md)가 필요합니다.

## 주요 기능

| 기능 | 동작 |
|---|---|
| 계층 탐색 | 노드를 펼칠 때 하위 리소스를 조회하고, 선택 시 상세 속성과 상위 경로를 표시합니다. |
| 리소스 진단 | Cluster/Namespace 하위 집계, Node 준비/압박 조건·용량, Pod 실행/준비 상태·사유·대표 이미지·워크로드를 표시합니다. |
| 검색·필터 | 이름·유형·상태·Cluster·Namespace 조건을 조합하고, 검색 결과의 계층 위치로 이동합니다. |
| 대용량 목록 | 서버 커서 페이지와 가상 리스트로 전체 데이터를 한 번에 내려받거나 DOM에 그리지 않습니다. |
| 상태 갱신·이력 | 수동 새로고침과 자동 폴링을 지원하며 상태·속성 변경 전후를 상세 패널에서 확인합니다. |
| 로그인·접근 제어 | Keycloak 로그인 후 서버가 JWT와 Cluster 권한을 검증해 목록·집계·상세·이력을 제한합니다. |
| 운영 관측 | 관리자가 요청 수·오류·API/DB P95·JVM Heap·DB 연결과 추이 차트·경보를 확인합니다. |
| 경보 메일 | ADMIN은 경보 카드의 `관리자 메일` 팝업에서 수신 주소·발송 여부를 관리하고, Alertmanager가 발생·해소 메일을 직접 보냅니다. |

화면 왼쪽에서 계층을 펼치고, 가운데 목록에서 검색·필터·선택을 수행합니다. 오른쪽 상세 패널에는 속성·경로·변경 이력이 표시됩니다. 운영 관측은 관리자 메뉴에서 접근합니다.

상세 `리소스 진단`은 Mock 메타데이터·상태 시나리오와 실제 DB 범위 집계입니다. 기존 설치는 백엔드/프론트 재빌드 후 Flyway V4가 적용되며 계정·15만 리소스의 기존 상태/관계를 초기화하지 않습니다. [필드·범위·이행·검증](docs/RESOURCE_DIAGNOSTICS.md)을 참고합니다.

## 구조와 기술

인증 환경은 **PostgreSQL 서버 하나**에 서비스용 `monitoring`과 Keycloak용 `identity` DB를 둡니다. 각 전용 계정의 접속 권한을 분리하며 개발 모드·비인증 Compose의 DB와는 공유하지 않습니다.

| 영역 | 기술 | 적용 |
|---|---|---|
| Backend | Java 21, Spring Boot 3.5, JPA/Hibernate | REST API, DTO projection, 트랜잭션, JWT 검증 |
| Database | PostgreSQL 17, Flyway | 계층 데이터·이력, 스키마 이행, 검색·커서 인덱스 |
| Frontend | Vue 3, TypeScript, Vite | 지연 로딩 트리, 가상 리스트, 제한 캐시, 폴링 |
| Authentication | Keycloak, OAuth2/OIDC | Authorization Code + PKCE 로그인, 역할·Cluster 권한 |
| Observability | Actuator, Micrometer, Prometheus, Alertmanager | 계측·시계열 조회·경보 평가·독립 내부 수신 |
| Build / CI | Gradle Wrapper, Docker Compose, Jenkins | 버전 고정 빌드, 컨테이너 실행, 격리 검증 |
| Test | JUnit 5, Mockito, Testcontainers, Vitest, Playwright | 단위·실제 PostgreSQL·컴포넌트·브라우저 검증 |

브라우저는 NGINX를 통해 Spring Boot API를 호출하고, 백엔드는 JPA로 허용 범위의 데이터만 조회합니다. Keycloak은 로그인·토큰 발급을 담당하며 API는 서명과 권한을 검증합니다. 운영 지표는 `Micrometer → Actuator → Prometheus → 고정 PromQL → 관리자 화면`으로 전달됩니다. 경보는 Alertmanager가 내부 수신기에 전달하며, SMTP 설정·활성화 후 관리자 메일로도 전송합니다.

[전체 구성도](docs/PROJECT_ARCHITECTURE.md) · [API 명세](docs/API.md) · [인증·권한 설계](docs/AUTHENTICATION.md)

### 대용량 처리 방식

- **저장·조회:** Cluster 20 / Node 200 / Namespace 2,000 / Pod 150,000개를 실제 DB에 저장합니다. 별도 `scripts/seed.sql` 배치로 초기 적재하고, 서비스는 요청 시 PostgreSQL을 인덱스·keyset 페이지로 조회합니다. 부모·상태·ID 인덱스와 이름 검색용 `pg_trgm`을 사용하며 요청 때 전체 데이터를 생성하거나 메모리에 적재하지 않습니다.
- **응답 제한:** 조건에 연결된 keyset 커서로 목록을 최대 100개씩 반환합니다. 전체 데이터 메모리 적재나 매 요청 전체 순회를 하지 않습니다.
- **렌더링 제한:** 화면에 보이는 행만 렌더링하고 목록은 최근 5페이지·500개까지만 유지합니다. 임의 위치 점프가 아닌 순차 탐색입니다.
- **갱신 제한:** 현재 조회창·펼친 계층·선택 상세만 폴링합니다. 숨김·오프라인에서 중지하고 실패 시 백오프합니다.
- **변경 정합성:** Mock 상태 변경과 변경 이력을 한 트랜잭션으로 저장합니다. 이력은 24시간·최신 20,000개 기준으로 배치 정리합니다.

[JPA 조회 설계](docs/JPA_QUERY_DESIGN.md) · [렌더링·캐시 설계](docs/FRONTEND_RENDERING.md) · [운영·보관 정책](docs/OPERATIONS_AND_DELIVERY.md)

## 테스트와 성능 검증

리소스 진단 V4 변경은 통합 검증·인증 환경 반영까지 완료했습니다. 기존 리소스·풀이/변경 이력과 계정 데이터 보존을 확인했으며, 일시적으로 발생한 구 이미지와 신규 seed 불일치는 WH-042에서 해결했습니다. 아래 성능 측정은 별도 조건·표본의 기록이며 전체 성능 목표 합격 여부와 구분합니다.

```bash
# Python·Git만 사용: 도구 회귀, 문서·소스 규칙, 공백 검사
bash scripts/check.sh fast

# Java 21·Node.js 22.12 이상 22.x·Docker 필요: 서버·실제 DB·프론트 테스트와 빌드
bash scripts/check.sh full
```

실제 Keycloak 로그인·계정별 권한 E2E는 [실행 가이드](docs/RUNNING.md)의 별도 명령으로 검증합니다. Jenkins는 커밋 SHA를 고정한 독립 체크아웃에서 이미지·실제 API·인증·경보 복구까지 검사합니다. 최신 실행 범위와 이전 CI 결과는 [검증 기록](docs/PROJECT_GUIDE.md#현재-검증-결과)에서 구분합니다.

### 브라우저 반복 측정

2026-10-18 **단일 PostgreSQL·인증 관리자·15만 Pod·프로덕션 NGINX**에서 측정했습니다. Apple M1 Pro·RAM 16GiB, 자동 측정 Chrome **155.0.8059.80**, CPU **4×**, 캐시 **OFF**, 네트워크 제한 없음·1440×1000 조건입니다. 사용자의 현재 버전 진행 요청에 따른 검증이며 최신 Chrome 확인/업데이트 완료를 뜻하지 않습니다.

20회마다 새 context에서 실제 PKCE 로그인 후 SSO 재진입·탐색을 측정합니다. DOM 결과 검증과 animation frame 2회를 포함하며, 검색의 250ms 디바운스는 제외합니다. FULL 모드에서는 Pod 1을 잠시 변경해 목록·상세 갱신을 확인한 뒤 원복합니다. 시작/종료와 재현 명령은 [측정 방법](docs/PERFORMANCE.md)에 있습니다. 빌드·E2E·네이티브 화면 검사와 겹치지 않은 두 번째 전체 실행 결과입니다.

| 동작 | 목표 | 중앙값 | P95 | 최댓값 | 목표 초과 |
|---|---:|---:|---:|---:|---:|
| SSO 재진입 | 500ms | 315.9ms | 533.1ms | 1,223.9ms | 2/20 |
| 첫 계층 펼침 | 100ms | 44.1ms | 205.2ms | 226.9ms | 3/20 |
| 접기 | 100ms | 26.8ms | 28.2ms | 29.5ms | 0/20 |
| 캐시 재펼침 | 100ms | 27.7ms | 28.9ms | 30.7ms | 0/20 |
| 이름 검색 | 300ms | 72.5ms | 192.6ms | 466.4ms | 1/20 |
| 상태 필터 | 200ms | 46.0ms | 62.7ms | 124.1ms | 0/20 |
| 변경 없는 새로고침 | 200ms | 74.9ms | 110.9ms | 671.0ms | 1/20 |
| 실제 변경 새로고침 | 200ms | 76.8ms | 107.2ms | 316.0ms | 1/20 |
| 실제 변경 자동 폴링 | 200ms | 118.2ms | 158.4ms | 158.4ms | 0/3 |

**기능 오류는 0건이지만, 지연 목표 초과가 남아 전체 합격은 아닙니다.** 인증 모듈/설정 병렬 로딩·silent SSO·갱신 시각 처리 개선을 적용했습니다. 개선 전 5회·네이티브 검사와 겹친 첫 20회·별도 두 번째 20회 모두 [측정 표본](docs/performance/2026-10-18-final-auth.json)에 보존합니다. 단일 느린 표본에서 여러 API 대기가 함께 증가했으며 원인을 확정하지 않았습니다. 느린 표본 삭제·목표/준비 조건 완화는 하지 않았습니다.

이후 계측 도구의 selector 보호를 재확인한 3회 smoke에서도 자동 갱신1회 초과가 관측됐습니다. 적은 표본을 위20회 결과 대신 사용하지 않으며 [모든 후속 결과와 남은 점검](docs/FINAL_PERFORMANCE_REVIEW.md)을 구분해 기록합니다.

- 스크롤: 실제 15만 데이터에서 캐시 500개·표시 19행, 4초 CDP DrawFrame **59.8fps**. 보조 합성 프레임이며 가이드의 **Chrome FPS 미터 직접 검증은 남아 있습니다**.
- 탭 메모리: 실제 Chrome 작업관리자 **74.3MB**(참고 목표 300MB). GUI Chrome153·500개 캐시·DevTools 종료 상태의 별도 관측으로, CPU4× 반복 결과와 동일 조건은 아닙니다.
- 기능 회귀: 리소스 진단 추가 후 서버 **140개**, 프론트 **96개**, 실제 Keycloak E2E **14개** 통과. [진단 검증](docs/RESOURCE_DIAGNOSTICS.md)에 DB 이행·권한·모바일 결과를 정리했습니다. [경보 메일](docs/ALERT_EMAIL.md)은 관리자 팝업 UI와 합성 firing/resolved SMTP 전달을 확인했습니다. SMTP 서버의 수락 확인이며 편지함 도착/열람은 확인하지 않았습니다.

<details>
<summary>실제 Chrome Performance 패널 캡처</summary>

![Chrome Performance 실제 타임라인](docs/images/chrome-performance-final-20261018.jpg)

현재 실행 중인 GUI Chrome153에서 CPU4×·캐시 OFF를 설정한 후 실제 초기 로드를 기록했습니다. 네이티브/확장 계측 비용을 포함한 단일 타임라인으로, 반복 측정이나 전체 목표 합격을 대신하지 않습니다. [캡처 조건·한계](docs/CHROME_PERFORMANCE_CAPTURE.md)

![Chrome 작업관리자 실제 탭 메모리](docs/images/chrome-task-memory-20261018.jpg)

</details>

## 범위와 문서

실제 Kubernetes API·공개 HTTPS 운영 배포는 포함하지 않습니다. 내부 수신기는 최근 200개 이벤트를 메모리에 보관합니다. [관리자 경보 메일](docs/ALERT_EMAIL.md)은 로컬 SMTP·수신 주소·명시적 활성화가 모두 설정돼야 전송됩니다. 합성 경보의 발생·해소 메일이 SMTP 서버에 각각 수락된 것을 확인했으며 최종 편지함 도착/열람은 확인하지 않았습니다. SMS·외부 AI·클라우드 API는 사용하지 않습니다.

| 문서 | 내용 |
|---|---|
| [실행 가이드](docs/RUNNING.md) | 인증 시연·개발·비인증 Compose·검증 명령·문제 해결 |
| [프로젝트 기준 문서](docs/PROJECT_GUIDE.md) | 전체 기능·구조·기술 선택·현재 검증 상태 |
| [요구사항 대조](docs/REQUIREMENTS_AND_HARDENING_REVIEW.md) | 과제 요구와 구현 범위·제약 |
| [운영 관측·경보](docs/ALERTING_AND_RECOVERY.md) | 경보 조건·전달 경로·복구 검증 |
| [CI 재현성](docs/CI_REPRODUCIBILITY.md) | Jenkins·커밋 고정·이미지/컨테이너 검증 |
| [개발 가이드](docs/DEVELOPMENT_GUIDE.md) | 코드·보안·테스트·문서 동기화 규칙 |
| [작업 이력](docs/WORK_HISTORY.md) | 구현·검증·시행착오와 후속 작업 |
