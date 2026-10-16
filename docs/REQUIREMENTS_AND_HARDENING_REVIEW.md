# 과제·채용 요구 대조와 안정성 보완

검토일: 2026-10-16 · 대상: 독립 Infrastructure Monitoring Service

## 판단 기준

사용자가 제공한 기술 과제 PDF 4페이지와 오픈소스컨설팅 백엔드 채용 공고를 기준으로 확인했습니다. 과제는 실제 Kubernetes 없이 Mock 계층 데이터를 저장·탐색하는 서비스이며, Backend/Frontend 기술 선택은 자유입니다. 채용 공고의 모든 제품 연동 기능을 이 과제에 복제하라는 요구는 아닙니다.

필수 기능의 누락, 현재 코드에서 재현 가능한 오류, 운영 지연/보안 경계를 우선합니다. 현재 문제와 검증 방법이 없는 OSS 도입은 하지 않습니다. 이 검토는 전체 보안 감사나 운영 환경의 SLA 인증을 뜻하지 않습니다.

## 과제 필수 항목 대조

| 원문 요구 | 현재 구현과 확인할 증거 | 판단 |
|---|---|---|
| 실제 Pod 10만 건 이상, 전체 메모리 순회 지양 | PostgreSQL·seed 20/200/2,000/100,000, Testcontainers 정합성·API 테스트 | 기존 구현 유지, 최종 전체 CI에서 재검증 |
| Cluster → Node → Namespace → Pod 탐색 | children API·지연 트리·서버 페이지·Chromium E2E | 과제의 단순화된 계층 모델 유지 |
| 이름·상태·갱신 시각·상세 | 목록/DTO·상세 속성·공통 시각 표시·API/화면 테스트 | 구현, 임의 데이터를 더 표시하지 않음 |
| 이름 검색과 계층 위치 확인 | 전체 경로를 포함한 검색 DTO·선택 상세·트리 reveal | 구현, 첫 페이지 밖의 항목은 검증한 대상만 pin |
| 상태 등 조건 필터 | 상태·유형·Cluster/Namespace·조건 바인딩 커서 | 구현, 브라우저 필터가 서버 권한을 대체하지 않음 |
| 상태 변화 반영과 방식의 이유 | 제한 Mock 트랜잭션·수동/5초 폴링·선택/스크롤 유지·백오프 | 저빈도 조회에 스트리밍 OSS 추가 불필요 |
| DOM에 10만 건을 한 번에 그리지 않기 | 응답 최대 100·목록 캐시 500·가상 리스트·DOM 상한 테스트/E2E | 구현, 임의 위치 점프는 필수가 아닌 자유 UX 범위 |
| README와 4개 설계 항목 | README → 렌더링·데이터 모델·인덱스/쿼리·API 문서 | 링크/내용을 빠른 게이트로 확인 |
| 성능 방법과 결과 첨부 | CPU4x·cache OFF의 기존 반복 표본·실행 계획·trace 요약 | 아래 미검증 경계를 포함해 기록, 이번 변경을 성능 향상 수치로 주장하지 않음 |

관련 코드/테스트는 [API 계약](API.md), [렌더링](FRONTEND_RENDERING.md), [설계](IMPLEMENTATION_PLAN.md), [JPA 검증](JPA_QUERY_DESIGN.md), [기준 문서](PROJECT_GUIDE.md)에 연결돼 있습니다. 보완은 WH-025, 이전 재실행은 WH-026과 [빌드 13 근거](verification/2026-10-16-ci-retry.json), 공개 CI 설정 격리 후 최종 전체 검증은 WH-027과 [빌드 15 SUCCESS 근거](verification/2026-10-16-public-image-ci.json)에 구분합니다.

## 이번에 실제 보완한 경계

### 1. 유효한 서명과 올바른 토큰 구조를 별도로 확인

- 발견: 기존 실제 RSA/JWKS 통합 테스트에 만료 시각 `exp`가 없는 토큰과 객체 형태의 `groups`를 추가하자, 401 예상에 HTTP 200을 반환했습니다. 표준 JWT 라이브러리의 claim 변환에만 맡기면 잘못된 권한 데이터도 변환돼 조회로 이어질 수 있었습니다.
- 적용: 기존 issuer·audience·서명·시간·sub 검증에 더해 `exp`의 존재를 요구합니다. `groups`와 API client `roles`는 문자열 배열/최대 1,000개인지 검사하고, `resource_access`와 해당 client 구조도 확인합니다. 잘못된 구조는 서버 조회 전에 401입니다. 정상 토큰의 역할 없음·다른 client 역할은 기존 403, 미배정 VIEWER는 조회 0건 정책을 유지합니다.
- 범위: 공격자가 서명 키 없이 위조 JWT를 만들어 접근할 수 있었다는 뜻은 아닙니다. **신뢰하는 발급자가 서명했지만 설정/claim 형태가 잘못된 입력**과 무기한 토큰을 방어합니다. Keycloak의 정상 120초 토큰 계약은 바꾸지 않습니다.
- 검증: 실제 서명/JWKS·PostgreSQL에서 정상 ADMIN/A/B와 기존 교차 접근 차단을 유지하고, exp 누락·groups/roles 구조 오류·aud 누락·미래 nbf를 거부합니다. 실제 Keycloak 브라우저 로그인도 전체 CI에서 재검증합니다. 비밀번호/JWT/인증 화면 trace를 출력/보관하지 않습니다.

[Spring Security 공식 JWT 검증/claim 변환 설명](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)을 기준으로 이 서비스의 더 엄격한 access-token 정책을 추가했습니다. 인증 구조를 새로 만들거나 세션/Redis를 추가하지 않습니다.

