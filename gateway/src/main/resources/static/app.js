const state = {
    chatTurns: [],
    historyLoaded: false,
    pollers: new Map(),
    benchmarkPoller: null,
    benchmarkFormTouched: false,
    chatDefaultRuntimePending: false,
    latencyChart: null,
    breakdownChart: null,
    compareLatencyChart: null,
    compareThroughputChart: null
};

const terminalRunStatuses = new Set(["COMPLETED", "FAILED", "TIMED_OUT"]);
const promptInput = document.getElementById("prompt");
const CHAT_DEFAULT_CONFIG = Object.freeze({ strategy: "cached", numSlots: 4 });
const BENCHMARK_STRATEGY_GUIDE = {
    sequential: {
        title: "Sequential Baseline",
        copy: "Run one request at a time to measure the floor for queue wait, tail latency, and throughput."
    },
    static: {
        title: "Static Batching",
        copy: "Collect a fixed batch, dispatch it together, then wait for the whole batch to clear before the next wave."
    },
    continuous: {
        title: "Continuous Dispatching",
        copy: "Keep the worker slots occupied and refill immediately when one request completes."
    },
    cached: {
        title: "Cache-Aware Dispatch",
        copy: "Check Redis before taking a slot so repeated prompts can bypass Ollama on cache hits."
    }
};
const BENCHMARK_WORKLOAD_GUIDE = {
    unique: {
        title: "Unique Prompts",
        copy: "Use this mode to isolate scheduler and queue behavior without prompt reuse."
    },
    repeated: {
        title: "Repeated Prompts",
        copy: "Use this mode to test how much work the cache-aware path can skip under controlled repetition."
    }
};

document.querySelectorAll(".tab-btn").forEach((button) => {
    button.addEventListener("click", () => openTab(button.dataset.tab));
});

document.getElementById("chat-form").addEventListener("submit", submitChat);
document.getElementById("clear-chat-btn").addEventListener("click", clearChatSession);
document.getElementById("bench-start-btn").addEventListener("click", runBenchmark);
document.getElementById("bench-refresh-btn").addEventListener("click", loadBenchmarks);
document.getElementById("bench-clear-btn").addEventListener("click", clearBenchmarkHistory);
document.getElementById("runtime-refresh-btn").addEventListener("click", refreshMetrics);
document.getElementById("bench-strategy").addEventListener("change", () => {
    markBenchmarkFormTouched();
    syncStrategyDependentControls();
});
document.getElementById("bench-workload").addEventListener("change", () => {
    markBenchmarkFormTouched();
    syncStrategyDependentControls();
});
document.getElementById("bench-slots").addEventListener("change", () => {
    markBenchmarkFormTouched();
    syncStrategyDependentControls();
});
promptInput.addEventListener("input", resizePromptInput);
promptInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        submitChat(event);
    }
});

function openTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((button) => {
        button.classList.toggle("active", button.dataset.tab === tabName);
    });

    document.querySelectorAll(".tab-content").forEach((panel) => {
        panel.classList.toggle("active", panel.id === "tab-" + tabName);
    });

    if (tabName === "chat") {
        loadChatHistory();
        scrollChatToBottom();
        refreshMetrics();
    }

    if (tabName === "benchmark") {
        loadBenchmarks();
        refreshMetrics();
    }

    if (tabName === "runtime") {
        refreshMetrics();
    }
}

async function loadChatHistory() {
    if (state.historyLoaded) {
        return;
    }

    state.historyLoaded = true;

    try {
        const response = await fetch("/api/v1/infer/list?limit=12");
        const rows = await response.json();
        state.chatTurns = rows.slice().reverse().map(mapRecentRowToTurn);
        renderChat();

        state.chatTurns
            .filter((turn) => !terminalRunStatuses.has(turn.status))
            .forEach((turn) => {
                if (turn.requestId) {
                    startTurnPolling(turn.requestId);
                }
            });
    } catch (error) {
        state.historyLoaded = false;
        state.chatTurns = [];
        renderChat("Could not load recent requests.");
    }
}

