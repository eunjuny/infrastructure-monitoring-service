# JPA 전환과 조회 설계

## 전환 목적과 범위

2026-10-18 진단 확장에서는 V5 진단 테이블을 매핑하고 상세 전용 HQL scalar 집계/조회만 추가합니다. 목록 projection/SQL 1회·Entity 로딩 0은 유지하고 Cluster 3 SQL·Namespace 2·Node 3·Pod 3·path 1·이력 2로 제한합니다. Node GPU·Pod 컨테이너 자식 행은 해당 상세에서만 최대 8/4행으로 조회하며 이벤트는 리소스 접근을 다시 확인하는 별도 API에서 최대 20행입니다. [정의·API·이행·검증](RESOURCE_DIAGNOSTICS.md)을 참고합니다.

과제 가이드는 백엔드 기술을 제한하지 않습니다. 유형별 관계를 Entity로 매핑하고 조회는 JPA/Hibernate DTO projection으로 구현했습니다. JPA가 JDBC보다 빠르다는 주장이나 실무 운영 경험의 대체가 아닙니다.

애플리케이션의 목록·검색·상세·경로·건수·이력 조회는 EntityManager로 실행합니다. `JdbcTemplate`은 실제 DB 테스트의 독립 검증·fixture 적재에서만 사용합니다. 기존 API와 화면, Flyway V1~V3, 데이터와 인덱스는 유지했습니다. 새 테이블을 만들거나 기존 데이터를 재생성·삭제하지 않습니다.

## Entity와 API 응답 분리

```text
ClusterResource ← NodeResource ← NamespaceResource ← PodResource
     @ManyToOne(LAZY)로 부모 연결 / 자식 컬렉션은 매핑하지 않음

조건 검증 → JPA/Hibernate 관계 JOIN + select new ResourceProjection(...)
         → 최대 limit+1개의 스칼라 DTO → 기존 응답·경로·속성 조립
         → 트랜잭션 종료 → JSON 응답

ResourceChangeEvent → JSONB 필드 매핑 → 이력 DTO projection
```

5개 Entity로 리소스와 변경 이력을 매핑합니다. API는 Entity를 직렬화하지 않습니다. 조회에서는 이름·상태·갱신 시각·조상 ID/이름·필요한 속성을 한 번의 JOIN 쿼리로 가져옵니다. 따라서 결과 행마다 부모를 지연 로딩하는 구조가 아닙니다. `hasChildren`은 같은 SQL 안의 상관 EXISTS로 계산합니다.

현재 Entity는 조회 매핑 중심입니다. 리소스 ID는 seed에서 정하고 이력 ID는 native INSERT의 DB sequence 기본값으로 생성합니다. `save`·cascade·dirty checking 기반 CRUD를 추가한 구현은 아닙니다. EntityManager의 HQL DTO 조회·관계 매핑·트랜잭션·native bulk 경계를 실제 검증한 범위로 설명합니다.

- `spring.jpa.open-in-view=false`: 응답 직렬화 단계의 지연 로딩을 막습니다.
- 조회 서비스·Repository는 `@Transactional(readOnly=true)`와 Hibernate readOnly query hint를 적용합니다.
- `ddl-auto=validate`: Hibernate는 Flyway 스키마의 Entity 매핑을 확인하며 DDL을 자동 변경하지 않습니다.
- collection fetch join과 페이지 제한을 혼용하면 실패하도록 설정했습니다. 현재 조회 자체에는 fetch join을 쓰지 않습니다.

## keyset·검색·인덱스 유지

`id > :afterId ORDER BY id`, `setMaxResults(limit + 1)`을 사용합니다. `Page`의 자동 전체 count나 offset 페이지로 바꾸지 않았습니다. 값은 JPA query parameter로 바인딩하며 유형·Entity 이름은 서버 enum에서만 선택합니다.

유형 지정 검색은 한 번의 DTO 쿼리입니다. 전체 유형 검색은 depth 순서로 최대 4개의 제한된 쿼리를 실행하며, 이미 지나간 depth를 건너뛰고 전체 `limit + 1`을 채우면 중단합니다. 합산 반환량도 최대 101개입니다. 이는 JDBC의 단일 UNION에서 바뀐 부분이며 행마다 추가 조회하는 N+1과 다릅니다. 이름 미일치 시 네 번 조회하는 왕복 비용이 있을 수 있습니다. 동시 상태 변화 중 전체 스냅샷 일관성은 보장하지 않습니다.

이름 검색은 Hibernate HQL의 `ilike`를 사용합니다. `lower(name)`로 바꾸면 기존 name GIN 인덱스와 표현식이 달라지므로 PostgreSQL ILIKE를 유지합니다. 이 부분과 SELECT 안의 EXISTS는 Hibernate 확장으로, 다른 JPA provider/DB로 그대로 이식 가능한 표준 JPQL만 사용하는 것은 아닙니다.

