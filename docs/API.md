# 리소스 API 계약

실제 PostgreSQL 17의 Testcontainers 및 Chromium E2E로 조회 계약을 검증했습니다. 아래 예시는 응답 형식 설명이며 성능 수치가 아닙니다.

## 엔드포인트

| 메서드·경로 | 역할 |
|---|---|
| GET /api/resources | 유형별 전체 또는 부모별 목록 |
| GET /api/resources/{type}/{id}/children | 선택한 리소스의 직접 하위 목록 |
| GET /api/resources/{type}/{id} | 공통 정보·유형별 속성·경로 |
| GET /api/resources/{type}/{id}/path | Cluster부터 선택한 리소스까지의 경로 |
| GET /api/resources/search | 이름 검색, 선택적 유형·상태 조건, 결과 경로 |
| GET /api/resources/summary | 실제 테이블별 건수 |
| GET /api/resources/{type}/{id}/events | 최신 변경 이력·이전 이력 커서 |
| GET /api/resources/{type}/{id}/kubernetes-events?limit=20 | 현재 상태에 연결된 Kubernetes Mock 이벤트, 최대 20건 |
| GET /api/operations/metrics | 고정 PromQL의 운영 값·시계열 |
| GET /actuator/health | 별도 관리 포트 8086의 서비스 상태 |
| GET /actuator/prometheus | 별도 관리 포트의 Micrometer 지표 |

## 유형과 상태

- 유형: CLUSTER, NODE, NAMESPACE, POD. 입력은 대소문자를 구분하지 않으며 응답은 대문자입니다.
- 상태: NORMAL, WARNING, ERROR. 선택하지 않으면 전체 상태입니다.
- ID와 parentId는 양수입니다.

## 목록과 하위 목록

`GET /api/resources?type=POD&parentId=1&status=NORMAL&q=worker&limit=50`

type 기본값은 CLUSTER입니다. parentId를 생략하면 해당 유형 전체를 대상으로 조회합니다. parentId는 그 유형의 직접 부모 ID입니다. 예를 들어 NODE의 부모는 Cluster, POD의 부모는 Namespace입니다. CLUSTER에는 parentId를 넣을 수 없습니다. 존재하지 않는 부모는 404입니다.

children API에서는 서버가 하위 유형과 부모 ID를 정합니다. Cluster의 children은 Node, Node는 Namespace, Namespace는 Pod입니다. 존재하는 Pod의 children은 빈 목록이며 없는 Pod는 404입니다.

공통 파라미터: q, status, limit, cursor. q는 선택적 이름 부분 검색, limit 기본 50·최대 100입니다. 응답은 다음 형식입니다.

목록·검색에는 선택적 `clusterId`, `namespaceId`도 받습니다. 양수 ID로 지정하며 기존 조건과 AND로 적용합니다. 없는 범위는 404, Cluster와 Namespace의 소속 불일치·부모 조건 충돌은 400입니다. Namespace 범위는 Namespace·Pod 유형만 허용하며 전체 유형 검색에서는 두 유형만 검색합니다. children API는 선택 리소스의 직접 하위 범위를 사용하므로 별도 범위 파라미터를 받지 않습니다.

