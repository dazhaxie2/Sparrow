# Verification guide

## Validation levels

| Level | Command | Use |
| --- | --- | --- |
| Environment | `node tools/harness.mjs doctor` | First run or toolchain failure |
| Deterministic | `node tools/harness.mjs guard` | Every meaningful edit |
| Change-aware | `node tools/harness.mjs changed` | Normal local handoff |
| Component | `node tools/harness.mjs backend <module>` or `frontend` | Tight edit loop |
| Full | `node tools/harness.mjs full` | Shared contracts, build/config or release-risk changes |
| Runtime | `backend/scripts/smoke.ps1` and focused manual/E2E flow | Behaviour involving deployed services |

`changed --base <ref>` includes committed branch changes relative to a base plus
staged, unstaged and untracked files. Without `--base`, it validates the working
tree. It always runs both deterministic guards first.

## Change-to-check matrix

| Changed area | Minimum evidence |
| --- | --- |
| One backend module | Its Maven tests with `-am`, plus guards |
| `sparrow-common` or parent POM | Full backend test reactor |
| One frontend module | TypeScript/Vite production build, plus guards |
| `tools/sparrow-spider` / `tools/sparrow-layout` | Python compile check plus focused tool fixture/run when behaviour changes |
| Gateway route / Compose / `.env.example` | Guards and `docker compose config --quiet` |
| SQL migration | Owning service tests, idempotence review, apply twice on disposable DB |
| SSE / long-running research | Industry-chain tests plus refresh, reconnect and process-restart scenario |
| Authentication / payment | Focused unit tests plus `backend/scripts/smoke.ps1` when stack is available |
| Model URL/key/config | Masking/audit tests; verify no secret in response, log, URL or git diff |
| Harness rules | Deliberately trigger the new rule once, then run `guard` successfully |

## Direct commands

The legacy-named Maven wrapper actually uses Java 21 in Docker:

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/mvn17.ps1 clean test
powershell -ExecutionPolicy Bypass -File backend/scripts/mvn17.ps1 -pl sparrow-industry-chain -am clean test
```

```text
cd frontend
npm ci
npm run build
npm run guard:architecture
```

## Evidence quality

A passing test written by the same agent is useful but not sufficient for critical
behaviour. Completion evidence should combine at least one independent signal where
the risk warrants it: an existing regression test, browser flow, API smoke test,
database observation, log/metric, or contract exercised across a real boundary.

When a required runtime dependency is unavailable, do not claim the scenario
passed. Record the unrun command and residual risk in the task file and handoff.
