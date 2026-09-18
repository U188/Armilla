# Conversation turn lifecycle

- `turnId` denotes a user logical turn; provider request `round` is not a user turn.
- Pause/resume and accepted steering retain `turnId`. An execution continuation can change `runId` without changing `turnId`.
- User stop cancels resources immediately, closes the UI running state, then commits the stopped worker's transcript through the durable result outbox. A new send/revision waits for this commit; its text is not discarded.
- Stopped history contains completed assistant/tool records and acknowledged queued supplements. A missing tool result is marked unknown, never invented as success or blindly replayed. Sensitive tool data remains redacted.
- Failure is terminal. Clicking Continue after a terminal failure creates a new `runId` and `turnId`; the failed turn remains in history and is closed in result grouping.
- Internal bounded HTTP retries remain in the current logical turn. Only live retry-wait events classify stop-during-retry; old notices are not live state.
- Revision/branch lookup uses turn identity and user content, not differing UI/history message counts. A missing supplement fails closed instead of cutting away an unrelated original question.

Regression tests cover deferred stop commit, commit/stop races, queued supplements, partial response retention, interrupted tool batches, revision misalignment, old retry notices, and failure/result boundaries.
