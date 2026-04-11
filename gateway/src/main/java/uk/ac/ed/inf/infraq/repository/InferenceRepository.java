package uk.ac.ed.inf.infraq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class InferenceRepository {

    private final JdbcTemplate jdbc;

    public InferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRequest(String id, String prompt, String taskType, int priority, long submittedAt) {
        jdbc.update(
            "INSERT INTO infraq.inference_requests (id, prompt, task_type, priority, status, submitted_at) " +
            "VALUES (?::uuid, ?, ?, ?, 'QUEUED', ?)",
            id, prompt, taskType, priority, submittedAt
        );
    }

    public Map<String, Object> getRequestById(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT r.id, r.prompt, r.task_type, r.status, r.submitted_at, r.completed_at, " +
            "r.queue_wait_ms, r.inference_ms, r.total_latency_ms, r.cache_hit, r.error_message, " +
            "res.output, res.model, res.tokens_in, res.tokens_out " +
            "FROM infraq.inference_requests r " +
            "LEFT JOIN infraq.inference_results res ON r.id = res.request_id " +
            "WHERE r.id = ?::uuid", id
        );
        if (rows.isEmpty()) return Map.of("id", id, "status", "NOT_FOUND");
        return rows.get(0);
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return jdbc.queryForList(
            "SELECT r.id, r.prompt, r.task_type, r.status, r.submitted_at, " +
            "r.total_latency_ms, r.cache_hit, res.output " +
            "FROM infraq.inference_requests r " +
            "LEFT JOIN infraq.inference_results res ON r.id = res.request_id " +
            "ORDER BY r.submitted_at DESC LIMIT ?", limit
        );
    }
}
