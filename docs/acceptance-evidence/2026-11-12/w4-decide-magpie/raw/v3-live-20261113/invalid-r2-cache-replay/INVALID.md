# INVALID: worker idempotency cache replay

This run is not a live model round. 32/32 rawOutput rows are byte-identical to r1
and latency is 4.6-18.6 ms. The worker replayed the 10-minute idempotency cache
for identical (runId, attempt, turnNo, snapshotHash). It must not be counted
toward the two-round gate or used as PASS evidence.

A genuine second round was run separately after TTL expiry under rounds/r2-valid.
