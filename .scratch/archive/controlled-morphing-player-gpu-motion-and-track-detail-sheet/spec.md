# Spec: 受控流体播放器、GPU 绘制阶段变换与统一曲目详情抽屉 (Controlled Morphing Player, GPU Draw Phase Motion, and Unified Track Detail Bottom Sheet)

Status: `completed`  
ADR: [`docs/adr/0043-controlled-morphing-player-gpu-motion-and-track-detail-sheet.md`](../../docs/adr/0043-controlled-morphing-player-gpu-motion-and-track-detail-sheet.md)

## Problem Statement

在当前 Aoffee Player 表现层交互、微动效渲染与客户端测试架构中，存在四项直接破坏用户体验并违反架构法典的结构性问题：
1. **播放器展开与抽屉宿主状态三层重叠**：`MainActivity` 维护 `playerExpansionProgress` 驱动底部导航栏避让与主界面内容缩放；然而 `MorphingPlayerContainer` 内部又独立分配了一份闲置的 `animateFloatAsState` 动画状态机；`FullPlayerView` 与 `MainActivity` 各自独立挂载了一套 `HoistedPlaybackQueueSheet`；下拉关闭手势与系统返回键拦截在三处重复实现，导致队列抽屉展开时返回键可能误将全屏播放器一同关闭，存在严重的层级状态竞态。
2. **违背 GPU 绘制阶段变换红线（帧内 CPU 重排重测）**：`MorphingPlayerContainer` 在执行 0f 到 1f 形变过渡时，三大常驻单体实体（专辑封面 Squircle、播放/暂停胶囊、队列按钮）通过 `Modifier.offset { ... }.size(...)` 动态调整尺寸。在 60fps/120fps 高刷屏上，每一帧都在调用 `.size(toDp())` 强行触发 Compose Recomposition 与 Measure/Layout 阶段重新排版计算，产生高额 CPU 负载，引发明显的界面掉帧与微抖动，直接触犯了 `CODING_STANDARDS.md §6` 的 GPU 绘制阶段变换铁律。
3. **曲目信息弹窗重复实现且违背焦点隔离规范**：媒体库列表（`AudioTrackItemRow.FileInfoDialog`）与歌单详情（`PlaylistTrackDetailDialog`）各自硬编码了一套音频详细信息居中卡片，布局样式与发烧规格呈现逻辑重复割裂；两处弹窗均为传统 AlertDialog，且存在一个仅有 1 行代码的单调用者浅包装文件 `TabAccessibilityGuard.kt`，不仅违背了 Material 3 Expressive 统一沉浸式抽屉规范，亦缺乏对 Android 14+ 预测性返回与 Window Focus 的隔离保护。
4. **`WebDavClient` 端口缺口与 7 套分散重复的测试 Fake**：`WebDavClient` 核心端口接口缺失了 `invalidateServer` 与 `getStreamingClientForServer` 方法，导致上层仓储与媒体源工厂被迫绕过接口强转具体类 `OkHttpWebDavClient`；同时，全工程测试套件中散落了 7 份各自手写的私有 `FakeWebDavClient` 内部类，重复编写且能力不一，维护成本极高并掩盖真实接口缺陷。

## Solution

