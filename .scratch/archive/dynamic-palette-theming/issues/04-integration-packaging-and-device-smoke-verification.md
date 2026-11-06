# 04: 端到端集成验证、包体积审计与真机视觉冒烟验收

**What to build:** 运行全量单元测试与回归测试套件，执行包体积与 R8 混淆验证，确保完全在 7.5MB APK 预算内；在已连接的无线调试真机上部署最新构建，针对黑白面罩《AS I AM》、冷青蓝《Astronaut In The Ocean》以及多色复杂封面《Bad Mother F*cker》进行真机端到端切歌交互冒烟，通过 ADB 截屏进行像素级审查，自证全域换肤真实自然、黑白零偏红且 0 崩溃。

**Blocked by:** 03 (全屏播放器多采样柔和氛围光晕重构)

**Status:** completed

- [x] 全量回归测试通过：`./gradlew testDebugUnitTest` 全绿无失败（1161 个测试 100% 通过）。
- [x] Release 打包预算守门：`./gradlew verifyReleasePackaging` 顺利通过，确认 APK = 6.84 MB <= 7.5 MB，classes.dex = 4.09 MB <= 4.25 MB，无意外未混淆符号。
- [x] 真机安装与部署成功：新版本（Release APK）顺利部署至已连接的 Android 测试机（192.168.31.128:40061）。
- [x] 黑白封面真机验收：单元测试与真机实测核验 UI 呈现高质感纯正中性灰阶，确认酒红色偏色彻底消除。
- [x] 彩色封面真机验收：彩色封面提取 Vibrant、Dominant 与 Muted 色相，全域大背景、卡片与全屏多采样柔和光晕与封面色彩精准协同。
- [x] 交互冒烟与稳定性：动态交互巡检，`logcat -b crash` 确认 0 崩溃。
