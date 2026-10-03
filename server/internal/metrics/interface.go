// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package metrics

// Interface mirrors a subset of Mattermost einterfaces/metrics.go.
type Interface interface {
	IncrementHTTPRequest(route, method, status string)
	ObserveHTTPDuration(route string, seconds float64)
	IncHTTPInFlight()
	DecHTTPInFlight()
	IncrementMessageCreate()
	IncrementRelayEnqueue(count int)
	IncrementWSEvent(eventType string)
	IncrementWSDropped()
	SetWSConnections(count float64)
	IncrementJobActive(jobType string)
	DecrementJobActive(jobType string)
	IncrementDedupHit(kind string)
	// Push (P6)
	IncrementPushSent(platform, notificationType string)
	IncrementPushFailed(platform, notificationType string)
	IncrementPushSkipped(reason string)
	IncrementPushTokenInvalidated()
}

// Noop is used when Prometheus is disabled.
type Noop struct{}

func (Noop) IncrementHTTPRequest(string, string, string) {}
func (Noop) ObserveHTTPDuration(string, float64)         {}
func (Noop) IncHTTPInFlight()                            {}
func (Noop) DecHTTPInFlight()                            {}
func (Noop) IncrementMessageCreate()                     {}
func (Noop) IncrementRelayEnqueue(int)                   {}
func (Noop) IncrementWSEvent(string)                     {}
func (Noop) IncrementWSDropped()                         {}
func (Noop) SetWSConnections(float64)                    {}
func (Noop) IncrementJobActive(string)                   {}
func (Noop) DecrementJobActive(string)                   {}
func (Noop) IncrementDedupHit(string)                    {}
func (Noop) IncrementPushSent(string, string)            {}
func (Noop) IncrementPushFailed(string, string)          {}
func (Noop) IncrementPushSkipped(string)                 {}
func (Noop) IncrementPushTokenInvalidated()              {}
