# Aoffee Player

Android 平台上的现代化高保真音乐播放器，遵循 Material Design 3 Expressive (M3E) 视觉语言，统一支持远程 WebDAV 服务器（NAS / 私有云）纯在线流式回放与本地设备存储（SAF）无损直放。

## Language

### Application & Branding

**Aoffee Player**:
本播放器应用的正式官方名称，遵循 Material Design 3 Expressive 动态视觉体系与跨源音频架构。
_Avoid_: WebDAVPlayer, WebDavPlayer, WebDav Music Player

### Storage & Sources

**Storage Source**:
统一抽象的音乐存储源节点，涵盖内置本地设备存储节点与用户配置的各个远程 WebDAV 服务器节点。
_Avoid_: Drive, Source Provider, Media Account

**Browsing Storage**:
用户当前在媒体库中选定用于目录层级浏览与存储管理的存储源，与当前在播音轨所属的物理源独立解耦。
_Avoid_: Playback Server, Stream Source, Active Server (指代浏览目标时)

**Playback Server**:
当前正在解码播放或排队待播音轨物理所属的存储服务器凭据与网络连接主体。
_Avoid_: Browsing Server, UI Selected Server, Navigation Target

**Local Storage**:
内置不可删除的本地设备存储源节点，通过 Android 存储访问框架（SAF）聚合管理用户授权的本地音乐目录。
_Avoid_: Phone Storage, Local File System, Internal Drive

**Mounted Directory**:
用户通过系统目录选择器授权并持久化访问权限的本地音乐文件夹，作为本地存储源下的根目录节点呈现。
_Avoid_: Document Tree, Folder Access, Local Path

**WebDAV Server**:
提供基于 WebDAV 协议远程文件访问能力的存储服务器配置，包含主机地址、端口、路径、认证凭证及证书信任选项。
_Avoid_: Host, Cloud, Remote Drive

**Storage Directory**:
存储源上的目录层级节点，聚合当前路径下的子目录与音频曲目集合。
_Avoid_: Remote Path, Cue Album, Cue Folder, Virtual Album

**Companion Manifest**:
与音频曲目同目录存放的外置伴生资源集合（包含同目录封面图像与同名伴生歌词文件），作为音轨标签信息的保真补充源。
_Avoid_: Extra Files, Sidecar Assets, Folder Icon, Album Photo

**Directory Cache**:
存储源目录在本地持久化存储中的结构化镜像快照，用于秒级列表渲染及离线状态下的目录浏览。
_Avoid_: Folder Cache, File Index, Offline Copy

**Media Library**:
跨源音乐曲目的聚合统一组织与检索实体，融合目录层级快照、增量同步检查点与跨源音轨全局索引。
_Avoid_: Directory Tree, File Catalog, Music Store, Media Browser

### Playback & Queue

**Audio Track**:
可被播放引擎解码回放的远程 WebDAV 音频流或本地存储音频实体。
_Avoid_: Song, Music, Audio File, Virtual Track, Cue Track, Sub-track

**Audiophile Specs**:
音频曲目解码呈现的发烧级规格参数模型，聚合音频格式类型、采样率、位深、比特率及高解析度（Hi-Res）认证状态。
_Avoid_: Audio Resolution, Track Spec String, Bitrate Text

**Audio Quality Badge**:
音频曲目在列表中呈现的轻量音质规格徽章，分为无损、高品质、标准与压缩四级。
_Avoid_: Format Tag, Bitrate Label

**Pure Ephemeral Streaming**:
音频流直接从网络传输至内存环形缓冲区递送解码渲染、本地闪存不落盘任何持久音频文件的纯在线回放模式。
_Avoid_: Disk Cached Playback, Offline Sync

**Lossless Direct Playback**:
本地存储源音频直接通过系统文件流管道送入解码引擎渲染的直读回放模式，无网络开销且零转码损耗。
_Avoid_: Local File Playback, Offline Playback, Device Playback

**Track Metadata**:
音频曲目的描述性标签信息，包含标题、艺术家、专辑名称、曲目编号、时长、歌词及封面缩略图。
_Avoid_: ID3, Audio Info

**Playback Queue**:
当前播放会话中排队待播的有序音频曲目运行时集合，持有明确的队列来源标记，与持久化保存的歌单独立。
_Avoid_: Track List, Song Sequence

**Playback Mode**:
播放队列在切歌时的流转规则，包含列表循环（List Loop）、单曲循环（Single Loop）与随机播放（Shuffle）。
_Avoid_: Repeat Mode, Loop Type, Play Style

**Shuffled Playback Queue**:
随机播放模式下将待播曲目显式重排后的确定性运行时队列，当前播放曲目固定居首。
_Avoid_: Random Walk Queue, Virtual Shuffle, Dynamic Index Map

**Order Mirroring**:
播放队列在随机播放模式下为待播曲目保留的初始未打乱序列镜像，用于在退出随机模式时瞬时无损还原初始队列顺序并精确定位在播曲目。
_Avoid_: Original Queue Backup, Track History Index, Unshuffled List

