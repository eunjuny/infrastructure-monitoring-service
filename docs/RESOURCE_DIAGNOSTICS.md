# 리소스별 진단 정보

2026-10-18 · 상세 패널의 `개요 → 리소스 진단`. 이 화면은 초기 데이터에 저장된 리소스 시나리오와 PostgreSQL 집계로 동작하며, Kubernetes API·실제 GPU 계측을 연결하지 않습니다.

## 필드와 집계 범위

| 유형 | 추가 속성 |
|---|---|
| Cluster | 전체/준비된 Node 수, 전체/문제 Pod 수 |
| Node | Ready·MemoryPressure·DiskPressure, CPU/메모리 용량·할당 가능량·요청량 시나리오 값·현재 사용량, GPU 장치 모델·메모리·사용률·온도·할당 Pod 수 |
| Namespace | 전체/오류/준비되지 않은 Pod 수 |
| Pod | phase·준비/전체 컨테이너 수·요약 문제 사유·워크로드 종류/이름, 선택 Pod의 컨테이너별 이미지·상태·Ready·재시작·CPU/메모리 요청 및 사용량 |
| 리소스 이벤트 | 현재 이상 상태별 reason·message·최초/최근 시각·반복 횟수. 정상 복구 시 현재 이벤트 제거 |

Cluster/Namespace 집계는 선택 리소스 **전체 하위 DB 범위**이며, 화면 최대 500개 목록이나 검색/상태 필터 결과 수가 아닙니다. 문제 Pod와 미준비 Pod는 status != NORMAL, 오류 Pod는 status == ERROR입니다. 오류는 미준비의 부분집합이므로 합산하지 않습니다. 준비된 Node는 status != ERROR입니다. 없는/접근 불가능한 리소스는 404이고 존재하는 빈 Namespace만 실제 0을 반환합니다.

### Node 자원과 GPU

GPU는 Node 상세의 `개요 → 리소스 진단 → GPU 장치`에 표시됩니다. Node ID가 10의 배수인 20개 Node에만 2개씩 있어 총 40개입니다. 예를 들면 `node-010`에는 장치 2개가 있고 `node-001`에는 없습니다. 이 값은 `scripts/seed.sql`이 넣은 진단 시나리오이며 GPU collector에서 읽지 않습니다. 현재 조건은 모두 짝수 Node ID여서 실제 생성 모델은 NVIDIA A100 40GB뿐입니다. 홀수 ID에 L40S 48GB를 선택하는 SQL 분기는 있지만 현재 seed에서는 해당되지 않습니다. GPU 메모리는 A100 40,960 MiB로 저장되고, 장치별 사용률은 `mod(node_id × 17 + device_number × 13, 101)`, 온도는 `35 + mod(node_id × 3 + device_number × 7, 51)`°C, 할당 Pod 수는 `mod(node_id + device_number, 5)`로 생성됩니다. 이 GPU 행은 상태 시뮬레이터에서 갱신되지 않습니다.

CPU 용량은 `8 × (1 + node_id % 4)` 코어, CPU 할당 가능량은 그 90%입니다. 메모리 용량은 `16,384 × (1 + node_id % 4)` MiB이며 할당 가능량은 `14,745 × (1 + node_id % 4)` MiB로 초기 생성됩니다. 화면의 `Pod CPU 요청량`·`Pod 메모리 요청량`는 `infrastructure_node`에 저장된 별도 fixture 값입니다. CPU는 용량에 `(0.35 + (node_id % 5) × 0.10)`을 곱하고, 메모리도 같은 비율로 생성합니다. Node 상세에서는 이 두 값을 하위 Pod 컨테이너 요청량의 합으로 계산하지 않습니다. Pod별 CPU·메모리 요청은 각 컨테이너 행에서 별도로 표시합니다. 현재 CPU/메모리 사용량은 `용량 × 사용률 / 100`으로 환산합니다. 메모리는 MiB를 1,024로 나눠 GiB로 표시하며 소수 사용량도 표시합니다. NaN·무한대·음수는 값 없음으로 처리합니다. 초기 할당 가능량의 약 90% 비율은 예제 구성값이지 실제 Node 예약 정책을 측정한 값은 아닙니다.

Namespace는 과제의 Node 아래 드릴다운 범위이며 실제 Kubernetes의 클러스터 전체 Namespace/ResourceQuota가 아닙니다. CPU/메모리 **용량**과 사용률을 구분하고 MiB를 화면에서 GiB로 변환합니다. 이미지의 example.invalid는 실행/다운로드하지 않는 생성 메타데이터이고 워크로드 문자열은 실제 Deployment/Job의 존재나 조회 기능을 뜻하지 않습니다.

