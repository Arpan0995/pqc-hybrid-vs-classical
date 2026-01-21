Java-PQC-Hybrid
=================

Overview
--------
What we tested and why
- This project compares two TLS key-exchange configurations: a classical-only configuration (using X25519) and a hybrid configuration (a hybrid named-group that pairs a post-quantum primitive with a classical primitive). The goal was to measure functional compatibility and the runtime performance impact (handshake latency and basic throughput) of using a hybrid key-exchange versus a classical-only key-exchange.
- Purpose: quantify the additional handshake cost (average and tail latency) and verify whether hybrid negotiation behaves differently (compatibility/fallback) compared to classical-only setups. These results help assess whether hybrid TLS is practical from a latency and interoperability perspective.

What the repository contains
- `bench.HybridTlsServer` — a small TLS server that can run in `classical` or `hybrid` modes and logs handshake timing and negotiated parameters.
- `bench.HybridTlsClient` — a client benchmark that runs handshakes (single or concurrent) and prints aggregated CSV output suitable for automated analysis.
- `bench.ResultsAnalyzer` — simple log/C SV parser that summarizes the CSV output and prints basic comparisons.

Prerequisites (high level)
-------------------------
- Java 17+ (or the JDK you intend to measure). Use the same JDK for all experiments to ensure fair comparisons.
- Maven (or use the included `./mvnw` wrapper) to build and run tests.
- For local tests we used BouncyCastle in tests (Maven handles this dependency).

How to create a JKS keystore (test key)
----------------------------------------
For local testing you can create a simple self-signed JKS keystore using `keytool`. The server looks for `server.keystore` in the working directory and then `target/server.keystore` (useful for tests). The default password expected by the code is `changeit` unless you override `keystore.password` in `src/main/resources/application.properties`.

Create a keystore (recommended for local manual runs):

```bash
# create keystore in project root
keytool -genkeypair \
  -alias server \
  -keyalg RSA -keysize 2048 \
  -keystore server.keystore \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost" \
  -validity 365
```

Alternatively, create it under `target/` (the test harness uses this location when present):

```bash
mkdir -p target
keytool -genkeypair \
  -alias server \
  -keyalg RSA -keysize 2048 \
  -keystore target/server.keystore \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost" \
  -validity 365
```

Notes:
- For real experiments, use proper certificates and private keys (not the above self-signed test key).
- If you change the keystore password, set `keystore.password` in `src/main/resources/application.properties` or provide a keystore file that the code can load.

How to run the server and client (examples)
-------------------------------------------
Build the project:

```bash
./mvnw -q package
```

Start the server (example: classical mode on default port 8443):

```bash
# start server and save console output to results/raw/server_classical.log
java -cp target/classes bench.HybridTlsServer classical 8443 > results/raw/server_classical.log 2>&1 &
```

Start the client (single run, classical):

```bash
# run single handshake (defaults to concurrency=1 and runsPerThread=1)
java -cp target/classes bench.HybridTlsClient classical > results/raw/client_classical.log 2>&1
```

Run the client with concurrency and runs per thread:

```bash
# 10 threads * 100 runs each = 1000 handshakes
java -cp target/classes bench.HybridTlsClient classical 10 100 > results/raw/client_classical_10x.log 2>&1
```

