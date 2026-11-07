# Spec: AndroidX Palette 官方多色提取、M3E 纯正色调阶梯与音轨驱动零预设全域换肤

Status: `completed`

## Problem Statement

用户在播放音乐时发现，当前的整机 UI 动态变色逻辑存在严重不真实与离散化缺陷：“并不是实时抓取封面颜色而跟随，而是好像有几个固定的预设颜色一样”。

通过真机运行时实证（ADB 截屏与内存日志），该问题是由自研色彩提取算法的三项底层数学与架构缺陷导致的：
1. **12 色相桶粗暴离散量化**：算法将 360° 色相除以 30 划入 12 个固定区间并在桶内强行平均，导致丰富微妙的真实封面色彩被“拍平”为仅有的几种离散颜色；
2. **中性黑白无彩色偏红 Bug**：在 Android 原生 HSV 模型中，当 R ≈ G ≈ B（饱和度为 0 的黑白老照片或金属灰质感封面）时，系统默认返回 Hue=0.0f（归入红色桶），且算法强行指定了 50% 的最低饱和度下限，直接把纯黑白单色封面（如实测曲目《AS I AM》）错误渲染为高饱和深酒红（Wine Red）；
3. **主观硬编码品牌预设违背直觉**：原代码在无封面或空闲时强行硬编码暗紫曜石黑（`#141022` / 电光紫 `#5544B3`）作为回退色，用户明确期望应用不设定任何主观默认品牌色，界面色彩必须 100% 由当前加载的歌曲真实驱动。

## Solution

废除自研的 12 桶伪色算法与硬编码暗紫回退，全面接入 Google Android 官方多媒体色彩提取标准库与 Material 3 Expressive (M3E) 感知色彩工程：

1. **官方标准依赖集成**：接入官方 `androidx.palette:palette-ktx`，通过人体感知模型提取 `vibrantSwatch`（活力主色）、`dominantSwatch`（核心基调色）与 `mutedSwatch`（柔和辅助色），保证 360° 连续色相高保真；
2. **纯正 M3E HCT Tonal Palette 色阶映射**：严格遵循 Google HCT 模型推导深浅双模整机色阶，浅色模式呈现 Tone 97~98 通透微光底色，文字锁定墨炭色（`#1E293B`），全天候保障 WCAG AAA 8.5:1 超清对比度；
3. **音轨驱动零预设原则**：彻底移除硬编码暗紫，冷启动与运行期 100% 由当前在播曲目真实驱动换肤；极端无封面场景自动回退为纯正中性 M3 灰阶（Chroma = 0）；
4. **中性无彩色零偏色防线**：当彩度 Chroma < 8 或 Saturation < 0.12 时，强制冻结彩色注入，锁定深邃石墨黑 / 珍珠雪白纯中性灰阶，物理根除黑白封面偏红问题；
5. **全屏多色融合氛围光晕与 450ms 流体色彩形变**：全屏播放器背景基于多采样 Swatch 构筑柔和径向网格氛围光晕，并将 Primary/Container 纳入 450ms 缓动过渡，切歌换肤平滑如呼吸。

## User Stories

1. As a music listener, I want the app UI to dynamically reflect the genuine, nuanced color of my current album artwork, so that the player feels bespoke and tailored to each album.
2. As a listener playing a monochrome or black-and-white album (e.g. *AS I AM*), I want the UI to display in clean, sophisticated neutral monochrome (slate dark or pearl white), so that the interface never artificially turns into wine red or any fake hue.
3. As a listener browsing albums with subtle pastel or cool cyan tones (e.g. *Astronaut In The Ocean*), I want the UI to faithfully match that exact sky/teal tone instead of snapping to a coarse, quantized primary blue bucket.
4. As a listener playing multi-colored rich album art (e.g. *Bad Mother F*cker*), I want the full player background to blend multiple authentic colors into a smooth ambient glow, so that the album art looks luminous and high-end.
5. As a user operating in Light Mode during daytime, I want the app background to be delicately tinted (Tone 97~98) without losing contrast, so that all secondary track details and pills remain razor-sharp and legible (WCAG AAA >= 8.5:1).
6. As a user operating in Dark Mode at night, I want deep immersive backgrounds (Tone 6~8) paired with gentle luminous accents, so that the screen is comfortable to look at in the dark.
7. As a listener switching between consecutive tracks, I want the UI theme change to morph smoothly over 450ms, so that color shifts are fluid and never cause abrupt jarring flashes.
8. As a user launching the app, I want the application to resume the last played track's authentic artwork colors immediately, so that no unnatural hardcoded purple screen flashes before playback starts.
9. As a listener playing a raw audio file that physically lacks any embedded or folder artwork, I want the app to stay in pure neutral Material 3 monochrome without forcing an artificial brand tint, so that missing art is handled with restraint.
10. As a user navigating between directory browser, playlists, server settings, and full player, I want all core surfaces and cards to harmoniously share the current album's color palette, so that the entire app feels unified.
11. As an audio enthusiast monitoring lossless format specifications, I want format pills (FLAC, MP3, 24-bit Hi-Res) and queue equalizer indicators to remain legible and harmonized with the active color palette.
12. As a user on mid-range or battery-constrained Android devices, I want the dynamic theming and ambient glow to compute once asynchronously in the background and render via lightweight shaders, so that scrolling and player morphing remain at a rock-solid 120 FPS.

