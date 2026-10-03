// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

// RouteClass values for region-aware quality policy (no GPS / city labels).
const (
	RouteClassLocal          = "local"
	RouteClassIntercity      = "intercity"
	RouteClassInternational  = "international"
	RouteClassForcedRegion   = "forced_region"
	RouteClassDegraded       = "degraded"
	RouteClassSingleRegion   = "single_region"
)

// CallRouteMetadata is attached to token responses / diagnostics.
// Must not include IP, GPS, or profile city.
type CallRouteMetadata struct {
	SelectedRegion string `json:"selected_region"`
	RouteClass     string `json:"route_class"`
	SelectionReason string `json:"selection_reason,omitempty"`
	TurnRegion     string `json:"turn_region,omitempty"`
}

// SelectRoute v1: single primary region. Ready for multi-region scoring later.
func SelectRoute(cfg Config, hints map[string]any) CallRouteMetadata {
	_ = hints // privacy-safe RTT buckets only when route_hints feature is on
	region := cfg.RegionID
	if region == "" {
		region = "primary"
	}
	return CallRouteMetadata{
		SelectedRegion:  region,
		RouteClass:      RouteClassSingleRegion,
		SelectionReason: "single_region",
		TurnRegion:      region,
	}
}
