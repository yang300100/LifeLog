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
#include "timebase.h"
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
RTC_DATA_ATTR uint8_t  kicks_remaining = 0; // PB0A 踢狗剩余次数（0=正常录制）
RTC_DATA_ATTR uint32_t rtc_expected_sleep_ms = 0; // 本次期望休眠时长（ms），醒来后补偿时间基准
RTC_DATA_ATTR uint64_t rtc_esp_before_sleep = 0;  // 休眠前 esp_timer ms，用于计算实际前进量

AppConfig g_cfg;

// ── 踢狗版 Deep Sleep ──
// PB0A 充放电芯片：负载 < 50mA 持续 > 32s 自动断电。
// Deep Sleep 时 ESP32 只吃 4-6mA → 会被 PB0A 断电。
// 解决方案：空闲 > 30s 时，分段睡眠（每 25s 唤醒一次），
// 启动时的 ~70mA 电流脉冲把 PB0A 的 32s 计时器踢回去。
static void enter_deep_sleep() {
    uint32_t elapsed = millis();  // 本次启动已用时间
    uint32_t interval = g_cfg.interval;

    // 计算空闲时间
    uint32_t idle_ms;
    if (interval > elapsed) {
        idle_ms = interval - elapsed;
    } else {
        // 本次耗时超过了间隔（BLE 连太久等极端情况），至少睡 1 秒
        idle_ms = 1000;
    }

    // 释放摄像头资源
    camera_power_off();
    delay(200);  // SD 缓存落盘
    SD_MMC.end();

    // 记录休眠前状态（醒来后 timebase 用这两个值判断是否需要补偿）
    rtc_expected_sleep_ms = idle_ms;
    rtc_esp_before_sleep = (uint64_t)(esp_timer_get_time() / 1000);

    if (idle_ms <= KICK_MIN_IDLE_MS) {
        // 空闲短，PB0A 32s 计时器不会触发，直接睡满
        Serial.printf("[SLEEP] 空闲 %u ms ≤ 30s, 不踢狗, 直接睡\n", idle_ms);
        esp_sleep_enable_timer_wakeup((uint64_t)idle_ms * 1000ULL);
        kicks_remaining = 0;
    } else {
        // 空闲长，需要分段踢狗
        uint8_t kicks = (uint8_t)(idle_ms / KICK_INTERVAL_MS);
        if (kicks > 30) kicks = 30;  // 安全帽
        kicks_remaining = kicks;
        Serial.printf("[SLEEP] 空闲 %u ms → %u 次踢狗, 间隔 %u ms\n",
                      idle_ms, kicks, KICK_INTERVAL_MS);
        esp_sleep_enable_timer_wakeup((uint64_t)KICK_INTERVAL_MS * 1000ULL);
    }

    Serial.flush();
    esp_deep_sleep_start();
}

// ── 录制并存储一段视频 ──
static bool record_clip(uint32_t clip_id) {
    char filepath[40];  // /sdcard/YYYYMMDD_HHMMSS.mjpg = 35 + \0

    // 生成文件名：通过 sd_make_filename 与 sd_next_filename 保证格式一致
    uint64_t record_epoch_ms = 0;
    if (timebase_is_valid()) record_epoch_ms = timebase_now_ms();
    else record_epoch_ms = (uint64_t)(esp_timer_get_time() / 1000);
    sd_make_filename(clip_id, record_epoch_ms, filepath, sizeof(filepath));

    led_on();  // 录制中红灯常亮
    Serial.printf("[REC] 录制 %s (%u ms @ %u fps)...\n",
                  filepath, g_cfg.video_duration, g_cfg.video_fps);

    int frames = camera_capture_video(filepath,
                                      g_cfg.video_duration,
                                      g_cfg.video_fps);

    if (frames < 0) {
        Serial.printf("[ERR] 录制失败 code=%d，删除残缺文件\n", frames);
        remove(filepath);
        return false;
    }

    Serial.printf("[REC] 完成: %d 帧\n", frames);
    led_off();

    // 写入 ID→时间戳 映射（供 sd_next_filename 反查日期文件名 + BLE age 计算）
    if (record_epoch_ms > 0) {
        sd_clip_ts_write(clip_id, record_epoch_ms);
        Serial.printf("[TS-WRITE] clip_%06u → %s\n",
                      clip_id, filepath + 8);  // 跳过 "/sdcard/" (8字符)
    } else {
        // 无时间基准：仍用 esp_timer 记录相对时刻（BLE 列表需要 age 字段）
        uint64_t now_ms = (uint64_t)(esp_timer_get_time() / 1000);
        sd_clip_ts_write(clip_id, now_ms);
        Serial.printf("[TS-WRITE] clip_%06u → esp_timer=%llu ms\n",
                      clip_id, (unsigned long long)now_ms);
    }

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
    for (uint32_t id = 1; id <= 100; id++) {
        char fname[40];
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

    // ── 🐶 PB0A 踢狗检测 ──
    // 这是踢狗唤醒（不是正常录制）→ 只做最小化启动，不碰 SD/摄像头/BLE
    if (kicks_remaining > 0) {
        Serial.printf("[KICK] 踢狗唤醒 %d/%d\n", kicks_remaining, kicks_remaining + 1);
        kicks_remaining--;
        delay(200);  // 稳定电流 ~70mA，让 PB0A 看清负载脉冲
        if (kicks_remaining > 0) {
            // 还有踢狗任务 → 继续睡
            esp_sleep_enable_timer_wakeup((uint64_t)KICK_INTERVAL_MS * 1000ULL);
            Serial.flush();
            esp_deep_sleep_start();
        }
        // kicks_remaining == 0 → 踢完了，继续往下走正常录制
    }

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
    Serial.printf("[CFG] 锐度=%d 对比度=%d 饱和度=%d 闪灯阈值=%u行 广播=%us 设备名=%s\n",
                  (int)g_cfg.sharpness,
                  (int)g_cfg.contrast,
                  (int)g_cfg.saturation,
                  g_cfg.flash_threshold,
                  g_cfg.ble_advertise_timeout / 1000,
                  g_cfg.ble_device_name);

    // 3. Deep Sleep 时间补偿：esp_timer 在某些芯片上休眠期间暂停，
    //    比对期望时长和实际前进量，只补差值（避免双重计数）
    if (rtc_expected_sleep_ms > 0) {
        timebase_adjust_for_sleep(rtc_expected_sleep_ms, rtc_esp_before_sleep);
        rtc_expected_sleep_ms = 0;
        rtc_esp_before_sleep = 0;
    }

    // 4. 初始化时间基准（RTC 内存保留 → 决定文件名是否带日期）
    bool had_timebase = timebase_init();

#ifdef WITH_BLE
    // 3.5 时间基准无效时，先快速连一次手机获取时间（仅每天首次开机 / RES 后）
    if (!had_timebase) {
        Serial.println("[TIME] 无有效时间基准，等待手机同步时间（30 秒窗口）...");
        // 短超时 BLE：手机连上来同步时间+文件，到点就继续
        ble_request_time(30000);
    }
#endif

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
    uint32_t last_id = 0, last_synced = 0;
    sd_index_read(&last_id, &last_synced);
    uint32_t next_id = last_id + 1;
    Serial.printf("[IDX] 下一个视频: clip_%06u.mjpg (已录:%u, 已同步:%u)\n",
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
