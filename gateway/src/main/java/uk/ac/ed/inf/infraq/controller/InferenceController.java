package uk.ac.ed.inf.infraq.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.ac.ed.inf.infraq.dto.InferRequest;
import uk.ac.ed.inf.infraq.service.InferenceService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/infer")
public class InferenceController {

    private final InferenceService inferenceService;

    public InferenceController(InferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    /**
     * Submit a new inference request. Returns 202 with the request ID.
     * The request is queued and processed asynchronously by a worker.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody InferRequest req) {
        if (req.getPrompt() == null || req.getPrompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        String id = inferenceService.submit(req.getPrompt(), req.getTaskType(), req.getPriority());
        return ResponseEntity.accepted().body(Map.of(
                "id", id,
                "status", "QUEUED"
        ));
    }

    /**
     * Get the status and result of a request.
     * Fast path via Redis, falls back to PostgreSQL.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String id) {
        return ResponseEntity.ok(inferenceService.getStatus(id));
    }

    /**
     * List recent requests with their status.
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listRecent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(inferenceService.listRecent(limit));
    }
}
