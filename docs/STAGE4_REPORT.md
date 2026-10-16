# 실제 DB 검증과 Mock·자동 갱신 결과

당시 Maven/POM/target 명령과 결과 경로는 역사 기록입니다. 현재 실행·보고서 경로는 [Gradle 빌드](GRADLE_BUILD.md)를 따릅니다.

기준일: 2026-10-14. 기존 Auknowlog와 독립적으로 작업했습니다. 소스는 `project/infrastructure-monitoring-service`에 있습니다. 아래는 실제 실행 검증 당시 기록이며 이후 단계별 로컬 커밋으로 정리했습니다. 푸시는 하지 않았습니다.

## 이전 중단 원인과 조치

- Docker 소켓·로컬 포트·네트워크 제한은 현재 환경에서 해소됐습니다. 추가 OS 보안 설정을 임의로 변경하지 않았습니다.
- 임시 소스를 영구 프로젝트 경로로 옮기고 빌린 node_modules 링크 대신 별도 의존성을 설치했습니다.
- 자동 갱신 체크박스 추가 후 두 테스트가 첫 input을 검색어로 선택하던 오류는 `.filters input`으로 수정했습니다. 검색 기능 자체가 망가진 오류는 아니었습니다.
- 폴링 테스트는 컴포넌트 생성 전에 가짜 타이머를 적용하도록 수정했습니다.
- npm 10.9.8의 peer 해결 과정에서 `edgesOut` 내부 오류가 발생했습니다. 초기 설치에 `--legacy-peer-deps`를 사용했으며 생성된 잠금 파일로 기본 `npm ci` 재설치·빌드·테스트를 다시 통과했습니다. 전역 npm 버전을 변경하지 않았습니다.
- 최초 Vitest 3 계열의 취약점은 4.1.11 수정 버전으로 갱신했습니다. 현재 npm audit 결과는 0건입니다.

## 환경

Java 21.0.2, Spring Boot 3.5.16, Docker Engine 25.0.3, PostgreSQL 17.11, Node 22.23.2, Vue 3.5.44, TypeScript 5.9.3, vue-tsc 3.3.13, Vite 7.3.7, Vitest 4.1.11, Playwright 1.64.0의 Chromium 환경입니다. npm 패키지 버전은 package-lock.json으로 재현합니다.

## 실제 저장소·서버

- Compose의 새 과제 전용 볼륨에서 PostgreSQL을 실행했습니다. 기존 프로젝트 DB·컨테이너를 삭제하거나 변경하지 않았습니다.
- Flyway V1·V2 성공을 확인하고 seed.sql로 Cluster 20, Node 200, Namespace 2,000, Pod 100,000개를 저장했습니다.
- 백엔드 `http://127.0.0.1:8081/actuator/health`는 UP입니다. 건수 API도 실제 DB 수치와 일치합니다.
- 화면 프로덕션 미리보기는 `http://127.0.0.1:5174/`입니다. Mock 프로필을 켠 백엔드에서 실제 배치 변경이 발생하는 것을 DB의 updated_at으로 확인했습니다.
- 서버는 루프백 전용이며 외부 공개 URL·AI·클라우드 자원을 생성하지 않았습니다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| `mvn -f backend/pom.xml test` | 69개 통과, 실패·오류·skip 0 |
| 실제 PostgreSQL 통합 | 17개 포함: 스키마·재적재 1, 조회 API 12, Mock 변경·롤백 4 |
| 단위·MVC·SQL·Mock 정책 | 52개 포함 |
| `npm ci` | 독립 의존성 재설치 성공 |
| `npm run build` | 타입 검사 및 Vite 프로덕션 번들 성공 |
| `npm test` | 44개 통과: 기존 33 + 폴링 9 + 화면 자동 갱신 1 + 행 재사용 1 |
| `npm audit --audit-level=moderate` | 0건 |
| `npm run test:e2e` | 실제 API Chromium 5개 통과, 대체 API 없음 |

Chromium 테스트 항목:

1. 10만 건 건수·지연 펼침·부모별 목록·유형별 속성·전체 경로.
2. 실제 DB 이름 검색·경로 펼침·상태 필터.
3. 빈 검색 결과·400 요청 오류·404 없는 리소스.
4. 전역 Pod 순차 탐색, 최대 500개 캐시·20개 이하 렌더 행·초기 커서 복귀.
5. 과제 DB의 Pod 1 상태를 직접 변경한 뒤 폴링 반영·선택 상세·1,200px 스크롤 유지.

브라우저 테스트의 마지막 항목은 개발 Mock DB 값을 바꿉니다. 실사용 DB에 실행하지 않습니다. 실제 화면 캡처는 `frontend/.validation/screenshots/monitoring-live.png`에 저장했고 Git에 포함하지 않습니다. 백엔드 보고서는 `backend/target/surefire-reports`, 브라우저 보고서는 `frontend/playwright-report`입니다.

## 구현의 경계와 다음 단계

- 서버 Mock 배치는 기본 비활성이며 mock 프로필에서만 동작합니다. 변경용 HTTP API를 만들지 않았습니다.
- 폴링은 조회 완료 후 5초 간격이며 숨김·오프라인·사용자 해제에서 중지합니다. 현재 창만 조회하고 건수 집계를 반복하지 않습니다.
- 위 표는 Mock·폴링 기능을 구현했을 때의 검증입니다. 이후 콘솔 UI·요청 제한·초기 렌더링을 보강했으며 최신 테스트는 프론트 56개·Chromium E2E 7개입니다. 백엔드는 변경하지 않았습니다.
- Chrome CPU 4x·캐시 OFF의 반복 측정, 합성 프레임, 메모리와 실행 계획은 [후속 성능 보고서](PERFORMANCE_RESULTS.md)에 기록했습니다. Chrome 작업관리자의 탭 메모리는 별도 확인 대상입니다.
- 전체 10만 건 임의 인덱스 점프는 제공하지 않는 순차 커서 UX라는 제한도 최종 제출 시 점검합니다.

설계 이유와 실행 옵션은 [Mock·자동 갱신](LIVE_UPDATES.md), [프로젝트 기준 문서](PROJECT_GUIDE.md), [README](../README.md)에 동기화했습니다.
