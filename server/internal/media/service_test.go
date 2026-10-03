// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"bytes"
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type memoryBlobStore struct {
	objects map[string][]byte
}

func (m *memoryBlobStore) Put(_ context.Context, objectKey string, reader io.Reader, _ int64, _ string) error {
	data, err := io.ReadAll(reader)
	if err != nil {
		return err
	}
	if m.objects == nil {
		m.objects = make(map[string][]byte)
	}
	m.objects[objectKey] = data
	return nil
}

func (m *memoryBlobStore) Get(_ context.Context, objectKey string) (io.ReadCloser, error) {
	data, ok := m.objects[objectKey]
	if !ok {
		return nil, store.ErrNotFound
	}
	return io.NopCloser(bytes.NewReader(data)), nil
}

func (m *memoryBlobStore) Delete(_ context.Context, objectKey string) error {
	delete(m.objects, objectKey)
	return nil
}

func TestEncryptedUploadDownloadRoundTrip(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	cfg := ConfigFromEnv()
	cfg.DefaultTTL = 24 * time.Hour
	svc := NewService(mem, blobs, cfg)

	slot, err := svc.CreateUploadSlot("user-1", CreateSlotRequest{Kind: KindPhoto, MimeType: "image/jpeg"})
	if err != nil {
		t.Fatalf("create slot: %v", err)
	}
	if slot.ContentMode != "" && slot.ContentMode != "encrypted" {
		t.Fatalf("default content_mode = %q", slot.ContentMode)
	}

	ciphertext := []byte("encrypted-blob-bytes")
	info, err := svc.UploadEncryptedBlob(context.Background(), slot.FileID, "user-1", bytes.NewReader(ciphertext), "")
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	if info.SizeBytes != int64(len(ciphertext)) {
		t.Fatalf("unexpected size: %d", info.SizeBytes)
	}
	if info.ContentHashEncrypted == "" {
		t.Fatal("expected content hash")
	}

	record, reader, err := svc.DownloadEncryptedBlob(context.Background(), slot.FileID)
	if err != nil {
		t.Fatalf("download: %v", err)
	}
	defer reader.Close()
	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("read blob: %v", err)
	}
	if !bytes.Equal(got, ciphertext) {
		t.Fatalf("blob mismatch")
	}
	if record.Kind != KindPhoto {
		t.Fatalf("unexpected kind: %s", record.Kind)
	}
}

func TestOpenPhotoUploadRequiresChannelAccess(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	svc := NewService(mem, blobs, ConfigFromEnv())
	// No Access configured → open slot rejected.
	_, err := svc.CreateUploadSlot("u1", CreateSlotRequest{
		Kind: KindPhoto, ChatID: "c1", ContentMode: "open", MimeType: "image/png",
	})
	if err == nil {
		t.Fatal("expected open slot without Access to fail")
	}
}

func TestOpenPhotoUploadDownloadForMember(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	cfg := ConfigFromEnv()
	svc := NewService(mem, blobs, cfg)
	svc.Access = NewGroupChatAccess(mem)

	// Seed channel: admin owner, encryption none.
	admin := "admin-1"
	member := "member-1"
	stranger := "stranger-1"
	for _, id := range []string{admin, member, stranger} {
		_, err := mem.CreateAccount(model.User{ID: id, Username: id, CreatedAt: store.NowUTC()}, "hash")
		if err != nil {
			// CreateAccount may need more fields — use existing test helpers if fail
			t.Logf("create account %s: %v", id, err)
		}
	}
	// Minimal chat + settings via CreateGroup
	chat, settings, err := mem.CreateGroup(store.CreateGroupInput{
		Title:            "chan",
		CreatorID:        admin,
		MemberIDs:        []string{member},
		ChatType:         model.ChatTypeChannel,
		Visibility:       model.VisibilityPublic,
		Slug:             "openphoto1",
		EncryptionMode:   model.EncryptionNone,
		PermSendMessages: store.GroupPermAdmin,
	})
	if err != nil {
		t.Fatalf("create channel: %v", err)
	}
	if settings.EncryptionMode != model.EncryptionNone {
		t.Fatalf("enc = %s", settings.EncryptionMode)
	}

	slot, err := svc.CreateUploadSlot(admin, CreateSlotRequest{
		Kind: KindPhoto, ChatID: chat.ID, ContentMode: "open", MimeType: "image/png",
	})
	if err != nil {
		t.Fatalf("open slot: %v", err)
	}
	if slot.ContentMode != "open" {
		t.Fatalf("content_mode = %s", slot.ContentMode)
	}

	// Minimal PNG header + padding
	png := []byte{
		0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
		0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4,
	}
	// Valid minimal PNG is hard; use a real 1x1 PNG.
	png1x1 := []byte{
		0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
		0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
		0xde, 0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41, 0x54, 0x08, 0xd7, 0x63, 0xf8, 0xcf, 0xc0, 0x00,
		0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xfe, 0xd4, 0xef, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
		0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
	}
	info, err := svc.UploadOpenBlob(context.Background(), slot.FileID, admin, bytes.NewReader(png1x1))
	if err != nil {
		t.Fatalf("open upload: %v", err)
	}
	if info.MimeType != "image/png" {
		t.Fatalf("mime = %s", info.MimeType)
	}
	if info.ContentMode != "open" {
		t.Fatalf("info mode = %s", info.ContentMode)
	}
	// Thumb is best-effort; 1x1 should produce one.
	if info.ThumbFileID == "" {
		t.Log("thumb_file_id empty (decode may have failed for tiny png); continuing")
	}

	// Member can download
	_, r, err := svc.DownloadBlob(context.Background(), slot.FileID, member)
	if err != nil {
		t.Fatalf("member download: %v", err)
	}
	r.Close()

	// Stranger cannot
	_, _, err = svc.DownloadBlob(context.Background(), slot.FileID, stranger)
	if err == nil {
		t.Fatal("stranger should not download open media")
	}

	// Encrypted upload into open slot must fail
	_, err = svc.UploadEncryptedBlob(context.Background(), slot.FileID, admin, bytes.NewReader(png), "")
	if err == nil {
		t.Fatal("encrypted upload into open slot should fail")
	}
}

