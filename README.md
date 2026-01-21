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

Test methodology
----------------
This section explains how we performed the handshake-latency experiments (the exact procedure used to produce the CSV outputs and summary data), not the unit/integration test cases.

1) Environment and repeatability
- Use the same JDK and machine for all modes to keep results comparable.
- Record the JDK vendor/version, OS, CPU, and any JVM flags used.
- Run each experiment in a quiet environment (minimal other load) and repeat each configuration across multiple independent trials.

2) Keystore and server setup
- Ensure a test keystore is available at `server.keystore` (or `target/server.keystore`). Use the keytool commands earlier in this README to create one.
- Start the server in the chosen mode and port, redirecting logs to a file under `results/raw/` so you capture the server `INFO` lines (supported named groups and handshake diagnostics). Example (hybrid mode):

```bash
java -cp target/classes bench.HybridTlsServer hybrid 8443 > results/raw/server_hybrid.log 2>&1 &
```

3) Warm-up pass
- Perform a warm-up run before collecting measurements to let the JVM JIT optimize hot code paths. A recommended warm-up is 100 handshakes using the same client invocation you will use for measurement.

4) Measurement parameters
- Choose a set of concurrency levels and runs-per-thread to measure under different load shapes. Typical example sets we used:
  - Concurrency levels: 1, 10, 100
  - Runs per thread: 500 (for latency distributions) and 1000 (for throughput/tail under load)
- For each (mode, concurrency, runs) configuration, run multiple independent trials (we used 3–5 trials) and randomize the sequence of modes across trials to reduce time-related bias.

5) Running the client to collect data
- Use `HybridTlsClient` to perform the handshakes; redirect its console output to `results/raw/` so each run's `CSV_OUTPUT:` line is preserved. Example single-run client command:

```bash
java -cp target/classes bench.HybridTlsClient classical 10 500 > results/raw/client_classical_10x_1.log 2>&1
```

- The client prints per-trial console lines and a single CSV summary line with the header `concurrency,runs,success,fail,mean_ms,median_ms,p90_ms,p95_ms,p99_ms,max_ms,throughput` which `ResultsAnalyzer` can parse.

6) Repeat and rotate
- Repeat the above client run for the configured number of trials. Between trials you may restart the server to avoid persistent state effects. When possible, alternate the order of modes (e.g., run hybrid first in some trials and classical first in others).

7) Aggregate and analyze
- After collecting logs under `results/raw/`, either:
  - Run the included analyzer: `java -cp target/classes bench.ResultsAnalyzer` — it will read `results/raw/*` and write `results/tail_latency_summary.csv`, and print comparison summaries.
  - Or extract the CSV_OUTPUT lines manually and aggregate them (e.g., with Python/pandas) to compute median-of-trials and confidence intervals.

8) Metrics and interpretation
- Primary latency metrics: mean, median, p90, p95, p99, and max (ms). We focused on p99 (tail latency) to understand worst-case behavior under load.
- Throughput: connections per second (aggregated across threads and runs).
- Interpretation: compare classical vs hybrid on the same machine/JVM flags, and report relative overheads (percent change) for mean and p99.

Experiment configurations used
-----------------------------
These are the exact configurations and commands we used during the experiments described in this project. Use them as a reference to reproduce the results.

- Warm-up (one per mode):

```bash
# warm up hybrid (100 sequential handshakes)
java -cp target/classes bench.HybridTlsClient hybrid 1 100 > results/raw/client_hybrid_warmup.log 2>&1
```

- Latency-focused runs (single-thread, many runs):

```bash
# classical latency distribution: 1 thread × 500 runs
java -cp target/classes bench.HybridTlsClient classical 1 500 > results/raw/client_classical_1x_500.log 2>&1

# hybrid latency distribution: 1 thread × 500 runs
java -cp target/classes bench.HybridTlsClient hybrid 1 500 > results/raw/client_hybrid_1x_500.log 2>&1
```

- Concurrency-focused runs (throughput and tail under load):

```bash
# classical throughput: 10 threads × 1000 runs each
java -cp target/classes bench.HybridTlsClient classical 10 1000 > results/raw/client_classical_10x_1000.log 2>&1

# hybrid throughput: 10 threads × 1000 runs each
java -cp target/classes bench.HybridTlsClient hybrid 10 1000 > results/raw/client_hybrid_10x_1000.log 2>&1
```

- High-concurrency stress runs (if resources permit):

```bash
# classical stress: 100 threads × 500 runs
java -cp target/classes bench.HybridTlsClient classical 100 500 > results/raw/client_classical_100x_500.log 2>&1

# hybrid stress: 100 threads × 500 runs
java -cp target/classes bench.HybridTlsClient hybrid 100 500 > results/raw/client_hybrid_100x_500.log 2>&1
```

- Trials and rotation pattern used in the study:
  - For each configuration above we ran 3 independent trials.
  - Trial order was randomized per configuration; server was restarted between trials to reduce carry-over effects.

9) Example end-to-end routine (one configuration)
- Build:

```bash
./mvnw -q package
```

- Create keystore if needed:

```bash
keytool -genkeypair -alias server -keyalg RSA -keystore target/server.keystore -storepass changeit -keypass changeit -dname "CN=localhost" -validity 365
```

- Start server (hybrid) and capture logs:

```bash
java -cp target/classes bench.HybridTlsServer hybrid 8443 > results/raw/server_hybrid.log 2>&1 &
```

- Warm-up (100 handshakes):

```bash
java -cp target/classes bench.HybridTlsClient hybrid 1 100 > results/raw/client_hybrid_warmup.log 2>&1
```

- Measurement run (e.g., 10 threads × 500 runs):

```bash
java -cp target/classes bench.HybridTlsClient hybrid 10 500 > results/raw/client_hybrid_10x_500.log 2>&1
```

- Stop/restart server if desired and repeat for classical mode.

10) Notes on variations and caution
- If hybrid negotiation is not supported by the JVM (server logs will show supported named groups), the hybrid attempt may fall back or the handshake may fail — ensure the server log confirms negotiation parameters.
- For high-concurrency runs you may need to tune the server's thread pool or OS limits (file descriptors) to avoid unrelated resource limitations.

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