function mapRecentRowToTurn(row) {
    return {
        requestId: row.id,
        prompt: row.prompt || "",
        response: row.output || defaultAssistantText(row.status),
        status: row.status || "UNKNOWN",
        latencyMs: row.total_latency_ms ?? null,
        queueWaitMs: row.queue_wait_ms ?? null,
        inferenceMs: row.inference_ms ?? null,
        cacheHit: Boolean(row.cache_hit),
        taskType: row.task_type || "chat",
        priority: row.priority ?? 0,
        error: row.error_message || null
    };
}

function renderChat(message) {
    const thread = document.getElementById("chat-thread");

    if (!state.chatTurns.length) {
        thread.innerHTML = `
            <div class="chat-empty">
                <strong>No conversation yet</strong>
                <p>${escapeHtml(message || "Send a prompt to start a queued local inference run.")}</p>
            </div>
        `;
        return;
    }

    thread.innerHTML = state.chatTurns.map(renderTurn).join("");
    scrollChatToBottom();
}

function renderTurn(turn) {
    const assistantClasses = ["bubble", "assistant"];
    if (!terminalRunStatuses.has(turn.status)) {
        assistantClasses.push("pending");
    }
    if (turn.status === "FAILED") {
        assistantClasses.push("error");
    }

    const assistantText = turn.status === "FAILED"
        ? (turn.error || "The worker reported a failure.")
        : (turn.response || defaultAssistantText(turn.status));

    return `
        <article class="chat-turn">
            <div class="chat-row user">
                <div class="bubble user">
                    <div class="bubble-meta">
                        <span>You</span>
                        <span>${escapeHtml(turn.taskType || "chat")}</span>
                    </div>
                    <div class="bubble-text">${escapeHtml(turn.prompt)}</div>
                </div>
            </div>
            <div class="chat-row assistant">
                <div class="${assistantClasses.join(" ")}">
                    <div class="bubble-meta">
                        <span>InfraQ</span>
                        <span class="badge ${statusClass(turn.status)}">${escapeHtml(turn.status || "UNKNOWN")}</span>
                        ${turn.requestId ? `<span>${escapeHtml(turn.requestId.slice(0, 8))}</span>` : ""}
                    </div>
                    <div class="bubble-text">${escapeHtml(assistantText)}</div>
                    ${renderTurnStats(turn)}
                </div>
            </div>
        </article>
    `;
}

function renderTurnStats(turn) {
    const stats = [];

    if (turn.queueWaitMs != null) {
        stats.push(`<span>Queue ${escapeHtml(String(turn.queueWaitMs))} ms</span>`);
    }
    if (turn.inferenceMs != null) {
        stats.push(`<span>Infer ${escapeHtml(String(turn.inferenceMs))} ms</span>`);
    }
    if (turn.latencyMs != null) {
        stats.push(`<span>Total ${escapeHtml(String(turn.latencyMs))} ms</span>`);
    }
    if (turn.cacheHit) {
        stats.push("<span>Cache hit</span>");
    }

    if (!stats.length) {
        return "";
    }

    return `<div class="turn-stats">${stats.join("")}</div>`;
}

async function submitChat(event) {
    event.preventDefault();

    const prompt = promptInput.value.trim();
    if (!prompt) {
        return;
    }

    const turn = {
        requestId: null,
        prompt,
        response: "Queueing request...",
        status: "QUEUED",
        latencyMs: null,
        queueWaitMs: null,
        inferenceMs: null,
        cacheHit: false,
        taskType: document.getElementById("taskType").value,
        priority: parseInt(document.getElementById("priority").value, 10),
        error: null
    };

    state.chatTurns.push(turn);
    renderChat();

    promptInput.value = "";
    resizePromptInput();

    try {
        const response = await fetch("/api/v1/infer", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                prompt,
                taskType: turn.taskType,
                priority: turn.priority
            })
        });

        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Could not submit request.");
        }

        turn.requestId = payload.id;
        turn.status = payload.status || "QUEUED";
        turn.response = defaultAssistantText(turn.status);
        renderChat();
        startTurnPolling(payload.id);
    } catch (error) {
        turn.status = "FAILED";
        turn.error = error.message || "Unknown submit error.";
        turn.response = "";
        renderChat();
    }
}

