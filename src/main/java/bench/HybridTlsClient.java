package bench;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HybridTlsClient {
    private final String host;
    private final int port;
    private final String[] namedGroups;
    private final SSLContext sslContext;

    public HybridTlsClient(String host, int port, String[] namedGroups) throws Exception {
        this.host = host;
        this.port = port;
        this.namedGroups = namedGroups;
        this.sslContext = createClientContext();
    }

    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.err.println("Usage: HybridTlsClient classical|hybrid <concurrency> <runsPerThread>");
                System.err.println("Example: HybridTlsClient classical 10 100");
                System.err.println("  → 10 concurrent threads, 100 connections each = 1000 total");
                System.exit(1);
            }

            String mode = args[0].toLowerCase();
            int concurrency = Integer.parseInt(args[1]);
            int runsPerThread = Integer.parseInt(args[2]);

            String[] namedGroups;
            switch (mode) {
                case "classical":
                    namedGroups = new String[]{"x25519"};
                    break;
                case "hybrid":
                    namedGroups = new String[]{"X25519MLKEM768", "x25519"};
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            System.out.println("===========================================");
            System.out.println("Client Mode: " + mode.toUpperCase());
            System.out.println("TLS Named Groups: " + String.join(", ", namedGroups));
            System.out.println("Concurrency: " + concurrency + " threads");
            System.out.println("Runs per thread: " + runsPerThread);
            System.out.println("Total connections: " + (concurrency * runsPerThread));
            System.out.println("===========================================");

            HybridTlsClient client = new HybridTlsClient("localhost", 8443, namedGroups);
            client.runConcurrentBenchmark(concurrency, runsPerThread);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private SSLContext createClientContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String s) {}
                    public void checkServerTrusted(X509Certificate[] certs, String s) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }

    public void runConcurrentBenchmark(int concurrency, int runsPerThread) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Double> allHandshakeTimes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(concurrency);

        long benchmarkStart = System.nanoTime();

        for (int t = 0; t < concurrency; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < runsPerThread; i++) {
                        try {
                            double handshakeMs = runSingleConnection();
                            allHandshakeTimes.add(handshakeMs);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.MINUTES);
        executor.shutdown();

        long benchmarkEnd = System.nanoTime();
        double totalSeconds = (benchmarkEnd - benchmarkStart) / 1_000_000_000.0;

        printResults(allHandshakeTimes, successCount.get(), failCount.get(),
                concurrency, runsPerThread, totalSeconds);
    }

    private double runSingleConnection() throws Exception {
        SSLSocketFactory factory = sslContext.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setProtocols(new String[]{"TLSv1.3"});
            if (namedGroups != null && namedGroups.length > 0) {
                params.setNamedGroups(namedGroups);
            }
            socket.setSSLParameters(params);

            long start = System.nanoTime();
            socket.startHandshake();
            long end = System.nanoTime();

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            writer.write("hello\n");
            writer.flush();
            reader.readLine();

            return (end - start) / 1_000_000.0;
        }
    }

    private void printResults(List<Double> times, int success, int fail,
                              int concurrency, int runsPerThread, double totalSeconds) {
        if (times.isEmpty()) {
            System.err.println("No successful connections!");
            return;
        }

        Collections.sort(times);
        int n = times.size();

        double sum = 0;
        for (double t : times) sum += t;
        double mean = sum / n;

        double median = percentile(times, 50);
        double p90 = percentile(times, 90);
        double p95 = percentile(times, 95);
        double p99 = percentile(times, 99);
        double min = times.get(0);
        double max = times.get(n - 1);

        double throughput = success / totalSeconds;

        System.out.println();
        System.out.println("===========================================");
        System.out.println("           BENCHMARK RESULTS");
        System.out.println("===========================================");
        System.out.println();
        System.out.printf("Connections: %d success, %d failed%n", success, fail);
        System.out.printf("Duration: %.2f seconds%n", totalSeconds);
        System.out.printf("Throughput: %.2f connections/sec%n", throughput);
        System.out.println();
        System.out.println("--- Handshake Latency (ms) ---");
        System.out.printf("  Min:    %.3f%n", min);
        System.out.printf("  Mean:   %.3f%n", mean);
        System.out.printf("  Median: %.3f%n", median);
        System.out.printf("  p90:    %.3f%n", p90);
        System.out.printf("  p95:    %.3f%n", p95);
        System.out.printf("  p99:    %.3f%n", p99);
        System.out.printf("  Max:    %.3f%n", max);
        System.out.println();
        System.out.println("===========================================");

        // CSV output for easy parsing
        System.out.println();
        System.out.println("CSV_OUTPUT:");
        System.out.printf("concurrency,runs,success,fail,mean_ms,median_ms,p90_ms,p95_ms,p99_ms,max_ms,throughput%n");
        System.out.printf("%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f%n",
                concurrency, runsPerThread, success, fail, mean, median, p90, p95, p99, max, throughput);
    }

    private double percentile(List<Double> sortedList, double p) {
        int n = sortedList.size();
        double rank = (p / 100.0) * (n - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sortedList.get(low);
        double weight = rank - low;
        return sortedList.get(low) * (1 - weight) + sortedList.get(high) * weight;
    }
}
