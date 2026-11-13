# 01: 引入规范值模型 TrackIdentity（兼容 TrackKey 过渡）与纯领域测试

**What to build:** 在领域模型层引入不可变强类型 `TrackIdentity`，集中承载本地存储识别（覆盖所有本地协议与规范本地目录路径）、`LOCAL_STORAGE_ID` 单向钳制、物理复合去重键以及曲目标识字符串派生，并暂时保留 `TrackKey` 作为过渡别名，使现有全项目代码与测试保持零破坏通过，同时通过新增的纯领域单元测试全面验证各类跨源路径规范化行为。

**Blocked by:** None (can start immediately)

**Status:** completed

- [x] 领域层新增 `TrackIdentity` 不可变值模型，构造即自动完成本地存储识别（涵盖标准本地 URI 与 `/local/` 规范前缀）与 `LOCAL_STORAGE_ID` 单向钳制。
- [x] `TrackIdentity` 对外提供统一的本地源判断、复合去重键、标准曲目 ID 生成及单源/全库作用域过滤断言能力。
- [x] 原有 `TrackKey` 保留为过渡兼容别名，确保未迁移模块编译与原有测试零回归。
- [x] 补充纯领域单元测试覆盖远程 WebDAV、SAF 文档 URI、文件 URI、`/local/` 规范路径及非法混配 `serverId` 的自动钳制，且针对性单测全部通过。
