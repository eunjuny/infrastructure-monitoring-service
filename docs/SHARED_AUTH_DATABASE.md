# 인증 환경의 단일 PostgreSQL 구성

적용: 2026-10-18 · 선택형 인증 환경(5176)만 대상

## 이유와 선택

기존에는 서비스와 Keycloak에 PostgreSQL 컨테이너를 하나씩 실행했습니다. 이 과제 규모에서는 자원/관리 부담을 줄이기 위해 **한 PostgreSQL 서버에 두 데이터베이스와 두 전용 계정**을 둡니다. 새 OSS/유료 서비스는 추가하지 않으며 개발(5174)·runtime(5175)·다른 프로젝트 DB는 합치지 않습니다.

| 구성 | 용도와 권한 |
|---|---|
| `db` 컨테이너 | PostgreSQL 17 서버 한 개, 호스트 DB 포트 비공개 |
| `monitoring` DB / 동일 이름 계정 | 리소스·이력·Flyway, 해당 DB만 접속 |
| `identity` DB / 동일 이름 계정 | Keycloak 계정·역할·키·세션, 해당 DB만 접속 |
| `infrastructure_admin` | 초기화/복구 전용 관리 계정, 앱·Keycloak에서 사용하지 않음 |

두 앱 계정에 SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS를 주지 않습니다. PUBLIC/상대 계정의 DB 권한을 회수하고, 유지관리 DB(`postgres`·`template1`)도 PUBLIC 접속을 차단합니다. 각 DB 소유자는 자신의 스키마를 관리하지만 상대 DB에 접속할 수 없습니다. 기존 앱/인증 비밀번호는 보존하고 서로 다른 관리 계정 비밀값만 기존 credentials에 추가합니다.

비밀 파일은 Git 제외 `.local/auth` 0700·파일 0600입니다. 초기화 SQL은 psql `\getenv`로 비밀번호를 읽으며 명령 인자/로그로 출력하지 않습니다. Docker/호스트 관리자의 접근 경계는 여전히 존재합니다.

상시 컨테이너는 프론트·백엔드·DB·Keycloak·Prometheus·Alertmanager·내부 수신기 **7개**입니다. `db-init`·`auth-import`·`seed`는 초기화 후 종료하는 작업입니다. 공유 서버의 재시작·자원 경합·장애는 두 서비스에 함께 영향을 줍니다.

## 새 설치와 재실행

```bash
python3 scripts/auth-local.py
python3 scripts/test-auth.py
```

DB 준비 → `db-init`의 두 DB/역할 초기화 → Keycloak·백엔드 → seed → 프론트 순으로 시작합니다. 초기화는 기존 테이블을 지우지 않습니다. 새 `auth-shared-data` 볼륨을 사용하고 기존 `auth-data`를 자동 재사용하지 않습니다. 앱 계정이 bootstrap 슈퍼유저였던 옛 클러스터를 단순 강등하지 않고, 관리 계정이 생성한 새 클러스터로 복원해 시스템 카탈로그 소유권도 분리합니다. CI도 같은 단일 서버/두 DB 구조이며 해당 서버 저장소만 tmpfs입니다.

## 기존 두 서버 설치의 일회성 이행

기존 `keycloak-db` 컨테이너가 있으면 일반 시작은 안내 후 중단합니다. credentials/볼륨 삭제로 해결하지 않습니다.

```bash
python3 scripts/migrate-auth-database.py --apply
python3 scripts/migrate-auth-database.py --verify
python3 scripts/test-auth.py
# 실제 로그인·권한까지 통과한 뒤에만:
python3 scripts/migrate-auth-database.py --retire-legacy
```

