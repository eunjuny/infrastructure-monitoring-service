# 리소스 API 계약

실제 PostgreSQL 17의 Testcontainers 및 Chromium E2E로 조회 계약을 검증했습니다. 아래 예시는 응답 형식 설명이며 성능 수치가 아닙니다. [실행 근거](STAGE4_REPORT.md)를 참고합니다.

## 엔드포인트

| 메서드·경로 | 역할 |
|---|---|
| GET /api/resources | 유형별 전체 또는 부모별 목록 |
| GET /api/resources/{type}/{id}/children | 선택한 리소스의 직접 하위 목록 |
| GET /api/resources/{type}/{id} | 공통 정보·유형별 속성·경로 |
| GET /api/resources/{type}/{id}/path | Cluster부터 선택한 리소스까지의 경로 |
| GET /api/resources/search | 이름 검색, 선택적 유형·상태 조건, 결과 경로 |
| GET /api/resources/summary | 실제 테이블별 건수 |
| GET /actuator/health | 서비스 상태 |

## 유형과 상태

- 유형: CLUSTER, NODE, NAMESPACE, POD. 입력은 대소문자를 구분하지 않으며 응답은 대문자입니다.
- 상태: NORMAL, WARNING, ERROR. 선택하지 않으면 전체 상태입니다.
- ID와 parentId는 양수입니다.

## 목록과 하위 목록

`GET /api/resources?type=POD&parentId=1&status=NORMAL&q=worker&limit=50`

type 기본값은 CLUSTER입니다. parentId를 생략하면 해당 유형 전체를 대상으로 조회합니다. parentId는 그 유형의 직접 부모 ID입니다. 예를 들어 NODE의 부모는 Cluster, POD의 부모는 Namespace입니다. CLUSTER에는 parentId를 넣을 수 없습니다. 존재하지 않는 부모는 404입니다.

children API에서는 서버가 하위 유형과 부모 ID를 정합니다. Cluster의 children은 Node, Node는 Namespace, Namespace는 Pod입니다. 존재하는 Pod의 children은 빈 목록이며 없는 Pod는 404입니다.

공통 파라미터: q, status, limit, cursor. q는 선택적 이름 부분 검색, limit 기본 50·최대 100입니다. 응답은 다음 형식입니다.

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

- 커서는 유형·부모·검색어·상태 조건에 연결됩니다. 조건 변경 시 cursor를 제거합니다.
- 페이지 크기는 바꿀 수 있습니다.
- 잘못된 Base64, 버전, 위치, 추가 바이트, 길이 초과 또는 다른 조건의 커서는 400입니다.
- 커서는 공개 탐색 상태이며 로그인 토큰·권한 증명·서명된 보안 토큰이 아닙니다.
- 임의 페이지 점프와 전체 데이터 스냅샷은 제공하지 않습니다. 상태 변화로 조건 일치 여부가 바뀌면 새 조회로 다시 확인합니다.

## 검색

`GET /api/resources/search?q=pod&type=POD&status=ERROR&limit=50`

q는 필수이며 공백을 제거한 뒤 1~160자입니다. type 생략 시 4개 유형을 검색합니다. 이름·유형·상태는 AND 조건입니다. q의 `%`, `_`, `!`는 SQL 와일드카드가 아닌 문자 그대로 검색합니다. 검색값은 SQL 파라미터로 바인딩합니다.

검색 결과의 각 items 원소는 `{ "resource": 목록항목, "path": 경로배열 }`입니다. 경로를 응답에 포함해 결과마다 추가 API 요청을 보내는 패턴을 피합니다.

## 상세와 경로

상세는 `{ "resource": 목록항목, "attributes": 유형별속성, "path": 경로배열 }`입니다.

- Node attributes: cpuUsage, memoryUsage.
- Pod attributes: restartCount.
- Cluster와 Namespace attributes는 현재 빈 객체입니다.
- path는 Cluster부터 자기 자신까지 최대 4개의 `{type, id, name}`입니다.
- 전용 path API 응답은 `{ "items": 경로배열 }`입니다.

## 오류·운영 경계

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_QUERY | 유형·ID·필터·페이지·커서·필수 값 오류 |
| 404 | RESOURCE_NOT_FOUND | 해당 유형의 리소스 또는 부모 없음 |
| 503 | QUERY_TIMEOUT | SQL 실행 시간 제한 초과 |
| 503 | STORAGE_UNAVAILABLE | 저장소 조회 실패 |

오류는 ProblemDetail 형식이며 code와 가능한 경우 field를 추가합니다. SQL·내부 오류 메시지는 응답에 넣지 않습니다. 각 리소스 조회 SQL은 기본 3초 제한입니다. 이는 브라우저 반응 목표나 전체 요청의 3초 제한을 보장하지 않습니다.

현재 API는 로컬 Mock 데이터 조회 전용이며 사용자 인증·운영 배포 API가 아닙니다.

## 연결 화면

화면은 children의 50개 페이지, 목록·검색의 100개 페이지를 요청합니다. 상세의 path를 사용하므로 전용 path API를 중복 호출하지 않습니다. 검색은 부모 조건 없이 유형 전체를 대상으로 하며 계층에는 상태 조건을 적용하지 않아 조상을 유지합니다. 자동 폴링은 기존 GET을 현재 창에만 사용하고 집계는 제외합니다. 새로운 변경용 HTTP API는 없습니다. [화면 설계](FRONTEND_RENDERING.md), [자동 갱신](LIVE_UPDATES.md), [실제 검증](STAGE4_REPORT.md)을 참고합니다.

클라이언트는 각 GET의 응답·JSON 처리에 10초 제한을 적용합니다. 시간 초과는 별도 한국어 안내로 표시하고 기존 갱신 데이터를 유지합니다. 사용자 취소는 시간 초과로 분류하지 않습니다. 이 제한은 서버 SQL별 제한이나 브라우저 성능 목표와 동일하지 않습니다.
