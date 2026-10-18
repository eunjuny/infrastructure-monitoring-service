# Keycloak 인증과 세 계정의 클러스터 조회 권한

## 목적과 데이터 규모

운영 콘솔에서 계정별 조회 범위가 다른 상황을 검증합니다. 로그인 화면만 붙이지 않고 목록·검색·건수·상세·경로·이력과 운영 관측에 서버 권한을 적용합니다. 기존 과제/성능 모드와 독립된 로컬 인증 실행 모드를 제공합니다.

| 서비스 계정 | 역할 | 조회 범위 | 기본 seed의 Pod 수 |
|---|---|---|---:|
| admin | monitoring-api의 ADMIN | 전체 Cluster·리소스·변경 이력·운영 관측 | 150,000 |
| user1 | monitoring-api의 VIEWER | Cluster 1~10과 그 하위 | 100,000 |
| user2 | monitoring-api의 VIEWER | Cluster 11~20과 그 하위 | 50,000 |

세 계정은 한 인증 환경의 같은 150,000개 Pod를 공유하며 계정별로 복제하지 않습니다. 기존 100,000개 ID/부모는 유지하고 추가 50,000개만 앞쪽 1,000 Namespace에 50개씩 배정하여 Cluster 1~10은 100,000·11~20은 50,000개입니다. 권한은 ID 구간이 아니라 Cluster 관계이며 추가 ID 100001/125001/150000은 모두 user1 범위입니다. 기존 50001은 여전히 user2 범위여서 큰 ID가 무조건 user2라는 규칙이 아닙니다. 이전 user-a/user-b를 user1/user2로 바꾸되 동일 사용자 ID/sub·암호·role/group은 유지합니다. [데이터 이행·이름 변경·로그인 테마](USER_SCOPES_AND_LOGIN_THEME.md)를 참고합니다. 아래 과거 10만/균등 15만 검증과 새 분포를 구분합니다.

인증 환경과 기존 개발/runtime/CI는 서로 다른 DB 볼륨을 사용합니다. 환경마다 같은 규모의 seed가 존재하는 것과 사용자별 데이터 복제는 구분합니다. 기존 개발 데이터·볼륨을 옮기거나 삭제하지 않았습니다. 이 프로젝트의 계층은 과제 모델이며 실제 Kubernetes RBAC 연동이 아닙니다.

## 구성과 선택 이유

2026-10-18부터 인증 환경의 PostgreSQL 서버는 한 개입니다. `monitoring`·`identity` DB와 각각의 비관리자 계정을 분리하고 상대 DB 접속을 차단합니다. 기존 설치는 백업·내용 비교 후 이행하며 계정·리소스를 초기화하지 않습니다. 상시 컨테이너는 8개에서 7개로 줄어듭니다. [단일 DB 서버 설계·이행·복구·검증](SHARED_AUTH_DATABASE.md)을 참고합니다.

```text
브라우저(Vue) → Keycloak 로그인(Authorization Code + PKCE S256)
             → 메모리의 Access Token으로 /api 요청
Spring Security → RS256 서명·iss·aud·exp/nbf·sub 검증 → API용 client role 추출
  ADMIN → 전체 조회 및 운영 관측
  VIEWER → 검증된 groups의 Cluster ID를 JPA 조회/집계 조건에 바인딩
PostgreSQL → 권한 범위 안의 목록·DTO·건수 반환
Prometheus → 인증 환경의 backend만 수집 → ADMIN 운영 관측
```

