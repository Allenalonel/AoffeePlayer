# 0034: 彻底废弃与物理清除 CUE 虚拟分轨支持 (Deprecate and Purge CUE Sheet Support and Consolidate Stream Playback)

## Status

Accepted

## Context

在 Aoffee Player 演进历程中，曾通过 ADR-0014（CUE 虚拟分轨单流映射）与 ADR-0021（深层 CUE 解析器封装）引入了对 CD 抓轨大文件（FLAC/WAV/APE + .cue）的虚拟分轨解析与回放支持。然而随着现代在线音频流与本地存储生态的发展，该特性逐渐退化为沉重的技术负债与架构阻碍：

1. **格式生态变迁与用户诉求偏离（Format Obsolescence）**：
   - 绝大多数通过 WebDAV 挂载的个人音乐库或通过 SAF 挂载的本地存储均已全面采用分轨单曲文件（.flac, .mp3, .m4a, .ogg 等）。整轨大文件加伴生 `.cue` 的抓轨格式已极度罕见。
2. **全链路架构摩擦与复杂性膨胀（Recurrent Architectural Friction）**：
   - CUE 虚拟分轨穿透了应用的所有层级：从数据层的 AST 词法分析（`CueParser`）、启发式母轨文件关联（`DefaultCueSheetResolver`）、数据库主键合成（`#cue_X` 虚拟 URI）、到播放引擎的时间轴伪装偏移（`VirtualTimelineEngine`、`applyCueContextAndGetStartMs`）以及冷启动会话恢复补丁。
   - 这在历次架构审查中持续引入复杂度告警与结构性债务。
3. **播放时序与时间轴抖动（Timeline Arithmetic Inconsistencies）**：
   - 由于多个虚拟音轨共享底层单条物理音频流，高频进度轮询、Seek 操作、无缝切歌及冷启动会话恢复必须依赖复杂的相对时间偏移量换算，偶发时间轴抖动与网络重缓冲边界异常。
4. **目录浏览体验被割裂（Confusing Directory UI）**：
   - 含有 `.cue` 的目录会拦截并隐藏母轨物理音频文件，将其嵌套进可展开的 `CueAlbumCard` 卡片中。用户想要直接播放整张专辑音频或纯粹浏览文件时，面临繁琐的折叠展开交互。

## Decision

彻底废弃并物理清除全部 CUE 分轨解析、虚拟时间轴换算与合成 URI 逻辑，将播放引擎与仓储层 100% 收敛恢复为物理真实音频流的 1:1 直流回放：

### 1. 数据库安全迁移（Room v7 升级至 v8）

- **版本递增与非破坏性升级 (`MIGRATION_7_8`)**：
  - Room 数据库版本由 7 升级至 8。
  - Android 10 (API 29) 的 SQLite 3.28 不支持 `ALTER TABLE DROP COLUMN`，因此采用标准的临时表重构模式：
    - `playlist_tracks` 重构：创建不包含 `cuePath` 与 `virtualTrackId` 列的新表，迁移数据时过滤剔除所有历史遗留的虚拟音轨（`remotePath NOT LIKE '%#cue_%' AND virtualTrackId IS NULL`）；
    - `media_track_index` 重构：创建不包含 `isCue` 列的新表，迁移数据时过滤剔除所有 CUE 虚拟分轨与 CUE 索引行（`isCue = 0 AND remotePath NOT LIKE '%#cue_%'`）；
    - 完整重建外键级联约束与全部 B-Tree 索引。
- **清除 CUE 专用持久化偏好与 SQL 分支**：
  - `DataStorePlaybackSessionStore` 移除 `KEY_CUE_PATH` 偏好键；
  - `TrackMetadataDao` 与 `DirectoryCacheDao` 移除对 `#cue_` 与 `.cue` 的特殊查询分支。

### 2. 纯粹 1:1 物理音频流播放引擎管道

