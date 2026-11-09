# 0037: 存储生命周期级联失效与单向仓储接缝 (Storage Lifecycle Cascade and Unidirectional Repository Seams)

## Status

Accepted

## Context

在先前的架构演进中，**ADR-0036** 成功将目录浏览与全局搜索两大离散仓储折叠收敛为统一的 `MediaLibraryRepository` 深模块，大幅简化了表现层 ViewModel 的接口表面。

然而在依赖装配与生命周期级联层面，系统遗留了一处隐蔽的**环形接缝依恋（Circular Seam Dependency）**：
1. `ServerRepositoryImpl` 在执行服务器删除（`deleteServer`）与本地目录解绑（`removeMountedLocalFolder`）时，需要级联清理媒体库的 L1 内存缓存、L2 Room 目录快照、搜索索引及同步检查点，因而直接持有了 `var mediaLibraryRepository: MediaLibraryRepository? = null`；
2. 另一方面，`MediaLibraryRepositoryImpl` 在初始化本地存储目录解析器 `LocalStorageDirectoryResolver` 时，后者为了读取当前已挂载的 SAF 文件夹列表，又反向注入了 `ServerRepository`；
3. 这导致在应用启动初始化（`WebDavApplication.onCreate`）时，两个单例仓储无法通过构造函数完成天然装配，被迫引入了丑陋的可变属性后门与向下转型注入：
   ```kotlin
   (serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = mediaLibraryRepository
   ```
4. 这种后门注入不仅破坏了 Kotlin 不可变对象构造规范，还使得 `ServerRepositoryTest` 与 `ServerCascadeDeletionTest` 必须显式感知 `MediaLibraryRepository` 的具体实现或复杂 Mock，污染了仓储层的测试表面。

## Decision

恪守 `/codebase-design` 深度模块与窄接口倒置纪律，彻底斩断逆向边缘并确立单向有向无环图（DAG）：

### 1. 建立领域窄接口接缝 `StorageCascadeInvalidator`
在 `domain.repository` 提炼最小职责窄接口，仅承载存储注销引发的级联失效契约：
```kotlin
interface StorageCascadeInvalidator {
    suspend fun clearStorageData(serverId: Long)
    suspend fun clearLocalFolderData(uriString: String)
}
```
`MediaLibraryRepository` 继承该接口。现存的方法签名完全满足契约，实现零冗余、零破坏。

### 2. 仓储构造不可变倒置与消除后门
`ServerRepositoryImpl` 构造函数以不可变 `val` 接收窄接口，彻底删除 `var mediaLibraryRepository`：
```kotlin
class ServerRepositoryImpl(
    ...,
    private val cascadeInvalidator: StorageCascadeInvalidator? = null,
    ...,
) : ServerRepository
```
`deleteServer` 与 `removeMountedLocalFolder` 统一委托 `cascadeInvalidator` 进行同步阻塞清理，确保级联事务的一致性，且 `ServerRepositoryImpl` 不再引入任何 `MediaLibraryRepository` 符号。

### 3. 解除本地解析器对整仓储的依赖
`LocalStorageDirectoryResolver` 仅需要读取已持久化的本地 SAF 文件夹。直接将依赖收敛为其底层数据访问对象：
```kotlin
class LocalStorageDirectoryResolver(
    private val context: Context,
    private val localStorageFolderDao: LocalStorageFolderDao,
    ...,
)
```
彻底剥离对 `ServerRepository` 的依赖。

### 4. 依赖构建管道回归纯粹单向 DAG
`WebDavApplication` 中的组装顺序严格对齐依赖拓扑：
```
AppDatabase
  └── LocalStorageFolderDao
        └── LocalStorageDirectoryResolver
              └── MediaLibraryRepositoryImpl (as StorageCascadeInvalidator)
                    └── ServerRepositoryImpl
```
彻底废止 `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = ...` 强转后门。

### 5. 架构接缝自动化持续检验
在 `PackagingSeamVerificationTest` 中追加自动化架构断言：
- `ServerRepositoryImpl.kt` 严禁反向 `import com.webdav.player.domain.repository.MediaLibraryRepository`；
- `WebDavApplication.kt` 严禁存在向下类型转换 `as? ServerRepositoryImpl` 或属性后门修改。

## Consequences

- **Locality**: 存储源注销时的级联清理契约收敛在窄接口之后，存储凭据管理与媒体库缓存实现彻底物理隔离；
- **Leverage**: `ServerRepositoryImpl` 恢复纯粹的不可变构造函数注入，测试表面仅需面对最小函数式接口或轻量 Fake；
- **Immutability**: 彻底消除了进程启动期的可变属性后门，根除了由于初始化顺序交错导致的级联缓存漏清理隐患。
