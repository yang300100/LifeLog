#include <Arduino.h>
#include "timebase.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include <time.h>
#include <stdio.h>

// ── RTC 内存：deep sleep 保留，RES/断电 清零 ──
RTC_DATA_ATTR uint64_t rtc_timebase_epoch = 0;  // esp_timer==0 时的本地 epoch (ms)

// 时间基准是否已初始化并有效
static bool g_timebase_valid = false;

bool timebase_init() {
    // 仅用 RTC 内存（deep sleep 保留，最准）。
    // RES/断电后清零 → 返回 false → 走 ble_request_time 从手机获取最新时间。
    // 不使用 SD 卡备份 —— 备份的时间是上次同步的旧值，不够准确。
    if (rtc_timebase_epoch > 0) {
        g_timebase_valid = true;
        uint64_t local_ms = timebase_now_ms();
        char buf[20];
        timebase_format_epoch(local_ms, buf, sizeof(buf));
        Serial.printf("[TIME] RTC 基准有效, 当前本地时间≈%s\n", buf);
        return true;
    }

    Serial.println("[TIME] RTC 丢失（RES/断电），等待手机同步当前时间...");
    g_timebase_valid = false;
    return false;
}

bool timebase_is_valid() {
    return g_timebase_valid;
}

// Deep Sleep 时间补偿：
// 部分 ESP32 芯片的 esp_timer 在 deep sleep 期间暂停，导致 timebase 慢掉。
// 比对"期望休眠时长"和"esp_timer 实际前进的量"，只补偿差值。
void timebase_adjust_for_sleep(uint32_t expected_sleep_ms, uint64_t esp_before_sleep_ms) {
    if (rtc_timebase_epoch == 0 || expected_sleep_ms == 0) return;

    uint64_t esp_now = (uint64_t)(esp_timer_get_time() / 1000);
    uint64_t esp_advanced = (esp_now > esp_before_sleep_ms) ? (esp_now - esp_before_sleep_ms) : 0;

    if (esp_advanced < expected_sleep_ms) {
        uint32_t lag = (uint32_t)(expected_sleep_ms - esp_advanced);
        rtc_timebase_epoch += lag;
        Serial.printf("[TIME] esp_timer 仅前进 %llu ms (期望 %u ms), 补偿 +%u ms\n",
                      (unsigned long long)esp_advanced, expected_sleep_ms, lag);
    } else {
        Serial.printf("[TIME] esp_timer 前进 %llu ms ≥ 期望 %u ms, 无需补偿\n",
                      (unsigned long long)esp_advanced, expected_sleep_ms);
    }
}

void timebase_update(uint64_t local_epoch_ms) {
    // 反算 esp_timer==0 时的 epoch
    uint64_t esp_ms = (uint64_t)(esp_timer_get_time() / 1000);
    if (local_epoch_ms < esp_ms) {
        // 手机时间小于开机时间（时间回拨、时区错误等异常）→ 拒绝更新
        Serial.printf("[TIME] 拒绝异常时间: local=%llu < esp=%llu ms\n",
                      (unsigned long long)local_epoch_ms, (unsigned long long)esp_ms);
        return;
    }
    rtc_timebase_epoch = local_epoch_ms - esp_ms;
    g_timebase_valid = true;

    char buf[20];
    timebase_format_epoch(local_epoch_ms, buf, sizeof(buf));
    Serial.printf("[TIME] 时间基准已更新, 当前本地时间=%s\n", buf);
}

uint64_t timebase_now_ms() {
    if (!g_timebase_valid) return 0;
    uint64_t esp_ms = (uint64_t)(esp_timer_get_time() / 1000);
    return rtc_timebase_epoch + esp_ms;
}

// 将 epoch 毫秒转换为本地时间并格式化
void timebase_format_epoch(uint64_t epoch_ms, char *buf, size_t bufsz) {
    if (epoch_ms == 0) {
        snprintf(buf, bufsz, "norTC");
        return;
    }
    time_t t = (time_t)(epoch_ms / 1000);
    struct tm tm_info;
    // epoch_ms 已是本地时间，直接用 gmtime 格式化即可
    gmtime_r(&t, &tm_info);
    snprintf(buf, bufsz, "%04d%02d%02d_%02d%02d%02d",
             tm_info.tm_year + 1900,
             tm_info.tm_mon + 1,
             tm_info.tm_mday,
             tm_info.tm_hour,
             tm_info.tm_min,
             tm_info.tm_sec);
}

void timebase_format_now(char *buf, size_t bufsz) {
    timebase_format_epoch(timebase_now_ms(), buf, bufsz);
}
