# Administrator-managed Agent configuration

## Ownership

Agent configuration follows the same service boundary as execution:

| Service | Public admin API | Database tables | Agent keys |
| --- | --- | --- | --- |
| `sparrow-ai` | `/api/ai/admin/agent-configs` | `ai_agent_config`, `ai_agent_config_audit` | `tech-tree-guide` |
| `sparrow-industry-chain` | `/api/chains/admin/agent-configs` | `agent_config`, `agent_config_audit` | planning, research roles, evidence, host, graph and report |

The frontend route `/admin/agents` aggregates both APIs. It never reads either
database directly and never receives model API keys. Each API calls the internal
user profile endpoint and requires role `admin`; the router guard is only an
additional user-experience boundary.

## Managed fields

Each registered Agent has a stable key and immutable product description. An
administrator can update:

- enabled/disabled state;
- system prompt (20 to 20,000 characters);
- bounded context messages and characters;
- maximum validated output characters;
- maximum steps (retrieval count for the graph Agent, reflection/execution depth
  for research Agents).

Services seed reviewed defaults with `INSERT IGNORE`, read the persisted value for
new requests, and fall back to the reviewed code default if the configuration table
is temporarily unavailable. Updates write an audit summary without copying the
prompt body into the audit table. Existing in-flight research keeps its current
execution objects; subsequent stages that resolve a profile use the latest value.

## Identity and administrator bootstrap

`sparrow-user` owns usernames, normalized emails and roles.

- Password login accepts an exact username or an already-bound email.
- Login verification codes and binding verification codes use separate Redis key
  namespaces; one purpose cannot be replayed as the other.
- A username-only account may bind one globally unique email after proving control.
- `sparrow.auth.bootstrap-admin-email` defaults to `13102373468@163.com`. Startup,
  email registration and email binding all promote that verified account to
  `admin`. No password or token is created, reset or committed.
- Existing administrators are not silently demoted.

Production may override the bootstrap identity with `SPARROW_ADMIN_EMAIL`. Run the
additive migrations before deployment:

```text
backend/scripts/migrate-user-email-admin.sql
backend/scripts/migrate-agent-config.sql
```

After deployment, sign in to the designated account, open `/admin/agents`, update
one low-risk prompt, and verify both the next Agent request and its audit row.