To run hybrid mode (if supported by your JDK's named-group support):

```bash
java -cp target/classes bench.HybridTlsServer hybrid 8443 > results/raw/server_hybrid.log 2>&1 &
java -cp target/classes bench.HybridTlsClient hybrid  > results/raw/client_hybrid.log 2>&1
```

Important logging notes
- All code uses SLF4J for logging. The project does not ship a logging backend by default — if you want structured file logging (rather than shell redirection), add a SLF4J binding such as Logback to the classpath and supply a configuration (logback.xml).
- The server prints the supported named groups at startup (INFO) so you can verify the exact label strings your JVM supports.

Where the code sets the named-group label
----------------------------------------
- `HybridTlsServer.main` and `HybridTlsClient.main` map the logical mode names to the TLS named-group strings. By default the code uses:
  - classical: `x25519`
  - hybrid: `X25519MLKEM768`, `x25519`

If your JDK uses different labels for the hybrid group, change these strings in the two main methods to exactly match the names reported by your JVM.

Test methodology (what we ran and what we verified)
--------------------------------------------------
This section documents exactly what tests were implemented and executed against this codebase and the outcomes observed (past tense):

- Unit-level checks: we created unit tests that validated the server's SSLContext creation and the ability to obtain an SSLServerSocketFactory. These tests ensured the code paths that load keystores and initialize key managers behaved as expected.

- Keystore validation: one test programmatically generated a JKS keystore (using BouncyCastle) and wrote it to `target/server.keystore`. The test invoked the server's keystore-loading helper and asserted an SSLContext was returned, confirming the keystore loading path worked with a generated certificate.

- Integration smoke test: we implemented an integration-style smoke test that:
  - Wrote a temporary keystore into `target/server.keystore`.
  - Started `HybridTlsServer` in a background thread on an ephemeral port.
  - Ran `HybridTlsClient` to perform a single handshake against the running server for both `classical` and `hybrid` modes; the test programmatically checked the JVM's supported named-groups and skipped a mode when the named-group required for that mode was not present on the running JVM.
  - Verified that the client did not throw exceptions during the handshake and that the server recorded handshake timing and negotiated parameters.

- Logging & diagnostics: during the smoke tests we used the server logs (which list supported named groups at startup) and the client's CSV output lines to confirm successful negotiation and to capture handshake timings for analysis.

Test results (summary)
----------------------
- The code compiled and the tests we added in this workspace passed: `./mvnw test` produced a successful build.
- The integration smoke test completed a successful classical handshake; hybrid mode was exercised only when the running JVM reported support for the hybrid named-group.

Data capture and analysis
-------------------------
- Client runs produced `CSV_OUTPUT:` lines with aggregated metrics (mean/median/p90/p95/p99/max and throughput) which were parsed by `ResultsAnalyzer` to create `results/tail_latency_summary.csv` for analysis.

CSV schema (what to expect in the `CSV_OUTPUT:` line)
-----------------------------------------------------
The client prints a CSV line with the following fields (header `concurrency,runs,success,fail,mean_ms,median_ms,p90_ms,p95_ms,p99_ms,max_ms,throughput`):

- concurrency: number of client threads
- runs: runs per thread
- success: successful handshakes
- fail: failed handshakes
- mean_ms: mean handshake latency in milliseconds
- median_ms: median
- p90_ms, p95_ms, p99_ms: percentile latencies
- max_ms: maximum observed latency
- throughput: connections per second

Troubleshooting
---------------
- If you see `missing_extension` or other handshake alert errors, check supported named groups printed by the server. The hybrid group label must match exactly what the JVM supports.
- If the client prints `No successful connections!` check server logs and ensure the keystore exists and the server is listening on the expected port.

Appendix: Example quick-run sequence
-----------------------------------
1) Build:
```bash
./mvnw -q package
```

2) Create a temporary keystore (if not already present):
```bash
keytool -genkeypair -alias server -keyalg RSA -keystore target/server.keystore -storepass changeit -keypass changeit -dname "CN=localhost" -validity 365
```

3) Start server (hybrid) and capture logs:
```bash
java -cp target/classes bench.HybridTlsServer hybrid 8443 > results/raw/server_hybrid.log 2>&1 &
```

4) Run a quick client run (classical):
```bash
java -cp target/classes bench.HybridTlsClient classical > results/raw/client_classical.log 2>&1
```

5) Analyze results:
```bash
java -cp target/classes bench.ResultsAnalyzer
# or open results/raw/*.log and inspect CSV_OUTPUT lines, then run the analyzer
```