```json
{
  "items": [
    {
      "type": "POD",
      "id": 1,
      "name": "worker-pod-000001",
      "status": "NORMAL",
      "updatedAt": "2026-01-01T00:00:00Z",
      "parent": { "type": "NAMESPACE", "id": 1, "name": "namespace-0001" },
      "hasChildren": false
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

Cluster의 parent는 null입니다. 목록 항목을 위해 전체 하위 객체를 반환하지 않습니다. hasChildren은 직접 하위 리소스 존재 여부이며 상태 필터가 적용된 하위 건수를 의미하지 않습니다.

## 커서

목록은 ID 오름차순, 전체 검색은 depth·ID 오름차순입니다. limit + 1개를 조회해 다음 페이지 존재 여부를 판단합니다. 브라우저는 nextCursor를 수정하지 않고 같은 조건에 붙여 다음 페이지를 요청합니다.

- 커서는 유형·부모·검색어·상태·Cluster·Namespace 범위에 연결됩니다. 조건 변경 시 cursor를 제거합니다.
- 페이지 크기는 바꿀 수 있습니다.
- 잘못된 Base64, 버전, 위치, 추가 바이트, 길이 초과 또는 다른 조건의 커서는 400입니다.
- 커서는 공개 탐색 상태이며 로그인 토큰·권한 증명·서명된 보안 토큰이 아닙니다.
- 임의 페이지 점프와 전체 데이터 스냅샷은 제공하지 않습니다. 상태 변화로 조건 일치 여부가 바뀌면 새 조회로 다시 확인합니다.

## 검색

`GET /api/resources/search?q=pod&type=POD&status=ERROR&limit=50`

q는 필수이며 공백을 제거한 뒤 1~160자입니다. type 생략 시 4개 유형을 검색합니다. 이름·유형·상태는 AND 조건입니다. q의 `%`, `_`, `!`는 SQL 와일드카드가 아닌 문자 그대로 검색합니다. 검색값은 SQL 파라미터로 바인딩합니다.

검색 결과의 각 items 원소는 `{ "resource": 목록항목, "path": 경로배열 }`입니다. 경로를 응답에 포함해 결과마다 추가 API 요청을 보내는 패턴을 피합니다.

영속 계층은 JPA Entity·DTO projection으로 전환했지만 API 계약은 유지합니다. 전체 유형 검색은 depth 순서로 최대 4개의 쿼리를 실행하고 합산 최대 `limit + 1`개만 가져옵니다. 목록·검색마다 전체 count하거나 Entity 관계를 행별 추가 조회하지 않습니다. [조회·트랜잭션·실제 DB 검증](JPA_QUERY_DESIGN.md)을 참고합니다.

## 상세와 경로

상세는 `{ "resource": 목록항목, "attributes": 유형별속성, "path": 경로배열 }`입니다.

- Cluster attributes: nodeCount, readyNodeCount, podCount, problemPodCount.
- Node attributes: cpuUsage, memoryUsage, ready, memoryPressure, diskPressure, cpuCapacityCores, cpuAllocatableCores, cpuRequestedCores, memoryCapacityMiB, memoryAllocatableMiB, memoryRequestedMiB, conditionReason, gpuDevices[]. GPU 장치 목록은 선택 상세에서 최대 8개입니다.
- Namespace attributes: podCount, errorPodCount, notReadyPodCount.
- Pod attributes: restartCount, phase, containerCount, readyContainerCount, containerState, failureReason, lastTerminationReason, image, workloadKind, workloadName, containers[]. 컨테이너 배열은 선택 상세에서 최대 4개입니다.
- diagnosticSource="MOCK"가 포함되며 failureReason/lastTerminationReason은 문제 없을 때 null입니다. 집계는 선택 리소스 전체 범위이고 목록 500개·화면 필터 결과 수가 아닙니다. [정의·시나리오·권한·쿼리 예산](RESOURCE_DIAGNOSTICS.md)을 참고합니다.
- path는 Cluster부터 자기 자신까지 최대 4개의 `{type, id, name}`입니다.
- 전용 path API 응답은 `{ "items": 경로배열 }`입니다.

## 오류·운영 경계

이력은 ID 내림차순이며 `limit` 기본 20·최대 100, `beforeId`는 양수입니다. 응답에는 `items`, `hasMore`, `nextBeforeId`, 보관 정책이 포함됩니다. 각 이력은 이전/다음 상태·속성·발생원·시각을 표시합니다. 리소스 변경과 같은 트랜잭션으로 저장하며 전역 최신 20,000개/24시간 정책으로 제한합니다.

운영 응답은 `available`, `source`, `fetchedAt`, `values`, `series`, `message`입니다. `values`는 정의된 10개 지표, `series`는 API·DB P95의 최근 30분 값입니다. Prometheus 장애는 200 응답의 `available=false`와 빈 값으로 표시하고 UI가 연결 안내를 제공합니다. 표본 부족·NaN은 누락 또는 null이며 DB 값으로 대체하지 않습니다. 임의 PromQL·URL은 요청받지 않습니다. [정의·안전 경계](OPERATIONS_AND_DELIVERY.md)를 참고합니다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_QUERY | 유형·ID·필터·페이지·커서·필수 값 오류 |
| 404 | RESOURCE_NOT_FOUND | 해당 유형의 리소스 또는 부모 없음 |
| 503 | QUERY_TIMEOUT | SQL 실행 시간 제한 초과 |
| 503 | STORAGE_UNAVAILABLE | 저장소 조회 실패 |

오류는 ProblemDetail 형식이며 code와 가능한 경우 field를 추가합니다. SQL·내부 오류 메시지는 응답에 넣지 않습니다. 각 리소스 조회 SQL은 기본 3초 제한입니다. 이는 브라우저 반응 목표나 전체 요청의 3초 제한을 보장하지 않습니다.

기본 API 모드는 로컬 Mock 데이터 조회용입니다. 선택형 인증 모드는 아래 Keycloak 경계를 적용하며 인터넷 운영 배포 설정과는 구분합니다.

## 연결 화면

연결 풀 고갈로 JPA 트랜잭션을 시작할 수 없는 경우도 `503 STORAGE_UNAVAILABLE`입니다. 연결 대기 기본 상한 3초·연결 검사 1초·SQL별 3초는 서로 다른 경계이며 합산 요청의 3초 보장은 아닙니다. 연결이 반환되면 기존 API를 재요청할 수 있고 내부 DB/풀 진단은 응답에 포함하지 않습니다.

화면은 children의 50개 페이지, 목록·검색의 100개 페이지를 요청합니다. 상세의 path를 사용하므로 전용 path API를 중복 호출하지 않습니다. 검색은 선택한 Cluster·Namespace 범위 안에서 유형·이름·상태 조건을 적용하며 계층에는 상태 조건을 적용하지 않아 조상을 유지합니다. 범위를 바꾸면 목록·커서를 초기화합니다. 자동 폴링은 기존 GET을 현재 창에만 사용하고 집계는 제외합니다. 운영 관측 화면에서는 목록 정기 폴링을 중지합니다. 변경용 HTTP API는 없습니다. [화면 설계](FRONTEND_RENDERING.md), [자동 갱신](LIVE_UPDATES.md), [최신 확장 검증](OPERATIONS_AND_DELIVERY.md)을 참고합니다.

클라이언트는 각 GET의 응답·JSON 처리에 10초 제한을 적용합니다. 시간 초과는 별도 한국어 안내로 표시하고 기존 갱신 데이터를 유지합니다. 사용자 취소는 시간 초과로 분류하지 않습니다. 이 제한은 서버 SQL별 제한이나 브라우저 성능 목표와 동일하지 않습니다.
## 선택형 인증 경계

`GET /api/operations/alerts`는 `available`, `source`, `fetchedAt`, `rules`, `message`를 반환합니다. 세 알려진 경보의 각 rule은 `name`, `title`, `severity`, `state`(inactive/pending/firing), `activeAt`, `value`, `condition`, `response`입니다. rule 누락/평가 실패/소스 장애는 HTTP 200의 `available=false`, 빈 rules와 안전한 메시지이며 "경보 없음"으로 대체하지 않습니다. 사용자 입력 PromQL/URL을 받지 않는 읽기 전용 API이며 인증 모드 ADMIN만 허용합니다. 실제 알림 전달 여부/영구 해소 이력과는 구분합니다. [규칙·제한·독립 수신·복구 검증](ALERTING_AND_RECOVERY.md)을 참고합니다.

인증 모드에서는 `/api/auth/config`만 공개 설정이며 나머지 리소스 API는 유효한 API용 Bearer JWT와 ADMIN/VIEWER client role이 필요합니다. ADMIN은 전체, VIEWER는 Keycloak이 배정한 Cluster 범위만 조회합니다. 목록·검색·집계는 서버 쿼리에서 제한하고 다른 범위의 상세/경로/하위/이력/명시적 필터는 404, 운영 관측은 ADMIN만 허용해 VIEWER에 403입니다. 토큰 누락/검증 실패는 401입니다. 기존 비인증 모드는 루프백 과제 검증용입니다.

- `GET /api/auth/config`: enabled·issuer·clientId, 비밀값 없음.
- `GET /api/auth/me`: 인증 사용자 표시명·administrator·허용 clusters.
- 인증 커서는 issuer/sub/배정 범위에 연결되며 다른 계정의 원본 커서는 400입니다. 커서 자체가 접근 권한은 아닙니다.

세 계정과 상세 경계는 [인증 설계](AUTHENTICATION.md)를 참고합니다.

## 관리자 경보 메일 설정

인증 모드의 ADMIN만 GET/PUT `/api/operations/alert-email`을 사용할 수 있습니다. PUT JSON은 recipient(주소 하나 또는 빈 문자열)·enabled(boolean)이며 응답은 recipient·enabled·smtpConfigured·available입니다. SMTP 비밀값은 받거나 반환하지 않습니다. 형식 오류/SMTP 미설정 중 활성화는 400, 내부 설정·적용 장애는 503, 익명 401·VIEWER 403입니다. 비인증 모드에는 endpoint가 없습니다. 저장은 배달 성공 확인이 아닙니다. [실행·보안·제약](ALERT_EMAIL.md)을 참고합니다.
