# 04: 常驻三实体纯 GPU Draw 阶段 RenderNode 矩阵形变与静态架构门禁 (GPU Draw Phase Motion & Seam Gate)

**What to build:**
`MorphingPlayerContainer` 中的三大常驻单体实体（专辑封面 Squircle、播放/暂停胶囊、队列按钮）以全屏展开状态为基准固定占位，彻底废除每帧调用 `.size(toDp()).offset { ... }` 带来的 CPU 测量重排；在 `Modifier.graphicsLayer { ... }` 内部纯通过 DisplayList 矩阵运算（平移、等比缩放、动态圆角 `shape`、动态阴影 `shadowElevation`）执行 120fps 流体过渡，达成形变全周期 0 次 Measure/Layout 重排；在 `PackagingSeamVerificationTest` 中追加静态架构断言（禁止逐帧动态 `size()`、校验废弃 Dialog/单行浅文件物理删除、校验客户端端口解耦），并执行全量单测门禁。

**Blocked by:** 01 (WebDavClient 端口抽象补全与统一 FakeWebDavClient 测试桩), 02 (统一沉浸式曲目详情抽屉 TrackDetailBottomSheet 与清理浅碎片), 03 (宿主控制权收拢与受控单体流体播放器).

**Status:** completed

## Acceptance criteria

- [x] `MorphingPlayerContainer` 中的专辑封面、播放/暂停胶囊与队列按钮以终态绝对尺寸固定测量，彻底废除每帧调用的 `.size(...)` 与动态 `.offset { ... }` 计算。
- [x] 在 `Modifier.graphicsLayer { ... }` 内部，完全通过纯 GPU RenderNode 矩阵变换（`scaleX/Y`、`translationX/Y`、`shape = RoundedCornerShape(...)` 与 `shadowElevation`）驱动形变，形变全周期 0 次 Compose Measure/Layout 阶段重排。
- [x] 在 `PackagingSeamVerificationTest` 中追加自动化静态红线守卫：
  - 校验 `MorphingPlayerContainer.kt` 中不存在逐帧调用 `.size(` 的动态计算；
  - 校验 `TabAccessibilityGuard`、`PlaylistTrackDetailDialog` 与 `FileInfoDialog` 彻底物理删除；
  - 校验 `WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 严禁直接强耦合 `OkHttpWebDavClient` 具体实现类。
- [x] 针对性动效与包接缝测试通过：`./gradlew testDebugUnitTest --tests "*PackagingSeamVerificationTest*" --tests "*MotionTransitionsTest*" --tests "*PlayerSheetExpansionTest*"`。
- [x] 全量工程单测门禁 100% 绿色通过：`./gradlew testDebugUnitTest`。
