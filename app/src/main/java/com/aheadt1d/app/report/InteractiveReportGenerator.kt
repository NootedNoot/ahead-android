package com.aheadt1d.app.report

import android.content.Context
import com.aheadt1d.app.chart.EventMarkerLayout
import com.aheadt1d.app.chart.GapSegmenter
import com.aheadt1d.app.chart.SeverityColoring
import com.aheadt1d.app.events.EventTag
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a single, self-contained HTML file: a JSON blob of precomputed
 * geometry (gap-segmented readings with per-point color, chronologically
 * numbered event markers with full note text, severity legend, summary
 * metrics) embedded next to a small vanilla-JS canvas renderer that does its
 * own pan/zoom/hit-testing against that data. Deliberately "dumb" on the JS
 * side - all domain math (gap detection, marker numbering, severity color)
 * reuses the exact same `com.aheadt1d.app.chart` primitives the PDF and live
 * chart use, so this can never disagree with them about what a gap or a
 * color means. No CDN references, no external assets - opens correctly from
 * a downloaded file:// URL with zero network access.
 *
 * Per the confirmed privacy model: note text is always present in the JSON
 * regardless of the show/hide toggle - the toggle only controls client-side
 * DOM visibility, so a doctor with the raw file can always find it, but a
 * shoulder-surfer glancing at the opened page by default sees none of it.
 */
object InteractiveReportGenerator {
    private const val Y_MIN = 40
    private const val Y_MAX = 400
    private const val TARGET_LOW = 70
    private const val TARGET_HIGH = 180
    private val MAX_CONNECT_GAP: Duration = Duration.ofMinutes(20)

    private val RANGE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")
    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
    private val SLUG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun generate(context: Context, data: ReportData): File = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val html = buildHtml(data, zone)
        val slug = "${SLUG_FORMATTER.withZone(zone).format(data.startDate)}_to_${SLUG_FORMATTER.withZone(zone).format(data.endDate)}"
        val file = File(context.cacheDir, "ahead-report-interactive-$slug.html")
        file.writeText(html)
        file
    }

    /** Pure (no Context/IO) so it's unit-testable and reusable for local
     *  preview generation - [generate] is just this plus the cache-file write. */
    fun buildHtml(data: ReportData, zone: ZoneId = ZoneId.systemDefault()): String {
        val json = buildJson(data, zone)
        return HTML_TEMPLATE.replace("__REPORT_JSON__", escapeForScript(json.toString()))
    }

    /** `</script` inside embedded JSON (e.g. a note that mentions a tag) would
     *  otherwise close the surrounding <script> tag early - split it so the
     *  browser's HTML parser never sees the literal sequence. */
    private fun escapeForScript(json: String): String = json.replace("</", "<\\/")

    private fun buildJson(data: ReportData, zone: ZoneId): JSONObject {
        val segments = JSONArray()
        GapSegmenter.segment(data.readings, MAX_CONNECT_GAP).forEach { segment ->
            val points = JSONArray()
            segment.forEach { point ->
                points.put(
                    JSONObject().apply {
                        put("t", point.time.toEpochMilli())
                        put("v", point.sgv)
                        put("c", hex(SeverityColoring.colorInt(point.sgv)))
                    }
                )
            }
            segments.put(points)
        }

        val markers = JSONArray()
        EventMarkerLayout.buildEventMarkers(data.events).forEach { marker ->
            val tag = EventTag.fromStorageValue(marker.event.tag)
            markers.put(
                JSONObject().apply {
                    put("number", marker.number)
                    put("t", marker.event.timestamp)
                    put("tag", tag.label)
                    put("glyph", tag.glyph)
                    put("note", marker.event.note ?: "")
                    put("glucoseAtTime", marker.event.glucoseAtTime?.toDouble() ?: JSONObject.NULL)
                }
            )
        }

        val legend = JSONArray()
        SeverityColoring.SEVERITY_LEGEND.forEach { (label, color) ->
            legend.put(JSONObject().apply { put("label", label); put("color", hex(color)) })
        }

        return JSONObject().apply {
            put("startMillis", data.startDate.toEpochMilli())
            put("endMillis", data.endDate.toEpochMilli())
            put("yMin", Y_MIN)
            put("yMax", Y_MAX)
            put("targetLow", TARGET_LOW)
            put("targetHigh", TARGET_HIGH)
            put("segments", segments)
            put("markers", markers)
            put("legend", legend)
            put("rangeLabel", "${RANGE_FORMATTER.withZone(zone).format(data.startDate)} – ${RANGE_FORMATTER.withZone(zone).format(data.endDate)}")
            put("generatedLabel", "Generated by Ahead - ${TIMESTAMP_FORMATTER.withZone(zone).format(Instant.now())}")
            put(
                "metrics",
                JSONObject().apply {
                    put("mean", data.metrics.meanGlucose)
                    put("gmi", data.metrics.gmi)
                    put("cv", data.metrics.coefficientOfVariation)
                    put("tir", data.metrics.timeInRangePercent)
                    put("readingsCount", data.metrics.readingsCount)
                    put("daysOfData", data.metrics.daysOfData)
                }
            )
        }
    }

    private fun hex(colorInt: Int): String = "#%06X".format(0xFFFFFF and colorInt)

    private val HTML_TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>Ahead Glucose Report</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin: 0; padding: 0; background: #ffffff; font-family: -apple-system, Roboto, Helvetica, Arial, sans-serif; color: #222; overscroll-behavior: none; }
  #header { padding: 14px 16px 8px; border-bottom: 1px solid #eee; }
  #header h1 { font-size: 18px; margin: 0 0 4px; }
  #header .sub { font-size: 12px; color: #666; margin: 0 0 2px; }
  #metrics { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 10px; }
  #metrics div { font-size: 11px; color: #666; }
  #metrics b { display: block; font-size: 15px; color: #222; }
  #legend { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; font-size: 11px; color: #666; }
  #legend span.swatch { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }
  #toggleRow { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 12px; color: #444; }
  #chartWrap { position: relative; width: 100%; height: 62vh; min-height: 320px; touch-action: none; }
  #chart { width: 100%; height: 100%; display: block; }
  #hint { padding: 6px 16px 14px; font-size: 11px; color: #999; }
  #tooltip, #popup { position: absolute; display: none; background: rgba(20,20,20,0.92); color: #fff; padding: 8px 10px; border-radius: 8px; font-size: 12px; pointer-events: none; max-width: 240px; line-height: 1.4; z-index: 5; }
  #popup { pointer-events: auto; }
  #popup .close { display: block; margin-top: 6px; color: #9cf; font-size: 11px; text-align: right; }
  #resetZoom { position: absolute; top: 8px; right: 8px; background: rgba(107,63,160,0.9); color: #fff; border: none; border-radius: 6px; padding: 5px 9px; font-size: 11px; display: none; }
