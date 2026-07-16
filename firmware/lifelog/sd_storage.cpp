#include "sd_storage.h"
#include "config.h"
#include "FS.h"
#include "SD_MMC.h"
#include <stdio.h>

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

bool sd_index_read(uint16_t *last_id, uint16_t *last_synced) {
    *last_id = 0;
    *last_synced = 0;

    FILE *f = fopen(SD_INDEX_FILE, "r");
    if (!f) return false;

    char line[64];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "last_id=", 8) == 0)
            *last_id = (uint16_t)atoi(line + 8);
        else if (strncmp(line, "last_synced=", 12) == 0)
            *last_synced = (uint16_t)atoi(line + 12);
    }
    fclose(f);
    return true;
}

static bool sd_index_write(uint16_t last_id, uint16_t last_synced) {
    FILE *f = fopen(SD_INDEX_FILE, "w");
    if (!f) return false;
    fprintf(f, "last_id=%u\nlast_synced=%u\n", last_id, last_synced);
    fclose(f);
    return true;
}

// 注：读-改-写非原子操作，但在 Arduino 单线程事件循环中安全
// 若未来在 ISR/BLE 回调中调用，需加锁或用原子操作
bool sd_index_update_last_id(uint16_t new_id) {
    uint16_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    return sd_index_write(new_id, last_synced);
}

bool sd_index_update_last_synced(uint16_t synced_id) {
    uint16_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    return sd_index_write(last_id, synced_id);
}

void sd_next_filename(uint16_t id, char *buf, size_t bufsz) {
    snprintf(buf, bufsz, "/clip_%04u.mjpg", id);
}

uint32_t sd_free_mb() {
    return (uint32_t)(SD_MMC.totalBytes() - SD_MMC.usedBytes()) / (1024 * 1024);
}
