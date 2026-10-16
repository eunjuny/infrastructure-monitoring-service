# 커밋 고정 CI와 실행 근거

## 목적과 선택

이전 로컬 Jenkins는 개발 중인 작업 디렉터리를 직접 읽었습니다. 실행 중 파일을 바꾸면 한 빌드가 서로 다른 시점의 스크립트·소스를 사용해도 성공으로 표시될 수 있었습니다. 이제 **Git 전체 커밋 SHA → 독립 체크아웃 → 단계별 검증 → 이미지 ID → 실제 컨테이너**를 연결합니다. 미커밋 파일은 CI 입력으로 취급하지 않습니다.

```text
Jenkins: 신뢰하는 로컬 Git 저장소 + 전체 SHA
  → runs/<build>/source에 독립 clone·detached checkout
  → 공통 ci_pipeline.py
  → clean HEAD·tree 확인 → 빠른 검사·경보 규칙
  → Gradle·PostgreSQL → 프론트 테스트·빌드·audit
  → SHA/실행자/build 태그의 이미지 4개
  → 기본 화면 E2E → 실제 장애/해소 알림
  → 같은 백엔드·수신 이미지 + 인증 프론트로 Keycloak E2E
  → 소스·태그·실행 이미지 일치 확인 → 격리 컨테이너 정리
```

Jenkinsfile은 각 단계를 화면에 따로 표시하며 Python 단계 구현을 호출합니다. CI 실행자는 Jenkins 하나입니다. `scripts/ci.sh`는 깨끗한 체크아웃에서 같은 단계를 순서대로 재현하는 로컬 진입점이지 별도 CI 서비스가 아닙니다. 기존 개발 작업 폴더에서 사용하는 `check.sh fast/full`은 미커밋 변경 검증용으로 계속 사용할 수 있습니다.

## 실행

Java 21·Node 22·Python 3.9+·Git·Docker·Compose 2.24.4+와 Chromium이 필요합니다. 의존성 다운로드와 컨테이너 레지스트리 접근은 필요하지만 클라우드 자원·SMTP·AI API는 사용하지 않습니다.

```bash
# Jenkins: 커밋에 Jenkinsfile과 ci_pipeline.py가 들어 있어야 합니다.
# HEAD는 실행 요청 시 한 번 전체 SHA로 해석됩니다.
python3 scripts/jenkins-local.py --revision HEAD

# 다른 신뢰하는 로컬 Git 저장소의 특정 커밋을 검증하는 경우
python3 scripts/jenkins-local.py --repository /absolute/trusted/repository --revision <full-commit-sha>

# 별도로 만든 새 체크아웃 안에서 직접 실행
(cd frontend && npm ci && npx playwright install chromium)
bash scripts/ci.sh
```

한 체크아웃은 한 실행에만 사용합니다. 기존 provenance 보고서가 있거나 추적/미추적 소스가 바뀌면 거부합니다. 출력·node_modules·비밀 파일은 Git 제외 경로입니다. 이를 소스 무결성 검사 대상으로 오해하지 않도록, 실제 빌드 이미지의 label·ID와 실행 컨테이너의 Image ID를 추가 확인합니다. Jenkins clone은 `--no-local`로 Git 객체를 독립 전송하며 개발 폴더의 변경 파일을 복사하지 않습니다. 원본 Git 저장소에 자동 commit·push하지 않습니다.

## 공개 이미지와 개인 Docker 설정 분리

빌드 12/13은 공개 base image 조회 중 개인 Docker Desktop 자격증명 도우미 응답을 기다려 중단됐습니다. 별도 빈 설정의 공개 manifest 조회는 성공해, CI가 개인 키체인에 의존하지 않도록 클라이언트 설정만 분리했습니다. Docker 전체 재시작·개인 자격증명 삭제·키체인 보호 해제는 하지 않습니다.

