#ifndef LIFELOG_CONFIG_H
#define LIFELOG_CONFIG_H

// 摄像头型号（AI-Thinker 引脚布局，兼容 OV2640 / OV3660 两种传感器）
#define CAMERA_MODEL_AI_THINKER

// 功能开关
#define WITH_SLEEP                    // Deep Sleep 省电
// #define WITH_EVIL_CAM_PWR_SHUTDOWN // 【已废弃】简化硬件去掉 MOSFET，不再切断摄像头电源
#define WITH_BLE                      // BLE 文件传输
#define WITH_VIDEO                    // MJPEG 视频录制

// 故意不启用:
// #define WITH_FLASH                 // GPIO4 与 SD DATA1 冲突
// #define WITH_SD_4BIT              // 仅用 1-bit 模式
// #define WITH_CAM_PWDN             // 不修改 PCB
// #define WITH_SETUP_MODE_BUTTON    // 首次开机自动进入设置模式

// ── 板载指示灯与闪光灯 ──
#define WITH_STATUS_LED              // GPIO33 红色指示灯（状态反馈）
#define STATUS_LED_PIN         33    // 与电池 ADC 共用引脚，分时复用
#define WITH_AUTO_FLASH              // 光线不足时自动开启闪光灯 (GPIO4)
#define FLASH_GPIO_NUM         4     // 摄像头闪光灯 LED
#define FLASH_DARK_THRESHOLD   150    // AEC 曝光行数阈值，超过即判定为暗光（App 可覆写）

// Deep Sleep 唤醒源
#define SLEEP_WAKEUP_TIMER

// BLE 配置
#define BLE_DEVICE_NAME_DEFAULT  "lifelog-cam"
#define BLE_ADV_TIMEOUT_DEFAULT  30000   // 广播窗口 (ms) — 30s，配合 PB0A 踢狗机制
#define BLE_ADV_INTERVAL_DEFAULT 100     // 广播间隔 (ms)

// PB0A 充放电芯片防关机踢狗
// PB0A 负载 < 50mA 持续 > 32s 自动断电。Deep Sleep 时 ESP32 只吃 4-6mA，
// 因此每隔 KICK_INTERVAL_MS 唤醒一次（启动电流 ~70mA 踢回 PB0A 计时器）
#define KICK_INTERVAL_MS        25000   // 踢狗间隔 (ms)，< 32s PB0A 阈值
#define KICK_MIN_IDLE_MS        30000   // 空闲低于此值不踢狗，直接睡满（< 32s 安全）

// 视频录制默认参数
#define VIDEO_DURATION_DEFAULT   5000    // 视频时长 (ms)
#define VIDEO_RESOLUTION_DEFAULT FRAMESIZE_QXGA   // 2048×1536 — OV3660 上限（OV2640 仅支持到 UXGA）
#define VIDEO_QUALITY_DEFAULT    12      // JPEG 质量 (10-63, 越小画质越好文件越大)
#define VIDEO_FPS_DEFAULT        3       // 帧率

// 拍照间隔默认 (ms)
#define INTERVAL_DEFAULT         300000  // 5 分钟

// 电池监测（ESP32 ADC，GPIO33 在 ESP32-CAM 上常用，按实际硬件改）
#define BATTERY_ADC_PIN         33      // ADC1_CH5
#define BATTERY_ADC_ATTEN       ADC_11db  // 0~3.9V 量程
#define BATTERY_VOLTAGE_DIVIDER 2.0f    // 分压比（100k+100k = 二分之一）
#define BATTERY_MIN_VOLTAGE     3.3f    // LiPo 放电截止电压 ≈ 0%
#define BATTERY_MAX_VOLTAGE     4.2f    // LiPo 满电电压 ≈ 100%

// SD 卡
#define SD_INDEX_FILE            "/sdcard/index.txt"
#define SD_CLIP_TS_FILE          "/sdcard/clip_ts.txt"  // 每段视频的录制时刻 (esp_timer ms)

// LED 控制（lifelog.ino 定义，ble_service 也可调用）
void led_on();
void led_off();

#endif
