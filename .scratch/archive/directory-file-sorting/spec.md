# Spec: 媒体库目录排序策略与播控队列对齐体系 (Directory File Sorting)

Status: `ready-for-agent`  
ADR: [`docs/adr/0040-directory-sort-order-and-queue-alignment.md`](../../docs/adr/0040-directory-sort-order-and-queue-alignment.md)

## Problem Statement

Aoffee Player 作为跨源（WebDAV 与本地设备存储 SAF）高保真音乐播放器，在媒体库目录浏览与点播时缺乏完整的排序体系：
1. **排序机制缺失与双源行为分裂**：本地存储（SAF）硬编码按名称全小写排序，而远程 WebDAV 直接透传服务端 PROPFIND 响应的原始无序条目，表现层完全缺乏由用户主导的排序机制；
2. **纯文本字典序导致数字曲目断裂**：标准字符串比较使得 `["Track 1", "Track 10", "Track 2"]` 乱序，严重破坏专辑与编号曲目的自然听歌习惯；
3. **展示顺序与底层播控队列失步风险**：用户在媒体库中点播曲目时，底层播放引擎直接根据当前目录的 `files` 列表构建队列。若排序仅停留在 UI 瞬态展示而未穿透至目录实体，会导致“界面看到是按修改时间排列，点击播放后下一首却是按文件名乱序播放”的严重视听断裂。

## Solution

在媒体库目录浏览全链路中引入统一的 **`DirectorySortOrder`** 领域模型与排序体系：
- **核心维度与规则**：支持按名称（NAME）、最后修改时间（LAST_MODIFIED）、文件大小（SIZE）三个维度排序，并支持升序（ASCENDING）与降序（DESCENDING）切换；
- **数字自然语序（Natural Alphanumeric Order）**：名称排序采用分块自然排序算法（Chunk-based Natural Comparator），严格保证 `Track 1` < `Track 2` < `Track 10`；
- **文件夹始终置顶守恒律（Subdirectories Top-Pinned Invariant）**：在任何排序维度下，子文件夹始终置顶排列在上方，音频与其它文件紧随其后排列在下方，两组内部各自按选定规则排序；
- **全局偏好持久化（Global Persistence）**：用户设置的排序偏好持久化保存至媒体库偏好存储，冷启动后自动恢复，全库各存储源与目录通用；
- **所见即所播（WYSIWYG Playback Queue）**：在目录内点击任意音轨点播时，派发给底层播放会话的曲目序列严格与界面展示的排序序列一致；多选批量操作同理；
- **零额外 I/O 守恒（In-Memory Presentation Sorting）**：切换排序维度完全在内存中瞬时完成重排，不改写本地持久缓存 `directory_cache`，不触发多余的网络或数据库 I/O；
- **M3E 顶栏交互集成**：在 `FluidTopAppBar` 搜索按钮左侧提供排序图标按钮，点击拉起 Material 3 `DropdownMenu`，提供维度与方向点选及状态勾选反馈。

## User Stories

1. 作为听众在浏览媒体库目录时，我希望能按名称（A-Z、Z-A）、修改时间（最新、最旧）和文件大小（从大到小、从小到大）对文件进行排序，以便快速定位音轨。
2. 作为听众在查看带序号的专辑目录（如 `01.xxx`, `02.xxx`, `10.xxx` 或 `Track 1`, `Track 2`, `Track 10`）时，排序能遵循数字自然语序，而不是生硬的纯字典序导致 `10` 排在 `2` 前面。
3. 作为听众在切换不同的排序方式时，子文件夹始终固定排列在列表最顶端、音频文件排列在下方，符合主流文件浏览习惯。
4. 作为听众在选择某一排序方式（如按修改时间倒序）后退出应用重进，该排序偏好依然生效，无需重复手动配置。
5. 作为听众在已排序的目录中点击某首歌曲播放时，生成的播放队列完全对齐界面当前可见的顺序，下一首播放的歌曲符合我眼前的视觉预期。
6. 作为听众在已排序的目录中使用多选模式点击“全选”并执行“下一首播放”或“添加到歌单”时，操作的音轨顺序与界面展示的排序完全一致。
7. 作为听众在点击切换排序规则时，界面列表瞬时重排呈现，无需重新转菊花或重新从远程 WebDAV 下载目录数据。
8. 作为听众在顶栏点击排序图标时，能看到清晰规范的 Material 3 菜单，标明当前生效的排序维度与升降序状态。

## Implementation Decisions

### 1. 领域模型与自然排序器
- 在 `com.webdav.player.domain.model` 中定义：
  - `enum class SortField { NAME, LAST_MODIFIED, SIZE }`
  - `enum class SortDirection { ASCENDING, DESCENDING }`
  - `data class DirectorySortOrder(val field: SortField = SortField.NAME, val direction: SortDirection = SortDirection.ASCENDING)`
- 编写 `NaturalOrderComparator`：支持分块数字切分、不区分大小写、中英文数字混合排序；
- 编写 `RemoteDirectorySorter`：为 `RemoteDirectory` 提供扩展函数 `withSortOrder(sortOrder: DirectorySortOrder): RemoteDirectory`。

### 2. 仓储层偏好存储下沉
- 在 `MediaLibraryRepository` 接口中暴露：
  - `fun getDirectorySortOrder(): DirectorySortOrder`
  - `fun setDirectorySortOrder(sortOrder: DirectorySortOrder)`
- 在内部偏好存储（`InternalBrowsingPreferences` 或拓展 `InternalBrowsingCursorStore`）中读写 SharedPreferences，并提供内存并发缓存，保证主线程安全与毫秒级读取。

### 3. ViewModel 响应式重排与队列对齐
- `DirectoryBrowserUiState` 新增 `sortOrder: DirectorySortOrder = DirectorySortOrder()`；
- `DirectoryBrowserViewModel` 在初始化时读取持久化的排序偏好，并在目录数据发射时调用排序器重排；
- 提供 `onSortOrderChanged(newOrder: DirectorySortOrder)`，实时更新持久化与内存 `currentDirectory`；
- `onAudioTrackClicked` 与批量操作直接取自已排序的 `currentDirectory`，确保播控队列所见即所播。

### 4. Compose 顶栏交互
- 在 `DirectoryBrowserScreen` 的 `FluidTopAppBar` 动作区域（搜索按钮左侧）集成排序 `IconButton`；
- 维护 `isSortMenuExpanded` 状态，展开 Material 3 `DropdownMenu`；
- 菜单展示排序维度（名称、修改时间、文件大小）与排序方向（升序、降序），通过带选中徽章的 `DropdownMenuItem` 提供即时视觉反馈。