function startTurnPolling(requestId) {
    if (state.pollers.has(requestId)) {
        return;
    }

    const intervalId = setInterval(async () => {
        try {
            const response = await fetch("/api/v1/infer/" + requestId);
            const payload = await response.json();
            const turn = state.chatTurns.find((entry) => entry.requestId === requestId);

            if (!turn) {
                stopTurnPolling(requestId);
                return;
            }

            applyTurnUpdate(turn, payload);
            renderChat();

            if (terminalRunStatuses.has(turn.status)) {
                stopTurnPolling(requestId);
            }
        } catch (error) {
            // Keep polling; transient network errors should not clear the turn.
        }
    }, 700);

    state.pollers.set(requestId, intervalId);
}

function stopTurnPolling(requestId) {
    const intervalId = state.pollers.get(requestId);
    if (intervalId) {
        clearInterval(intervalId);
        state.pollers.delete(requestId);
    }
}

function applyTurnUpdate(turn, payload) {
    turn.status = payload.status || turn.status;

    if (turn.status === "COMPLETED") {
        turn.response = payload.result || "";
        turn.latencyMs = payload.latency_ms ?? turn.latencyMs;
        turn.queueWaitMs = payload.queue_wait_ms ?? turn.queueWaitMs;
        turn.inferenceMs = payload.inference_ms ?? turn.inferenceMs;
        turn.cacheHit = Boolean(payload.cache_hit);
        turn.error = null;
        return;
    }

    if (turn.status === "FAILED") {
        turn.error = payload.error || "The request failed.";
        turn.response = "";
        return;
    }

    turn.response = defaultAssistantText(turn.status);
}

function clearChatSession() {
    state.pollers.forEach((intervalId) => clearInterval(intervalId));
    state.pollers.clear();
    state.chatTurns = [];
    renderChat();
}

function markBenchmarkFormTouched() {
    state.benchmarkFormTouched = true;
}

function syncBenchmarkForm(metrics) {
    if (state.benchmarkFormTouched || document.getElementById("bench-start-btn").disabled) {
        return;
    }

    const strategy = String(metrics.effective_strategy || "");
    const slots = String(metrics.effective_slots || "");
    const strategyInput = document.getElementById("bench-strategy");
    const slotsInput = document.getElementById("bench-slots");

    if (strategy && [...strategyInput.options].some((option) => option.value === strategy)) {
        strategyInput.value = strategy;
    }

    if (slots && [...slotsInput.options].some((option) => option.value === slots)) {
        slotsInput.value = slots;
    }

    syncStrategyDependentControls();
}

function syncStrategyDependentControls() {
    const strategy = document.getElementById("bench-strategy").value;
    const workload = document.getElementById("bench-workload").value;
    const slotsInput = document.getElementById("bench-slots");
    const launchNote = document.getElementById("bench-launch-note");

    if (strategy === "sequential") {
        slotsInput.value = "1";
        slotsInput.disabled = true;
        launchNote.textContent =
            "Sequential is the baseline mode and always runs with exactly 1 active slot.";
        renderBenchmarkSetupNarrative();
        return;
    }

    slotsInput.disabled = false;
    if (strategy === "cached" && workload === "unique") {
        launchNote.textContent =
            "Cached on a unique workload behaves mostly like continuous dispatch because prompt reuse stays near zero.";
    } else if (strategy === "cached" && workload === "repeated") {
        launchNote.textContent =
            "Repeated prompts are where cache-aware dispatch should show real reuse and queue-wait reduction.";
    } else {
        launchNote.textContent =
            "The gateway waits until the worker reports the requested runtime config.";
    }

    renderBenchmarkSetupNarrative();
}

function resizePromptInput() {
    promptInput.style.height = "auto";
    promptInput.style.height = Math.min(promptInput.scrollHeight, 168) + "px";
}

