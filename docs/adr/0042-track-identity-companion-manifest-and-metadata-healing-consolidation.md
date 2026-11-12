# 0042: TrackIdentity 规范化、伴生清单两阶段收拢与跨源封面批量自愈 (TrackIdentity Canonicalization, Two-Stage Companion Manifest, and Cross-Source Metadata Healing)

## Status

Accepted (Amends ADR-0006, ADR-0022, ADR-0025, ADR-0032b, ADR-0033, and ADR-0037)

> 参照主流成熟 Android 播放器（Symfonium 的强类型跨源身份与分离式歌词列、Poweramp/Auxio 的两阶段目录伴生快显与单份目录封面共享）确立组合包 B 终局架构共识。

## Context

在既有架构演进中，媒体库（`MediaLibraryRepository`）、元数据（`TrackMetadataRepository`）、歌单（`PlaylistRepository`）与存储管理（`ServerRepository`）之间仍存在五处相互交织的结构性摩擦与隐性缺陷：

1. **原始路径判定泛滥与静默分叉**：`TrackKey` 仅局限在局部模块使用，`content://`、`file://` 与 `/local/` 本地路径判定以及去重键拼接（`"local:..."`、`"$serverId:$remotePath"`）分散在 14 个文件 31 处；`AudioTrack.isLocal` 与 `TrackMetadataEntity.fromDomain` 已出现漏判 `/local/` 前缀的规则分叉，违背 `CODING_STANDARDS.md §3` 本地身份单向钳制铁律。
2. **解绑本地目录过度清理与缓存清除粗粒度**：`ServerManagementViewModel.onRemoveMountedFolder` 在 `ServerRepositoryImpl` 已执行精准目录级联后，又冗余调用 `mediaLibraryRepository.clearStorageData(LOCAL_STORAGE_ID)`；同时 `MediaLibraryRepositoryImpl.clearLocalFolderData` 对 `directory_cache` 直接全量清空 `LOCAL_STORAGE_ID`，导致解绑单个挂载文件夹时误删其余本地目录的缓存与索引。此外，删除 WebDAV 服务器时未定向清理 `TrackMetadataRepository` 内存中的歌词与目录封面缓存。
3. **伴生清单（`Companion Manifest`）双份实现与歌词优先级倒置**：
   - `LocalStorageDirectoryResolver` 私自扫盘生成文件夹缩略图并组装 `metadataMap`，与 `DefaultTrackMetadataResolver` 重复实现；
   - `DefaultTrackMetadataResolver` 对本地曲目仅在“内嵌歌词为空时”才匹配外置 `.lrc`，**倒置了 `CODING_STANDARDS.md §6`「外置同名伴生优先，内嵌保底」的规定**；
   - 远程 WebDAV 目录扫描未利用 `PROPFIND` 返回的同目录清单记录 `.lrc`，导致播放期盲发大量 `GET *.lrc` 404 请求；
   - `TrackMetadata.lyrics` 单列混存路径、内嵌正文与缺省态，导致发现外置 `.lrc` 会抹除已提取的内嵌歌词保底；
   - `DefaultTrackMetadataResolver` 以 `RemoteDirectory.getParentPath(file.path)` 切分 SAF `content://` URI 计算 `folderKey`，因 `%2F` 编码导致同一挂载树下所有子目录共享同一个 `folderKey`，引发跨专辑封面碰撞。
4. **歌单 N+1 查库与远程封面自愈缺位**：`PlaylistRepositoryImpl.reconcilePlaylistTracks` 对歌单逐首循环发起 SQLite 单行查询，且 `triggerBackgroundCoverHealing` 硬编码跳过所有远程 WebDAV 曲目，并在歌单仓储内部重复实现磁盘存活性检测。

## Decision

### 1. 提炼强类型 `TrackIdentity` 取代 `TrackKey`
- 在 `domain.model` 建立不可变规范值类型 `TrackIdentity`，全面取代原有 `TrackKey` 与分散的路径拼接字符串；
- 将 `content://`、`file://`、`/local/` 本地源识别、`LOCAL_STORAGE_ID (0L)` 单向钳制、`AudioTrack.id` 派生及跨源物理复合去重键收拢至 `TrackIdentity` 单一实现中，消除 31 处散落前缀判定；
- 并在 `PackagingSeamVerificationTest` 中补充防回归规则，约束核心仓储与协调器不得绕开规范身份模型散写原始前缀判断。

