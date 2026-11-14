# Aoffee Player

本项目为远程 WebDAV 与本地设备存储（SAF）双源 Android 高保真流式音乐播放器（应用 ID：`com.webdav.player`）。交互全流程使用中文。

## 1. 核心权限与文档权威准则

- **只读增量特例**：在遵循全局只读模式前提下，仅当通过 `domain-modeling` 达成领域或架构共识时，特例放行内联沉淀至领域文档（`GLOSSARY.md`、`docs/adr/`、`.scratch/`）；业务源码（`app/src/`）与构建脚本保持绝对只读。
- **真理源权威层级（Precedence Hierarchy）**：
  `CODING_STANDARDS.md`（最高架构法典） > `GLOSSARY.md` / 有效 ADR（消费准则详见 `docs/agents/domain.md`） > 历史实现代码。
- **代码审查标准**：遵循 Matt Pocock 双轴审查体系（Standards 轴严格对照 `CODING_STANDARDS.md` 与 Fowler 坏味道；Spec 轴严格对照工单验收条件，杜绝遗漏与越界重构）。由于只读 `reviewer` 子代理无 shell 且 `watchdog_diff` 无法直接检查已提交历史，在触发 `code-review` 前，主代理必须预先将 `git diff <base>...HEAD` 与 `git log <base>..HEAD --oneline` 导出至 `.tmp/review/` 并向子代理传递文件指针；并发审查统一使用 `async: true` 启动。
- **本地工单看板**：
  工单管理与状态流转遵循 `docs/agents/issue-tracker.md` 与 `docs/agents/triage-labels.md`，活跃工单维护在 `.scratch/`，结项后移入 `.scratch/archive/`。

## 2. 四大不可妥协工程红线 (Non-Negotiable Gates)

1. **Subagent execution & concurrency ceiling (并发上限与隔离契约)**
   - 并发上限为 **2–4 个 subagents**，各写子代理必须在相互隔离的独立 Git worktree 中执行（严禁多个写代理共享同一目录）；
   - 多工单并发优先复用持久化 worktree 槽位（`.worktrees/slot-1..3`，配合 `scripts/worktree-slot.ps1`）以共享 Gradle / CMake `.cxx/` 编译缓存，并在干净集成分支推进，避免把未提交改动留在大仓 `main` 上；
   - 子代理仅运行针对性单测（`--tests "*<TargetTest>*"`）；公共脚手架与符号桩必须由主代理预先在集成分支建立；
   - **ADB / MCP 设备独占单写者原则**：真机交互遵循单写者原则（Single Device Writer），仅允许主代理或指定的单个冒烟子代理独占调度真机，严禁多子代理并发调用设备交互工具；日常编码与单测子代理严禁注入设备 MCP 工具以节约上下文。
2. **UI visual sanity & dynamic smoke gate (表现层真机自查与零崩溃红线)**
   - 凡涉及布局、底栏、播放器、歌词等表现层改动，交付前必须通过真机截屏自查居中度、边距与避让，并触发关键交互链路；
   - 必须执行 `scripts/adb-inspect.ps1 -Tag <tag>` 自查关键播控对齐与几何约束（支持 `-Crop 'x,y,w,h'` 局部采样比对）；可配合 `android-mcp-server` 驱动端到端点击与手势；
   - 必须通过 `adb logcat -d -b crash` 确证 **0 崩溃（Zero-Crash）**，杜绝白屏、残影与抖动（无设备环境明确告警并转静态校验）。
3. **Empirical runtime debugging (真机与运行时实证排查红线)**
   - 严禁凭空盲猜；状态机、数据库与置灰问题必须优先获取 logcat 与 SQLite (`webdav_player.db`) 确凿运行时实证；微动效掉帧必须采集 `dumpsys gfxinfo` 纳秒级帧耗时；
   - 日常真机联调与数据库实证排查优先安装 Debug 包（`./gradlew assembleDebug`），非 Root 设备数据库实证统一执行 `scripts/adb-db.ps1 -Table <name> [-AssertMinCount N]`（自动提取三件套并联动 SQLite CLI 断言）；
   - 微动效掉帧分析必须执行 `scripts/adb-gfxinfo.ps1 [-MaxJankRate N] [-MaxP95Ms M]`（自动解析 16 阶段纳秒时间戳、诊断 Traversal/Draw 瓶颈并执行门禁退出）；仅在最终体积/R8 混淆验收时安装 Release 包。
4. **Rapid iterative build & packaging gate (分层构建与发布红线)**
   - **工单日常针对性闭环**：严禁在子工单开发与问题排查期间执行 Release 打包或混淆任务。仅运行针对性单测（`--tests "*<TargetTest>*"`）与 `./gradlew assembleDebug`，单次耗时控制在 10~20 秒内；
   - **Spec 逻辑集成门禁**：整组工单集成合并后，仅运行全量单测 `./gradlew testDebugUnitTest`；
   - **终结发版与体积验收门禁**：仅在涉及三方依赖变更、底层 Native 变动、或进入 GitHub 发版（Release Tag）前的最终终审阶段，单独执行一次 `./gradlew verifyReleasePackaging`，严禁在开发/排查期间与单测拼接串行执行；版本发布统一使用 GitHub CLI（`gh release create`）。