- 각 CI 단계와 정리, Jenkins controller 기동 명령은 전용 임시 `DOCKER_CONFIG`를 사용합니다. 디렉터리 0700·설정 0600이며 정상/예외 종료 시 정리합니다. 강제 종료/호스트 중단 시 임시 파일이 남을 수 있으나 실제 비밀번호는 넣지 않습니다.
- 설정에는 Docker Hub의 명시적 익명 항목만 기록합니다. `auth`·identity token·`credsStore`·`credHelpers`와 기존 proxy/HTTP header는 복사하지 않습니다. 익명 항목은 OS 기본 도우미 자동 선택도 피합니다. TLS 검증을 끄거나 사설 레지스트리 인증에 실패했을 때 익명으로 재시도하는 기능이 아닙니다.
- 기존 Docker context의 로컬 Unix socket을 확인해 같은 엔진을 사용합니다. 원격 TCP/SSH·TLS override는 이 모드에서 거부합니다. 기존 설정/엔진·DB·인증 정책은 바꾸지 않습니다.
- macOS Docker Desktop은 호스트의 클라이언트 socket과 VM 안에서 컨테이너에 mount할 socket이 다릅니다. 기존 override가 없으면 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`을 CI 자식 환경에만 지정합니다. Ryuk을 끄거나 테스트를 제외하지 않습니다. [Testcontainers 설정](https://java.testcontainers.org/features/configuration/)을 따릅니다.
- 설치된 Buildx·Compose 실행 파일만 임시 plugin 디렉터리에 연결합니다. 별도 설치나 Docker 전역 context/builder 변경은 없으며 Buildx 상태도 임시 경로로 분리합니다.
- 모든 단계의 자식 명령에 같은 제한 환경을 넘기고, 이미지 inspect/실행 컨테이너 확인도 해당 endpoint를 사용합니다. nested 인증 CI는 상위 임시 환경을 상속합니다.

현재 CI는 공개 이미지 조회·로컬 이미지 빌드/실행만 수행하고 private registry login/push는 지원하지 않습니다. Docker Hub 익명 rate limit·다운로드 장애는 그대로 실패하며 인증 정보를 자동 대체하지 않습니다. 사설 레지스트리가 필요해지면 전용 최소 권한 CI 자격증명과 보관/정리 정책을 별도로 설계해야 합니다. 기업 proxy/인증서·원격 Docker 환경도 이 로컬 모드에 자동 이식하지 않습니다.

Docker의 [클라이언트 설정 위치와 endpoint](https://docs.docker.com/reference/cli/docker/), [Buildx 설정 경로](https://docs.docker.com/build/building/variables/), [인증 저장소](https://docs.docker.com/reference/cli/docker/login/) 규칙을 사용합니다. 회귀 테스트는 전역 설정·환경 보존, 파일 권한, 기존 plugin 연결, 실패 시 정리, 원격/TLS 환경 거부와 단계 환경 전달을 확인합니다. 실제 전체 실행 결과는 WH-027에 기록합니다.

## 남기는 증거

`frontend/.validation/ci/provenance.json`에는 다음 정보만 저장합니다.

- 전체 commit SHA와 tree SHA, CI 실행자, build ID, 준비 시각
- 단계별 passed/failed·실행 시간, 최종 complete와 cleanup 결과
- 이미지 4개의 태그·Docker image ID·커밋/빌드 label
- 기본/인증 runtime의 실제 이미지 일치 확인 여부

Jenkins가 이 파일, 서버/인증 JUnit, 안전한 인증·복구 요약을 수집합니다. 인증 화면의 비밀번호·토큰·원본 trace는 수집하지 않습니다. cleanup이 실패해도 Jenkins의 결과 수집은 시도하며, 전체 성공으로 간주하지 않습니다. SIGKILL·호스트 종료까지 자동 정리를 보장하지 않습니다.

## Jenkins 단일 CI 선택

동일 검증을 두 도구에서 중복 실행할 필요가 없어 사용자 선택에 따라 Jenkins로 통일했습니다. 지원 공고의 Jenkins 파이프라인 경험과 현재 실제 검증 환경을 함께 유지합니다. `.github/workflows/verify.yaml`은 제거했고 CI 실행자 값도 `jenkins`와 로컬 재현용 `local`만 허용합니다. GitHub는 소스 저장소로 사용할 수 있지만 Actions를 CI로 실행하지 않습니다.

Jenkins 호스트 agent는 Docker/사용자 권한이 있으므로 불특정 외부 PR을 실행하면 안 됩니다. 로컬 Jenkins의 Git 입력은 신뢰하는 로컬 저장소 경로이며 원격 SCM 자격 증명·webhook은 아직 구성하지 않았습니다. 현재 자동 push/PR 빌드가 연결된 것처럼 설명하지 않습니다. 원격 이벤트 연결은 제출 커밋·저장소 연결 후 별도 검증해야 합니다.

## 검증과 한계

2026-10-16 사용자 승인으로 원본 안정화 버전도 기능·CI·문서로 분리 커밋하고 기존 초기 원격 이력을 tree 변경 없이 보존해 비공개 main에 게시했습니다. WH-028에 실제 반영 커밋을 기록합니다. 아래 빌드 15는 동일 실행 소스의 fixture SHA를 검증한 결과이며, 게시한 원본 SHA에서 전체 CI를 다시 돌렸다고 주장하지 않습니다. 추가 Git attribute는 Windows Wrapper CRLF 보존용이며 실행/테스트/CI 본문은 동일합니다. 원격 SCM/webhook 자동 실행은 아직 별도입니다.

2026-10-16 최신 실행은 **Jenkins 빌드 15 SUCCESS**입니다. 공개 Docker 설정 격리·Desktop socket 매핑을 포함한 fixture 커밋의 새 체크아웃에서 오프라인 46·서버 107(실제 PostgreSQL 44)·프론트 77·기본 브라우저 11·실제 Keycloak 5·규칙 9시나리오·실제 복구·8단계·정리를 통과했습니다. 이미지 4개의 SHA/build label·실행 Image ID·소스 tree·JUnit 112와 CI 컨테이너/임시 설정 잔존 0을 확인했습니다. 전역 Docker 설정·기존 컨테이너 18개를 보존했고 전체 Docker 재시작은 하지 않았습니다. [최신 근거](verification/2026-10-16-public-image-ci.json)를 참고합니다.

이전 빌드 13은 빠른 검사 39·서버 107·프론트 77을 통과했지만 이미지 단계 도우미 대기로 `ABORTED`·정리 완료였습니다. [당시 근거](verification/2026-10-16-ci-retry.json)를 보존합니다. 첫 격리 빌드 14는 Ryuk의 host/VM socket 경로 차이로 실패했고, socket 매핑을 보완한 빌드 15가 최종 성공입니다. 개인 도우미 자체 수리·원격 SCM·controller 신규 bootstrap·수동 성능까지 완료했다고 주장하지 않습니다.

오프라인 회귀는 깨끗한 SHA/tree, 변경 파일 거부, 이전 커밋 독립 clone, 잘못된 실행자/빌드 ID, 이미지 label/ID 불일치, 단계 순서·실패·소스 변경 차단, 장애 복구의 이미지 오버레이 유지를 검사합니다. 전체 실행 결과는 [작업 이력 WH-023](WORK_HISTORY.md#wh-023--커밋-고정-ci와-이미지-추적)과 기준 문서에 기록합니다.

Jenkins 빌드 11에서 8개 공통 단계와 정리를 모두 통과했습니다. 오프라인 38·서버 102·프론트 77·기본 E2E 11·실제 Keycloak 5·경보 9시나리오 및 장애/해소 알림을 검증했습니다. 이미지 4개의 label/ID와 실제 기본/인증 컨테이너를 확인했고 컨테이너 잔존은 0입니다. [실행 근거](verification/2026-10-15-commit-pinned-ci.json)를 참고합니다.

위 빌드 11 검증은 원본의 미커밋 작업을 보존하기 위해 비밀값/생성물을 제외한 소스로 만든 **임시 fixture Git 저장소**를 사용했습니다. 원본 HEAD가 최신 기능 커밋이라는 뜻이 아닙니다. 실제 프로젝트 커밋·원격 SCM 연결은 별도 작업입니다. 최종 문서/결과 기록은 fixture 생성 후 갱신했습니다. 이후 Jenkins 단일화는 workflow 제거·실행자 검증만 변경했으며 오프라인 39개와 빠른 검사를 재통과했습니다. 전체 CI 단계를 변경하지 않아 빌드 11을 새 실행 결과로 바꾸지 않습니다.

이 설계는 실행 소스와 이미지의 대응을 검증하는 것이지, 공급망 서명·SLSA attestation·재현 가능한 바이트 동일 빌드를 보장하지 않습니다. 모든 base image·Jenkins plugin을 digest/버전 고정한 상태도 아닙니다. 의존성 잠금·Wrapper 체크섬은 유지하지만 레지스트리/실행 환경 차이에 따른 결과는 별도 확인해야 합니다. 고정 CI 포트를 사용하므로 한 호스트에서 전체 CI를 동시에 실행하지 않습니다. Jenkins는 동시 빌드를 막지만 로컬 재현 명령과도 동시에 실행하지 않아야 합니다. 장기 로컬 Jenkins 사용 시 빌드별 체크아웃/이미지의 디스크 보관 정책이 필요합니다.