function scrollChatToBottom() {
    const thread = document.getElementById("chat-thread");
    thread.scrollTop = thread.scrollHeight;
}

async function runBenchmark() {
    hideBenchmarkError();
    clearBenchmarkPoller();

    const config = {
        numRequests: parseInt(document.getElementById("bench-num").value, 10),
        strategy: document.getElementById("bench-strategy").value,
        workloadMode: document.getElementById("bench-workload").value,
        numSlots: document.getElementById("bench-strategy").value === "sequential"
            ? 1
            : parseInt(document.getElementById("bench-slots").value, 10)
    };

    document.getElementById("bench-start-btn").disabled = true;
    document.getElementById("bench-results").classList.add("hidden");
    document.getElementById("bench-progress-panel").classList.remove("hidden");
    document.getElementById("bench-status-badge").textContent = "PREPARING";
    document.getElementById("bench-status-badge").className = "badge running";
    document.getElementById("bench-active-config").textContent =
        `${formatWorkloadMode(config.workloadMode)} | ${config.strategy} / ${config.numSlots} slots`;
    document.getElementById("bench-pct").textContent = `0 / ${config.numRequests}`;
    document.getElementById("bench-bar").value = 0;
    document.getElementById("bench-status-text").textContent = "Applying worker config before benchmark submission...";

    try {
        const response = await fetch("/api/v1/benchmark", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(config)
        });
        const payload = await response.json();

        if (!response.ok) {
            throw new Error(payload.error || "Benchmark could not be started.");
        }

        document.getElementById("bench-status-badge").textContent = "RUNNING";
        document.getElementById("bench-active-config").textContent =
            `${formatWorkloadMode(payload.workload_mode)} | ${payload.effective_strategy} / ${payload.effective_slots} slots`;
        document.getElementById("bench-status-text").textContent =
            "Benchmark is active. Progress updates every second.";

        startBenchmarkPolling(payload.benchmark_id, payload.total_requests || config.numRequests);
        refreshMetrics();
    } catch (error) {
        document.getElementById("bench-start-btn").disabled = false;
        showBenchmarkError(error.message || "Unknown benchmark error.");
    }
}

function startBenchmarkPolling(benchmarkId, totalRequests) {
    state.benchmarkPoller = setInterval(async () => {
        try {
            const response = await fetch("/api/v1/benchmark/" + benchmarkId);
            const run = await response.json();

            const completed = run.completed || 0;
            const total = run.total_requests || totalRequests;
            const percentage = total > 0 ? Math.round((completed / total) * 100) : 0;

            document.getElementById("bench-bar").value = percentage;
            document.getElementById("bench-pct").textContent = `${completed} / ${total}`;
            document.getElementById("bench-status-badge").textContent = run.status || "RUNNING";
            document.getElementById("bench-status-badge").className = `badge ${statusClass(run.status)}`;
            document.getElementById("bench-active-config").textContent =
                `${formatWorkloadMode(run.workload_mode)} | ${run.strategy || "-"} / ${run.num_slots || "-"} slots`;
            document.getElementById("bench-status-text").textContent = benchmarkStatusText(run);
            refreshMetrics();

            if (terminalRunStatuses.has(run.status)) {
                clearBenchmarkPoller();
                document.getElementById("bench-start-btn").disabled = false;

                if (run.status === "FAILED") {
                    showBenchmarkError("Benchmark failed before producing a valid result set.");
                } else {
                    renderBenchmarkResults(run);
                }

                loadBenchmarks();
            }
        } catch (error) {
            clearBenchmarkPoller();
            document.getElementById("bench-start-btn").disabled = false;
            showBenchmarkError("Lost connection while polling benchmark status.");
        }
    }, 1000);
}

function benchmarkStatusText(run) {
    if (run.status === "COMPLETED") {
        return "Benchmark completed successfully.";
    }

    if (run.status === "TIMED_OUT") {
        return "Benchmark reached the timeout window. Partial data is shown if any requests completed.";
    }

    if (run.status === "FAILED") {
        return "Benchmark failed.";
    }

    return "Benchmark is still running...";
}

