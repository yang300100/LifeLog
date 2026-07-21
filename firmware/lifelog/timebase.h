#ifndef LIFELOG_TIMEBASE_H
#define LIFELOG_TIMEBASE_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// 时间域判断阈值：clip_ts 中 > 此值的为本地 epoch（ms），< 此值的为 esp_timer（ms）
// 阈值 1e12 ≈ 2001-09-09，远超 esp_timer 可能的值（连续运行 11 天 ≈ 1e9）
#define TIMEBASE_EPOCH_THRESHOLD  1000000000000ULL

// 判断 clip_ts 中存储的值是否为本地 epoch 时间戳
static inline bool timebase_is_epoch_ts(uint64_t ts) {
    return ts > TIMEBASE_EPOCH_THRESHOLD;
}

// 初始化时间基准：优先从 RTC 内存读取（deep sleep 保留），
// 无效时尝试从 SD 卡加载上次同步时保存的备份（RES 后可恢复）
// 返回 true 表示时间基准有效，可生成日期文件名
bool timebase_init();

// 时间基准是否有效
bool timebase_is_valid();

// 手机同步时间后更新基准（写入 RTC 内存 + SD 卡双备份）
// local_epoch_ms: 手机本地时间的 epoch 毫秒
void timebase_update(uint64_t local_epoch_ms);

// Deep Sleep 时间补偿：部分 ESP32 芯片的 esp_timer 在 deep sleep 期间暂停。
// 比对"期望休眠时长"和"esp_timer 实际前进量"，只补差值。
// esp_before_sleep_ms: 休眠前 esp_timer_get_time()/1000 的值
void timebase_adjust_for_sleep(uint32_t expected_sleep_ms, uint64_t esp_before_sleep_ms);

// 获取当前本地时间（epoch 毫秒）
uint64_t timebase_now_ms();

// 将 epoch 毫秒格式化为 "YYYYMMDD_HHMMSS"（不含路径）
// 用于生成文件名
void timebase_format_epoch(uint64_t epoch_ms, char *buf, size_t bufsz);

// 将当前时间格式化为 "YYYYMMDD_HHMMSS"
void timebase_format_now(char *buf, size_t bufsz);

#endif