## 상태 시나리오

동적 진단은 `MockDiagnostics`가 기존 상태와 ID에서 계산합니다. 기존 시뮬레이터의 순환과 맞물리며 별도 저장된 진단 상태가 어긋나지 않습니다. MemoryPressure를 사용률 하나로 추론한 값이나 실제 장애 분석 결과로 설명하지 않습니다.

| 상태 | 진단 |
|---|---|
| Node NORMAL | Ready=true, Pressure=false |
| Node WARNING | Ready=true, 짝수 MemoryPressure / 홀수 DiskPressure |
| Node ERROR | Ready=false, KubeletNotReady |
| Pod NORMAL | Running, 전부 준비, 현재 문제 없음 |
| Pod WARNING·짝수 | Pending, 준비 0, Waiting/ImagePullBackOff |
| Pod WARNING·홀수 | Running, 전체보다 1개 적게 준비, ReadinessProbeFailed |
| Pod ERROR·짝수 | Running, 준비 0, Waiting/CrashLoopBackOff, 최근 종료 OOMKilled |
| Pod ERROR·홀수 | Failed, 준비 0, Terminated/Error |

CrashLoopBackOff는 phase가 아니라 컨테이너 대기 사유이고 Running과 Ready는 다릅니다. Kubernetes Event는 Kubernetes가 오브젝트에서 관측한 상태 변화·스케줄링·컨테이너 문제의 이유와 메시지를 기록합니다. 이 화면의 `리소스 이벤트`는 현재 선택 리소스의 생성 시나리오 상태에서 만든 최신 이벤트 한 건을 보관하고, 상태가 정상화되면 제거합니다. 실제 Kubernetes Events API 스트림이나 보존형 감사 로그가 아니며, 실제 클러스터의 과거 재시작 원인·시간도 수집하지 않습니다. GPU·진단·이벤트의 반복적인 생성 데이터 배지와 설명은 사용자 화면에서 제거했습니다. seed의 새 기본 이벤트 메시지는 일반 표현으로 바꿨고, 기존 DB의 정확히 같은 과거 기본 안내 한 문장만 화면에서 일반 상태 설명으로 표시합니다. 다른 메시지/메타데이터는 가공하거나 지우지 않습니다. 데이터 생성 경로와 실제 collector 미연결 범위는 이 문서에 유지합니다. GPU 40개는 GPU가 있는 Node와 없는 Node를 함께 탐색하기 위한 초기 구성이며 기술적 최대 개수가 아닙니다.

## 조회·권한·성능

기존 상세 API의 attributes만 확장하고 resource/path·cpuUsage/memoryUsage/restartCount는 유지합니다. 목록/검색 응답·최대 100개·500개 캐시·가상화는 유지합니다.

`detailWithDiagnostics`는 readOnly/REPEATABLE_READ에서 접근 확인된 기본 상세와 진단을 같은 DB snapshot으로 읽습니다. 집계/메타데이터 쿼리에도 Cluster 권한을 바인딩하고 scalar만 반환합니다. 전체 하위 객체를 Java로 가져오지 않습니다.

- Cluster 상세: 기본 1 + Node 집계 1 + Pod 집계 1 = 3 SQL.
- Namespace 상세: 기본 1 + Pod 집계 1 = 2 SQL.
- Node 상세: 기본 1 + 자원 계측 1 + GPU 장치 목록 1 = 3 SQL. GPU 장치 목록은 최대 8행.
- Pod 상세: 기본 1 + 진단 1 + 컨테이너 목록 1 = 3 SQL. 컨테이너 목록은 최대 4행.
- Kubernetes 이벤트는 별도 `/api/resources/{type}/{id}/kubernetes-events?limit=20` 요청이며 리소스 권한을 다시 검증하고 최대 20행을 반환합니다.
- 기본 Service.detail/path/이력 접근 확인은 그대로이며 집계를 추가하지 않습니다. path 1·이력 2회 유지.
- 상세 없는 초기/목록에는 새 집계가 없습니다. **선택 상세의 자동 폴링에는 해당 범위 집계도 포함**됩니다. 전역 15만 건을 매번 count하지 않습니다.
- 기존 부모/상태 인덱스·JOIN·SQL별 3초 제한을 사용합니다. COUNT는 해당 범위를 확인하므로 반환이 한 행이라고 비용이 O(1)은 아닙니다. 성능 합격은 실제 측정과 구분합니다.