### 2. DB 연결을 기다리는 시간과 SQL 실행 시간을 구분

- 문제: 기존 SQL별 3초 제한은 커넥션 풀에서 연결을 얻기 전 대기에는 적용되지 않습니다. HikariCP 기본 연결 대기는 30초이므로 풀 고갈/일부 장애에서 브라우저의 10초 제한 뒤에도 서버 요청이 기다릴 수 있었습니다. 트랜잭션 시작 실패는 기존 `DataAccessException` 처리와 다른 계층에서 발생합니다.
- 적용: 연결 획득 대기 `connection-timeout=3000ms`, 연결 검사 `validation-timeout=1000ms`를 명시했습니다. 연결을 얻지 못해 트랜잭션을 시작할 수 없는 `CannotCreateTransactionException`도 기존 안전한 `503 STORAGE_UNAVAILABLE`로 처리합니다. DB 주소·SQL·풀 진단 메시지를 응답하지 않습니다.
- 검증: 실제 PostgreSQL과 Hikari 풀 2개의 연결을 모두 점유하고 목록·건수 API의 503을 확인합니다. 테스트 장애 주입 시 대기는 350ms로 줄이며, 실제 기본 설정 3초/1초는 별도 테스트에서 검사합니다. 연결 반환 뒤 Pod 10만 건 집계와 목록 조회가 복구되는지 확인합니다. DB 중단이나 사용자 데이터 변경 없이 재현합니다.
- 제한: 연결 대기 3초 + SQL별 3초는 **전체 요청 3초 보장**이 아닙니다. 여러 쿼리, 새 물리 연결/네트워크 장애의 드라이버 동작, 직렬화 시간이 별도로 존재합니다. 이 변경은 풀을 과도하게 늘리거나 자동 재시도로 DB 부하를 증폭시키지 않습니다.

[HikariCP 공식 connectionTimeout/validationTimeout 설명](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)을 참고했습니다. Hikari는 기존 Spring Boot/JPA 의존성이며 새 OSS가 아닙니다.

## 채용 공고의 기술과 추가하지 않은 범위

| 항목 | 선택과 이유 |
|---|---|
| Java 17+·Spring Boot 3·ORM·RDBMS | 기존 Java 21·Boot 3.5·JPA DTO·인덱스/SQL 횟수/실제 DB 검증으로 충족하는 구현 방향 유지 |
| Keycloak/OIDC·RBAC | 실제 로그인·ADMIN/VIEWER·Cluster 서버 조건을 유지하고 토큰 형식 검증을 보강 |
| 외부 시스템 연동 | 실제 Keycloak/JWKS·Prometheus API·독립 알림 수신의 실패 경계를 검증. 계약 없는 고객 인사/SMS 시스템을 가상 구현하지 않음 |
| CI·Docker·테스트 | 사용자 선택대로 Jenkins 하나, 커밋/image ID/실제 컨테이너 일치·격리 로그인/복구까지 재검증 |
| Kubernetes API·Helm·GPU·OpenStack·Ceph | 이번 과제에 실제 클러스터가 없고 제어 기능 요구도 없어 추가하지 않음. UI의 Mock 리소스를 실제 운영 경험으로 설명하지 않음 |
| Redis·Kafka·Elasticsearch·MSA·Grafana | 현재 페이지 조회/운영 관측의 결함을 해결할 필수 근거가 없음. 서비스/비용/설정 복잡도만 늘리지 않음 |

## 검증·제출의 남은 경계

공개 이미지용 CI 설정을 개인 키체인과 분리해 Docker 전체 재시작 없이 빌드 15 전체 검증을 완료했습니다. 이전 대기와 남은 네이티브 성능 측정은 [확인 항목](REMAINING_PERMISSIONS.md)에 구분합니다. CI 성공을 개인 도우미 자체 수리·새 성능 수치·수동 측정 완료로 확대하지 않습니다.

- 초기 진입 60회·인증 SSO 재진입 40회·탐색/검색/필터/갱신·합성 프레임 결과는 [기존 성능 보고서](PERFORMANCE_RESULTS.md), [초기 개선](STARTUP_PERFORMANCE.md), [인증 성능](AUTHENTICATION_PERFORMANCE.md)의 당시 환경과 범위에 한정합니다. 이번에는 화면/조회 알고리즘을 바꾸지 않았습니다.
- 원문의 **Chrome Performance FPS 미터·Chrome 작업관리자의 탭 메모리**와 CDP 합성 프레임/JS heap/RSS는 다릅니다. 기존 자동 측정으로 전자를 완료했다고 하지 않습니다. 처음에는 권한 대기에 막혔고 사용자 승인 후 네이티브 조회/캡처는 성공했지만 수동 성능 측정은 아직 하지 않았습니다. 메모리 300MB는 참고 목표이며 초과 여부를 추정하지 않습니다. 잠금이나 접근 권한을 우회하지 않습니다.
- 원문은 세부 UX 자유를 명시하고 임의 10만 번째 위치 점프를 요구하지 않습니다. 순차 keyset 탐색·최근 500개 캐시라는 제한을 유지하며 불필요한 offset 전체 count/좌표 점프를 추가하지 않습니다.
- 전체 CI는 원본 미커밋 상태를 보존한 별도 Git fixture의 고정 커밋으로 검증합니다. 실제 제출 소스의 원격 commit/push·Jenkins webhook·공개 HTTPS 배포는 이번 사용자 요청에서 임의 실행하지 않습니다.
- 자동 실행 근거와 수동 검증의 미확인은 구분합니다. 검증하지 않은 성능/보안 범위를 통과로 바꾸지 않으며, 위 두 결함 외 새 기능 개발은 추가하지 않고 마무리합니다.
