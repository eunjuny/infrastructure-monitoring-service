# 초기 진입 성능 개선

## 문제와 동일한 측정 기준

JPA 전환 직후 측정에서는 CPU 4x·HTTP 캐시 OFF·1440×1000·동작별 20회 중 첫 진입이 652.6ms로 500ms 목표를 초과했습니다. P95는 304.8ms였지만 모든 표본이 통과한 것은 아니었습니다. 바로 전 JDBC 버전에도 첫 1회 초과가 있었으므로 이를 JPA만의 문제로 판단하지 않았습니다.

측정 기준을 바꾸지 않았습니다. `measure-performance.mjs`는 초기 Cluster 목록·실제 10만 Pod 건수·첫 Pod 목록이 표시된 뒤 두 번의 requestAnimationFrame까지 기다립니다. 서버·DB는 기동된 상태이며 CPU 4배 제한은 브라우저에 적용합니다. 초기 데이터 생성·서버 기동·모든 첫 쿼리 컴파일을 측정한 결과는 아닙니다. 로딩 skeleton만 보여주거나 건수를 고정 값으로 대체하지 않습니다.

## 진단과 변경

`profile-startup.mjs`는 새 Chromium·같은 CPU/cache 설정에서 CDP CPU profile과 long task, 포매터 호출을 기록합니다. 진단 도구에는 추가 계측 부하가 있으므로 그 로딩 시간을 성능 통과 판정에 사용하지 않습니다. 날짜 포매터 첫 생성 약 66.2ms, 날짜 초기화를 제거한 중간 버전의 첫 숫자 지역화 약 46.6ms를 관측했습니다. 이 비용만으로 전체 지연 원인을 단정하지 않습니다.

| 변경 | 적용과 이유 |
|---|---|
| 날짜 표기 | 초기 Intl.DateTimeFormat 생성 대신 로컬 날짜 getter로 HH:mm:ss·YYYY-MM-DD HH:mm:ss 표시. API의 UTC ISO 문자열·datetime 속성은 유지 |
| 건수 표기 | 정수 건수에 직접 쉼표 구분 적용. 초기 Number.toLocaleString/ICU 초기화를 피하며 잘못된 값·안전 정수 범위 초과는 `—` 표시 |
| 선택 화면 코드 분리 | 상세 패널·운영 관측·변경 이력은 defineAsyncComponent + import로 해당 화면을 열 때만 로드 |
| 닫힌 범위 UI | ScopeFilters 옵션 영역은 닫혀 있을 때 DOM을 만들지 않으며 열면 기존 서버 조회·소속 검증 사용 |
| 초기 I/O 중첩 | 건수·Pod 페이지·Cluster 루트의 기존 요청 3개를 각 컴포넌트 setup에서 시작. 전체 마운트 완료를 기다리지 않고 DOM 생성과 네트워크를 겹침 |
| 기본 폰트 스택 | Inter/시스템 UI 폰트 탐색 대신 Arial·Apple SD Gothic Neo·Malgun Gothic 등 로컬 기본 폰트 사용. 한글·색상·레이아웃·아이콘을 유지하고 외부 폰트는 다운로드하지 않음 |

초기 main JS는 110.66KB에서 103.78KB로, gzip은 약 41.36KB에서 39.82KB로 줄었습니다. 선택 화면의 비용은 없어진 것이 아니라 첫 사용 시로 이동한 것입니다. 기능 제거·응답량 증가·DB 캐시나 모델 변경·새 UI OSS는 없습니다. 날짜의 표시 형식만 숫자형 24시간으로 바뀌며 시간대는 여전히 사용자 브라우저의 로컬 시간입니다.

## 중간 시도도 보관

- `startup-fixed`: 날짜 초기화 제거·운영/이력 코드 분리·닫힌 범위 DOM 억제로 20회 최대 476.6ms, 초과 0.
- `startup-fixed-repeat`: 같은 중간 코드로 새 브라우저를 재실행하자 최대 619.3ms, 초과 1. 첫 성공만으로 완료 판정하지 않았습니다.
- `startup-final`: 정수 지역화 제거·상세 패널 코드 분리 후 20회 최대 488.5ms, 초과 0.
- `startup-overlap`: 초기 I/O 중첩을 더했지만 최대 504.2ms, 초과 1. 이를 반올림해 통과 처리하지 않았습니다.
- 세부 트레이스에서 Layout 약 80~88ms·UpdateLayoutTree 약 47~49ms를 관측해 폰트 스택도 조정했습니다. 이 구간 전체를 폰트 비용이라고 단정하지는 않습니다.

## 최종 코드 초기 진입 결과

같은 createPage·준비 조건·CPU/cache 설정을 사용하는 `PERF_INITIAL_ONLY=1` 모드로 초기 진입만 반복합니다. 기존 전체 측정에서 초기 구간을 그대로 실행하며 500ms 초과나 측정 오류가 있으면 종료 코드 1을 반환합니다. 전체 기능 측정과 혼동하지 않고 독립 브라우저 두 묶음의 결과를 따로 기록합니다.

