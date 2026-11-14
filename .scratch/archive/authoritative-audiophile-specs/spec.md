# Spec: Authoritative Audiophile Specs Extraction, Room Migration, and Strict Spec Gating

Status: ready-for-agent

## Problem Statement

Aoffee Player is designed as an audiophile-grade high-fidelity streaming and direct playback application. However, the current audio specification pipeline suffers from a fundamental disconnect between low-level container parsing and high-level specification presentation:

1. **Information Loss at Binary Parser Seams**:
   Binary audio stream parsers (such as FLAC and WAV header extractors and MPEG audio frame analyzers) currently decode physical container stream headers to extract the sample rate and total sample counts (used to compute track duration). However, the resulting parsed metadata container drops the sample rate, channel count, and bit depth.
2. **Heuristic Guesswork & False Badges in Presentation**:
   Because real physical specifications are discarded during extraction, upper-layer quality resolution modules rely on regular expression matching against file name strings and speculative bitrate thresholds (e.g., guessing that FLAC files with bitrates above 2000 kbps are 96 kHz / 24-bit). This introduces severe user-facing defects:
   - Legitimate 24-bit/96kHz Hi-Res FLAC tracks with clean file names (e.g., `01. Hotel California.flac`) are demoted to 16-bit/44.1kHz standard lossless, erroneously stripping their Hi-Res badges.
   - High-complexity 16-bit/44.1kHz standard CD audio tracks whose dynamic range produces bitrates exceeding 2000 kbps are erroneously stamped with 96kHz / 24-bit Hi-Res badges.
3. **Missing Physical Attributes in Domain and Persistence Models**:
   The primary playback track and metadata persistence models do not store physical sample rates, bit depths, or channel counts. Consequently, every render pass of the track detail sheet, player controls, and track rows forces repeated string parsing and heuristic guesswork.
4. **Lack of Specification Persistence Across Sessions**:
   The local database schema for track metadata lacks dedicated columns for physical audio properties. Even when a track's header is inspected during streaming or local resolution, the verified physical parameters are never cached, making offline specification display impossible.

## Solution

Deepen the audio metadata extraction and specification pipeline into an authoritative, verifiable system backed by real stream physics, safe database persistence, and strict gating:

1. **Preserve Physical Specs at Binary Parser Seams**:
   Extend parsed audio metadata to carry sample rate, bit depth, channel count, and verified bitrate directly from binary container headers (such as FLAC `STREAMINFO`, WAV `fmt ` chunks, and MPEG frame headers).
2. **Expand Persistence Schema with Room Migration (v10 to v11)**:
   Add physical specification attributes (sample rate, bit depth, bitrate, channels) to the persistent track metadata entity. Upgrade the database version to 11 with a non-destructive schema migration (`MIGRATION_10_11`), guaranteeing seamless backward compatibility for existing user databases.
3. **Carry Authoritative Specs on Playback Entities**:
   Enhance the core audio track domain model to hold optional authoritative physical specifications. When resolving metadata, populate these attributes directly so that all downstream consumers (mini-player, full player, and track detail sheet) observe genuine physical data with zero async re-querying.
4. **Enforce Strict Spec Gating (Eliminate Guesswork)**:
   Abolish all file name regular expression heuristics and bitrate guessing rules. Enforce a strict gating contract:
   - When real physical parameters are present, render the exact sample rate, bit depth, and bitrate.
   - When real physical parameters are absent (e.g., network truncation, corrupted headers, or unsupported containers), render only the verified base format name (e.g., `FLAC` or `WAV`) without inventing speculative numbers.
   - Restrict Hi-Res badge assignment strictly to tracks with verified physical sample rates of 88.2 kHz or higher, or verified bit depths of 24 bits or higher.

## User Stories

