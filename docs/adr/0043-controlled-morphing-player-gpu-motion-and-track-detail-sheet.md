# 0043: 受控流体播放器、GPU 绘制阶段变换与统一曲目详情抽屉 (Controlled Morphing Player, GPU Draw Phase Motion, and Unified Track Detail Bottom Sheet)

## Status

Accepted (Amends ADR-0007, ADR-0028, and ADR-0031)

> 参照主流成熟 Android 与跨平台播放器（Apple Music 的顶层 Coordinator 统一位移下沉、Spotify 的纯全屏插槽与单一队列抽屉、Poweramp/Symfonium 的 M3 Expressive 发烧级曲目详情抽屉）确立组合包 C 终局架构共识。

## Context

在完成播控会话（ADR-0041）与跨源元数据管道（ADR-0042）的深化重构后，表现层与客户端测试基础设施暴露出以下四大结构性摩擦与运行缺陷：

1. **宿主控制权三层撕裂与双重状态源**：
   - `MainActivity` 维护 `playerExpansionProgress`（`animateFloatAsState`）驱动底栏下沉与主内容缩放；
   - `MorphingPlayerContainer` 内部却同时分配了一个闲置的 `internalProgress`（`animateFloatAsState`），产生双重动画状态机；
   - `MainActivity` 与 `FullPlayerView` **各自**挂载了一套 `HoistedPlaybackQueueSheet`；
   - `MorphingPlayerContainer` 与 `FullPlayerView` **各自**实现了一套下拉折叠手势；
   - `MainActivity`、`MorphingPlayerContainer` 与 `FullPlayerView` 存在三层重复的返回键（`BackHandler`）拦截，导致返回优先级与状态同步脆弱。
2. **违背 GPU 绘制阶段变换红线（帧内 CPU 重排重测）**：
   - `MorphingPlayerContainer` 中的三大常驻单体实体（专辑封面 Squircle、播放/暂停胶囊、队列按钮）目前通过 `Modifier.offset { ... }.size(...)` 动态调整几何属性；
   - 逐帧调用 `.size(with(density) { curWidth.toDp() })` 在 60fps/120fps 形变过程中每一帧都触发 Compose Recomposition、Measure 与 Layout 阶段重新计算，明确违背了 `CODING_STANDARDS.md §6` 的 GPU Draw Phase 矩阵变换红线，在高刷设备上引发 CPU 峰值与微掉帧。
3. **曲目信息弹窗重复实现与焦点隔离缺失**：
   - 媒体库 `AudioTrackItemRow.FileInfoDialog` 与歌单 `PlaylistTrackDetailDialog` 各自实现了一遍音频规格、采样率、码率、文件路径的弹窗排版，样式与格式化细节割裂，且以传统居中卡片呈现；
   - 未对齐 Android 14+ 预测性返回与 `ModalBottomSheet` 焦点隔离不变量；
   - 另有 1 个仅有 1 行代码的单调用者浅包装文件 `TabAccessibilityGuard.kt` 悬挂在导航包中。
