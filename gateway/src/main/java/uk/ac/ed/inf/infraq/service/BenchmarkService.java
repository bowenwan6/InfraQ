package uk.ac.ed.inf.infraq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.ac.ed.inf.infraq.dto.BenchmarkConfig;
import uk.ac.ed.inf.infraq.repository.BenchmarkRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages benchmark runs: applies worker config, submits N requests,
 * tracks completion, and computes summary statistics.
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);
    private static final Set<String> ALLOWED_STRATEGIES = Set.of("sequential", "static", "continuous", "cached");
    private static final Set<String> READY_PHASES = Set.of("READY", "RUNNING");
    private static final long WORKER_IDLE_TIMEOUT_MS = 120_000;
    private static final long WORKER_APPLY_TIMEOUT_MS = 15_000;
    private static final long BENCHMARK_TIMEOUT_MS = 1_800_000;
    private static final int MAX_REQUESTS = 200;
    private static final String MODEL = System.getenv().getOrDefault("MODEL", "qwen2.5:1.5b");

    private final InferenceService inferenceService;
    private final BenchmarkRepository benchRepo;
    private final RedisService redis;
    private final RabbitMQPublisher rabbit;

    public BenchmarkService(InferenceService inferenceService,
                            BenchmarkRepository benchRepo, RedisService redis,
                            RabbitMQPublisher rabbit) {
        this.inferenceService = inferenceService;
        this.benchRepo = benchRepo;
        this.redis = redis;
        this.rabbit = rabbit;
    }

    /**
     * Apply benchmark config to the worker, wait until the worker reports it as active,
     * then create and launch a benchmark run.
     */
    public Map<String, Object> start(BenchmarkConfig config) {
        int numRequests = Math.max(1, Math.min(MAX_REQUESTS, config.getNumRequests()));
        String requestedStrategy = sanitizeStrategy(config.getStrategy());
        int requestedSlots = "sequential".equals(requestedStrategy)
                ? 1
                : Math.max(1, config.getNumSlots());
        String workloadMode = BenchmarkPromptCatalog.normalizeMode(config.getWorkloadMode());
        List<String> prompts = BenchmarkPromptCatalog.buildPrompts(workloadMode, numRequests);

        waitForWorkerIdle();
        redis.set("config:strategy", requestedStrategy);
        redis.set("config:num_slots", String.valueOf(requestedSlots));

        RuntimeConfig effectiveConfig = waitForWorkerConfig(requestedStrategy, requestedSlots);
        clearPromptCaches(prompts);

        String benchId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String label = workloadMode + "_" + effectiveConfig.strategy() + "_" + effectiveConfig.numSlots() + "slots";

        benchRepo.createRun(benchId, label, effectiveConfig.strategy(),
                effectiveConfig.numSlots(), prompts.size(), now);

        CompletableFuture.runAsync(() ->
            runBenchmarkAsync(benchId, prompts, workloadMode, effectiveConfig.strategy(), effectiveConfig.numSlots())
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("benchmark_id", benchId);
        response.put("status", "RUNNING");
        response.put("total_requests", prompts.size());
        response.put("requested_strategy", requestedStrategy);
        response.put("requested_slots", requestedSlots);
        response.put("effective_strategy", effectiveConfig.strategy());
        response.put("effective_slots", effectiveConfig.numSlots());
        response.put("workload_mode", workloadMode);
        response.put("worker_phase", effectiveConfig.phase());
        return response;
    }

    private void runBenchmarkAsync(String benchId, List<String> prompts, String workloadMode,
                                   String strategy, int numSlots) {
        try {
            List<String> requestIds = new ArrayList<>();
            for (String prompt : prompts) {
                String reqId = inferenceService.submit(prompt, "chat", 0);
                benchRepo.linkRequest(benchId, reqId);
                requestIds.add(reqId);
            }

            log.info("Benchmark {} started: {} requests, workload={}, strategy={}, slots={}",
                    benchId, requestIds.size(), workloadMode, strategy, numSlots);

            long deadline = System.currentTimeMillis() + BENCHMARK_TIMEOUT_MS;
            int lastCompleted = 0;
            boolean finishedAll = false;

            while (System.currentTimeMillis() < deadline) {
                int completed = 0;
                for (String reqId : requestIds) {
                    Map<String, Object> status = inferenceService.getStatus(reqId);
                    String currentStatus = String.valueOf(status.get("status"));
                    if ("COMPLETED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
                        completed++;
                    }
                }

                if (completed != lastCompleted) {
                    benchRepo.updateCompleted(benchId, completed);
                    lastCompleted = completed;
                }

                if (completed >= requestIds.size()) {
                    finishedAll = true;
                    break;
                }

                Thread.sleep(500);
            }

            computeStats(benchId, requestIds, finishedAll ? "COMPLETED" : "TIMED_OUT");
            log.info("Benchmark {} finished with status={}", benchId, finishedAll ? "COMPLETED" : "TIMED_OUT");
        } catch (Exception e) {
            log.error("Benchmark {} failed: {}", benchId, e.getMessage(), e);
            benchRepo.markStatus(benchId, "FAILED", System.currentTimeMillis());
        }
    }

    private void computeStats(String benchId, List<String> requestIds, String finalStatus) {
        List<Integer> latencies = new ArrayList<>();
        List<Integer> queueWaits = new ArrayList<>();
        int cacheHits = 0;
        long earliestSubmit = Long.MAX_VALUE;
        long latestComplete = 0;

        for (String reqId : requestIds) {
            Map<String, Object> status = inferenceService.getStatus(reqId);
            if (!"COMPLETED".equals(String.valueOf(status.get("status")))) {
                continue;
            }

            int latency = status.get("latency_ms") != null
                    ? ((Number) status.get("latency_ms")).intValue() : 0;
            int queueWait = status.get("queue_wait_ms") != null
                    ? ((Number) status.get("queue_wait_ms")).intValue() : 0;
            int inferenceMs = status.get("inference_ms") != null
                    ? ((Number) status.get("inference_ms")).intValue() : 0;
            boolean cacheHit = Boolean.TRUE.equals(status.get("cache_hit"));

            latencies.add(latency);
            queueWaits.add(queueWait);
            if (cacheHit) {
                cacheHits++;
            }

            benchRepo.updateBenchmarkRequest(benchId, reqId, latency, queueWait, inferenceMs, cacheHit);

            String submittedAt = redis.get("req:" + reqId + ":submitted");
            String completedAt = redis.get("req:" + reqId + ":completed_at");
            if (submittedAt != null) {
                earliestSubmit = Math.min(earliestSubmit, Long.parseLong(submittedAt));
            }
            if (completedAt != null) {
                latestComplete = Math.max(latestComplete, Long.parseLong(completedAt));
            }
        }

        if (latencies.isEmpty()) {
            String emptyStatus = "COMPLETED".equals(finalStatus) ? "FAILED" : finalStatus;
            benchRepo.finishRun(benchId, 0, 0, 0, 0, 0, 0, 0,
                    System.currentTimeMillis(), emptyStatus);
            return;
        }

        Collections.sort(latencies);
        int count = latencies.size();

        double avgLatency = latencies.stream().mapToInt(Integer::intValue).average().orElse(0);
        double p50 = latencies.get(count / 2);
        double p95 = latencies.get(Math.min((int) Math.ceil(count * 0.95) - 1, count - 1));
        double p99 = latencies.get(Math.min((int) Math.ceil(count * 0.99) - 1, count - 1));
        double avgQueueWait = queueWaits.stream().mapToInt(Integer::intValue).average().orElse(0);
        double cacheHitRate = (double) cacheHits / count;

        long totalDuration = latestComplete > earliestSubmit ? latestComplete - earliestSubmit : 0;
        double throughput = totalDuration > 0 ? (count * 1000.0 / totalDuration) : 0;

        benchRepo.finishRun(benchId, avgLatency, p50, p95, p99, throughput,
                cacheHitRate, avgQueueWait, System.currentTimeMillis(), finalStatus);
    }

    public Map<String, Object> getResults(String benchId) {
        Map<String, Object> run = decorateRun(benchRepo.getRun(benchId));
        List<Map<String, Object>> details = benchRepo.getRequestDetails(benchId);
        Map<String, Object> result = new LinkedHashMap<>(run);
        result.put("requests", details);
        return result;
    }

    public List<Map<String, Object>> listAll() {
        return benchRepo.listRuns().stream()
                .map(this::decorateRun)
                .toList();
    }

    private Map<String, Object> decorateRun(Map<String, Object> run) {
        Map<String, Object> decorated = new LinkedHashMap<>(run);
        decorated.put("workload_mode", inferWorkloadMode(run));
        return decorated;
    }

    private String inferWorkloadMode(Map<String, Object> run) {
        String label = String.valueOf(run.getOrDefault("label", ""));
        if (label.startsWith(BenchmarkPromptCatalog.REPEATED_MODE + "_")) {
            return BenchmarkPromptCatalog.REPEATED_MODE;
        }
        if (label.startsWith(BenchmarkPromptCatalog.UNIQUE_MODE + "_")) {
            return BenchmarkPromptCatalog.UNIQUE_MODE;
        }
        return "legacy";
    }

    private RuntimeConfig waitForWorkerConfig(String strategy, int numSlots) {
        long deadline = System.currentTimeMillis() + WORKER_APPLY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            String heartbeatAt = redis.get("worker:runtime:heartbeat_at");
            String activeStrategy = redis.get("worker:runtime:strategy");
            String activeSlots = redis.get("worker:runtime:num_slots");
            String phase = redis.getOrDefault("worker:runtime:phase", "UNKNOWN");

            if (heartbeatAt != null && activeStrategy != null && activeSlots != null) {
                long ageMs = System.currentTimeMillis() - Long.parseLong(heartbeatAt);
                if (ageMs <= 5_000
                        && strategy.equals(activeStrategy)
                        && String.valueOf(numSlots).equals(activeSlots)
                        && READY_PHASES.contains(phase)) {
                    return new RuntimeConfig(activeStrategy, Integer.parseInt(activeSlots), phase);
                }
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for worker config to apply.");
            }
        }

        throw new IllegalStateException(
                "Worker did not apply the requested benchmark config in time. Check the Runtime tab and worker health."
        );
    }

    private void waitForWorkerIdle() {
        long deadline = System.currentTimeMillis() + WORKER_IDLE_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            String heartbeatAt = redis.get("worker:runtime:heartbeat_at");
            String phase = redis.getOrDefault("worker:runtime:phase", "UNKNOWN");
            int activeRequests = parseInt(redis.getOrDefault("worker:runtime:active_requests", "0"), 0);
            int bufferedMessages = parseInt(redis.getOrDefault("worker:runtime:buffered_messages", "0"), 0);
            long queueDepth = rabbit != null ? rabbit.getQueueDepth() : -1;

            boolean freshHeartbeat = heartbeatAt != null
                    && System.currentTimeMillis() - Long.parseLong(heartbeatAt) <= 5_000;
            boolean idle = activeRequests == 0 && bufferedMessages == 0 && queueDepth == 0;

            if (freshHeartbeat && READY_PHASES.contains(phase) && idle) {
                return;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for worker to become idle.");
            }
        }

        throw new IllegalStateException(
                "Worker is still draining previous requests. Wait for the Runtime tab to show queue depth, active requests, and buffered messages at zero."
        );
    }

    private String sanitizeStrategy(String requested) {
        if (requested == null) {
            return "continuous";
        }
        String normalized = requested.trim().toLowerCase();
        return ALLOWED_STRATEGIES.contains(normalized) ? normalized : "continuous";
    }

    private void clearPromptCaches(List<String> prompts) {
        String[] keys = prompts.stream()
                .map(this::cacheKeyForPrompt)
                .distinct()
                .toArray(String[]::new);

        if (keys.length > 0) {
            redis.del(keys);
        }
    }

    private String cacheKeyForPrompt(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((MODEL + ":" + prompt).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("cache:");
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available for prompt cache hashing", e);
        }
    }

    private int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record RuntimeConfig(String strategy, int numSlots, String phase) {}
}
