# Performance experiments

| Date (local) | Database | Dataset | Columns encrypted | Algorithm | Job duration | Gatling run | Result |
|---|---|---|---|---|---|---|---|
| 2026-10-24 (EDT) | PostgreSQL 18 | 40,000 rows per table across `contacts`, `contact_addresses`, `contact_phones`, `contact_emails`, and `contact_relationships` (200,000 total) | `display_name`, `date_of_birth`, `street`, `locality`, `postal_code`, `phone_number`, `extension`, `email_address`, `notes` | NIST FF1 (AES-256), fixed empty tweak, no value marker | 8.397 s | About 13 s; 4 successful HTTP requests (one start and three polls), zero failures. | Gatling observed `SUCCEEDED`; 200,000 rows processed with `parallelism: 4`. The same first 10,000 rows per table were encrypted for a third time; they now require three inverse FF1 passes to restore their original plaintext. The next 30,000 rows per table were encrypted for the first time in this run. |
| 2026-10-24 (EDT) | PostgreSQL 18 | Same 10,000 rows per table across `contacts`, `contact_addresses`, `contact_phones`, `contact_emails`, and `contact_relationships` as the prior run | `display_name`, `date_of_birth`, `street`, `locality`, `postal_code`, `phone_number`, `extension`, `email_address`, `notes` | NIST FF1 (AES-256), fixed empty tweak, no value marker | 9.15 s | About 13 s; 3 successful HTTP requests (one start and two polls), zero failures. The job group duration was 9,150 ms. | 50,000 rows processed with `parallelism: 4`. The same rows were deliberately encrypted again at the user's request; this applied FF1 a second time, so one decryption no longer recovers the original plaintext. A second inverse pass with the same key and parameters would be required. No ciphertext format marker exists to identify them. |
| 2026-10-24 (EDT) | PostgreSQL 18 | 10,000 rows per table across `contacts`, `contact_addresses`, `contact_phones`, `contact_emails`, and `contact_relationships` | `display_name`, `date_of_birth`, `street`, `locality`, `postal_code`, `phone_number`, `extension`, `email_address`, `notes` | NIST FF1 (AES-256), fixed empty tweak, no value marker | About 8.4 s | About 13 s; 4 successful HTTP requests (one start and three polls), zero failures. Latency: median 8 ms, p95/max 8,387 ms. | 50,000 rows processed; 80,000 values transformed. This run used the updated engine, initialized once at application startup and reused instead of being initialized for every value/request. Gatling observed `SUCCEEDED`. |
| 2026-10-12 (EDT) | PostgreSQL 18 | 10,000 rows per table across `contacts`, `contact_addresses`, `contact_phones`, `contact_emails`, and `contact_relationships` | `display_name`, `date_of_birth`, `street`, `locality`, `postal_code`, `phone_number`, `extension`, `email_address`, `notes` | NIST FF1 (AES-256), no value marker | 3.16 s (23:05:10-23:05:13 UTC) | Not used; direct API run. | 50,000 rows processed; 80,000 values transformed. Numeric extensions have only three digits, below the FF1 minimum domain size, and remain unchanged. Sampled values retain length and separators; row 10,001 remained plaintext in every table. |
| 2026-10-12 (EDT) | PostgreSQL 18 | 10,000 rows per table across `contacts`, `contact_addresses`, `contact_phones`, `contact_emails`, and `contact_relationships` | `display_name`, `date_of_birth`, `street`, `locality`, `postal_code`, `phone_number`, `extension`, `email_address`, `notes` | AES-256-GCM | 3.47 s (14:50:44-14:50:47 UTC) | Not used; direct API run. | `maxRows: 10000` applied independently to each table; 50,000 rows and 90,000 values encrypted. Verified row 10,001 remained unencrypted in every table. |
| 2026-10-12 (EDT) | PostgreSQL 18 | 10,000,000 synthetic rows in `contacts` | `display_name`, `date_of_birth` | AES-256-GCM | About 1 h 32 min 25 s, from approximately 12:58:25 UTC to completion observed at 14:30:50 UTC | Ran for about 1 h 0 min 14 s; 3,601 successful HTTP requests (one start request and 3,600 polls), zero failures. Latency: median 1 ms, p95 3 ms, p99 5 ms, maximum 88 ms. | Server-side encryption continued after Gatling stopped polling and completed all 10,000,000 rows. No targeted plaintext rows remained. |

## Run artifact

| Item | Value |
|---|---|
| Latest Gatling report | `target/gatling/encryptionapisimulation-20261024115158650/index.html` |
| Gatling command duration | About 13 s |
| Completion time | 2026-10-24 11:52:12 UTC (07:52:12 EDT) |
| Processed rows | 200,000 across five tables, 40,000 per table |
| Parallelism | 4 workers within the job |
| Data note | The first 10,000 rows per table had already been encrypted twice and were encrypted for a third time in this run. |
| Previous Gatling report | `target/gatling/encryptionapisimulation-20261024114732687/index.html` |
| Previous Gatling command duration | About 13 s |
| Previous completion time | 2026-10-24 11:48:02 UTC (07:48:02 EDT) |
| Previous processed rows | 50,000 across five tables |
| Previous data note | The first 10,000 rows per table had already been encrypted once and were encrypted for a second time in that run. |
| Earlier same-day Gatling report | `target/gatling/encryptionapisimulation-20261024112701888/index.html` |
| Earlier same-day Gatling command duration | About 13 s |
| Earlier same-day completion time | 2026-10-24 11:27:13 UTC (07:27:13 EDT) |
| Configuration note | Updated FF1 engines were initialized once when the application started and reused during the job. |
| 2026-10-12 Gatling report | `target/gatling/encryptionapisimulation-20261012125825475/index.html` |
| 2026-10-12 Gatling command duration | 3,614.73 s (about 1 h 0 min 15 s) |
| 2026-10-12 completion time | 2026-10-12 14:30:50 UTC (10:30:50 EDT) |