정확한 규모 집계는 유형별 count4개입니다. 초기 조회와 마지막 집계 완료 후60초가 지난 갱신 작업에 실행하며 목록/검색의 매 페이지나5초 상태 폴링마다 반복하지 않습니다. JPA timeout은 SQL별3초이며 전체 요청의3초 보장은 아닙니다.

## 변경과 이력: JPA native query 경계

DB 연결 획득은 SQL 실행 전 별도 대기입니다. Hikari `connection-timeout=3000ms`·`validation-timeout=1000ms`를 적용하고, 풀 고갈의 트랜잭션 시작 실패도 안전한 503으로 반환합니다. 실제 PostgreSQL 풀의 연결을 모두 점유한 상태에서 목록/집계 오류 응답과 반환 후 10만 건 조회 복구를 검증합니다. 테스트 주입 대기는 350ms이며 실제 기본값은 별도 검사합니다. SQL별 3초/요청 전체 지연과 혼동하지 않습니다. [보완 근거와 제한](QUALITY_GATES.md)을 참고합니다.

Mock의 제한 행 잠금·상태/속성 갱신·변경 전후 이력 INSERT는 PostgreSQL CTE를 **EntityManager native query**로 실행합니다. 순수 JPQL·dirty checking으로 일괄 변환하지 않았습니다. 제한 행을 한 SQL에서 변경하고 이력과 원자적으로 묶는 기존 동작을 유지하기 위함입니다.

`JpaTransactionManager`가 네 유형을 한 트랜잭션으로 묶습니다. 배치 Repository는 기존 트랜잭션 참여가 필수입니다. bulk DML 전에 flush, 이후 clear를 적용해 관리 객체와 DB 상태가 어긋나는 상황을 피합니다. 커밋 뒤에만 커서·변경 Counter가 전진합니다. 실패 시 리소스·이력 모두 롤백됩니다.

이력의 bounded 삭제도 JPA native query로 처리합니다. JPA Entity를 2만 개 로딩해 하나씩 지우지 않습니다. seed·대표 SQL 분석 스크립트는 서비스 외부의 독립 도구로 유지합니다.

## 검증 근거

`JpaQueryIntegrationTest`는 현재 실제 PostgreSQL 17의 15만 Pod에서 다음을 확인합니다. 2026-10-17에 seed·기대 건수를 확장했으며 아래 과거 JPA 전환/성능 결과는 당시 10만 건 기록으로 유지합니다.

| 검사 | 기대 결과 |
|---|---|
| Pod 목록 | SQL 1회, 최대 101개 반환, Entity load/fetch·collection fetch 0 |
| 전체 유형에서 `pod` 검색 | SQL 4회, 합산 101개, Entity 그래프 로딩 0 |
| Cluster·Namespace 범위 | 존재/소속 검증 포함 SQL 3회, 앞쪽 Namespace 아래 100개 반환. 뒤쪽은 50개 |
| Pod 상세·4단계 경로 | SQL 1회, 트랜잭션 종료 후 DTO 사용, Entity 로딩 0 |
| 변경 이력 서비스 | 리소스 존재·Cluster 접근 확인 1회 + 이력 projection 1회, 최대 101개 내부 조회·100개 반환, Entity 그래프 로딩 0 |
| Entity·트랜잭션 | 5개 Entity 매핑, JpaTransactionManager, Flyway V1~V6 매핑 검증 |
| 관계 정책 | 부모 LAZY 및 collection fetch pagination 차단 |
| 실제 생성 SQL 실행 계획 | 선택적 이름 검색에서 `idx_pod_name_trgm` 사용 |
| timeout | PostgreSQL `pg_sleep(3)`이 JPA 1초 timeout hint로 취소 |

Hibernate Statistics와 StatementInspector는 이 통합 테스트에서만 켭니다. 운영 요청마다 SQL/통계를 로깅하지 않습니다. 인덱스 사용은 선택적인 특정 검색어에서 검증한 것이며 모든 검색어의 GIN 사용을 보장하지 않습니다. 기존 Mock 커밋·롤백·보관 정리와 API 계약 테스트도 전환 후 재검증합니다.  검증된 Hibernate 버전은 6.6.53.Final입니다.

이력 서비스는 조회 권한과 페이지 검증·503 복구를 별도 테스트하며 읽기 전용 트랜잭션을 유지합니다.

전체 테스트·Jenkins·브라우저·동일 조건 성능 결과는 [프로젝트 기준 문서](PROJECT_GUIDE.md)에 최신화합니다. 전환 전 성능과 비교할 때 캐시·JIT·DB 상태·동시 로컬 프로세스의 영향을 구분하며 관측 결과만으로 JDBC/JPA 전체의 성능 우열을 주장하지 않습니다.

## 참고

- [Spring Data JPA 3.5 DTO projection](https://docs.spring.io/spring-data/data-jpa/reference/3.5/repositories/projections.html)
- [Spring의 JPA 트랜잭션 경계](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)
- [Hibernate HQL constructor projection·ILIKE](https://docs.hibernate.org/orm/6.6/querylanguage/html_single/)
