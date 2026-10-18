# 실행·검증 가이드

제출 시연은 **인증 Compose(5176)** 하나만 실행하면 됩니다. 개발·비인증 Compose·Jenkins를 동시에 띄울 필요는 없습니다. 모든 명령은 별도 설명이 없으면 프로젝트 루트 기준입니다.

## 1. 제출 시연: 한 명령으로 시작

필수는 Python 3.9+와 실행 중인 Docker 엔진·Compose v2입니다. Java/Node 빌드는 컨테이너에서 수행하며, 최초 실행에는 이미지·의존성 다운로드를 위한 네트워크가 필요합니다. CI까지 실행하려면 Compose 2.24.4 이상을 사용합니다.

```bash
python3 scripts/auth-local.py
```

시작 스크립트는 다음 순서로 준비합니다.

1. 기존 비공개 설정을 재사용하거나 평가 계정(admin/user1/user2, 공통 비밀번호 `1234`)과 별도 DB 비밀 파일을 생성합니다.
2. 백엔드·프론트엔드·내부 수신기 이미지를 빌드합니다.
3. PostgreSQL 서버 하나에 서비스용 `monitoring`·Keycloak용 `identity` DB와 각각의 전용 계정을 준비합니다.
4. Keycloak·백엔드를 시작하고 Flyway 스키마 이행·15만 Pod seed 후 프론트엔드를 시작합니다.
5. Prometheus·Alertmanager·내부 수신기를 시작하고 공개 OIDC 설정과 프론트의 인증 설정을 확인합니다.