| 묶음 | 횟수 | 중앙값(ms) | P95(ms) | 최대(ms) | 500ms 초과 |
|---|---:|---:|---:|---:|---:|
| startup-font-check | 20 | 204.35 | 221.8 | 468.8 | 0 |
| startup-font-repeat | 20 | 204.70 | 213.5 | 458.0 | 0 |
| startup-release (전체 동작) | 20 | 209.10 | 220.6 | 446.1 | 0 |

초기 전용 40회에 전체 동작 측정 20회를 더해 **최종 코드 총 60회 모두 500ms 이내, 오류 0**입니다. 각 묶음의 첫 새 브라우저 진입도 포함하며 워밍업 표본을 버리지 않았습니다. 초기 준비 조건을 바꾸거나 작은 mock 응답을 사용하는 방식으로 통과시킨 것이 아닙니다. [휴대 가능한 요약 JSON](performance/2026-10-14-startup-summary.json)에 변경 전·중간 실패·최종 결과를 구분했습니다.

전체 동작 20회에서도 미캐시 펼침 최대 62.5ms·검색 69.8ms·필터 51.9ms·현재 페이지 갱신 127.0ms·5페이지 갱신 80.9ms로 각 목표 초과가 없었습니다. 합성 compositor는 약 59.84FPS, 추가 100페이지 후 캐시 500개·표시 행 14개를 유지했습니다. 실제 탭 메모리 합격 판정은 하지 않습니다.

이전 실패 표본은 Git 제외 `frontend/.validation/performance/`의 각 label JSON·trace에 남깁니다. 통과할 때까지 실행한 결과를 모두 합쳐 이전 실패가 없었다고 주장하지 않습니다. 최종 코드의 반복 검증과 중간 코드의 시행착오를 구분합니다.

## 기능 회귀 확인

- 날짜·자정·잘못된 값, 정수 건수·안전 범위, 초기 지역화 미사용 단위 테스트.
- 초기 API 요청은 기존 3개이며 선택 메뉴·닫힌 옵션의 추가 요청이 없음을 확인.
- 실제 API E2E에서 초기 선택 청크 미요청, 선택 후 상세·운영·이력 청크와 화면 로딩 확인.
- 상세 탭·닫기·오래된 응답 차단·검색·범위·경로·자동 갱신·모바일 기능은 유지.

최종 Jenkins 빌드 6은 SUCCESS입니다. 백엔드 87개·프론트 단위 66개·실제 API Chromium 11개, 타입 검사·이미지 빌드·격리 Compose·Prometheus up 확인·CI 정리를 완료했습니다. 초기 선택 청크 미로딩·선택 후 실제 화면 로딩·변경된 날짜 표기도 E2E에 포함합니다. 익명 접근 차단·비밀값 미노출·npm audit 0건을 확인했습니다. API·백엔드·DB 스키마는 이번 성능 수정에서 변경하지 않았지만 파이프라인에서 전체 회귀 검증을 실행했습니다.

## 재현과 한계

프로덕션 preview 5174·API 8081·실제 과제 DB를 실행한 뒤:

```bash
cd frontend
PERF_LABEL=startup-check npm run test:performance
PERF_LABEL=startup-check-repeat npm run test:performance
PERF_INITIAL_ONLY=1 PERF_LABEL=initial-check npm run test:performance
PERF_LABEL=diagnostic-current npm run test:performance:profile
```

성능 스크립트는 과제 DB의 Pod 1을 변경하므로 실사용 DB에서는 실행하지 않습니다. 프로파일 도구는 조회만 수행하고 CPU profile·요약을 `.validation/performance/`에 저장합니다. 계측 코드는 제품 번들에 넣지 않습니다. 날짜·건수 단위 테스트와 실제 E2E는 성능 측정 도구와 별개입니다.

실제 Chrome 작업관리자 탭 메모리는 Mac 잠금으로 미확인입니다. 합성 FPS·JS heap·renderer RSS로 해당 목표를 대체하지 않습니다. 같은 기기·조건의 반복 표본이 통과하더라도 다른 기기나 높은 부하에서 500ms를 보장하는 SLA는 아닙니다.

관련 설계: [JPA 조회](JPA_QUERY_DESIGN.md), [렌더링·캐시](FRONTEND_RENDERING.md), [측정 프로토콜](PERFORMANCE.md).
선택 화면 코드 분리는 [Vue 공식 성능 가이드](https://vuejs.org/guide/best-practices/performance.html#code-splitting)의 패턴을 사용하며 프로파일링은 [Chrome DevTools](https://developer.chrome.com/docs/devtools/performance/reference)의 CPU 분석 방식과 구분해 기록합니다.