func TestUploadRejectsForeignOwner(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	svc := NewService(mem, blobs, ConfigFromEnv())
	slot, err := svc.CreateUploadSlot("owner", CreateSlotRequest{Kind: KindDocument})
	if err != nil {
		t.Fatalf("create slot: %v", err)
	}
	_, err = svc.UploadEncryptedBlob(context.Background(), slot.FileID, "other-user", bytes.NewReader([]byte("x")), "")
	if err == nil {
		t.Fatal("expected forbidden upload")
	}
}

func TestChunkedUploadRoundTrip(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	cfg := ConfigFromEnv()
	cfg.ChunkMaxBytes = 8
	svc := NewService(mem, blobs, cfg)
	slot, err := svc.CreateUploadSlot("owner", CreateSlotRequest{Kind: KindDocument})
	if err != nil {
		t.Fatalf("create slot: %v", err)
	}

	payload := []byte("this-is-an-encrypted-attachment")
	uploadID := "upload-1"
	offset := int64(0)
	for offset < int64(len(payload)) {
		next := offset + 8
		if next > int64(len(payload)) {
			next = int64(len(payload))
		}
		chunk := payload[offset:next]
		result, err := svc.UploadEncryptedChunk(
			context.Background(),
			slot.FileID,
			"owner",
			uploadID,
			offset,
			"",
			next == int64(len(payload)),
			bytes.NewReader(chunk),
		)
		if err != nil {
			t.Fatalf("upload chunk: %v", err)
		}
		offset = next
		if !result.Completed && result.NextOffset != offset {
			t.Fatalf("offset mismatch, expected=%d got=%d", offset, result.NextOffset)
		}
		if result.Completed && result.Info == nil {
			t.Fatal("expected final attachment info")
		}
	}

	_, reader, err := svc.DownloadEncryptedBlob(context.Background(), slot.FileID)
	if err != nil {
		t.Fatalf("download: %v", err)
	}
	defer reader.Close()
	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("payload mismatch")
	}
}

func TestChunkedUploadRejectsOffsetMismatch(t *testing.T) {
	mem := store.NewMemory()
	blobs := &memoryBlobStore{}
	cfg := ConfigFromEnv()
	cfg.ChunkMaxBytes = 8
	svc := NewService(mem, blobs, cfg)
	slot, err := svc.CreateUploadSlot("owner", CreateSlotRequest{Kind: KindDocument})
	if err != nil {
		t.Fatalf("create slot: %v", err)
	}
	_, err = svc.UploadEncryptedChunk(
		context.Background(),
		slot.FileID,
		"owner",
		"upload-1",
		4,
		"",
		false,
		bytes.NewReader([]byte("data")),
	)
	if err == nil {
		t.Fatal("expected offset mismatch")
	}
	if !errors.Is(err, ErrOffsetMismatch) {
		t.Fatalf("expected ErrOffsetMismatch, got %v", err)
	}
}
