# ADR-0040: 媒体库目录排序策略与播控队列对齐架构

**状态**: Accepted  
**日期**: 2026-11-10  
**上下文**: 媒体库（Media Library）文件浏览与播放队列生成机制

---

## 1. 背景与痛点 (Context & Problem Statement)

Aoffee Player 作为跨源（WebDAV 与本地 SAF）高保真音乐播放器，在媒体库目录浏览与播放时存在如下局限：
1. **排序机制缺失与双源行为分裂**：本地存储（`LocalStorageDirectoryResolver`）写死按名称升序 `sortedBy { it.name.lowercase() }`，而远程 WebDAV（`OkHttpWebDavClient`）直接透传服务端 PROPFIND 响应的原始无序列表，表现层完全缺乏由用户主导的排序机制；
2. **纯文本字典序数字断裂**：常规字符串排序导致 `["Track 1", "Track 10", "Track 2"]` 乱序，破坏专辑与编号曲目的自然听歌习惯；
3. **播控队列与展示序列潜在失步**：用户在媒体库中点播曲目时，`PlaybackQueueManager.buildDirectoryQueue` 直接取自 `RemoteDirectory.files`。若排序仅作为表现层瞬态渲染而不穿透至目录实体，会导致“界面看到是按时间排序，点击播放后下一首却是按原始名称播放”的严重视听断裂。

---

## 2. 核心架构决策 (Architectural Decisions)

### 2.1 统一领域模型与自然排序法则 (Domain Model & Natural Order)
提炼纯领域模型：
- `SortField`：`NAME`（名称/标题）、`LAST_MODIFIED`（最后修改时间）、`SIZE`（文件大小）；
- `SortDirection`：`ASCENDING`（升序）、`DESCENDING`（降序）；
- `DirectorySortOrder`：聚合 `field` 与 `direction`，默认初始为 `NAME` + `ASCENDING`；
- **数字自然语序（Natural Alphanumeric Order）**：针对 `NAME` 排序引入大小写不敏感的分块数字自然比较算法（Chunk-based Natural Comparator），严格保证 `Track 1` < `Track 2` < `Track 10`。

### 2.2 文件夹始终置顶守恒律 (Subdirectories Top-Pinned Invariant)
在任何选定的排序规则下，**子文件夹（Subdirectories）始终集中置顶在列表上半部分**，音频/普通文件排列在下方。子文件夹与文件各自在其独立分组内部遵循用户选定的排序维度与升降序。

### 2.3 表现层实时重排与持久缓存不可变原则 (In-Memory Presentation Sorting)
本地持久缓存（`directory_cache` 表）与 L1 内存缓存仅存储结构化原始快照，不对缓存 JSON 进行排序污染。排序逻辑统一收敛在 `DirectoryBrowserViewModel` 或轻量域内比较器，在数据流发出或排序切换时瞬时应用。切换排序时**零数据库 I/O、零网络重新请求**。

### 2.4 播控队列与多选上下文全保真对齐 (Queue & Selection Alignment)
当用户在已排序的目录中点击单曲播放时，传递给 `MusicPlayerAppSession.playDirectoryTrack()` 的目录数据必须携带**已完成排序的 `files` 列表**，确保生成的 `PlaybackQueue` 顺序与界面呈现完全一致。同时，多选模式下的“全选”与“批量播放”亦严格对齐该排序序列。

### 2.5 全局偏好持久化下沉 (Global Preference Persistence)
排序偏好为全局持久化配置，委托 `MediaLibraryRepository`（内部由轻量偏好存储 `InternalBrowsingPreferences` 承载），冷启动后自动恢复，全库各存储源与目录通用。

### 2.6 M3E 顶部操作区收敛交互 (M3E TopAppBar Action)
在媒体库顶部 `FluidTopAppBar` 搜索按钮左侧提供排序图标按钮，点击拉起 Material 3 `DropdownMenu`，支持即时点选排序维度与方向，选中状态带有 Checkmark 显式反馈。

---

## 3. 影响与收益 (Consequences)

- **Positive**: 补全媒体库核心浏览能力，统一 WebDAV 与本地存储的排序行为；消除纯字典序导致的音轨乱序；保证用户所见即所播（WYSIWYG Playback Queue）。
- **Negative**: 需要在 `DirectoryBrowserViewModel` 中维护排序状态并与持久层打通；修改时间（RFC 1123 字符串与毫秒时间戳）需要宽容解析比较。
