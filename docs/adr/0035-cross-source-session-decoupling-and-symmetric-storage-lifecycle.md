# 0035: 跨源会话双轨解耦与对称存储生命周期闭环 (Cross-Source Session Decoupling and Symmetric Storage Lifecycle)

## Status

Accepted

## Context

随着 Aoffee Player 演进至跨服务器混编歌单（ADR-0020）、本地存储 SAF 挂载（ADR-0032b）以及统一曲库 B-Tree 索引（ADR-0033）的多源混编架构，系统在长时间运行与复杂多源操作场景下暴露出了一批深层次的架构摩擦与隐性边界缺陷：

1. **Active Storage 与 Playback Server Context 混用引发媒体库重置**：
   - 过去 `PlayerSessionState.activeServer` 既承担“媒体库当前浏览源（Active Storage）”，又承担“在播物理流凭据上下文（Server Context）”；
   - 每次切歌（`loadTrackIntoEngine`）都会无差别覆写 `sessionState.activeServer`；
   - 当用户在媒体库深入浏览 Server A 的子目录、而后台播放包含 Server B 或本地存储的混合歌单时，每次切歌都会导致 `DirectoryBrowserViewModel` 监听到活跃源变更，强制将用户正在浏览的媒体库目录重置到另一个源的根目录，破坏浏览连续性。
2. **冷启动外部 Playback Intent 竞态丢指令**：
   - 应用进程被系统回收后，用户通过蓝牙耳机播放键或通知栏按键唤醒应用；
   - 系统服务在 `onStartCommand` 中分发 `appSession.play()` 时，`DataStore` 异步持久化恢复（`restoreSession()`）尚未完成，`isRestored` 为 `false` 且 `currentTrack` 为空，导致外部首次播放指令被静默丢弃，必须手动重复按键。
3. **本地 SAF 伴生封面与 LRC 歌词在后台富化阶段被覆盖抹除**：
   - `DefaultTrackMetadataResolver` 在借助目录清单（`directoryFiles`）解析伴生资源时，忽略了 `RemoteFile.path` 并使用 `buildCandidatePath` 拼装相对路径，对本地 `content://` URI 生成了非法的 `/content:/.../cover.jpg` 导致外置封面探测失败；
   - `DirectoryRepositoryImpl.enrichDirectoryContent` 在合并实时目录与元数据缓存时执行 `resolvedMetadataMap + cachedMetadata` 整项覆盖，将已探测到的本地伴生封面与伴生 `.lrc` Document URI 彻底清空为 `null`。
4. **解绑本地目录与删除服务器时的级联失效断路**：
   - `ServerRepositoryImpl.removeMountedLocalFolder` 仅清理了搜索索引和检查点，漏删了 `directory_cache` 中的 `LOCAL_STORAGE_ID` 目录快照以及仓储层 L1 内存缓存，解绑后媒体库依然闪现已被移除的幽灵文件夹；
   - `deleteServer` 在删除默认服务器时缺少升格剩余远程服务器为默认节点的自动回退逻辑。
5. **引擎与系统通知栏封面解码开销与 URI 兼容缺陷**：
   - `Media3AudioPlayerEngine` 对 `content://` 封面路径机械调用 `Uri.fromFile(File(path))` 破坏了协议头；
   - `WebDavNotificationProvider` 仅支持 `file://` 协议，且每次状态切换刷新通知时在主线程重复调用 `BitmapFactory.decodeFile`。

## Decision

恪守深层模块设计准则、对称闭环准则与跨源身份隔离铁律，完成跨源会话双轨解耦与全链路存储生命周期闭环：

### 1. 建立双轨存储状态与统一音轨所属源解析接缝 (Dual-Track Storage Decoupling)

- **`browsingServer` 与 `activeServer` 严格分离**：
  - 在 `PlayerSessionState` 中引入显式 `browsingServer: WebDavServer? = activeServer`；
  - 媒体库浏览目录、祖先面包屑、存储管理仅响应 `browsingServer`（对齐 `ServerRepository.getActiveServer()`）；
  - 播放器时间线、预热预卷与 MediaSource 凭据仅响应物理在播音轨的 `activeServer`；
  - 混合歌单跨源切歌时仅流转 `activeServer`，严禁污染或重置用户的媒体库浏览上下文。
