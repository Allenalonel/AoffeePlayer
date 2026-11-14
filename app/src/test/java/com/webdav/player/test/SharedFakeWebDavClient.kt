package com.webdav.player.test

import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Single authoritative, highly configurable test double for [WebDavClient].
 *
 * Implements the full [WebDavClient] port interface with rich stubbing and fault-injection capabilities:
 * - Directory tree mocking & path normalization
 * - File data stubbing & HTTP Range slice simulation (with secondary fetch and partial chunk support)
 * - Text/lyrics fetching with fault and cancellation injection
 * - Connection testing & per-server probe result configuration
 * - Network latency simulation via [delayMs]
 * - Server cache invalidation tracking
 * - Streaming OkHttpClient factory & connection pool simulation
 * - Thread-safe concurrency metrics & call recording
 */
class SharedFakeWebDavClient : WebDavClient {
    // --- Connection Testing ---
    var defaultConnectionResult: ConnectionResult = ConnectionResult.Success
    var resultToReturn: ConnectionResult
        get() = defaultConnectionResult
        set(value) {
            defaultConnectionResult = value
        }
    val testConnectionResults: MutableMap<Long, ConnectionResult> = ConcurrentHashMap()
    val connectionProbeCount = AtomicInteger(0)
    var onTestConnection: (suspend (WebDavServer) -> ConnectionResult)? = null

    // --- Directory Listing ---
    val directoryTree: MutableMap<String, RemoteDirectory> = ConcurrentHashMap()
    val directoryResults: MutableMap<String, ListDirectoryResult> = ConcurrentHashMap()
    val results: MutableMap<String, ListDirectoryResult>
        get() = directoryResults
    val visitedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val directoryCallCount: MutableMap<String, Int> = ConcurrentHashMap()
    val callCount: MutableMap<String, Int>
        get() = directoryCallCount
    val failureMap: MutableMap<String, String> = ConcurrentHashMap()
    val activeCalls = AtomicInteger(0)
    val maxConcurrentCalls = AtomicInteger(0)
    var delayMs: Long = 0L
    var shouldThrowCancellation: Boolean = false
    var listDirectoryException: Throwable? = null
    var defaultDirectoryHandler: ((server: WebDavServer, path: String) -> ListDirectoryResult)? = null

    // --- Range / File Downloads ---
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val throwOnPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val ignoreRangeOnSecondaryFetchPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val partialChunkPaths: MutableMap<String, Int> = ConcurrentHashMap()
    val fetchCount = AtomicInteger(0)
    val requestedRanges: MutableList<Triple<String, Long, Long>> = Collections.synchronizedList(mutableListOf())
    var onFetchRange: (suspend (String) -> Unit)? = null
    var fetchRangeException: Throwable? = null

    // --- Text Fetching (e.g., Lyrics, CUE) ---
    val textFiles: MutableMap<String, String?> = ConcurrentHashMap()
    val textResponses: MutableMap<String, String?>
        get() = textFiles
    val fetchTextCount = AtomicInteger(0)
    val throwOnTextPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    var fetchTextException: Throwable? = null

    // --- Invalidation & Streaming Client ---
    val invalidatedServerIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val streamingConnectionPool: ConnectionPool = ConnectionPool()
    var defaultStreamingClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(streamingConnectionPool)
            .build()
    val streamingClients: MutableMap<Long, OkHttpClient> = ConcurrentHashMap()
    val streamingClientRequests = AtomicInteger(0)

    fun stubFileBytes(
        path: String,
        bytes: ByteArray
    ) {
        files[path] = bytes
    }

    fun stubText(
        path: String,
        text: String?
    ) {
        if (text != null) {
            textFiles[path] = text
        } else {
            textFiles.remove(path)
        }
    }

    fun stubDirectory(
        path: String,
        directory: RemoteDirectory
    ) {
        directoryTree[RemoteDirectory.normalizePath(path)] = directory
    }

    fun stubDirectoryResult(
        path: String,
        result: ListDirectoryResult
    ) {
        directoryResults[path] = result
    }

    override suspend fun testConnection(server: WebDavServer): ConnectionResult {
        connectionProbeCount.incrementAndGet()
        onTestConnection?.let { return it(server) }
        return testConnectionResults[server.id] ?: defaultConnectionResult
    }

