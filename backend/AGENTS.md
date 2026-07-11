# Backend agent map

This directory is a Java 21 Maven reactor. The root `AGENTS.md` still applies.

## Modules and ownership

- `sparrow-common`: shared API envelope, security context, exceptions and event
  contracts. Keep it small; it must not depend on a business service.
- `sparrow-gateway`: public entry, route ownership, CORS and token filtering.
- `sparrow-user`: identity, membership and account state; database `sparrow_user`.
- `sparrow-graph`: technology graph and Neo4j read model; database `sparrow_graph`.
- `sparrow-trade`: products, orders and payment; database `sparrow_trade`.
- `sparrow-ai`: general chat, RAG and graph-related AI; database `sparrow_ai`.
- `sparrow-industry-chain`: research cards, runs, sources, forum, report and model
  configuration; database `sparrow_industry_chain`.

## Change rules

- Do not add a Maven dependency from one business service to another. Put stable,
  genuinely shared contracts in `sparrow-common`; communicate at runtime.
- Public APIs live in `interfaces`; orchestration lives in `application`; external
  systems, persistence, Feign, Kafka and LLM clients live in `infrastructure`.
  Existing deviations are migration debt, not a pattern to spread.
- Internal endpoints use `/internal/**`; public traffic enters through the gateway.
- Keep transaction ownership inside the service that owns the data. Do not join
  another service's tables or reuse its database credentials.
- Time persisted or emitted across boundaries must carry an offset/zone or be an
  agreed UTC instant. UI display should explicitly use `Asia/Shanghai` for the
  Chinese product experience.
- Long-running research must persist checkpoints before emitting progress. Refresh,
  SSE reconnect, retry and process restart must not erase completed work.
- Never log prompts containing secrets or raw API keys. Model configuration updates
  must preserve encryption, masking and audit behaviour.
- AI chat endpoints must use the shared `AiHarness` metadata contract while keeping
  context assembly and execution inside the owning service. Persist complete turns
  atomically after output validation.
- Agent prompts and runtime bounds live with the executing service, require a
  server-side admin check and retain reviewed code defaults for recovery.

## Verification

```text
node tools/harness.mjs backend sparrow-industry-chain
node tools/harness.mjs changed
node tools/harness.mjs full
```

For runtime changes, add a focused unit/integration test and use the smoke scripts
listed in `docs/harness/verification.md` when infrastructure is available. A schema
change also needs an idempotent `backend/scripts/migrate-*.sql` path.