- Keycloak Server 26.8.1과 별도 배포 주기의 keycloak-js 26.2.4를 고정했습니다. Spring Boot 3.5의 표준 OAuth2 Resource Server를 사용하고 Keycloak 전용 Java 어댑터는 추가하지 않습니다.
- Keycloak은 계정·비밀번호·client role·그룹 배정을 관리합니다. 이번에는 세 샘플 계정에 필요한 권한만 있으므로 별도 app_user/소유자 테이블을 중복 생성하지 않았습니다. 개인 소유 데이터나 앱 자체의 사용자 상태가 필요해지면 issuer+sub를 키로 별도 설계합니다.
- Realm은 `infrastructure-monitoring`이며 다른 프로젝트의 Realm/계정/DB를 재사용하지 않습니다. 서비스 client role은 `monitoring-api`의 ADMIN/VIEWER 두 개입니다. 다른 client의 ADMIN이나 사용자명 admin은 권한을 주지 않습니다.
- Keycloak의 관리자만 배정할 수 있는 `cluster-1`~`cluster-20` 그룹을 사용합니다. 브라우저가 보내는 clusterId는 필터일 뿐 권한이 아닙니다. 그룹 이름 접두어는 이 Realm의 예약 이름이며 중첩 그룹에 같은 이름을 재사용하지 않습니다.
- 등록·비밀번호 재설정·implicit/password grant는 브라우저 client에서 비활성입니다. 계정 관리는 Keycloak 관리자 작업이며 애플리케이션에 사용자/권한 변경 화면을 추가한 것은 아닙니다.
- 서비스 admin과 Keycloak 운영용 `kc-bootstrap`은 다릅니다. 서비스 로그인 계정은 3개이며 인프라 초기 설정용 master 관리 계정은 별도 비밀값으로 관리합니다. 서비스 ADMIN에게 Keycloak master 관리권을 주지 않습니다.

## 서버의 접근 제어

2026-10-16 보완: 유효한 서명이어도 만료 시각 `exp`가 없거나 groups/해당 API client roles가 문자열 배열이 아니면 401로 차단합니다. 배열은 최대 1,000개이며 `resource_access`/client 구조도 검사합니다. 정상 역할 없음의 403과 미배정 VIEWER의 0건 정책은 유지합니다. 설정이 잘못된 발급자의 토큰도 무조건 신뢰하지 않기 위한 경계이며 서명 없는 위조 토큰의 접근 허용을 뜻하지 않습니다. [재현·근거·검증 범위](QUALITY_GATES.md)를 참고합니다.

| 요청 | 서버 처리 |
|---|---|
| 목록·하위 탐색·전체 유형 검색 | 허용 Cluster 조건을 JPA WHERE에 넣고 limit/keyset 적용 |
| 상세·경로·변경 이력·명시적 범위 | 허용 범위의 존재 확인, 다른 범위는 404 |
| 건수 | 허용 범위만 count, VIEWER에게 전체 수 유출하지 않음 |
| 운영 관측 | 서비스 전체 지표이므로 ADMIN만, VIEWER는 403 |
| 토큰 없음/검증 실패 | 401, 비인증 로컬 모드로 전환하지 않음 |
| VIEWER 그룹 미배정 | 빈 목록·건수 0, 전체 조회로 처리하지 않음 |
| API용 역할 미부여 | 403, 다른 client role·사용자명으로 승격하지 않음 |

사용자 식별은 검증된 issuer+sub를 사용합니다. 커서에 issuer/sub/권한 범위의 지문을 연결해 다른 계정이나 권한으로 원본 커서를 재사용하면 400입니다. 커서는 서명된 권한 토큰이 아니며 사용자가 상태를 가공해도 JPA의 실제 접근 제한을 대체/해제할 수 없습니다.

서버는 Bearer 헤더 기반 stateless API입니다. 앱 로그인 세션 쿠키를 사용하지 않아 API CSRF 처리를 비활성화했으며 URL query/form의 토큰이나 Keycloak SSO 쿠키만으로 API에 접근하지 않습니다. API 인증과 Keycloak 브라우저 로그인 쿠키를 구분합니다. 관리 포트·DB는 내부 전용이고 NGINX는 `/actuator`를 차단합니다.

## 브라우저·토큰·주소 경계

