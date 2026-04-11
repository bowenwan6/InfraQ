// InfraQ Dashboard — app.js

// ============================================================
// Tab Navigation
// ============================================================
document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById('tab-' + btn.dataset.tab).classList.add('active');

        // Load data when switching to certain tabs
        if (btn.dataset.tab === 'results') loadResults();
        if (btn.dataset.tab === 'benchmark') loadBenchmarks();
        if (btn.dataset.tab === 'metrics') refreshMetrics();
    });
});

// ============================================================
// Submit Tab
// ============================================================
async function submitRequest() {
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) return alert('Please enter a prompt');

    const body = {
        prompt,
        taskType: document.getElementById('taskType').value,
        priority: parseInt(document.getElementById('priority').value)
    };

    const res = await fetch('/api/v1/infer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    const data = await res.json();

    // Show result box and start polling
    const box = document.getElementById('submit-result');
    box.classList.remove('hidden');
    document.getElementById('submit-badge').textContent = 'QUEUED';
    document.getElementById('submit-badge').className = 'badge queued';
    document.getElementById('submit-id').textContent = data.id;
    document.getElementById('submit-response').textContent = 'Waiting for worker...';
    document.getElementById('submit-timing').style.display = 'none';

    pollResult(data.id);
}

function pollResult(id) {
    const poll = setInterval(async () => {
        const res = await fetch('/api/v1/infer/' + id);
        const data = await res.json();
        const badge = document.getElementById('submit-badge');

        badge.textContent = data.status;
        badge.className = 'badge ' + data.status.toLowerCase();

        if (data.status === 'PROCESSING') {
            document.getElementById('submit-response').textContent = 'Processing by worker...';
        }

        if (data.status === 'COMPLETED') {
            clearInterval(poll);
            document.getElementById('submit-response').textContent = data.result || '';
            document.getElementById('submit-timing').style.display = 'flex';
            document.getElementById('t-queue').textContent = data.queue_wait_ms ?? '-';
            document.getElementById('t-infer').textContent = data.inference_ms ?? '-';
            document.getElementById('t-total').textContent = data.latency_ms ?? '-';
            document.getElementById('t-cache').textContent = data.cache_hit ? 'HIT' : 'MISS';
            document.getElementById('t-cache').style.color =
                data.cache_hit ? 'var(--green)' : 'var(--text-muted)';
        }

        if (data.status === 'FAILED') {
            clearInterval(poll);
            document.getElementById('submit-response').textContent = 'Error: ' + (data.error || 'unknown');
        }
    }, 500);
}

// ============================================================
// Results Tab
// ============================================================
async function loadResults() {
    const res = await fetch('/api/v1/infer/list?limit=30');
    const data = await res.json();
    const container = document.getElementById('results-list');

    if (!data.length) {
        container.innerHTML = '<p style="color:var(--text-muted)">No requests yet.</p>';
        return;
    }

    container.innerHTML = data.map(r => `
        <div class="history-item" onclick="this.querySelector('.full-output').classList.toggle('hidden')">
            <div>
                <span class="badge ${(r.status || '').toLowerCase()}">${r.status || 'UNKNOWN'}</span>
                <span class="prompt-preview" style="margin-left:12px">${escapeHtml((r.prompt || '').substring(0, 80))}</span>
            </div>
            <div style="font-size:12px; color:var(--text-muted)">
                ${r.total_latency_ms ? r.total_latency_ms + 'ms' : '-'}
                ${r.cache_hit ? '<span style="color:var(--green)">CACHED</span>' : ''}
            </div>
            <div class="full-output hidden" style="width:100%; margin-top:8px">
                <pre style="font-size:12px">${escapeHtml(r.output || '')}</pre>
            </div>
        </div>
    `).join('');
}

// ============================================================
// Benchmark Tab
// ============================================================
let latencyChart = null;
let breakdownChart = null;
let compareLatencyChart = null;
let compareThroughputChart = null;

