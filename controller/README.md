# TimeLord Controller

Central controller service (Java 21 / Spring Boot / PostgreSQL). Accepts agent registration,
heartbeats, and events over plain HTTP (Phase 1 — see the security warning in the root
`README.md`), and serves a Thymeleaf admin dashboard.

## Running

The normal path is via the root `compose.yaml` (`docker compose up --build` from the repo
root) — see the root `README.md`. To run just the controller against a local Postgres:

```console
$ docker run -d --name timelord-postgres -p 5432:5432 \
    -e POSTGRES_DB=timelord -e POSTGRES_USER=timelord -e POSTGRES_PASSWORD=timelord postgres:17
$ mvn spring-boot:run
```

Config is environment-driven (`application.yml`); the important ones:

```text
SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD
TIMELORD_CONTROLLER_NAME
TIMELORD_PUBLIC_URL           # must be reachable by agents — never the Docker-internal hostname
TIMELORD_DISCOVERY_ENABLED / _PORT / _PRIORITY
TIMELORD_ANNOUNCEMENT_ENABLED / _INTERVAL
TIMELORD_HEARTBEAT_INTERVAL_SECONDS
TIMELORD_HEARTBEAT_RETENTION_DAYS
```

## Layout

```text
src/main/java/com/timelord/controller/
├── config/          @ConfigurationProperties records (controller/discovery/device/heartbeat)
├── agent/            Agent-facing DTOs + AgentController (register/heartbeat/events)
├── device/            Device entity/repository/service, admin DeviceController, online/offline scheduler
├── event/              DeviceEvent/DeviceHeartbeat entities, EventService (idempotent submission),
│                        admin EventController, heartbeat retention scheduler
├── discovery/            UDP listener + optional periodic announcer, controller identity persistence
├── dashboard/              Thymeleaf DashboardController
├── health/                  Custom actuator health indicator (discovery listener)
└── common/                    ApiError/ApiException/GlobalExceptionHandler, correlation IDs,
                                AgentRequestAuthenticator (no-op — the Phase-2 auth seam)
```

Flyway migrations: `src/main/resources/db/migration/`. Templates: `src/main/resources/templates/`.

## API

`GET /api/v1/system/info` · admin: `/api/v1/devices`, `/api/v1/devices/{id}`,
`/api/v1/devices/{id}/events`, `/api/v1/events`, `/api/v1/events/{id}` · agent:
`/api/v1/agents/register`, `/api/v1/agents/{id}/heartbeat`, `/api/v1/agents/{id}/events`.

Errors are a consistent JSON shape (`timestamp/status/code/message/details/correlationId`);
device/event lookups return `404` with code `DEVICE_NOT_FOUND` / `EVENT_NOT_FOUND`.

## Testing

```console
$ mvn test
```

Integration tests (`src/test/java/.../*IntegrationTest.java`) use Testcontainers Postgres via
the **singleton container pattern** — one Postgres for the whole test JVM, started once in a
static initializer in `support/AbstractIntegrationTest`, not via `@Container`/`@Testcontainers`
(that combination, with the field declared in a shared abstract base class, ties container
start/stop to whichever subclass runs, which stops a still-referenced container out from under
another test class's cached Spring context — surfaces as `@Scheduled` background tasks and even
live HTTP calls failing with connection-refused against a dead container index). See the doc
comment on `AbstractIntegrationTest` if you're adding a new integration test class.

Running via Docker (no local Maven/JDK 21 needed), with the host Docker socket mounted so
Testcontainers can start Postgres as a sibling container:

```console
$ docker run --rm -v "$PWD":/build -w /build -v /var/run/docker.sock:/var/run/docker.sock \
    -v timelord-maven-repo:/root/.m2 -e TESTCONTAINERS_RYUK_DISABLED=true \
    maven:3.9-eclipse-temurin-21 mvn -B test
```

## Web UI

`/` (dashboard), `/devices` (filterable list), `/devices/{id}` (detail + recent events),
`/events` (filterable, paginated). Bootstrap is bundled via WebJars — no external CDN.
