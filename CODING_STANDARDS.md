# Coding Standards

These standards are enforced during `/code-review` alongside the Fowler code-smell baseline. Mechanical rules (import directions, ProGuard rules, binary budgets) are enforced deterministically in `PackagingSeamVerificationTest`.

- **规范效力铁律**：`CODING_STANDARDS.md` 高于所有 `docs/adr/`。ADR 仅作为特定技术选型的历史背景参考，绝不构成任何模块的“架构免检金牌”，严禁援引 ADR 历史条目对抗或推翻 `CODING_STANDARDS.md`。随着新特性持续迭代，既有已重构模块极易产生架构漂移（Architecture Drift）与边界侵蚀；架构审查必须面向全工程，凡发现真实退化、隐性缺陷或新引入的摩擦，一律允许并应当提出深化（Deepening）方案。

---

## 1. Module Depth & Orthogonal Decomposition (模块深度与正交解耦)

- **Judge by Responsibility, Not Line Count**:
  Evaluate modules by **change-axis orthogonality (Divergent Change)** and **interface depth (leverage)**, never by rigid line-count limits.
  - **When to keep together**: If a module has a narrow interface and a single cohesive responsibility (e.g., binary stream demuxing in `AsfExtractor`, CUE sheet parsing in `CueParser`, or a self-contained Compose component), keep it in one file even if it spans 400–600+ lines.
  - **When to split (Internal Seam Discipline)**: When a single implementation class accumulates **2 or more orthogonal sub-domains** (e.g., session persistence + queue topology + network fault watchdog) or exhibits high constructor fan-out (6+ unrelated dependencies), extract package-private (`internal`) collaborators while keeping the external interface narrow.
- **No Artificial Fragmentation**:
  Do not split a cohesive 150–400 line component into tiny single-caller satellite files (`*Collaborators.kt`, 30-line pill wrappers) that force readers to bounce across multiple files to understand one concept.
- **No Formatting Tricks**:
  Never chain multiple statements on a single line with semicolons (`;`) or compress parameter lists to artificially reduce line counts.

---

## 2. Seam Discipline & Zero Zombie Forwarders (接缝纪律与零僵尸转发)

- **Two Adapters Justify a Seam**:
  Do not introduce an interface if it only ever has one concrete implementation and no test stand-in. Collapse single-adapter wrappers into their owning module.
- **Delete Transitional Scaffolding (No Middle Man)**:
  When migrating callers to a new or deepened seam (such as `AppViewModelFactory`, `AudioTrackRow`, or `PlaybackActions`), delete the old pass-through factories, compatibility aliases, and `@Deprecated` shells in the same change. Never leave zero-production-caller forwarding files in the tree.

---

## 3. Test Surface Discipline (测试表面纪律)

- **The Interface Is the Test Surface**:
  Write tests against the public interface and observable behavior of a module.
- **No Implementation-Locking Assertions**:
  - Do not use reflection (`Class.forName("...Kt")`, `declaredMethods`) to assert on internal Kotlin file facades or private/internal helper names.
  - Do not hardcode file line-count assertions (`readLines().size <= X`) inside unit tests.

---

## 4. Cache Elasticity & Persistence Boundaries (缓存弹性与持久化边界)

- **No Hard SQLite Foreign Keys on Cache Tables**:
  `directory_cache` and `track_metadata` must **not** declare `FOREIGN KEY (serverId) REFERENCES webdav_servers(id) ON DELETE CASCADE`. Cross-server playlists (`ADR-0020`) and background metadata self-healing (`ADR-0022`) require reading and writing track snapshots even when a server is offline, unconfigured, or isolated in DAO tests. Cascade cleanup upon explicit server deletion is handled in `ServerRepositoryImpl.deleteServer`.

---

## 5. Mainstream Engineering Benchmark & Pragmatic Review (对标主流工业级标准与务实审查)

- **Benchmark Against Mainstream Production Engineering (对标主流工业级标准，拒绝教条化审查)**:
  The sole North Star for architecture review is **mainstream production-grade software engineering**: standard architectural patterns, industry benchmarks, framework-idiomatic conventions, and pragmatic maintainability.
  Strictly avoid "reviewing for the sake of reviewing" or applying extreme academic/theoretical dogmas that deviate from mainstream industry engineering.