async function runBenchmark() {
    const config = {
        numRequests: parseInt(document.getElementById('bench-num').value),
        strategy: document.getElementById('bench-strategy').value,
        numSlots: parseInt(document.getElementById('bench-slots').value)
    };

    document.getElementById('bench-start-btn').disabled = true;
    const res = await fetch('/api/v1/benchmark', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
    });
    const data = await res.json();
    const benchId = data.benchmark_id;

    // Show progress
    const progBox = document.getElementById('bench-progress');
    progBox.classList.remove('hidden');
    document.getElementById('bench-results').classList.add('hidden');

    // Poll for completion
    const poll = setInterval(async () => {
        const r = await fetch('/api/v1/benchmark/' + benchId);
        const result = await r.json();

        const completed = result.completed || 0;
        const total = result.total_requests || config.numRequests;
        const pct = Math.round((completed / total) * 100);

        document.getElementById('bench-bar').value = pct;
        document.getElementById('bench-pct').textContent = `${completed} / ${total}`;

        if (result.status === 'COMPLETED') {
            clearInterval(poll);
            document.getElementById('bench-status-badge').textContent = 'COMPLETED';
            document.getElementById('bench-status-badge').className = 'badge completed';
            document.getElementById('bench-start-btn').disabled = false;
            renderBenchmarkResults(result);
            loadBenchmarks();
        }
    }, 1000);
}

function renderBenchmarkResults(data) {
    document.getElementById('bench-results').classList.remove('hidden');

    // Summary table
    const tbody = document.getElementById('bench-summary').querySelector('tbody');
    const metrics = [
        ['Strategy', data.strategy || '-'],
        ['Concurrent Slots', data.num_slots || '-'],
        ['Total Requests', data.total_requests || '-'],
        ['Avg Latency', fmt(data.avg_latency_ms) + ' ms'],
        ['P50 Latency', fmt(data.p50_latency_ms) + ' ms'],
        ['P95 Latency', fmt(data.p95_latency_ms) + ' ms'],
        ['P99 Latency', fmt(data.p99_latency_ms) + ' ms'],
        ['Throughput', fmt(data.throughput_rps) + ' req/s'],
        ['Cache Hit Rate', fmt((data.cache_hit_rate || 0) * 100) + '%'],
        ['Avg Queue Wait', fmt(data.avg_queue_wait) + ' ms']
    ];
    tbody.innerHTML = metrics.map(([k, v]) =>
        `<tr><th>${k}</th><td>${v}</td></tr>`
    ).join('');

    // Latency distribution chart
    const requests = data.requests || [];
    const latencies = requests.map(r => r.latency_ms).filter(x => x != null);

    if (latencyChart) latencyChart.destroy();
    latencyChart = new Chart(document.getElementById('chart-latency'), {
        type: 'bar',
        data: {
            labels: latencies.map((_, i) => i + 1),
            datasets: [{
                label: 'Latency (ms)',
                data: latencies,
                backgroundColor: requests.map(r =>
                    r.cache_hit ? '#22c55e' : '#6366f1'
                )
            }]
        },
        options: {
            responsive: true,
            plugins: {
                title: { display: true, text: 'Per-Request Latency', color: '#e1e4ed' },
                legend: { display: false }
            },
            scales: {
                x: { title: { display: true, text: 'Request #', color: '#8b8fa3' }, ticks: { color: '#8b8fa3' } },
                y: { title: { display: true, text: 'ms', color: '#8b8fa3' }, ticks: { color: '#8b8fa3' } }
            }
        }
    });

    // Queue wait vs Inference time breakdown
    const queueWaits = requests.map(r => r.queue_wait_ms || 0);
    const inferenceTimes = requests.map(r => r.inference_ms || 0);

    if (breakdownChart) breakdownChart.destroy();
    breakdownChart = new Chart(document.getElementById('chart-breakdown'), {
        type: 'bar',
        data: {
            labels: requests.map((_, i) => i + 1),
            datasets: [
                { label: 'Queue Wait', data: queueWaits, backgroundColor: '#eab308' },
                { label: 'Inference', data: inferenceTimes, backgroundColor: '#6366f1' }
            ]
        },
        options: {
            responsive: true,
            plugins: {
                title: { display: true, text: 'Time Breakdown', color: '#e1e4ed' }
            },
            scales: {
                x: { stacked: true, ticks: { color: '#8b8fa3' } },
                y: { stacked: true, title: { display: true, text: 'ms', color: '#8b8fa3' }, ticks: { color: '#8b8fa3' } }
            }
        }
    });
}