function renderBenchmarkResults(run) {
    document.getElementById("bench-results").classList.remove("hidden");

    const stats = [
        ["Workload", formatWorkloadMode(run.workload_mode)],
        ["Strategy", `${run.strategy || "-"} / ${run.num_slots || "-"} slots`],
        ["Avg Latency", withUnit(run.avg_latency_ms, " ms")],
        ["P95 Latency", withUnit(run.p95_latency_ms, " ms")],
        ["P99 Latency", withUnit(run.p99_latency_ms, " ms")],
        ["Throughput", withUnit(run.throughput_rps, " req/s")],
        ["Cache Hit Rate", withUnit((run.cache_hit_rate || 0) * 100, "%")]
    ];

    document.getElementById("bench-stats").innerHTML = stats.map(([label, value]) => `
        <div class="stat-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
        </div>
    `).join("");

    renderRunCharts(run.requests || []);
}

function renderRunCharts(requests) {
    const labels = requests.map((_, index) => index + 1);
    const latencies = requests.map((request) => request.latency_ms || 0);
    const queueWaits = requests.map((request) => request.queue_wait_ms || 0);
    const inferenceTimes = requests.map((request) => request.inference_ms || 0);

    if (state.latencyChart) {
        state.latencyChart.destroy();
    }

    state.latencyChart = new Chart(document.getElementById("chart-latency"), {
        type: "bar",
        data: {
            labels,
            datasets: [{
                label: "Latency (ms)",
                data: latencies,
                backgroundColor: requests.map((request) => request.cache_hit ? "#80f5b3" : "#ff8a3d"),
                borderRadius: 10
            }]
        },
        options: chartOptions("Per-request latency", "Latency (ms)", false)
    });

    if (state.breakdownChart) {
        state.breakdownChart.destroy();
    }

    state.breakdownChart = new Chart(document.getElementById("chart-breakdown"), {
        type: "bar",
        data: {
            labels,
            datasets: [
                { label: "Queue wait", data: queueWaits, backgroundColor: "#76e7ff", borderRadius: 10 },
                { label: "Inference", data: inferenceTimes, backgroundColor: "#ff8a3d", borderRadius: 10 }
            ]
        },
        options: chartOptions("Queue vs inference time", "Time (ms)", true)
    });
}

async function loadBenchmarks() {
    try {
        const response = await fetch("/api/v1/benchmark");
        const runs = await response.json();
        const container = document.getElementById("bench-history");

        if (!runs.length) {
            container.innerHTML = `<p class="muted-text">No benchmark runs yet.</p>`;
            renderComparison([]);
            return;
        }

        container.innerHTML = runs.map((run) => `
            <article class="run-item ${escapeHtml(statusClass(run.status))}">
                <div class="run-item-top">
                    <div class="run-item-title">
                        <span class="badge ${statusClass(run.status)}">${escapeHtml(run.status)}</span>
                        <strong>${escapeHtml(run.label || `${run.strategy} / ${run.num_slots} slots`)}</strong>
                        <p>${escapeHtml(formatWorkloadMode(run.workload_mode) + " | " + (run.total_requests || 0) + " requests")}</p>
                    </div>
                    <div class="run-item-kpis">
                        <div class="run-kpi">
                            <span>Avg</span>
                            <strong>${escapeHtml(withUnit(run.avg_latency_ms, " ms"))}</strong>
                        </div>
                        <div class="run-kpi">
                            <span>Tput</span>
                            <strong>${escapeHtml(withUnit(run.throughput_rps, " req/s"))}</strong>
                        </div>
                        <div class="run-kpi">
                            <span>Cache</span>
                            <strong>${escapeHtml(withUnit((run.cache_hit_rate || 0) * 100, "%"))}</strong>
                        </div>
                    </div>
                </div>
                <div class="run-item-footer">
                    <span class="run-chip">${escapeHtml(`${run.strategy || "-"} / ${run.num_slots || "-"} slots`)}</span>
                    <span class="run-chip">${escapeHtml(`effective ${run.effective_strategy || run.strategy || "-"} / ${run.effective_slots || run.num_slots || "-"}`)}</span>
                </div>
            </article>
        `).join("");

        renderComparison(runs.filter((run) => run.status === "COMPLETED"));
    } catch (error) {
        document.getElementById("bench-history").innerHTML =
            `<p class="muted-text">Could not load benchmark history.</p>`;
    }
}