- **Problem-Driven with Measurable Engineering Value (问题驱动与实质收益)**:
  Any architectural finding or refactoring proposal must address genuine engineering friction: performance bottlenecks, concurrency/lifecycle hazards, memory leaks, boundary leakage, or real maintenance impediments.
  If an existing design aligns with mainstream framework idioms, is cleanly structured, and reliably passes all quality/performance gates, it is solid engineering. Strictly prohibit inventing make-work, circular refactorings (endless splitting vs. merging), or aesthetic churn that delivers zero practical engineering value.
- **Review Stopping Discipline & Drift Auditing (收敛边界与漂移审查准则)**:
  An architectural review must converge based on empirical health, not historical immunity.
  审查的收敛与终止是建立在“经全面实证该模块当前运行健康、无真实边界摩擦与维护障碍”之上，绝非建立在“它曾经写过 ADR 或重构过”的免检假设上。
  若模块经审计符合主流工程标准且无实质痛点，应确认合格并收敛终止；反之，若后续开发在该模块引入了新的正交职责、循环依赖或状态泄漏，无论其历史 ADR 多么完善，均须将其列为深化候选。

---

## 6. Fault Lifecycle Symmetry & Diagnostic Parity (故障生命周期对称闭环与诊断对等)

- **Symmetric Fault Lifecycle (故障对称闭环原则)**:
  凡在仓储或持久层设计了写入持久化错误、降级或置灰标记（如 `markTrackInactive`、`markServerInactive`）的接缝，必须在同一领域边界内提供对等、全自动的解除与自愈信道（如探活自愈、成功出声反哺、测试连接联动清除）。严禁只写不销、只增不减的单向状态累积，杜绝节点交互死锁。
- **Diagnostic Aggregation Parity (UI 诊断聚合与领域自愈对等原则)**:
  当表现层将多个底层故障枚举（如 `SERVER_UNREACHABLE` 与瞬态 401/403 `SERVER_CREDENTIAL_INVALID`）聚合映射为同一用户提示文案时，领域自愈层与状态清除逻辑必须以相同的广度覆盖这些枚举，彻底消除“UI 展现已聚合、底层自愈漏一半”引发的认知死角。
- **Failure Granularity Alignment (故障粒度对齐原则)**:
  针对整机/服务级异常（如网络拒绝、超时、服务重启认证不可用），故障标记与恢复必须在服务器级批量同步，严禁仅对当前报错单曲标记而留同源排队曲目高亮，杜绝同源数据展现割裂。

---

## 7. Motion & Container Morphing Architecture (动效系统与容器形变工程准则)

- **Single Living Entity & Slot Architecture (单一真实实体与插槽架构法则)**:
  在容器连续形态蜕变（Morphing Container）与多阶界面过渡中，核心视觉元素（专辑封面、主控按钮）必须且仅能在根容器宿主中拥有唯一的实例，全生命周期常驻；子视图（MiniPlayer 与 FullPlayerView）严禁重复实例化同名多媒体视图，必须退化为纯几何插槽（Slot Anchor，仅保留约束尺寸与坐标测量回调）。严禁在动画终点通过 `if (isMorphing)` 等条件分支进行结构性组件卸载与“替身到真实视图”的偷换（End-State Swap），彻底从物理根源上消灭 RenderNode 切换导致的 1 帧空白闪烁与排版跳跃。
- **Draw Phase Animation Rule (GPU 绘制阶段渲染原则)**:
  在 60/120fps 连续高频形变中，严禁逐帧修改 `Modifier.size()` 与 `Modifier.offset()`（这会强迫 Compose 逐帧回退至 CPU Measure & Layout 测量重排与图片重采样，引发掉帧卡顿）；必须以固定目标尺寸为基底，统一在 `Modifier.graphicsLayer { scaleX/Y = ...; translationX/Y = ...; shape = ...; clip = true }` 内通过 GPU 矩阵变换完成缩放位移，保障 CPU 零重新测算与 120Hz 硬件加速。
