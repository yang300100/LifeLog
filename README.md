# LifeLog

一款可穿戴的别针式生活记录摄像头。定时拍摄短视频片段，通过 BLE 同步到 Android 手机，利用云端 AI 分析行为并生成自然语言日报。同时提供 AI 陪伴模式，支持个性化风格照片生成。

A wearable lifelog camera in a brooch form factor. Captures short video clips at regular intervals, syncs to an Android phone via BLE, and generates natural-language daily journals using cloud AI — including an AI Companion mode for personalized stylized photo generation.

## 系统架构 / Architecture

```
ESP32-CAM （固件）             Android App               云端 AI
+------------------+       +-------------------+       +----------+
| OV2640 摄像头     |       | BLE 同步服务       |       | Kimi API |
| MJPEG 录制        | --BLE--> 文件接收          | --HTTP--> （文本）  |
| SD 卡存储         |       | 时间轴 UI          |       | Seedream |
| Deep Sleep 循环   |       | AI 分析引擎        |       | （图像）  |
+------------------+       | 陪伴模式           |       +----------+
                           +-------------------+
                                  |
                           +-------------------+
                           | YOLOv8n (NCNN)    |
                           | 端侧人像分割        |
                           +-------------------+
```

## 核心功能 / Key Features

- **定时拍摄 / Interval Capture** -- ESP32-CAM + OV2640 每 2 分钟录制 5 秒 MJPEG 视频片段。
- **BLE 文件传输 / BLE File Transfer** -- 通过 BLE GATT 将录制的片段传输到 Android 手机，支持自动文件索引。
- **AI 日报生成 / AI Daily Reports** -- 从视频中提取关键帧，通过 Kimi API 分析视觉内容，生成结构化自然语言日报。
- **AI 陪伴模式 / AI Companion Mode** -- 端侧 YOLOv8n 分割检测人物，调用 Seedream API 进行局部重绘，将用户融入风格化场景。
- **RPG 实时模式 / RPG Realtime Mode** -- 基于角色的互动模式，实时活动记录与 AI 驱动叙事响应。
- **3D 打印外壳 / 3D Printed Enclosure** -- OpenSCAD 设计的定制外壳，带挂绳孔，支持标准 FDM 打印机。

## 仓库结构 / Repository Structure

```
firmware/lifelog/         ESP32-CAM 固件 (Arduino / C++)
    lifelog.ino             主程序，包含状态机逻辑
    camera.cpp/h            OV2640 拍摄与 MJPEG 编码
    sd_storage.cpp/h        SD 卡读写与文件索引
    ble_service.cpp/h       NimBLE GATT 服务端，负责文件传输
    configuration.cpp/h     运行时设置持久化
    config.h                编译期功能开关与默认参数
    camera_pins.h           AI-Thinker ESP32-CAM 引脚映射

android/                  Android App (Kotlin / Jetpack Compose)
    app/src/main/java/com/lifelog/camera/
        ai/                 AI 分析、关键帧提取、陪伴流水线
        ble/                BLE 文件同步客户端
        data/               Room 数据库、数据仓库、数据模型
        di/                 Hilt 依赖注入模块
        ui/timeline/        时间轴主页 & 陪伴画廊
        ui/report/          日报生成与查看
        ui/realtime/        RPG 角色实时模式
        ui/settings/        API 密钥配置与偏好设置
        util/               崩溃日志、图像工具

enclosure/                OpenSCAD 外壳设计
    Lifelog_Case.scad       3D 打印两片式外壳

prototype/                Python 探索性脚本
    YOLO 分割、Seedream 融合测试、
    陪伴流水线原型验证
```

## 硬件规格 / Hardware

| 组件 / Component | 规格 / Specification |
|------------------|---------------------|
| MCU | ESP32 (AI-Thinker ESP32-CAM) |
| 摄像头 / Camera | OV3660 (2048×1536 QXGA MJPEG) 或 OV2640 (1600×1200 UXGA) |
| 存储 / Storage | MicroSD 卡 (1-bit SD 模式) |
| 电池 / Battery | 3.7V 800mAh 锂聚合物 (402030) |
| 电源模块 / Power | IP5306 充放电一体模块（充电 + 升压5V + 电池保护） |
| 外壳 / Enclosure | 3D 打印 PLA/PETG，45x60x15mm |

