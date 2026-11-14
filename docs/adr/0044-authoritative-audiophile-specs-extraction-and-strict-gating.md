# 0044: 权威音频流规格提取、Room 迁移与严格规格门控 (Authoritative Audiophile Specs Extraction, Room Migration, and Strict Spec Gating)

## Status

Accepted

> 对齐发烧级音乐播放器核心定位，彻底消除基于文件名正则与码率阈值的音质推测启发式，建立从底层二进制流头到 Room 持久化与表现层的全链路权威音频规格。

## Context

在完成流体播放器与统一曲目详情抽屉（ADR-0043）后，发烧级音质呈现链路暴露出底层流解析与表现层规格计算严重脱节的根本性缺陷：

1. **二进制流解析器深层信息丢失**：
   - `FlacParser` 在解析 FLAC `STREAMINFO` 元数据块时，已从 bit 10..13 中精确计算了 `sampleRate` 与 `totalSamples`（用于计算 `durationMs`），但其伴随提取的 `channels`（声道数）与 `bitsPerSample`（位深）以及 `sampleRate` 本身却未随 `ParsedAudioMetadata` 向外暴露；
   - `WavParser` 在解析 RIFF `fmt ` 块时，已读取了 `byteRate`、`sampleRate`、`channels` 与 `bitsPerSample`，但同样在构建 `ParsedAudioMetadata` 时直接丢弃了物理规格；
   - `MpegAudioFrameParser` 已能精确解析 MPEG 帧头并得出 `sampleRate` 与 `bitrateKbps`，但该数据未对接到统一元数据模型中。
2. **表现层依赖文件名正则猜测与假数据隐患**：
   - 由于底层规格被丢弃，上层 `AudioQuality.resolveAudiophileSpecs` 沦落为使用宽泛的正规表达式（`SAMPLE_RATE_KHZ_PATTERN`、`BIT_DEPTH_PATTERN`）去匹配文件名字符串；
   - 当文件名无标记时，上层仅依据 `bitrate >= 2000kbps` 粗暴推断 FLAC 为 `96kHz / 24-bit`，导致普通 16-bit/44.1kHz 复杂 CD 音轨被错误打上 Hi-Res 闪电金标，而真实 24-bit/96kHz 但未在文件名标注规格的无损音轨反被降级为标准音质，造成发烧级核心体验严重失真。
3. **领域实体缺失物理规格字段**：
   - 核心可播放实体 `AudioTrack` 与持久化实体 `TrackMetadata` 均缺乏采样率、位深与声道数字段，使得每次曲目行与详情抽屉渲染都必须重复执行无谓的字符串正则匹配与码率推导。

## Decision

### 1. 深化二进制流解析器并补全 `ParsedAudioMetadata`
- 在 `ParsedAudioMetadata` 中新增物理规格字段：
  ```kotlin
  val sampleRate: Int? = null,
  val bitDepth: Int? = null,
  val bitrateKbps: Int? = null,
  val channels: Int? = null,
  ```
- **`FlacParser`**：在 `STREAMINFO` 解析块内完整提取 20-bit 采样率（`sampleRate`）、3-bit 声道数（`channels = ((b12 and 0x0E) ushr 1) + 1`）与 5-bit 位深（`bitDepth = (((b12 and 0x01) shl 4) or ((b13 and 0xF0) ushr 4)) + 1`），并计算实际码率注入；
- **`WavParser`**：在 `fmt ` 块解析中提取 `sampleRate`、`channels`、`bitsPerSample` 并由 `byteRate` 换算真实 `bitrateKbps`；
- **`MpegAudioFrameParser` / `AudioMetadataParser`**：统一将探测到的精确帧头参数注入结果。

### 2. 扩展持久化结构并安全执行 Room 数据库迁移 (v10 -> v11)
- 在 Room 实体 `TrackMetadataEntity` 与领域模型 `TrackMetadata` 中增补 `sampleRate`、`bitDepth`、`bitrateKbps`、`channels` 属性；
- 升级 `AppDatabase` 版本至 `11`，编写严格向后兼容的非破坏性迁移策略 `MIGRATION_10_11`：
  ```sql
  ALTER TABLE `track_metadata` ADD COLUMN `sampleRate` INTEGER DEFAULT NULL;
  ALTER TABLE `track_metadata` ADD COLUMN `bitDepth` INTEGER DEFAULT NULL;
  ALTER TABLE `track_metadata` ADD COLUMN `bitrateKbps` INTEGER DEFAULT NULL;
  ALTER TABLE `track_metadata` ADD COLUMN `channels` INTEGER DEFAULT NULL;
  ```
- 由 `DefaultTrackMetadataResolver` 在首次探测流头时将解析出的物理规格一并持久化，达成跨会话“一次解析，终生秒开真实规格”。

### 3. 增强 `AudioTrack` 实体权威规格承载能力
- 在核心播控实体 `AudioTrack` 中直接增补可选物理规格字段（`sampleRate`、`bitDepth`、`bitrateKbps`、`channels`）；
- `AudioTrack.audiophileSpecsModel` 优先使用自身持有的权威物理规格生成展示模型，杜绝跨层异步回查与视图层胶水代码。

### 4. 确立严格规格门控策略 (Strict Spec Gating，零猜测原则)
- **彻底废止文件名正则猜测**：
  删除 `AudioQuality` 中基于文件名正则（`SAMPLE_RATE_KHZ_PATTERN`、`BIT_DEPTH_PATTERN` 等）与码率阈值（`bitrate >= 2000` 强猜 96kHz）的推断逻辑；
- **优雅兜底契约**：
  若音频流头未解析出真实采样率或位深（如网络截断或不支持的特殊封装）：
  - 仅呈现基础格式名称（如 `FLAC`、`WAV`、`MP3 · 320 kbps`）；
  - `isHiRes` 严格由真实 `sampleRate >= 88200` 或 `bitDepth >= 24` 决定，无真实数据时一律为 `false`；
  - 坚决杜绝向用户展示任何推测或伪造的物理参数。

## Consequences

- **100% Truthful Audiophile Presentation**：彻底消除发烧友核心界面的假数据隐患，所有采样率、位深与 Hi-Res 金标均拥有真实二进制流头背书。
- **Zero Regex Overhead on Render Path**：列表项与详情抽屉在渲染时直接读取数值模型，完全免除运行时正则匹配与字符串解析。
- **Controlled & Safe Migration**：通过标准 Room `ALTER TABLE` 迁移脚本保护现有用户数据库完整性，零丢数据风险。
- **Clean Single-Session Testable Seam**：解析器、模型与数据库迁移各层均具备高内聚的纯单元测试表面，可完全脱离 Android UI 自动化执行毫秒级验证。
