# Gradle 빌드 전환

전환일: 2026-10-14 · 대상: 독립 Infrastructure Monitoring Service 백엔드

## 선택 이유와 범위

사용자의 Gradle 전환 요청에 따라 초기 Maven 빌드를 Gradle Kotlin DSL로 변경했습니다. Maven이 과제에 부적합해서가 아니며, 빌드 속도 개선 수치를 주장하지 않습니다. 사용자가 선호하는 도구로 개발·검증·컨테이너·CI 명령을 통일하고 Wrapper와 잠금 파일로 버전 재현성을 보강합니다.

Java 21·Spring Boot 3.5.16·JPA·Flyway·Testcontainers와 서비스 코드, API·스키마·DB 데이터는 유지합니다. `backend/pom.xml`은 제거해 두 빌드 체계가 충돌하지 않게 했습니다. Git의 이전 커밋과 Git 제외 로컬 백업에는 전환 전 구성을 보존합니다. 과거 Maven 테스트 결과는 해당 시점의 기록입니다.

Spring Boot 3.5의 지원 범위 내인 Gradle 8.14.3을 선택했습니다. 전역에 설치된 Gradle을 호출하지 않고 저장소 Wrapper를 사용합니다. [Spring Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)를 참고합니다.

## 구성

| 파일 | 역할 |
|---|---|
| backend/build.gradle.kts | Java toolchain·의존성·JUnit·seed fixture·bootJar·빌드 정책 |
| backend/settings.gradle.kts | 프로젝트명 monitoring-service |
| backend/gradlew / gradlew.bat | Unix/Windows Wrapper 실행 진입점 |
| backend/gradle/wrapper/gradle-wrapper.properties | 공식 배포 URL·8.14.3·ZIP SHA-256 |
| backend/gradle/wrapper/gradle-wrapper.jar | 공식 Wrapper 바이너리, 빠른 게이트에서 SHA-256 검증 |
| backend/gradle.lockfile | compile/test/runtime 의존성 버전 잠금 |

- Java toolchain 21, UTF-8, `-parameters`를 적용해 Controller 파라미터 이름과 컴파일 설정을 유지합니다.
- Spring Boot BOM의 enforced platform으로 관리 버전을 유지하고 strict dependency locking을 사용합니다. 라이브러리 공개용 프로젝트가 아니라 서비스 애플리케이션의 버전 통제를 위한 선택입니다.
- JUnit Platform을 명시하고 Gradle의 테스트 런타임 launcher를 추가했습니다. 테스트용 `scripts/seed.sql`만 classpath root로 복사합니다.
- `bootJar`는 `backend/build/libs/monitoring-service.jar` 한 파일로 생성합니다. 일반 plain JAR는 비활성이고 아카이브 시간/순서를 정규화합니다. 이는 모든 외부 이미지/플러그인까지 완전 재현된다는 뜻은 아닙니다.
- 기존 Maven target 산출물과 새 Gradle build/.gradle은 Git·Docker context에서 제외합니다. Windows 공식 Wrapper의 CRLF는 유지하며 코드의 LF 규칙과 구분합니다. `.gitattributes`는 이 경로만 줄바꿈 변환 없이 보존하고 Git 공백 검사에서 CR-at-EOL을 인식하게 합니다. 실제 후행 공백 검사는 유지합니다.

## 실행과 검사

프로젝트 루트에서 실행합니다. Java 21은 필요하지만 전역 Maven/Gradle 설치는 필요하지 않습니다. Wrapper·의존성이 없는 첫 실행은 공식 저장소에서 다운로드하므로 네트워크가 필요합니다.

```bash
./backend/gradlew -p backend --version
./backend/gradlew -p backend bootRun
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=mock'
./backend/gradlew -p backend --no-daemon --console=plain clean build
./backend/gradlew -p backend --no-daemon --console=plain test
bash scripts/check.sh fast
bash scripts/check.sh full
```

`build`는 테스트와 bootJar를 포함합니다. `bootJar`만 실행하는 Docker/호스트 실행 스크립트는 테스트를 실행하지 않으며 CI의 별도 `clean build`에서 전체 테스트를 수행합니다. Testcontainers에는 Docker가 필요합니다. 프론트는 기존 npm 명령을 유지합니다.

호스트와 CI를 병행할 때 `bash scripts/run-local-backend.sh`를 사용합니다. Gradle bootJar를 고유 이름의 `.local/runtime/` JAR로 복사한 뒤 실행하므로 CI의 `clean build`가 실행 중인 JAR를 덮어쓰지 않습니다. 기존 서버를 종료하거나 바꾸지 않고 빌드 검증할 수 있습니다.

