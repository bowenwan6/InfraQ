package uk.ac.ed.inf.infraq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.ac.ed.inf.infraq.repository.InferenceRepository;

import java.util.*;

/**
 * Core service: accepts inference requests, publishes to RabbitMQ,
 * reads results from Redis (fast) with PostgreSQL fallback (durable).
 */
@Service
public class InferenceService {

    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    private final RedisService redis;
    private final RabbitMQPublisher rabbit;
    private final InferenceRepository repo;
    private final ObjectMapper mapper;

    public InferenceService(RedisService redis, RabbitMQPublisher rabbit,
                            InferenceRepository repo, ObjectMapper mapper) {
        this.redis = redis;
        this.rabbit = rabbit;
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * Submit an inference request: persist, set state in Redis, publish to RabbitMQ.
     * Returns the request ID immediately (async processing).
     */
    public String submit(String prompt, String taskType, int priority) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        // 1. Persist to PostgreSQL
        repo.insertRequest(id, prompt, taskType, priority, now);

        // 2. Set fast-path state in Redis
        redis.set("req:" + id + ":status", "QUEUED");
        redis.set("req:" + id + ":submitted", String.valueOf(now));

        // 3. Publish to RabbitMQ for worker consumption
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("prompt", prompt);
        msg.put("task_type", taskType);
        msg.put("priority", priority);
        msg.put("submitted_at", now);
        rabbit.publish(msg);

        // 4. Track metrics
        redis.incr("metrics:total_submitted");

        log.info("Request submitted: id={}, taskType={}", id, taskType);
        return id;
    }

    /**
     * Get request status and result. Redis first (fast), PostgreSQL fallback (durable).
     */
    public Map<String, Object> getStatus(String id) {
        // Fast path: Redis
        String status = redis.get("req:" + id + ":status");
        if (status != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", status);

            if ("COMPLETED".equals(status)) {
                result.put("result", redis.get("req:" + id + ":result"));
                String latency = redis.get("req:" + id + ":latency_ms");
                result.put("latency_ms", latency != null ? Integer.parseInt(latency) : null);
                String queueWait = redis.get("req:" + id + ":queue_wait_ms");
                result.put("queue_wait_ms", queueWait != null ? Integer.parseInt(queueWait) : null);
                String inferenceMs = redis.get("req:" + id + ":inference_ms");
                result.put("inference_ms", inferenceMs != null ? Integer.parseInt(inferenceMs) : null);
                result.put("cache_hit", "true".equals(redis.get("req:" + id + ":cache_hit")));
                String model = redis.get("req:" + id + ":model");
                if (model != null) result.put("model", model);
            } else if ("FAILED".equals(status)) {
                result.put("error", redis.get("req:" + id + ":error"));
            }
            return result;
        }

        // Slow path: PostgreSQL
        Map<String, Object> row = repo.getRequestById(id);
        if ("NOT_FOUND".equals(row.get("status"))) {
            return row;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("status", row.get("status"));

        if ("COMPLETED".equals(row.get("status"))) {
            result.put("result", row.get("output"));
            result.put("latency_ms", row.get("total_latency_ms"));
            result.put("queue_wait_ms", row.get("queue_wait_ms"));
            result.put("inference_ms", row.get("inference_ms"));
            result.put("cache_hit", Boolean.TRUE.equals(row.get("cache_hit")));
            if (row.get("model") != null) {
                result.put("model", row.get("model"));
            }
        } else if ("FAILED".equals(row.get("status"))) {
            result.put("error", row.get("error_message"));
        }

        return result;
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return repo.listRecent(limit);
    }
}
