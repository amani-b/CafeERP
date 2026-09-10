# CafeERP

CafeERP is a Spring Boot web application for managing cafe operations — categories, menu items,
customer orders, kitchen queue, inventory tracking, sales reports, and an AI assistant —
with role-based access for staff and administrators.

**Live production:** https://cafeerp.onrender.com
**Live demo (public sandbox):** https://cafeerp-demo.onrender.com

> The demo is a throwaway sandbox: every visitor gets their own private, session-scoped copy of a
> fictional cafe dataset. Nothing you do there touches production data, and your sandbox resets
> after ~25 minutes of inactivity. Log in with `demo-admin`, `demo-staff` or `demo-kitchen`
> (password: `demo1234`). See [docs/DEMO_DEPLOYMENT.md](docs/DEMO_DEPLOYMENT.md) for how it works.

## Features

- Role-based authentication (ADMIN / STAFF / KITCHEN, plus SUPER_ADMIN root tier)
  - ADMIN: manage categories, menu items, inventory, reports, users and settings (as granted)
  - STAFF: view and create orders
  - KITCHEN: work through the live kitchen queue
- Manage product categories
- Manage menu items (availability toggle, pricing)
- Create and view orders (itemised with per-item name and quantity)
- Kitchen queue with order status flow (PENDING → PREPARING → READY → COMPLETED)
- Inventory tracking — per-menu-item opt-in model: when `trackInventory` is enabled,
  stock is atomically decremented on order creation and blocked when insufficient;
  untracked items are completely unaffected by inventory logic
- Low-stock indicators highlight tracked items whose quantity is at or below their
  configurable threshold
