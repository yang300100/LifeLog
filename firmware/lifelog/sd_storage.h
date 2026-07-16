#ifndef LIFELOG_SD_STORAGE_H
#define LIFELOG_SD_STORAGE_H

#include <stdint.h>
#include <stdbool.h>

// 初始化 SD 卡 (1-bit 模式)
bool sd_init();

// 读取索引文件，获取 last_id 和 last_synced
// 返回 false 表示文件不存在（首次使用）
bool sd_index_read(uint16_t *last_id, uint16_t *last_synced);

// 更新 last_id（录制新视频后递增写入）
bool sd_index_update_last_id(uint16_t new_id);

// 更新 last_synced（手机同步完成后写入）
bool sd_index_update_last_synced(uint16_t synced_id);

// 生成下一个视频文件名: clip_XXXX.mjpg
void sd_next_filename(uint16_t id, char *buf, size_t bufsz);

// 获取 SD 卡剩余空间 (MB)
uint32_t sd_free_mb();

#endif