**Play Next**:
将用户选定的一首或多首音轨插入到当前在播音轨正后方的优先待播操作。
_Avoid_: Insert Track, Priority Queue, Jump Queue

**Queue Origin**:
播放队列的来源拓扑标识，区分目录闭环回放、跨源歌单闭环回放与临时单曲插队。
_Avoid_: Queue Type, Play Source, List Kind

**Playback Session**:
统筹全局播放状态、队列流转、跨源凭据切换与播控指令流转的唯一应用级会话门面。
_Avoid_: Player Controller, Engine Wrapper, Media Manager

### Playlists, Indexing & Session

**Playlist**:
用户创建并持久化保存的自定义音频曲目集合，支持跨多个远程 WebDAV 服务器与本地存储混编引用音轨。
_Avoid_: Song List, Favorite Folder, Collection

**Inactive Playlist Track**:
因所属存储源离线、认证失效、目录解绑或物理文件缺失而在歌单中呈现为置灰不可用状态的音轨条目。
_Avoid_: Disabled Song, Dead Link, Error Item

**Track Inactivity Reason**:
跨源音轨条目因外部环境不可用而呈现置灰状态时的显式领域诊断原因，包含服务器失联、凭据失效与文件缺失。
_Avoid_: Error Code, Fault Flag, Inactive Status

**Track Identity**:
音频曲目在跨源体系中的物理唯一复合标识，由所属存储源与物理路径共同确定，用于跨源排重与状态映射。
_Avoid_: Track UUID, Composite ID, Track Key

**Sync Checkpoint**:
存储源目录增量同步时记录的变更特征快照，用于识别并跳过未变更的目录子树。
_Avoid_: Scan Anchor, Cache Timestamp

**Self-Healing**:
当存储源连通性恢复或底层音频成功解码播放时，自动清除音轨或服务器置灰降级标记的闭环自愈机制。
_Avoid_: Reset Hook, Retry Loop

**Media Track Index**:
本地维护的跨源轻量音轨全量索引快照，作为即时检索与当前源过滤的统一权威数据源。
_Avoid_: Track Mirror, Audio Database, Cache Table, WebDAV Search

**Search Scope**:
媒体库检索的范围边界，支持在当前选定浏览源与包含所有远程和本地存储的全库范围间无缝切换。
_Avoid_: Search Filter Mode, Query Range, Search Scope Type

**Lyric Line**:
按毫秒级时间戳对齐的单行歌词领域模型，聚合主歌词文本与可选双语译文文本。
_Avoid_: Bilingual Lyric Line, Dual Lyrics, Subtitle Pair

**Playback Session State**:
描述播放器当前运行现场的响应式状态，聚合在播曲目、队列、播放模式、底层状态与进度。
_Avoid_: Transient State, UI Holder

**Playback Session Snapshot**:
本地持久化保存的播放会话现场数据快照，用于应用进程冷启动后无缝恢复之前的播放上下文。
_Avoid_: Last Song, Resume Info, History Record

### UI & Expressive Stage

**Primary Navigation Bar**:
应用底部的顶层导航栏，承载「歌单」、「媒体库」与「存储」三大一级目的地。
_Avoid_: Bottom Bar, Tab Bar, Footer Menu

**Docked Mini-Player**:
悬浮驻留在底栏之上的常驻胶囊迷你播放器，承载曲目摘要、进度填充与快捷播控，支持展开为全屏播放器。
_Avoid_: Bottom Player, Small Player, Playback Bar

**Morphing Player**:
统一承载迷你播放器与全屏播放器的单体流体视效容器，在收起与展开状态间平滑无缝形变。
_Avoid_: Dual Player Instance, Dialog Overlay Player, Sheet Modal Player

**Full Player View**:
由常驻迷你播放器无缝形变展开的全屏播放视图，集成专辑封面、进度控制、发烧级音频规格与同步歌词。
_Avoid_: Player Activity, Big Player, Music Detail, Full Player Sheet

**Floating Search Capsule**:
常驻媒体库顶部的自适应展开悬浮搜索胶囊条，集成多源过滤分段滑块与即时检索输入。
_Avoid_: Top Search Bar, Search Header Box, Floating Search Pill Capsule

**Immersive Dark Stage**:
全屏播放器的全天候深邃暗调流光舞台视觉模型，在任何系统主题下均基于封面动态渲染暗调光晕并锁定高对比纯白前景。
_Avoid_: Shallow Player White Bleed, Fixed Dark Player, Non-Dynamic Stage

**Dynamic Artwork Palette**:
基于在播音轨专辑封面提取的主色调实时推导应用全域主题色与全屏氛围光晕的动态色彩体系。
_Avoid_: Static Theme, Wallpaper Color Only, Hardcoded Brand Fallback

**Monochrome Safeguard**:
当在播曲目专辑封面为黑白或低饱和度灰阶时，自动回退至中性灰阶以防止界面产生杂色偏色的视觉保护机制。
_Avoid_: Grayscale Color Leak, Red Tinted Neutral
