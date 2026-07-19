// LifeLog Camera — 随身行为记录摄像头固件
//
// 状态机:
//   冷启动 → 检测 SD 卡配置文件
//     ├── 无 camera.cfg → 设置模式 (BLE 持续广播，等手机配置)
//     └── 有 camera.cfg → 正常循环:
//           Wake → Init Camera → Capture Video → SD 存储
//           → BLE 广播窗口 → Camera Off → Deep Sleep
//
// Phase 1+2: 录制循环 + BLE GATT 文件传输

#include "config.h"
#include "camera_pins.h"
#include "camera.h"
#include "sd_storage.h"
#include "configuration.h"
#include "ble_service.h"
#include "SD_MMC.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "driver/rtc_io.h"
#include "driver/gpio.h"
#include <stdio.h>

// ── 板载状态 LED ──
// ESP32-CAM 红色 LED（GPIO33）：低电平点亮（阴极接 GPIO，阳极接 3.3V）
#ifdef WITH_STATUS_LED
void led_on()  { pinMode(33, OUTPUT); digitalWrite(33, LOW);  }  // LOW = 亮
void led_off() { pinMode(33, OUTPUT); digitalWrite(33, HIGH); }  // HIGH = 灭
void led_init() {
    gpio_set_direction(GPIO_NUM_33, GPIO_MODE_OUTPUT);
    gpio_set_level(GPIO_NUM_33, HIGH);  // 初始灭
}
#else
void led_on()  {}
void led_off() {}
void led_init() {}
#endif

// RTC 内存变量 — Deep Sleep 唤醒后保留
RTC_DATA_ATTR uint32_t boot_count = 0;
RTC_DATA_ATTR uint32_t restart_count = 0;  // 防止无限重启循环

AppConfig g_cfg;

// ── 进入 Deep Sleep ──
static void enter_deep_sleep() {
    Serial.printf("[SLEEP] 休眠 %u ms (%.1f min)\n",
                  g_cfg.interval, g_cfg.interval / 60000.0f);

    // 防御性检查：如果间隔太短（<10秒），可能是配置单位用错了，回退到60秒
    uint64_t sleep_us = (uint64_t)g_cfg.interval * 1000;
    if (sleep_us < 10000000ULL) {
        Serial.printf("[SLEEP] 休眠间隔 %llu µs 过短，回退到 60 秒\n", sleep_us);
        sleep_us = 60 * 1000000ULL;
    }

    // 释放摄像头资源再休眠（简化硬件无外部 MOSFET，靠 deinit 降低功耗）
    camera_power_off();

    // SD 卡卸载前多等片刻：sync_done 后的 index.txt 写入 + 文件删除
    // 可能 SD 卡内部缓存还没落盘 CPU 就断电了，导致 last_synced 丢进度
    delay(200);

    // SD 卡卸载
    SD_MMC.end();

    // 配置唤醒定时器
    esp_err_t wakeup_err = esp_sleep_enable_timer_wakeup(sleep_us);
    if (wakeup_err != ESP_OK) {
        Serial.printf("[SLEEP] 唤醒定时器配置失败: 0x%x，回退到 120 秒\n", wakeup_err);
        esp_err_t fallback_err = esp_sleep_enable_timer_wakeup(120 * 1000000ULL);
        if (fallback_err != ESP_OK) {
            Serial.printf("[SLEEP] 回退也失败: 0x%x，5 秒后尝试重启\n", fallback_err);
            restart_count++;
            if (restart_count > 5) {
                Serial.println("[SLEEP] 连续重启超过5次，进入深度休眠60秒...");
                esp_sleep_enable_timer_wakeup(60 * 1000000ULL);
                esp_deep_sleep_start();
            }
            delay(5000);
            esp_restart();
        }
    }

    Serial.flush();
    esp_deep_sleep_start();
}

// ── 录制并存储一段视频 ──
static bool record_clip(uint16_t clip_id) {
    char filepath[32];
    sd_next_filename(clip_id, filepath, sizeof(filepath));

    led_on();  // 录制中红灯常亮
    Serial.printf("[REC] 录制 %s (%u ms @ %u fps)...\n",
                  filepath, g_cfg.video_duration, g_cfg.video_fps);

    int frames = camera_capture_video(filepath,
                                      g_cfg.video_duration,
                                      g_cfg.video_fps);

    if (frames < 0) {
        Serial.printf("[ERR] 录制失败 code=%d，删除残缺文件\n", frames);
        // 清理 SD 卡上已部分写入的残缺文件
        remove(filepath);
        return false;
    }

    Serial.printf("[REC] 完成: %d 帧\n", frames);
    led_off();

    // 记录录制时刻（esp_timer 跨 deep sleep 连续走时，
    // BLE 文件列表用它算相对年龄，手机端还原真实录制时间）
    sd_clip_ts_write(clip_id, (uint64_t)(esp_timer_get_time() / 1000));

    // 更新 SD 卡索引
    if (!sd_index_update_last_id(clip_id)) {
        Serial.println("[ERR] 更新 index.txt 失败");
        return false;
    }

    return true;
}

// ── 设置模式 ──
static void enter_setup_mode() {
    Serial.println("[SETUP] 首次启动 — 自动生成默认配置...");

    config_save("/sdcard/camera.cfg", g_cfg);
    Serial.println("[SETUP] 已生成默认 camera.cfg");
    Serial.printf("[SETUP] 2 秒后开始正常循环...\n");
    delay(2000);
}

