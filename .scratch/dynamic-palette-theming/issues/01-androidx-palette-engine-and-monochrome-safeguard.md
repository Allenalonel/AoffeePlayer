# 01: AndroidX Palette 依赖注入与 M3E 色彩推导引擎重构

**What to build:** 为应用接入 Google 官方 `androidx.palette:palette-ktx` 标准多媒体色彩提取库，彻底废除粗暴的 12-bin 离散均值伪色量化；重构色彩提取引擎核心契约，提取 Vibrant、Dominant 与 Muted 多采样 Swatch，并接入 Material 3 Expressive HCT Tonal Palette 规范推导浅色（Tone 97~98）、深色（Tone 6~8）全套色阶。建立 Zero-Tint Monochrome 灰阶防线，在遇到低彩度或黑白单色封面（如《AS I AM》）时自动冻结色彩注入，锁定纯正中性黑白灰阶，物理杜绝偏酒红色；彻底拔除硬编码品牌暗紫，无封面时纯中性回退。通过单元测试验证彩色提取、无彩色零偏色及 WCAG AAA 对比度。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 官方依赖注入：工程成功引入 `androidx.palette:palette-ktx:1.0.0`，无构建冲突。
- [ ] 12 桶伪色算法清零：拔除粗暴的 12 色相桶取模与 50% 强制饱和度注入逻辑。
- [ ] 官方 Swatch 提取：从封面 Bitmap 提取 Vibrant 活力主色、Dominant 基调色与 Muted 柔和色。
- [ ] 纯正 M3E HCT 色阶推导：遵循感知色彩模型，浅色模式推导 Tone 97~98 柔和底色与墨炭色高对比度文字（WCAG AAA >= 8.5:1），深色模式推导 Tone 6~8 沉浸底色与 Tone 80 醒目主键。
- [ ] Zero-Tint Monochrome 防线：当 Chroma < 8 或 Saturation < 0.12 时，输出无色相中性灰阶（深色石墨黑 / 浅色珍珠雪白），黑白封面绝对不偏红。
- [ ] 零预设回退：移除 `#141022` 与 `#5544B3` 硬编码暗紫，无封面时返回纯正中性 M3 灰阶。
- [ ] 针对性测试通过：单元测试断言彩色提取、黑白灰阶零偏色及对比度验证 100% 通过。
