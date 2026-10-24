# Future encryption API requirements


- `GET /api/admin/encryption-jobs` lists running and queued jobs, with database identity, estimated rows to process per table, processed rows per table, and running/queued job counts.
- Replace or extend the current API-token authentication with OAuth 2.0 authentication.
- NIST FF1 (FPE) is now used for new values. The default alphabet is ASCII alphanumeric; `date_of_birth`, `phone_number`, and `extension` use digits only. Punctuation and whitespace remain unchanged. Pools of 16 FF1 engines per alphabet and direction are initialized once at application startup and reused without reinitialization, supporting two active jobs with up to eight workers each. The tweak is empty and fixed; values no longer depend on column purpose, so different columns using the same alphabet produce the same ciphertext for the same plaintext.
- A single encryption job can process primary-key batches concurrently. Its optional `parallelism` field defaults to 4 and is capped at 8; each worker uses an independent JDBC connection and borrows a preinitialized FF1 engine. `maxRows` is enforced across all workers per table. Worker batches contain at most 900 IDs to remain under common JDBC `IN`-clause parameter limits.
- FF1 requires a domain of at least one million values: strings with fewer than four default-alphabet characters or fewer than six numeric characters are left unchanged. Non-ASCII characters are also left unchanged.
- FF1 values intentionally have no marker and cannot be distinguished from plaintext. Re-running a job encrypts FF1 values again; run it once against freshly generated plaintext data.
- Values encrypted with the previous purpose-derived tweak are not decryptable with the fixed-tweak engines. Existing FF1 ciphertext must not be deployed against without a migration or data regeneration plan.
- Legacy AES-256-GCM `v1:` values are excluded by encryption-job SQL and are not supported by the FF1 encryption/decryption service.

## Current implementation context

- Job status/progress is held in application memory.
- Only one active encryption job is currently permitted.
- The Gatling simulation polls one job and defaults to 3,600 polls; a full-dataset run can exceed that duration.
- PostgreSQL performance data is initialized with five synthetic tables of ten million rows each.
- Confirmed FF1 input/output formatting preference: per-column alphabet, preserve length, keep separators unchanged.