- **Zero-Ghosting & Parallax Space Orchestration (零残影与全局空间视差联动)**:
  容器在形变或展开过程中，屏幕上严禁出现同一封面或同一定位控件的二次重叠绘制（Double Rendering / Ghosting Artifacts）。当通过顶层共享图层执行飞行动画时，底层的真实目标槽位必须使用纯净的 `Spacer` 进行无渲染几何占位，严禁底层在半透明浮现期间重复渲染封面背板、阴影或按钮图形，杜绝重影叠印与月牙阴影。同时必须保持全局空间视差联动（底栏下沉 80dp 退出与主视窗微缩至 0.94f），杜绝突兀硬切。
- **Dynamic Theme Purity & Universal Perceptual Normalization (组件色调动态纯净度与通用感知归一化)**:
  严禁在 UI 表现层针对特定业务状态（如 Hi-Res 24-bit 标志、格式徽章、播放按钮）硬编码孤立颜色常量（如 `#FFB703` 或写死 Hex 色值）；所有高亮标签、指示器与边框必须统一绑定到 `MaterialTheme.colorScheme` 的动态语义 Token，由当前曲目封面通过 Google Palette + HCT 规范提取的种子色全自动推导，保障在任意色调（冷青、暖金、玫红、纯黑白）专辑下 100% 动态跟随自适应。

---

## 8. Cross-Source Storage Identity & Search Scope Discipline (跨源存储身份隔离与检索范围契约)

- **Local Storage Identity Clamping (本地存储身份单向隔离法则)**:
  `LOCAL_STORAGE_ID = 0L` 与远程 WebDAV 节点互为一等公民，但两者的资源寻址体系完全异构（Android SAF `content://` URI vs 相对路径 HTTP/HTTPS URL）。播放管线（`MusicPlayerAppSessionImpl`）、元数据解析与提取协调器（`PlaybackMetadataCoordinator`、`TrackMetadataRepositoryImpl`）以及数据实体构造（`TrackMetadataEntity.fromDomain`）必须执行**严格单向隔离保护**：任何路径以 `content://` 或 `file://` 开头的本地音轨，其持久化关联的 `serverId` 必须无条件强制钳制为 `LOCAL_STORAGE_ID` (`0L`)，**严禁借用、继承或污染当前活跃的远程 WebDAV 服务器 ID**。
- **Strict Source Isolation & Physical Composite Deduplication (严格源过滤隔离与物理复合去重法则)**:
  - **单源严格隔离**：单源检索时（`serverId != null`），若目标源为 WebDAV，底层查询与内存映射层必须对所有数据流（曲目索引、目录快照、元数据缓存）实施严格防御式过滤，坚决剔除任何本地文件（`content://`、`isLocal`）；若目标源为本地存储，坚决剔除任何远程文件。
  - **全库复合去重**：全库跨源检索时（`serverId == null`），由于存在混合持久化表（`media_track_index`、`directory_cache`、`track_metadata`），必须采用**物理唯一标识键**（本地音轨统一映射为 `local:${remotePath}`，远程音轨映射为 `${serverId}:${remotePath}`）进行严格去重，杜绝同一首物理文件在搜索结果中重复渲染。
- **Mutual Exclusion Scope Controls & Unmistakable Visual Contrast (互斥检索范围控件与强视觉状态法则)**:
  当搜索或过滤界面提供“当前源”与“全库搜索”等互斥状态切换时，严禁使用视觉权重轻微、未激活/激活状态差异模糊的松散组件（如弱边界的普通 `FilterChip`），必须采用符合平台设计规范的**一体化分段滑块胶囊（Segmented Pill Capsule）**。选中的分段必须具备饱满的高对比实体背景、加粗文本及语义辅助图标，使当前作用域在任何主题下具有一目了然的确定性心智。
- **Zero-Friction Silent Auto-Indexing (零阻力静默自建索引原则)**:
  曲目快速索引必须是完全透明的系统基础设施，严禁将“同步媒体库/建立索引”做成独立的手动阻断前置步骤，更严禁在主界面顶部栏增加二级菜单让用户找寻操作。新节点接入（保存服务器/挂载本地目录）、应用启动切换及进入搜索时，必须由系统在后台协程静默发起增量快照索引，并通过自然手势（列表下拉刷新 Pull-to-Refresh）统一归口手动触发。


