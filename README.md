# DuoFold-Bixi

小米 MIX Flip 2（`bixi`）专用的折叠磨砂效果：折起手机时，**内屏上半部分**变成透视压缩 + 模糊的圆角卡片，**下半部分保持原生画面不动**。桌面、第三方应用、锁屏都生效。

> 版本 0.2 · 包名 `dev.duofold` · 基于 [DuoFold-Android](https://github.com/jcx396905-gif/DuoFold-Android)（MIT, Copyright (c) 2026 jcx）改造。

## 效果说明

- 只处理**内屏上半屏**；下半屏原样输出，两半使用同一帧，没有时间差
- 折叠角度由**铰链角度传感器**（`android.sensor.hinge_angle`）驱动，不是陀螺仪
- **2° 为零点**：0–2° 完全无效果，图层不挂载，触屏不会受影响
- 角度经 **60ms 低通**平滑，消除传感器 1° 一档的台阶感
- 模糊区是**圆角卡片**（超椭圆 / G2 曲率），折叠时整体缩小、角外为黑
- 锁屏状态下同样生效；屏幕熄灭才停止

## 前提

| 项目 | 要求 |
|---|---|
| 系统 | Android 14 及以上 |
| 硬件 | 折叠屏，且有铰链角度传感器（直板机无效） |
| Shizuku | 必需，用于 shell 侧采集画面；**每次重启手机后要重新启动一次** |

## 使用

1. 安装 APK，打开 Shizuku 并按提示启动服务
2. 应用内依次点击 **01 连接并授权 Shizuku** → **02 开启屏幕效果辅助服务** → **02b 允许后台运行** → **03 开启全局效果**
3. 折起手机上半屏即可看到效果，展平后自动恢复原始画面与正常触屏

## 可调参数

| 参数 | 说明 |
|---|---|
| 最大倾斜角度 | 30° / 60° / 90° / 120° / 150°，物理折到该角度时形变达到最大 |
| 空间强度 | 整体效果强度 |
| 采集分辨率 | 720 / 960 / 原生 1224，下次折起生效 |
| 高度变化 | 折起来后卡片变短（+）或变长（−） |
| 左右边倾斜 | 上边张开 / 竖直 / 收拢 |
| 圆角半径 | 屏幕短边占比 2%–25% |
| 圆角圆度 | 2.0–6.0，越小越圆 |
| 按应用开启 | 默认全部应用生效；可勾选「例外」排除某些应用，或切换为「仅勾选的应用生效」。锁屏与本应用始终生效 |

## 编译

需要 JDK 17、Android SDK 35、Gradle 8.7：

```bash
./gradlew :app:assembleStandardDebug
```

产物：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`

`local.properties` 中的 `sdk.dir` 需指向本机 SDK 路径。

## 已知限制

- 效果图层存在期间不接管触摸，但展平后会有一帧左右的重建延迟
- 画面来自系统镜像，比真实屏幕晚 1–2 帧
- APK 使用 debug 签名，长期分发建议自行生成正式密钥

## 许可

本项目基于 [DuoFold-Android](https://github.com/jcx396905-gif/DuoFold-Android) 修改，遵循 MIT 许可，原作者版权声明见 [LICENSE](LICENSE)。

Modified work based on DuoFold-Android (MIT, Copyright (c) 2026 jcx). Changes: hinge-angle driven, upper-half only rendering, 2° zero point with 60 ms low-pass, squircle blur card, lock-screen support, gyroscope code removed.