- **剥离虚拟时间轴状态机**：
  - `PlaybackTimelineCoordinator` 彻底移除 `activeCuePath`、`applyCueContextAndGetStartMs()` 及全部虚拟时间轴偏移计算，ExoPlayer 时间轴进度与时长直接反映物理媒体流的真实值。
  - 彻底删除 `VirtualTimelineEngine`。
- **播放队列管理器纯物理化 (`PlaybackQueueManager`)**：
  - 队列仅容纳 1:1 物理 `AudioTrack` 实体，完全移除目录 CUE 关联映射与 `#cue_` 虚拟 ID 拆分。
- **冷启动会话恢复极速化 (`SessionPersistenceCoordinator`)**：
  - 冷启动恢复完全基于持久化的标准音轨元数据直接起播，不再发送网络请求去二次拉取并解析 `.cue` 文本。

### 3. 仓储层与多源同步扫描净化

- **`DirectoryRepositoryImpl`**：
  - 移除 CUE 探测与骨架占位生成流程，直接发射目录下的全部物理文件与文件夹，伴生大音频文件作为普通音频音轨正常呈现。
- **`LibrarySearchRepositoryImpl`**：
  - 移除 CUE 文本缓存查询与虚拟分轨展开，搜索查询直接利用原生 SQLite B-Tree 索引精确秒级匹配物理音轨。
- **本地 SAF 与 WebDAV 扫描器**：
  - `LocalStorageDirectoryResolver` 与 `LocalStorageIndexScanner` 停止加载本地 `.cue` 文本流入内存，不再生成虚拟分轨记录。
  - `WebDavSyncScanner` 移除 `CueVirtualTrackIndexer`，仅对标准音频格式建立索引。
- **`.cue` 文件表现标准化**：
  - 远程与本地目录中的 `.cue` 文件被归类为常规非音频辅助文档，以半透明（0.5f）、不可点播的标准文件样式展现。

### 4. 物理删除 CUE 模块与依赖注入清理

- **物理删除源码模块（~4,500 行代码缩减）**：
  - 删除 `data.cue` 包（`CueParser.kt`、`CueVirtualTrackIndexer.kt`、`DefaultCueSheetResolver.kt`）；
  - 删除 `domain.cue` 包（`CueSheetResolver.kt`）；
  - 删除领域模型 `CueAlbumItem.kt` 与 `VirtualTrack.kt`；
  - 删除 UI 组件 `CueAlbumCard.kt`；
  - 删除相关单测套件 `CueParserTest.kt`、`CueVirtualTrackIndexerTest.kt`、`DefaultCueSheetResolverTest.kt`、`VirtualTrackTest.kt`、`VirtualTimelineEngineTest.kt`。
- **容器与类型净化**：
  - `AppContainer` 与 `WebDavApplication` 移除 `cueSheetResolver` 属性与构造注入。
  - `RemoteFileType` 移除 `Cue` 类型；`RemoteFile` 移除 `isCue`；`AudioQuality` 移除 CUE 徽章映射。

## Consequences

### Positive
- **极致纯粹与代码瘦身**：一次性删除 4,500+ 行高复杂度代码，彻底卸下 CUE 相关的 AST 词法、启发式匹配及虚拟时间轴技术债务。
- **播放与时间轴绝对精准**：杜绝进度漂移、跨轨杂音与二次重缓冲，Seek 与无缝切歌达到 100% 物理真实流表现。
- **平滑安全的数据库升级**：Room v8 迁移脚本自动清除受污染的虚拟分轨行，用户既有歌单与所有真实曲目保持完整。
- **浏览体验统一扁平**：目录列表恢复为清晰统一的文件夹与曲目平铺结构，消灭了嵌套卡片带来的层级割裂感。

### Superseded
- 本 ADR 正式取代（Supersede）并废止：
  - `docs/adr/0014-cue-virtual-track-and-folder-ring-gapless-streaming.md`
  - `docs/adr/0021-deepen-cue-sheet-resolver-and-purge-network-leakage.md`
