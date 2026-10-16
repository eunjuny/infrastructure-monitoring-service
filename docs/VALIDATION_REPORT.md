# 1단계 구동 검증 보고서

당시 Maven/POM 명령은 역사 기록입니다. 현재 실행·보고서 경로는 [Gradle 빌드](GRADLE_BUILD.md)를 따릅니다.

아래는 초기 환경 제한 기록입니다. 2026-10-14에는 실제 DB·정식 빌드·브라우저 검증을 완료했습니다. [현재 결과](STAGE4_REPORT.md)를 참고합니다.

검증일: 2026-10-13. 실행 결과와 환경 제한으로 실행하지 못한 항목을 구분합니다.

## 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| JSON / Maven XML / Compose 설정 | 통과 | 파싱 및 `docker compose config --quiet` |
| 변경 파일 공백 검사 | 통과 | `git diff --cached --check` |
| Vue 화면 프로덕션 번들 | 통과, 캐시 도구 이용 | Vue 3.5.20, Vite 7.3.6, plugin-vue 6.0.1 |
| 정식 npm 설치·잠금 파일 생성 | 미완료 | npm 저장소 DNS 접근 제한 및 오프라인 캐시 부족 |
| TypeScript 타입 검사 | 미실행 | vue-tsc / TypeScript 설치 불가 |
| Maven 빌드·테스트 | 미완료 | Spring Boot 3.5.16 캐시 없음, 저장소 DNS 접근 제한 |
| Docker PostgreSQL 17 | 미기동 | Docker 소켓 접근 제한 |
| 설치된 PostgreSQL 16.11 임시 DB | 초기화 실패 | 공유 메모리 생성 권한 제한 |
| Flyway 실제 실행 | 미검증 | 백엔드 및 DB 구동 불가 |
| 실제 10만 Pod 적재·재적재·관계 검사 | 미검증 | DB 구동 불가 |
| 실제 API·브라우저 연동 | 미검증 | 백엔드 및 DB 구동 불가 |
| Testcontainers 통합 테스트 | 미실행 | Maven 및 Docker 접근 제한 |

## 프론트엔드 번들

로컬에 이미 설치된 공개 Vue/Vite 도구만 임시 프로젝트에서 참조했습니다. 기존 프로젝트의 소스·설정·데이터는 사용하거나 변경하지 않았습니다. 캐시 도구 버전은 package.json의 버전 범위 안에 있지만 정식 설치·잠금 파일 기반 빌드를 대체하지 않습니다.

- HTML: 0.42 kB
- CSS: 1.10 kB / gzip 0.58 kB
- JavaScript: 61.01 kB / gzip 24.74 kB
- Vite 출력 빌드 시간: 350ms

350ms는 **빌드 도구 실행 시간**이며 최초 화면 진입 500ms, 사용자 입력 반응, 서버 응답 성능을 의미하지 않습니다. 전체 `npm run build`에 포함된 vue-tsc 타입 검사도 아직 통과한 것이 아닙니다.

## 통합 테스트 보강

`SchemaIntegrationTest`를 다음 검증으로 확장했습니다. 아직 실행되지 않았으므로 통과로 기록하지 않습니다.

1. Testcontainers PostgreSQL 17에서 Flyway로 4개 테이블 생성.
2. 실제 `scripts/seed.sql`로 20 / 200 / 2,000 / 100,000개 적재.
3. Cluster당 Node 10개, Node당 Namespace 10개, Namespace당 Pod 50개 확인.
4. Pod에서 Cluster까지 누락된 부모가 없는지 확인.
5. 재적재 후 건수와 Pod 데이터 집계·갱신 시각 유지 확인.
6. MockMvc로 실제 Spring Controller / DB 조회 결과 JSON 확인.

MockMvc는 실제 네트워크 서버·브라우저 검증과 구분합니다.

## 재개 조건

네트워크와 Docker 접근이 가능한 독립 작업 환경에서 의존성 설치, 타입 검사·정식 빌드, 통합 테스트, 실제 서버·10만 건 적재·재적재·화면 확인 순으로 재개합니다. 제한을 피하려 버전을 낮추거나 운영 DB에 테스트 데이터를 넣지 않습니다.

결과를 [프로젝트 기준 문서](PROJECT_GUIDE.md)에 반영한 뒤 2단계로 진행합니다.
