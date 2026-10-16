# 품질 게이트와 개발 하네스

## 구성과 실행

```text
AGENTS.md → 개발 가이드·현재 설계 읽기 → 작은 코드/테스트/문서 변경
    → 빠른 게이트 → 해당 단위·실제 DB·브라우저·성능 검증
    → CI의 같은 게이트 → 리뷰·이력 기록
```

[개발 가이드](DEVELOPMENT_GUIDE.md)는 사람이 판단할 설계·책임·보안·완료 규칙입니다. 아래 도구는 반복 가능한 일부 검사만 자동화합니다. 새 Python 패키지·유료 서비스·외부 AI 호출은 필요하지 않으며 Python 3.9 이상과 Git을 사용합니다.

프로젝트 루트에서:

```bash
# 오프라인: 게이트 자체 테스트 → 소스/문서 검사 → git diff --check
bash scripts/check.sh fast

# 위 검사 + Gradle build + npm ci/단위/타입/빌드/audit
bash scripts/check.sh full

# 새 깨끗한 Git 체크아웃: 전체 테스트/버전 이미지/격리 runtime/인증·복구
bash scripts/ci.sh
```

`full`은 Docker 기반 Testcontainers와 의존성/취약점 조회 네트워크가 필요할 수 있습니다. 애플리케이션 runtime을 직접 띄우지는 않으며 실제 화면 E2E·성능 검증을 포함하지 않습니다. 전체 CI도 성능 측정과 실제 작업관리자 메모리 확인을 자동 대체하지 않습니다. 기존 개발 서버를 종료하거나 데이터를 초기화할 필요는 없습니다.

CI 실행자는 Jenkins 하나이며 `scripts/ci_pipeline.py`의 단계를 호출합니다. `scripts/ci.sh`는 같은 검증의 로컬 재현용입니다. Actions workflow는 제거했으며 중복 자동 실행하지 않습니다. 빠른 게이트 실패 시 Gradle/애플리케이션 단계 전에 종료합니다. 전체 CI는 clean commit SHA/tree와 이미지/실행 컨테이너를 검증합니다. [커밋 고정 CI](CI_REPRODUCIBILITY.md)를 참고합니다. 개발용 fast/full은 미커밋 변경도 검사하며 전체 CI의 입력 정책과 구분합니다.

## 자동 검사 범위

후속 CI는 규칙 단위/설정 검사와 격리 백엔드의 실제 중단·독립 수신·해소 알림, 별도 실제 Keycloak 로그인/권한 E2E를 포함합니다. 기능 성능·메모리 측정은 여전히 별도입니다. [인증 CI](AUTHENTICATION_CI.md), [경보 검증](ALERTING_AND_RECOVERY.md)을 참고합니다. 빠른 게이트는 이 네트워크/실제 장애 검증을 실행하지 않고 도구 회귀만 검사합니다.

| 검사 | 실패 조건 |
|---|---|
| 기준 문서 | AGENTS·README·개발/품질/프로젝트/이력 문서 누락 |
| 텍스트 기본 형식 | UTF-8 아님, CRLF, 마지막 개행 누락, 코드의 후행 공백 |
| 문서 링크 | 인라인 상대 파일 링크가 저장소의 검사 대상 텍스트 파일로 연결되지 않음 |
| 비밀정보 일부 패턴 | 개인키 헤더·일부 GitHub/OpenAI 키 패턴. 값은 출력하지 않음 |
| 프론트 경계 | src의 api.ts 외 fetch, raw HTML/동적 코드 실행 패턴, TS 검사 무시 지시 |
| 서버 경계 | 운영 Java의 JdbcTemplate, Controller의 JPA/SQL/Entity 패키지 import |
| ORM 기본 설정 | 기본 application.yaml의 OSIV false·ddl-auto validate 누락 |
| Flyway | 이름/버전 중복·누락, HEAD 대비 이미 커밋한 마이그레이션 수정·삭제 |
| Gradle | 공식 Wrapper JAR hash·배포 URL/ZIP checksum 불일치, 필수 설정/잠금 파일 누락, POM과 동시 유지 |
| Git 공백 | git diff --check 실패 |

파일은 `git ls-files --cached --others --exclude-standard`로 수집합니다. 따라서 현재 미커밋 신규 소스도 검사하고 `.local`·node_modules·target·산출물 등 Git 제외 파일은 읽지 않습니다. 이 도구 자체와 회귀 테스트도 검사 대상입니다. 무시 파일의 시크릿·바이너리·원격 웹·전체 Git 이력은 검사하지 않습니다.

Gradle의 kts/properties/lockfile/Unix Wrapper도 텍스트 검사에 포함합니다. 공식 Wrapper JAR는 별도 SHA-256 검사로 예외적으로 읽습니다. Windows 생성 스크립트의 공식 CRLF는 유지하며 일반 LF 검사에 포함하지 않습니다. 로컬 Gradle 배포 캐시 전체를 검사하거나 lockfile의 모든 내용까지 이 게이트로 검증하지는 않으며 strict locking 빌드가 의존성 정합성을 확인합니다.

