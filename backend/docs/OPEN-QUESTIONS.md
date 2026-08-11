Q-1: agents and conversations must be also hard-deleted.
Q-2: MCP enabled must be done per-agent per-MCP
Q-3: An agent B cannot be a member of the team of agent A if the team of agent B is not empty. An agent B member of the team of agent A cannot have a non empty team list.
Q-4: BRAVE_API_KEY is the correct name. SPECS have been updated
Q-5: When restarting a conversation the chat memory (and LLM context) must be loaded with the existing conversation. The conversation will continue as if it has never been stopped
Q-6: Max length is 32 characters. Empty message must be ignored. If title cannot be deducted a default name will be allowed: chat-<uuid>. The title can be modified by the user afterward
Q-7: HTTP status must be 429. Refill after an hour. Default value is 50 per hour, and 10 per minute. Must be configurable per admin user
Q-8: First admin user is created by Flyway. Admin user will be forced to change its password at first login. No rotate policy for admin password
Q-9: `(client-id, api-key)` pairs can be created by admin user only thanks to a specific endpoint. They are not allocated to a user. They will serve as auth parameters for machine-to-machine access
Q-10: or short TTL only
Q-11: Agent name will be actually unique per owner user. Not globally. Specifications have been changed
Q-12: Pagination must be done per scrolling
Q-13: No quotas per user exists. A conversation will have a maximum number of messages equals to 64
Q-14: Yes, onversation titles are editable by the user after auto-derivation
Q-15: The root directory of MCP filesystem must be scoped per user
Q-16: no audit trail required for V1.

---

- **Q-17 (AUTH / SEC) — JWT signing algorithm and key source.** JWTs should be signed with **HS256** (single shared secret, simpler) The signing key come from a fixed environment variable
- **Q-18 (AUTH) — API-key authorization scope.** When a request arrives via `X-Api-Key` / `X-Client-Id`, (b) full chat capabilities under a virtual "system" principal
- **Q-19 (AUTH) — Logout endpoint.** For TTL-only enforcement (`REQ-AUTH-006`), a logout endpoint is still required for client convenience. Existing tokens must be discarded
- **Q-20 (AGT / CHAT) — Field length caps.** Maximum lengths for: agent `name`: 32 char, `description`, `systemPrompt`; 1024 char; chat message `content`: 1024 char.
- **Q-21 (AGT / CHAT) — Effect of agent mutation on existing conversations.**  all subsequent turns use the new configuration immediately
- **Q-22 (TOOL) — Initial tool catalog for v1.** For v1 the list of available tools will be created with only an AwsS3Tool. You can find an example of a tool implementtion in the file docs/AwsS3Tool.java. THIS is ONLY an example. You can modify it. This tool can then be part of a static list provided by a dedicated tool service.
- **Q-23 (LLM / AGT) — Per-agent LLM overrides.** Model name and sampling parameters (temperature, max output tokens, top-p) are properties of the **agent**. They can be changed at any time and new values must be taken into account in the ongoing conversation
- **Q-24 (CHAT) — Tool-call message persistence and visibility.** Tool-call requests and tool-call results are not persisted as messages in the conversation
- **Q-25 (AGT) — Delegation execution model.** When agent A delegates to agent B (`REQ-AGT-011`):
  - only the delegated task is passed to agent B
  - B’s exchanges with the LLM are not persisted into the parent conversation, neither into a separate (B-owned) conversation, or in memory
  - The user see i A’s aggregated answer only
  - B’s call does not count against the 64-message cap of the parent conversation
- **Q-26 (API) — Base path and versioning.** Endpoints qhould be served under a versioned prefix (e.g., `/api/v1/...`). This prefix must be configured centrally. They must not appear in the controller enpoints.
- **Q-27 (MCP) — Filesystem MCP base directory.** The configured base directory for the per-user folder is created on demand (first-use). The naming convention is `users/{userId}` 
- **Q-28 (NFR) — Concurrency and sizing targets.** Expected order of magnitude for: total registered users: 64, concurrent authenticated users: 64, concurrent in-flight SSE streams: 16
- **Q-29 (AUTH) — API-key lifecycle operations beyond creation.** Admin operations are also required to **list** existing API-keys (with metadata only — never the cleartext) and to **revoke** an API-key using a "disabled" flag
