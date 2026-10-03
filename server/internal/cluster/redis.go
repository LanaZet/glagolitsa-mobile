// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package cluster

import (
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"sync"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"glagolitsa/server/internal/model"
)

const redisChannel = "glagolitsa:cluster:ws"

type wsMessage struct {
	OriginNodeID string        `json:"origin_node_id"`
	MemberIDs    []string      `json:"member_ids"`
	Event        model.WSEvent `json:"event"`
}

// RedisCluster publishes WS events to all nodes (Mattermost platform/cluster.go).
type RedisCluster struct {
	nodeID string
	client *redis.Client
	local  LocalBroadcaster
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

// LocalBroadcaster delivers events only to connections on this node.
type LocalBroadcaster interface {
	BroadcastLocal(memberIDs []string, event model.WSEvent)
}

func NewRedisCluster(redisURL string, local LocalBroadcaster) (*RedisCluster, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, err
	}
	client := redis.NewClient(opts)
	ctx, cancel := context.WithCancel(context.Background())
	nodeID := os.Getenv("NODE_ID")
	if nodeID == "" {
		nodeID = uuid.NewString()
	}
	c := &RedisCluster{
		nodeID: nodeID,
		client: client,
		local:  local,
		cancel: cancel,
	}
	c.wg.Add(1)
	go c.listen(ctx)
	slog.Info("cluster_enabled", "backend", "redis", "node_id", nodeID)
	return c, nil
}

func (c *RedisCluster) Publish(memberIDs []string, event model.WSEvent) {
	if c == nil {
		return
	}
	payload, err := json.Marshal(wsMessage{
		OriginNodeID: c.nodeID,
		MemberIDs:    memberIDs,
		Event:        event,
	})
	if err != nil {
		return
	}
	_ = c.client.Publish(context.Background(), redisChannel, payload).Err()
}

func (c *RedisCluster) listen(ctx context.Context) {
	defer c.wg.Done()
	sub := c.client.Subscribe(ctx, redisChannel)
	ch := sub.Channel()
	for {
		select {
		case <-ctx.Done():
			_ = sub.Close()
			return
		case msg, ok := <-ch:
			if !ok {
				return
			}
			var payload wsMessage
			if err := json.Unmarshal([]byte(msg.Payload), &payload); err != nil {
				continue
			}
			if payload.OriginNodeID == c.nodeID {
				continue
			}
			if c.local != nil {
				c.local.BroadcastLocal(payload.MemberIDs, payload.Event)
			}
		}
	}
}

func (c *RedisCluster) Close() error {
	if c == nil {
		return nil
	}
	c.cancel()
	c.wg.Wait()
	return c.client.Close()
}
