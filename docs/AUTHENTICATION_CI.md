# 실제 인증·권한을 포함하는 CI

전체 파이프라인은 [커밋 고정 CI](CI_REPRODUCIBILITY.md)를 사용합니다. 인증 단계는 같은 커밋/build의 백엔드·독립 수신 이미지와 별도로 빌드한 인증 프론트 이미지를 `--no-build --pull never`로 실행하고 실제 container Image ID를 확인합니다. `ci-auth.sh` 단독 실행은 기존처럼 인증 이미지를 빌드할 수 있습니다.

## Keycloak과 CI는 서로 다른 역할입니다

Keycloak은 사용자의 로그인과 역할·Cluster 배정을 담당하는 서비스 의존성입니다. Jenkins는 코드를 빌드하고 테스트하는 CI 실행자입니다. 이번 기능은 Jenkins 로그인에 Keycloak을 연결하거나 Keycloak이 배포를 실행하게 만드는 것이 아닙니다.

서비스 배포물에는 로그인 코드뿐 아니라 브라우저 redirect URI·공개 issuer·내부 JWKS 주소·audience·client role·그룹 매핑이 맞아야 합니다. Java 컴파일과 가짜 JWT 테스트만 성공해도 실제 로그인은 실패할 수 있습니다. CI가 격리된 실제 Keycloak을 기동하고 브라우저 로그인부터 API 데이터 격리까지 확인해 이 설정/코드 조합의 회귀를 잡습니다.

```text
변경 → Gradle/Testcontainers·프론트 테스트 → 기본 이미지·실행 검증
     → CI 전용 Keycloak + 앱 DB + 백엔드 + 인증 프론트 + Prometheus
     → 실제 Authorization Code + PKCE 로그인
     → ADMIN 전체 조회 / A·B 반쪽 조회 / 교차 404 / 운영 403 / 익명 401
     → 비밀값 없는 JUnit·요약 → CI 실패/성공 → CI 컨테이너 정리
```

이 검사는 검증 단계이지 클라우드 배포·승인·롤백까지 완성한 CD가 아닙니다. 향후 배포 단계가 연결되면 인증 검사 실패 시 이미지 승격/배포를 중단하는 선행 조건으로 사용할 수 있습니다. 레지스트리 push·SSH·클라우드 API에 쓰는 **배포용 자격 증명**은 별개이며 이번 사용자용 Keycloak 계정을 그 용도로 쓰지 않습니다.

## 격리 설계

- `scripts/ci-auth.sh`는 별도 Compose 프로젝트 `infrastructure-monitoring-ci-auth`만 생성·정리합니다. `compose.auth.yaml`에 `compose.auth-ci.yaml`을 덧씌웁니다.
- Compose 2.24.4 이상이 필요합니다. CI 오버레이는 단일 PostgreSQL 저장소를 tmpfs로 바꾸고 `db-init`이 앱/identity 두 DB와 비관리자 계정을 준비합니다. 매 실행의 Flyway·seed·realm import를 확인하며 기존 개발/인증 DB를 지우거나 재설정하지 않습니다. [공유 서버·권한 분리](SHARED_AUTH_DATABASE.md)를 참고합니다.
- UI 5186·Keycloak 8193·API 8093·Prometheus 9096·Alertmanager 9196은 루프백이며 사용 중인 인증 UI 5176 등과 다릅니다.
- CI 전용 계정/DB 비밀번호는 매 실행 새로 생성합니다. `.local/auth-ci`는 0700, 비밀 JSON/env/import 파일은 0600·Git/Docker context 제외입니다. 로컬 인증 계정은 재사용하고 바꾸지 않습니다.
- API 권한은 서버가 검증한 JWT를 기준으로 합니다. 비인증 모드 fallback·password grant·테스트 토큰 주입은 사용하지 않습니다.
- DB tmpfs는 컨테이너 제거 시 사라집니다. 기존 named DB 볼륨을 `down -v`로 삭제하지 않습니다. CI import/관측 볼륨과 0600 비밀 파일은 로컬에 남을 수 있으며 Docker/호스트 관리자 접근 경계입니다. 시크릿 매니저를 대체하지 않습니다.

## 실행·보고서

2026-10-16 Jenkins 빌드 15에서 공개 이미지용 격리 Docker 클라이언트로 전체 파이프라인과 실제 Keycloak 5개를 통과했습니다. 서버 107+인증 5=JUnit 112개·실패/skip 0, 기본/인증 컨테이너의 image ID 일치와 정리도 확인했습니다. JWT 구조 보완·DB 풀 고갈/복구와 macOS Testcontainers socket 매핑이 포함됩니다. [최신 실행 근거](verification/2026-10-16-public-image-ci.json)를 참고합니다. 이전 빌드 11의 [커밋 고정 실행](verification/2026-10-15-commit-pinned-ci.json)과 빌드 10의 [실행 요약](verification/2026-10-15-auth-ci-alerting.json)은 당시 이력으로 보존합니다.

```bash
# 인증 단계만: 준비 → 실제 테스트 → 성공/실패 모두 CI 컨테이너 정리
bash scripts/ci-auth.sh

# 전체: 공통 검사·경보 규칙·서버/프론트·기본 화면·장애 복구·인증
bash scripts/ci.sh
```

실제 브라우저 테스트는 `python3 scripts/test-auth.py --ci`로 실행합니다. 준비 단계에 실패해도 예전 성공 보고서를 재사용하지 않도록 먼저 실패 sentinel을 기록합니다. JUnit XML과 수치 요약만 `frontend/.validation/auth-ci/`에 저장하며 단일 CI 실행자인 Jenkins가 수집합니다. 실행 여부와 제약은 [작업 이력](WORK_HISTORY.md)에 기록합니다.

실패 시 인증 코드·토큰·비밀번호가 들어갈 수 있는 Playwright call log·원본 JSON·trace·스크린샷·HAR·storageState를 저장하지 않습니다. JUnit에는 테스트 이름·결과·시간과 고정된 실패 안내만 넣습니다. 준비/테스트 실패는 CI 종료 코드로 전달되고 `EXIT` trap으로 CI 컨테이너를 정리합니다. SIGKILL·호스트 전원 종료까지 정리를 보장하는 것은 아닙니다.

관리자/사용자 기능 E2E와 별개로 Gradle은 실제 RSA/JWKS·PostgreSQL 기반의 잘못된 서명·발급자·audience·시간·sub·역할 테스트를 유지합니다. CPU4x 성능 검증은 기기 부하에 좌우되므로 매 CI 빌드의 기능 성공 기준과 섞지 않고 별도 [고정 조건 관리자 성능 명령](AUTHENTICATION_PERFORMANCE.md)을 사용합니다.

현재 사용자의 인증 설계·토큰 유효 기간·배정 변경 제약은 [인증 문서](AUTHENTICATION.md), 전달 경보·복구는 [경보 설계](ALERTING_AND_RECOVERY.md)를 참고합니다.
