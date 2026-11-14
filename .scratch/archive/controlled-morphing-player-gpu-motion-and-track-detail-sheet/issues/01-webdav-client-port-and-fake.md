# 01: WebDavClient 端口抽象补全与统一 FakeWebDavClient 测试桩 (Ports & Adapters Closure)

**What to build:**
让上层业务仓储（`ServerRepositoryImpl`）与媒体源构建工厂（`WebDavMediaSourceBuilder`）纯粹面向 `WebDavClient` 抽象端口运行，彻底消除对具体实现类 `OkHttpWebDavClient` 的强转与私有方法绕过；在测试基础设施中提供单一权威、高拟真的 `SharedFakeWebDavClient`（支持目录树模拟、Range 切片下载、网络延迟与 404/超时故障注入），批量替换并清除 7 个测试文件中散落手写的内联 Fake，保证测试基础设施健壮统一。

**Blocked by:** None (can start immediately).

**Status:** completed

## Acceptance criteria

- [x] `WebDavClient` 接口补全声明 `invalidateServer(serverId: Long)` 与 `getStreamingClientForServer(server: WebDavServer): OkHttpClient`。
- [x] `OkHttpWebDavClient` 实现新增的端口方法；`WebDavMediaSourceBuilder` 与 `ServerRepositoryImpl` 构造参数类型从 `OkHttpWebDavClient` 纯化为 `WebDavClient`，彻底消除运行时向下类型强转。
- [x] 在 `app/src/test/java/com/webdav/player/test/SharedFakeWebDavClient.kt` 建立统一可配置的测试桩，完整实现 `WebDavClient` 端口所有方法，支持目录树模拟、文件/Range 下载模拟与网络异常注入。
- [x] 批量重构并清理 7 个测试文件中散落私有手写的 `FakeWebDavClient` 副本，统一切换为 `SharedFakeWebDavClient`。
- [x] 针对性单元测试通过：`./gradlew testDebugUnitTest --tests "*WebDavMediaSourceBuilderTest*" --tests "*SharedFakeWebDavClientTest*"`。
