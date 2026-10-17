# 微调 (WeiTiao)

[English](README.en.md) · **[点击直接下载安装包 (WeiTiao-v1.5.0.apk · 1.6 MB)](https://github.com/WEIHEWU/WeiTiao/raw/main/releases/WeiTiao-v1.5.0.apk)** · [Releases 页面](https://github.com/WEIHEWU/WeiTiao/releases)

一个基于 `libxposed API 102`、`Root` 与 **`miuix` (`0.8.8`)** 界面的三星 One UI 增强模块，集成 **底层 Native COW 系统属性伪装引擎**、**应用商店跨区解锁（CSC/SIM 伪装）**、**硬件机型伪装（三年内纯血旗舰库）**、**微信运动步数与消息防撤回**、**亮屏快充** 以及 **USB PD 应用旁路供电**。

当前版本：`1.5.0`（`versionCode 30`）。

> 全面测试并官方适配三星 One UI 8.5 / One UI 9（Android 17 / API 37），兼容搭载 One UI 的 Galaxy 全系机型。

## 核心特性

- **Miuix 原生质感与物理交互**：
  - 基于 `top.yukonga.miuix.kmp:miuix:0.8.8` 全面构建，支持可折叠大标题导航栏、物理弹性越界回弹 (`overScrollVertical`)、G2 连续曲率圆角 (`smoothRounding`) 与 `folmeSpring` 物理弹簧弹窗动画。
- **底层 Native COW 系统属性改写引擎 (NativePropCow)**：
  - 彻底攻克 iPhone/Apple 及全品牌机型在加固防篡改应用（如腾讯乐固/加固、酷安、QQ 等）中失效的行业难题；
  - 精准解析进程 `/proc/self/maps`，通过 `mmap(MAP_PRIVATE | MAP_FIXED)` 将 `/dev/__properties__/*` 共享内存页转换为私有写时复制（COW）物理页，并利用直接内存指针及原子 `serial` 计数重写底层属性；
  - 彻底消除 Native libc（`__system_property_get`）与 Java 层（`Build.*`）不一致引起的防篡改回退，毫秒级注入且 100% 稳定防封。
- **机型伪装生效应用保存自动调起与 Root 作用域静默同步**：
  - 在机型伪装生效应用列表保存修改时，若拥有 Root 权限则自动读取 LSPosed SQLite 数据库静默补全模块作用域，免去手动去 LSPosed 勾选的繁琐步骤；无 Root 时无缝直达 LSPosed 作用域管理页面。
- **应用商店跨区解锁（CSC + SIM 联合伪装）**：
  - 内置 6 大精选预设（中国香港、美国公开版、欧洲通用版、中国台湾、韩国官方版、中国大陆）及全球 80 个国家/运营商预设检索库；
  - 仅对应用商店（Galaxy Store / 主题商店 / 三星账号判区组件）返回目标 CSC 与 SIM 运营商信息，**绝不注入系统框架 (`android`) 或电话服务 (`com.android.phone`)，确保物理 SIM 卡与系统基带 100% 原生稳定**；
  - 支持一键清理商店缓存并立即切换区域，支持随时一键关闭跨区伪装并恢复物理 SIM 原生识别。
- **硬件机型伪装（三年纯血旗舰库）**：
  - 严格执行 **发布时间不超过 3 年（2023.10 - 2026.10）** 与 **顶级纯血旗舰（Pro / Ultra / Fold / Flip / 机皇）** 筛选准则；
  - 深度收录 Apple（含 iPhone Duo、iPhone 18/17/16 Pro Max）、OnePlus 16、Xiaomi 17、Galaxy S26/S25/S24、Pixel 10 Pro Fold、HUAWEI Mate XT 等主流顶尖旗舰，支持自定义型号并实时展示库容量。
- **微信增强（LSPosed Hook）**：
  - **微信运动步数修改**：基于单调递增绝对锚点偏移模型，支持自动递增与固定倍率模式、每日封顶上限、每日停止增长时间，跨天与重启平滑衔接，杜绝负跳变；
  - **微信消息防撤回**：拦截对方撤回指令，保留文字、图片、语音及视频文件；
  - **一键重启微信**：通过 Root 强制停止并自动重新拉起微信。
- **亮屏快充（纯 Root 直控，无需 LSPosed）**：
  - 连接充电器且亮屏时自动屏蔽三星温控限流节点，维持高功率快充；拔线、关闭或电池温度达到 `44.0°C` 安全熔断线时立即恢复 `0755` 原厂温控。
- **USB PD 应用旁路供电（纯 Root 直控，无需 LSPosed）**：
  - 前台运行指定游戏/应用且电量达到设定阈值时，自动开启旁路供电直接给主板供电，大幅降低边充边玩发热。
- **极致轻量化**：
  - Release APK 仅 **1.6 MB**，O(1) 哈希属性查表，零轮询唤醒。

## 推荐 LSPosed 作用域

启用模块后，LSPosed 会自动勾选以下推荐作用域（**请勿勾选 `android` 系统框架或系统设置**）：

- `com.sec.android.app.samsungapps`（三星应用商店 Galaxy Store）
- `com.samsung.android.themestore` / `com.samsung.android.themecenter`（三星主题商店）
- `com.osp.app.signin` / `com.samsung.android.samsungaccount` / `com.samsung.android.authfw`（三星账号判区服务）
- `com.tencent.mm`（微信）

> **注意**：「亮屏快充」与「应用旁路供电」通过 Root 直接控制硬件节点，**不需要**勾选任何 LSPosed 作用域，但建议将「微调」设为系统电池优化「不受限制」以防后台服务被深度休眠拦截。

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk 37`）。

```bash
./gradlew :app:assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/app-release.apk`。
签名从 `local.properties` 读取（`signing.storeFile` 等字段），未配置时自动回退到 `~/.android/debug.keystore`，克隆仓库后可直接构建。

## 免责声明

本模块仅用于个人学习与研究。修改系统属性或温控节点可能带来潜在风险，请自行评估并承担相应后果。

## License

GNU General Public License v3.0 (GPL-3.0-only)。详见 [LICENSE](LICENSE)。
扩展机型模板来源及许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

