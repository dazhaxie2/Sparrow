# Failure and recovery playbook

Use this file for recurring failure patterns. Add a deterministic check or test when
possible; keep prose for diagnosis that cannot be automated safely.

## Research appears stuck at a percentage

1. Check the persisted run status, current stage, heartbeat/update time and last
   durable artifact. Do not infer liveness from the percentage alone.
2. Distinguish a disconnected SSE client from a stopped worker by reconnecting and
   loading the snapshot endpoint/state.
3. Inspect provider latency, timeout/retry logs and the specific agent/stage. Avoid
   restarting the entire run until its checkpoint is known.
4. Recovery must be idempotent: resume from the last completed stage and avoid
   duplicate sources, forum messages, charges or report sections.
5. Regression evidence should include refresh, SSE reconnect and process restart,
   not only the happy-path orchestrator test.

## Research history disappears after refresh

Treat this as persistence/reconstruction failure. Verify that forum messages,
sources, artifacts and run metadata are committed before their progress event is
emitted, then verify the card snapshot reconstructs them without an active SSE
connection.

## Timestamp is several hours wrong

Trace the value at database, Java serialization and browser formatting boundaries.
Persist an instant or explicit offset, and render the Chinese UI with
`Asia/Shanghai`. Add a test with a fixed instant; never rely on the machine timezone.

## Model request stalls or configuration changes do not apply

Identify the active model configuration version without exposing the API key. Check
URL normalization, connect/read timeout, provider response code, retry budget and
whether all instances received the configuration update. Preserve the last known
good configuration and make rollback auditable.

## Guard fails

Read the remediation printed beneath the rule. Change the code or, for an intentional
architecture decision, update the manifest, architecture document, guard and tests
together. Never silence a rule with a broad exclusion merely to obtain green CI.

## Full verification cannot run

Run `doctor`, then the narrowest unaffected checks. Record the missing dependency,
exact failed command and unverified risk in the active task. Do not replace a real
runtime check with an invented success statement.

If Maven reports "Nothing to compile" and then fails on an API that the configured
Java version supports, suspect stale `target` output from another JDK/compiler. The
Harness uses `clean test` deliberately; do not weaken it to reuse ambiguous classes.
