// charts.jsx — Chart.js 4 wrappers
// Components: BarChartJS / LineChartJS / DoughnutChartJS / SparkChartJS
// All canvas-based with smooth animations + interactive tooltips

const __CHART_FONT = "'Geist', 'Source Han Sans CN', system-ui, sans-serif";
const __CHART_MONO = "'Geist Mono', monospace";

const __CHART_DEFAULTS = (() => {
  if (typeof Chart !== 'undefined') {
    Chart.defaults.font.family = __CHART_FONT;
    Chart.defaults.font.size = 11;
    Chart.defaults.color = '#94A3B8';
    Chart.defaults.borderColor = '#F1F5F9';
    Chart.defaults.animation.duration = 760;
    Chart.defaults.animation.easing = 'easeOutCubic';
    Chart.defaults.plugins.tooltip = {
      ...Chart.defaults.plugins.tooltip,
      backgroundColor: 'rgba(15,23,42,0.94)',
      titleColor: '#fff',
      bodyColor: '#E2E8F0',
      titleFont: { family: __CHART_FONT, weight: 600, size: 12 },
      bodyFont: { family: __CHART_MONO, size: 11 },
      borderColor: 'rgba(255,255,255,0.08)',
      borderWidth: 1,
      padding: 10,
      cornerRadius: 8,
      displayColors: true,
      boxPadding: 6,
    };
  }
  return true;
})();

function useChart(builderFn, deps) {
  const ref = React.useRef(null);
  const chartRef = React.useRef(null);
  React.useEffect(() => {
    if (typeof Chart === 'undefined' || !ref.current) return;
    if (chartRef.current) {
      try { chartRef.current.destroy(); } catch (e) {}
    }
    chartRef.current = builderFn(ref.current);
    return () => {
      if (chartRef.current) {
        try { chartRef.current.destroy(); } catch (e) {}
        chartRef.current = null;
      }
    };
  }, deps);
  return ref;
}

