# CafeERP

CafeERP is a Spring Boot web application for managing cafe operations — categories, menu items, and customer orders — with role-based access for staff and administrators.

## Features

- Role-based authentication (ADMIN / STAFF)
  - ADMIN: full CRUD on categories, menu items, and inventory
  - STAFF: can view and create orders only
- Manage product categories
- Manage menu items (availability toggle, pricing)
- Create and view orders (itemised with per-item name and quantity)
- Inventory tracking — per-menu-item opt-in model: when `trackInventory` is enabled,
  stock is atomically decremented on order creation and blocked when insufficient;
  untracked items are completely unaffected by inventory logic
- Low-stock indicators highlight tracked items whose quantity is at or below their
  configurable threshold
- Database schema managed via Flyway migrations
- Actuator health endpoint (`/actuator/health`) for monitoring
- Dev / Prod profile support

## Tech Stack

- Java 21
- Spring Boot 3.3
- Spring MVC (Thymeleaf views)
- Spring Data JPA
- Spring Security
- PostgreSQL
- Flyway (database migrations)
- Maven

## Prerequisites

- Java 21 (see `pom.xml` — `<java.version>21</java.version>`)
- Maven 3.8+
- PostgreSQL running locally (for dev) or reachable (for prod)

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

## Configuration

### Profiles

The application uses Spring profiles to switch between environments. The default profile is `dev`.

| Profile | Config File | Behaviour |
|---|---|---|
| `dev` (default) | `application-dev.properties` | Sensible defaults — DB credentials fall back to local dev values. Thymeleaf caching off. DEBUG logging for `com.cafeerp`. |
| `prod` | `application-prod.properties` | No fallback values — every env var **must** be set or the application fails fast. Thymeleaf caching on. INFO logging. SQL logging off. |

Override the active profile via:

```bash
# Environment variable
export SPRING_PROFILES_ACTIVE=prod
mvn spring-boot:run

# Or command-line argument
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

### Database Connection

Configure via environment variables:

| Variable | Dev Default | Prod |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/cafe_erp` | Required |
| `DB_USERNAME` | `postgres` | Required |
| `DB_PASSWORD` | (empty) | Required |

Example:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/cafe_erp
export DB_USERNAME=postgres
export DB_PASSWORD=my_secret_password
```

## Authentication

All endpoints except `/login`, `/login-error`, `/css/**`, and `/actuator/health` require authentication. Category, menu, and inventory management (`/categories/**`, `/menu/**`, `/inventory/**`) is restricted to the `ADMIN` role; orders can be viewed/created by any authenticated user.

Any authenticated user can change their own password at `/account/password`.

### Seeded Admin User

The migration `V2__add_users.sql` seeds one admin user. See that file for the hashed credentials.

Migration `V4__add_must_change_password.sql` sets a `mustChangePassword` flag on that seeded admin, so on first login they are redirected to `/account/password` before any other page is accessible. Once the password is changed, the flag is cleared and normal access resumes.

**⚠️ Security:** The migration includes a placeholder BCrypt hash. You **must** change this password immediately after first login by updating the hash in the database or by writing a new Flyway migration. Do not rely on the seeded value in production.

## Running the Application

1. Clone the repository.
2. Ensure PostgreSQL is running and the database exists (e.g. `cafe_erp`).
3. Set environment variables if needed (see [Configuration](#configuration)).
4. Run:

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080/`. If no admin user has been modified yet, use the credentials from `V2__add_users.sql` to log in on the first run — you will be forced to change the password before accessing any other page.

## Deployment

This application is deployed via Docker on [Render](https://render.com/) using the **Docker runtime** (Render does not support Java natively, so the [Dockerfile](Dockerfile) at the repository root handles the build and runtime).

The database is hosted on [Neon](https://neon.tech/) rather than Render's own managed Postgres. Render's free-tier Postgres expires after approximately 30 days; Neon's free tier is permanent, making it a better fit for a demo / low-budget deployment.

### Required Environment Variables

When deploying on Render, set the following environment variables in the Render dashboard:

| Variable | Value |
|---|---|
| `DB_URL` | Your Neon connection string (e.g. `jdbc:postgresql://...`) |
| `DB_USERNAME` | Your Neon database user |
| `DB_PASSWORD` | Your Neon database password |
| `SPRING_PROFILES_ACTIVE` | `prod` |

The application listens on the port provided by Render via the `PORT` environment variable (defaults to `8080`).

### Health Check

Render uses the `/actuator/health` endpoint to determine when the application is ready. This endpoint is publicly accessible and returns a JSON status — see [Health Check](#health-check) below for details.

### Free-Tier Cold Starts

On Render's free tier, the web service spins down after 15 minutes of inactivity. The next request triggers a cold start that takes approximately 30–60 seconds before the application responds. This is normal behaviour for the free plan.

## Health Check

Spring Boot Actuator is configured to expose **only** the health endpoint:

```
GET /actuator/health
```

This endpoint is publicly accessible (no authentication required) so monitoring tools can reach it. It returns:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": { "database": "PostgreSQL", "validationQuery": "isValid()" }
    }
  }
}
```

- If the database is unreachable, `status` becomes `"DOWN"` (provided by Spring Boot's built-in `DataSourceHealthIndicator`).
- No other Actuator endpoints (`/env`, `/beans`, `/metrics`, etc.) are exposed.

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

## Project Structure

```
src/main/java/com/cafeerp/
├── category/          # Category CRUD controller, service, repository, entity
├── common/            # Security config, global exception handler, auth controller
├── inventory/         # Inventory tracking controller, service, repository, entity
├── menu/              # Menu item CRUD controller, service, repository, entity
├── order/             # Order creation/listing controller, service, repository, entities
└── user/              # User entity, repository, custom UserDetailsService

src/main/resources/
├── db/migration/      # Flyway migration scripts (V1, V2, ...)
├── templates/         # Thymeleaf HTML templates
├── application.properties         # Shared config (datasource driver, JPA, actuator)
├── application-dev.properties     # Dev profile overrides
└── application-prod.properties    # Prod profile overrides
```

## Operations

For backup and restore procedures, first-deploy checklist, and incident response, see [RUNBOOK.md](RUNBOOK.md).

## Notes

This project is a basic ERP-style demo for cafe operations and can be extended with payments, reporting, and other features.