package uk.ac.ed.inf.infraq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.ac.ed.inf.infraq.dto.BenchmarkConfig;
import uk.ac.ed.inf.infraq.repository.BenchmarkRepository;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manages benchmark runs: submits N requests, tracks completion, computes stats.
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final InferenceService inferenceService;
    private final BenchmarkRepository benchRepo;
    private final RedisService redis;

    // Diverse prompt set for benchmarking — some duplicated to test caching
    private static final List<String> BENCHMARK_PROMPTS = List.of(
        "Explain the concept of cloud computing in 2-3 sentences.",
        "What is the difference between SQL and NoSQL databases?",
        "Describe microservices architecture and its key benefits.",
        "What are containers and why are they useful in software deployment?",
        "Explain the CAP theorem in simple terms.",
        "What is a message queue and when should you use one?",
        "Describe the producer-consumer pattern in distributed systems.",
        "What is the purpose of a load balancer?",
        "Explain the concept of database sharding.",
        "What is continuous integration and continuous deployment?",
        "Describe the differences between REST and GraphQL APIs.",
        "What is caching and how does it improve system performance?",
        "Explain the concept of eventual consistency.",
        "What is a reverse proxy and how does it work?",
        "Describe the benefits of infrastructure as code.",
        // Intentional duplicates to test cache hit behavior
        "Explain the concept of cloud computing in 2-3 sentences.",
        "What is the difference between SQL and NoSQL databases?",
        "Describe microservices architecture and its key benefits.",
        "What are containers and why are they useful in software deployment?",
        "Explain the CAP theorem in simple terms."
    );

    public BenchmarkService(InferenceService inferenceService,
                            BenchmarkRepository benchRepo, RedisService redis) {
        this.inferenceService = inferenceService;
        this.benchRepo = benchRepo;
        this.redis = redis;
    }

    /**
     * Start a new benchmark run. Submits requests and tracks them asynchronously.
     */
    public String start(BenchmarkConfig config) {
        String benchId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        // Read actual active strategy from Redis (set via Metrics config panel)
        // The worker must be restarted after changing strategy for it to take effect.
        String activeStrategy = redis.getOrDefault("config:strategy", "continuous");
        String activeSlots = redis.getOrDefault("config:num_slots", "4");
        String label = activeStrategy + "_" + activeSlots + "slots";

        benchRepo.createRun(benchId, label, activeStrategy,
                Integer.parseInt(activeSlots), config.getNumRequests(), now);

        // Run benchmark in background thread
        CompletableFuture.runAsync(() -> runBenchmarkAsync(benchId, config));
        return benchId;
    }

    private void runBenchmarkAsync(String benchId, BenchmarkConfig config) {
        try {
            // Submit all requests
            List<String> requestIds = new ArrayList<>();
            for (int i = 0; i < config.getNumRequests(); i++) {
                String prompt = BENCHMARK_PROMPTS.get(i % BENCHMARK_PROMPTS.size());
                String reqId = inferenceService.submit(prompt, "chat", 0);
                benchRepo.linkRequest(benchId, reqId);
                requestIds.add(reqId);
            }

            log.info("Benchmark {} started: {} requests, strategy={}, slots={}",
                    benchId, requestIds.size(), config.getStrategy(), config.getNumSlots());

            // Poll until all complete or timeout (10 min)
            long deadline = System.currentTimeMillis() + 600_000;
            int lastCompleted = 0;

            while (System.currentTimeMillis() < deadline) {
                int completed = 0;
                for (String reqId : requestIds) {
                    Map<String, Object> status = inferenceService.getStatus(reqId);
                    String s = String.valueOf(status.get("status"));
                    if ("COMPLETED".equals(s) || "FAILED".equals(s)) {
                        completed++;
                    }
                }

                if (completed != lastCompleted) {
                    benchRepo.updateCompleted(benchId, completed);
                    lastCompleted = completed;
                }

                if (completed >= requestIds.size()) break;
                Thread.sleep(500);
            }

            // Compute stats
            computeStats(benchId, requestIds);
            log.info("Benchmark {} completed", benchId);

        } catch (Exception e) {
            log.error("Benchmark {} failed: {}", benchId, e.getMessage());
        }
    }

    private void computeStats(String benchId, List<String> requestIds) {
        List<Integer> latencies = new ArrayList<>();
        List<Integer> queueWaits = new ArrayList<>();
        int cacheHits = 0;
        long earliestSubmit = Long.MAX_VALUE;
        long latestComplete = 0;

        for (String reqId : requestIds) {
            Map<String, Object> status = inferenceService.getStatus(reqId);
            if (!"COMPLETED".equals(String.valueOf(status.get("status")))) continue;

            int latency = status.get("latency_ms") != null ?
                    ((Number) status.get("latency_ms")).intValue() : 0;
            int qw = status.get("queue_wait_ms") != null ?
                    ((Number) status.get("queue_wait_ms")).intValue() : 0;
            int infMs = status.get("inference_ms") != null ?
                    ((Number) status.get("inference_ms")).intValue() : 0;
            boolean hit = Boolean.TRUE.equals(status.get("cache_hit"));

            latencies.add(latency);
            queueWaits.add(qw);
            if (hit) cacheHits++;

            // Update per-request benchmark record
            benchRepo.updateBenchmarkRequest(benchId, reqId, latency, qw, infMs, hit);

            // Track time span for throughput
            String submitted = redis.get("req:" + reqId + ":submitted");
            String completed = redis.get("req:" + reqId + ":completed_at");
            if (submitted != null) {
                earliestSubmit = Math.min(earliestSubmit, Long.parseLong(submitted));
            }
            if (completed != null) {
                latestComplete = Math.max(latestComplete, Long.parseLong(completed));
            }
        }

        if (latencies.isEmpty()) return;
        Collections.sort(latencies);
        int n = latencies.size();

        double avg = latencies.stream().mapToInt(i -> i).average().orElse(0);
        double p50 = latencies.get(n / 2);
        double p95 = latencies.get(Math.min((int)(n * 0.95), n - 1));
        double p99 = latencies.get(Math.min((int)(n * 0.99), n - 1));
        double avgQW = queueWaits.stream().mapToInt(i -> i).average().orElse(0);
        double cacheRate = (double) cacheHits / n;

        long totalDuration = latestComplete - earliestSubmit;
        double throughput = totalDuration > 0 ? (n * 1000.0 / totalDuration) : 0;

        benchRepo.finishRun(benchId, avg, p50, p95, p99, throughput, cacheRate, avgQW,
                System.currentTimeMillis());
    }

    public Map<String, Object> getResults(String benchId) {
        Map<String, Object> run = benchRepo.getRun(benchId);
        List<Map<String, Object>> details = benchRepo.getRequestDetails(benchId);
        Map<String, Object> result = new LinkedHashMap<>(run);
        result.put("requests", details);
        return result;
    }

    public List<Map<String, Object>> listAll() {
        return benchRepo.listRuns();
    }
}