### 接线说明 / Wiring

```
IP5306 模块        ESP32-CAM
  5V  ───────────→  5V
  GND ───────────→  GND
  BAT+ ─────────→  电池红线
  BAT- ─────────→  电池黑线
```

仅需 4 根线，全部使用成品模块，零贴片焊接。IP5306 模块通过 USB-C 充电（兼容 C-to-C 线缆），自带 4 级电量指示。

### 引脚约束 / Pin Constraints

- **GPIO4** -- 与 SD 卡 DATA1 冲突，LED 闪光灯永久禁用。
- **GPIO12** -- Strapping pin，启动时必须保持低电平或浮空。
- Deep Sleep 电流约 4-6mA（含 IP5306 升压模块静态功耗）。

## 固件编译 / Firmware Build

在 Arduino IDE 2.x 中打开 `firmware/lifelog/` 项目。

- **开发板 / Board**: AI-Thinker ESP32-CAM
- **分区方案 / Partition Scheme**: No OTA (Large APP) -- 至少 3MB 程序空间
- **波特率 / Baud Rate**: 115200
- **依赖库 / Required Libraries**: `esp_camera` (Espressif Systems)、NimBLE

### 编译开关 / Build Flags

在 `config.h` 中定义：

| 开关 / Flag | 用途 / Purpose |
|-------------|---------------|
| `WITH_SLEEP` | 拍摄间隔期间进入 Deep Sleep 省电 |
| `WITH_EVIL_CAM_PWR_SHUTDOWN` | 休眠前切断摄像头电源 (GPIO32 拉高) |
| `WITH_BLE` | 启用 BLE 广播与 GATT 文件传输 |
| `WITH_VIDEO` | 启用 MJPEG 视频录制到 SD 卡 |

**有意禁用的开关**: `WITH_FLASH`、`WITH_SD_4BIT`、`WITH_CAM_PWDN`、`WITH_SETUP_MODE_BUTTON`、`WITH_EVIL_CAM_PWR_SHUTDOWN`（简化硬件无需外部 MOSFET）。

### 烧录步骤 / Flashing

1. 连接 USB 转 TTL 适配器：RX-TX、TX-RX、GND-GND、5V-5V。
2. 将 IO0 与 GND 短接进入下载模式。
3. 上电，从 Arduino IDE 上传。
4. 移除 IO0-GND 短接，按复位键运行。

## Android App 编译 / Android App Build

在 Android Studio 中打开 `android/` 目录。

- **最低 SDK / Min SDK**: 26 (Android 8.0)
- **目标 SDK / Target SDK**: 34
- **NDK**: 30.0.14904198 (arm64-v8a)
- **主要依赖 / Key Dependencies**: Jetpack Compose, Hilt, Room, OkHttp, NCNN (native)

```bash
cd android
./gradlew assembleDebug
```

## 配置 / Configuration

首次开机时若 SD 卡无配置文件，设备进入设置模式并持续 BLE 广播，通过 Android App 进行配置：

- 拍摄间隔（默认 120 秒）/ Capture interval (default: 120s)
- 视频时长（默认 5 秒）/ Video duration (default: 5s)
- 视频分辨率与质量 / Video resolution and quality
- BLE 设备名称 / BLE device name

On first boot without a configuration file on the SD card, the device enters setup mode and broadcasts BLE continuously. Use the Android app to configure the parameters above.

## AI 后端 / AI Backend

Android App 支持在设置中配置多种 AI 后端：

| 用途 / Use | 后端 / Backend | 说明 / Notes |
|------------|---------------|-------------|
| 文本分析 / Text Analysis | Kimi API | OpenAI 兼容接口，用于场景分析、日报生成、陪伴提示词 |
| 图像生成 / Image Generation | Seedream API | 用于陪伴模式的局部重绘 |
| 端侧推理 / On-Device | YOLOv8n (NCNN) | 陪伴流水线中的人像分割 |

API 密钥和接口地址在 App 运行时通过设置界面配置，存储在 Android SharedPreferences 中，源代码不含任何密钥。

API keys and endpoint URLs are configured at runtime via the app settings and stored in Android SharedPreferences. No keys are included in the source code.

## 开源协议 / License

本项目仅供个人使用，保留所有权利。

This project is for personal use. All rights reserved.