- Sales reports (today / week / month / custom range, top sellers)
- AI assistant with live-data-awareness (see below)
- Trilingual UX: English, Amharic (Ge'ez script) and Amharic written in Latin letters
- Database schema managed via Flyway migrations
- Actuator health endpoint (`/actuator/health`) for monitoring
- Dev / Prod / Demo profile support

## AI assistant (capability overview)

The built-in assistant answers questions from **live cafe data** — menu, orders, inventory,
kitchen queue, sales reports — instead of answering from memory. It can:

- look up order status, recent orders, menu items and prices, stock levels, sales totals,
  best-sellers and the kitchen queue;
- **take real actions** (create orders, change order statuses, update stock and menu items)
  through an explicit **Confirm / Cancel** step in the chat UI — nothing mutates data silently;
- run in a **user-controlled autonomy mode per session** (`Confirm actions` by default, with an
  opt-in `Auto low-risk` mode for routine stock housekeeping);
- converse in **English, Amharic (Ge'ez script) and transliterated Amharic**, always replying
  in the script the user wrote in, including voice input/output in the browser;
- stream its progress ("Looking up order #482…") while a turn is still running.

Access is permission-scoped: the assistant can only surface data and actions the signed-in
user is themselves allowed to see and do. When the AI backend is unreachable it degrades to a
deterministic local answerer over the same live data rather than showing an error.

## Tech stack

- Java 17+ (CI and Docker use 21)
- Spring Boot 3.3
- Spring MVC (Thymeleaf views)
- Spring Data JPA
- Spring Security
- PostgreSQL (dev/prod) / throwaway in-memory store (demo profile)
- Flyway (database migrations)
- Maven

## Prerequisites

- Java 17+ and Maven 3.8+
- PostgreSQL running locally — **only for `dev`/`prod`**; the `demo` profile needs no database at all

## Configuration

### Profiles

| Profile | Config File | Behaviour |
|---|---|---|
| `dev` (default) | `application-dev.properties` | Sensible defaults — DB credentials fall back to local dev values. Thymeleaf caching off. DEBUG logging for `com.cafeerp`. |
| `prod` | `application-prod.properties` | No fallback values — every env var **must** be set or the application fails fast. Thymeleaf caching on. INFO logging. SQL logging off. |
| `demo` | `application-demo.properties` | Public sandbox — session-scoped in-memory data seeded from a fictional fixture, **no database required and no production credentials read** (it cannot see `DB_URL` even if one is set). Session timeout 25 min. INFO logging. |

Override the active profile via:

```bash
# Environment variable
export SPRING_PROFILES_ACTIVE=demo
mvn spring-boot:run

# Or command-line argument
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

### Environment variables

No secrets are ever committed to this repository — everything sensitive is injected via
environment variables at runtime:

| Variable | Dev Default | Prod | Demo |
|---|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/cafe_erp` | Required | Ignored (in-memory sandbox) |
| `DB_USERNAME` | `postgres` | Required | Ignored |
| `DB_PASSWORD` | (empty) | Required | Ignored |
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` | `demo` |
| AI provider keys | — | Set all three for full assistant quality | Optional (see below) |

The assistant calls the configured AI backend with a primary → secondary → tertiary failover
chain; each provider's key is read from its own environment variable
(see `application.properties` for the variable names). With **no keys set at all** the
assistant still works — it answers deterministically from live data — so the `demo` profile
is fully runnable with zero secrets, and the test suite runs hermetically the same way.

Example:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/cafe_erp
export DB_USERNAME=postgres
export DB_PASSWORD=my_secret_password
```

## Authentication

All endpoints except `/login`, `/login-error`, `/css/**`, `/js/**`, `/actuator/health` and
`/build-version` require authentication. Category, menu, inventory, report, user and settings
management is restricted to the admin tiers; orders can be viewed/created by any authenticated
user; the kitchen queue is restricted to kitchen/admin roles.

Any authenticated user can change their own password at `/account/password`.

### Seeded admin user (dev/prod only)

The migration `V2__add_users.sql` seeds one admin user. See that file for the hashed credentials.

Migration `V4__add_must_change_password.sql` sets a `mustChangePassword` flag on that seeded admin, so on first login they are redirected to `/account/password` before any other page is accessible. Once the password is changed, the flag is cleared and normal access resumes.

**⚠️ Security:** The migration includes a placeholder BCrypt hash. You **must** change this password immediately after first login. Do not rely on the seeded value in production.

### Demo accounts (demo profile only)

The demo seeds three fictional accounts, all with password `demo1234`:

| Username | Role | Shows off |
|---|---|---|
| `demo-admin` | ADMIN (full permissions) | Menu, inventory, orders, kitchen, reports, AI actions |
| `demo-staff` | STAFF | Placing and tracking orders |
| `demo-kitchen` | KITCHEN | Kitchen queue |

User management, business settings and assistant audit viewers are disabled in the demo —
they are not meaningful to a portfolio visitor and every sandbox is throwaway anyway.

## Running the app

### Option A — demo mode (no database, no secrets)

```bash
export SPRING_PROFILES_ACTIVE=demo
mvn spring-boot:run
```

Open `http://localhost:8080/`, log in as `demo-admin` / `demo1234`, and explore. Each browser
session gets its own sandbox; the assistant works out of the box (deterministic answers over
the demo data), or set the AI provider env vars to also exercise live AI turns (capped at 15
assistant messages per visitor session).

### Option B — local dev (PostgreSQL)

1. Ensure PostgreSQL is running and the database exists (e.g. `cafe_erp`).
2. Set environment variables if needed (see [Configuration](#configuration)).
3. Run `mvn spring-boot:run` (default `dev` profile).
4. Open `http://localhost:8080/`. If no admin user has been modified yet, use the credentials
   from `V2__add_users.sql` to log in on the first run — you will be forced to change the
   password before accessing any other page.

## Deployment

This application is deployed via Docker on [Render](https://render.com/) using the **Docker runtime**
(Render does not support Java natively, so the [Dockerfile](Dockerfile) at the repository root
handles the build and runtime).

Production and demo run as **two separate services from the same image**:

- **Production** (`SPRING_PROFILES_ACTIVE=prod`) with the real database credentials.
- **Demo** (`SPRING_PROFILES_ACTIVE=demo`) with **no database credentials at all** — the demo
  profile defines its own throwaway datasource and never reads `DB_URL`, so it is physically
  impossible for the demo service to reach the production database, even by misconfiguration.

See [docs/DEMO_DEPLOYMENT.md](docs/DEMO_DEPLOYMENT.md) for the demo service setup.

### Required env variables (production)

| Variable | Value |
|---|---|
| `DB_URL` | JDBC connection string |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `SPRING_PROFILES_ACTIVE` | `prod` |

The application listens on the port provided by Render via the `PORT` environment variable (defaults to `8080`).

### Health check

Render uses the `/actuator/health` endpoint to determine when the application is ready.

## Database Schema

Schema is managed by **Flyway** versioned migrations in:

```
src/main/resources/db/migration/
```

| Migration | Description |
|---|---|
| `V1__baseline_schema.sql` | Core tables: `category`, `menu_item`, `cafe_order`, `cafe_order_item` |
| `V2__add_users.sql` | Adds `cafe_user` table and seeds the initial admin user |
| `V3__add_inventory.sql` | Adds `inventory` table (per-item stock with opt-in tracking) and initialises a row for every existing menu item |

**Important:** Hibernate `ddl-auto` is set to `validate`, not `update`. Never enable DDL generation in production — all schema changes must go through new Flyway migrations.

## Tests

```bash
mvn test           # run all tests
mvn -q clean verify  # clean build with tests (no verbose output)
```

A [GitHub Actions CI workflow](.github/workflows/ci.yml) runs `mvn -q clean verify` on every push and pull request, gating all changes on a successful build and passing tests.

Test groups:

- `OrderServiceTest` — unit tests for order creation logic (item availability filtering, quantity validation, total calculation, stock-check wiring)
- `InventoryServiceTest` — unit tests for inventory CRUD and low-stock detection boundary conditions
- `CategoryServiceTest` / `MenuServiceTest` — unit tests for CRUD services
- `CategoryControllerTest` / `InventoryControllerTest` — `@WebMvcTest` slice tests for 404 handling and role-based access control
- `DemoSeedDataTest` — unit tests for the demo fixture (shape, trilingual content, roles, quota)
- `DemoProfileTest` — full-stack demo-profile tests (login, flows with no API keys, per-session sandbox isolation, disabled admin screens, assistant cap)

## Project structure

```
src/main/java/com/cafeerp/
├── assistant/       # AI assistant: chat service, tool registry, voice support, history
├── category/        # Category CRUD controller, service, repository, entity
├── common/          # Security config, global exception handler, auth controller
├── demo/            # Demo profile only: session-scoped in-memory repos, seed, guards
├── inventory/       # Inventory tracking controller, service, repository, entity
├── menu/            # Menu item CRUD controller, service, repository, entity
├── order/           # Order creation/listing controller, service, repository, entities
├── report/          # Sales report controller and service
├── settings/        # Business settings (e.g. display timezone)
└── user/            # User entity, repository, custom UserDetailsService

src/main/resources/
├── db/migration/      # Flyway migration scripts (V1, V2, ...)
├── templates/         # Thymeleaf HTML templates
├── application.properties         # Shared config (datasource driver, JPA, actuator)
├── application-dev.properties     # Dev profile overrides
├── application-prod.properties    # Prod profile overrides
└── application-demo.properties    # Demo profile (throwaway datasource, 25 min sessions)
```

## Operations

For backup and restore procedures, first-deploy checklist, and incident response, see [RUNBOOK.md](RUNBOOK.md).
For the public demo service setup, see [docs/DEMO_DEPLOYMENT.md](docs/DEMO_DEPLOYMENT.md).

## Timestamp & Timezone Contract

- All `LocalDateTime` columns are persisted in **UTC** (`LocalDateTime.now(ZoneOffset.UTC)` at write sites).
- All displayed timestamps are rendered in the business's configured IANA timezone (12-hour AM/PM), sourced from the admin **Settings** page (default `Africa/Addis_Ababa`).
- Rendering is centralized in `TimeDisplayService` (`timeFmt` in every Thymeleaf template).
- **Known caveat:** rows written before the UTC-at-source change were stored in server-local time and have **not** been migrated; they will render shifted until a one-off data migration is performed (deferred by decision).

## License

MIT — see [LICENSE](LICENSE).