async function clearBenchmarkHistory() {
    if (!window.confirm("Clear all completed, failed, and timed out benchmark runs?")) {
        return;
    }

    try {
        const response = await fetch("/api/v1/benchmark", { method: "DELETE" });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Could not clear benchmark history.");
        }

        hideBenchmarkError();
        await loadBenchmarks();
    } catch (error) {
        showBenchmarkError(error.message || "Could not clear benchmark history.");
    }
}

function renderComparison(runs) {
    const compareNote = document.getElementById("compare-note");

    if (state.compareLatencyChart) {
        state.compareLatencyChart.destroy();
        state.compareLatencyChart = null;
    }

    if (state.compareThroughputChart) {
        state.compareThroughputChart.destroy();
        state.compareThroughputChart = null;
    }

    if (runs.length < 2) {
        compareNote.textContent = "Run at least two completed benchmarks to unlock comparison charts.";
        return;
    }

    compareNote.textContent = "Comparison charts show only completed runs.";

    const labels = runs.map((run) => run.label || `${run.strategy} / ${run.num_slots}`);

    state.compareLatencyChart = new Chart(document.getElementById("chart-compare-latency"), {
        type: "bar",
        data: {
            labels,
            datasets: [
                { label: "P50", data: runs.map((run) => run.p50_latency_ms || 0), backgroundColor: "#76e7ff" },
                { label: "P95", data: runs.map((run) => run.p95_latency_ms || 0), backgroundColor: "#ffd56b" },
                { label: "P99", data: runs.map((run) => run.p99_latency_ms || 0), backgroundColor: "#fe6f9d" }
            ]
        },
        options: chartOptions("Latency comparison", "Latency (ms)", false)
    });

    state.compareThroughputChart = new Chart(document.getElementById("chart-compare-throughput"), {
        type: "bar",
        data: {
            labels,
            datasets: [{
                label: "Throughput",
                data: runs.map((run) => run.throughput_rps || 0),
                backgroundColor: "#ff8a3d",
                borderRadius: 10
            }]
        },
        options: chartOptions("Throughput comparison", "Requests / sec", false)
    });
}

async function refreshMetrics() {
    try {
        const response = await fetch("/api/v1/metrics");
        const metrics = await response.json();
        syncBenchmarkForm(metrics);

        const desired = `${metrics.desired_strategy || "-"} / ${metrics.desired_slots || "-"} slots`;
        const effective = `${metrics.effective_strategy || "-"} / ${metrics.effective_slots || "-"} slots`;
        const phase = metrics.worker_phase || "UNKNOWN";
        const queueDepth = metrics.queue_depth >= 0 ? metrics.queue_depth : "?";
        const activeRequests = Number(metrics.active_requests || 0);
        const bufferedMessages = Number(metrics.buffered_messages || 0);
        const pendingQueue = formatPendingQueueDepth(metrics.queue_depth, bufferedMessages);

        document.getElementById("bench-runtime-desired").textContent = desired;
        document.getElementById("bench-runtime-effective").textContent = effective;
        document.getElementById("bench-runtime-phase").textContent = phase;
        document.getElementById("bench-runtime-queue").textContent = pendingQueue;
        document.getElementById("chat-runtime-mode").textContent = desired === effective ? effective : `${desired} -> ${effective}`;
        document.getElementById("chat-runtime-phase").textContent = phase;
        document.getElementById("chat-runtime-queue").textContent = pendingQueue;
        document.getElementById("chat-runtime-copy").textContent = chatRuntimeCopy(desired, effective, phase);
        document.getElementById("bench-runtime-note").textContent =
            desired === effective
                ? `Worker is ready. Phase: ${phase}. Broker queue: ${queueDepth}. Buffered: ${bufferedMessages}. Active: ${activeRequests}.`
                : `Worker is still applying the requested runtime config. Broker queue: ${queueDepth}. Buffered: ${bufferedMessages}. Active: ${activeRequests}.`;

        document.getElementById("m-submitted").textContent = metrics.total_submitted || 0;
        document.getElementById("m-completed").textContent = metrics.total_completed || 0;
        document.getElementById("m-cache").textContent = metrics.total_cache_hits || 0;
        document.getElementById("m-failed").textContent = metrics.total_failed || 0;
        document.getElementById("m-queue").textContent = pendingQueue;
        document.getElementById("m-desired").textContent = desired;
        document.getElementById("m-effective").textContent = effective;
        document.getElementById("m-phase").textContent = phase;

        ensureChatDefaultRuntime(metrics);
    } catch (error) {
        // Keep the last rendered values if the gateway is temporarily unavailable.
    }
}

