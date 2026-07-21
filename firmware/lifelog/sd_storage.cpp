#include <Arduino.h>
#include "sd_storage.h"
#include "config.h"
#include "timebase.h"
#include "FS.h"
#include "SD_MMC.h"
#include <stdio.h>
#include <stdlib.h>

bool sd_init() {
    // 1-bit 模式：仅使用 DATA0 (CMD=GPIO15, CLK=GPIO14, DATA0=GPIO2)
    // 不使用 DATA1(GPIO4)、DATA2(GPIO12)、DATA3(GPIO13)
    bool ok = SD_MMC.begin("/sdcard", true);  // true = 1-bit mode
    if (!ok) {
        Serial.println("[SD] SD_MMC.begin 失败 — 检查卡是否插入、格式是否为 FAT32");
        return false;
    }

    uint8_t cardType = SD_MMC.cardType();
    if (cardType == CARD_NONE) {
        Serial.println("[SD] cardType=CARD_NONE — SD 卡未被识别（可能损坏或不兼容）");
        return false;
    }

    const char *typeName = "UNKNOWN";
    if (cardType == CARD_MMC) typeName = "MMC";
    else if (cardType == CARD_SD) typeName = "SDSC";
    else if (cardType == CARD_SDHC) typeName = "SDHC";

    Serial.printf("[SD] 初始化成功, 类型=%s, 大小=%lluMB\n",
                  typeName, SD_MMC.cardSize() / (1024 * 1024));
    return true;
}

bool sd_index_read(uint32_t *last_id, uint32_t *last_synced) {
    *last_id = 0;
    *last_synced = 0;

    FILE *f = fopen(SD_INDEX_FILE, "r");
    if (!f) return false;

    char line[64];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "last_id=", 8) == 0)
            *last_id = (uint32_t)strtoul(line + 8, NULL, 10);
        else if (strncmp(line, "last_synced=", 12) == 0)
            *last_synced = (uint32_t)strtoul(line + 12, NULL, 10);
    }
    fclose(f);
    return true;
}

static bool sd_index_write(uint32_t last_id, uint32_t last_synced) {
    FILE *f = fopen(SD_INDEX_FILE, "w");
    if (!f) return false;
    fprintf(f, "last_id=%u\nlast_synced=%u\n", last_id, last_synced);
    fclose(f);
    return true;
}

// 注：读-改-写非原子操作，但在 Arduino 单线程事件循环中安全
// 若未来在 ISR/BLE 回调中调用，需加锁或用原子操作
bool sd_index_update_last_id(uint32_t new_id) {
    uint32_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    return sd_index_write(new_id, last_synced);
}

bool sd_index_update_last_synced(uint32_t synced_id) {
    uint32_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    // 防回退/防超前：手机异常时（如 sync_done(0)）不能抹掉已同步进度
    if (synced_id < last_synced) synced_id = last_synced;
    if (synced_id > last_id) synced_id = last_id;
    return sd_index_write(last_id, synced_id);
}

// 生成文件名：epoch_ms 为本地 epoch → 日期格式，否则回退到数字格式
// 这是录制和传输共用的单一入口，保证两条路径的文件名一致
//
// 注意：不检查 timebase_is_valid()！文件名由 epoch_ms 的值决定，
// 而不是由当前 timebase 状态决定。否则 RES 后 timebase 丢失，
// 之前用日期名存的文件会因回退到数字名而找不到（→ size=0 → 0B 文件）。
void sd_make_filename(uint32_t id, uint64_t epoch_ms, char *buf, size_t bufsz) {
    if (timebase_is_epoch_ts(epoch_ms)) {
        char ts_str[20];
        timebase_format_epoch(epoch_ms, ts_str, sizeof(ts_str));
        snprintf(buf, bufsz, "/sdcard/%s.mjpg", ts_str);
    } else {
        snprintf(buf, bufsz, "/sdcard/clip_%06u.mjpg", id);
    }
}

// 从 ID 反查文件名（BLE 传输 / 删除时用）
void sd_next_filename(uint32_t id, char *buf, size_t bufsz) {
    uint64_t ts = sd_clip_ts_read(id);
    sd_make_filename(id, ts, buf, bufsz);
}

