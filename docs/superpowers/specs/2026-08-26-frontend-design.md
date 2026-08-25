# Prayer Roster — Frontend Design

Status: proposed design, not yet implemented
Depends on: `2026-08-26-admin-api-and-spa-auth-design.md` (B1 blocks all authenticated work)
Reference: `../../../nsia-dashboard` (inspiration, not a template)

## Problem

The backend has no client. Nobody can see a roster, submit an unavailability, or sign in without
curl. The product question a member has — *"quand est-ce que je sers, et quel est mon rôle ?"* —
currently has no interface that answers it.

The goal is a frontend that reads as a real product rather than a generated admin panel: French
first, dark blue and white, restrained, fast, accessible, and honest about what the API can serve.

## What the API actually serves

Every screen below is traced to a real endpoint. Two constraints shaped the whole design:

1. **A member sees only their own assignments.** The `USER` role is seeded with zero permissions and
   `/api/prayer-sessions` requires `ROSTER_VIEW`. This is a product decision, confirmed: the
   community calendar is administrative. A member's calendar is built from
   `/api/me/prayer-assignments`, which returns `(id, date, role)` and nothing else.
2. **There is no time-of-day and no per-assignment status anywhere in the domain.** Any mockup
   showing "19:00" or "Statut: Confirmée" cannot be built and is dropped rather than faked.

## Stack

Adopted from NSIA, because it is modern, already proven in this organisation, and reduces the number
of novel decisions: **React 19, Vite 7, TanStack Router (file-based) + Query + Table, Tailwind 4,
shadcn/ui (`base-nova`), Zod 4 + react-hook-form, date-fns, sonner, nuqs, Vitest + Testing Library,
Playwright, `@t3-oss/env-core`.** Package manager `pnpm`, matching NSIA.

Deliberately **not** adopted:

- `mvc-front-sdk` — NSIA's controllers extend a shared SDK base built for its Django API. Our backend
  speaks JHipster `ProblemDetail` and a Bearer ID token; a thin local fetch wrapper is smaller and
  honest about the contract.
- NSIA's i18n — a single `fr.ts` object imported directly. Type-safe, but no switching mechanism.
- NSIA's palette — see below.

## Visual identity

NSIA's `--primary` is `oklch(0.2838 0.1056 264.94)`: already dark blue, with a gold accent and a
dark-blue sidebar. Reproducing "dark blue + white" would make Prayer Roster look like the same
product. Differentiation is therefore explicit:

| | NSIA | Prayer Roster |
|---|---|---|
| Primary | `oklch(0.284 0.106 265)` navy | `oklch(0.32 0.075 245)` deeper, cooler cerulean |
| Accent | gold `oklch(0.726 0.147 84)` | **none** — emphasis is tonal, via weight and space |
| Sidebar | saturated navy | white/near-white; the blue is reserved for meaning |
| Display type | Inter | **serif for landing display**, Inter for UI |

Dropping the accent colour and inverting the sidebar are the two moves that most change the
impression. The serif display face gives the landing page a calm, considered voice that a SaaS sans
cannot, and it is the single cheapest way to not look generated.

Status colours stay tonal: roster states (`Brouillon`, `Publié`, `À réorganiser`, `Archivé`) use
weight, border and one restrained amber reserved for "needs attention". Semantic red and green exist
only for destructive confirmation and success feedback. **State is never signalled by colour alone**
— always colour plus text or icon.

## Illustrations

I cannot generate images. Rather than pretend otherwise, the visual language leans on what can be
authored directly and kept perfectly on-brand: **hand-authored SVG compositions** built from the
product's own vocabulary — week grids, day cells, assignment chips, calendar rhythm. These read as
considered and expensive when done well, and they are guaranteed cohesive.

Where a human figure genuinely helps (the landing hero, a few empty states), **recoloured unDraw
scenes** (MIT, single-colour customisable to our blue) fill the gap. If a persona style is wanted
beyond that, it needs a designer or stock assets — flagged rather than fudged.

## Internationalisation

French is the default and the source of truth. A small typed dictionary with a real locale switch:

```
src/i18n/
  fr.ts          # source of truth; its type defines the contract
  en.ts          # satisfies typeof fr — missing keys are a compile error
  index.ts       # provider + useT()
```

No i18n library. The type-checked `en.ts` is what makes "prepare for English later" real rather than
aspirational: adding a key to French breaks the build until English has it too.

Dates are formatted with `date-fns` and the `fr` locale. **All dates from this API are `LocalDate`
strings** (`2026-09-14`) with no timezone. They are parsed with `parseISO` and never passed through
`new Date(string)` in a way that applies a UTC offset — the failure mode being 14 September rendering
as the 13th for anyone west of UTC.

## Architecture

Feature folders mirroring NSIA, which keeps the two codebases legible to the same people:

