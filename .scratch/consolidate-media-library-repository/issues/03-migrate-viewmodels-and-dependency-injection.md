# 03: 表现层接入 MediaLibraryRepository 并精简 ViewModel 依赖

**What to build:** 在 `AppContainer` 与 `WebDavApplication` 中注册并暴露单一的 `mediaLibraryRepository`；重构 `DirectoryBrowserViewModel`，构造函数移除 `librarySearchRepository`，将 `directoryRepository` 替换为 `mediaLibraryRepository`（依赖由 4 个减至 3 个）；重构 `ServerManagementViewModel` 对分离仓储的调用；迁移所有 ViewModel 单测（`DirectoryBrowserViewModelTest`、`DirectoryBrowserSyncTest`、`ServerManagementViewModelTest`），使用统一的媒体库 Fake/Mock 跑通所有状态流。

**Blocked by:** 01: 建立 MediaLibraryRepository 领域接口与深层实现, 02: 收敛 ServerRepository 存储级联注销至 MediaLibraryRepository

**Status:** completed

- [x] `AppContainer` 声明 `val mediaLibraryRepository: MediaLibraryRepository`，并在 `WebDavApplication` 中完成单例初始化与依赖组装。
- [x] `DirectoryBrowserViewModel` 构造函数移除 `librarySearchRepository`，统一依赖 `mediaLibraryRepository: MediaLibraryRepository`，收拢目录流、检索流、同步状态与历史记录。
- [x] `ServerManagementViewModel` 移除对分离仓储的双重依赖，改由 `mediaLibraryRepository` 触发后台扫描与缓存失效。
- [x] `AppViewModelFactory` 同步更新，使用 `mediaLibraryRepository` 装配各 ViewModel。
- [x] 重构更新 `DirectoryBrowserViewModelTest`、`DirectoryBrowserSyncTest` 与 `ServerManagementViewModelTest`，全部断言在统一媒体库接缝上 100% 通过。