依据 `ADR-0043`、`CODING_STANDARDS.md §6` 以及主流成熟商业播放器（Apple Music、Spotify、Poweramp、Symfonium）的最佳实践，全面收拢流体播放器宿主权责、落地 GPU 零重排微动效、统一沉浸式曲目详情抽屉并闭环测试端口：
- **`MainActivity` 单体协调与纯受控 `MorphingPlayerContainer`**：由 `MainActivity` 作为全屏唯一的 Scaffold Coordinator 统一维护展开状态与进度，单向驱动底栏位移与唯一的顶层 `PlaybackQueueBottomSheet` 挂载；彻底移除 `MorphingPlayerContainer` 内部的闲置动画状态；`FullPlayerView` 退化为纯展示插槽并移除重复的抽屉副本与手势，建立严格的返回键拦截链路（抽屉优先 -> 全屏折叠 -> 退出）。
- **纯 GPU Draw 阶段 RenderNode 矩阵形变**：三大常驻单体实体以展开全屏状态固定占位，彻底废除逐帧 `Modifier.size()` 与 `Modifier.offset()`；在 `Modifier.graphicsLayer` 内部纯粹通过 GPU DisplayList 矩阵运算（`scaleX/Y`、`translationX/Y`、动态圆角 `shape`、动态阴影 `shadowElevation`）完成无缝流体形变，达成全过渡过程 0 次 Measure/Layout 重排，实现真机极限丝滑。
- **统一 M3 Expressive `TrackDetailBottomSheet` 并清理浅文件**：在 `ui.common` 建立单一权威的曲目详情抽屉，聚合封面缩略图、标题/艺术家/专辑、发烧物理规格（采样率/位深/比特率/Hi-Res）、存储卷标格式化路径与文件大小时长；强制配置 `isFocusable = false` 焦点隔离与 `sheetState.hide()` 优雅收起动效，根治 IME 键盘闪退鬼影；彻底物理删除媒体库与歌单两处旧 Dialog，并内联删除单行浅包装 `TabAccessibilityGuard.kt`。
- **补齐 `WebDavClient` 端口抽象与统一 `SharedFakeWebDavClient`**：在 `WebDavClient` 接口中补齐 `invalidateServer` 与 `getStreamingClientForServer`，彻底消除运行时强转；在测试基础包中建立统一可配置的 `SharedFakeWebDavClient`，全面替换并清除 7 个测试文件中散落的重复内联 Fake。

## User Stories

1. 作为听众，当我点击常驻 Mini-Player 向上拖拽或点击展开全屏播放器时，我希望动画如丝般顺滑（稳定运行在 60fps/120fps），没有任何掉帧、抽搐或重影。
2. 作为听众，当全屏播放器处于展开状态且我打开了播放队列抽屉时，我希望按下系统返回键首先关闭播放队列抽屉，再次按下返回键折叠全屏播放器回到底部迷你胶囊，而不是直接退回桌面或发生多层同时关闭的视觉混乱。
3. 作为听众，当我在全屏播放器中点击右下角队列图标时，我希望平滑滑出唯一的全局播放队列抽屉，而不是在播放器内部又嵌套一层带黑底遮罩的重复抽屉。
4. 作为听众，当我在全屏播放器任意非控件区域向下轻扫或拖拽时，我希望播放器跟随手势自然回弹或顺畅折叠为迷你胶囊，不会发生手势冲突或双重触发。
5. 作为听众，当我在媒体库浏览长列表或歌单列表中长按或点击曲目菜单中的「详细信息」时，我希望从屏幕底部升起统一的 Material 3 Expressive 沉浸式曲目详情抽屉，展示发烧级音频参数（格式、采样率、位深、比特率、Hi-Res 徽章）与规范的存储卷标路径。
6. 作为听众，当我在 Android 14+ 手机上点击关闭曲目详情抽屉或触发预测性返回手势时，我希望抽屉平滑滑落收起，绝不触发底层的软键盘误弹窗或闪退鬼影（Zero IME Ghost）。
7. 作为听众，无论我是在媒体库目录浏览列表、跨源搜索结果还是自定义歌单中查看曲目详情，我都希望看到视觉统一、信息完整且对齐的规格呈现，不因来源不同而产生排版和字段缺失。
8. 作为听众，当全屏播放器伴随专辑封面动态换肤（Dynamic Artwork Palette）渲染暗调舞台光晕时，我希望全屏视图内的文字与按钮始终保持纯白高对比度，界面层次清晰分明，不因为外部嵌套重复主题而产生杂色偏色。
9. 作为开发者，我希望 `MainActivity` 是全局唯一的播控抽屉与全屏展开协调中枢，`MorphingPlayerContainer` 纯化为单向受控组件，没有多余的内部状态分叉。
10. 作为开发者，我希望全工程所有针对 `WebDavClient` 的网络与流式单元测试都面向单一权威的 `SharedFakeWebDavClient`，支持预设响应、延迟与故障注入，不再需要在各个测试类中重复维护私有手写桩。
11. 作为开发者，我希望 `PackagingSeamVerificationTest` 能自动拦截任何试图在 `MorphingPlayerContainer` 动画中逐帧修改 `Modifier.size()` 的违规代码，确保 120fps GPU Draw 阶段变换铁律被机械化防守。
12. 作为开发者，我希望物理删除无实质价值的单行浅包装文件（如 `TabAccessibilityGuard.kt`）与重复的业务 Dialog，保持代码库高内聚与零伪碎片化。