// 删除所有已同步的视频文件 —— 手机已确认字节级完整收到，
// 删掉既省 SD 空间，也避免下次会话重复传输
void sd_delete_synced() {
    uint32_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    int deleted = 0;
    for (uint32_t id = 1; id <= last_synced; id++) {
        char fname[40];
        sd_next_filename(id, fname, sizeof(fname));
        if (remove(fname) == 0) deleted++;  // 不存在则跳过，不报错
    }
    if (deleted > 0) {
        Serial.printf("[SD] 已删除 %d 个已同步视频 (id<=%u)，剩余空间 %u MB\n",
                      deleted, last_synced, sd_free_mb());
    }

    // 顺带清理时间戳文件中已删除片段的记录（读全量 → 过滤 → 重写）
    FILE *tsf = fopen(SD_CLIP_TS_FILE, "r");
    if (tsf) {
        char keep[2048];  // 未同步片段：2048 足以容纳 ~60 条 uint32_t ID 的记录
        size_t used = 0;
        keep[0] = '\0';
        char line[48];
        while (fgets(line, sizeof(line), tsf)) {
            unsigned int lid = 0;
            if (sscanf(line, "%u=", &lid) == 1 && lid <= last_synced) continue;  // 丢弃已删除的
            size_t l = strlen(line);
            if (used + l >= sizeof(keep) - 1) break;  // 溢出保护
            memcpy(keep + used, line, l);
            used += l;
        }
        fclose(tsf);
        keep[used] = '\0';
        tsf = fopen(SD_CLIP_TS_FILE, "w");
        if (tsf) {
            fwrite(keep, 1, used, tsf);
            fclose(tsf);
        }
    }
}

// 记录某段视频的录制时刻 —— esp_timer 基于 RTC 计数器，deep sleep 中持续走时，
// 所以不同周期录制的片段可以靠它算出准确的相对年龄
void sd_clip_ts_write(uint32_t id, uint64_t record_ms) {
    FILE *f = fopen(SD_CLIP_TS_FILE, "a");
    if (!f) return;
    fprintf(f, "%u=%llu\n", id, (unsigned long long)record_ms);
    fclose(f);
}

uint64_t sd_clip_ts_read(uint32_t id) {
    FILE *f = fopen(SD_CLIP_TS_FILE, "r");
    if (!f) return 0;
    char line[48];
    uint64_t ts = 0;
    while (fgets(line, sizeof(line), f)) {
        unsigned int lid = 0;
        unsigned long long v = 0;
        if (sscanf(line, "%u=%llu", &lid, &v) == 2 && lid == id) {
            ts = v;
            break;
        }
    }
    fclose(f);
    return ts;
}

uint32_t sd_free_mb() {
    return (uint32_t)(SD_MMC.totalBytes() - SD_MMC.usedBytes()) / (1024 * 1024);
}

int battery_percent() {
    // 首次调用时初始化 ADC 衰减（仅一次，后续 analogRead 在正确量程下工作）
    static bool adc_inited = false;
    if (!adc_inited) {
        analogSetAttenuation(BATTERY_ADC_ATTEN);
        adc_inited = true;
    }

    int raw = analogRead(BATTERY_ADC_PIN);

    // ADC 引脚完全悬空时读值在 0 附近剧烈抖动 —— 如果连续采样都极低，
    // 说明电池分压电路没接，直接返回 -1 避免显示虚假电量
    if (raw < 100) {
        // 再读一次确认不是偶然
        delay(1);
        if (analogRead(BATTERY_ADC_PIN) < 100) return -1;
    }

    // ESP32 ADC 12-bit → 电压 → 乘以分压比 → 真实电池电压
    float v_adc = (float)raw / 4095.0f * 3.9f;  // ATTEN_DB_11 ≈ 3.9V 满量程
    float v_bat = v_adc * BATTERY_VOLTAGE_DIVIDER;
    int pct = (int)((v_bat - BATTERY_MIN_VOLTAGE) /
                    (BATTERY_MAX_VOLTAGE - BATTERY_MIN_VOLTAGE) * 100.0f);
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return pct;
}