Windows Wrapper는 `.gitattributes`의 해당 경로에만 `-text`와 `cr-at-eol`을 지정해 원본 바이트를 보존합니다. Git 공백 검사에서 CRLF를 후행 공백으로 오인하지 않되 기본 blank-at-eol·blank-at-eof·space-before-tab 검사는 유지합니다. 전역 Git 설정이나 다른 파일의 공백 기준을 변경하지 않습니다.

마이그레이션 불변 검사는 로컬 HEAD 기준입니다. 브랜치에서 이미 커밋된 과거 수정까지 찾아주는 것은 아니므로 리뷰에서 기준 브랜치와 비교해야 합니다. 이 저장소는 기존 Git HEAD가 있어야 도구를 실행할 수 있습니다.

## 제한과 실패 처리

- 텍스트/정규식 검사입니다. Java/TypeScript AST·타입 검사·보안 감사가 아니며, 별칭/우회·댓글/문자열에 의한 미탐/오탐이 가능합니다.
- 링크의 파일 존재만 확인합니다. 외부 URL, heading anchor, 참조식 링크와 이미지 바이너리 검증은 포함하지 않습니다. 코드 블록의 예시 링크는 제외합니다.
- 시크릿 검출은 일부 고확신 패턴뿐입니다. 일반 비밀번호·실사용 이메일·모든 토큰을 자동으로 찾아주지 않으므로 diff 리뷰와 시크릿 저장 규칙이 계속 필요합니다.
- 기본 JPA 설정 검사는 프로필/환경변수의 모든 재정의를 분석하지 않습니다. Controller가 DTO 대신 Entity를 간접 반환하는지도 이 검사만으로 보장하지 않습니다.
- 기준 문서의 존재·파일 링크를 검사할 뿐 이력의 새 항목 추가 여부·설계 내용의 정확성·문서와 코드의 의미적 일치는 자동 판정하지 않습니다. AGENTS의 작업 루프와 리뷰로 확인합니다.
- 숫자 상한, 쿼리 안전성, N+1, SSRF, 취소·타이머 정리, 접근성, 실제 성능은 해당 테스트와 리뷰로 확인합니다.
- 검사 실패 시 규칙 ID와 파일 경로를 보고 수정합니다. 비밀값을 출력해 원인을 찾지 않습니다. 오탐은 최소 재현 테스트를 추가하고 검사/문서를 함께 고칩니다. 실패를 무시하는 옵션은 제공하지 않습니다.

## 이번 도입의 검증과 후속 범위

최신 오프라인 회귀는 기존 39개에 CI Docker 설정 격리/정리·Desktop socket 매핑 7개를 더한 46개입니다. Jenkins 빌드 15의 새 fixture 체크아웃에서 서버 107(실제 PostgreSQL 44)·프론트 77·기본 브라우저 11·실제 Keycloak 5·경보 9시나리오·실제 복구·이미지 추적·정리를 통과했습니다. [최신 실행 근거](verification/2026-10-16-public-image-ci.json)를 참고합니다. 이전 빌드 11과 아래 도입 수치는 당시 이력으로 유지합니다.

- 게이트 회귀 테스트 19개는 기존 17개에 Gradle metadata/바이너리 오류 차단과 POM 동시 유지 차단을 추가합니다. 문서 누락/링크, 비밀값 비출력, 프론트/서버 경계, ORM 설정, Flyway 버전, 텍스트 형식의 정상·실패 사례를 다룹니다. 임시 Git 저장소에서 커밋된 마이그레이션의 수정·스테이징·삭제 차단과 신규 버전 허용·Git 제외 시크릿 미검사도 확인합니다. 테스트의 임시 저장소 외에 실제 프로젝트 Git 이력은 변경하지 않습니다.
- 도입 시 `bash scripts/check.sh full`로 게이트 17개와 텍스트 134개, Maven 87개·Vitest 66개, npm ci·타입/빌드·audit 0건을 확인했습니다. 셸 문법도 통과했습니다. 기존 E2E 11개·Jenkins 빌드 6·성능 통과 기록은 이번에 재실행하지 않았으며 새 로컬 검사와 구분합니다.
- 이후 Gradle 전환에서는 게이트 19개·텍스트 139개와 Jenkins 빌드 7의 Gradle 87개·Vitest 66개·실제 API Chromium 11개·이미지/격리 Compose/Prometheus/정리를 실제 검증했습니다. 이전 도입 결과와 새 전환 결과는 [작업 이력](WORK_HISTORY.md)에서 구분합니다.
- 들여쓰기·import·코드 복잡도까지 강제하는 정식 formatter/linter나 AST 아키텍처 검사는 추가하지 않았습니다. 일괄 재포맷과 새 의존성을 먼저 늘리지 않고 현재 경계부터 검사합니다.

운영 격리·시크릿·CI 과정은 [운영/CI 문서](OPERATIONS_AND_DELIVERY.md), 누적 변경과 실행 결과는 [작업 이력](WORK_HISTORY.md)에 관리합니다.