function chartOptions(title, yAxisLabel, stacked) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                labels: { color: "#f2fbff" }
            },
            title: {
                display: true,
                text: title,
                color: "#f2fbff"
            }
        },
        scales: {
            x: {
                stacked,
                ticks: { color: "#8ba7b7" },
                grid: { color: "rgba(118,231,255,0.08)" }
            },
            y: {
                stacked,
                title: {
                    display: true,
                    text: yAxisLabel,
                    color: "#8ba7b7"
                },
                ticks: { color: "#8ba7b7" },
                grid: { color: "rgba(118,231,255,0.08)" }
            }
        }
    };
}

function hideBenchmarkError() {
    document.getElementById("bench-error-panel").classList.add("hidden");
    document.getElementById("bench-error-text").textContent = "";
}

function showBenchmarkError(message) {
    document.getElementById("bench-error-panel").classList.remove("hidden");
    document.getElementById("bench-error-text").textContent = message;
}

function clearBenchmarkPoller() {
    if (state.benchmarkPoller) {
        clearInterval(state.benchmarkPoller);
        state.benchmarkPoller = null;
    }
}

function defaultAssistantText(status) {
    switch (status) {
        case "QUEUED":
            return "Waiting in the queue...";
        case "PROCESSING":
            return "The worker is generating a response...";
        case "FAILED":
            return "The request failed.";
        default:
            return "Waiting for a response...";
    }
}

function statusClass(status) {
    return String(status || "").toLowerCase();
}

function formatWorkloadMode(mode) {
    if (mode === "repeated") {
        return "Repeated prompts";
    }
    if (mode === "unique") {
        return "Unique prompts";
    }
    return "Legacy prompts";
}

function withUnit(value, unit) {
    if (value == null) {
        return "-";
    }

    return `${Math.round(value * 100) / 100}${unit}`;
}

function formatPendingQueueDepth(queueDepth, bufferedMessages) {
    const brokerQueue = Number(queueDepth);
    const buffered = Number(bufferedMessages || 0);

    if (!Number.isFinite(brokerQueue) || brokerQueue < 0) {
        return buffered > 0 ? String(buffered) : "?";
    }

    return String(brokerQueue + buffered);
}

