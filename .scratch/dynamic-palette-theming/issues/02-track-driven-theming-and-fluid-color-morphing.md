# 02: 全域主题音轨驱动与 450ms 流体色彩形变

**What to build:** 将全域动态换肤系统改造为纯粹由当前在播音轨真实驱动，彻底废止全局写死的固定品牌暗紫。扩展平滑缓动渐变动画管线，将主色（Primary）、主容器（PrimaryContainer）、大背景（Background）与表面容器（SurfaceContainer）全部统一纳入 450ms 物理渐变动画，实现切歌时主界面大背景、卡片外框、底栏指示舱等整机核心元素的流体自然换肤，消除生硬顿挫与色彩跳闪。

**Blocked by:** 01 (AndroidX Palette 依赖注入与 M3E 色彩推导引擎重构)

**Status:** ready-for-review

- [x] 音轨驱动全域换肤：全域主题 ColorScheme 100% 随当前在播音轨实时自适应，启动与运行期均以当前在播曲目为唯一色源。
- [x] 动画管线全域补全：`animatedColorScheme` 扩展覆盖 Primary、PrimaryContainer、Background、SurfaceContainer 等关键色调层级。
- [x] 450ms 流体物理缓动：切歌触发的主题切换在 450ms 内丝滑渐变过度，无突兀跳色。
- [x] 全域主界面一致性：长列表卡片、顶栏折叠雾化层及底栏药丸指示舱在切歌时和谐演变。
- [x] 主题测试通过：相关主题与动画单元测试全部通过。
