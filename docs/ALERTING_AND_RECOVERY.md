# 운영 경보·독립 알림·복구 검증

인증 환경은 [관리자 경보 메일](ALERT_EMAIL.md)을 추가로 지원합니다. SMTP가 설정되고 ADMIN이 활성화한 경우에만 Alertmanager가 이메일을 직접 발송합니다. 초기 격리 검증은 외부 메일을 사용하지 않았고, 이후 로컬 SMTP로 합성 firing/resolved 전달을 확인했습니다. 격리 CI 복구 검증 자체는 계속 SMTP를 사용하지 않습니다.

## 목적과 구성

지표를 사람이 읽는 화면에서 장애 조건의 판단·알림·복구 확인까지 확장합니다. 트래픽이 많아야만 의미 있는 기능은 아닙니다. 서비스의 수집 경로와 주기적인 Mock 배치가 정상 동작하는지를 확인할 수 있습니다.

```text
Actuator/Micrometer → Prometheus 시계열 → 경보 규칙 평가
                                      ├→ /api/operations/alerts → 관리자 경보 화면
                                      └→ Alertmanager → 독립 local inbox
                                                          └→ 선택 시 SMTP 이메일
복구 → Prometheus 조건 해소 → Alertmanager resolved 알림 → inbox/SMTP
```

Prometheus는 조건 판단, Alertmanager 0.34.0은 그룹화·알림 반복 간격·해소 알림·종속 경보 억제를 맡습니다. HTTP webhook 수신기는 Python 표준 라이브러리만 사용하고 별도 컨테이너에서 실행합니다. SMTP를 활성화하면 메일 공급자 요금·한도가 적용될 수 있으며 로컬 CPU·메모리·디스크도 사용합니다.

초기에는 **관측·알림·수동 복구 검증**까지 제공합니다. 원인 확인 없이 모든 장애를 자동 재시작시키거나 운영 화면에 Docker 제어권을 주지 않습니다. 금융권/공개 운영용 HA 알림 시스템을 완성한 것은 아닙니다.

## 세 경보와 오탐 방지

공통 지속 조건은 1분이며 평가 주기는 15초입니다. 조건 시작은 pending, 지속 충족은 firing, 조건 미충족은 inactive입니다. 수집·평가·그룹 대기 때문에 장애 발생 후 정확히 60초에 전달된다는 보장은 없습니다.

| 경보 | 조건 | 대응 |
|---|---|---|
| BackendScrapeUnavailable | 해당 job의 up=0 또는 up 시계열 없음이 1분 지속 | 백엔드·관리 포트·수집 경로 확인 |
| ResourceApiErrorBurst | 최근 5분 리소스 API 요청 20건 이상, 5xx 비율 >10%가 1분 지속 | 최근 배포·서버 오류·DB 연결 확인 |
| MockBatchFailures | 최근 5분 배치 실패 증가량 ≥3이 1분 지속 | DB·트랜잭션 실패 원인과 복구 후 배치 성공 확인 |

`increase`는 표본 간격을 외삽하므로 정수 Counter의 관측 증가량도 소수일 수 있습니다. 오류율은 낮은 요청 수에서 과장될 수 있어 최소 요청 수를 함께 봅니다. 401/403은 리소스 API의 5xx 분자에 포함하지 않습니다. 표본이 없는 P95를 근거로 지연 경보를 추가하지 않았습니다. 수집 실패는 **DB 장애 확정**이 아닙니다.

Alertmanager는 job·alertname 기준으로 묶고 group_wait 5초, group_interval 15초, repeat_interval 4시간, send_resolved=true를 사용합니다. 백엔드 수집 실패가 firing이면 같은 job의 API/배치 알림 전달을 억제합니다. Prometheus의 원래 규칙 상태가 지워지는 것은 아니므로 UI firing과 실제 알림 전달 여부는 구분합니다.

## 화면·API와 안전 경계

`GET /api/operations/alerts`는 Prometheus의 고정 `/api/v1/rules?type=alert`만 조회합니다. 인증 모드에서는 기존 `/api/operations/**` ADMIN 정책을 적용하고 VIEWER 403·익명 401을 유지합니다. 기본 비인증 루프백 데모에서는 기존 운영 화면과 같은 접근 경계입니다.

- 세 가지 알려진 규칙의 실제 상태·조건 시작 시각·관측값과 서버의 고정 제목/대응 방법만 반환합니다. 외부 annotation의 링크/문장을 그대로 실행·노출하지 않습니다.
- 규칙 누락·중복·평가 실패·알 수 없는 상태·수신 오류는 `available=false`이며 "경보 상태 확인 불가"로 표시합니다. 규칙이 로드되지 않은 것을 "경보 없음"으로 바꾸지 않습니다.
- 지표/경보 공통 내부 HTTP client는 HTTP localhost/127.0.0.1/prometheus만 허용하고 사용자 정보·경로·query 설정과 리다이렉트를 차단합니다. 본문까지 3초·1MiB 제한, 규칙 그룹 32개/그룹별 100개/규칙별 alert 100개 상한을 적용합니다.
- UI는 별도 선택 화면 청크에 포함하고 숨김·화면 이탈 시 폴링/요청을 정리합니다. 새 기능으로 초기 리소스 조회에 경보 요청을 추가하지 않습니다. 조회 실패 시 이전 상태를 현재 정상 상태로 남기지 않습니다.
- Prometheus/Alertmanager의 직접 포트는 루프백 운영자 도구입니다. 브라우저용 ADMIN API 정책이 그 직접 포트의 별도 사용자 인증까지 제공하지는 않습니다. 공개 노출하지 않습니다.

