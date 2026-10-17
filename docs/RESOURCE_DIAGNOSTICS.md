# 리소스별 진단 정보

2026-10-18 · 상세 패널의 `개요 → 리소스 진단`. 실제 Kubernetes 연동/측정이 아닌 Mock 시나리오와 DB 집계이며, 새 OSS/서버/유료 API는 없습니다.

## 필드와 집계 범위

| 유형 | 추가 속성 |
|---|---|
| Cluster | 전체/준비된 Node 수, 전체/문제 Pod 수 |
| Node | Ready·MemoryPressure·DiskPressure, CPU 코어·메모리 MiB 용량, 상태 사유 |
| Namespace | 전체/오류/준비되지 않은 Pod 수 |
| Pod | phase·준비/전체 컨테이너 수·진단 대상 컨테이너 상태·현재 문제/최근 종료 사유·대표 이미지·워크로드 종류/이름 |

Cluster/Namespace 집계는 선택 리소스 **전체 하위 DB 범위**이며, 화면 최대 500개 목록이나 검색/상태 필터 결과 수가 아닙니다. 문제 Pod와 미준비 Pod는 status != NORMAL, 오류 Pod는 status == ERROR입니다. 오류는 미준비의 부분집합이므로 합산하지 않습니다. 준비된 Node는 status != ERROR입니다. 없는/접근 불가능한 리소스는 404이고 존재하는 빈 Namespace만 실제 0을 반환합니다.

Namespace는 과제의 Node 아래 드릴다운 범위이며 실제 Kubernetes의 클러스터 전체 Namespace/ResourceQuota가 아닙니다. CPU/메모리 **용량**과 사용률을 구분하고 MiB를 화면에서 GiB로 변환합니다. 이미지의 example.invalid는 실행/다운로드하지 않는 Mock 메타데이터이고 워크로드 문자열은 실제 Deployment/Job의 존재나 조회 기능을 뜻하지 않습니다.

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

CrashLoopBackOff는 phase가 아니라 컨테이너 대기 사유이고 Running과 Ready는 다릅니다. 컨테이너가 여러 개여도 진단 대상/대표 이미지 하나만 제공하며 개별 컨테이너 목록은 없습니다. failureReason에는 준비 검사 실패도 포함되고 lastTerminationReason과 다릅니다. 과거 모든 재시작의 원인·시간을 추적한 데이터는 아닙니다.

## 조회·권한·성능

기존 상세 API의 attributes만 확장하고 resource/path·cpuUsage/memoryUsage/restartCount는 유지합니다. 목록/검색 응답·최대 100개·500개 캐시·가상화는 유지합니다.

`detailWithDiagnostics`는 readOnly/REPEATABLE_READ에서 접근 확인된 기본 상세와 진단을 같은 DB snapshot으로 읽습니다. 집계/메타데이터 쿼리에도 Cluster 권한을 바인딩하고 scalar만 반환합니다. 전체 하위 객체를 Java로 가져오지 않습니다.

- Cluster 상세: 기본 1 + Node 집계 1 + Pod 집계 1 = 3 SQL.
- Namespace/Node/Pod 상세: 기본 1 + 집계/메타데이터 1 = 2 SQL.
- 기본 Service.detail/path/이력 접근 확인은 그대로이며 집계를 추가하지 않습니다. path 1·이력 2회 유지.
- 상세 없는 초기/목록에는 새 집계가 없습니다. **선택 상세의 자동 폴링에는 해당 범위 집계도 포함**됩니다. 전역 15만 건을 매번 count하지 않습니다.
- 기존 부모/상태 인덱스·JOIN·SQL별 3초 제한을 사용합니다. COUNT는 해당 범위를 확인하므로 반환이 한 행이라고 비용이 O(1)은 아닙니다. 성능 합격은 실제 측정과 구분합니다.

## 저장·이행

Flyway V4는 Node cpu_capacity_cores·memory_capacity_mib, Pod container_count·image·workload_kind·workload_name을 추가/backfill합니다. ID·이름·소속·status·updated_at·사용률·재시작 수·이력을 바꾸지 않으며 V1~V3는 수정하지 않습니다. 새 seed도 메타데이터를 생성하지만 ON CONFLICT DO NOTHING으로 기존 값을 보존합니다. 일회성 메타데이터 backfill은 Mock 상태 변경 이력으로 기록하지 않습니다.

동적 조건은 현재 상태에서 계산하며 기존 이력은 원래 상태/사용률/재시작 수를 유지합니다. 과거 컨테이너 상태를 새로 만들어 이력처럼 표시하지 않습니다. 기존 실행은 백엔드/프론트를 재빌드해야 하며 백엔드 시작 시 V4가 적용됩니다. 인증 앱 DB를 백업한 뒤 해당 환경만 재기동하며 identity DB/계정은 그대로입니다. 이전 앱으로 돌아갈 때 새 컬럼을 임의 DROP하지 않습니다. 다른 실행 모드 DB는 해당 백엔드 재배포 때 이행합니다.

## 검증 범위

단위 시나리오, 실제 PostgreSQL V3→V4 기존 상태 보존·15만 seed·전체 범위/빈/없음 집계, JPA scalar SQL 예산과 Entity 로딩 0, 실제 JWT/JWKS 교차 권한 차단, 네 유형 화면·단위·누락/0 구분·안전한 문자열 출력·실제 인증/모바일을 검증합니다. 실행 결과는 [작업 이력](WORK_HISTORY.md)의 WH-042와 기준 문서에 기록하며 이전 Jenkins/성능을 새 기능 검증으로 대체하지 않습니다.

2026-10-18 실제 실행 결과는 서버140개·프론트95개·실제 Keycloak E2E14개입니다. 1440px/390px에서 네 유형의 API 응답과 화면 값을 비교했고 가로 넘침 없이 표시했습니다. 인증 DB를 비공개 백업하고 V4 적용 직후 기존 열/이력의 건수와 내용 지문이 일치하는 것을 확인했습니다. Pod150,000과 계정/권한을 보존했으며 인증5176만 새 이미지로 반영했습니다. 백업은 자동 삭제하지 않고 복구용으로 보관합니다.

실행 중 DB의 단일 EXPLAIN ANALYZE 프로브에서 Cluster의 Pod 집계11.367ms(기존 Node 부모·Pod 부모/상태 인덱스), Namespace 집계0.172ms(Pod 부모/상태 인덱스)를 확인했습니다. 현재 로컬 DB의 한 표본이며 HTTP/CPU4x 브라우저 지연·동시 부하·다른 실행 모드·전체 Jenkins 합격을 뜻하지 않습니다. [정리된 검증 근거](verification/2026-10-18-resource-diagnostics.json)를 참고합니다.