`Authentication environment ready` 메시지가 나오면 [서비스](http://127.0.0.1:5176/)에 접속합니다. 상시 서비스는 7개이며 `db-init`·`auth-import`·`seed`의 정상 종료는 오류가 아닙니다.

| 용도 | 주소 |
|---|---|
| 서비스·로그인 진입 | http://127.0.0.1:5176/ |
| 백엔드 API | http://127.0.0.1:8083/api/resources/summary |
| Keycloak | http://127.0.0.1:8183/ |
| Prometheus | http://127.0.0.1:9095/ |
| Alertmanager | http://127.0.0.1:9195/ |

API를 토큰 없이 직접 열었을 때 401은 정상입니다. 서비스에서 로그인해 사용합니다. DB·Actuator 관리 endpoint·내부 수신기는 호스트에 공개하지 않습니다.

### 로그인과 시연

관리자 메일 등록·SMTP 비공개 설정·발송 활성화는 [메일 설정 가이드](ALERT_EMAIL.md)를 따릅니다. SMTP 미설정 상태에서는 외부 메일이 나가지 않습니다.

- `admin`: 전체 150,000개 Pod, 상세·이력·운영 관측·경보.
- `user1`: Cluster 1~10의 100,000개 Pod.
- `user2`: Cluster 11~20의 50,000개 Pod.
- 새 설치의 세 평가 계정은 모두 비밀번호 `1234`로 로그인합니다. 기존 설치에서 암호를 별도 변경했다면 `.local/auth/credentials.json`의 설정을 사용합니다.

평가 계정은 공개된 테스트 데이터입니다. 서비스 ADMIN과 Keycloak 초기화 관리자 `kc-bootstrap`은 별개이며 DB·초기화 관리자·내부 제어용 비밀값은 무작위로 생성합니다. 재실행은 기존 값을 사용합니다. CI의 임시 사용자 비밀번호도 별도 무작위 생성합니다.

로그인 후 계층 펼침 → 이름·상태·범위 검색 → 상세·계층 위치 → 자동 갱신·변경 이력을 확인합니다. 관리자는 운영 관측에서 서비스 카드·P95 추이·경보를 볼 수 있습니다. 사용자 계정을 바꿔 조회 범위가 달라지는지도 확인할 수 있습니다.

### 상태 확인·정지·재시작

```bash
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml ps
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml stop
# 다시 시작: 기존 비밀 파일·DB를 유지하고 현재 소스로 빌드
python3 scripts/auth-local.py
```

정지는 컨테이너 실행만 멈추며 데이터·계정을 삭제하지 않습니다. `down -v`나 비밀 파일 삭제는 재시작 방법이 아닙니다. 스크립트 재실행으로 기존 Realm의 암호·권한을 덮어쓰지 않습니다.

### 시작되지 않을 때

| 증상 | 확인·대응 |
|---|---|
| Docker 연결 실패 | Docker Desktop/엔진을 먼저 시작하고 같은 명령을 다시 실행합니다. |
| 이미지 다운로드·빌드 실패 | 네트워크·레지스트리 제한·Docker 자격 증명 도우미 상태를 확인합니다. DB 초기화로 해결하지 않습니다. |
| 포트 사용 중 | 위 포트를 점유한 프로세스가 무엇인지 확인합니다. Keycloak redirect/issuer가 연결되므로 웹 포트만 임의 변경하지 않습니다. |
| `Legacy identity database detected` | 이전 두 서버 설치입니다. [백업·이행·검증 절차](SHARED_AUTH_DATABASE.md)를 진행하며 일반 시작을 반복하거나 볼륨을 지우지 않습니다. |
| 옛 사용자명 설정 안내 | 기존 사용자 ID를 보존하는 [이름·범위 이행](USER_SCOPES_AND_LOGIN_THEME.md)을 확인합니다. private 파일을 새로 만들지 않습니다. |
| 로그인 오류 | 저장된 계정과 비밀번호를 로컬에서 확인합니다. Keycloak import는 기존 Realm을 덮어쓰지 않습니다. |
| 지표·차트에 표본 없음 | 요청·수집 표본이 쌓인 후 다시 확인합니다. 수집 성공은 전체 API의 정상이나 오류 없음과 같지 않습니다. |

구성 검사에는 비밀값을 출력하지 않는 `docker compose --env-file .local/auth/compose.env -f compose.auth.yaml config --quiet`를 사용합니다. 전체 config·inspect·환경변수 덤프를 공개하지 않습니다. 단일 PostgreSQL 설계와 기존 설치 복구는 [DB 구성 문서](SHARED_AUTH_DATABASE.md), 인증 경계는 [인증 설계](AUTHENTICATION.md)를 참고합니다.

## 2. 다른 실행 모드

| 모드 | UI / API | DB·인증 | 용도 |
|---|---|---|---|
| 인증 Compose | 5176 / 8083 | 단일 PostgreSQL·두 DB, Keycloak | 제출 시연·사용자별 권한·관리자 관측 |
| 호스트 개발 | 5174 / 8081 | 개발 DB(5544), 인증 없음 | Java/Vue 소스 수정·개별 실행 |
| 비인증 Compose | 5175 / 8082 | 별도 runtime DB, 인증 없음 | 핵심 기능의 컨테이너 실행 |

세 모드는 복제·이중화 서버가 아닙니다. 한쪽 변경이 다른 DB에 반영되지 않으며 Keycloak DB 통합은 인증 환경에만 적용됩니다.

### 호스트 개발

Java 21·Node.js 22.12 이상 22.x·Docker·Python이 필요합니다. 전역 Gradle/Maven은 필요하지 않습니다.

```bash
# 터미널 1: DB·관측 서비스를 시작한 뒤 백엔드 실행
docker compose --profile monitoring up -d --build db prometheus alertmanager alert-receiver
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=mock'
```

```bash
# 터미널 2: 백엔드 health UP·Flyway 완료 후 seed와 프론트 실행
docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/seed.sql
cd frontend
npm ci
npm run dev
```

Health는 http://127.0.0.1:8086/actuator/health, Prometheus는 http://127.0.0.1:9094/ 입니다. 정적 상태가 필요하면 백엔드의 `--args`를 생략합니다. Mock 배치는 실제 Kubernetes가 아닌 과제 DB의 상태·사용률을 변경하며 [폴링·변경 정책](LIVE_UPDATES.md)을 따릅니다.

CI와 병행할 때는 `bash scripts/run-local-backend.sh`로 고유 이름의 JAR를 실행해 CI 빌드와 런타임 파일을 분리할 수 있습니다. 호스트 백엔드/프론트는 각 터미널의 Ctrl+C로 종료합니다.

### 비인증 전체 Compose

```bash
docker compose -f compose.runtime.yaml --profile monitoring up -d --build
docker compose -f compose.runtime.yaml --profile monitoring ps
docker compose -f compose.runtime.yaml --profile monitoring stop
```

UI 5175·API 8082·Prometheus 9092를 사용하며 별도 볼륨에 seed를 적재합니다. 모니터링 프로필을 빼면 운영 관측의 Prometheus 조회가 실패하므로 위 명령에 포함합니다. 오래된 이미지가 실행 중이면 현재 소스로 재빌드해야 최신 기능이 반영됩니다.

## 3. 검증 명령

문서·도구 규칙만 확인하려면 `bash scripts/check.sh fast`, Java·실제 DB·프론트 전체 검사에는 `bash scripts/check.sh full`을 사용합니다. 후자는 Docker·Java 21·Node 22.x와 다운로드 네트워크가 필요하며 Playwright E2E/성능은 포함하지 않습니다.

### 실제 인증·권한 E2E

인증 Compose를 준비한 뒤 실행합니다. 브라우저 의존성 설치는 최초 한 번 필요합니다.

```bash
(cd frontend && npm ci && npx playwright install chromium)
python3 scripts/test-auth.py
PERF_LABEL=auth-admin-local PERF_REPETITIONS=20 python3 scripts/test-auth.py --performance
```

마지막 명령은 ADMIN의 실제 PKCE 로그인·SSO 재진입·탐색을 읽기 전용으로 측정합니다. 매 실행에 서로 다른 `PERF_LABEL`을 지정합니다. 실패·목표 초과를 성공으로 바꾸지 않으며 [인증 성능 기준](AUTHENTICATION_PERFORMANCE.md)을 따릅니다.

설치된 Chrome으로 실제 변경 반영·폴링·스크롤까지 확인하려면 `PERF_BROWSER=chrome PERF_FULL=1 PERF_LABEL=auth-full-local PERF_REPETITIONS=20 python3 scripts/test-auth.py --performance`를 사용합니다. FULL은 과제 인증 DB의 Pod 1을 잠시 변경하고 동시 수정이 없을 때만 복원합니다. 실사용 DB에는 실행하지 않으며 [상세 프로토콜](PERFORMANCE.md)을 먼저 확인합니다. 기본 읽기 전용 측정과 구분합니다.

기본 호스트 E2E(`npm run test:e2e`)와 `test:performance`·`test:performance:edges`는 **과제 DB의 Pod 상태를 변경하는 검증**을 포함합니다. 호스트 개발/프로덕션 preview의 준비 조건과 실행 방법은 [성능 프로토콜](PERFORMANCE.md)을 먼저 확인합니다. 실제 인증 E2E와 같은 명령으로 혼동하지 않습니다. SQL 실행 계획은 개발 DB에서 `docker compose exec -T db psql -U monitoring -d monitoring -v ON_ERROR_STOP=1 < scripts/explain.sql`로 확인합니다.

### Jenkins / 전체 CI

```bash
# 새로운 깨끗한 Git 체크아웃에서 커밋을 고정해 실행
bash scripts/ci.sh
# 또는 로컬 Jenkins에서 동일 검증을 실행
python3 scripts/jenkins-local.py --revision HEAD
```

두 명령을 연속으로 실행할 필요는 없습니다. Jenkins가 CI 실행자이고 `ci.sh`는 같은 검증의 로컬 재현 경로입니다. Jenkins agent에는 Java 21·Node 22.x·Docker·Python·Git이 필요하며, Compose 2.24.4 이상을 사용합니다. 미커밋 변경은 빌드에 포함되지 않습니다. 기존 프로젝트나 DB 볼륨을 초기화하지 않는 격리 환경에서 실행합니다.

인증 CI만 검사하려면 `bash scripts/ci-auth.sh`, 경보 규칙만 검사하려면 `bash scripts/test-alert-rules.sh`를 사용합니다. Jenkins UI는 http://127.0.0.1:8182/ 이며 별도 비공개 관리자 파일을 사용합니다. 공개 이미지 다운로드와 CI 실행 권한·보관·실패 정리는 [CI 재현성](CI_REPRODUCIBILITY.md), [인증 CI](AUTHENTICATION_CI.md), [운영 절차](OPERATIONS_AND_DELIVERY.md)를 참고합니다.

## 안전 경계

이 구성은 루프백·HTTP·Keycloak start-dev 기반의 로컬 과제 시연용입니다. 공개 HTTPS 운영 배포·실제 Kubernetes 연결·외부 메일/SMS는 포함하지 않습니다. Docker 관리자 권한은 비밀 설정·볼륨에 접근할 수 있으므로 신뢰하는 호스트에서만 실행합니다. 다른 프로젝트의 컨테이너·DB·전역 Docker 설정을 정리 대상으로 삼지 않습니다.
