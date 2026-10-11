# 微调 (WeiTiao)

[English](README.en.md) · **[📥 点击直接下载安装包 (WeiTiao-v1.3.9.apk · 1.6 MB)](https://github.com/WEIHEWU/WeiTiao/raw/main/releases/WeiTiao-v1.3.9.apk)** · [Releases 页面](https://github.com/WEIHEWU/WeiTiao/releases)

一个基于 `libxposed API 102` 与 `Root` 的三星 One UI 增强模块，集成 **应用商店跨区解锁（CSC/SIM 伪装）**、**硬件机型伪装**、**微信运动步数与消息防撤回**、**亮屏快充** 以及 **USB PD 应用旁路供电**。

当前版本：`1.3.9`（`versionCode 19`）。

> 仅在三星 One UI 8.5 上测试通过，其他版本不保证能够使用。

## 核心特性

- **应用商店跨区解锁（CSC + SIM 联合伪装）**：
  - 内置 6 大精选预设（中国香港、美国公开版、欧洲通用版、中国台湾、韩国官方版、中国大陆）及全球 80 个国家/运营商预设检索库；
  - 仅对应用商店（Galaxy Store / 主题商店 / Play 商店 / 三星账号判区组件）返回目标 CSC 与 SIM 运营商信息，**绝不注入系统框架 (`android`) 或电话服务 (`com.android.phone`)，确保物理 SIM 卡与系统基带 100% 原生稳定**；
  - 支持一键清理商店缓存并立即切换区域，支持一键恢复出厂原生状态。
- **硬件机型伪装**：
  - 内置 126 款主流多品牌旗舰机型，支持自定义型号名称与指定目标应用生效。
- **微信增强（LSPosed Hook）**：
  - **微信运动步数修改**：基于单调递增绝对锚点偏移模型，支持自动递增与固定倍率模式、每日封顶上限、每日停止增长时间，跨天与重启平滑衔接，杜绝负跳变；
  - **微信消息防撤回**：拦截对方撤回指令，保留文字、图片、语音及视频文件；
  - **一键重启微信**：通过 Root 强制停止并自动重新拉起微信。
- **亮屏快充（纯 Root 直控，无需 LSPosed）**：
  - 连接充电器且亮屏时自动屏蔽三星温控限流节点，维持高功率快充；拔线、关闭或电池温度达到 `44.0°C` 安全熔断线时立即恢复 `0755` 原厂温控。
- **USB PD 应用旁路供电（纯 Root 直控，无需 LSPosed）**：
  - 前台运行指定游戏/应用且电量达到设定阈值时，自动开启旁路供电直接给主板供电，大幅降低边充边玩发热。
- **极致轻量化**：
  - 零第三方冗余依赖，Release APK 仅 **1.6 MB**，O(1) 哈希属性查表，零轮询唤醒。

## 推荐 LSPosed 作用域

启用模块后，LSPosed 会自动勾选以下推荐作用域（**请勿勾选 `android` 系统框架或系统设置**）：

- `com.sec.android.app.samsungapps`（三星应用商店 Galaxy Store）
- `com.samsung.android.themestore` / `com.samsung.android.themecenter`（三星主题商店）
- `com.android.vending`（Google Play 商店）
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