- **收拢 `resolveServerForTrack` 单一权威接缝**：
  - 在 `PlaybackQueueManager` 中收拢 `resolveServerForTrack(track, preferredServer, fallbackServer)`；
  - 集中担保 `track.isLocal` 强制钳制 `LOCAL_STORAGE_ID = 0L`，移除全工程 6 处重复且容易漏判的手写分支。

### 2. 冷启动播控意图恢复门控 (Intent Restoration Gating)

- 在 `MusicPlayerAppSessionImpl` 的 `play()`、`togglePlayPause()`、`skipToNext()`、`skipToPrevious()` 中加入状态门控：
  - 当 `!isRestored.value && queue.isEmpty` 时，挂起等待异步 `restoreSession()` 完成后再安全调度起播；
  - 彻底杜绝冷启动时因时序竞争导致的外部蓝牙/通知栏首次播放指令被丢弃的缺陷。

### 3. 本地 SAF 伴生文件保真与字段级无损富化 (Lossless Companion Merge)

- **`DefaultTrackMetadataResolver`**：
  - 命中 `directoryFiles` 清单时直接透传并消费其真实 `content://` Document URI；
  - 音频内嵌歌词为空且存在伴生同名 `.lrc`（`content://` / `file://`）时，自动将伴生歌词写入 `TrackMetadata`。
- **`DirectoryRepositoryImpl.enrichDirectoryContent`**：
  - 将整项覆盖重构为**字段级非空保底合并**：若内嵌标签未提取出封面或歌词，强制保留 `resolvedMetadataMap` 中已探测到的同目录伴生封面与伴生 `.lrc`，杜绝二次发射时伴生资源消失。

### 4. 存储注销多级缓存级联清理闭环 (Symmetric Cache Cascade)

- **解绑本地目录**：`removeMountedLocalFolder` 增加 `directoryCacheDao?.deleteCacheByServerId(LOCAL_STORAGE_ID)`，并联动调用 `DirectoryRepository.clearCacheForServer(LOCAL_STORAGE_ID)`，彻底消除已解绑文件夹的残留快照与 L1 内存缓存。
- **删除远程服务器**：`deleteServer` 补齐默认节点自动升格逻辑（优先提升剩余远程节点，无剩余时回退为空），并联动清理 `DirectoryRepository` 对应服务器的 L1 内存缓存。

### 5. 引擎与通知栏封面 URI 兼容与内存复用

- **`Media3AudioPlayerEngine`**：`applyActiveTrackMetadataUpdate` 对齐 `WebDavMediaSourceBuilder`，兼容 `content://` 与 `file://` 解析。
- **`WebDavNotificationProvider`**：支持 `content://` 流式读取，并引入单曲封面 `Bitmap` 内存缓存，播控状态切换刷新通知时实现 0 磁盘 I/O 瞬间复用。
- **`ArtworkColorExtractor`**：引入 64 节点 LRU 内存缓存（`getCachedArtworkColors`），切回已播曲目实现 0ms 瞬间换肤，并在 `onTrimMemory` 中联动释放。

### 6. AppContainer 接口整洁度收窄

- `AppContainer` 移除 5 个零外部调用方的内部装配属性（`sessionStore`、`networkConnectivityObserver`、`mediaTrackIndexDao`、`localStorageIndexScanner`、`webDavSyncScanner`），将 `webDavClient` 收窄为 `WebDavClient` 接口类型。

## Consequences

### Positive
- **切歌零干扰**：混合歌单跨源连续切歌不再打断用户在媒体库中的目录浏览或多选操作。
- **冷启动外部必响**：耳机按键与通知栏冷启动唤醒百分之百可靠起播，消除了时序竞态盲区。
- **伴生资源绝对稳定**：本地 SAF 目录下无内嵌标签的纯伴生封面与 `.lrc` 歌词在整个加载生命周期内永不丢失。
- **缓存完全自洽**：移除本地目录或删除服务器后，内存缓存与持久化快照同步原子失效。
- **全量测试绿色通行**：全工程 1,152 个单元测试 100% 通过，真机安装 0 崩溃，界面渲染符合 M3 Expressive 设计规范。

### Superseded and Amended ADRs
- **Amends ADR-0008, 0018, 0019, 0020, 0023, 0024, 0025, 0030, 0032b, 0033**: Fully decouples browsing storage state from active playback context, gates cold-start system intents, preserves companion manifest Document URIs, and establishes symmetric storage cascade cleanup.