4. **`WebDavClient` 端口缺口与 7 份分散内联测试桩**：
   - `WebDavClient` 接口缺失 `invalidateServer` 与 `getStreamingClientForServer`，导致生产代码中 `WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 绕过接口强转或直连具体类 `OkHttpWebDavClient`；
   - 全工程散落 7 份手写的 `FakeWebDavClient` 内部类，方法签名未对齐，测试基础设施严重腐化。

## Decision

### 1. 纯化 `MorphingPlayerContainer` 为受控单体组件，消灭宿主双重状态
- **`MainActivity` 作为全局唯一 Scaffold Coordinator**：
  - 独占维持 `isPlayerExpanded` 状态与 `playerExpansionProgress`，单向驱动底栏 `translationY` 与主内容矩阵缩放；
  - 独占宿主全局唯一的 `HoistedPlaybackQueueSheet`（`PlaybackQueueBottomSheet`）；
  - 确立统一系统返回键（`BackHandler`）严格优先级链路：
    `QueueSheet (若展开 -> 关闭抽屉) -> FullPlayer (若全屏 -> 折叠至 Mini) -> 顶层导航退出`。
- **`MorphingPlayerContainer` 纯化为受控组件（Controlled Component）**：
  - 彻底拔除内部冗余的 `internalProgress`，纯粹以外部传入的 `expansionProgress` 单向驱动；
  - 统一承担纵向阻尼拖拽与快速滑落（Downward Fling / Drag）手势；
  - `FullPlayerView` 降级为纯展示插槽，彻底移除其内部的 `showQueueSheet` 状态与第二份 `HoistedPlaybackQueueSheet`，点击队列按钮仅向外派发 `onOpenQueue` 回调；彻底移除其内部重复的 `downwardSwipeModifier` 与重复包裹的内层主题。

### 2. 落实纯 GPU Draw 阶段 RenderNode 矩阵形变（零 CPU 测量排版）
- 严格遵循 `CODING_STANDARDS.md §6` GPU 绘制阶段变换原则：
  - 三大常驻单体实体（专辑封面 Squircle、播放/暂停胶囊、队列按钮）以终态（全屏展开状态）的物理边界设定固定基础尺寸（Base Bounds）；
  - 在 `Modifier.graphicsLayer { ... }` 块内部，完全通过 GPU DisplayList 矩阵运算执行变换：
    - 平移：`translationX = lerp(miniX - finalX, 0f, p)`，`translationY = lerp(miniY - finalY, 0f, p)`；
    - 等比缩放：`scaleX = lerp(miniWidth / finalWidth, 1f, p)`，`scaleY = scaleX`；
    - 动态圆角剪裁：`shape = RoundedCornerShape(lerp(miniRadius, finalRadius, p))`，`clip = true`；
    - 动态阴影抬升：`shadowElevation = lerp(miniElevation, finalElevation, p).toPx()`；
  - 在 60fps/120fps 形变全过程中，达成 **0 次 Compose Measure/Layout 阶段重排**，实现真机极限丝滑。

### 3. 收拢沉浸式 `TrackDetailBottomSheet` 并物理删除冗余浅文件
- **统一在 `ui.common` 建立 `TrackDetailBottomSheet`**：
  - 废除媒体库 `FileInfoDialog` 与歌单 `PlaylistTrackDetailDialog`，统一使用 Material 3 Expressive 风格的单体 BottomSheet；
  - 整合呈现封面缩略图、标题、艺术家、专辑、发烧级音频规格（采样率、位深、比特率、Hi-Res 动态徽章）、存储卷标规范路径（`TrackPathFormatter`）与物理大小/时长；
  - **强制落实焦点隔离与优雅收起不变量**：
    配置 `properties = ModalBottomSheetProperties(isFocusable = false, shouldDismissOnBackPress = true)`，切断 Window Focus 争夺，杜绝 IME 弹窗闪退鬼影；关闭时执行 `sheetState.hide()` 位移动画后再卸载组件；
- **物理删除单行包装 `TabAccessibilityGuard.kt`**：内联至 `MainActivity` 导航栏，消除单调用者文件碎片。

### 4. 补齐 `WebDavClient` 端口抽象与收敛统一架构测试桩
- **扩展 `WebDavClient` 端口方法**：
  - 增加 `invalidateServer(serverId: Long)` 与 `getStreamingClientForServer(server: WebDavServer): OkHttpClient`；
  - `WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 统一面向抽象接口，彻底清除运行时具体类型强转；
- **建立共享架构测试桩 `SharedFakeWebDavClient`**：
  - 统一支持预设目录、按路径注入异常（404/401/超时）、延迟模拟及 Range 切片流模拟；
  - 彻底清除 7 个测试文件中散落手写的内联 `FakeWebDavClient` 副本，统一测试表面。

## Consequences

- **High Locality & Single Ownership**：播放器全屏状态、底栏避让、返回键与队列抽屉统归 `MainActivity` 协调；形变物理呈现统归 `MorphingPlayerContainer` 控制；双份手势、双份抽屉与双重动画状态机彻底绝迹。
- **60/120fps Smooth Performance**：形变动画迁移至 GPU Draw 阶段，杜绝帧内 CPU 重排重测，彻底对齐架构法典红线。
- **Consistent UI Experience & Zero IME Ghost**：曲目详情交互升级为 M3E 风格统一抽屉，强制焦点隔离根治 Android 14+ 软键盘闪退；删除重复 Dialog 与僵尸代码。
- **Clean Test Seam**：消除测试端 7 份重复 Fake，`WebDavClient` 真正达成 Ports & Adapters 架构闭环。
- **Amends ADR-0007, ADR-0028, and ADR-0031**：修正了 ADR-0031 在具体实现中遗留的逐帧 `size()` 布局开销，收敛了 ADR-0028 遗留的分散 Dialog，奠定了最终表现层纯净架构。