function renderBenchmarkSetupNarrative() {
    const strategy = document.getElementById("bench-strategy").value;
    const workload = document.getElementById("bench-workload").value;
    const slots = document.getElementById("bench-strategy").value === "sequential"
        ? "1"
        : document.getElementById("bench-slots").value;
    const strategyGuide = BENCHMARK_STRATEGY_GUIDE[strategy] || BENCHMARK_STRATEGY_GUIDE.continuous;
    const workloadGuide = BENCHMARK_WORKLOAD_GUIDE[workload] || BENCHMARK_WORKLOAD_GUIDE.unique;

    document.getElementById("bench-strategy-title").textContent = strategyGuide.title;
    document.getElementById("bench-strategy-copy").textContent = strategyGuide.copy;
    document.getElementById("bench-workload-title").textContent = workloadGuide.title;
    document.getElementById("bench-workload-copy").textContent = workloadGuide.copy;

    if (strategy === "cached" && workload === "repeated") {
        document.getElementById("bench-plan-title").textContent = "Cache Efficiency Run";
        document.getElementById("bench-plan-copy").textContent =
            `This setup is tuned to show whether repeated prompts can bypass inference work with ${slots} active slots.`;
        return;
    }

    if (strategy === "cached" && workload === "unique") {
        document.getElementById("bench-plan-title").textContent = "Cold-Cache Control";
        document.getElementById("bench-plan-copy").textContent =
            `Because prompts are unique, this run mostly behaves like continuous dispatch and should keep cache hits near zero at ${slots} slots.`;
        return;
    }

    if (strategy === "static") {
        document.getElementById("bench-plan-title").textContent = "Batch Boundary Test";
        document.getElementById("bench-plan-copy").textContent =
            `This run highlights how much latency is created by waiting for an entire ${slots}-slot batch to finish before refilling.`;
        return;
    }

    if (strategy === "sequential") {
        document.getElementById("bench-plan-title").textContent = "Baseline Floor";
        document.getElementById("bench-plan-copy").textContent =
            "This run measures the single-slot baseline with no concurrency and no batching effects.";
        return;
    }

    document.getElementById("bench-plan-title").textContent = "Throughput Comparison";
    document.getElementById("bench-plan-copy").textContent =
        `This run keeps ${slots} slots active and is best for comparing queue refill efficiency under controlled load.`;
}

function chatRuntimeCopy(desired, effective, phase) {
    if (desired === "cached / 4 slots" && effective === desired) {
        return "Cache-aware dispatch is active for chat requests and follow-up prompts.";
    }

    if (desired === "cached / 4 slots" && effective !== desired) {
        return `Chat is steering the worker toward cached / 4 slots. Current phase: ${phase}.`;
    }

    return "Chat prefers cached / 4 slots when the worker is idle.";
}

async function ensureChatDefaultRuntime(metrics) {
    const chatOpen = document.getElementById("tab-chat").classList.contains("active");

    if (!chatOpen || state.chatDefaultRuntimePending || state.benchmarkPoller) {
        return;
    }

    const desiredStrategy = String(metrics.desired_strategy || "");
    const desiredSlots = String(metrics.desired_slots || "");
    const phase = String(metrics.worker_phase || "").toUpperCase();

    if (desiredStrategy === CHAT_DEFAULT_CONFIG.strategy && desiredSlots === String(CHAT_DEFAULT_CONFIG.numSlots)) {
        return;
    }

    if (phase === "RUNNING") {
        return;
    }

    state.chatDefaultRuntimePending = true;

    try {
        const response = await fetch("/api/v1/metrics/config", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                strategy: CHAT_DEFAULT_CONFIG.strategy,
                num_slots: String(CHAT_DEFAULT_CONFIG.numSlots)
            })
        });

        if (response.ok) {
            setTimeout(() => {
                refreshMetrics();
            }, 150);
        }
    } catch (error) {
        // Leave the current runtime untouched if the config endpoint is unavailable.
    } finally {
        state.chatDefaultRuntimePending = false;
    }
}

function escapeHtml(value) {
    const container = document.createElement("div");
    container.textContent = value == null ? "" : String(value);
    return container.innerHTML;
}

setInterval(() => {
    const chatOpen = document.getElementById("tab-chat").classList.contains("active");
    const benchmarkOpen = document.getElementById("tab-benchmark").classList.contains("active");
    const runtimeOpen = document.getElementById("tab-runtime").classList.contains("active");

    if (chatOpen || benchmarkOpen || runtimeOpen) {
        refreshMetrics();
    }
}, 2000);

resizePromptInput();
loadChatHistory();
loadBenchmarks();
refreshMetrics();
syncStrategyDependentControls();