## 독립 수신기와 한계

백엔드가 죽으면 앱 화면/API도 동작하지 않을 수 있으므로 알림은 백엔드와 독립된 inbox 컨테이너로 전달합니다. inbox는 호스트 포트를 공개하지 않으며 같은 Compose 내부에서만 사용합니다. 내부 webhook에는 별도 인증이 없으므로 신뢰된 로컬 네트워크용이며 외부 수신 endpoint로 공개하면 안 됩니다.

수신기는 본문 64KiB·최대 20개 alert·소켓 대기 3초, 메모리의 최근 200개 이벤트만 유지합니다. 이름/job/status를 허용 목록으로 검사하고 이벤트에는 이름·firing/resolved·수신 시각만 남깁니다. 원문 labels/annotations/URL/토큰을 저장·출력하지 않습니다. 비루트·읽기 전용 파일시스템·64MiB·capability 제거를 적용합니다. 재시작 시 inbox 기록은 사라지며 영구 장애 이력/감사 DB가 아닙니다.

Alertmanager는 별도 볼륨과 24시간 알림 로그/해소 상태 retention을 사용합니다. 이것도 장애 이력을 장기 보관하는 DB가 아닙니다. 현재 로컬 SMTP를 연결해 합성 firing/resolved 알림이 공급자에 수락되는 것까지 확인했습니다. SMTP 성공은 최종 편지함 도착/읽음 보장을 뜻하지 않습니다. Mac/Docker/Prometheus/Alertmanager 전체가 함께 중단되는 장애는 단일 호스트 구성으로 외부에서 감지하지 못합니다.

## 실행과 검증

2026-10-15 Jenkins 빌드 10에서 9개 시계열 시나리오·설정 검사·실제 장애/복구 검증을 통과했습니다. 검증 시작 후 63.24초 firing, 68.63초 독립 수신, 84.26초 resolved 수신과 10만 Pod 보존을 확인했습니다. 준비 확인·수집/평가/전달 간격을 포함하는 한 번의 실행 기록이며 SLA는 아닙니다. 서버 102개·프론트 77개·기본 브라우저 11개·실제 Keycloak 5개도 통과했습니다. [실행 요약](verification/2026-10-15-auth-ci-alerting.json)을 참고합니다.

```bash
# 규칙 정적 검사·시계열 단위 테스트·Alertmanager 설정 검사
bash scripts/test-alert-rules.sh

# 현재 로컬 인증 서비스에 적용(기존 계정과 DB 보존)
python3 scripts/auth-local.py

# 전체 격리 CI: 규칙·실제 화면·장애/해소 알림·실제 인증까지 검증
bash scripts/ci.sh
```

전체 runtime은 기존 `docker compose -f compose.runtime.yaml --profile monitoring up -d --build`로 기동합니다. 호스트 개발에서 수신기도 준비하려면 `docker compose --profile monitoring up -d --build db prometheus alertmanager alert-receiver`를 사용합니다. 운영자용 Alertmanager 주소는 runtime 9192·호스트 개발 9194·인증 9195이며 모두 127.0.0.1입니다.

`promtool`의 9개 시나리오는 정상/무요청·일시 중단·지속 중단/복구·대상 시계열 없음·소량 오류·지속 5xx/복구·401/403·배치 반복 실패/해소·임계값 미만을 검증합니다. 이 합성 테스트를 실제 알림 전달 검증으로 대신하지 않습니다.

`scripts/test-alerting.py`는 **격리 CI 프로젝트에만** 실행됩니다. Compose label을 확인하고 그 백엔드만 중단한 뒤 실제 firing→독립 inbox 수신→백엔드 재기동→inactive→resolved 수신→기존 10만 건 보존을 확인합니다. 실패 시에도 백엔드 재기동을 시도하고 CI가 컨테이너를 정리합니다. 개발·runtime·사용 중인 인증 프로젝트를 장애 대상으로 허용하지 않습니다. 검증 요약은 `frontend/.validation/alerting/summary.json`에 저장하고 Jenkins 산출물로 수집합니다.

## 대응 절차

### backend

경보 대상 환경의 컨테이너 상태·로그와 관리 포트/수집 설정을 확인합니다. 백엔드 자체 중단과 네트워크/수집 실패를 구분합니다. 승인된 환경에서 원인을 해결·재기동한 뒤 up=1·경보 해소·실제 리소스 조회를 확인합니다. 데이터 볼륨을 지우지 않습니다.

### api-errors

해당 시간대 서버 오류·최근 변경·DB 연결 상태를 확인합니다. 사용자 인증 실패와 서버 5xx를 혼동하지 않습니다. 원인 해결 후 요청/오류 비율과 실제 조회를 확인합니다. 5분 창의 과거 오류가 사라질 때까지 해소가 지연될 수 있습니다.

### mock

배치 트랜잭션 실패와 DB 접근 상태를 확인합니다. 단순 재시작 반복 대신 원인을 수정하고 배치 성공·데이터와 변경 이력의 원자적 반영을 확인합니다.

근거: [Prometheus 경보](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/), [규칙 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/), [Alertmanager 설정](https://prometheus.io/docs/alerting/latest/configuration/). 프로젝트 인증 검증은 [인증 CI](AUTHENTICATION_CI.md)를 참고합니다.
