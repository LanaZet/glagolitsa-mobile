// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package ws

import (
	"encoding/json"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/cluster"
	"glagolitsa/server/internal/metrics"
	"glagolitsa/server/internal/model"
)

type client struct {
	userID   string
	deviceID string
	conn     *websocket.Conn
	send     chan []byte
}

// ConnectionTracker — online/offline через Presence Service.
type ConnectionTracker interface {
	OnSocketConnected(userID string)
	OnSocketDisconnected(userID string)
}

// AccessGate — дополнительные проверки после JWT (сессия, статус аккаунта).
type AccessGate interface {
	AllowSocket(claims auth.Claims) error
}

type Hub struct {
	mu       sync.RWMutex
	clients  map[string]map[*client]struct{}
	presence ConnectionTracker
	access   AccessGate
	origin   OriginPolicy
	upgrader websocket.Upgrader
	cluster  cluster.Interface
	metrics  metrics.Interface
}

func NewHub(presence ConnectionTracker) *Hub {
	h := &Hub{
		clients:  make(map[string]map[*client]struct{}),
		presence: presence,
		metrics:  metrics.Noop{},
		origin:   OriginPolicy{AllowAll: true},
	}
	h.upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return h.origin.allows(r) },
	}
	return h
}

func (h *Hub) SetOriginPolicy(policy OriginPolicy) {
	h.origin = policy
}

func (h *Hub) SetMetrics(m metrics.Interface) {
	if m != nil {
		h.metrics = m
	}
}

func (h *Hub) SetCluster(c cluster.Interface) {
	h.cluster = c
}

func (h *Hub) SetConnectionTracker(presence ConnectionTracker) {
	h.presence = presence
}

func (h *Hub) SetAccessGate(gate AccessGate) {
	h.access = gate
}

func (h *Hub) Handle(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}

	claims, err := auth.ParseToken(token)
	if err != nil {
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}
	if h.access != nil {
		if err := h.access.AllowSocket(claims); err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	c := &client{
		userID:   claims.UserID,
		deviceID: claims.DeviceID,
		conn:     conn,
		send:     make(chan []byte, 16),
	}

	h.register(c)

	go c.writePump()
	c.readPump(h)
}

func (h *Hub) register(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	wasEmpty := len(h.clients[c.userID]) == 0
	if h.clients[c.userID] == nil {
		h.clients[c.userID] = make(map[*client]struct{})
	}
	h.clients[c.userID][c] = struct{}{}
	if wasEmpty && h.presence != nil {
		h.presence.OnSocketConnected(c.userID)
	}
	h.updateConnectionGaugeLocked()
}

func (h *Hub) unregister(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	removed := false
	if set, ok := h.clients[c.userID]; ok {
		if _, exists := set[c]; exists {
			delete(set, c)
			removed = true
		}
		if len(set) == 0 {
			delete(h.clients, c.userID)
			if h.presence != nil {
				h.presence.OnSocketDisconnected(c.userID)
			}
		}
	}
	if removed {
		close(c.send)
	}
	if c.conn != nil {
		_ = c.conn.Close()
	}
	h.updateConnectionGaugeLocked()
}

func (h *Hub) updateConnectionGaugeLocked() {
	total := 0
	for _, set := range h.clients {
		total += len(set)
	}
	h.metrics.SetWSConnections(float64(total))
}

func (h *Hub) BroadcastMessage(memberIDs []string, message model.Message) {
	h.BroadcastToUsers(memberIDs, model.WSEvent{
		Event: "message.new",
		Data:  model.MessageNewData{Message: message},
	})
}

func (h *Hub) BroadcastChatUpdated(memberIDs []string, chat model.Chat) {
	h.BroadcastToUsers(memberIDs, model.WSEvent{
		Event: "chat.updated",
		Data:  model.ChatUpdatedData{Chat: chat},
	})
}

func (h *Hub) BroadcastEnvelope(memberIDs []string, envelope model.EnvelopeNewData) {
	h.BroadcastToUsers(memberIDs, model.WSEvent{
		Event: "message.envelope",
		Data:  envelope,
	})
}

func (h *Hub) BroadcastTyping(memberIDs []string, chatID, userID string) {
	h.BroadcastToUsers(memberIDs, model.WSEvent{
		Event: "typing",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
		},
	})
}

// BroadcastToUsers — fanout для Presence Service и messaging.
// Mattermost-style Publish: local delivery first, then cluster fan-out when configured.
func (h *Hub) BroadcastToUsers(memberIDs []string, event model.WSEvent) {
	h.BroadcastLocal(memberIDs, event)
	if h.cluster != nil {
		h.cluster.Publish(memberIDs, event)
	}
}

// BroadcastLocal — Mattermost PublishSkipClusterSend (this node only).
func (h *Hub) BroadcastLocal(memberIDs []string, event model.WSEvent) {
	h.broadcast(memberIDs, event)
}

func (h *Hub) HasConnectedClients(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[userID]) > 0
}

// ConnectedDeviceIDs — devices with an active WS for this account (P2 push skip).
// Empty device_id claims are reported as "" once if any such connection exists.
func (h *Hub) ConnectedDeviceIDs(userID string) []string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	set := h.clients[userID]
	if len(set) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(set))
	out := make([]string, 0, len(set))
	for c := range set {
		id := c.deviceID
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	return out
}

// IsDeviceConnected reports whether this specific device has a live socket.
func (h *Hub) IsDeviceConnected(userID, deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for c := range h.clients[userID] {
		if c.deviceID == deviceID {
			return true
		}
	}
	return false
}

func (h *Hub) broadcast(memberIDs []string, event model.WSEvent) {
	payload, err := json.Marshal(event)
	if err != nil {
		return
	}
	h.metrics.IncrementWSEvent(event.Event)

	h.mu.RLock()
	defer h.mu.RUnlock()

	for _, memberID := range memberIDs {
		for client := range h.clients[memberID] {
			select {
			case client.send <- payload:
			default:
				h.metrics.IncrementWSDropped()
			}
		}
	}
}

func (c *client) readPump(h *Hub) {
	defer h.unregister(c)
	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return
		}
	}
}

func (c *client) writePump() {
	for payload := range c.send {
		if err := c.conn.WriteMessage(websocket.TextMessage, payload); err != nil {
			return
		}
	}
}