// ── 一次性清理旧文件 ──
static void cleanup_old_files() {
    // 检查清理标记文件，已清理过则跳过
    FILE *flag = fopen("/sdcard/.cleanup_done", "r");
    if (flag) { fclose(flag); return; }

    Serial.println("[CLEAN] 检测到首次启动，清理 SD 卡旧文件...");
    // 删除所有旧 clip 文件
    for (uint16_t id = 1; id <= 100; id++) {
        char fname[32];
        sd_next_filename(id, fname, sizeof(fname));
        if (remove(fname) == 0) {
            Serial.printf("  已删除: %s\n", fname);
        }
    }
    // 重置索引
    remove("/sdcard/index.txt");
    remove("/sdcard/camera.cfg");
    remove("/sdcard/clip_ts.txt");
    // 写入清理标记
    flag = fopen("/sdcard/.cleanup_done", "w");
    if (flag) { fprintf(flag, "1\n"); fclose(flag); }
    Serial.println("[CLEAN] 清理完成!");
}

// ── 主设置 ──
void setup() {
    Serial.begin(115200);
    delay(100);  // 等待串口稳定

    boot_count++;
    led_init();
    Serial.println();
    Serial.println("╔══════════════════════════════╗");
    Serial.println("║   LifeLog Camera v1.0       ║");
    Serial.println("╚══════════════════════════════╝");
    Serial.printf("  启动次数: %u\n", boot_count);

    // 1. 初始化 SD 卡
    Serial.print("[INIT] SD 卡... ");
    if (!sd_init()) {
        Serial.println("失败! 重试一次...");
        delay(500);
        if (!sd_init()) {
            Serial.println("SD 卡初始化失败，进入 Deep Sleep 60 秒后重试");
            esp_sleep_enable_timer_wakeup(60 * 1000000ULL);
            esp_deep_sleep_start();
            return;  // unreachable
        }
    }
    Serial.println("OK");
    Serial.printf("       剩余空间: %u MB\n", sd_free_mb());

    // 1.5 一次性清理旧 resolution 的大文件
    cleanup_old_files();

    // 2. 加载配置（文件不存在时自动使用默认值）
    if (!config_load("/sdcard/camera.cfg", g_cfg)) {
        // 首次启动，自动生成默认配置
        enter_setup_mode();
    }

    Serial.printf("[CFG] 模式=%s, 间隔=%us, 视频=%ums, Q=%u, 分辨率=%s, fps=%u\n",
                  g_cfg.capture_mode,
                  g_cfg.interval / 1000,
                  g_cfg.video_duration,
                  g_cfg.video_quality,
                  camera_framesize_name(g_cfg.video_resolution),
                  g_cfg.video_fps);

    // 4. 初始化摄像头（按配置参数）
    Serial.print("[INIT] 摄像头... ");
    if (!camera_init(g_cfg)) {
        Serial.println("失败!");
        Serial.println("60 秒后重试...");
        SD_MMC.end();
        esp_sleep_enable_timer_wakeup(60 * 1000000ULL);
        esp_deep_sleep_start();
        return;
    }
    Serial.println("OK");

    // 5. 获取下一个视频编号
    uint16_t last_id = 0, last_synced = 0;
    sd_index_read(&last_id, &last_synced);
    uint16_t next_id = last_id + 1;
    Serial.printf("[IDX] 下一个视频: clip_%04u.mjpg (已录:%u, 已同步:%u)\n",
                  next_id, last_id, last_synced);

    // 6. 录制视频
    if (!record_clip(next_id)) {
        Serial.println("[ERR] 录制环节失败");
    }

    // 6.5 录制完成后关闭摄像头，释放 PSRAM 给 BLE 使用
    Serial.println("[CAM] 释放摄像头资源...");
    camera_power_off();

    // 7. BLE 广播窗口 — 等待手机连接并同步文件
#ifdef WITH_BLE
    Serial.printf("[BLE] 广播窗口 %u 秒, 设备名: %s\n",
                  g_cfg.ble_advertise_timeout / 1000,
                  g_cfg.ble_device_name);
    // BLE 会话（公告时 LED 闪烁、连接后常亮，ble_service 内部处理）
    bool synced = ble_run_sync_session(g_cfg.ble_advertise_timeout);
    led_off();
    if (synced) {
        Serial.printf("[BLE] 同步完成, 传输 %d 块\n", ble_files_transferred());
    } else {
        Serial.println("[BLE] 超时无连接，数据保留在 SD 卡");
    }
#endif

    // 8. 进入 Deep Sleep
    enter_deep_sleep();
}

void loop() {
    // 安全网：检查唤醒原因，正常情况下不会到达这里
    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
    const char *causeName = "UNKNOWN";
    switch (cause) {
        case ESP_SLEEP_WAKEUP_TIMER:   causeName = "TIMER";   break;
        case ESP_SLEEP_WAKEUP_EXT0:    causeName = "EXT0";    break;
        case ESP_SLEEP_WAKEUP_EXT1:    causeName = "EXT1";    break;
        case ESP_SLEEP_WAKEUP_TOUCHPAD:causeName = "TOUCH";   break;
        case ESP_SLEEP_WAKEUP_ULP:     causeName = "ULP";     break;
        default: break;
    }
    Serial.printf("[WARN] 意外到达 loop(), 唤醒源=%s, 60 秒后重试休眠\n", causeName);
    delay(1000);
    esp_sleep_enable_timer_wakeup(60 * 1000000ULL);
    esp_deep_sleep_start();
}
