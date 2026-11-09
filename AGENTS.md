# Aoffee Player

本项目为远程 WebDAV 与本地设备存储（SAF）双源 Android 高保真流式音乐播放器（应用 ID：`com.webdav.player`）。交互全流程使用中文。

## 1. 核心权限与文档权威准则

- **只读增量特例**：在遵循全局只读模式前提下，仅当通过 `domain-modeling` 达成领域或架构共识时，特例放行内联沉淀至领域文档（`GLOSSARY.md`、`docs/adr/`、`.scratch/`）；业务源码（`app/src/`）与构建脚本保持绝对只读。
- **真理源权威层级（Precedence Hierarchy）**：
  `CODING_STANDARDS.md`（最高架构法典） > `GLOSSARY.md` / 有效 ADR（消费准则详见 `docs/agents/domain.md`） > 历史实现代码。
- **代码审查标准**：遵循 Matt Pocock 双轴审查体系（Standards 轴严格对照 `CODING_STANDARDS.md` 与 Fowler 坏味道；Spec 轴严格对照工单验收条件，杜绝遗漏与越界重构）。
- **本地工单看板**：
  工单管理与状态流转遵循 `docs/agents/issue-tracker.md` 与 `docs/agents/triage-labels.md`，活跃工单维护在 `.scratch/`，结项后移入 `.scratch/archive/`。

## 2. 四大不可妥协工程红线 (Non-Negotiable Gates)

1. **Subagent execution & concurrency ceiling (并发上限与隔离契约)**
   - 并发上限为 **2–4 个 subagents**，各子代理必须在独立 worktree 中隔离执行（`worktree: true`）；
   - 子代理仅运行针对性单测（`--tests "*<TargetTest>*"`）；公共脚手架与符号桩必须由主代理预先在集成分支建立。
2. **UI visual sanity & dynamic smoke gate (表现层真机自查与零崩溃红线)**
   - 凡涉及布局、底栏、播放器、歌词等表现层改动，交付前必须通过真机截屏自查居中度、边距与避让，并触发关键交互链路；
   - 必须通过 `adb logcat -d -b crash` 确证 **0 崩溃（Zero-Crash）**，杜绝白屏、残影与抖动（无设备环境明确告警并转静态校验）。
3. **Empirical runtime debugging (真机与运行时实证排查红线)**
   - 严禁凭空盲猜；状态机、数据库与置灰问题必须优先获取 logcat 与 SQLite (`webdav_player.db`) 确凿运行时实证；微动效掉帧必须采集 `dumpsys gfxinfo` 纳秒级帧耗时。
4. **Rapid iterative build & packaging gate (分层构建与发布红线)**
   - 日常快速闭环仅运行针对性单测与 `./gradlew assembleDebug`；
   - 交付结项门禁强制通过 `./gradlew testDebugUnitTest verifyReleasePackaging`；版本发布统一使用 GitHub CLI（`gh release create`）。
