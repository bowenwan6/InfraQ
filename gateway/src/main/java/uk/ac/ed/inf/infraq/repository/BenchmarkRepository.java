package uk.ac.ed.inf.infraq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class BenchmarkRepository {

    private final JdbcTemplate jdbc;

    public BenchmarkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createRun(String id, String label, String strategy, int numSlots,
                          int totalRequests, long startedAt) {
        jdbc.update(
            "INSERT INTO infraq.benchmark_runs " +
            "(id, label, strategy, num_slots, total_requests, started_at, status) " +
            "VALUES (?::uuid, ?, ?, ?, ?, ?, 'RUNNING')",
            id, label, strategy, numSlots, totalRequests, startedAt
        );
    }

    public void linkRequest(String benchmarkId, String requestId) {
        jdbc.update(
            "INSERT INTO infraq.benchmark_requests (id, benchmark_id, request_id) " +
            "VALUES (?::uuid, ?::uuid, ?::uuid)",
            UUID.randomUUID().toString(), benchmarkId, requestId
        );
    }

    public void updateCompleted(String benchmarkId, int completed) {
        jdbc.update(
            "UPDATE infraq.benchmark_runs SET completed = ? WHERE id = ?::uuid",
            completed, benchmarkId
        );
    }

    public void finishRun(String benchmarkId, double avgLatency, double p50, double p95,
                          double p99, double throughput, double cacheHitRate,
                          double avgQueueWait, long finishedAt, String status) {
        jdbc.update(
            "UPDATE infraq.benchmark_runs SET " +
            "avg_latency_ms=?, p50_latency_ms=?, p95_latency_ms=?, p99_latency_ms=?, " +
            "throughput_rps=?, cache_hit_rate=?, avg_queue_wait=?, finished_at=?, status=? " +
            "WHERE id = ?::uuid",
            avgLatency, p50, p95, p99, throughput, cacheHitRate, avgQueueWait, finishedAt, status, benchmarkId
        );
    }

    public void markStatus(String benchmarkId, String status, long finishedAt) {
        jdbc.update(
            "UPDATE infraq.benchmark_runs SET status=?, finished_at=? WHERE id=?::uuid",
            status, finishedAt, benchmarkId
        );
    }

    public void updateBenchmarkRequest(String benchmarkId, String requestId,
                                       int latency, int queueWait, int inferenceMs, boolean cacheHit) {
        jdbc.update(
            "UPDATE infraq.benchmark_requests SET latency_ms=?, queue_wait_ms=?, " +
            "inference_ms=?, cache_hit=? WHERE benchmark_id=?::uuid AND request_id=?::uuid",
            latency, queueWait, inferenceMs, cacheHit, benchmarkId, requestId
        );
    }

    public Map<String, Object> getRun(String benchmarkId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM infraq.benchmark_runs WHERE id = ?::uuid", benchmarkId
        );
        if (rows.isEmpty()) return Map.of("status", "NOT_FOUND");
        return rows.get(0);
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.queryForList(
            "SELECT * FROM infraq.benchmark_runs ORDER BY started_at DESC LIMIT 50"
        );
    }

    public List<Map<String, Object>> getRequestDetails(String benchmarkId) {
        return jdbc.queryForList(
            "SELECT br.request_id, br.latency_ms, br.queue_wait_ms, br.inference_ms, br.cache_hit " +
            "FROM infraq.benchmark_requests br WHERE br.benchmark_id = ?::uuid " +
            "ORDER BY br.latency_ms ASC", benchmarkId
        );
    }

    public int deleteTerminalRuns() {
        Integer deletedRunCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM infraq.benchmark_runs WHERE status IN ('COMPLETED', 'FAILED', 'TIMED_OUT')",
                Integer.class
        );
        List<String> requestIds = jdbc.queryForList(
                "SELECT DISTINCT br.request_id::text " +
                "FROM infraq.benchmark_requests br " +
                "JOIN infraq.benchmark_runs runs ON runs.id = br.benchmark_id " +
                "WHERE runs.status IN ('COMPLETED', 'FAILED', 'TIMED_OUT')",
                String.class
        );

        jdbc.update(
                "DELETE FROM infraq.benchmark_requests " +
                "WHERE benchmark_id IN (" +
                "SELECT id FROM infraq.benchmark_runs WHERE status IN ('COMPLETED', 'FAILED', 'TIMED_OUT')" +
                ")"
        );

        if (!requestIds.isEmpty()) {
            for (String requestId : requestIds) {
                jdbc.update("DELETE FROM infraq.inference_results WHERE request_id = ?::uuid", requestId);
                jdbc.update("DELETE FROM infraq.inference_requests WHERE id = ?::uuid", requestId);
            }
        }

        jdbc.update("DELETE FROM infraq.benchmark_runs WHERE status IN ('COMPLETED', 'FAILED', 'TIMED_OUT')");
        return deletedRunCount == null ? 0 : deletedRunCount;
    }
}
