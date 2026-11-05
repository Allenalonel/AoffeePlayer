# 01: 剪枝无用形变数学表面与深化 GPU 绘制阶段播放器容器

**What to build:** 清理 `MorphingPlayerMath` 中废弃未调用的 7 个几何与透明度函数（`artworkSize`、`artworkCornerRadius`、`artworkRotation`、`playButtonSize`、`playButtonCornerRadius`、`queueButtonSize`、`secondaryControlsAlpha`、`miniControlsAlpha`），内化次级控制淡入与胶囊淡出的门槛常量；确保 `MorphingPlayerContainer` 的展开收缩过程遵循 Draw Phase 渲染原则，移除每帧对 `Modifier.height()` 与 `Modifier.padding()` 的 CPU 重新测量计算；同步重构 `PlayerSheetExpansionTest`，移除对已删除数学函数的测试，改为针对真实容器状态流转与手势阈值进行断言。

**Blocked by:** None (can start immediately)

**Status: claimed**

## Acceptance criteria

- [ ] 从 `MorphingPlayerMath` 接口中安全移除 `artworkSize`、`artworkCornerRadius`、`artworkRotation`、`playButtonSize`、`playButtonCornerRadius`、`queueButtonSize`、`secondaryControlsAlpha`、`miniControlsAlpha`，仅保留实际生产使用的几何插值函数（`containerHeight`、`containerHorizontalMargin`、`containerBottomMargin`、`containerCornerRadius`）。
- [ ] 将透明度淡入淡出门限（`SecondaryControlsFadeStartProgress = 0.35f`、`MiniControlsFadeEndProgress = 0.25f`）内化在 `MorphingPlayerDefaults` 中，消除多余的公开数学接缝。
- [ ] 确保 `MorphingPlayerContainer` 容器在形变过程中严格保障 GPU 120Hz 硬件渲染，杜绝多余的 CPU 布局测算开销，且 100% 保持既有视觉效果与圆角形变几何一致。
- [ ] 更新 `PlayerSheetExpansionTest`，清理针对已删除函数的断言，确保测试直接验证真实的播放器展开、收起手势门禁与可观测几何插值。
- [ ] 运行 `./gradlew testDebugUnitTest --tests "*PlayerSheetExpansionTest*"` 且测试全部通过。
