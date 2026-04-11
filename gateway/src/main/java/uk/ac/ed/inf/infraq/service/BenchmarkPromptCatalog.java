package uk.ac.ed.inf.infraq.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BenchmarkPromptCatalog {

    static final String UNIQUE_MODE = "unique";
    static final String REPEATED_MODE = "repeated";

    private static final Set<String> ALLOWED_MODES = Set.of(UNIQUE_MODE, REPEATED_MODE);

    private static final List<String> UNIQUE_TOPICS = List.of(
        "database indexing",
        "eventual consistency",
        "message queues",
        "load balancers",
        "container image layers",
        "service discovery",
        "API rate limiting",
        "distributed tracing",
        "circuit breakers",
        "database sharding",
        "content delivery networks",
        "reverse proxies",
        "infrastructure as code",
        "blue-green deployments",
        "feature flags",
        "stream processing",
        "dead-letter queues",
        "connection pooling",
        "TLS termination",
        "idempotent APIs",
        "write-ahead logging",
        "replication lag",
        "object storage",
        "Kubernetes namespaces",
        "horizontal autoscaling",
        "job schedulers",
        "schema migration strategies",
        "cache invalidation",
        "observability dashboards",
        "retry backoff",
        "leader election",
        "consistent hashing",
        "vector databases",
        "prompt caching",
        "batch inference",
        "CPU vs GPU inference",
        "embedding pipelines",
        "model quantization",
        "request prioritization",
        "queue backpressure"
    );

    private static final List<String> UNIQUE_TEMPLATES = List.of(
        "Explain the role of %s in modern software systems in 3 concise sentences.",
        "Give one concrete production example where %s is useful, and state the main benefit.",
        "Describe one benefit and one trade-off of %s.",
        "Describe one common failure mode or misuse of %s and how to reduce the risk."
    );

    private static final List<String> REPEATED_HOT_SET = List.of(
        "Explain the difference between SQL and NoSQL databases in 3 concise sentences.",
        "Describe what a load balancer does and why it matters in production.",
        "Explain eventual consistency in simple terms and give one trade-off.",
        "What is a message queue, and when should a backend team use one?",
        "Explain why cache invalidation is difficult and name one mitigation strategy.",
        "Describe the difference between REST and GraphQL for API design.",
        "Explain the difference between containers and virtual machines.",
        "What is continuous integration, and how is it different from continuous deployment?"
    );

    private BenchmarkPromptCatalog() {}

    static String normalizeMode(String requested) {
        if (requested == null) {
            return UNIQUE_MODE;
        }

        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_MODES.contains(normalized) ? normalized : UNIQUE_MODE;
    }

    static List<String> buildPrompts(String workloadMode, int numRequests) {
        return REPEATED_MODE.equals(workloadMode)
            ? buildRepeatedPrompts(numRequests)
            : buildUniquePrompts(numRequests);
    }

    static List<String> buildUniquePrompts(int numRequests) {
        List<String> prompts = new ArrayList<>(numRequests);
        int promptsPerCycle = UNIQUE_TOPICS.size() * UNIQUE_TEMPLATES.size();

        for (int i = 0; i < numRequests; i++) {
            int topicIndex = i % UNIQUE_TOPICS.size();
            int templateIndex = (i / UNIQUE_TOPICS.size()) % UNIQUE_TEMPLATES.size();
            int cycle = i / promptsPerCycle;

            String prompt = String.format(
                UNIQUE_TEMPLATES.get(templateIndex),
                UNIQUE_TOPICS.get(topicIndex)
            );

            if (cycle > 0) {
                prompt += " Keep the wording distinct from earlier runs. Variant " + (cycle + 1) + ".";
            }

            prompts.add(prompt);
        }

        return prompts;
    }

    static List<String> buildRepeatedPrompts(int numRequests) {
        List<String> prompts = new ArrayList<>(numRequests);
        List<String> uniquePrompts = buildUniquePrompts(Math.max(4, (numRequests / 5) + 2));
        int uniqueCursor = 0;

        for (int i = 0; i < numRequests; i++) {
            if ((i + 1) % 5 == 0) {
                prompts.add(uniquePrompts.get(uniqueCursor % uniquePrompts.size()));
                uniqueCursor++;
            } else {
                prompts.add(REPEATED_HOT_SET.get(i % REPEATED_HOT_SET.size()));
            }
        }

        return prompts;
    }
}
