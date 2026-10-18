# 품질 게이트

## 실행

```bash
bash scripts/check.sh fast  # 오프라인 도구 회귀·소스/문서·공백 검사
bash scripts/check.sh full  # 위 검사 + Gradle build·프론트 단위/타입/빌드/audit
bash scripts/ci.sh          # 깨끗한 체크아웃의 이미지·격리 실행·브라우저·인증 검증
```

Testcontainers에는 Docker가 필요합니다. 의존성 다운로드·취약점 조회에는 인터넷 연결이 필요할 수 있습니다. 실제 브라우저 E2E·성능은 fast/full에 포함하지 않습니다. 전체 CI는 [커밋 고정 절차](CI_REPRODUCIBILITY.md)를 사용합니다.

## 검사 경계

| 검사 | 조건 |
|---|---|
| 필수 공개 문서 | AGENTS·README·PROJECT_GUIDE·DEVELOPMENT_GUIDE·QUALITY_GATES |
| 텍스트 | UTF-8·LF·마지막 개행·코드 후행 공백 |
| 링크 | 공개 파일/허용 이미지로 연결되는 인라인 상대 링크. 내부 로컬 메모로의 링크는 실패 |
| 이미지 | docs/images의 JPEG/PNG·5MiB 이하·형식/확장자 일치·심볼릭 링크 거부 |
| 시크릿 일부 패턴 | 개인키·일부 GitHub/OpenAI 키. 일치 값은 출력하지 않음 |
| 프론트 | api.ts 밖 fetch·raw HTML/eval·TypeScript 검사 무시 차단 |
| 서버 | 운영 JDBC 직접 사용·Controller의 JPA/SQL/Entity import 차단 |
| ORM | OSIV false·ddl-auto validate 유지 |
| Flyway | 연속 버전·이름 검사, 커밋된 마이그레이션 수정/삭제 거부 |
| Gradle | 공식 Wrapper JAR/ZIP 체크섬·잠금 파일·필수 설정, POM 동시 유지 차단 |
| Git | git diff --check |

파일은 `git ls-files --cached --others --exclude-standard`로 수집합니다. 새 공개 파일도 검사하지만 Git 제외 비밀 설정·내부 기록·빌드 산출물은 읽지 않습니다. 공개 docs의 허용 목록은 .gitignore에 관리합니다. 내부 작업일지는 공개 빌드의 입력이나 필수 문서가 아니며, 새 체크아웃에서도 개인 기록 없이 검증할 수 있습니다.

게이트는 컴파일러·AST 분석기·보안 감사가 아닙니다. 링크 anchor·외부 URL·이미지 속 문자열·전체 Git 이력은 검사하지 않습니다. 문서 분류 변경으로 소스·시크릿·마이그레이션 검사나 기능 테스트 기준을 완화하지 않습니다.

## 기능별 검증

| 경계 | 검증 |
|---|---|
| 서버 정책·입력·권한 | JUnit/Mockito/MVC |
| PostgreSQL·JPA·Flyway | Testcontainers·실제 SQL 수·정합성·인덱스·timeout·롤백 |
| 화면 상태·캐시·폴링 | Vitest·Vue Test Utils·jsdom |
| 실제 화면/API | Playwright·Chromium |
| 로그인과 조회 범위 | 별도 Keycloak·실제 PKCE·JWT/JWKS·PostgreSQL |
| 관측·알림 | 규칙 테스트·격리 장애/복구·발생/해소 알림·SMTP sink |
| 화면 성능 | 고정 조건 Chrome Performance 및 반복 검증. CI 기능 성공과 별도 판정 |

최신 실행 결과는 [프로젝트 기준 문서](PROJECT_GUIDE.md), 성능 조건·측정 경계는 [성능 프로토콜](PERFORMANCE.md)에 관리합니다. 측정하지 않은 항목을 다른 도구의 숫자로 대체하지 않습니다.

Jenkins는 전체 SHA를 독립 체크아웃하고 검증·이미지·실행 컨테이너를 연결합니다. 미커밋 파일은 CI 입력으로 사용하지 않습니다. 단계 실패를 성공으로 변환하지 않고 성공/실패 모두 CI 프로젝트만 정리합니다. 사용자 DB·서비스는 정리 대상이 아닙니다. [개발 규칙](DEVELOPMENT_GUIDE.md), [인증 CI](AUTHENTICATION_CI.md), [경보와 복구](ALERTING_AND_RECOVERY.md)를 함께 적용합니다.