## 저장·이행

Flyway V4는 기본 Node 용량과 Pod 대표 메타데이터를 추가했습니다. V5는 Node 할당/요청량, Pod별 컨테이너 행, Node GPU 행, 현재 이벤트 테이블/인덱스를 추가합니다. V1~V4는 수정하지 않습니다. 데이터 배치는 기존 리소스 상태·ID·부모와 이력을 보존하고 컨테이너/GPU 누락 행만 채웁니다. 서버 이미지가 V5를 적용한 뒤 seed를 실행합니다. 상태 시뮬레이터는 선택된 Pod의 컨테이너 상태와 현재 이벤트를 리소스 상태·변경 이력과 같은 트랜잭션에서 갱신합니다.

리소스 상태의 자동 변경은 실제 Kubernetes watch나 Prometheus scrape에서 감지하지 않습니다. 선택형 시뮬레이터가 최초 10초 대기 뒤 각 DB 커밋 완료 5초 후 실행되며, Cluster 1개·Node 5개·Namespace 10개·Pod 100개씩 ID keyset으로 골라 상태/시각을 갱신합니다. 각 유형은 별도 커서를 가집니다. 한 주기는 대략 Cluster 100초, Node 200초, Namespace 1,000초, Pod 7,500초(125분)이며 쿼리 실행 시간만큼 늘어납니다. 따라서 15만 Pod가 매 5초마다 모두 변경되는 것이 아닙니다. 이 범위의 실제 자원 수집은 Kubernetes API watch/주기 수집기를 연결할 때 구현해야 합니다. Node GPU 행은 현재 시뮬레이터 대상이 아니므로 갱신되지 않습니다.

동적 조건은 현재 상태에서 계산하며 기존 이력은 원래 상태/사용률/재시작 수를 유지합니다. 과거 컨테이너 상태를 새로 만들어 이력처럼 표시하지 않습니다. 기존 실행은 백엔드/프론트를 재빌드해야 하며 백엔드 시작 시 V5가 적용됩니다. 인증 앱 DB를 백업한 뒤 해당 환경만 재기동하며 identity DB/계정은 그대로입니다. 이전 앱으로 돌아갈 때 새 컬럼을 임의 DROP하지 않습니다. 다른 실행 모드 DB는 해당 백엔드 재배포 때 이행합니다.

## 검증 범위

단위 시나리오, 실제 PostgreSQL V4→V5 보존·15만 seed·컨테이너/GPU 행 수·상태 전환과 이벤트 upsert/정리·JPA scalar SQL 예산·실제 JWT/JWKS 교차 권한 차단·상세/이벤트 화면을 검증합니다. 성능 반복과 실제 Kubernetes/GPU collector 연결 여부는 별도입니다.

2026-10-18 PostgreSQL/Testcontainers 통합 테스트 39개(진단7·스키마1·인증14·Mock 상태7·JPA 조회10), 프론트 99개, TypeScript 검사, 프로덕션 빌드와 `scripts/check.sh fast` 73개를 통과했습니다. 1440px/390px에서 네 유형의 API 응답과 화면 값을 비교했고 가로 넘침 없이 표시했습니다. 실제 실행 환경의 V5 배포 검증 상태는 WH-047에 따로 기록하며, 테스트 성공을 실제 서비스 반영으로 혼동하지 않습니다.

WH-047 통합 과정에서 인증 평가 DB에 V5를 실제 적용했습니다. Pod 150,000·컨테이너 200,000·GPU 40·현재 이벤트 118,104행, DB/backend/alert-receiver health와 웹·OIDC 설정·Prometheus·Alertmanager ready 응답을 확인했고 Keycloak E2E 14개도 통과했습니다. 데이터베이스는 비공개 dump 백업 후 이행했으며 기존 계정/데이터를 유지했습니다.

실행 중 DB의 단일 EXPLAIN ANALYZE 프로브에서 Cluster의 Pod 집계11.367ms(기존 Node 부모·Pod 부모/상태 인덱스), Namespace 집계0.172ms(Pod 부모/상태 인덱스)를 확인했습니다. 현재 로컬 DB의 한 표본이며 HTTP/CPU4x 브라우저 지연·동시 부하·다른 실행 모드·전체 Jenkins 합격을 뜻하지 않습니다. [정리된 검증 근거](verification/2026-10-18-resource-diagnostics.json)를 참고합니다.
