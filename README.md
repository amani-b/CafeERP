# CafeERP

CafeERP is a Spring Boot web application for managing cafe operations — categories, menu items, and customer orders — with role-based access for staff and administrators.

## Features

- Role-based authentication (ADMIN / STAFF)
  - ADMIN: full CRUD on categories and menu items
  - STAFF: can view and create orders only
- Manage product categories
- Manage menu items (availability toggle, pricing)
- Create and view orders (itemised with per-item name and quantity)
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

All endpoints except `/login`, `/login-error`, `/css/**`, and `/actuator/health` require authentication. Category and menu management is restricted to `ADMIN` role; orders can be viewed/created by any authenticated user.

### Seeded Admin User

The migration `V2__add_users.sql` seeds one admin user. See that file for the hashed credentials.

**⚠️ Security:** The migration includes a placeholder BCrypt hash. You **must** change this password immediately after first login by updating the hash in the database or by writing a new Flyway migration. Do not rely on the seeded value in production.

## Running the Application

1. Clone the repository.
2. Ensure PostgreSQL is running and the database exists (e.g. `cafe_erp`).
3. Set environment variables if needed (see [Configuration](#configuration)).
4. Run:

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080/`. If no admin user has been modified yet, use the credentials from `V2__add_users.sql` to log in on the first run, then change the password immediately.

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

Test groups:

- `OrderServiceTest` — unit tests for order creation logic (item availability filtering, quantity validation, total calculation)
- `CategoryServiceTest` / `MenuServiceTest` — unit tests for CRUD services
- `CategoryControllerTest` — `@WebMvcTest` slice tests for 404 handling and role-based access control

## Project Structure

```
src/main/java/com/cafeerp/
├── category/          # Category CRUD controller, service, repository, entity
├── common/            # Security config, global exception handler, auth controller
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

## Notes

This project is a basic ERP-style demo for cafe operations and can be extended with inventory, payments, reporting, and other features.