```
src/
  routes/            # TanStack Router, file-based
    __root.tsx
    index.tsx                    # landing (public)
    auth/callback.tsx            # handoff exchange
    _authenticated.tsx           # guard
    _authenticated/
      dashboard, calendar, assignments, availability, notifications, profile
      admin/{rosters,prayer-config,users,invitations,reminders,roles}
  features/<domain>/{index.tsx,components/,hooks/,types/}
  lib/api/           # one module per resource, typed to real DTOs
  lib/auth/          # token storage, guard, permission helpers
  i18n/
  components/{ui,layout,shared}/
```

**State**: TanStack Query owns server state; nuqs owns filters and pagination in the URL; React
context holds only auth and theme. No global store.

**API layer**: one thin `apiFetch` with Bearer injection, JHipster `ProblemDetail` parsing, and a
typed error union. Components never call `fetch`.

## Permissions

`GET /api/account` returns the granted authorities (`PERM_*`, plus `ROLE_ADMIN` for super admins).
From those: `hasPermission("ROSTER_GENERATE")` and a `<Can permission="...">` wrapper, used for
navigation, routes, buttons and sections.

This is **UX only**. Every gated call is independently enforced by `@PreAuthorize` on the backend,
and the UI is written assuming a 403 can still arrive — hiding a button is never the control.

## Screens

**Public** — Landing (`/`): hero, comment ça fonctionne, planification intelligente, aperçu du
calendrier, disponibilités, notifications, CTA. `/auth/callback` exchanges the handoff and redirects.

**Member** (authenticated, no permission required)

| Screen | Endpoint |
|---|---|
| Tableau de bord | `/api/me/prayer-assignments`, `/api/me/notifications` |
| Mon calendrier | `/api/me/prayer-assignments` (own assignments only) |
| Mes services | `/api/me/prayer-assignments`, `/pdf` |
| Mes disponibilités | `/api/me/availability` + conflict preview (B5) |
| Notifications | `/api/me/notifications`, `PUT /{id}/read` |
| Mon profil | `/api/account`, `/api/me/notification-preference` |

The dashboard leads with a single answer — the next assignment, its date and role — rather than a KPI
row. Upcoming assignments, a calendar preview and recent notifications follow.

**Admin** (permission-gated)

| Screen | Permission |
|---|---|
| Plannings: list, detail table, generation | `ROSTER_VIEW`, `ROSTER_GENERATE` |
| Calendrier général (everyone's assignments) | `ROSTER_VIEW` via `/api/prayer-sessions?from&to` |
| Configuration hebdomadaire + historique | `PRAYER_CONFIG_VIEW/UPDATE` |
| Utilisateurs: list, detail, statut, rôle, capacités | `USER_VIEW/UPDATE/ROLE_ASSIGN` |
| Invitations (allowlist) | `USER_VIEW/CREATE/DELETE` |
| Rappels | `REMINDER_CONFIG_VIEW/UPDATE` |
| Rôles et permissions | `ROLE_*` — **depends on B2** |

Invitations is not in the original brief but is not optional: without it nobody can sign in at all.

The UI must keep two concepts visually distinct, because they are routinely confused: the
**application role** (`USER`/`ADMIN`/`SUPER_ADMIN`, what you may do in the software) and **service
capabilities** (`canModerate`/`canPreach`, what you may do in the prayer service). A plain `USER` can
hold both capabilities.

## Error, empty and loading states

API errors are translated to French through a single mapper keyed on the `ProblemDetail` `message`
field (`error.invalidState`, `error.duplicateEmail`, …), never surfaced raw. 401 clears the session
and returns to the landing page; 403 renders a designed "accès refusé" rather than a blank screen.

Every list has an authored empty state using the same visual language as the landing page. Loading
uses skeletons shaped like the content, never a centred spinner. A route-level error boundary keeps
one broken section from taking down the shell.

## Testing

- **Unit** — date handling (explicitly including the timezone-shift trap), permission helpers, the
  error mapper, i18n key coverage.
- **Component** — availability form including the conflict warning, permission-aware rendering,
  calendar interaction, notification read state.
- **E2E (Playwright)** — sign-in, dashboard shows the next assignment, submit an unavailability,
  admin generates a roster, a member is refused an admin route.

Google sign-in cannot be automated in CI, so E2E authenticates by seeding a token via the handoff
endpoint against a test backend rather than driving Google's consent screen.

Gates before any phase is called done: `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build`, and
a real browser pass at desktop, tablet and mobile widths.

## Delivery phases

1. Scaffold, env, API layer, i18n, design tokens
2. Design system primitives + landing page
3. Auth flow, app shell, permission system *(needs B1)*
4. Member experience
5. Admin experience
6. Roles UI *(needs B2)*, polish, accessibility, performance, visual review

Phases 1–2 depend on no backend work and can start immediately.