// ── Bar Chart ───────────────────────────────────────────────────
function BarChartJS({ labels, datasets, height = 260, accent = '#4F46E5', unit = '' }) {
  const ref = useChart((canvas) => {
    const ctx = canvas.getContext('2d');
    // gradient for the bars
    const grad = ctx.createLinearGradient(0, 0, 0, canvas.height);
    grad.addColorStop(0, accent);
    grad.addColorStop(1, accent + '80');
    return new Chart(canvas, {
      type: 'bar',
      data: {
        labels,
        datasets: datasets.map((ds, i) => ({
          backgroundColor: ds.color ? ds.color : grad,
          hoverBackgroundColor: ds.color || accent,
          borderRadius: 6,
          borderSkipped: false,
          barThickness: 24,
          ...ds,
        })),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: datasets.length > 1, position: 'top', align: 'end', labels: { boxWidth: 10, boxHeight: 10 } },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.dataset.label || ''}${ctx.dataset.label ? ': ' : ''}${ctx.parsed.y}${unit}`,
            },
          },
        },
        scales: {
          x: {
            grid: { display: false },
            ticks: { font: { size: 11 } },
          },
          y: {
            beginAtZero: true,
            grid: { color: '#F1F5F9', drawBorder: false },
            ticks: { font: { family: __CHART_MONO, size: 10 }, padding: 8 },
          },
        },
      },
    });
  }, [JSON.stringify(labels), JSON.stringify(datasets), accent]);

  return (
    <div style={{ height, position: 'relative' }}>
      <canvas ref={ref} />
    </div>
  );
}

// ── Line / Area Chart ───────────────────────────────────────────
function LineChartJS({ labels, datasets, height = 220, accent = '#4F46E5', unit = '' }) {
  const ref = useChart((canvas) => {
    const ctx = canvas.getContext('2d');
    return new Chart(canvas, {
      type: 'line',
      data: {
        labels,
        datasets: datasets.map((ds) => {
          const c = ds.color || accent;
          const grad = ctx.createLinearGradient(0, 0, 0, canvas.height);
          grad.addColorStop(0, c + '40');
          grad.addColorStop(1, c + '00');
          return {
            borderColor: c,
            backgroundColor: ds.fill === false ? 'transparent' : grad,
            fill: ds.fill !== false,
            tension: 0.36,
            borderWidth: 2.5,
            pointRadius: 4,
            pointHoverRadius: 6,
            pointBackgroundColor: '#fff',
            pointBorderColor: c,
            pointBorderWidth: 2,
            ...ds,
          };
        }),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: datasets.length > 1, position: 'top', align: 'end', labels: { boxWidth: 10, boxHeight: 10 } },
          tooltip: {
            mode: 'index', intersect: false,
            callbacks: { label: (ctx) => `${ctx.dataset.label || ''}${ctx.dataset.label ? ': ' : ''}${ctx.parsed.y}${unit}` },
          },
        },
        interaction: { mode: 'nearest', axis: 'x', intersect: false },
        scales: {
          x: { grid: { display: false }, ticks: { font: { size: 11 } } },
          y: {
            beginAtZero: true,
            grid: { color: '#F1F5F9', drawBorder: false },
            ticks: { font: { family: __CHART_MONO, size: 10 }, padding: 8 },
          },
        },
      },
    });
  }, [JSON.stringify(labels), JSON.stringify(datasets), accent]);

  return (
    <div style={{ height, position: 'relative' }}>
      <canvas ref={ref} />
    </div>
  );
}

// ── Doughnut Chart ──────────────────────────────────────────────
function DoughnutChartJS({ labels, data, colors, height = 180, cutout = '70%', centerLabel, centerValue }) {
  const ref = useChart((canvas) => {
    return new Chart(canvas, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [{
          data,
          backgroundColor: colors,
          borderColor: '#fff',
          borderWidth: 2,
          hoverOffset: 6,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.label}: ${ctx.parsed} (${((ctx.parsed / data.reduce((a, b) => a + b, 0)) * 100).toFixed(1)}%)`,
            },
          },
        },
      },
    });
  }, [JSON.stringify(labels), JSON.stringify(data), JSON.stringify(colors), cutout]);

  return (
    <div style={{ height, position: 'relative' }}>
      <canvas ref={ref} />
      {(centerLabel !== undefined || centerValue !== undefined) && (
        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
          {centerLabel && (
            <span className="font-mono text-[9.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
              {centerLabel}
            </span>
          )}
          {centerValue !== undefined && (
            <span className="num text-[22px] font-bold mt-0.5" style={{ color: 'var(--text-strong)' }}>
              {centerValue}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

// ── Mini Sparkline (used in KPI cards) ─────────────────────────
function SparkChartJS({ data, color = '#4F46E5', height = 44, width = 120 }) {
  const ref = useChart((canvas) => {
    const ctx = canvas.getContext('2d');
    const grad = ctx.createLinearGradient(0, 0, 0, canvas.height);
    grad.addColorStop(0, color + '30');
    grad.addColorStop(1, color + '00');
    return new Chart(canvas, {
      type: 'line',
      data: {
        labels: data.map((_, i) => i + 1),
        datasets: [{
          data, borderColor: color, backgroundColor: grad,
          fill: true, tension: 0.4, borderWidth: 2,
          pointRadius: 0, pointHoverRadius: 4,
          pointBackgroundColor: '#fff', pointBorderColor: color, pointBorderWidth: 2,
        }],
      },
      options: {
        responsive: false,
        maintainAspectRatio: false,
        plugins: { legend: { display: false }, tooltip: { enabled: true, displayColors: false } },
        scales: { x: { display: false }, y: { display: false } },
        elements: { line: { tension: 0.4 } },
      },
    });
  }, [JSON.stringify(data), color]);

  return <canvas ref={ref} width={width} height={height} style={{ width, height }} />;
}

Object.assign(window, { BarChartJS, LineChartJS, DoughnutChartJS, SparkChartJS });