## Implementation Decisions

### 1. Build and Dependency Invariants
- Add official `androidx.palette:palette-ktx:1.0.0` to the application dependencies.
- Enforce the 7.5 MB APK size budget and 4.25 MB classes.dex budget (verifiable via `verifyReleasePackaging`).

### 2. Color Extraction Seam and Contract
- The color extraction engine exposes a unified interface:
  - Input: Optional thumbnail file path or decoded Bitmap.
  - Output: `ArtworkColors` domain entity containing dominant color, background top/bottom colors, accent color, fallback flag, and both dark and light `ColorScheme` instances.
- Replaces the 12-bin histogram algorithm with `androidx.palette` extraction:
  - Computes `Palette.from(bitmap).maximumColorCount(16).generate()` on the IO dispatcher.
  - Extracts `vibrantSwatch` (first priority for accent/Primary), `dominantSwatch` (base tone), and `mutedSwatch` (ambient secondary tone).
- Implements the Zero-Tint Monochrome Safeguard:
  - If the extracted swatch or sample pixels exhibit saturation < 0.12 or perceptual Chroma < 8:
    - Mark the extraction as neutral monochrome (`isMonochrome = true`).
    - Dark theme: Background Tone 8, Surface Tone 12, Primary Tone 85 (pearl white), OnPrimary Tone 10 (slate black).
    - Light theme: Background Tone 98 (pure white), Surface Tone 94 (clean gray), Primary Tone 20 (slate charcoal), OnPrimary Tone 100.
    - Zero hue bias is introduced.

### 3. Material 3 Expressive Tonal Mapping
- For chromatic artwork (`isMonochrome = false`):
  - Convert selected seed color to HCT / HSL.
  - **Light Theme**:
    - `background` and `surface`: Soft ambient pastel, Tone 97~98, Chroma capped to <= 8.
    - `surfaceContainer`: Tone 93~95.
    - `primary`: Tone 40 high-contrast vibrant accent.
    - `onSurface` and `onSurfaceVariant`: Fixed high-contrast slate charcoal (`#1E293B` / `#3A3546`), guaranteeing contrast ratio >= 8.5:1 against all container tones.
  - **Dark Theme**:
    - `background` and `surface`: Deep dark immersive obsidian, Tone 6~8, Chroma <= 12.
    - `surfaceContainer`: Tone 12~14.
    - `primary`: Tone 80 luminous accent.
    - `onPrimary`: Tone 20 dark slate, contrast ratio >= 4.5:1.

### 4. Zero-Preset Track-Driven Architecture
- Remove all hardcoded default brand purple colors (`#141022` and `#5544B3`) from the fallback paths.
- Default fallback colors when no track is present: Pure neutral Material 3 monochrome (`#121318` in dark, `#FFFFFF` in light, Chroma = 0).
- Session restoration (`SessionPersistenceCoordinator`) ensures that on cold launch, the previously active track is loaded and its artwork colors are emitted immediately.

