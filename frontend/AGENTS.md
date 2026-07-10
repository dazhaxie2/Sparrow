# Frontend agent map

This directory is Vue 3 + TypeScript + Vite. The root `AGENTS.md` still applies.

## Structure

- `src/app`: application shell, router and cross-cutting composition.
- `src/modules/<business>`: a business-owned vertical slice.
- `src/shared`: reusable API, UI, state and utilities with no business ownership.

## Change rules

- A file inside one business module must not import another business module. Move
  stable shared behaviour to `src/shared`, or compose modules from `src/app`.
- Keep API access in the owning module/shared API layer, not scattered through view
  components.
- Preserve SSE reconnect and snapshot recovery. The browser event stream is not the
  source of truth; refreshed views must rebuild from persisted backend state.
- Render product timestamps in `Asia/Shanghai`; do not depend on the browser or
  server machine's implicit timezone.
- Show explicit pending, timeout, retry, cancelled and failed states for long jobs.
  A progress percentage must never be the only liveness signal.
- Do not expose model API keys in browser bundles, local storage, URLs or logs.

## Verification

```text
node tools/harness.mjs frontend
node tools/harness.mjs changed
```

For user-visible changes, also exercise the affected flow in a real browser at the
target viewport and verify refresh/reconnect behaviour where applicable.

