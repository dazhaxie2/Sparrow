# Sparrow Agent Map

This file is the entry point for coding agents. Keep it short; put durable detail in
`docs/harness/`. Instructions in the nearest nested `AGENTS.md` add to this file.

## Mission

Sparrow is a Java 21 Spring Cloud Alibaba microservice system with a Vue 3 frontend.
Prefer small, reversible changes that preserve service ownership and observable
behaviour. The repository, not chat history, is the source of truth.

## Read first

1. Inspect `git status --short`; existing changes belong to the user.
2. Read `docs/harness/architecture.md` and the nearest nested `AGENTS.md`.
3. For work lasting more than one session, read or create a task checkpoint under
   `.harness/tasks/`; follow `docs/harness/long-running-tasks.md`.
4. Find the existing implementation and tests before proposing a new abstraction.

## Repository map

- `backend/`: Maven reactor and seven Java modules. See `backend/AGENTS.md`.
- `frontend/`: Vue/Vite application organized by business module. See
  `frontend/AGENTS.md`.
- `tools/`: architecture guards, harness runner, crawler and layout utilities.
- `backend/scripts/`: migrations, smoke tests, load tests and operational checks.
- `deploy/`, `docker-compose*.yml`: runtime and infrastructure definitions.
- `docs/harness/`: maintained architecture, verification and recovery knowledge.
- `.harness/`: machine-readable manifest and resumable task state.

## Non-negotiable boundaries

- Business services may depend on `sparrow-common`, never on another service JAR.
- Cross-service calls use gateway routes, internal HTTP/Feign contracts, or Kafka.
- `sparrow-industry-chain` owns `/api/chains/**` and `sparrow_industry_chain`.
- Never restore the retired `sparrow-chain`, `sparrow_chain`, or frontend
  `modules/chains` paths.
- Frontend business modules do not import another business module directly. Shared
  code belongs under `frontend/src/shared` or `frontend/src/app`.
- Secrets stay in local `.env` or an external secret store. Never commit or print
  API keys, tokens, passwords, or decrypted model credentials.
- Persisted state is authoritative for long-running research. SSE is a delivery
  channel, not the only record of progress.

## Work loop

1. Define observable acceptance criteria and identify the owning module.
2. Make one coherent slice at a time; avoid unrelated cleanup.
3. Add or update tests that fail before the fix when practical.
4. Run the narrowest useful checks after each slice.
5. For UI or end-to-end behaviour, verify the real user flow; generated unit tests
   alone are not completion evidence.
6. Update task state after each meaningful checkpoint, including the next action.
7. Before handoff, report changed files, commands run, results and remaining risk.

## Commands

Run from the repository root:

```text
node tools/harness.mjs doctor
node tools/harness.mjs guard
node tools/harness.mjs changed
node tools/harness.mjs full
node tools/harness.mjs task:init <slug> <title>
node tools/harness.mjs task:status
```

Use `changed --base <git-ref>` in CI or when validating a branch. See
`docs/harness/verification.md` for the change-to-check matrix and direct commands.

## Completion bar

- The requested behaviour and error paths are covered.
- `node tools/harness.mjs guard` passes.
- Targeted tests/builds pass; full verification is run for shared or release-risk
  changes.
- Database changes are additive, idempotent migrations; destructive migration or
  volume deletion requires explicit user authorization.
- Documentation and `.harness/manifest.json` change with architecture or command
  changes.
- No user changes, secrets, generated build output, or local configuration are
  accidentally included.

## Improve the harness

When an agent repeatedly guesses wrong, a task stalls, or review catches the same
defect twice, do not only add prose. Add the smallest durable feedback mechanism:
a guard rule, focused test, fixture, diagnostic command, or failure-playbook entry.