1. 정확한 로컬 Compose 대상과 원본 앱 볼륨을 검사하고 파일 잠금을 획득합니다. 대상 DB/공유 볼륨이 이미 있으면 덮어쓰지 않습니다.
2. 백엔드·Keycloak을 정지해 쓰기를 멈추고 두 DB의 모든 public 테이블 행 수/내용 지문을 메모리에 기록합니다.
3. 두 DB를 custom pg_dump로 `.local/auth/database-backups/<실행 시각>/`에 저장합니다. 디렉터리 0700·dump 0600입니다. dump에는 비밀번호 해시·서명 키·세션 등 민감 데이터가 있으므로 공개/첨부하지 않습니다.
4. 새 PostgreSQL에 두 DB/역할을 초기화하고 각각의 소유자로 dump를 복원합니다. 원본 볼륨 두 개는 삭제하지 않습니다.
5. 모든 public 테이블 내용이 이행 전과 동일한지 비교한 뒤 Keycloak을 `db:5432/identity`로 연결하고 서비스를 재시작합니다. ID/sub·암호·그룹·클라이언트·서명 키·리소스/이력을 새로 생성하지 않습니다.
6. 실제 로그인·admin/user1/user2 범위·교차 차단을 검증한 뒤 이전 identity 컨테이너만 제거합니다. 원본 identity/app 볼륨과 백업은 복구용으로 남습니다. 이들은 디스크만 차지하고 실행 프로세스의 메모리는 쓰지 않습니다.

작업 중 잠시 로그인이 중단됩니다. 세션도 복원하지만 브라우저의 갱신 실패 시 재로그인이 필요할 수 있습니다. 비밀값 없는 단계 상태는 `.local/auth/database-migration.json`에 남깁니다.

### 실패와 복구

이미 복원이 끝났고 원래 public 테이블 지문이 private 상태 파일에 남아 있는 경우에만 `python3 scripts/migrate-auth-database.py --resume`으로 내용을 재검증해 전환을 이어갈 수 있습니다. 이 명령은 DB를 재복원하거나 덮어쓰지 않으며, 비교가 다르면 전환을 거부합니다. 비교는 PostgreSQL Parallel Append의 반환 순서에 영향받지 않도록 테이블명 기준으로 정렬합니다.

실패하면 대상 DB를 자동 삭제/덮어쓰기하지 않습니다. 부분 복원에 `--apply`를 반복하지 말고 상태와 원인을 확인합니다. 이전 identity 컨테이너 제거 전에는 다음으로 원본 앱 볼륨·identity 서버를 다시 연결할 수 있습니다.

```bash
python3 scripts/migrate-auth-database.py --rollback
```

임시 Compose override로 원래 저장소에 연결하며 초기화/seed를 실행하지 않습니다. 설정 소스를 Git으로 되돌리지는 않습니다. 이행 후 새 데이터까지 동기화하는 절차가 아니므로 사용 재개 뒤 무분별한 rollback은 하지 않습니다. `--retire-legacy` 뒤에는 이전 컨테이너가 없어 자동 rollback 대신 보존된 볼륨/백업으로 별도 복구가 필요합니다. 원본/백업 자동 삭제는 하지 않습니다.

## 검증

```bash
bash scripts/check.sh fast
python3 scripts/test-auth-database.py
python3 scripts/migrate-auth-database.py --verify
python3 scripts/test-auth.py
```

실제 PostgreSQL 검사는 별도 이름·tmpfs 서버에서 새 초기화, 재실행의 데이터 보존, 두 계정의 TCP 비밀번호 인증, 상대/유지관리 DB 접속 거부, DB/역할 생성·슈퍼유저 승격 거부를 검증한 뒤 그 서버만 제거합니다. 실제 이행에서는 전체 테이블 일치와 브라우저 로그인·권한을 확인합니다. 새 전체 Jenkins/성능 측정과는 별개입니다.

2026-10-18 실제 이행에서 서비스 public 테이블 6개·Keycloak 101개의 내용 일치를 확인했습니다. 기존 15만 Pod와 사용자 ID/암호/권한/서명 키를 보존했고, 로컬 실제 Keycloak 10개와 새 단일 서버/tmpfs 인증 환경의 실제 Keycloak 10개를 각각 통과했습니다(skip/unexpected/flaky 0). 새 환경은 기존 앱 이미지를 재사용해 DB 초기화 경로를 검증했으며 전체 Jenkins/새 이미지 빌드로 설명하지 않습니다. 이전 identity 컨테이너는 검증 후 제거했고 원본 볼륨 두 개와 private dump는 보관합니다. [실행 요약](verification/2026-10-18-shared-auth-database.json), WH-037을 참고합니다.
