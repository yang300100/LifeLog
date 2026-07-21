#ifndef LIFELOG_BLE_SERVICE_H
#define LIFELOG_BLE_SERVICE_H

#include <stdint.h>
#include <stdbool.h>

// 运行一次 BLE 同步会话 (阻塞式)
// 流程: 初始化 BLE → 开始广播 → 等待连接+传输+sync_done → 清理
// timeout_ms: 整个广播窗口时长
// 返回 true 表示收到 sync_done 并更新了索引
bool ble_run_sync_session(uint32_t timeout_ms);

// 快速时间同步：跑一个短超时的正常 BLE 会话（时间基准无效时调用）。
// 手机连上来发 sync_time 校准时间，顺便同步昨天的残留文件。
// 仅在每天首次开机 / RES 后调用。
bool ble_request_time(uint32_t timeout_ms);

// 获取本次会话传输的文件数
int ble_files_transferred();

#endif
