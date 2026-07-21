#ifndef LIFELOG_SD_STORAGE_H
#define LIFELOG_SD_STORAGE_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// 初始化 SD 卡 (1-bit 模式)
bool sd_init();

// 读取索引文件，获取 last_id 和 last_synced
// 返回 false 表示文件不存在（首次使用）
bool sd_index_read(uint32_t *last_id, uint32_t *last_synced);

// 更新 last_id（录制新视频后递增写入）
bool sd_index_update_last_id(uint32_t new_id);

// 更新 last_synced（手机同步完成后写入）
bool sd_index_update_last_synced(uint32_t synced_id);

// 删除所有已同步 (id <= last_synced) 的视频文件，释放 SD 空间
void sd_delete_synced();

// 记录某段视频的录制时刻（esp_timer 毫秒，跨 deep sleep 连续走时）
void sd_clip_ts_write(uint32_t id, uint64_t record_ms);

// 查询某段视频的录制时刻，0 = 无记录（旧文件）
uint64_t sd_clip_ts_read(uint32_t id);

// 生成下一个视频文件名: clip_XXXXXX.mjpg (6 位数字)
// 内部从 clip_ts 反查时间戳，自动选择日期格式或数字格式
void sd_next_filename(uint32_t id, char *buf, size_t bufsz);

// 直接用 epoch 毫秒生成文件名（录制时用，不依赖 clip_ts 已有记录）
// 与 sd_next_filename 使用相同的文件名规则，确保录制和传输路径一致
void sd_make_filename(uint32_t id, uint64_t epoch_ms, char *buf, size_t bufsz);

// 获取 SD 卡剩余空间 (MB)
uint32_t sd_free_mb();

// 获取电池剩余百分比（0~100），ADC 未连接时返回 -1
int battery_percent();

#endif
