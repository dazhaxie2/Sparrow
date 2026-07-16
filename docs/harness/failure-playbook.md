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

## AI chat appears complete but disappears after refresh

Treat this as a terminal-contract violation. For a request with a session ID, inject a
repository failure and verify the server emits `error`/`failed` without `done`; the
client must not increment its durable message count. Never downgrade persistence failure
to a warning after answer deltas have already been delivered.

## Timestamp is several hours wrong

Trace the value at database, Java serialization and browser formatting boundaries.
Persist an instant or explicit offset, and render the Chinese UI with
`Asia/Shanghai`. Add a test with a fixed instant; never rely on the machine timezone.

## Model request stalls or configuration changes do not apply

Identify the active model configuration version without exposing the API key. Check
URL normalization, connect/read timeout, provider response code, retry budget and
whether all instances received the configuration update. Preserve the last known
good configuration and make rollback auditable.

When an administrator leaves a masked API key blank, reuse is allowed only for the exact
same normalized Base URL. A host, port or path change must require a newly supplied key;
cover both the connection-test and saved-config paths with regression tests.

## Authentication abuse or account takeover risk

Verification codes must be atomically consumed and have a bounded failure budget per
purpose and email. A new code resets the budget; login and binding codes never share it.
Changing an existing password requires the current password and advances the user's auth
version so all older tokens fail at the gateway. Keep gateway compatibility tests for
legacy tokens during rollout and collision tests for concurrent email auto-registration.

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

## Self-hosted deploy fetch fails to reach GitHub

The `deploy` job runs on a self-hosted Windows runner behind a Mihomo/Clash TUN
proxy. Two distinct failure modes look the same (`Failed to connect to github.com
port 443`) but need different fixes. Diagnose before changing the workflow.

1. Distinguish the failure layer first: if the `verify` job (GitHub-hosted
   ubuntu) passes but `deploy` (self-hosted) fails on `git fetch`, the repo and
   workflow logic are fine; the problem is the runner host's network path to
   GitHub.
2. The runner service runs under a specific Windows account. Confirm it before
   trusting any `git config --global` fix: `Get-CimInstance Win32_Service |
   Where-Object { $_.PathName -match 'actions.runner' } | Select StartName`.
   Only the `~/.gitconfig` of that account (here `.\hai`) is read by the runner;
   a global config set under another account is inert.
3. The Mihomo HTTP proxy port is whatever `verge-mihomo` actually listens on,
   not the value cached in the registry `ProxyServer`. List real listeners:
   `Get-NetTCPConnection -State Listen | ... OwningProcess -> verge-mihomo`.
   On this host the live mixed-port is `7897`; the registry `ProxyServer` held a
   stale `7993` that no process serves.
4. Do not commit `git config --global http.proxy` as the fix. A global proxy
   breaks all `git` whenever Mihomo is off or restarting. The workflow itself
   probes `127.0.0.1:7897` per run and routes `fetch` through it only when up,
   falling back to direct/TUN otherwise. See the `Sync repo to triggering
   commit` step in `.github/workflows/deploy.yml`.
5. If fetch still fails after the probe logic, verify Mihomo itself can reach
   GitHub from the runner: `Test-NetConnection github.com -Port 443` and a
   manual `git ls-remote`. A proxy that cannot reach GitHub cannot be fixed in
   the workflow.

## Deploy job stalls at "Waiting for a runner to pick up this job"

A healthy self-hosted runner claims a queued job within seconds. Minutes of
"Waiting" means the runner service lost its long-poll session to GitHub while
the process stayed "Running". The broker reconnect loop is visible in the
runner log, not the Actions UI.

1. Read the newest runner log, not the service status:
   `Get-ChildItem ...\actions-runner\_diag -Filter 'Runner_*.log' | sort
   LastWriteTime -Descending | select -First 1`. `Get-Service` showing `Running`
   only proves the process is alive, not that it is polling GitHub.
2. Look for the broker retry loop: `BrokerMessageListener` lines such as
   `Retriable exception: ... 无法连接 (127.0.0.1:<port>)` followed by
   `Sleeping for 30 seconds before retrying`. The `<port>` is the culprit — the
   runner's .NET stack is routing its GitHub connection through a dead local
   proxy port and retrying every 30s.
3. That port comes from `HKCU\...\Internet Settings` `ProxyServer`, which can
   hold a stale value (e.g. `7993`) from a previous proxy configuration even
   when `ProxyEnable` is `0`; the runner caches it at start. Clear it:
   `Set-ItemProperty ...\Internet Settings -Name ProxyServer -Value ''`. Also
   check and reset the WinHTTP layer: `netsh winhttp show proxy` /
   `netsh winhttp reset proxy`.
4. Restart the service so it re-reads the cleared proxy and rebuilds the
   session: `Restart-Service actions.runner.dazhaxie2-Sparrow.14120DA`. Within
   ~10s the log should show `Running job:` and no further `127.0.0.1:<port>`
   refusals; the queued job then leaves "Waiting" on the Actions UI.
