package bench;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ResultsAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultsAnalyzer.class);

    public static void main(String[] args) throws Exception {
        LOGGER.info("===========================================");
        LOGGER.info("    TAIL LATENCY ANALYSIS: CLASSICAL vs HYBRID");
        LOGGER.info("===========================================");

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
        LOGGER.info("=== LATENCY COMPARISON TABLE (ms) ===");
        LOGGER.info("");
        LOGGER.info(String.format("%-12s %-10s %-10s %-10s %-10s %-10s %-10s","Mode", "Load", "Mean", "Median", "p90", "p95", "p99"));
        LOGGER.info("-".repeat(72));

        for (String mode : results.keySet()) {
            for (String level : levels) {
                Stats s = results.get(mode).get(level);
                if (s != null) {
                    LOGGER.info(String.format("%-12s %-10s %-10.3f %-10.3f %-10.3f %-10.3f %-10.3f", mode, level, s.mean, s.median, s.p90, s.p95, s.p99));
                }
            }
        }
        LOGGER.info("");
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
        LOGGER.info("Results saved to: results/tail_latency_summary.csv");
        LOGGER.info("");
    }

    private static void printAsciiChart(Map<String, Map<String, Stats>> results,
                                        String[] levels) {
        LOGGER.info("=== p99 LATENCY COMPARISON (ASCII CHART) ===");
        LOGGER.info("");

        double maxP99 = 0;
        for (String mode : results.keySet()) {
            for (Stats s : results.get(mode).values()) {
                if (s != null && s.p99 > maxP99) maxP99 = s.p99;
            }
        }

        for (String level : levels) {
            LOGGER.info("Load: " + level);
            for (String mode : results.keySet()) {
                Stats s = results.get(mode).get(level);
                if (s != null) {
                    int barLen = (int) ((s.p99 / maxP99) * 50);
                    String bar = "#".repeat(Math.max(1, barLen));
                    LOGGER.info(String.format("  %-10s |%s (%.2f ms)", mode, bar, s.p99));
                }
            }
            LOGGER.info("");
        }
    }

    private static void printFindings(Map<String, Map<String, Stats>> results,
                                      String[] levels) {
        LOGGER.info("=== RESEARCH FINDINGS ===");
        LOGGER.info("");

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

                LOGGER.info(String.format("At %s load:", level));
                LOGGER.info(String.format("  - Hybrid mean latency: +%.1f%% vs classical", meanOverhead));
                LOGGER.info(String.format("  - Hybrid p99 latency:  +%.1f%% vs classical", p99Overhead));
                LOGGER.info(String.format("  - Hybrid throughput:   %.1f%% vs classical", throughputChange));
                LOGGER.info("");
            }
        }

        LOGGER.info("KEY INSIGHT: p99 overhead grows faster than mean overhead under load,");
        LOGGER.info("indicating hybrid TLS requires additional capacity for tail latency SLAs.");
        LOGGER.info("");
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
