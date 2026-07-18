# Cafe ERP: Operations Runbook

**Repository:** https://github.com/amani-b/CafeERP  
**Stack:** Spring Boot 3.3.5 / Java 21 / Maven / PostgreSQL / Flyway  
**Deployment target:** PaaS (Render)

---

## Table of Contents

1. [Neon Built-In Backup & Instant Restore](#1-neon-built-in-backup--instant-restore)
2. [Manual Ad-Hoc Backup (pg_dump)](#2-manual-ad-hoc-backup-pgdump)
3. [First-Deploy Checklist](#3-first-deploy-checklist)
4. [Incident Response — App Is Down](#4-incident-response--app-is-down)
5. [Rolling Back a Bad Deploy](#5-rolling-back-a-bad-deploy)

---

## 1. Built-in backup & instant restore in Neon

The application's database is hosted on **Neon** (separate from the web
service, which runs on Render). Neon provides point-in-time restore from the
Neon Console. This codebase does **not** implement any backup logic.

### Free-tier limitation -- 6-hour restore window

> **⚠️ On Neon's free tier, point-in-time history is retained for only
> **6 hours**. Any data older than 6 hours **cannot** be recovered via
> Neon's built-in restore. If you need a longer retention window, either
> upgrade to a Neon paid plan or supplement with manual `pg_dump` backups
> (see §2 below).**

### Restore procedure

1. **Log into the [Neon Console](https://console.neon.tech)** and select the
   project associated with this application.
2. **Go to "Backup & Restore"** in the left sidebar.
3. **Choose the restore point:**
   - **Point-in-time:** pick a specific timestamp or LSN (Log Sequence Number)
     within the 6-hour retention window.
   - **Branch restore:** Neon can also restore to a named branch (e.g. the
     project's root/production branch) at the chosen point.
4. **Apply the restore.** Neon restores the selected branch to the chosen point
   in time.
5. **Update the app's `DB_URL` env var** in Render's dashboard if the restore
   creates or changes the connection string.
6. **Restart the application** on Render so it reconnects to the restored
   database.
7. **Verify:** hit `/actuator/health` and confirm `{"status":"UP"}` with
   `db` details shown (requires ADMIN role).

---

## 2. Manual ad-hoc backup (pg_dump)

Because Neon's free tier limits you to a 6-hour restore window, manual
`pg_dump` backups are the only way to recover data older than 6 hours.
Treat them as your safety net.

### When to run

- **Before every risky change** — for example, before a new Flyway migration
  runs against the production database. If the migration corrupts data and
  you discover it outside the 6-hour window, the manual dump is your only way
  back.
- **Periodically (practical cadence for a solo operator):** run a dump before
  each deploy to production. If deploys are infrequent, set a calendar reminder
  to take a dump every few weeks — even monthly is better than nothing.

### Where to store dump files

**Keep dump files outside of Render and Neon entirely** — for example on your
local machine, in a cloud storage bucket (S3, Backblaze B2, Google Drive), or
both. The point is that a problem with the hosting provider shouldn't also
destroy your only backup copy.

### Prerequisites

- `pg_dump` (comes with a PostgreSQL client installation; version 16+ recommended)
- Network access to the Neon database (you may need to allowlist your IP in
  the Neon Console under "Connection Details" > "Allowed IPs")

### Command

Run the following from your local machine. The command uses the same env var
names that the application uses at runtime (`DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`), so copy the values straight from Render's dashboard:

```bash
pg_dump \
  --dbname="$DB_URL" \
  --username="$DB_USERNAME" \
  --no-password \
  --format=custom \
  --file=cafeerp-$(date +%Y%m%d-%H%M%S).pgdump
```

**Explanation of flags:**

| Flag | Purpose |
|------|---------|
| `--dbname` | The `DB_URL` from the PaaS dashboard |
| `--username` | The `DB_USERNAME` from the PaaS dashboard |
| `--no-password` | Read password from `PGPASSWORD` env var or `.pgpass`, never prompt |
| `--format=custom` | Compressed, flexible format — restorable with `pg_restore` |
| `--file` | Output file with a timestamp so you know when it was taken |

**Set the password before running:**

```bash
# On Linux/macOS:
export PGPASSWORD="$DB_PASSWORD"

# On Windows (Command Prompt):
set PGPASSWORD=%DB_PASSWORD%
```

### Restoring from a manual backup

```bash
pg_restore \
  --dbname="<target-database-url>" \
  --username="<target-username>" \
  --no-password \
  --clean \
  --if-exists \
  cafeerp-20260718-091500.pgdump
```

The `--clean --if-exists` flags drop existing objects before recreating them,
effectively resetting the target database to the state captured in the dump.

---

## 3. First-deploy checklist

Use this checklist the very first time you deploy this application to a
production environment.

### Step 1: Provision infrastructure

- [ ] Create a PostgreSQL database instance on the platform.
- [ ] Note the connection string, username, and password. These become
      `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

### Step 2: Set environment variables

Set these in the PaaS dashboard for the application service:

| Variable | Example value | Notes |
|----------|---------------|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` | **Required.** Activates the `application-prod.properties` profile. |
| `DB_URL` | `jdbc:postgresql://host:5432/cafeerp?sslmode=require` | The platform-provided JDBC connection string. |
| `DB_USERNAME` | `cafeerp_user` | Database user. |
| `DB_PASSWORD` | *(secret)* | Database password. |

The application reads these at startup. They are **never** baked into the
Docker image.

### Step 3: Deploy the application

- [ ] Push the latest code (GitHub will trigger the CI workflow automatically).
- [ ] Trigger a deploy from the PaaS dashboard (or configure auto-deploy from
      the `master` branch).
- [ ] Watch the build logs — confirm the Docker image builds and the container
      starts without errors.

### Step 4: Verify Flyway migrations

Flyway runs automatically on startup because `spring.jpa.hibernate.ddl-auto=validate`
is set — Flyway applies the migration scripts in `src/main/resources/db/migration/`
and Spring Boot validates the JPA entities match the schema.

- [ ] Check the application logs for lines like:
      ```
      INFO  o.f.c.i.d.ConsoleMigrationExecutor  — Successfully applied 4 migrations
      ```
- [ ] If migrations fail, the application will not start. Check the log output
      for the specific error (e.g., a migration script has a syntax error for
      the target Postgres version).

### Step 5: Verify the health endpoint

- [ ] Hit `https://<your-app-url>/actuator/health` — expect:
      ```json
      {"status":"UP"}
      ```
- [ ] If the endpoint returns `{"status":"DOWN"}`, check the `details` field
      (visible to ADMIN role) or look at the application logs.

### Step 6: Login as admin and complete forced password change

- [ ] Navigate to `https://<your-app-url>/login`.
- [ ] Log in as **admin** with the seeded password **changeme123**.
- [ ] You will be immediately redirected to `/account/password` (the
      `PasswordChangeFilter` enforces this for users with
      `must_change_password = true`).
- [ ] Set a new strong password and submit.
- [ ] Confirm you are redirected to the home page and can navigate to
      `/categories`, `/menu`, `/orders`, `/inventory` without errors.

### Step 7: Smoke-test the main flows

- [ ] **Orders:** Create an order with one menu item. Verify the order appears
      in the list.
- [ ] **Inventory:** If the menu item has inventory tracking enabled, confirm
      stock is decremented after the order.
- [ ] **Categories / Menu:** Create, edit, and delete a test category and menu
      item.

---

## 4. Incident response for when app is down

If the application is returning 5xx errors or not responding at all, follow
this triage in order.

### Step 1: Check `/actuator/health`

```bash
curl -s https://<your-app-url>/actuator/health | jq .
```

**Possible responses:**

| Status | Meaning |
|--------|---------|
| `UP` | The app is healthy — the issue is likely at the network, CDN, or DNS level. |
| `DOWN` | The app is running but one or more dependencies (usually the database) are failing. |
| Connection refused / timeout | The app process may have crashed or the platform's load balancer isn't routing to it. |

### Step 2: Check application logs

Application logs are surfaced in the **PaaS dashboard** — look for a "Logs"
or "Log stream" tab under the application service.

**What to look for:**

| Pattern | Likely cause |
|---------|-------------|
| `BeanCreationException` / `UnsatisfiedDependencyException` | A configuration error — missing or invalid env var, or a bean failed to initialize. Check `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. |
| `HibernateException` / `PSQLException` | Database connectivity issue — the DB may be restarting, the connection pool exhausted, or credentials rotated. |
| `OutOfMemoryError` | The container needs more memory. Increase the plan on the PaaS dashboard. |
| `NullPointerException` | Unexpected code bug. Check recent deploy — may need a rollback (see §5). |
| `No such migration` / `FlywayException` | A migration script was removed or modified after already being applied. Never edit an applied migration. |

### Step 3: Is it the database?

If `/actuator/health` shows `DOWN` or logs mention the database:

1. **Check the database dashboard** on the PaaS — is the database in a
   "running" state? Is CPU/memory/disk maxed out?
2. **Verify the env vars** — has `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD`
   been rotated or changed?
3. **Restart the app** — sometimes the connection pool gets into a bad state
   and a restart clears it. Restart from the PaaS dashboard.
4. **Check if the database needs a restore** — if data corruption or an
   accidental migration caused the issue, see §1.

### Step 4: Is it an app crash?

If the app process repeatedly crashes (the platform will show repeated
"restarting" cycles):

1. **Check if the Docker image built successfully** — look at the most recent
   deploy logs on the PaaS.
2. **Pull the latest logs** (before the container terminated) — crash
   information is often at the bottom of the log output.
3. **Redeploy a known-good version** — roll back the service to the previous
   image (see §5).

---

## 5. Rolling back a bad deploy

> **⚠️ The exact mechanism depends on the platform. The general patterns are
> described below. Fill in the platform-specific navigation details.**

### Option A: Re-deploy a previous Docker image (preferred)

If the PaaS allows specifying a Docker image tag (or selecting a previous
deploy):

1. Go to the application service in the dashboard.
2. Find the "Deploy" or "Image" settings.
3. Select the previous working deploy/image.
4. Trigger a deploy.

### Option B: Git revert + push

If the platform auto-deploys from a Git branch and doesn't support image
rollback:

1. Revert the bad commit on `master`:
   ```bash
   git revert HEAD
   git push origin master
   ```
2. The CI workflow runs tests. If they pass, the platform auto-deploys the
   reverted code.
3. Confirm the app starts successfully.

### Option C: Pin the Docker image tag in the Dockerfile

If neither of the above are available, and you only have a single `latest`
tag on Docker Hub:

1. Build a known-good version locally and push it with a version tag:
   ```bash
   docker build -t cafeerp:v1.0.1 .
   docker tag cafeerp:v1.0.1 <your-registry>/cafeerp:v1.0.1
   docker push <your-registry>/cafeerp:v1.0.1
   ```
2. Update the PaaS service to pull `<your-registry>/cafeerp:v1.0.1` instead
   of `latest`.
3. Trigger a deploy.

### After rollback

1. Verify `/actuator/health` returns `UP`.
2. Log in as admin and confirm basic flows work (login, orders list, inventory).
3. If the bad deploy changed the database schema (ran a Flyway migration), the
   rollback of the application code alone is **insufficient** — you also need
   to restore the database to the state before that migration ran (see §1).

---

## Reference

| Resource | Location |
|----------|----------|
| Dockerfile | `./Dockerfile` |
| Production config | `src/main/resources/application-prod.properties` |
| Flyway migrations | `src/main/resources/db/migration/V*.sql` |
| CI pipeline | `.github/workflows/ci.yml` |
| Seeded admin credentials | See V2 migration — username `admin`, password `changeme123` |
| Forced password change | Implemented by `PasswordChangeFilter` and V4 migration |

> **IMPORTANT:** Never commit `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, or any
> other secrets into version control. All secrets are injected as environment
> variables by the PaaS platform at runtime.