### 2. 前缀级联精准失效与单向 DAG 闭环
- **移除 ViewModel 越界清理**：从 `ServerManagementViewModel` 中彻底删除对 `mediaLibraryRepository.clearStorageData(...)` 的冗余调用，将存储注销级联权唯一收归 `ServerRepositoryImpl`；
- **本地目录按前缀精确清理并保护歌单快照**：`DirectoryCacheDao` 扩充按前缀删除能力；`MediaLibraryRepositoryImpl.clearLocalFolderData(uriString)` 仅按对应树路径前缀清理 `directory_cache`、`media_track_index`、`directory_sync_checkpoint` 及关联 `Browsing Cursor`（并使 `"/"` 与 `"/local/"` 根汇总快照失效），**保留 `track_metadata` 与缩略图快照**以保护跨源歌单中 `Inactive Playlist Track` 的标题、时长与封面展示；
- **删服定向清内存与磁盘（保持 ADR-0037 单向 DAG）**：扩展 `StorageCascadeInvalidator`（或由 `TrackMetadataRepository` 提供定向失效接缝并由 `ServerRepositoryImpl` 单向依赖），在 `deleteServer(id)` 时除清理磁盘封面与 Room 元数据外，同步定向清除 `TrackMetadataRepository` 内存中该 `serverId` 对应的歌词与目录封面缓存，严禁引入环形依赖。

### 3. 升级 Room `v9 -> v10` 分离 `companionLyricsPath` 与两阶段伴生清单
- **数据库 Schema 显式拆列（`MIGRATION_9_10`）**：在 `track_metadata` 表与 `TrackMetadata` 模型中新增 `companionLyricsPath: String?`（存放本地或远程外置 `.lrc` 伴生路径），原 `lyrics` 列专职保留内嵌歌词正文作永驻保底；
- **统一两阶段伴生管道**：
  - `LocalStorageDirectoryResolver` 退化为纯目录列举模块，停止私自读写缩略图与拼装元数据；
  - 由 `TrackMetadataRepository` 统一接管「第一阶段：基于目录完整 `directoryFiles` 清单毫秒级配对同目录共享封面与同名伴生 `.lrc` 并快显 → 第二阶段：并发按需 Range/流式提取内嵌标签与内嵌封面并执行字段级无损补全」；
  - 修复歌词优先级：无论本地或远程曲目，均强制执行**外置伴生 `.lrc` 优先，拉取失败或不存在时零开销回退内嵌 `lyrics`**；若目录清单已核验不存在同名 `.lrc`，播放期跳过盲目 `GET` 请求（仅目录刷新/重扫时更新清单），消灭无谓 404；
- **修复 SAF `folderKey` 碰撞与共享封面单份存储**：由目录富化调用方显式向解析器传入逻辑目录路径（如 `/local/<encodedTree>/Album/` 或远程 `/Music/Album/`）作为 `folderArtworkCache` 键，绝不切分 `content://` URI；同目录多首无内嵌封面的曲目共享基于封面文件物理标识生成的**单份**磁盘缩略图。

### 4. `TrackMetadataRepository` 统一托管批量校验与跨源 `Cover Art Liveness Healing`
- 废除 `PlaylistRepositoryImpl` 内部的 `isCoverDeadOnDisk` 与 `triggerBackgroundCoverHealing`，移除歌单循环内的逐行 `getCachedMetadata` 调用；
- 由 `TrackMetadataRepository` 暴露面向 `List<TrackIdentity>` 的批量元数据读取与存活性自愈接口：一次 Room `IN` 批量查询完成磁盘封面校验，并对传入的**非离线/可用**曲目（同时覆盖本地 SAF 与远程 WebDAV）统一执行防重入后台自愈提取并异步回写歌单快照；
- `PlaylistRepositoryImpl` 仅通过已有的 `StorageReachabilityTracker` 状态过滤出可用曲目传给元数据仓储，避免离线曲目触发网络探活风暴，同时保持两仓储之间零横向耦合。

## Consequences

- **Locality**：`TrackIdentity`、伴生清单匹配、LRC 优先级、SAF 目录作用域、批量自愈与失效策略完全归口至各自唯一的深模块，改一处即全链路生效。
- **Performance & Parity**：消除歌单 N+1 查库与播放期无意义 WebDAV `.lrc` 404 请求；远程歌单曲目获得与本地曲目完全对等的封面自愈能力；解绑单个本地文件夹不再误清其余挂载目录索引。
- **Schema Migration**：需编写并由 `DatabaseMigrationTest` 验证 `MIGRATION_9_10`（`ALTER TABLE track_metadata ADD COLUMN companionLyricsPath TEXT`），属于向后兼容的轻量非破坏性迁移。
- **Amends ADR-0006, ADR-0022, ADR-0025, ADR-0032b, ADR-0033, and ADR-0037**：确立外置伴生列与内嵌保底列的分离存储结构，将 `PlaylistRepository` 残留的封面自愈彻底移交 `TrackMetadataRepository`，并完善本地树前缀级联失效与服务器级元数据缓存定向清理。
