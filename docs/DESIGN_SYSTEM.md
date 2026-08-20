# Cafe ERP — Design System

Single source of truth for the app's visual language. Read this before
touching any page's markup or styling. The implementation lives in
`src/main/resources/static/css/app.css`; this document is the rationale
and the map of what exists.

## Why this exists

Every page in the app used to carry its own `<style>` block (90–166 lines
each, ~20 pages, ~2,300 lines total) with the same rules copy-pasted and
slowly drifting — one page had `.stat-icon`, another didn't; hover easing
used an overshoot bounce in most places but not all. That drift is exactly
what a "consistent app" pass is supposed to prevent, and copy-pasted CSS
makes it structurally inevitable. This system replaces all of it with one
file, loaded once, that every page consumes the same way.

## Design brief (for context on the choices below)

**Subject**: an operational tool used in three different physical contexts
— an admin at a desk running reports, staff at a register creating orders,
and a kitchen tablet getting tapped with someone's hands mid-shift. The
job in all three cases is *get through the task fast, without misreading a
number*. This is not a marketing site; legibility and speed beat visual
flourish every time a choice has to be made between them.

**Palette**: black, white, and warm paper tones, full stop — color is
spent only on status (a badge, a stock-level bar, a kitchen ticket's
priority edge), never on decoration. This was an explicit product
requirement, and it also sidesteps the generic-AI-gradient look the
previous login/change-password pages had drifted into (amber/stone
gradients, blurred decorative blobs) — see `git log` for the before/after
if you want the receipts.

**Type**: Inter for UI text (kept — it's a fine grotesk and there was no
reason to change a working choice), plus IBM Plex Mono for every
*identifying or countable figure*: prices, order/user IDs, quantities,
timestamps. That's the one deliberate signature in the system — it ties
the interface back to a register receipt / kitchen ticket without being
twee about it, and it has a real functional benefit too (tabular figures
align in columns).

**Signature element**: the kitchen order ticket's dashed "tear" rule
between the header and the item list (`.ticket-tear` in `app.css`,
`kitchen/queue.html`). One deliberate risk, spent in exactly one place,
appropriate to a kitchen order queue and nowhere else.

**Motion**: quick and quiet. 150–180ms ease-out, a 1–2px hover lift, no
scale-pop, no overshoot bounce. `prefers-reduced-motion: reduce` collapses
all durations to ~0. The previous system's buttons used a spring/overshoot
easing (`cubic-bezier(0.34, 1.56, 0.64, 1)`) with a scale-up-then-squash
click animation — a "toy" feel that reads wrong for software someone uses
50 times a shift to ring up coffee orders.

## Token reference

All tokens are CSS custom properties on `:root` in `app.css`. Don't
hardcode a hex value in a template — reference the token, so a future
palette change is a one-line edit instead of a grep-and-replace.

| Token | Value | Use |
|---|---|---|
| `--ink` | `#0a0a0a` | Primary text, primary buttons, page-header fill |
| `--ink-soft` | `#262624` | Hover state for ink surfaces |
| `--paper` | `#ffffff` | Card/input backgrounds |
| `--surface` | `#faf9f6` | Page background |
| `--surface-2` | `#f1f0ec` | Hover fills, table stripes, icon tiles |
| `--line` / `--line-strong` | `#e6e4de` / `#d2d0c8` | Hairline borders |
| `--muted` / `--muted-2` | `#6d6b64` / `#9c998f` | Secondary / tertiary text |
| `--danger` / `-bg` / `-border` | red family | Errors, out-of-stock, destructive states |
| `--warn` / `-bg` / `-border` | amber family | Low stock, preparing, attention |
| `--success` / `-bg` / `-border` | green family | In stock, ready, confirmed |
| `--info` / `-bg` / `-border` | blue family | Pending, informational, kitchen role |
| `--radius-sm/-/-lg` | 6 / 10 / 16px | Small controls / buttons+inputs / cards |
| `--font-sans` | Inter | All UI text |
| `--font-mono` | IBM Plex Mono | `.num` / `.mono` — prices, IDs, quantities, timestamps |

