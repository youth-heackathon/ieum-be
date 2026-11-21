# 이음수첩 백엔드

Java 17 · Spring Boot 4.2 · Postgres · Flyway · Spring Session JDBC · Railway 배포

- 기획·요구사항: [`docs/`](docs)
- API 문서: 서버의 `/swagger-ui.html`, 또는 [`docs/openapi.json`](docs/openapi.json) (`npx openapi-typescript docs/openapi.json -o src/api.d.ts`)

## 로컬

```bash
./gradlew bootTestRun   # Docker로 Postgres를 띄워서 실행 → http://localhost:8080/swagger-ui.html
./gradlew test          # 기능별 시나리오 테스트 (Docker 필요). docs/openapi.json도 다시 만든다
```

실제 DB에 붙이려면 `.env.example`을 `.env`로 복사해 값을 채우고 `./gradlew bootRun`.
API를 바꿨으면 `./gradlew test` 뒤 바뀐 `docs/openapi.json`을 같이 커밋한다 (CI가 확인).

## 배포 (Railway: 서버 + Postgres)

리전은 **Southeast Asia (Singapore)**. Railway에는 서울 리전이 없다. 서버와 DB를 같은 리전에 둬야 쿼리가 빠르다.

### 1. 프로젝트와 DB
1. New Project → **Deploy from GitHub repo** → 이 저장소
2. 같은 프로젝트에 **+ New → Database → PostgreSQL** (서비스 이름 `Postgres` 그대로)

### 2. 백엔드 서비스 설정 (Settings)
- **Root Directory: `ieum-be`** (저장소 최상위에 폴더가 하나 더 있다). Builder는 Dockerfile 자동 감지
- **Region: Southeast Asia**. Postgres 서비스도 같은 리전으로
- **Healthcheck Path: `/health`**. 새 버전이 뜬 뒤에 트래픽이 넘어간다. 세션은 DB에 있어 재배포해도 로그인·체험이 유지된다
- Networking → **Generate Domain**

### 3. 백엔드 Variables
`${{Postgres.…}}`는 Railway가 Postgres 서비스 값으로 바꿔 넣는다 (내부망 연결).

```
DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
IP_HASH_SALT=<openssl rand -hex 32 결과>
CORS_ALLOWED_ORIGINS=https://<vercel 프론트 주소>
```

- `PORT`는 Railway가 넣어 준다. 스키마는 첫 실행 때 Flyway가 만든다
- `IP_HASH_SALT`를 비우면 재시작마다 IP별 횟수 제한이 초기화된다
- `ANTHROPIC_API_KEY` 등은 4·5단계부터

### 4. Vercel (프론트): 같은 출처로 프록시
iOS Safari는 다른 도메인의 쿠키를 막는다. 그래서 `/api`를 Vercel이 대신 넘겨주게 한다. 프론트 저장소 `vercel.json`:

```json
{ "rewrites": [{ "source": "/api/:path*", "destination": "https://<railway 백엔드 도메인>/api/:path*" }] }
```

프론트는 `fetch("/api/...", { credentials: "include" })`로 부른다.

### 5. 배포 후 확인
- `https://<백엔드>/health` → `ok`
- `https://<백엔드>/swagger-ui.html` 열림
- 프론트 주소에서 체험을 시작하고 새로고침해도 체험이 이어지면 쿠키·프록시 설정은 끝

### 데이터 정리 (데모)
체험 데이터 24시간 삭제와 요청자 번호 30일 파기는 자동으로 돌지 않는다. Railway Postgres에는 pg_cron이 없다.
필요하면 Railway의 Postgres → Data(Query)에서 직접 실행한다.

```sql
select ieum_cleanup();
```
