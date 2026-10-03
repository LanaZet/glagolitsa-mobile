// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

interface MediaFileStore {
    suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String
    suspend fun copyEncrypted(sourcePath: String, cacheId: String): String
    suspend fun readEncrypted(path: String): ByteArray?
    suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray?
    suspend fun delete(path: String)
    suspend fun exists(path: String): Boolean
}

expect class PlatformMediaFileStore() : MediaFileStore {
    override suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String
    override suspend fun copyEncrypted(sourcePath: String, cacheId: String): String
    override suspend fun readEncrypted(path: String): ByteArray?
    override suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray?
    override suspend fun delete(path: String)
    override suspend fun exists(path: String): Boolean
}