## Component map

Everything below is defined once in `app.css` and used by class name only
in templates — no page should declare its own `<style>` block for
anything covered here.

- **`.page-header`** — the solid ink header bar at the top of every page.
- **`.card`**, **`.card--hover`**, **`.card-pad`** — the base surface for
  every section. `--hover` adds the lift-on-hover used for clickable
  stat tiles.
- **`.btn`, `.btn-primary`, `.btn-secondary`, `.btn-ghost`** — all buttons.
  Add `<span class="btn-spinner" aria-hidden="true"></span>` inside a
  primary/secondary button and `app.js` will show it automatically on
  form submit (see "Behavior" below).
- **`.icon-tile`** (+ `--warn/--success/--danger/--info/--ink/--sm`) —
  monochrome by default; a color variant is only used where the icon
  itself communicates status, never as page decoration.
- **`.tile`** — the clickable quick-action tiles on the dashboard.
- **`.badge`** (+ `-neutral/-success/-warn/-danger/-info/-ink`) — every
  status pill (order status, stock status, role label).
- **`.alert`** (+ `-danger/-success/-warn/-info`) — inline banners.
- **`.empty-state`** — the "nothing here yet" block used on every list
  page instead of a bare `<tr>No records</tr>`.
- **Forms** — `.field-label`, `.field-hint`, `.field-error`, plus
  overrides on `.uk-input/.uk-select/.uk-textarea` for focus rings and
  the `.uk-form-danger` error state.
- **Tables** — `.table-wrap` for horizontal scroll, header/row styling
  applied globally to `.uk-table`, `.row-flag-warn` for a highlighted row,
  `.stock-bar-track`/`.stock-bar-fill` for the inventory level bar.
- **Kitchen** — `.ticket`, `.ticket-tear`, `.ticket-items`, `.status-btn`
  (44px+ touch target, per the accessibility pass below).
- **Transcript** — `.msg-row`, `.avatar`, `.msg-bubble` (assistant admin
  log pages).

## Behavior (`app.js`)

Loaded once via the `head` fragment on every page (deferred, so it never
blocks render). Handles three things that used to be re-implemented (or
missing) per page:

1. **Mobile nav drawer** — open/close/Escape-to-close/overlay-click-to-close.
2. **Submit button loading state** — any `<form>` without
   `data-no-loading` gets its submit button marked `data-loading="true"`
   on submit, which dims it, blocks re-clicks, and reveals `.btn-spinner`
   if present. This is a real bug fix, not just polish: nothing previously
   stopped a double-tap on "Create Order" from creating two orders.
3. **`[uk-close]` wiring** — Franken UI v2.1.2's `core.iife.js` doesn't
   register a click handler for this attribute the way older UIkit
   versions did, so alert dismiss buttons need one; this provides it
   app-wide instead of per-page.

## Accessibility floor

- Every interactive element has a visible `:focus-visible` ring (button
  components use a matched box-shadow ring; everything else falls back to
  the global `outline: 2px solid var(--ink)` rule in `app.css`).
- `.btn` and `.status-btn` have a `min-height: 2.75rem` (44px) — relevant
  everywhere, but especially the kitchen queue's tablet-in-a-kitchen
  context.
- `prefers-reduced-motion: reduce` collapses all transition/animation
  durations.
- Color is never the only signal: every status badge carries a text label,
  not just a color.
- Contrast: body text is `--ink` (#0a0a0a) on `--paper`/`--surface`
  (both >19:1); status badge text colors were chosen against their own
  `-bg` token to clear 4.5:1 at the sizes they're used at.

## What's still page-specific CSS, and why

A handful of pages keep a small `<script>` block for behavior that is
genuinely local to that page (the kitchen clock, the order-form running
total, the inventory inline-edit toggle, the change-password live
checklist). None of them define page-local *styling* — only DOM
manipulation of the shared classes above. If a future page needs a new
visual pattern, it belongs in `app.css` as a new component, not as an
inline `<style>` block on that page.
