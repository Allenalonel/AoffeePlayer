# Persistent Directory Cache with SWR and Complete Session Resume

## Status

Accepted (Amended by ADR 0025, ADR 0034, and ADR 0035: hard foreign key removed; dual-track storage state decoupled)

> ⚠️ **AMENDMENT NOTICE**:
> - **状态**: `Accepted (Amended by ADR 0025, ADR 0034, and ADR 0035)`
> - **现行法典**: 技术约束以 `CODING_STANDARDS.md` 为唯一权威终审。
> - **核心修订**: 
>   1. Decision 1 中的硬外键级联删除已被 ADR-0025 与标准 §5 废除（SQLite 缓存表严禁硬外键，采用应用层弹性级联清理）；
>   2. Decision 3 中的单活跃源已被 ADR-0035 双轨解耦（媒体库浏览源 `browsingServer` 与在播流凭据 `activeServer` 彻底分离）；
>   3. 目录快照中的 CUE 关联已被 ADR-0034 废止。

## Context

原应用在目录浏览上仅维护内存级临时缓存（`ConcurrentHashMap`），进程重启或返回后数据彻底丢失。每次进入远程目录均需通过 WebDAV `PROPFIND` 发起网络往返，高延迟与弱网环境下白屏等待时间较长。同时，应用启动时存在冷启动会话恢复时序与异步数据流冲突，导致无法可靠秒开上次浏览的目录并恢复待播队列。

## Decision

1. **Room 持久化双层目录缓存**：
   - 在 `AppDatabase` 新增 `directory_cache` 数据表，持久化存储各服务器下各目录路径的子目录及文件快照与时间戳。
   - 建立内存 + 本地数据库的双层缓存架构（Memory L1 + SQLite L2），~~外键关联服务器并在服务器移除时级联清空~~ *(已在 ADR-0025 与 CODING_STANDARDS §5 废除：SQLite 缓存表严禁硬外键，统一由仓储层执行应用级弹性清理)*。
2. **SWR（Stale-While-Revalidate）更新策略**：
   - 用户进入目录或应用冷启动时，优先瞬时读取并渲染本地缓存快照（0ms 交互延迟）。
   - 后台异步向 WebDAV 发起 `PROPFIND` 进行增量比对与更新；若远端有变动则平滑更新界面与持久化缓存；网络失败时不阻断当前已渲染的缓存浏览。
   - 保留下拉刷新（Pull-to-refresh）作为强制穿透与全量校验手段。
3. **独立服务器目录记忆与完整现场恢复**：
   - 为每个 WebDAV Server 独立持久化记录其最后停留目录（`Directory Session State`）。
   - 重塑冷启动初始化时序，确保在会话状态（活跃服务器、待播队列、播放进度、最后停留目录）就绪后再触发目录渲染与播控装配，实现「上次目录秒开 + 队列随时就绪」的完整无缝体验。

## Consequences

- 目录打开实现即点即看，彻底消除反复进入同一目录时的网络等待。
- 弱网与离线环境下应用具备基础的曲库可浏览性。
- 需要在持久化层处理 JSON 序列化/反序列化及数据库版本平滑升级。