5. Watch for recurrence: if `ProxyServer` repopulates, some proxy GUI is writing
   it back on startup. Fix that tool's configuration rather than re-clearing the
   registry repeatedly.

## Website returns Cloudflare 1033 or 530 (tunnel/origin)

The public site is fronted by a `cloudflared` tunnel running as a Windows
service on the same host. `1033` means Cloudflare cannot resolve the tunnel
(connection lost); `530` whose body is `error code: 1033` is the same thing
seen from a different edge. Both are tunnel-side, not origin-side.

1. Do not assume the origin is down. Check it directly first:
   `curl -I http://localhost:8080` and the real business paths
   (`/api/user`, `/api/graph`) — a `404` on `/` with `200` on `/api/*` means the
   gateway is healthy; the failure is between Cloudflare edge and cloudflared.
2. The `Cloudflared` service runs as `LocalSystem`, so its proxy state lives in
   `HKEY_USERS\S-1-5-18\...\Internet Settings`, a different hive from the
   interactive user. Clear a stale `ProxyServer` there too, same as for the
   runner.
3. Confirm the tunnel is actually registered, not just that the process is
   `Running`: `cloudflared --config ... tunnel info <id>` must list a
   `CONNECTOR ID` with `EDGE` colos, and `Get-NetTCPConnection` for the
   cloudflared PID should show ~4 `Established` connections on remote port
   `7844`. Fewer means connections were not rebuilt after a network blip.
4. Restart the service to rebuild connections: `Restart-Service Cloudflared`,
   wait ~12s, re-check the connection count and the site. This is the common
   fix after a Mihomo outage scrambles cloudflared's edge sessions.
5. Configure auto-recovery so a crash is self-healing:
   `sc.exe failure Cloudflared reset= 86400 actions= restart/5000/restart/10000/restart/30000`.
   Note this only fires when the process exits; a "Running but disconnected"
   state needs the health-probe approach, not `sc failure`.

## Intermittent total outage: SSH (Tailscale) and the site drop together

When the Tailscale SSH session and the public site fail at the same time, the
Mihomo TUN is the single point of failure — it hijacks the whole host's traffic
including Tailscale's STUN/WireGuard. The Mihomo *process* can be stable for
hours while its upstream airport nodes flap, so "process uptime" does not prove
connectivity.

1. Read the Mihomo service log for a burst of `dial GLOBAL ... error: context
   deadline exceeded` lines. That is the airport failing, not a crash — the
   core stays up while every outbound times out.
2. The smoking gun is Tailscale traffic inside those timeouts: `[UDP] dial
   GLOBAL ...:41641 --> ...:3478 error`. Port `41641` is Tailscale's WireGuard
   and `3478` is STUN; both being routed through `GLOBAL` is the bug. Confirm
   the route is not direct: `Find-NetRoute -RemoteIPAddress <tailscale-ip>`
   should resolve on the `Tailscale` interface, not `Mihomo`.
3. Force Tailscale traffic to bypass the proxy via Clash Verge's **rules
   override** (`profiles/*.yaml` bound through `profiles.yaml` ->
   `option.rules`), using `prepend` rather than editing the generated
   `clash-verge.yaml`:
   ```yaml
   prepend:
     - DOMAIN-SUFFIX,ts.net,DIRECT
     - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve
     - IP-CIDR,fd7a:115c:a1e0::/48,DIRECT,no-resolve
   ```
   After the airport fails completely, the SSH rescue channel then stays up
   even though the public site (which needs the proxy to reach Cloudflare)
   does not — that trade-off is unavoidable on a single-outbound host.
4. The same prepend belongs in the merge profile (`option.merge`) as a backup;
   `prepend-rules:` is the merge-file key, `prepend:` is the rules-override
   key. Keep both consistent.

## Editing Clash Verge / Mihomo config without corrupting it

The runtime config `clash-verge.yaml` is UTF-8 with CJK proxy-group names and
is regenerated by the Verge GUI on every profile reload. Manual edits are
fragile — two real failures: a text-mode rewrite mangled the CJK bytes into
`閸ヨ棄...`, and a byte-offset calc landed rules in the wrong section, both
making the core refuse to start (`yaml: line N: did not find expected key`).

1. Never hand-edit `clash-verge.yaml`. Put changes in the Verge-managed
   override/merge files (`profiles/*.yaml`) and let the GUI merge them. That
   path preserves encoding and survives subscription updates.
2. To make a change take effect without the GUI, the only reliable trigger is
   a Verge profile reload/update from the GUI — there is no controller TCP API
   by default (`external-controller: ''`), and a hand-started `verge-mihomo -f`
   reads a static file without re-running the merge. Use a remote-desktop tool
   (e.g. AweSun) to reach the GUI when SSH is the only other channel.
3. Before swapping any hand-built config into the running core, validate it
   with `verge-mihomo -d <home> -f <file> -t`. A failed `test` must never be
   loaded — keep the last known-good file untouched as the rollback point and
   switch via a separate filename, not in-place.