</style>
</head>
<body>
  <div id="header">
    <h1>Ahead Glucose Report</h1>
    <p class="sub" id="rangeLabel"></p>
    <p class="sub" id="generatedLabel"></p>
    <div id="metrics"></div>
    <div id="legend"></div>
    <div id="toggleRow">
      <input type="checkbox" id="annotationsToggle">
      <label for="annotationsToggle">Show personal notes/annotations</label>
    </div>
  </div>
  <div id="chartWrap">
    <canvas id="chart"></canvas>
    <button id="resetZoom">Reset zoom</button>
    <div id="tooltip"></div>
    <div id="popup"></div>
  </div>
  <p id="hint">Drag to pan &middot; pinch or scroll to zoom &middot; tap and hold a point for its exact value &middot; tap a numbered marker for its note.</p>
<script>
(function () {
  "use strict";
  var REPORT = __REPORT_JSON__;

  var canvas = document.getElementById("chart");
  var ctx = canvas.getContext("2d");
  var wrap = document.getElementById("chartWrap");
  var tooltip = document.getElementById("tooltip");
  var popup = document.getElementById("popup");
  var resetZoomBtn = document.getElementById("resetZoom");
  var annotationsToggle = document.getElementById("annotationsToggle");

  document.getElementById("rangeLabel").textContent = REPORT.rangeLabel;
  document.getElementById("generatedLabel").textContent = REPORT.generatedLabel;

  var metricsEl = document.getElementById("metrics");
  var m = REPORT.metrics;
  var metricRows = [
    ["Mean glucose", Math.round(m.mean) + " mg/dL"],
    ["GMI (est. A1C)", m.gmi.toFixed(1) + "%"],
    ["CV", Math.round(m.cv) + "%"],
    ["Time in range", Math.round(m.tir) + "%"],
    ["Coverage", m.daysOfData.toFixed(1) + " days"]
  ];
  metricRows.forEach(function (row) {
    var div = document.createElement("div");
    div.innerHTML = "<b>" + row[1] + "</b>" + row[0];
    metricsEl.appendChild(div);
  });

  var legendEl = document.getElementById("legend");
  REPORT.legend.forEach(function (entry) {
    var span = document.createElement("span");
    span.innerHTML = "<span class=\"swatch\" style=\"background:" + entry.color + "\"></span>" + entry.label;
    legendEl.appendChild(span);
  });

  var FULL_START = REPORT.startMillis;
  var FULL_END = Math.max(REPORT.endMillis, REPORT.startMillis + 1);
  var viewStart = FULL_START;
  var viewEnd = FULL_END;
  var MIN_WINDOW_MS = 2 * 60 * 1000;

  // Bottom margin must fit both the x-axis date label AND up to
  // MARKER_MAX_ROWS stacked rows of event-marker numbers below it (rows
  // start at plotBottom+34, each MARKER_ROW_HEIGHT=15 apart) - mirrors
  // GlucoseReportChartRenderer's own MARGIN_BOTTOM=132, calibrated for this
  // exact same content. A smaller value here left the later marker rows
  // landing past the canvas edge, invisible, once real data needed 3+ rows.
  var MARGIN_LEFT = 46, MARGIN_RIGHT = 14, MARGIN_TOP = 14, MARGIN_BOTTOM = 132;
  var MARKER_ROW_HEIGHT = 15, MARKER_MIN_SPACING = 16, MARKER_MAX_ROWS = 5;
  var Y_VALUES = [40, 70, 180, 250, 400];
  var Y_PRIORITY = [70, 180, 40, 250, 400];

  var dpr = Math.max(1, window.devicePixelRatio || 1);
  var cssWidth = 0, cssHeight = 0;
  var lastMarkerHits = []; // {x, y, marker} in CSS px, refreshed every render

  function resize(width, height) {
    cssWidth = width;
    cssHeight = height;
    if (cssWidth <= 0 || cssHeight <= 0) return;
    canvas.width = Math.round(cssWidth * dpr);
    canvas.height = Math.round(cssHeight * dpr);
    canvas.style.width = cssWidth + "px";
    canvas.style.height = cssHeight + "px";
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    render();
  }

  function plotLeft() { return MARGIN_LEFT; }
  function plotRight() { return cssWidth - MARGIN_RIGHT; }
  function plotTop() { return MARGIN_TOP; }
  function plotBottom() { return cssHeight - MARGIN_BOTTOM; }

  function xForTime(t) {
    var frac = (t - viewStart) / (viewEnd - viewStart);
    return plotLeft() + clamp(frac, -0.2, 1.2) * (plotRight() - plotLeft());
  }
  function timeForX(x) {
    var frac = (x - plotLeft()) / (plotRight() - plotLeft());
    return viewStart + frac * (viewEnd - viewStart);
  }
  function yForValue(v) {
    var frac = clamp((v - REPORT.yMin) / (REPORT.yMax - REPORT.yMin), 0, 1);
    return plotBottom() - frac * (plotBottom() - plotTop());
  }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  var MONTHS = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
  function fmtDay(d) { return MONTHS[d.getMonth()] + " " + d.getDate(); }
  function fmtHour(d) {
    var h = d.getHours();
    var ampm = h >= 12 ? "PM" : "AM";
    var h12 = h % 12; if (h12 === 0) h12 = 12;
    return MONTHS[d.getMonth()] + " " + d.getDate() + ", " + h12 + " " + ampm;
  }
  function fmtExact(d) {
    var h = d.getHours(), mins = d.getMinutes();
    var ampm = h >= 12 ? "PM" : "AM";
    var h12 = h % 12; if (h12 === 0) h12 = 12;
    var mm = mins < 10 ? "0" + mins : "" + mins;
    return MONTHS[d.getMonth()] + " " + d.getDate() + ", " + h12 + ":" + mm + " " + ampm;
  }

  function xAxisTicks(start, end) {
    var totalDays = (end - start) / 86400000;
    var ticks = [];
    if (totalDays <= 2) {
      for (var i = 0; i <= 6; i++) {
        var t = start + (end - start) * (i / 6);
        ticks.push({ t: t, label: fmtHour(new Date(t)) });
      }
    } else {
      var intervalDays = totalDays <= 7 ? 1 : totalDays <= 21 ? 2 : totalDays <= 60 ? 5 : 14;
      var cur = new Date(start);
      cur.setHours(0, 0, 0, 0);
      var stamps = [];
      while (cur.getTime() <= end) {
        stamps.push(Math.max(cur.getTime(), start));
        cur.setDate(cur.getDate() + intervalDays);
      }
      var last = stamps.length ? stamps[stamps.length - 1] : null;
      if (last === null || (end - last) > intervalDays * 24 * 3600 * 1000 / 2) {
        stamps.push(end);
      } else {
        stamps[stamps.length - 1] = end;
      }
      stamps.forEach(function (t) { ticks.push({ t: t, label: fmtDay(new Date(t)) }); });
    }
    return ticks;
  }

  function render() {
    ctx.clearRect(0, 0, cssWidth, cssHeight);
    var left = plotLeft(), right = plotRight(), top = plotTop(), bottom = plotBottom();

    // Target band
    ctx.fillStyle = "rgba(61,220,151,0.10)";
    ctx.fillRect(left, yForValue(REPORT.targetHigh), right - left, yForValue(REPORT.targetLow) - yForValue(REPORT.targetHigh));

    // Y gridlines + labels (fixed set, static collision result)
    ctx.strokeStyle = "#e2e2e2";
    ctx.lineWidth = 1;
    ctx.fillStyle = "#666";
    ctx.font = "11px sans-serif";
    var placed = [];
    var shown = {};
    Y_PRIORITY.forEach(function (v) {
      var y = yForValue(v);
      var collides = placed.some(function (py) { return Math.abs(py - y) < 20; });
      if (!collides) { shown[v] = true; placed.push(y); }
    });
    Y_VALUES.forEach(function (v) {
      var y = yForValue(v);
      ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke();
      if (shown[v]) ctx.fillText(String(v), 6, y + 4);
    });

    // X ticks
    ctx.fillStyle = "#666";
    ctx.textAlign = "center";
    xAxisTicks(viewStart, viewEnd).forEach(function (tick) {
      var x = xForTime(tick.t);
      if (x < left - 30 || x > right + 30) return;
      ctx.fillText(tick.label, x, bottom + 18);
    });
    ctx.textAlign = "left";

    // Segments
    REPORT.segments.forEach(function (segment) {
      for (var i = 1; i < segment.length; i++) {
        var p0 = segment[i - 1], p1 = segment[i];
        var x0 = xForTime(p0.t), x1 = xForTime(p1.t);
        if (x1 < left - 4 || x0 > right + 4) continue;
        ctx.strokeStyle = p0.c;
        ctx.lineWidth = 2.5;
        ctx.lineCap = "round";
        ctx.beginPath();
        ctx.moveTo(x0, yForValue(p0.v));
        ctx.lineTo(x1, yForValue(p1.v));
        ctx.stroke();
      }
    });

    // Event markers - recomputed every render, so zooming spreads them apart
    // with no baked-in layout.
    lastMarkerHits = [];
    if (annotationsToggle.checked) {
      var visible = REPORT.markers.filter(function (mk) {
        var x = xForTime(mk.t);
        return x >= left - 20 && x <= right + 20;
      });
      var lastXPerRow = new Array(MARKER_MAX_ROWS).fill(-Infinity);
      var baseY = bottom + 34;
      ctx.textAlign = "center";
      ctx.font = "bold 12px sans-serif";
      ctx.fillStyle = "#6B3FA0";
      visible.forEach(function (mk) {
        var x = xForTime(mk.t);
        var row = 0;
        while (row < MARKER_MAX_ROWS - 1 && x - lastXPerRow[row] < MARKER_MIN_SPACING) row++;
        lastXPerRow[row] = x;
        var y = baseY + row * MARKER_ROW_HEIGHT;
        ctx.fillText(String(mk.number), x, y);
        lastMarkerHits.push({ x: x, y: y, marker: mk });
      });
      ctx.textAlign = "left";
      ctx.font = "11px sans-serif";
    }

    resetZoomBtn.style.display = (viewStart > FULL_START + 1000 || viewEnd < FULL_END - 1000) ? "block" : "none";
  }

  // ---- Pan / zoom ----
  var dragging = false, dragLastX = 0;
  var pinchStartDist = 0, pinchStartSpan = 0, pinchAnchorTime = 0;
  var holdTimer = null, holdFired = false;
  var pointerDownPos = null;

  function windowSpan() { return viewEnd - viewStart; }

  function panBy(dxPx) {
    var deltaMs = -dxPx / (plotRight() - plotLeft()) * windowSpan();
    shiftView(deltaMs);
  }
  function shiftView(deltaMs) {
    var span = windowSpan();
    var newStart = viewStart + deltaMs;
    var newEnd = viewEnd + deltaMs;
    if (newStart < FULL_START) { newStart = FULL_START; newEnd = newStart + span; }
    if (newEnd > FULL_END) { newEnd = FULL_END; newStart = newEnd - span; }
    viewStart = newStart; viewEnd = newEnd;
    render();
  }
  function zoomAt(anchorTime, factor) {
    var span = clamp(windowSpan() * factor, MIN_WINDOW_MS, FULL_END - FULL_START);
    var anchorFrac = (anchorTime - viewStart) / windowSpan();
    var newStart = anchorTime - anchorFrac * span;
    var newEnd = newStart + span;
    if (newStart < FULL_START) { newStart = FULL_START; newEnd = newStart + span; }
    if (newEnd > FULL_END) { newEnd = FULL_END; newStart = newEnd - span; }
    viewStart = newStart; viewEnd = newEnd;
    render();
  }

  resetZoomBtn.addEventListener("click", function () {
    viewStart = FULL_START; viewEnd = FULL_END; render();
  });
  annotationsToggle.addEventListener("change", function () {
    popup.style.display = "none";
    render();
  });

  function hideTooltip() { tooltip.style.display = "none"; }
  function hidePopup() { popup.style.display = "none"; }

  function nearestPoint(x) {
    var targetTime = timeForX(x);
    var best = null, bestDist = Infinity;
    REPORT.segments.forEach(function (segment) {
      segment.forEach(function (p) {
        var d = Math.abs(p.t - targetTime);
        if (d < bestDist) { bestDist = d; best = p; }
      });
    });
    return best;
  }

  function showTooltipAt(clientX, clientY, point) {
    var d = new Date(point.t);
    tooltip.innerHTML = "<b>" + point.v + " mg/dL</b><br>" + fmtExact(d);
    tooltip.style.left = Math.min(clientX + 10, cssWidth - 150) + "px";
    tooltip.style.top = Math.max(clientY - 50, 4) + "px";
    tooltip.style.display = "block";
  }

  function hitMarker(x, y) {
    for (var i = lastMarkerHits.length - 1; i >= 0; i--) {
      var h = lastMarkerHits[i];
      if (Math.abs(h.x - x) < 12 && Math.abs(h.y - y) < 12) return h.marker;
    }
    return null;
  }

  function showPopupFor(marker, clientX, clientY) {
    var d = new Date(marker.t);
    var note = marker.note ? marker.note : "(no note)";
    popup.innerHTML = "<b>" + marker.glyph + " " + marker.tag + "</b><br>" + fmtExact(d) + "<br>" + escapeHtml(note) + "<span class=\"close\">tap to close</span>";
    popup.style.left = Math.min(Math.max(clientX - 100, 4), cssWidth - 210) + "px";
    popup.style.top = Math.max(clientY - 90, 4) + "px";
    popup.style.display = "block";
  }
  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  popup.addEventListener("click", hidePopup);

  function localXY(evt) {
    var rect = canvas.getBoundingClientRect();
    var touch = evt.touches && evt.touches.length ? evt.touches[0] : evt;
    return { x: touch.clientX - rect.left, y: touch.clientY - rect.top, clientX: touch.clientX - rect.left, clientY: touch.clientY - rect.top };
  }
  function touchDist(t0, t1) {
    return Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
  }

  canvas.addEventListener("pointerdown", function (evt) {
    hideTooltip();
    dragging = true;
    dragLastX = evt.clientX;
    pointerDownPos = { x: evt.clientX, y: evt.clientY };
    holdFired = false;
    clearTimeout(holdTimer);
    holdTimer = setTimeout(function () {
      holdFired = true;
      var rect = canvas.getBoundingClientRect();
      var x = evt.clientX - rect.left, y = evt.clientY - rect.top;
      var p = nearestPoint(x);
      if (p) showTooltipAt(x, y, p);
    }, 420);
    canvas.setPointerCapture(evt.pointerId);
  });
  canvas.addEventListener("pointermove", function (evt) {
    if (!dragging) return;
    var moved = Math.hypot(evt.clientX - pointerDownPos.x, evt.clientY - pointerDownPos.y);
    if (moved > 8) {
      clearTimeout(holdTimer);
      hideTooltip();
      var dx = evt.clientX - dragLastX;
      dragLastX = evt.clientX;
      panBy(dx);
    }
  });
  function endPointer(evt) {
    clearTimeout(holdTimer);
    dragging = false;
    if (!holdFired && pointerDownPos) {
      var moved = Math.hypot(evt.clientX - pointerDownPos.x, evt.clientY - pointerDownPos.y);
      if (moved < 8) {
        var rect = canvas.getBoundingClientRect();
        var x = evt.clientX - rect.left, y = evt.clientY - rect.top;
        var marker = hitMarker(x, y);
        if (marker) showPopupFor(marker, x, y); else hidePopup();
      }
    }
    hideTooltip();
    pointerDownPos = null;
  }
  canvas.addEventListener("pointerup", endPointer);
  canvas.addEventListener("pointercancel", endPointer);

  canvas.addEventListener("wheel", function (evt) {
    evt.preventDefault();
    var rect = canvas.getBoundingClientRect();
    var x = evt.clientX - rect.left;
    var factor = evt.deltaY > 0 ? 1.15 : 1 / 1.15;
    zoomAt(timeForX(x), factor);
  }, { passive: false });

  canvas.addEventListener("touchstart", function (evt) {
    if (evt.touches.length === 2) {
      clearTimeout(holdTimer);
      pinchStartDist = touchDist(evt.touches[0], evt.touches[1]);
      pinchStartSpan = windowSpan();
      var rect = canvas.getBoundingClientRect();
      var midX = (evt.touches[0].clientX + evt.touches[1].clientX) / 2 - rect.left;
      pinchAnchorTime = timeForX(midX);
    }
  }, { passive: true });
  canvas.addEventListener("touchmove", function (evt) {
    if (evt.touches.length === 2 && pinchStartDist > 0) {
      evt.preventDefault();
      var dist = touchDist(evt.touches[0], evt.touches[1]);
      var factor = pinchStartDist / dist;
      var span = clamp(pinchStartSpan * factor, MIN_WINDOW_MS, FULL_END - FULL_START);
      var rect = canvas.getBoundingClientRect();
      var midX = (evt.touches[0].clientX + evt.touches[1].clientX) / 2 - rect.left;
      var anchorFrac = (pinchAnchorTime - viewStart) / windowSpan();
      var newStart = pinchAnchorTime - anchorFrac * span;
      var newEnd = newStart + span;
      if (newStart < FULL_START) { newStart = FULL_START; newEnd = newStart + span; }
      if (newEnd > FULL_END) { newEnd = FULL_END; newStart = newEnd - span; }
      viewStart = newStart; viewEnd = newEnd;
      render();
    }
  }, { passive: false });
  canvas.addEventListener("touchend", function (evt) {
    if (evt.touches.length < 2) pinchStartDist = 0;
  }, { passive: true });

  // Initial sizing/render goes last, after every function and constant above
  // it (MONTHS, xAxisTicks, render, etc.) is fully defined - this HTML can be
  // opened inside a share-sheet preview or an embedding WebView whose layout
  // isn't attached/sized yet at the moment this <script> runs, and even a
  // "resize" event or ResizeObserver firing shortly after isn't guaranteed in
  // every host. Polling every animation frame until a real (non-zero) size
  // shows up is the only approach that works regardless of *why* or *when*
  // the host finishes laying out the container. Placing this call earlier in
  // the script (before later declarations) is unsafe: when the container is
  // already sized on the very first attempt - the common case in a normal
  // browser - resize() calls render() synchronously, mid-script, before any
  // function/const declared further down has actually run.
  function pollUntilSized(attempt) {
    var w = wrap.clientWidth, h = wrap.clientHeight;
    if (w > 0 && h > 0) { resize(w, h); return; }
    if (attempt < 300) requestAnimationFrame(function () { pollUntilSized(attempt + 1); });
  }
  pollUntilSized(0);
  // Once sized, these two keep it correct across rotation/multi-window
  // resizes - ResizeObserver as the primary, "resize" as a fallback for
  // hosts that don't fire it.
  new ResizeObserver(function (entries) {
    var box = entries[0].contentRect;
    resize(box.width, box.height);
  }).observe(wrap);
  window.addEventListener("resize", function () { resize(wrap.clientWidth, wrap.clientHeight); });
})();
</script>
</body>
</html>
""".trimIndent()
}
