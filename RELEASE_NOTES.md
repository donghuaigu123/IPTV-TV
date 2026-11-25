# IPTV TV v1.0.0 — 电视大屏 / 遥控器优化 / Android 4.4+ 版

手机版功能的完整复刻，专为电视大屏与遥控器重新设计，最低支持 Android 4.4。

## 功能（完整复刻手机版）
- 全网直播源采集（20+ 内置源 / 多镜像回退 / 去重整理）
- 有效性检测 + 自动清除无效源
- 七格式导出（M3U / M3U8 / TXT / JSON / TVBox / XSPF / CSV）
- 全功能播放器（ExoPlayer 2.x：倍速 / 音轨 / 字幕 / 画面比例 / 画中画）
- 频道列表（搜索 / 分组 / 三来源分类 / 多订阅 ①②③ 编号）
- 直播源导入（网址 / 文件 / 剪贴板）+ TVBox 源导入（订阅二次展开）
- 频道自行删除 / 批量管理 / 回收站
- 来源隔离（重新采集不影响其他来源频道）

## 电视与遥控器优化
- 左侧导航栏 + 横屏大屏布局；大字号（18sp）/ 大按钮（56dp）
- 两列频道网格，一屏浏览更多频道
- 焦点高亮：卡片 / 按钮 / Chip 聚焦时描边 + 放大动画
- 主界面：数字键 1/2/3 快速切页，返回键双击退出
- 播放器：数字键选台、左右快进后退、上下音量、频道+/-、媒体键、菜单键、信息键、返回键分层处理

## Android 4 兼容
- minSdk 19（Android 4.4）；targetSdk 28
- 全部依赖降级到支持 API 19；播放器用 ExoPlayer 2.18 替代 Media3
- 启用 Java 8 desugaring + MultiDex
- Android 4.4 无 SAF 目录选择时，导出降级到应用外部存储 Download/IPTV

## 验证情况
- Kotlin / Java 编译通过（仅历史弃用 API 警告，无错误）
- APK 经 aapt 确认 sdkVersion=19 / targetSdkVersion=28
- 无 Media3 残留；OkHttp 3.x getter 调用、ExoPlayer 2.x 构造均已适配
- 签名有效，versionCode 1 / versionName 1.0.0-tv

## 安装
下载 `IPTV-TV-v1.0.0.apk`，在 Android 4.4+ 电视/盒子安装（需允许安装未知来源应用）。与手机版可共存（不同包名）。

> 沙箱无真机 / 模拟器，以上以编译、APK 元信息与 API 兼容性校验为准，建议真机复测遥控器与播放。
