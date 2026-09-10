# Demo deployment (public sandbox)

The demo is a **separate service from production**: same Docker image and codebase, different
Render service, different environment variables. Visitors get a private, throwaway sandbox —
no shared database, no production access.

## How the sandbox works

- Activating the `demo` Spring profile swaps every JPA repository for a session-scoped
  in-memory implementation (`com.cafeerp.demo`, `@Profile("demo")` + `@Primary`).
  Production wiring is untouched.
- On first access in a browser session, the session's store is cloned from a static fictional
  fixture (a showcase cafe with English + Amharic + transliterated-Amharic content, several
  historical orders, and `demo-admin` / `demo-staff` / `demo-kitchen` accounts).
- Every session sees only its own copy — visitors can never see each other's data.
- Session expiry (25 minutes of inactivity) evicts the session-scoped beans: the sandbox
  simply disappears. No cleanup job, no replication — single instance only.
- The AI assistant works against the session's demo data with the same features as
  production (live-data answers, real actions behind Confirm/Cancel, voice mode), capped at
  15 assistant messages per visitor session (`DemoAssistantQuota`).
- User management, business settings and assistant audit viewers are disabled in demo mode
  (HTTP 403 + hidden nav links) — they are not meaningful to a portfolio visitor.

## Render setup

Create a **new** web service from the same repo/image (do not reuse the production service):

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` |
| `PORT` | (provided by Render; the app defaults to `8080`) |

That is the entire required set. In particular:

- **Do NOT set `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`** on the demo service. The demo
  profile defines its own throwaway in-memory datasource and never reads those variables,
  so it is physically impossible for the demo to reach the production database — even if
  someone pastes production credentials into the demo service by accident, they are ignored.
- AI provider keys are **optional**. Without them the assistant answers deterministically
  from the demo data (still fully interactive). If you pass keys through so visitors can try
  live AI turns, the 15-message-per-session cap bounds the spend; voice mode and
  action-taking keep working, scoped to the sandbox.
- Health check path: `/actuator/health` (same as production).
- Use a single instance (no scaling / no session replication — sessions are in-memory).

## Verifying the demo

1. Open the demo URL → login page shows the demo-accounts hint.
2. Log in as `demo-admin` / `demo1234`.
3. A "Public demo sandbox" banner shows in the sidebar.
4. `/users`, `/settings`, `/admin/assistant` return the "Disabled in the public demo" page.
5. Place an order, move it through the kitchen queue, check inventory and reports, and chat
   with the assistant (try `status of order 7`, or Amharic: `selam, makiyato yint new?`).
6. Open a second browser (or incognito window): your first session's orders are not there —
   each sandbox is private.

## Local run

```bash
export SPRING_PROFILES_ACTIVE=demo
mvn spring-boot:run
```

No database, no API keys required.