1. As an audiophile streaming FLAC files from my WebDAV server, I want to see the true physical sample rate and bit depth extracted from the stream header, so that I know the exact quality of the recording.
2. As a listener playing high-resolution audio files with clean file names (without spec tags like `[96-24]`), I want the app to recognize their genuine 24-bit/96kHz specs, so that the Hi-Res lightning badge appears reliably.
3. As a listener playing complex CD-quality 16-bit/44.1kHz FLAC tracks, I want the app to never mislabel them as 96kHz / 24-bit, so that I am never misled by fake specifications.
4. As an audiophile playing uncompressed WAV files, I want the sample rate, bit depth, and channel count to be accurately read from the format chunk, so that studio masters are correctly identified.
5. As a listener playing MP3 files, I want the exact frame sample rate and bitrate to be displayed, so that I can distinguish between 320 kbps and lower-quality encodings.
6. As a user opening the Track Detail Bottom Sheet, I want to view verified physical audio specifications immediately without UI stutter or calculation delays, so that the inspection experience feels instantaneous.
7. As an Android user updating the application, I want my existing library database to migrate safely without data loss or crashes, so that my cached songs and playlists remain intact.
8. As an offline listener playing previously scanned tracks, I want physical audio specifications to load directly from the local database cache, so that I can see audio specs without requiring active network connections.
9. As a developer maintaining audio resolution logic, I want audio specs to be derived from structured numbers rather than regular expressions on file names, so that the codebase is robust and maintainable.
10. As a developer writing tests, I want to verify audio header parsing by passing discrete byte arrays to the parser interface, so that parsing edge cases can be tested in milliseconds.
11. As a listener playing a track with truncated or corrupted headers, I want the app to display the base audio format name gracefully without guessing numbers or crashing, so that the playback experience remains stable.
12. As an audiophile inspecting multi-channel recordings, I want the channel configuration (stereo or multi-channel) to be preserved, so that the audio format is faithfully represented.
13. As a developer inspecting the playback queue, I want the active AudioTrack entity to carry its own physical specifications, so that UI components do not need to query secondary metadata repositories.
14. As a listener switching between songs in the full-screen player, I want the dynamic audio quality pill and Hi-Res badges to update instantaneously with authoritative stream data, so that the visual feedback matches the audio.
15. As a performance-conscious mobile user, I want track list rendering to avoid executing regular expression matches on every row item, so that list scrolling remains 120fps smooth.
16. As a developer ensuring architectural compliance, I want the audio specification pipeline to strictly adhere to the domain glossary and ADR-0044, so that architectural boundaries are maintained.

## Implementation Decisions

### 1. Binary Parser Deepening and Data Transfer
- **Enhanced Parsed Metadata**: Extend the parsed audio metadata transfer object to include nullable physical properties: sample rate in Hertz, bit depth in bits per sample, bitrate in kilobits per second, and channel count.
- **FLAC Container Extraction**: Update the FLAC parser's stream info block processing to extract:
  - 20-bit sample rate from byte offsets 10, 11, and 12.
  - 3-bit channel count from byte offset 12, adding 1 to obtain the actual channel count.
  - 5-bit bit depth from byte offsets 12 and 13, adding 1 to obtain the actual bits per sample.
  - Calculate instantaneous or average bitrate when audio stream length and duration are available.
- **WAV Container Extraction**: Update the WAV parser's format chunk reader to extract channel count, sample rate, bits per sample, and derive bitrate from byte rate.
- **MPEG Frame Extraction**: Route sample rate and bitrate detected during MPEG audio frame header synchronization directly into the parsed metadata object.

### 2. Database Schema Expansion & Room Migration
- **Entity Augmentation**: Add `sampleRate` (nullable Integer), `bitDepth` (nullable Integer), `bitrateKbps` (nullable Integer), and `channels` (nullable Integer) to the persistent track metadata entity.
- **Room Database Migration (v10 to v11)**:
  - Bump the database schema version from 10 to 11.
  - Author an explicit, non-destructive migration (`MIGRATION_10_11`) executing four `ALTER TABLE track_metadata ADD COLUMN ...` statements with `DEFAULT NULL`.
  - Register `MIGRATION_10_11` within the database builder to ensure seamless upgrade from version 10 without destructive recreation.
- **Domain Mapping Alignment**: Update domain-to-entity and entity-to-domain conversion mappers to propagate the four physical specification fields bidirectionally.

