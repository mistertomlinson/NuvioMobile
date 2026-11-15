# Rewatch client/server contract audit

This harness compiles the actual replay planner, queue, API, and response parser
from both repositories and sends their requests to the actual VPS implementation
on loopback. It uses a temporary database and fixed fake device credentials. It
does not contact Simkl, the public VPS, or installed applications.

The fixture reaches revision 666 using real store transitions. The iPad client
repairs a revision-1 `PROGRESS` queue and selects S5E16. Android independently
reads S5E16 at 34 seconds, then repairs a stale `REPOSITION` queue and selects
S5E15. The iPad independently reads that result. Both repairs verify exact
archival of the original events, a drained queue, a verified clean baseline, and
progress saved between rebase and upload.

Run with Python 3, Node 20+, Java 21, and a prepared compiler/dependency cache:

```sh
python3 scripts/rewatch-contract-audit/run_audit.py \
  --tv-repo /path/to/NuvioTV \
  --server-repo /path/to/NuvioMobile-server-branch \
  --runtime /path/to/runtime-cache
```

The server source is `services/rewatch-sync` at
`e81369088351a58f511a33acb77b59e343831d7d`. Its five module hashes were compared
with the running staging service before the audit.

The cache contains these official Kotlin distributions and Maven artifacts:

| Path | Version |
| --- | --- |
| `kotlinc/` | Kotlin compiler 2.3.0 |
| `ios-common/kotlinc/` | Kotlin compiler 2.4.10 |
| `gson.jar` | Gson 2.10.1 |
| `tv-common/coroutines.jar` | kotlinx-coroutines-core-jvm 1.8.1 |
| `tv-common/serialization-core.jar`, `serialization-json.jar` | kotlinx-serialization JVM 1.8.0 |
| `ios-common/coroutines.jar` | kotlinx-coroutines-core-jvm 1.10.2 |
| `ios-common/serialization-core.jar`, `serialization-json.jar` | kotlinx-serialization JVM 1.10.0 |

This proves the shared replay protocol and queue behavior. It does not replace
Android application compilation, iOS native compilation, installation, or the
physical-device synchronization check.
