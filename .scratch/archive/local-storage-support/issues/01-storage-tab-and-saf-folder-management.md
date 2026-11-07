# 01: 存储视窗重命名与 SAF 音乐目录挂载管理

**What to build:**
从用户视角出发，将应用底栏第三项从“服务器”重命名为“存储”（Storage），保留现有存储设备图标。存储视窗顶部固定展示一个不可删除的内置“本地存储”卡片，展示当前已挂载目录数与运行状态。点击该卡片将其设为当前活跃存储源（Active Storage）。本地存储卡片右侧提供“管理目录”入口，弹出底部管理弹层，展示已授权的本地音乐目录列表。用户可点击“+ 添加音乐目录”调起 Android 原生 SAF（Storage Access Framework，`ACTION_OPEN_DOCUMENT_TREE`）系统文件夹选择器，选取手机内置存储或外置 SD 卡中的音乐文件夹，授权后持久化保存访问权限（`takePersistableUriPermission`）并支持解除挂载。现有的 WebDAV 服务器配置无缝下移陈列，增删改查逻辑与测试向前兼容。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 应用底栏（Primary Navigation Bar）第三项标签更新为“存储”，指示图标保持为 `AppIcons.Storage`，点击可正常切换至存储视窗。
- [x] 存储视窗（原服务器管理页）顶部永久固定置顶不可删除的“本地存储”卡片，卡片展示本机图标、“本地存储”标题与当前已挂载目录数量状态摘要。
- [x] 点击本地存储卡片主体，可将其设为当前活跃存储（`Active Storage`），并在有需要时联动导航至媒体库。
- [x] 本地存储卡片提供“管理目录”操作按钮，点击后弹出目录管理弹窗/底栏（Sheet），清晰展示已挂载的目录列表及其显示名称。
- [x] 管理弹窗提供“+ 添加音乐目录”动作，触发系统 SAF 文件夹选择器，并在用户选择目录后通过 `ContentResolver.takePersistableUriPermission` 持久化读取权限并落盘。
- [x] 支持在管理弹窗中移除已挂载目录，并在解绑时释放持久化 URI 权限。
- [x] 现有 WebDAV 服务器增删改查与切换功能保持 100% 正常，现有单元测试全部通过。

## Comments
已完成存储视窗重命名、本地存储不可删除常驻置顶卡片、SAF 目录管理弹层（挂载/解挂载与持久化 URI 权限）以及 Room Database 版本升级（v5 -> v6）。定向单元测试全数通过。
