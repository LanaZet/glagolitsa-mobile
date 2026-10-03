// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package metrics

import (
	"net/http"
	"strconv"
	"sync"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Prometheus implements metrics.Interface with Mattermost-style counters.
type Prometheus struct {
	httpRequests    *prometheus.CounterVec
	httpDuration    *prometheus.HistogramVec
	httpInFlight    prometheus.Gauge
	messageCreate   prometheus.Counter
	relayEnqueue    prometheus.Counter
	wsEvents        *prometheus.CounterVec
	wsDropped       prometheus.Counter
	wsConnections   prometheus.Gauge
	jobActive       *prometheus.GaugeVec
	dedupHits       *prometheus.CounterVec
	pushSent        *prometheus.CounterVec
	pushFailed      *prometheus.CounterVec
	pushSkipped     *prometheus.CounterVec
	pushInvalidated prometheus.Counter
}

var (
	defaultProm     *Prometheus
	defaultPromOnce sync.Once
)

func Default() Interface {
	defaultPromOnce.Do(func() {
		defaultProm = NewPrometheus()
	})
	return defaultProm
}

func NewPrometheus() *Prometheus {
	return &Prometheus{
		httpRequests: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_http_requests_total",
			Help: "Total HTTP requests",
		}, []string{"route", "method", "status"}),
		httpDuration: promauto.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "glagolitsa_http_request_duration_seconds",
			Help:    "HTTP request latency",
			Buckets: prometheus.DefBuckets,
		}, []string{"route"}),
		httpInFlight: promauto.NewGauge(prometheus.GaugeOpts{
			Name: "glagolitsa_http_inflight_requests",
			Help: "HTTP handlers currently executing (detect hung writes)",
		}),
		messageCreate: promauto.NewCounter(prometheus.CounterOpts{
			Name: "glagolitsa_messages_created_total",
			Help: "Messages created",
		}),
		relayEnqueue: promauto.NewCounter(prometheus.CounterOpts{
			Name: "glagolitsa_relay_envelopes_total",
			Help: "Relay envelopes enqueued",
		}),
		wsEvents: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_websocket_events_total",
			Help: "WebSocket events sent",
		}, []string{"event"}),
		wsDropped: promauto.NewCounter(prometheus.CounterOpts{
			Name: "glagolitsa_websocket_events_dropped_total",
			Help: "WebSocket events dropped due to backpressure",
		}),
		wsConnections: promauto.NewGauge(prometheus.GaugeOpts{
			Name: "glagolitsa_websocket_connections",
			Help: "Active websocket client connections",
		}),
		jobActive: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "glagolitsa_jobs_active",
			Help: "Active background jobs",
		}, []string{"type"}),
		dedupHits: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_dedup_hits_total",
			Help: "Idempotent delivery cache hits",
		}, []string{"kind"}),
		pushSent: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_push_sent_total",
			Help: "Push notifications accepted by OSPNS adapters",
		}, []string{"platform", "type"}),
		pushFailed: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_push_failed_total",
			Help: "Push notification send failures",
		}, []string{"platform", "type"}),
		pushSkipped: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "glagolitsa_push_skipped_total",
			Help: "Push notifications skipped (prefs, debounce, online, no token)",
		}, []string{"reason"}),
		pushInvalidated: promauto.NewCounter(prometheus.CounterOpts{
			Name: "glagolitsa_push_token_invalidated_total",
			Help: "Push tokens marked invalid after permanent OSPNS errors",
		}),
	}
}

func (p *Prometheus) IncrementHTTPRequest(route, method, status string) {
	p.httpRequests.WithLabelValues(route, method, status).Inc()
}

func (p *Prometheus) ObserveHTTPDuration(route string, seconds float64) {
	p.httpDuration.WithLabelValues(route).Observe(seconds)
}

func (p *Prometheus) IncHTTPInFlight() { p.httpInFlight.Inc() }

func (p *Prometheus) DecHTTPInFlight() { p.httpInFlight.Dec() }

func (p *Prometheus) IncrementMessageCreate() { p.messageCreate.Inc() }

func (p *Prometheus) IncrementRelayEnqueue(count int) {
	p.relayEnqueue.Add(float64(count))
}

func (p *Prometheus) IncrementWSEvent(eventType string) {
	p.wsEvents.WithLabelValues(eventType).Inc()
}

func (p *Prometheus) IncrementWSDropped() { p.wsDropped.Inc() }

func (p *Prometheus) SetWSConnections(count float64) { p.wsConnections.Set(count) }

func (p *Prometheus) IncrementJobActive(jobType string) {
	p.jobActive.WithLabelValues(jobType).Inc()
}

func (p *Prometheus) DecrementJobActive(jobType string) {
	p.jobActive.WithLabelValues(jobType).Dec()
}

func (p *Prometheus) IncrementDedupHit(kind string) {
	p.dedupHits.WithLabelValues(kind).Inc()
}

func (p *Prometheus) IncrementPushSent(platform, notificationType string) {
	p.pushSent.WithLabelValues(platform, notificationType).Inc()
}

func (p *Prometheus) IncrementPushFailed(platform, notificationType string) {
	p.pushFailed.WithLabelValues(platform, notificationType).Inc()
}

func (p *Prometheus) IncrementPushSkipped(reason string) {
	p.pushSkipped.WithLabelValues(reason).Inc()
}

func (p *Prometheus) IncrementPushTokenInvalidated() {
	p.pushInvalidated.Inc()
}

func Handler() http.Handler {
	return promhttp.Handler()
}

func EnabledFromEnv(value string) bool {
	return value == "1" || value == "true" || value == "yes"
}

func StatusLabel(code int) string {
	return strconv.Itoa(code)
}
