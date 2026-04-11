package uk.ac.ed.inf.infraq.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.ac.ed.inf.infraq.service.RabbitMQPublisher;
import uk.ac.ed.inf.infraq.service.RedisService;

import java.util.LinkedHashMap;
import java.util.Map;

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
        metrics.put("total_submitted", Long.parseLong(redis.getOrDefault("metrics:total_submitted", "0")));
        metrics.put("total_completed", Long.parseLong(redis.getOrDefault("metrics:total_completed", "0")));
        metrics.put("total_cache_hits", Long.parseLong(redis.getOrDefault("metrics:total_cache_hits", "0")));
        metrics.put("total_failed", Long.parseLong(redis.getOrDefault("metrics:total_failed", "0")));
        metrics.put("queue_depth", rabbit.getQueueDepth());
        metrics.put("active_strategy", redis.getOrDefault("config:strategy", "continuous"));
        metrics.put("active_slots", redis.getOrDefault("config:num_slots", "4"));
        return ResponseEntity.ok(metrics);
    }

    /**
     * Update runtime configuration (strategy, slots) — worker reads from Redis.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, String> config) {
        if (config.containsKey("strategy")) {
            redis.set("config:strategy", config.get("strategy"));
        }
        if (config.containsKey("num_slots")) {
            redis.set("config:num_slots", config.get("num_slots"));
        }
        return ResponseEntity.ok(Map.of("status", "updated", "config", config));
    }
}
