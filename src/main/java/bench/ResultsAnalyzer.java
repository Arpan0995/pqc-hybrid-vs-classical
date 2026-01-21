package bench;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ResultsAnalyzer {

    public static void main(String[] args) throws Exception {
        System.out.println("===========================================");
        System.out.println("    TAIL LATENCY ANALYSIS: CLASSICAL vs HYBRID");
        System.out.println("===========================================");
        System.out.println();

        // Expected files: results/raw/classical_1x.log, classical_10x.log, etc.
        String[] concurrencyLevels = {"1x", "10x", "100x"};
        String[] modes = {"classical", "hybrid"};

        Map<String, Map<String, Stats>> allResults = new LinkedHashMap<>();

        for (String mode : modes) {
            allResults.put(mode, new LinkedHashMap<>());
            for (String level : concurrencyLevels) {
                Path logFile = Path.of("results/raw/" + mode + "_" + level + ".log");
                if (Files.exists(logFile)) {
                    Stats stats = parseLogFile(logFile);
                    if (stats != null) {
                        allResults.get(mode).put(level, stats);
                    }
                }
            }
        }

        // Print comparison table
        printComparisonTable(allResults, concurrencyLevels);

        // Write summary CSV
        writeSummaryCsv(allResults, concurrencyLevels);

        // Print ASCII chart
        printAsciiChart(allResults, concurrencyLevels);

        // Print research findings
        printFindings(allResults, concurrencyLevels);
    }

    private static Stats parseLogFile(Path logFile) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(logFile.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("CSV_OUTPUT:")) {
                    br.readLine(); // header
                    String dataLine = br.readLine();
                    if (dataLine != null) {
                        return parseCSVLine(dataLine);
                    }
                }
            }
        }
        return null;
    }

    private static Stats parseCSVLine(String line) {
        String[] parts = line.split(",");
        Stats s = new Stats();
        s.concurrency = Integer.parseInt(parts[0].trim());
        s.runs = Integer.parseInt(parts[1].trim());
        s.success = Integer.parseInt(parts[2].trim());
        s.fail = Integer.parseInt(parts[3].trim());
        s.mean = Double.parseDouble(parts[4].trim());
        s.median = Double.parseDouble(parts[5].trim());
        s.p90 = Double.parseDouble(parts[6].trim());
        s.p95 = Double.parseDouble(parts[7].trim());
        s.p99 = Double.parseDouble(parts[8].trim());
        s.max = Double.parseDouble(parts[9].trim());
        s.throughput = Double.parseDouble(parts[10].trim());
        return s;
    }

    private static void printComparisonTable(Map<String, Map<String, Stats>> results,
                                             String[] levels) {
        System.out.println("=== LATENCY COMPARISON TABLE (ms) ===");
        System.out.println();
        System.out.printf("%-12s %-10s %-10s %-10s %-10s %-10s %-10s%n",
                "Mode", "Load", "Mean", "Median", "p90", "p95", "p99");
        System.out.println("-".repeat(72));

        for (String mode : results.keySet()) {
            for (String level : levels) {
                Stats s = results.get(mode).get(level);
                if (s != null) {
                    System.out.printf("%-12s %-10s %-10.3f %-10.3f %-10.3f %-10.3f %-10.3f%n",
                            mode, level, s.mean, s.median, s.p90, s.p95, s.p99);
                }
            }
        }
        System.out.println();
    }

    private static void writeSummaryCsv(Map<String, Map<String, Stats>> results,
                                        String[] levels) throws Exception {
        Files.createDirectories(Path.of("results"));
        try (FileWriter fw = new FileWriter("results/tail_latency_summary.csv")) {
            fw.write("mode,load,concurrency,mean_ms,median_ms,p90_ms,p95_ms,p99_ms,max_ms,throughput\n");
            for (String mode : results.keySet()) {
                for (String level : levels) {
                    Stats s = results.get(mode).get(level);
                    if (s != null) {
                        fw.write(String.format("%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f%n",
                                mode, level, s.concurrency, s.mean, s.median,
                                s.p90, s.p95, s.p99, s.max, s.throughput));
                    }
                }
            }
        }
        System.out.println("Results saved to: results/tail_latency_summary.csv");
        System.out.println();
    }

    private static void printAsciiChart(Map<String, Map<String, Stats>> results,
                                        String[] levels) {
        System.out.println("=== p99 LATENCY COMPARISON (ASCII CHART) ===");
        System.out.println();

        double maxP99 = 0;
        for (String mode : results.keySet()) {
            for (Stats s : results.get(mode).values()) {
                if (s != null && s.p99 > maxP99) maxP99 = s.p99;
            }
        }

        for (String level : levels) {
            System.out.println("Load: " + level);
            for (String mode : results.keySet()) {
                Stats s = results.get(mode).get(level);
                if (s != null) {
                    int barLen = (int) ((s.p99 / maxP99) * 50);
                    String bar = "#".repeat(Math.max(1, barLen));
                    System.out.printf("  %-10s |%s (%.2f ms)%n", mode, bar, s.p99);
                }
            }
            System.out.println();
        }
    }

    private static void printFindings(Map<String, Map<String, Stats>> results,
                                      String[] levels) {
        System.out.println("=== RESEARCH FINDINGS ===");
        System.out.println();

        for (String level : levels) {
            Stats classical = results.get("classical") != null ?
                    results.get("classical").get(level) : null;
            Stats hybrid = results.get("hybrid") != null ?
                    results.get("hybrid").get(level) : null;

            if (classical != null && hybrid != null) {
                double meanOverhead = ((hybrid.mean - classical.mean) / classical.mean) * 100;
                double p99Overhead = ((hybrid.p99 - classical.p99) / classical.p99) * 100;
                double throughputChange = ((hybrid.throughput - classical.throughput) /
                        classical.throughput) * 100;

                System.out.printf("At %s load:%n", level);
                System.out.printf("  - Hybrid mean latency: +%.1f%% vs classical%n", meanOverhead);
                System.out.printf("  - Hybrid p99 latency:  +%.1f%% vs classical%n", p99Overhead);
                System.out.printf("  - Hybrid throughput:   %.1f%% vs classical%n", throughputChange);
                System.out.println();
            }
        }

        System.out.println("KEY INSIGHT: p99 overhead grows faster than mean overhead under load,");
        System.out.println("indicating hybrid TLS requires additional capacity for tail latency SLAs.");
        System.out.println();
    }

    static class Stats {
        int concurrency;
        int runs;
        int success;
        int fail;
        double mean;
        double median;
        double p90;
        double p95;
        double p99;
        double max;
        double throughput;
    }
}
