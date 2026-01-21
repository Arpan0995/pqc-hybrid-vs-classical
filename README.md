Java-PQC-Hybrid
=================

Overview
--------
This repository contains a small Java benchmark harness for comparing classical TLS key-exchange (e.g. X25519) against hybrid and PQC-enabled key-exchange groups (MLKEM variants). The project provides:

- A minimal TLS server (`bench.HybridTlsServer`) that can be started in `classical`, `hybrid`, or `pqc` modes and accepts concurrent connections.
- A client benchmarker (`bench.HybridTlsClient`) that runs handshakes (single or concurrent) and prints per-run timing and a CSV summary line labeled `CSV_OUTPUT:` for easy parsing.
- A small `ResultsAnalyzer` utility to read CSV output from saved logs and produce summary tables and an ASCII chart.

This README explains the prerequisites, how to create a test keystore, common run commands, and the recommended test methodology to produce reproducible comparisons.

Prerequisites (high level)
-------------------------
- Java 17+ or a modern JDK (the project was compiled against Java 21 in CI runs). Using the same JDK for all experiments is important for fair comparisons.
- Maven (or use the included `./mvnw` wrapper) to build and run tests.
- Optional: a JDK or vendor build that exposes PQC/hybrid named-groups (if you want to exercise `hybrid` or `pqc` modes end-to-end). If the JDK doesn't support those named groups, the server will log the supported groups and the integration test will skip modes it cannot run.
- (Tests) BouncyCastle is used by the test utilities to generate in-memory keystores when running tests; Maven handles this dependency.

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

Important logging notes
- All code now uses SLF4J for logging. The project does not ship a logging backend by default — if you want structured file logging (rather than shell redirection), add a SLF4J binding such as Logback to the classpath and supply a configuration (logback.xml).
- The server prints the supported named groups at startup (INFO) so you can verify exact PQC/hybrid label strings supported by your JVM.

Test methodology (recommended)
------------------------------
Goal: measure handshake latency (and optionally throughput) for classical, hybrid, and PQC modes with reliable statistics.

1) Environment control
- Use the same JDK binary and identical JVM flags for all modes.
- Run on the same hardware (or same cloud instance snapshot) to minimize noise.
- Prefer to run tests on an otherwise idle machine and disable frequency scaling where possible.

2) Warm-up
- Warm up the JVM to let JIT compile hot paths: run ~100 warm-up handshakes before measurements.

3) Iterations & concurrency
- For latency: run many sequential handshakes (e.g., 1 thread × 500–5000 runs) to compute per-run latency distribution.
- For throughput & tail behaviour: run concurrent clients (e.g., 10/50/100 threads) with several hundred runs each.
- Use multiple independent trials and randomize the order of modes (classical/hybrid/pqc) across trials to avoid drift bias.

4) Metrics to collect
- Per-run handshake latency (measured with System.nanoTime — both client and server record timing in code). The client prints a `CSV_OUTPUT:` line that includes aggregated metrics suitable for ingestion.
- Negotiated TLS protocol and cipher suite (logged by server/client per-connection).
- Aggregate stats: min, mean, median, p90, p95, p99, max, and throughput (connections/sec).
- Optional: process-level CPU/memory during experiments for resource comparison.

5) Data capture & analysis
- Redirect the console output to files under `results/raw/` (see examples above). Each client run writes a `CSV_OUTPUT:` line to stdout which the `ResultsAnalyzer` (or any CSV tool) can parse.
- Use the provided `ResultsAnalyzer` (`bench.ResultsAnalyzer`) to summarize `results/raw/*` logs and write `results/tail_latency_summary.csv`.

6) Reproducibility checklist
- Keep a short text file that records: JDK vendor/version, OS, hardware, JVM flags, keystore used, experiment command lines, and the timestamp for each trial.
- Run each mode several times (3–5 trials) and report median-of-trials for stability.

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
- If you see `missing_extension` or other handshake alert errors, check supported named groups printed by the server. The hybrid/PQC group label must match exactly what the JVM supports.
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

If you want, I can add:
- A `logback.xml` and Logback dependency so the app writes logs to `results/raw` automatically (no shell redirection needed).
- A small convenience script (`scripts/run_experiment.sh`) that wraps keystore creation, server start, client runs, and cleanup.

---

README created at: `README.md` in the project root. I ran the Maven test build afterwards — the project still builds successfully. Would you like me to (1) add Logback and a default `logback.xml` to send server/client logs automatically to `results/raw/`, or (2) add the convenience run script next? Pick one (or both) and I'll implement it and re-run the verification steps.
