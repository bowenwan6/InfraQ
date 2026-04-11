package uk.ac.ed.inf.infraq.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.ac.ed.inf.infraq.dto.BenchmarkConfig;
import uk.ac.ed.inf.infraq.service.BenchmarkService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/benchmark")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    /**
     * Start a new benchmark run. The gateway applies the requested worker
     * config, waits until the worker reports it as active, then submits the run.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody BenchmarkConfig config) {
        try {
            return ResponseEntity.accepted().body(benchmarkService.start(config));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get benchmark results including per-request details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getResults(@PathVariable String id) {
        return ResponseEntity.ok(benchmarkService.getResults(id));
    }

    /**
     * List all benchmark runs.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        return ResponseEntity.ok(benchmarkService.listAll());
    }
}