## Implementation Decisions

### 1. `MainActivity` 唯一协调中枢与 `MorphingPlayerContainer` 纯化
- **Scaffold Coordinator 职责锁定**：
  - `MainActivity` 独占维持 `isPlayerExpanded: Boolean` 与 `playerExpansionProgress: Float`（通过 `animateFloatAsState` 配合 M3 Expressive `CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)` 驱动）；
  - `MainActivity` 单向向底栏下沉传递 `translationY = 80.dp.toPx() * playerExpansionProgress`，向主内容传递缩放矩阵；
  - `MainActivity` 独占声明并渲染 `HoistedPlaybackQueueSheet`（`isPlaybackQueueSheetVisible: Boolean`）；
  - `MainActivity` 独占定义顶层 `BackHandler`：
    ```kotlin
    BackHandler(enabled = isPlaybackQueueSheetVisible || isPlayerExpanded) {
        when {
            isPlaybackQueueSheetVisible -> isPlaybackQueueSheetVisible = false
            isPlayerExpanded -> isPlayerExpanded = false
        }
    }
    ```
- **`MorphingPlayerContainer` 受控化纯化**：
  - 彻底拔除内部的 `internalProgress` 与闲置的 `animateFloatAsState`，只接收外部传入的 `progress: Float`；
  - 内部移除 `onOpenQueue` 时对自身抽屉的调用，所有队列抽屉打开意图纯向外回调 `onOpenQueue()`；
  - 内聚处理纵向阻尼拖拽与快速滑落（Downward Fling / Drag）手势，触发时调用 `onCollapse()`；
- **`FullPlayerView` 展示插槽化**：
  - 彻底删除 `FullPlayerView` 内部的 `showQueueSheet: Boolean` 与第二套 `HoistedPlaybackQueueSheet` 渲染代码；
  - 彻底删除其内部的 `BackHandler` 与重复的 `downwardSwipeModifier`；
  - 移除其内部对整个视图二次包裹的 `WebDavPlayerTheme`（顶层已包裹），仅在背景流体氛围层与前景暗调舞台消费 `artworkColors`。

### 2. 落实纯 GPU Draw 阶段 RenderNode 矩阵形变（零 CPU 测量排版）
- **固定基底终态布局（Fixed Base Geometry）**：
  - 三大常驻单体实体（专辑封面 Squircle、播放/暂停胶囊、队列按钮）以终态（展开状态下的全屏对应绝对尺寸，如封面为 `fullArtSizePx` 静态固定尺寸，播放键为 `FullPlayerDefaults.PlayButtonSize`）作为基础 Composable 尺寸；
- **Modifier.graphicsLayer 纯矩阵过渡**：
  - 彻底废除每帧调用的 `.size(with(density) { curCoverWidth.toDp() }).offset { ... }`；
  - 在 `Modifier.graphicsLayer` 内部使用纯 DisplayList 变换参数：
    ```kotlin
    val scale = lerp(miniWidth / fullWidth, 1f, progress)
    Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = lerp(miniCenterX - finalCenterX, 0f, progress)
        translationY = lerp(miniCenterY - finalCenterY, 0f, progress)
        shape = RoundedCornerShape(lerp(miniCornerRadius, fullCornerRadius, progress))
        clip = true
        shadowElevation = lerp(0.dp, 24.dp, progress).toPx()
    }
    ```
  - 伴随色彩流体插值（`lerpColor`）仅在绘制阶段生效，整个 0f..1f 形变周期内，Compose Layout 测量与重排次数严格为 0。