## 잠금 파일과 Wrapper 갱신 규칙

의존성 변경 시 코드/버전 변경의 이유를 먼저 기록하고 잠금 파일을 갱신한 뒤 전체 검증을 수행합니다.

```bash
./backend/gradlew -p backend --no-daemon dependencies --write-locks
./backend/gradlew -p backend --no-daemon clean build
./backend/gradlew -p backend --no-daemon dependencySnapshot
```

`dependencySnapshot`은 test compile/runtime의 resolved 좌표를 `backend/build/reports/dependency-coordinates.txt`에 저장합니다. 전환 감사용 보고서이며 애플리케이션 기능이 아닙니다. BOM 좌표도 포함하므로 바이너리 라이브러리만 비교할 때 BOM을 구분합니다.

Wrapper 버전 변경은 공식 체크섬을 확인하고 `tasks.wrapper`, properties, 품질 게이트의 버전/체크섬 상수를 함께 갱신합니다. Wrapper를 다시 생성하고 다운로드 ZIP과 JAR를 검증합니다. [공식 체크섬](https://gradle.org/release-checksums/)을 사용하며 검사 우회·임의 미러·checksum 제거는 하지 않습니다. Gradle 자체 다운로드 검증은 새 배포 ZIP을 받을 때 수행하며, 로컬 빠른 게이트가 이미 설치된 전체 Gradle 캐시의 무결성을 보장하는 것은 아닙니다.

## Docker·CI·Jenkins

- Docker의 JDK 빌드 단계가 Wrapper로 bootJar를 만들고 JRE 비루트 단계로 결과만 복사합니다. Maven 이미지·POM은 필요하지 않습니다.
- `check.sh full`은 Gradle build 후 기존 프론트 단위/타입/빌드/audit를 수행합니다.
- `ci.sh`는 빠른 게이트 → Gradle clean build → 프론트 검증 → 전체 이미지/격리 Compose → E2E/Prometheus → CI 정리입니다.
- Jenkins JUnit XML 경로는 `backend/build/test-results/test/TEST-*.xml`입니다. HTML은 `backend/build/reports/tests/test/`에 있습니다.
- CI는 Jenkins로 통일했으며 저장소 Wrapper·strict lockfile·품질 게이트의 체크섬 검증과 새 결과 경로 수집을 유지합니다. 기존 Actions workflow는 제거했습니다.

## 검증 기록

| 검증 | 전환 후 결과 |
|---|---|
| Gradle clean build | 기존 87개 테스트, 실패·오류·skip 0; 실제 PostgreSQL 통합 30개 포함 |
| Wrapper | 8.14.3 다운로드 ZIP checksum과 공식 JAR SHA-256 일치 |
| 의존성 비교 | 전환 전 Maven의 test compile/runtime 122개 모듈 버전 모두 유지, Gradle의 JUnit launcher만 추가(총 123개, BOM 제외) |
| 게이트 | 회귀 19개·텍스트 139개 검사·셸 문법·공백 검사 통과 |
| Jenkins | 빌드 7 SUCCESS, 새 Gradle XML 결과 수집 성공, 익명 접근 차단·비밀값 미노출 |
| 전체 파이프라인 | Gradle 87개 → Vitest 66개·타입/빌드/audit 0건 → Docker 이미지·격리 Compose → 실제 API Chromium 11개 → Prometheus up=1 → CI 컨테이너 정리 |
| 호스트 실행 | Gradle bootJar의 고유 런타임 복사본으로 mock 기동, health UP·기존 10만 Pod·Flyway V1~V3 유지 |

첫 의존성 snapshot은 runtime만 비교해 compile-only API Guardian을 누락으로 보았습니다. 보고서를 test compile/runtime 합집합으로 수정해 전환 전 대상과 같은 범위로 대조했습니다. CI clean이 감사 보고서를 삭제한 경우에는 CI 완료 뒤 재생성해 비교했으며 서비스 데이터는 변경하지 않았습니다.

bootJar에 실제 애플리케이션·Flyway V1~V3를 확인하고 JUnit/Testcontainers와 test seed가 서비스 JAR에 포함되지 않는 것을 검사했습니다. 기존 개발 DB·볼륨을 삭제하지 않았습니다. 빌드 6 등 Maven 기록은 유지합니다. GitHub 원격 실행·깨끗한 체크아웃·브라우저 성능 재측정은 이번 결과에 포함하지 않습니다. 상세 이력은 [작업 이력](WORK_HISTORY.md)에 있습니다.
