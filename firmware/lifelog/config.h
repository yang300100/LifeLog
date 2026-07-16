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

// Deep Sleep 唤醒源
#define SLEEP_WAKEUP_TIMER

// BLE 配置
#define BLE_DEVICE_NAME_DEFAULT  "lifelog-cam"
#define BLE_ADV_TIMEOUT_DEFAULT  90000   // 广播窗口 (ms) — QXGA 极限画质需要较长的传输时间
#define BLE_ADV_INTERVAL_DEFAULT 100     // 广播间隔 (ms)

// 视频录制默认参数
#define VIDEO_DURATION_DEFAULT   5000    // 视频时长 (ms)
#define VIDEO_RESOLUTION_DEFAULT FRAMESIZE_QXGA   // 2048×1536 OV3660极限 (QVGA=320×240, VGA=640×480, SVGA=800×600, XGA=1024×768, SXGA=1280×1024, UXGA=1600×1200, QXGA=2048×1536)
#define VIDEO_QUALITY_DEFAULT    10      // JPEG 质量 (10-63, 越小画质越好)
#define VIDEO_FPS_DEFAULT        3       // 帧率 — QXGA 下 ESP32 硬件编码极限约 3fps

// 拍照间隔默认 (ms)
#define INTERVAL_DEFAULT         300000  // 5 分钟 — 极限画质拉长间隔保续航

// SD 卡
#define SD_INDEX_FILE            "/index.txt"

#endif