### 3. 收拢沉浸式 `TrackDetailBottomSheet` 并物理删除碎片
- **建立统一 `ui.common.TrackDetailBottomSheet`**：
  - 在 `ui.common` 包建立唯一的曲目详情抽屉，同时面向媒体库目录/搜索条目与歌单曲目条目；
  - 抽屉内容按 M3 Expressive 标准组织：
    1. 顶部拖拽把手（Drag Handle）与平滑收起按钮；
    2. 双行头部：封面缩略图（`CoverThumbnailImage`）、主标题、艺术家与专辑名称；
    3. 规格徽章区：发烧级规格动态药丸（`AudioQualityBadgePill`）、采样率、位深、比特率、Hi-Res 标志；
    4. 物理存储属性：格式化卷标物理路径（使用 `TrackPathFormatter` 解析内部存储或 SD 卡标签，如 `[内部存储] Music/Album/01.flac`）、文件大小（MB）、精准时长；
  - **强制落实焦点隔离与优雅收起**：
    - 配置 `properties = ModalBottomSheetProperties(isFocusable = false, shouldDismissOnBackPress = true)`，杜绝抢占 Window Focus 诱发 Android 14 软键盘闪退鬼影；
    - 点击遮罩或关闭按钮内聚执行 `coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }`；
- **清理重复文件与死代码**：
  - 物理删除 `app/src/main/java/com/webdav/player/ui/playlist/components/PlaylistTrackDetailDialog.kt`；
  - 物理删除 `app/src/test/java/com/webdav/player/ui/playlist/PlaylistTrackDetailDialogTest.kt`（若存在）；
  - 从 `AudioTrackItemRow.kt` 中删除内联的 `FileInfoDialog`；
  - 物理删除 `app/src/main/java/com/webdav/player/ui/navigation/TabAccessibilityGuard.kt`（将单行修饰符内联至 `MainActivity.kt` 底部导航栏调用处）。