### 5. Animated Palette Morphing Pipeline
- Expand `animatedColorScheme` to smoothly interpolate:
  - `background`, `surface`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerLow`
  - `primary`, `primaryContainer`, `onPrimary`, `onPrimaryContainer`
- Use a unified 450ms tween duration (`CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)`) so that background, card borders, active indicators, and play buttons transform in unison.

### 6. Full Player Ambient Glow
- FullPlayer background transitions from single-color linear tint to a multi-stop ambient radial glow:
  - Top center (behind 28dp squircle artwork card): `vibrantSwatch` / `dominantSwatch` composited with 28%~35% alpha.
  - Bottom: seamless melt into `background` / `surfaceContainerLowest`.
  - Zero expensive real-time full-screen Gaussian blur layers, preserving battery life and backward compatibility on Android 10/11.

## Testing Decisions

### 1. Seam-Level Behavior-Only Verification
- Tests must assert observable external color scheme properties and WCAG contrast invariants, never internal sampling iteration loops.
- Test Cases to cover:
  - **Real Chromatic Artwork**: Given a vivid cyan/blue bitmap, verifies that extracted primary is vibrant blue/cyan and derived dark/light color schemes adopt the corresponding hue family.
  - **Zero-Tint Monochrome Safeguard**: Given a pure grayscale bitmap (e.g. synthetic test bitmap where R == G == B, simulating *AS I AM*), asserts that `isFallback` is false, and the resulting primary and background have zero saturation/chroma (no red/wine-red hue).
  - **Extreme Fallback**: Given a null or zero-sized bitmap, asserts fallback colors return pure neutral slate/pearl tones without hardcoded purple tint.
  - **WCAG Contrast Ratios**: In both light and dark generated color schemes, assert:
    - `contrastRatio(onPrimary, primary) >= 4.5`
    - `contrastRatio(onSurfaceVariant, background) >= 8.5` (AAA standard)
    - `contrastRatio(onPrimaryContainer, primaryContainer) >= 4.5`
  - **450ms Animation Pipeline**: Verify that key primary and container tokens participate in animated transitions.

### 2. Prior Art
- `app/src/test/java/com/webdav/player/ui/player/ArtworkColorExtractorTest.kt`
- `app/src/test/java/com/webdav/player/ui/theme/WebDavPlayerThemeTest.kt`
- `app/src/test/java/com/webdav/player/EndToEndMusicPlayerPipelineTest.kt`

## Out of Scope

- Modifying player audio rendering, ExoPlayer pipelines, or WebDAV streaming credentials.
- Changing the layout geometry of MiniPlayer, TopAppBar, or BottomNavigationBar (established in ADR-0028 and ADR-0031).
- Adding user-configurable manual static theme pickers (the application explicitly follows automated track-driven theming).
- iOS-style real-time GPU RenderEffect full-screen Gaussian blur layers (evaluated and discarded in favor of M3E ambient glow for performance and light-mode contrast guarantees).

## Further Notes

- Architecture Decision Record: `docs/adr/0032-androidx-palette-and-m3-expressive-dynamic-theming.md`.
- Domain Glossary updates: `GLOSSARY.md` (`Global Dynamic Artwork Palette`, `Track-Driven Zero-Preset Principle`, `Zero-Tint Monochrome Safeguard`, `Immersive Stage Player`).
- Interactive Visual Prototype: `.tmp/player_color_prototype.html` (validated with 5 real library covers on ADB test device), `.tmp/player_color_prototype_v3.html` (validated multi-variant stage comparison).

## Decision Addendum: Immersive Stage Glow Refinement (真机实证决策附录)

在真机 ADB 实机联调中，针对浅色模式暴露的“四周白斑截断”与“白底白字反差失效”问题，经交互原型与视觉比对确认，对全屏播放器实施工业级分层演进：
1. **分层换肤体系**：主界面（媒体库/歌单/服务器）保持深浅双模自适应与曲目微光联动；全屏播放器（`FullPlayerView`）升格为全天候沉浸舞台（`Immersive Stage Player`）。
2. **全天候 100% 动态暗调流光**：全屏大背景无论白天黑夜，统一根据在播音轨 100% 动态提取生成深邃暗调流光（Tone 10~14 暗调彩色底 + 封面后置 Vibrant 活力光晕 + 底部 Muted 上扬流光，黑白封面落入纯正石墨灰阶）。
3. **全天候高对比度纯白字体**：全屏播放器内部字体与播控图标锁定为高亮纯白（`#FFFFFF`），对比度达到 12.8:1，彻底消除白天浅底发虚与看不清问题。
