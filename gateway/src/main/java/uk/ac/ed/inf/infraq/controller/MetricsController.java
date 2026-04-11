package uk.ac.ed.inf.infraq.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.ac.ed.inf.infraq.service.RabbitMQPublisher;
import uk.ac.ed.inf.infraq.service.RedisService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final RedisService redis;
    private final RabbitMQPublisher rabbit;

    public MetricsController(RedisService redis, RabbitMQPublisher rabbit) {
        this.redis = redis;
        this.rabbit = rabbit;
    }

    /**
     * Real-time metrics aggregated from Redis counters and RabbitMQ queue depth.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        String desiredStrategy = normalizeStrategy(redis.getOrDefault("config:strategy", "continuous"));
        String desiredSlots = normalizeSlots(desiredStrategy, redis.getOrDefault("config:num_slots", "4"));
        String effectiveStrategy = redis.getOrDefault("worker:runtime:strategy", desiredStrategy);
        String effectiveSlots = normalizeSlots(effectiveStrategy, redis.getOrDefault("worker:runtime:num_slots", desiredSlots));
        String workerPhase = redis.getOrDefault("worker:runtime:phase", "UNKNOWN");
        long activeRequests = Long.parseLong(redis.getOrDefault("worker:runtime:active_requests", "0"));
        long bufferedMessages = Long.parseLong(redis.getOrDefault("worker:runtime:buffered_messages", "0"));

        metrics.put("total_submitted", Long.parseLong(redis.getOrDefault("metrics:total_submitted", "0")));
        metrics.put("total_completed", Long.parseLong(redis.getOrDefault("metrics:total_completed", "0")));
        metrics.put("total_cache_hits", Long.parseLong(redis.getOrDefault("metrics:total_cache_hits", "0")));
        metrics.put("total_failed", Long.parseLong(redis.getOrDefault("metrics:total_failed", "0")));
        metrics.put("queue_depth", rabbit.getQueueDepth());
        metrics.put("desired_strategy", desiredStrategy);
        metrics.put("desired_slots", desiredSlots);
        metrics.put("effective_strategy", effectiveStrategy);
        metrics.put("effective_slots", effectiveSlots);
        metrics.put("worker_phase", workerPhase);
        metrics.put("active_requests", activeRequests);
        metrics.put("buffered_messages", bufferedMessages);
        metrics.put("active_strategy", effectiveStrategy);
        metrics.put("active_slots", effectiveSlots);
        return ResponseEntity.ok(metrics);
    }

    /**
     * Update runtime configuration (strategy, slots) — worker reads from Redis.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, String> config) {
        String strategy = normalizeStrategy(config.getOrDefault(
                "strategy", redis.getOrDefault("config:strategy", "continuous")
        ));
        String numSlots = normalizeSlots(
                strategy,
                config.getOrDefault("num_slots", redis.getOrDefault("config:num_slots", "4"))
        );

        redis.set("config:strategy", strategy);
        redis.set("config:num_slots", numSlots);
        return ResponseEntity.ok(Map.of(
                "status", "updated",
                "config", Map.of("strategy", strategy, "num_slots", numSlots)
        ));
    }

    private String normalizeStrategy(String rawStrategy) {
        String normalized = rawStrategy == null ? "continuous" : rawStrategy.trim().toLowerCase();
        return Set.of("sequential", "static", "continuous", "cached").contains(normalized)
                ? normalized
                : "continuous";
    }

    private String normalizeSlots(String strategy, String rawSlots) {
        if ("sequential".equals(strategy)) {
            return "1";
        }
        try {
            return String.valueOf(Math.max(1, Integer.parseInt(rawSlots)));
        } catch (NumberFormatException e) {
            return "4";
        }
    }
}