async function loadBenchmarks() {
    const res = await fetch('/api/v1/benchmark');
    const runs = await res.json();
    const container = document.getElementById('bench-history');

    if (!runs.length) {
        container.innerHTML = '<p style="color:var(--text-muted)">No benchmarks yet.</p>';
        return;
    }

    container.innerHTML = runs.map(r => `
        <div class="history-item">
            <div>
                <span class="badge ${(r.status || '').toLowerCase()}">${r.status}</span>
                <span style="margin-left:12px; font-weight:600">${r.label || r.strategy}</span>
            </div>
            <div style="font-size:12px; color:var(--text-muted)">
                ${r.total_requests} reqs |
                ${fmt(r.avg_latency_ms)}ms avg |
                ${fmt(r.throughput_rps)} req/s |
                ${fmt((r.cache_hit_rate || 0) * 100)}% cache
            </div>
        </div>
    `).join('');

    // Update comparison charts
    renderComparison(runs.filter(r => r.status === 'COMPLETED'));
}

function renderComparison(runs) {
    if (runs.length < 2) return;

    const labels = runs.map(r => r.label || r.strategy);
    const colors = ['#6366f1', '#22c55e', '#eab308', '#ef4444', '#3b82f6', '#f97316'];

    // Latency comparison
    if (compareLatencyChart) compareLatencyChart.destroy();
    compareLatencyChart = new Chart(document.getElementById('chart-compare-latency'), {
        type: 'bar',
        data: {
            labels,
            datasets: [
                { label: 'P50', data: runs.map(r => r.p50_latency_ms), backgroundColor: '#6366f1' },
                { label: 'P95', data: runs.map(r => r.p95_latency_ms), backgroundColor: '#eab308' },
                { label: 'P99', data: runs.map(r => r.p99_latency_ms), backgroundColor: '#ef4444' }
            ]
        },
        options: {
            responsive: true,
            plugins: {
                title: { display: true, text: 'Latency Comparison (ms)', color: '#e1e4ed' }
            },
            scales: {
                x: { ticks: { color: '#8b8fa3' } },
                y: { title: { display: true, text: 'ms', color: '#8b8fa3' }, ticks: { color: '#8b8fa3' } }
            }
        }
    });

    // Throughput comparison
    if (compareThroughputChart) compareThroughputChart.destroy();
    compareThroughputChart = new Chart(document.getElementById('chart-compare-throughput'), {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Throughput (req/s)',
                data: runs.map(r => r.throughput_rps),
                backgroundColor: colors.slice(0, runs.length)
            }]
        },
        options: {
            responsive: true,
            plugins: {
                title: { display: true, text: 'Throughput Comparison', color: '#e1e4ed' }
            },
            scales: {
                x: { ticks: { color: '#8b8fa3' } },
                y: { title: { display: true, text: 'req/s', color: '#8b8fa3' }, ticks: { color: '#8b8fa3' } }
            }
        }
    });
}

// ============================================================
// Metrics Tab
// ============================================================
let metricsInterval = null;

async function refreshMetrics() {
    try {
        const res = await fetch('/api/v1/metrics');
        const m = await res.json();
        document.getElementById('m-submitted').textContent = m.total_submitted || 0;
        document.getElementById('m-completed').textContent = m.total_completed || 0;
        document.getElementById('m-cache').textContent = m.total_cache_hits || 0;
        document.getElementById('m-failed').textContent = m.total_failed || 0;
        document.getElementById('m-queue').textContent = m.queue_depth >= 0 ? m.queue_depth : '?';
        document.getElementById('m-strategy').textContent = m.active_strategy || '-';
        document.getElementById('m-slots').textContent = m.active_slots || '-';
    } catch (e) {
        // Gateway not reachable
    }
}

async function updateConfig() {
    const config = {
        strategy: document.getElementById('config-strategy').value,
        num_slots: document.getElementById('config-slots').value
    };
    await fetch('/api/v1/metrics/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
    });
    refreshMetrics();
}

// Auto-refresh metrics every 2 seconds when on metrics tab
setInterval(() => {
    if (document.getElementById('tab-metrics').classList.contains('active')) {
        refreshMetrics();
    }
}, 2000);

// ============================================================
// Helpers
// ============================================================
function fmt(val) {
    if (val == null) return '-';
    return typeof val === 'number' ? Math.round(val * 100) / 100 : val;
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// Initial load
refreshMetrics();