    override suspend fun listDirectory(
        server: WebDavServer,
        path: String
    ): ListDirectoryResult {
        val normalizedPath = RemoteDirectory.normalizePath(path)
        visitedPaths.add(normalizedPath)
        directoryCallCount.compute(path) { _, count -> (count ?: 0) + 1 }
        if (normalizedPath != path) {
            directoryCallCount.compute(normalizedPath) { _, count -> (count ?: 0) + 1 }
        }
        val current = activeCalls.incrementAndGet()
        maxConcurrentCalls.updateAndGet { maxOf(it, current) }

        try {
            if (shouldThrowCancellation) {
                throw CancellationException("Simulated cancellation for $path")
            }
            listDirectoryException?.let { throw it }
            if (delayMs > 0L) {
                delay(delayMs)
            }
            val failure = failureMap[normalizedPath] ?: failureMap[path]
            if (failure != null) {
                return ListDirectoryResult.Failure(failure)
            }
            val explicitResult = directoryResults[path] ?: directoryResults[normalizedPath]
            if (explicitResult != null) {
                return explicitResult
            }
            val dir = directoryTree[normalizedPath] ?: directoryTree[path]
            if (dir != null) {
                return ListDirectoryResult.Success(dir)
            }
            if (directoryTree.isNotEmpty()) {
                return ListDirectoryResult.Failure("Directory not found: $normalizedPath", statusCode = 404)
            }
            return defaultDirectoryHandler?.invoke(server, path)
                ?: ListDirectoryResult.Success(
                    RemoteDirectory(
                        path = path,
                        name = path.trim('/').substringAfterLast('/')
                    )
                )
        } finally {
            activeCalls.decrementAndGet()
        }
    }

    override suspend fun fetchRange(
        server: WebDavServer,
        remotePath: String,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        fetchRangeException?.let { throw it }
        onFetchRange?.invoke(remotePath)
        fetchCount.incrementAndGet()
        requestedRanges.add(Triple(remotePath, startByte, endByte))
        if (throwOnPaths.contains(remotePath)) {
            throw IOException("Simulated network timeout for $remotePath")
        }
        val full = files[remotePath] ?: return null
        if (startByte > 0 && ignoreRangeOnSecondaryFetchPaths.contains(remotePath)) {
            return full
        }
        if (startByte >= full.size) return byteArrayOf()
        val from = startByte.toInt()
        val to = minOf(full.size, (endByte + 1).toInt())
        if (partialChunkPaths.containsKey(remotePath) && startByte > 0) {
            val partialSize = partialChunkPaths[remotePath]!!
            val chunkEnd = minOf(to, from + partialSize)
            return full.copyOfRange(from, chunkEnd)
        }
        return full.copyOfRange(from, to)
    }

    override suspend fun fetchText(
        server: WebDavServer,
        remotePath: String
    ): String? {
        fetchTextCount.incrementAndGet()
        fetchTextException?.let { throw it }
        if (throwOnTextPaths.contains(remotePath)) {
            throw IOException("Simulated network error for $remotePath")
        }
        return textFiles[remotePath]
    }

    override fun invalidateServer(serverId: Long) {
        invalidatedServerIds.add(serverId)
    }

    fun wasServerInvalidated(serverId: Long): Boolean = invalidatedServerIds.contains(serverId)

    override fun getStreamingClientForServer(server: WebDavServer): OkHttpClient {
        streamingClientRequests.incrementAndGet()
        return streamingClients[server.id] ?: defaultStreamingClient
    }

    fun clear() {
        testConnectionResults.clear()
        connectionProbeCount.set(0)
        onTestConnection = null
        defaultConnectionResult = ConnectionResult.Success

        directoryTree.clear()
        directoryResults.clear()
        visitedPaths.clear()
        directoryCallCount.clear()
        failureMap.clear()
        activeCalls.set(0)
        maxConcurrentCalls.set(0)
        delayMs = 0L
        shouldThrowCancellation = false
        listDirectoryException = null
        defaultDirectoryHandler = null

        files.clear()
        throwOnPaths.clear()
        ignoreRangeOnSecondaryFetchPaths.clear()
        partialChunkPaths.clear()
        fetchCount.set(0)
        requestedRanges.clear()
        onFetchRange = null
        fetchRangeException = null

        textFiles.clear()
        fetchTextCount.set(0)
        throwOnTextPaths.clear()
        fetchTextException = null

        invalidatedServerIds.clear()
        streamingClients.clear()
        streamingClientRequests.set(0)
    }
}