### 4. 补齐 `WebDavClient` 端口抽象与收敛统一架构测试桩
- **扩展 `WebDavClient` 端口抽象**：
  - 在 `com.webdav.player.data.remote.WebDavClient` 接口中显式声明：
    ```kotlin
    interface WebDavClient {
        // 既有方法保持
        suspend fun listDirectory(server: WebDavServer, path: String): ListDirectoryResult
        suspend fun testConnection(server: WebDavServer): ConnectionResult
        suspend fun downloadRange(server: WebDavServer, remotePath: String, startByte: Long, length: Long): ByteArray?
        suspend fun downloadFile(server: WebDavServer, remotePath: String): ByteArray?
        
        // 补齐缺失端口能力
        fun invalidateServer(serverId: Long)
        fun getStreamingClientForServer(server: WebDavServer): OkHttpClient
    }
    ```
  - `OkHttpWebDavClient` 实现上述方法；`WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 构造参数类型从 `OkHttpWebDavClient` 纯化为 `WebDavClient`，彻底消除向下类型转换；
- **建立共享架构测试桩 `SharedFakeWebDavClient`**：
  - 在 `app/src/test/java/com/webdav/player/test/SharedFakeWebDavClient.kt` 实现完备的测试适配器，支持按路径预设 `ListDirectoryResult`、文件数据、Range 切片，以及灵活的故障注入（`IOException`、HTTP 404/401、超时）；
  - 批量重构并清理散落在 `WebDavClientTest`、`TrackMetadataResolverTest`、`TrackMetadataRepositoryTest`、`MediaLibraryRepositoryTest`、`PlaylistRepositoryTest` 等 7 个测试文件内的私有内联 Fake 实现，统一切换为 `SharedFakeWebDavClient`。

### 5. 架构边界自动化防守
- 在 `PackagingSeamVerificationTest` 中追加静态断言：
  - 校验 `MorphingPlayerContainer.kt` 源码中不得包含逐帧调用 `.size(` 的动态计算；
  - 校验 `TabAccessibilityGuard`、`PlaylistTrackDetailDialog` 与 `FileInfoDialog` 不得作为独立符号存在；
  - 校验 `WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 不得强耦合 `OkHttpWebDavClient` 具体类。

## Testing Decisions

### 测试原则
- 坚守黑盒测试表面纪律（`CODING_STANDARDS.md §1`），仅面向公共 Composable 行为、手势回调、返回键消费与接口契约断言；
- 严禁通过反射窥探内部私有变量，严禁编写依赖源码行数脆弱断言；利用确定性的 Compose UI Test 与无 Mock 纯桩保障测试秒级完成。

### 拟验证的核心接缝与覆盖矩阵
1. **播放器受控形变与抽屉宿主接缝（`PlayerSheetExpansionTest`、`MorphingPlayerContainerTest`）**：
   - 验证 `expansionProgress` 从 0f 到 1f 驱动下，Mini-Player 透明度单调淡出、Full-Player 透明度单调淡入；
   - 验证常驻三大实体的几何 RenderNode 参数平滑插值，且整个过渡期不会触发额外的 Measure/Layout 异常重绘；
   - 验证在全屏展开且队列抽屉显示时，触发一次返回事件优先关闭抽屉，播放器仍保持全屏展开；再次触发返回事件才关闭全屏播放器折叠为迷你胶囊。
2. **统一曲目详情抽屉接缝（`TrackDetailBottomSheetTest`）**：
   - 验证传入本地 SAF 曲目与远程 WebDAV 曲目时，发烧规格徽章、采样率、位深、时长以及经 `TrackPathFormatter` 格式化后的卷标路径正确呈现；
   - 验证抽屉属性配置了 `isFocusable = false` 与 `shouldDismissOnBackPress = true`；
   - 验证点击关闭按钮后触发平滑退出回调。
3. **`WebDavClient` 端口与测试桩一致性接缝（`SharedFakeWebDavClientTest`、`WebDavMediaSourceBuilderTest`）**：
   - 验证 `SharedFakeWebDavClient` 能完整模拟目录树、404 故障注入、Range 切片下载与流式客户端派生；
   - 验证已迁移的仓储测试与媒体源构建测试在切换到统一桩后保持 100% 绿色通过，且代码完全解除了对 `OkHttpWebDavClient` 的直接依赖。
4. **架构不变量静态门禁（`PackagingSeamVerificationTest`）**：
   - 自动化验证废弃类物理删除、Draw 阶段变换红线以及端口解耦不变量。

### 参考先例
- `PlayerSheetExpansionTest` & `MiniPlayerPresentationTest`（播放器形态过渡与展开交互测试）；
- `PlaybackQueueBottomSheetTest`（焦点隔离与优雅收起抽屉测试）；
- `WebDavMediaSourceBuilderTest`（流式媒体源与客户端端口装配测试）；
- `PackagingSeamVerificationTest`（包接缝与架构红线静态检验）。

## Out of Scope

- 修改音频底层解码器（FFmpeg / Media3）或音频数据流传输管道。
- 更改全屏播放器暗调流光算法（`ArtworkColorExtractor`）或动态调色板。
- 更改媒体库目录排序策略或数据库表结构定义。

## Further Notes

- 严格遵循 `CODING_STANDARDS.md` 第 1 节（职责内聚与严禁伪碎片化）、第 6 节（绘制阶段变换原则、单一真实实体与插槽架构、全天候视觉舞台与抽屉焦点隔离规范）。
- 关联架构文档：`GLOSSARY.md`（`Controlled Morphing Container`、`Track Detail Sheet`、`Docked Mini-Player`、`Full Player View`）与 `docs/adr/0043-controlled-morphing-player-gpu-motion-and-track-detail-sheet.md`。