- 인증 모드는 `login-required`로 시작하므로 앱은 Keycloak의 Authorization Code + PKCE S256 왕복이 끝나기 전 리소스 화면을 마운트하지 않습니다. 세션이 있으면 Keycloak이 기존 SSO 세션으로 로그인 화면 입력 없이 인증하고, 세션이 없으면 로그인 화면을 표시합니다. 토큰은 어댑터 메모리에만 둡니다. 인증이 끝나면 리소스 화면을 먼저 표시하고 `/api/auth/me`는 사용자명·역할 표시를 위해 비동기로 불러옵니다. 서버는 각 데이터 API에서 JWT·역할·Cluster 범위를 계속 검증하며, `/me` 완료 전에는 ADMIN 전용 운영 메뉴/메일 설정을 노출하지 않습니다. [공식 어댑터](https://www.keycloak.org/securing-apps/javascript-adapter)를 따릅니다.
- 공개 인증 설정 요청과 인증 모듈 다운로드를 병렬로 시작하며 인증 실패 시 비인증 모드로 대체하지 않습니다.
- 인증용 빌드만 `VITE_AUTH_ENABLED=true`로 생성합니다. Keycloak JS 코드는 인증 초기화 시 동적 로드합니다. 기본 과제 모드는 로그인/config 요청을 추가하지 않습니다.
- 인증 초기화·토큰 갱신에 실패하면 인증 화면/오류를 표시하거나 API 오류를 처리합니다. 인증 실패를 이유로 리소스 화면을 비인증으로 마운트하지 않습니다.
- Access/Refresh Token은 어댑터 메모리에만 보관하고 localStorage/sessionStorage·Git·보고서에 저장하지 않습니다. 계정에 관한 작은 UI 상태와 토큰을 분리합니다.
- 중앙 api.ts에서만 Bearer를 붙이고 갱신을 공유합니다. 기존 요청별 10초 제한에 토큰 갱신 대기도 포함하며 사용자 취소를 구분합니다.
- 로그아웃 시 폴링·요청·목록 캐시·선택 상세를 정리하고 Keycloak 로그아웃 후 로그인으로 돌아갑니다. 실제 사용자 변경은 전체 로그인 흐름을 다시 탑니다.
- 브라우저 issuer는 `http://127.0.0.1:8183/realms/infrastructure-monitoring`, 컨테이너 JWKS는 내부 `keycloak:8080`입니다. 통신 주소를 분리해도 issuer 검증은 유지합니다. audience는 `monitoring-api`입니다.
- Access Token 유효 기간은 120초이며 서버 기본 clock skew도 적용됩니다. 로그아웃/그룹 변경이 이미 복사된 JWT를 즉시 무효화하는 구조가 아닙니다. 배정 변경은 새 토큰에 반영되며 화면 재접속을 권장합니다. 즉시 회수가 필요하면 별도 세션/인트로스펙션 정책이 필요합니다.
- 키 캐시로 기존 유효 JWT를 검증할 수 있으므로 Keycloak 장애가 모든 기존 요청의 즉각 실패를 뜻하지는 않습니다. 신규 로그인/갱신 또는 키 검증이 불가능하면 실패하며 익명 권한으로 대체하지 않습니다.

## 로컬 실행과 계정 확인

필수: Python 3.9+와 Docker Compose v2. 인증 앱의 Java/Node 빌드는 컨테이너에서 수행하므로 시연 기동에는 호스트 Java/Node가 필요하지 않습니다. 호스트 테스트·Playwright 검증은 별도 개발 도구가 필요합니다. 시작·정지·모드별 준비 조건과 문제 해결은 [실행 가이드](RUNNING.md)를 참고합니다.

```bash
python3 scripts/auth-local.py
python3 scripts/test-auth.py
```

| 대상 | 주소 |
|---|---|
| 인증 화면 | http://127.0.0.1:5176/ |
| 인증 API | http://127.0.0.1:8083/api/resources/summary (토큰 없으면 401) |
| Keycloak | http://127.0.0.1:8183/ |
| 인증 환경 Prometheus | http://127.0.0.1:9095/ |

평가용 새 설치는 `admin`, `user1`, `user2`를 초기 데이터로 등록하고 세 계정 모두 비밀번호 `1234`를 사용합니다. README의 로그인 정보로 바로 평가할 수 있도록 공개한 테스트 데이터입니다. `--ci`는 매번 서로 다른 무작위 임시 계정 비밀번호를 사용합니다. DB·Keycloak 초기화 관리자·내부 제어 토큰은 평가 환경에서도 별도 무작위 생성합니다.

전체 초기 설정은 `.local/auth`에 저장하며 디렉터리 0700·파일 0600 권한을 사용합니다. 공개된 평가 계정 외의 비밀값은 소스 ZIP·문서·로그에 포함하지 않습니다. 고정 평가 계정은 루프백으로 제공되는 로컬 환경에서 사용하며 공개 운영 시에는 운영용 계정·비밀번호 정책을 구성해야 합니다.

기존 비밀 파일이 있으면 값을 재사용합니다. 재실행으로 기존 사용자 비밀번호를 초기화하거나 Realm을 삭제하지 않습니다. 시작 import는 이미 존재하는 Realm을 덮어쓰지 않으므로 이후 권한/계정 변경은 Keycloak 관리 작업으로 수행해야 합니다.

DB 볼륨을 유지한 채 비밀 파일만 지우면 기존 DB 비밀번호와 새 설정이 달라질 수 있습니다. 비밀 파일과 볼륨을 임의 정리하지 않으며 필요 시 별도 복구/교체 절차를 먼저 결정합니다.

Realm import에는 초기 비밀번호가 들어가므로 Git 제외 파일만 생성합니다. root init 컨테이너가 이를 전용 볼륨에 복사해 Keycloak UID 1000/0600으로 전달합니다. Docker 소유자·호스트 관리자는 환경변수/볼륨에 접근할 수 있으므로 이 경계를 비밀 저장소와 동일하다고 보지 않습니다. 백엔드 이미지를 만들 때 `.local`·중첩 `.validation`·테스트 캡처를 포함하지 않습니다.

상태 확인과 정지는 **값을 출력하는 config 명령 대신** 다음을 사용합니다. 볼륨 삭제는 자동 수행하지 않습니다.

```bash
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml ps
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml config --quiet
docker compose --env-file .local/auth/compose.env -f compose.auth.yaml stop
```

이 설정은 루프백·HTTP·start-dev의 로컬 데모입니다. 인터넷 공개·운영 Keycloak·HA·고정 도메인/TLS 구성은 포함하지 않습니다. 공개 배포에는 비인증 실행 경로를 노출하지 않고 인증 환경만 배포해야 합니다. Keycloak OSS 자체 사용료·외부 AI/SMTP API 호출은 없지만 컨테이너의 CPU/메모리·디스크는 사용합니다.

## 검증과 제한

서버 통합 테스트는 실제 RSA/JWKS·PostgreSQL에서 서명·issuer·audience·만료·sub·역할과 그룹 구조, 권한별 목록/집계·교차 접근·커서 바인딩을 검증합니다. 실제 Keycloak 브라우저 E2E는 세 계정의 PKCE 로그인·전체/제한 조회·운영 메뉴/API 접근·로그아웃·토큰 비영속화를 확인합니다.

인증 CI는 별도 Keycloak·tmpfs DB·임시 계정을 사용하며 로컬 계정을 바꾸지 않습니다. 실패 보고서에는 인증 코드·JWT·비밀번호·HAR·storageState를 저장하지 않습니다. [인증 CI](AUTHENTICATION_CI.md), [계정·테마](USER_SCOPES_AND_LOGIN_THEME.md), [성능 조건](PERFORMANCE.md)을 참고합니다.
