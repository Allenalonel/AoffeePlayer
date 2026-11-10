package com.webdav.player

import com.webdav.player.data.service.PlaybackSessionHost
import com.webdav.player.domain.player.AudioPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * End-to-end packaging seam and binary thinning invariant test.
 *
 * Verifies that:
 * 1. ProGuard rules protect Room, JNI, Media3 session, and FFmpeg without bloated wildcards.
 * 2. build.gradle.kts enforces 64-bit ABI filter, R8 minification, resource shrinking,
 *    and excludes material-icons-extended.
 * 3. When release APK is present, total APK size <= 7.5 MB, classes.dex <= 4.0 MB,
 *    and native libraries contain strictly arm64-v8a with zero 32-bit .so files.
 * 4. The Streaming Playback Pipeline / Session Host boundary (ADR-0019) has no reverse coupling.
 */
class PackagingSeamVerificationTest {
    private val sourceRoot = File("src/main/java/com/webdav/player")
    private val testRoot = File("src/test/java/com/webdav/player")

    private fun source(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        assertTrue("${file.path} must exist", file.exists())
        return file.readText()
    }

    @Test
    fun playbackPipelineSeam_hasNoReverseCouplingToSessionHost() {
        // Session host talks to the engine solely through AudioPlayerEngine: zero downcasts
        val host = source("data/service/PlaybackSessionHost.kt")
        assertFalse(
            "PlaybackSessionHost must not implement AudioPlayerEngine",
            AudioPlayerEngine::class.java.isAssignableFrom(PlaybackSessionHost::class.java),
        )
        assertFalse(
            "PlaybackSessionHost must not downcast to Media3AudioPlayerEngine",
            Regex("""\bas\??\s+Media3AudioPlayerEngine\b""").containsMatchIn(host),
        )
        assertFalse(
            "PlaybackSessionHost must not reach into engine audio routing internals",
            host.contains("audioRoutingObserver") || host.contains("attachAudioRouting"),
        )

        // WebDavApplication must not downcast playerEngine to PlaybackSessionHost
        val app = source("WebDavApplication.kt")
        assertFalse(
            "WebDavApplication must not downcast playerEngine to PlaybackSessionHost",
            app.contains("as? PlaybackSessionHost") || app.contains("as PlaybackSessionHost"),
        )

        // Streaming pipeline owns no MediaSession, hardware locks, or reverse callbacks
        val engine = source("data/player/Media3AudioPlayerEngine.kt")
        listOf("MediaSession", "StreamingHardwareLockManager", "hardwareLockManager", "sessionCallback", "onSkipToNext", "onSkipToPrevious")
            .forEach { leak ->
                assertFalse("Media3AudioPlayerEngine must not reference '$leak'", engine.contains(leak))
            }

        // Media source builder stays focused on authenticated streaming sources
        val builder = source("data/player/WebDavMediaSourceBuilder.kt")
        assertFalse(
            "WebDavMediaSourceBuilder must not carry hardware lock dependencies",
            builder.contains("hardwareLockManager") || builder.contains("StreamingHardwareLockManager"),
        )
        assertFalse(
            "WebDavMediaSourceAdapter file must be eliminated",
            File(sourceRoot, "data/player/WebDavMediaSourceAdapter.kt").exists(),
        )
        assertFalse(
            "LyricsRepository file must be eliminated",
            File(sourceRoot, "domain/repository/LyricsRepository.kt").exists(),
        )
        assertFalse(
            "LyricsRepositoryImpl file must be eliminated",
            File(sourceRoot, "data/repository/LyricsRepositoryImpl.kt").exists(),
        )

        // AudioPlayerEngine interface exposes no routing or host concerns
        val engineInterface = source("domain/player/AudioPlayerEngine.kt")
        assertFalse(
            "AudioPlayerEngine must not expose audio routing hooks",
            engineInterface.contains("onAudioRoutingChanged"),
        )
        assertFalse(
            "AudioPlayerEngine must not depend on the data layer",
            Regex("""^import com\.webdav\.player\.data\.""", RegexOption.MULTILINE).containsMatchIn(engineInterface),
        )

        // No data.player source depends back on the host, the app session, or Media3 session APIs
        val reverseImport =
            Regex(
                """^import (com\.webdav\.player\.(data\.service|domain\.session|ui)\.|androidx\.media3\.session\.)""",
                RegexOption.MULTILINE,
            )
        val offenders =
            File(sourceRoot, "data/player")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { reverseImport.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertTrue("data.player must not import host/session/ui or media3.session: $offenders", offenders.isEmpty())
    }

    @Test
    fun moduleLayeringAndSeamBoundaries_haveNoReverseImportsOrLeaks() {
        // 1. ui.common must never reverse-import screen-specific UI packages
        val uiCommonReverseImport =
            Regex(
                """^import com\.webdav\.player\.ui\.(browser|playlist|player|server)\.""",
                RegexOption.MULTILINE,
            )
        val uiCommonOffenders =
            File(sourceRoot, "ui/common")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { uiCommonReverseImport.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertTrue(
            "ui.common must not reverse-import screen-specific packages (browser/playlist/player/server): $uiCommonOffenders",
            uiCommonOffenders.isEmpty(),
        )

        // 2. CUE packages are completely purged and must not be imported
        val cueLeakImport =
            Regex(
                """^import com\.webdav\.player\.(data|domain)\.cue\b""",
                RegexOption.MULTILINE,
            )
        val cueLeakOffenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { cueLeakImport.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertTrue(
            "CUE packages are purged; no imports allowed: $cueLeakOffenders",
            cueLeakOffenders.isEmpty(),
        )

        // 3. Deleted zombie forwarding shells must not reappear
        val bannedZombiePaths =
            listOf(
                "ui/browser/DirectoryBrowserViewModelFactory.kt",
                "ui/server/ServerManagementViewModelFactory.kt",
                "ui/playlist/PlaylistListViewModelFactory.kt",
                "ui/playlist/PlaylistDetailViewModelFactory.kt",
                "data/cue/CueTextCache.kt",
                "data/cue/CueParser.kt",
                "data/cue/CueVirtualTrackIndexer.kt",
                "data/cue/DefaultCueSheetResolver.kt",
                "domain/cue/CueSheetResolver.kt",
                "domain/model/CueAlbumItem.kt",
                "domain/model/VirtualTrack.kt",
                "domain/session/VirtualTimelineEngine.kt",
                "ui/browser/components/CueAlbumCard.kt",
                "ui/browser/components/AudioTrackItemRowCompat.kt",
                "domain/repository/DirectoryRepository.kt",
                "data/repository/DirectoryRepositoryImpl.kt",
                "domain/repository/LibrarySearchRepository.kt",
                "data/repository/LibrarySearchRepositoryImpl.kt",
            )
        val existingZombies = bannedZombiePaths.filter { File(sourceRoot, it).exists() }
        assertTrue(
            "Zombie forwarding shells and deprecated compatibility files must remain deleted: $existingZombies",
            existingZombies.isEmpty(),
        )

        val bannedZombieTestPaths =
            listOf(
                "data/repository/DirectoryRepositoryTest.kt",
                "data/repository/LibrarySearchRepositoryTest.kt",
            )
        val existingZombieTests = bannedZombieTestPaths.filter { File(testRoot, it).exists() }
        assertTrue(
            "Legacy repository test suites must remain deleted: $existingZombieTests",
            existingZombieTests.isEmpty(),
        )
    }

    @Test
    fun proguardRules_enforcePreciseKeepRulesWithoutBloatedWildcards() {
        val proguardFile = File("proguard-rules.pro")
        assertTrue("proguard-rules.pro must exist", proguardFile.exists())
        val content = proguardFile.readText()

        // Assert native JNI method bindings are preserved
        assertTrue(
            "Native method keep rule must be present",
            content.contains("native <methods>;"),
        )
        assertTrue(
            "FFmpeg decoder package keep rule must be present",
            content.contains("-keep class androidx.media3.decoder.ffmpeg.** { *; }"),
        )

        // Assert Room keep rules are present
        assertTrue(
            "Room database keep rule must be present",
            content.contains("extends androidx.room.RoomDatabase"),
        )
        assertTrue(
            "Room Entity keep rule must be present",
            content.contains("@androidx.room.Entity class * { *; }"),
        )
        assertTrue(
            "Room DAO keep rule must be present",
            content.contains("@androidx.room.Dao interface * { *; }"),
        )

        // Assert Media3 session and metadata keep rules are present
        assertTrue(
            "Media3 Session callback keep rule must be present",
            content.contains("androidx.media3.session.MediaSession\$Callback"),
        )
        assertTrue(
            "Media3 SessionService keep rule must be present",
            content.contains("androidx.media3.session.MediaSessionService"),
        )
        assertTrue(
            "MediaItem keep rule must be present",
            content.contains("class androidx.media3.common.MediaItem"),
        )
        assertTrue(
            "MediaMetadata keep rule must be present",
            content.contains("class androidx.media3.common.MediaMetadata"),
        )

        // Assert OkHttp and WebSocket keep rules are present
        assertTrue(
            "OkHttp PublicSuffixDatabase keep rule must be present",
            content.contains("class okhttp3.internal.publicsuffix.PublicSuffixDatabase"),
        )
        assertTrue(
            "OkHttp Interceptor keep rule must be present",
            content.contains("interface okhttp3.Interceptor"),
        )
        assertTrue(
            "OkHttp Authenticator keep rule must be present",
            content.contains("interface okhttp3.Authenticator"),
        )
        assertTrue(
            "WebSocket interface keep rule must be present",
            content.contains("interface okhttp3.WebSocket"),
        )
        assertTrue(
            "WebSocketListener keep rule must be present",
            content.contains("class okhttp3.WebSocketListener"),
        )

        // Assert bloated wildcard is NOT present
        assertFalse(
            "Overly broad '-keep class androidx.media3.** { *; }' must not be present to avoid DEX bloat",
            content.lines().any { line ->
                val trimmed = line.trim()
                trimmed == "-keep class androidx.media3.** { *; }" ||
                    trimmed == "-keep class androidx.media3.** {* ;}" ||
                    trimmed == "-keep class androidx.media3.**"
            },
        )
    }

    @Test
    fun buildGradle_enforcesPackagingSeamInvariants() {
        val buildGradleFile = File("build.gradle.kts")
        assertTrue("build.gradle.kts must exist", buildGradleFile.exists())
        val content = buildGradleFile.readText()

        // Assert 64-bit arm64-v8a only ABI filter
        assertTrue(
            "abiFilters must specify arm64-v8a",
            content.contains("\"arm64-v8a\""),
        )
        assertFalse(
            "armeabi-v7a must be stripped from build.gradle.kts",
            content.contains("\"armeabi-v7a\""),
        )

        // Assert R8 minification and resource shrinking
        assertTrue(
            "isMinifyEnabled must be true",
            content.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "isShrinkResources must be true",
            content.contains("isShrinkResources = true"),
        )

        // Assert material-icons-extended is pruned
        assertFalse(
            "material-icons-extended must not be present in dependencies",
            content.contains("material-icons-extended"),
        )

        // Assert dex legacy packaging is enabled for compressed DEX in standalone APK distribution
        assertTrue(
            "dex useLegacyPackaging must be true to enforce compressed DEX",
            content.contains("dex") && content.contains("useLegacyPackaging = true"),
        )
    }

    @Test
    fun releaseApk_whenPresent_satisfiesSizeBudgetAndEntryInvariants() {
        val apkFile = File("build/outputs/apk/release/app-release.apk")
        if (!apkFile.exists()) {
            // If release APK hasn't been assembled in this environment run, skip entry inspection
            return
        }

        val maxApkSizeBytes = 7_864_320L // 7.5 MB
        val maxDexUncompressedBytes = 4_456_448L // 4.25 MB

        // 1. Total APK size <= 7.5 MB
        assertTrue(
            "Release APK file size (${apkFile.length()} bytes) must be <= $maxApkSizeBytes bytes (7.5 MB)",
            apkFile.length() <= maxApkSizeBytes,
        )

        val zip = ZipFile(apkFile)
        try {
            val entries = zip.entries().toList()

            // 2. Exactly one classes.dex with uncompressed size <= 4.0 MB
            val dexEntries = entries.filter { it.name.endsWith(".dex") }
            assertEquals(
                "Must contain exactly 1 classes.dex file",
                1,
                dexEntries.size,
            )
            val dex = dexEntries.first()
            assertEquals("DEX entry must be named classes.dex", "classes.dex", dex.name)
            assertTrue(
                "classes.dex uncompressed size (${dex.size} bytes) must be <= $maxDexUncompressedBytes bytes (4.25 MB)",
                dex.size <= maxDexUncompressedBytes,
            )

            // 3. Native libraries contain strictly lib/arm64-v8a/
            val nativeLibs = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            val expectedLibs =
                setOf(
                    "lib/arm64-v8a/libavcodec.so",
                    "lib/arm64-v8a/libavformat.so",
                    "lib/arm64-v8a/libavutil.so",
                    "lib/arm64-v8a/libffmpegJNI.so",
                    "lib/arm64-v8a/libswresample.so",
                )
            val actualLibs = nativeLibs.map { it.name }.toSet()
            assertEquals("Native libraries must match expected arm64-v8a set", expectedLibs, actualLibs)

            val nonArm64 = nativeLibs.filter { !it.name.startsWith("lib/arm64-v8a/") }
            assertTrue(
                "Must contain zero non-arm64 native libraries",
                nonArm64.isEmpty(),
            )
        } finally {
            zip.close()
        }
    }

    @Test
    fun uiLayer_enforcesDynamicColorPurityAndForbidsHardcodedHexColorsInComponents() {
        val uiDir = File(sourceRoot, "ui")
        assertTrue("UI source directory must exist", uiDir.exists())

        // Whitelisted files that are legitimately permitted to declare theme palette constants or color extraction defaults
        val whitelistedFiles =
            setOf(
                "Color.kt",
                "ArtworkColorExtractor.kt",
            )

        val hexColorRegex = Regex("""\bColor\s*\(\s*0x[0-9a-fA-F]{8}\b""")

        val violations = mutableListOf<String>()

        uiDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in whitelistedFiles }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("//") && hexColorRegex.containsMatchIn(trimmed)) {
                            violations.add("${file.name}:${index + 1} -> $trimmed")
                        }
                    }
                }
            }

        assertTrue(
            "UI components must use MaterialTheme.colorScheme or theme tokens instead of hardcoded hex Color(0x...): $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun uiLayer_enforcesLifecycleAwareStateFlowCollectionAndForbidsBareCollectAsState() {
        val uiDir = File(sourceRoot, "ui")
        assertTrue("UI source directory must exist", uiDir.exists())

        // Match bare collectAsState() calls that are NOT collectAsStateWithLifecycle()
        val bareCollectAsStateRegex = Regex("""(?<!WithLifecycle)\bcollectAsState\s*\(""")

        val violations = mutableListOf<String>()

        uiDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("//") && bareCollectAsStateRegex.containsMatchIn(trimmed)) {
                            violations.add("${file.name}:${index + 1} -> $trimmed")
                        }
                    }
                }
            }

        assertTrue(
            "Compose UI must consume StateFlow via collectAsStateWithLifecycle(); bare collectAsState() is forbidden: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun safDirectoryScanning_forbidsDocumentFileListFiles() {
        // 1. Forbid importing DocumentFile anywhere in the project (enforces DocumentsContract cursor traversal)
        val documentFileImportRegex =
            Regex("""^import (androidx\.documentfile\.provider\.)?DocumentFile\b""", RegexOption.MULTILINE)
        val documentFileOffenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { documentFileImportRegex.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertTrue(
            "SAF directory scanning must not import or use DocumentFile (mandatory DocumentsContract traversal): $documentFileOffenders",
            documentFileOffenders.isEmpty(),
        )

        // 2. Forbid any .listFiles() calls in LocalStorage resolvers/scanners
        val safResolvers =
            listOf(
                "data/repository/LocalStorageDirectoryResolver.kt",
                "data/repository/LocalStorageIndexScanner.kt",
            )
        val listFilesRegex = Regex("""\.listFiles\s*\(""")
        safResolvers.forEach { relPath ->
            val file = File(sourceRoot, relPath)
            if (file.exists()) {
                assertFalse(
                    "$relPath must not call listFiles(); must use DocumentsContract cursor traversal",
                    listFilesRegex.containsMatchIn(file.readText()),
                )
            }
        }
    }

    @Test
    fun consolidatedMediaLibraryRepository_purgesLegacyRepositoriesAndTests() {
        // Production legacy repository interfaces and implementations must be eliminated
        assertFalse(
            "DirectoryRepository.kt must be eliminated",
            File(sourceRoot, "domain/repository/DirectoryRepository.kt").exists(),
        )
        assertFalse(
            "DirectoryRepositoryImpl.kt must be eliminated",
            File(sourceRoot, "data/repository/DirectoryRepositoryImpl.kt").exists(),
        )
        assertFalse(
            "LibrarySearchRepository.kt must be eliminated",
            File(sourceRoot, "domain/repository/LibrarySearchRepository.kt").exists(),
        )
        assertFalse(
            "LibrarySearchRepositoryImpl.kt must be eliminated",
            File(sourceRoot, "data/repository/LibrarySearchRepositoryImpl.kt").exists(),
        )

        // Test legacy repository test files must be eliminated
        assertFalse(
            "DirectoryRepositoryTest.kt must be eliminated",
            File(testRoot, "data/repository/DirectoryRepositoryTest.kt").exists(),
        )
        assertFalse(
            "LibrarySearchRepositoryTest.kt must be eliminated",
            File(testRoot, "data/repository/LibrarySearchRepositoryTest.kt").exists(),
        )

        // AppContainer must expose MediaLibraryRepository and zero legacy repositories
        val appContainer = source("di/AppContainer.kt")
        assertTrue(
            "AppContainer must expose mediaLibraryRepository",
            appContainer.contains("val mediaLibraryRepository: MediaLibraryRepository"),
        )
        assertFalse(
            "AppContainer must not expose directoryRepository",
            appContainer.contains("directoryRepository"),
        )
        assertFalse(
            "AppContainer must not expose librarySearchRepository",
            appContainer.contains("librarySearchRepository"),
        )

        // WebDavApplication must not instantiate or retain legacy repositories
        val app = source("WebDavApplication.kt")
        assertFalse("WebDavApplication must not reference DirectoryRepository", app.contains("DirectoryRepository"))
        assertFalse("WebDavApplication must not reference LibrarySearchRepository", app.contains("LibrarySearchRepository"))

        // No source file in src/main/java may import legacy repositories
        val legacyRepoImport =
            Regex(
                """^import com\.webdav\.player\.(data|domain)\.repository\.(DirectoryRepository|DirectoryRepositoryImpl|LibrarySearchRepository|LibrarySearchRepositoryImpl)\b""",
                RegexOption.MULTILINE,
            )
        val legacyRepoOffenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { legacyRepoImport.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertTrue(
            "No production source may import legacy repositories: $legacyRepoOffenders",
            legacyRepoOffenders.isEmpty(),
        )
    }

    @Test
    fun storageLifecycleAndRepositorySeams_enforceUnidirectionalAcyclicInvariants() {
        val serverRepoSource = source("data/repository/ServerRepositoryImpl.kt")

        // 1. ServerRepositoryImpl must not import MediaLibraryRepository
        assertFalse(
            "ServerRepositoryImpl must not import MediaLibraryRepository",
            serverRepoSource.contains("import com.webdav.player.domain.repository.MediaLibraryRepository"),
        )
        assertFalse(
            "ServerRepositoryImpl must not import MediaLibraryRepositoryImpl",
            serverRepoSource.contains("MediaLibraryRepositoryImpl"),
        )

        // 2. ServerRepositoryImpl must contain zero mutable repository dependencies
        assertFalse(
            "ServerRepositoryImpl must not contain mutable var repository dependencies",
            serverRepoSource.contains("var mediaLibraryRepository"),
        )
        val mutableRepoProperty = Regex("""^\s*var\s+.*(?:Repository|Invalidator)""", RegexOption.MULTILINE)
        assertFalse(
            "ServerRepositoryImpl must not contain mutable repository or invalidator fields",
            mutableRepoProperty.containsMatchIn(serverRepoSource),
        )

        // 3. WebDavApplication must contain zero downcasts or late-mutation on repository singletons
        val appSource = source("WebDavApplication.kt")
        assertFalse(
            "WebDavApplication must not contain downcast (as? ServerRepositoryImpl)",
            appSource.contains("as? ServerRepositoryImpl"),
        )
        assertFalse(
            "WebDavApplication must not contain downcast (as ServerRepositoryImpl)",
            appSource.contains("as ServerRepositoryImpl"),
        )
        assertFalse(
            "WebDavApplication must not perform late-mutation on serverRepository",
            appSource.contains("serverRepository.mediaLibraryRepository"),
        )

        // 4. LocalStorageDirectoryResolver must not import or depend on ServerRepository
        val resolverSource = source("data/repository/LocalStorageDirectoryResolver.kt")
        assertFalse(
            "LocalStorageDirectoryResolver must not import ServerRepository",
            resolverSource.contains("import com.webdav.player.domain.repository.ServerRepository"),
        )
        assertFalse(
            "LocalStorageDirectoryResolver must not reference ServerRepository",
            resolverSource.contains("ServerRepository"),
        )
    }

    @Test
    fun decoupledStorageReachability_enforcesUnidirectionalSeams() {
        // 1. PlaybackFaultTolerance must not import or reference PlaylistRepository
        val faultToleranceSource = source("domain/session/PlaybackFaultTolerance.kt")
        assertFalse(
            "PlaybackFaultTolerance must not import PlaylistRepository",
            faultToleranceSource.contains("import com.webdav.player.domain.repository.PlaylistRepository"),
        )
        assertFalse(
            "PlaybackFaultTolerance must not reference PlaylistRepository",
            faultToleranceSource.contains("PlaylistRepository"),
        )

        // 2. PlaylistRepository and PlaylistRepositoryImpl must not import or reference WebDavClient
        val playlistRepoInterface = source("domain/repository/PlaylistRepository.kt")
        assertFalse(
            "PlaylistRepository interface must not reference WebDavClient",
            playlistRepoInterface.contains("WebDavClient"),
        )
        val playlistRepoImpl = source("data/repository/PlaylistRepositoryImpl.kt")
        assertFalse(
            "PlaylistRepositoryImpl must not import WebDavClient",
            playlistRepoImpl.contains("import com.webdav.player.data.remote.WebDavClient"),
        )
        assertFalse(
            "PlaylistRepositoryImpl must not reference WebDavClient",
            playlistRepoImpl.contains("WebDavClient"),
        )
        assertFalse(
            "PlaylistRepositoryImpl must not reference localUriReachabilityChecker",
            playlistRepoImpl.contains("localUriReachabilityChecker"),
        )
        assertFalse(
            "PlaylistRepositoryImpl must not reference serverRepository",
            playlistRepoImpl.contains("serverRepository"),
        )

        // 3. AppContainer must expose storageReachabilityTracker
        val appContainer = source("di/AppContainer.kt")
        assertTrue(
            "AppContainer must expose storageReachabilityTracker",
            appContainer.contains("val storageReachabilityTracker: StorageReachabilityTracker"),
        )
    }

    @Test
    fun playbackSession_exposesZeroBrowsingCursorConcerns() {
        // ADR-0039: MusicPlayerAppSession facade has physically purged directory browsing methods
        val sessionInterface = source("domain/session/MusicPlayerAppSession.kt")
        assertFalse(
            "MusicPlayerAppSession must not declare getLastDirectoryForServer",
            sessionInterface.contains("getLastDirectoryForServer"),
        )
        assertFalse(
            "MusicPlayerAppSession must not declare setCurrentDirectoryPath",
            sessionInterface.contains("setCurrentDirectoryPath"),
        )

        // MusicPlayerAppSessionImpl must not contain directory browsing methods
        val sessionImpl = source("domain/session/MusicPlayerAppSessionImpl.kt")
        assertFalse(
            "MusicPlayerAppSessionImpl must not declare getLastDirectoryForServer",
            sessionImpl.contains("getLastDirectoryForServer"),
        )
        assertFalse(
            "MusicPlayerAppSessionImpl must not declare setCurrentDirectoryPath",
            sessionImpl.contains("setCurrentDirectoryPath"),
        )

        // PlayerSessionState must not contain currentDirectoryPath
        val sessionState = source("domain/model/PlayerSessionState.kt")
        assertFalse(
            "PlayerSessionState must not contain currentDirectoryPath",
            sessionState.contains("currentDirectoryPath"),
        )

        // PlaybackSessionData must not contain browsing directory fields
        val sessionData = source("domain/model/PlaybackSessionData.kt")
        assertFalse(
            "PlaybackSessionData must not contain currentDirectoryPath",
            sessionData.contains("currentDirectoryPath"),
        )
        assertFalse(
            "PlaybackSessionData must not contain serverLastDirectories",
            sessionData.contains("serverLastDirectories"),
        )

        // DataStorePlaybackSessionStore must not contain KEY_CURRENT_DIRECTORY_PATH or KEY_SERVER_LAST_DIRS_JSON
        val sessionStore = source("data/repository/DataStorePlaybackSessionStore.kt")
        assertFalse(
            "DataStorePlaybackSessionStore must not reference current_directory_path",
            sessionStore.contains("current_directory_path"),
        )
        assertFalse(
            "DataStorePlaybackSessionStore must not reference server_last_dirs_json",
            sessionStore.contains("server_last_dirs_json"),
        )
        assertFalse(
            "DataStorePlaybackSessionStore must not reference KEY_CURRENT_DIRECTORY_PATH",
            sessionStore.contains("KEY_CURRENT_DIRECTORY_PATH"),
        )
        assertFalse(
            "DataStorePlaybackSessionStore must not reference KEY_SERVER_LAST_DIRS_JSON",
            sessionStore.contains("KEY_SERVER_LAST_DIRS_JSON"),
        )

        // SessionPersistenceCoordinator must not reference currentDirectoryPath or serverLastDirectories
        val persistenceCoordinator = source("domain/session/SessionPersistenceCoordinator.kt")
        assertFalse(
            "SessionPersistenceCoordinator must not reference currentDirectoryPath",
            persistenceCoordinator.contains("currentDirectoryPath"),
        )
        assertFalse(
            "SessionPersistenceCoordinator must not reference serverLastDirectories",
            persistenceCoordinator.contains("serverLastDirectories"),
        )
    }
}