### 3. Domain Model and Track Resolution Alignment
- **Domain Metadata and Track Entities**: Extend the domain `TrackMetadata` and `AudioTrack` models with the physical specification attributes (`sampleRate`, `bitDepth`, `bitrateKbps`, `channels`).
- **Resolver Plumbing**: In the default track metadata resolver, map the parsed physical attributes from `ParsedAudioMetadata` into `TrackMetadata` upon initial header probe.
- **Track Construction**: When building `AudioTrack` from `RemoteFile` and `TrackMetadata` (or during queue loading and playlist mapping), pass through the authoritative physical specifications.

### 4. Strict Spec Gating in Audio Quality Module
- **Abolish Guesswork Heuristics**: Remove regex-based file name matching patterns (`SAMPLE_RATE_KHZ_PATTERN`, `BIT_DEPTH_PATTERN`, etc.) and bitrate-based FLAC/WAV 96kHz assumptions from specification resolution.
- **Strict Resolution Contract**:
  - Format string: If `sampleRate` and `bitDepth` are present, format as `<Format> · <SampleRate> / <BitDepth> · <Bitrate> kbps` (e.g. `FLAC · 96kHz / 24-bit · 2450 kbps`).
  - Fallback string: If physical specs are absent, format as `<Format>` or `<Format> · <Bitrate> kbps` (if bitrate is known), omitting speculative sample rates or bit depths.
  - Hi-Res Gating: Assign `isHiRes = true` strictly if `sampleRate >= 88200` or `bitDepth >= 24`. If physical specs are missing, `isHiRes` is strictly `false`.

## Testing Decisions

### Seam Strategy & Testing Principles
Tests must exercise external behavior across clear, high-level seams using real data inputs, asserting on verifiable outputs rather than private internal state:

1. **Binary Audio Parser Seams**:
   - Test `FlacParser` with realistic byte arrays containing valid FLAC magic bytes and `STREAMINFO` blocks. Verify that 44.1kHz/16-bit, 96kHz/24-bit, and 192kHz/24-bit headers accurately produce corresponding physical properties.
   - Test `WavParser` with valid RIFF/WAVE byte slices containing `fmt ` chunks. Verify that standard PCM and high-res PCM formats output accurate sample rates, channels, and bit depths.
   - Verify that truncated or corrupted header bytes return null or empty specs without throwing unhandled exceptions.
2. **Database Migration Seam**:
   - Test `MIGRATION_10_11` using the Room Migration testing harness or SQLite test assertions.
   - Verify that an existing database containing v10 `track_metadata` rows successfully migrates to v11 with new columns initialized to null, and subsequent read/write operations succeed.
3. **Domain Specification Resolution Seam**:
   - Test `AudioQuality.resolveAudiophileSpecs` with explicit physical parameters. Verify that genuine 24-bit/96kHz inputs produce the Hi-Res flag and correct formatted string.
   - Test `AudioQuality.resolveAudiophileSpecs` with null physical parameters and verify strict fallback: no regular expressions are used, no fake 96kHz is emitted, and `isHiRes` evaluates to false.
4. **End-to-End Resolver and Release Packaging Seams**:
   - Verify that `DefaultTrackMetadataResolver` captures physical specs from range bytes into `TrackMetadata`.
   - Run the packaging verification gate to ensure R8 shrinking, binary sizes, and packaging rules remain fully compliant.

## Out of Scope

- Modifying ID3 tag writers or editing local/remote file tags on disk.
- Writing native C/C++ decoders or modifying ExoPlayer low-level codec renderers.
- Changing visual dimensions, padding, or typography of UI badges and bottom sheets.
- Modifying network HTTP Range protocols or WebDAV client streaming primitives.

## Further Notes

- Existing cached tracks in user databases will have null physical fields until re-resolved or played. Under the strict gating contract, these tracks will display clean format names (e.g. `FLAC`) without bogus guesswork numbers until updated.
- All domain terminology conforms to `GLOSSARY.md` and aligns with `ADR-0044`.
