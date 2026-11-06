# 03: 本地音频极速直放管道、双源歌词与氛围流光联动

**What to build:**
从用户视角出发，在媒体库中点击本地单曲或 CUE 虚拟分轨时，播放器立即起播，无需经历任何网络缓冲等待，实现毫秒级零延迟直接出声。底层播放引擎（ExoPlayer）自动识别本地曲目并走 `DefaultDataSource` 零拷贝读取本地 `content://` URI。切至本地曲目时，自动释放或旁路网络硬件锁（WifiLock）与弱网超时看门狗，避免无谓电量消耗。底部的 MiniPlayer 药丸胶囊、进度流体填充、双向滑动手势、锁屏控制、通知栏播控与全屏播放器（Full Player Sheet）均对本地歌曲提供完整支持。同时，本地音轨封面自动提取主导色（Dominant Color）驱动全局 Material 3 Expressive 动态氛围取色，并无缝渲染伴生 `.lrc` 文件与内嵌逐行同步滚动双语歌词。

**Blocked by:** 02: 媒体库本地多挂载点遍历、SWR 缓存与伴生 CUE/LRC 识别

**Status:** resolved

- [x] 点击本地音频曲目（普通音轨或 CUE 虚拟分轨）时，通过 `DefaultDataSource.Factory` 极速装载并起播，具备零网络延迟与顺畅 Seek 体验。
- [x] 播放本地歌曲时，播放引擎与会话层自动跳过网络专有的看门狗定时器与 WifiLock 加锁，保持 CPU 唤醒锁与前台服务生命周期稳定。
- [x] 悬浮 MiniPlayer 胶囊与 FullPlayer 视图对本地曲目正常展示曲目名、艺术家、专辑封面、播放进度与音质标签（FLAC/WAV/MP3 等）。
- [x] 从本地音频文件中正确解析内嵌歌词或同目录 `.lrc` 伴生歌词，全屏歌词页支持双语对齐与毫秒级点击跳转 Seek。
- [x] 全域动态取色系统（`ArtworkColorExtractor`）成功提取本地专辑封面主色，动态渲染全屏背景光晕与底栏指示器。
- [x] 系统媒体通知栏、锁屏封面与蓝牙耳机/车机 AVRCP 播控意图对本地曲目分发与反馈完全正常。

## Comments
已实现基于 `DefaultDataSource.Factory` 的本地音频直放管道，自动旁路弱网看门狗与 WifiLock 硬件锁，保留 CPU 唤醒锁。实现本地媒体元数据、专辑封面主色提取与内嵌/伴生 LRC 歌词解析。定向单元测试全数通过。
