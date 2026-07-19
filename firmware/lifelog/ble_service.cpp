#include <Arduino.h>
#include "ble_service.h"
#include "sd_storage.h"
#include "config.h"
#include "configuration.h"
#include "camera.h"
#include <NimBLEDevice.h>
#include <esp_camera.h>
#include <esp_timer.h>
#include <stdio.h>
#include <string.h>

// ── UUID 定义 ──
#define SERVICE_UUID      "0000ff00-0000-1000-8000-00805f9b34fb"
#define CHAR_SYNC_CONTROL "0000ff01-0000-1000-8000-00805f9b34fb"
#define CHAR_FILE_INDEX   "0000ff02-0000-1000-8000-00805f9b34fb"
#define CHAR_FILE_TRANSFER "0000ff03-0000-1000-8000-00805f9b34fb"
#define CHAR_DEVICE_INFO  "0000ff04-0000-1000-8000-00805f9b34fb"

// ── 前向声明（回调类在下方定义，指针需提前声明类型）──
class ServerCB;
class SyncControlCB;
class FileIndexCB;
class FileTransferCB;

// ── 全局状态 ──
static NimBLEServer         *pServer        = nullptr;
static NimBLEService        *pService       = nullptr;
static NimBLECharacteristic *pSyncControl   = nullptr;
static NimBLECharacteristic *pFileIndex     = nullptr;
static NimBLECharacteristic *pFileTransfer  = nullptr;
static NimBLECharacteristic *pDeviceInfo    = nullptr;

// 回调对象指针（用于清理）
static ServerCB       *g_serverCb       = nullptr;
static SyncControlCB  *g_syncControlCb  = nullptr;
static FileIndexCB    *g_fileIndexCb    = nullptr;
static FileTransferCB *g_fileTransferCb = nullptr;

// volatile：在 BLE 回调中写入，主循环中读取，防止编译器优化掉内存访问
static volatile bool  g_connected      = false;
static volatile bool  g_transfer_done  = false;
static int   g_files_sent     = 0;

// ── 命令队列 (从回调写入，主循环读取) ──
static volatile bool g_cmd_pending = false;
static char   g_cmd_type[16];
static char   g_cmd_json[512];  // 原始 JSON（config 等复杂命令需要完整内容）
static uint16_t g_cmd_file_id;
static uint32_t g_cmd_offset;
static uint16_t g_cmd_chunk_size;
static uint16_t g_cmd_max_chunks;  // 窗口化推送：每次 pull 最多发几块，0=不限

// ── 简易 JSON 解析 ──
// 从 JSON 字符串中提取指定 key 的整数值
static int json_get_int(const char *json, const char *key, int default_val) {
    char search[32];
    snprintf(search, sizeof(search), "\"%s\":", key);
    const char *p = strstr(json, search);
    if (!p) return default_val;
    // 防止前缀误匹配：确保前面是 { , 或空白（JSON key 分隔符）
    if (p > json) {
        char prev = *(p - 1);
        if (prev != '{' && prev != ',' && prev != ' ' && prev != '\t' && prev != '\n')
            return default_val;
    }
    p += strlen(search);
    while (*p == ' ' || *p == '\t') p++;
    return atoi(p);
}

// ── 构建文件列表 JSON ──
static void build_file_list_json(char *buf, size_t bufsz) {
    uint16_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);

    // 发送同步状态 + 新文件列表
    uint16_t new_count = last_id - last_synced;
    int pos = snprintf(buf, bufsz,
        "{\"status\":\"ready\",\"new_count\":%u,\"files\":[", new_count);

    bool first = true;
    for (uint16_t id = last_synced + 1; id <= last_id; id++) {
        char fname[32];
        sd_next_filename(id, fname, sizeof(fname));

        FILE *f = fopen(fname, "rb");
        long fsize = 0;
        if (f) {
            fseek(f, 0, SEEK_END);
            fsize = ftell(f);
            fclose(f);
        }

        // 相对年龄（秒）：esp_timer 跨 deep sleep 连续，
        // 手机端用「当前时间 - age」还原真实录制时间；无记录的旧文件不带该字段
        uint64_t ts = sd_clip_ts_read(id);
        bool has_age = ts > 0;
        uint32_t age_s = 0;
        if (has_age) {
            uint64_t now_ms = (uint64_t)(esp_timer_get_time() / 1000);
            age_s = (uint32_t)(now_ms > ts ? (now_ms - ts) / 1000 : 0);
        }
        // 诊断日志：每次列表时打一条，确认年龄解析在固件端是活的
        if (id == last_synced + 1) {
            Serial.printf("[TS] 首文件 id=%u age=%us (ts=%llu)\n",
                          id, age_s, (unsigned long long)ts);
        }

        // 防御 buffer 溢出（带年龄的条目最长约 48 字节，留 60 字节余量）
        // 到上限就停止追加：列表宁缺毋滥，剩下的文件下周期的列表里再见
        if (bufsz <= 60 || pos >= (int)bufsz - 60) break;

        if (!first) buf[pos++] = ',';
        first = false;
        int written;
        if (has_age) {
            written = snprintf(buf + pos, bufsz - pos,
                "{\"id\":%u,\"size\":%ld,\"age\":%lu}",
                id, fsize, (unsigned long)age_s);
        } else {
            written = snprintf(buf + pos, bufsz - pos,
                "{\"id\":%u,\"size\":%ld}", id, fsize);
        }
        // 防止 snprintf 返回值（would-be 长度）使 pos 越界
        pos = (pos + written < (int)bufsz - 3) ? (pos + written) : (int)bufsz - 3;
    }
    // 安全写入结尾
    pos += snprintf(buf + pos, bufsz - pos, "]}");
}

// ── 设备信息 JSON ──
static void build_device_info_json(char *buf, size_t bufsz) {
    uint16_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    int bat = battery_percent();
    int pos = snprintf(buf, bufsz,
        "{\"name\":\"" BLE_DEVICE_NAME_DEFAULT "\","
        "\"fw_version\":\"1.2\","
        "\"total_clips\":%u,"
        "\"synced_clips\":%u,"
        "\"sd_free_mb\":%u,"
        "\"battery\":%d,"
        "\"config\":{",
        last_id, last_synced, sd_free_mb(), bat);
    // 嵌入当前配置
    pos += snprintf(buf + pos, bufsz - pos,
        "\"interval\":%u,"
        "\"video_duration\":%u,"
        "\"video_resolution\":\"%s\","
        "\"video_quality\":%u,"
        "\"video_fps\":%u,"
        "\"flash_threshold\":%u,"
        "\"ble_advertise_timeout\":%u,"
        "\"ble_device_name\":\"%s\","
        "\"ble_adv_interval\":%u}",
        g_cfg.interval,
        g_cfg.video_duration,
        camera_framesize_name(g_cfg.video_resolution),
        g_cfg.video_quality,
        g_cfg.video_fps,
        g_cfg.flash_threshold,
        g_cfg.ble_advertise_timeout,
        g_cfg.ble_device_name,
        g_cfg.ble_adv_interval);
    // 收尾 JSON（如果截断至少保证 JSON 闭合）
    if (pos < (int)bufsz - 3) {
        snprintf(buf + pos, bufsz - pos, "}");
    } else {
        // 被截断：尽力闭合
        snprintf(buf + (pos > 3 ? pos - 3 : 0), bufsz - (pos > 3 ? pos - 3 : 0), "...");
    }
}

// ── 发送 EOF 通知（带重试）──
// EOF 丢失会让手机干等 15 秒超时，所以和数据块一样需要背压重试
static bool send_eof(uint16_t file_id) {
    char buf[64];
    int len = snprintf(buf, sizeof(buf), "{\"eof\":true,\"file_id\":%u}", file_id);
    pFileTransfer->setValue((uint8_t*)buf, len);
    for (int retry = 0; retry < 250; retry++) {
        if (!g_connected) return false;
        if (pFileTransfer->notify()) return true;
        delay(2);  // 等 BLE 栈腾出队列
    }
    Serial.println("[BLE-CMD] EOF 发送失败（队列持续满）");
    return false;
}

// ── 处理命令 (在主循环中调用) ──
static void process_command() {
    if (!g_cmd_pending) return;
    g_cmd_pending = false;

    char *cmd = g_cmd_type;
    Serial.printf("[BLE-CMD] 收到命令: %s\n", cmd);

    if (strcmp(cmd, "list") == 0) {
        // 文件列表通过 GATT Read 返回（onRead 回调中同步构建），这里不发送 notify/indicate
        Serial.println("[BLE-CMD] list 命令已处理，等待 Android GATT Read");

    } else if (strcmp(cmd, "pull") == 0) {
        // 窗口化推送：每次 pull 最多发 max_chunks 块就停（不发 EOF），
        // 等手机收齐后发起下一窗口。
        // 为什么不一口气发完：NimBLE/控制器底层实际只能缓冲几个通知，
        // 超出的包会被底层静默丢弃但 notify() 依然返回 true（"假成功"），
        // 手机端只能收到流头几块。限窗后在途包永远 <= max_chunks，
        // 任何一层丢包都会被手机的 offset 校验发现并立刻续传。
        uint16_t file_id    = g_cmd_file_id;
        uint32_t offset     = g_cmd_offset;
        uint16_t max_chunks = g_cmd_max_chunks;  // 0 = 不限（兼容旧手机端）
        Serial.printf("[BLE-CMD] pull: file=%u offset=%u win=%u\n",
                      file_id, offset, max_chunks);

        char fname[32];
        sd_next_filename(file_id, fname, sizeof(fname));
        FILE *f = fopen(fname, "rb");
        if (!f) {
            send_eof(file_id);  // 文件不存在，让手机尽快结束等待
            return;
        }

        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        if (offset >= (uint32_t)fsize) {
            fclose(f);
            send_eof(file_id);
            return;
        }

        fseek(f, offset, SEEK_SET);
        // 用栈缓冲替代 malloc/free：避免高频分配导致堆碎片，510B 栈上完全够放
        const uint16_t CHUNK = 500;
        uint8_t full[10 + CHUNK];  // header + data

        size_t n;
        uint32_t sent = 0;
        uint16_t win = 0;
        bool aborted = false;
        bool window_done = false;
        while ((n = fread(full + 10, 1, CHUNK, f)) > 0) {
            // 断线立即中止：不再向空气发包，也不发 EOF（notify 无订阅者会"假成功"）
            if (!g_connected) {
                Serial.println("[BLE-CMD] 连接已断开，中止 pull");
                aborted = true;
                break;
            }

            // 块格式: [4B id LE][4B offset LE][2B len LE][data...]
            full[0] = file_id & 0xFF;
            full[1] = (file_id >> 8) & 0xFF;
            full[2] = 0; full[3] = 0;
            full[4] = (offset + sent) & 0xFF;
            full[5] = ((offset + sent) >> 8) & 0xFF;
            full[6] = ((offset + sent) >> 16) & 0xFF;
            full[7] = ((offset + sent) >> 24) & 0xFF;
            full[8] = n & 0xFF;
            full[9] = (n >> 8) & 0xFF;

            pFileTransfer->setValue(full, 10 + n);

            // 背压：队列满时等待重试；耗尽则中止（不发 EOF），手机会续传
            int retry = 0;
            bool enqueued = false;
            while (retry < 1000) {  // 最多等 2 秒/块
                if (!g_connected) break;
                if (pFileTransfer->notify()) { enqueued = true; break; }
                retry++;
                delay(2);  // 等 BLE 栈发完队列中的包腾出位置
            }
            if (!enqueued) {
                Serial.printf("[BLE-CMD] 中止 pull: offset=%u 队列持续满，等待手机续传\n",
                              offset + sent);
                aborted = true;
                break;
            }

            g_files_sent++;
            sent += n;
            win++;

            // 窗口发满即停：不发 EOF，等手机拉下一窗口
            if (max_chunks > 0 && win >= max_chunks) {
                window_done = true;
                break;
            }
        }
        fclose(f);

        if (window_done) {
            Serial.printf("[BLE-CMD] 窗口完成 %u 块 (发到 offset=%u)，等下一 pull\n",
                          win, offset + sent);
        } else if (!aborted) {
            // 只有真正读到文件尾才发 EOF —— 提前 EOF 会让手机误以为传完了
            // 与最后一个数据块隔开 20ms：实测「数据块+EOF 挤在同一突发」时
            // 手机端容易只收到 EOF，隔开可降低这种诡异的丢失
            delay(20);
            if (send_eof(file_id)) {
                Serial.printf("[BLE-CMD] pull 完成: file=%u 本次发送 %u 字节 (起始offset=%u)\n",
                              file_id, sent, offset);
            }
        }

    } else if (strcmp(cmd, "progress") == 0) {
        // 会话中途的进度推进：手机每完整收到一个文件就报一次
        // 更新索引 + 删除已同步文件释放 SD 空间；不结束会话
        sd_index_update_last_synced(g_cmd_file_id);
        sd_delete_synced();

    } else if (strcmp(cmd, "config") == 0) {
        // 手机端设置变更：从 JSON 解析各字段并写入 camera.cfg（下周期生效）
        // 字段格式: {"cmd":"config","interval":120000,"video_resolution":"VGA",...}
        // 只更新手机明确传来的字段，其他保持不变
        Serial.println("[BLE-CMD] 收到配置更新");

        // 从 JSON 里按需提取字段（config_load 先加载当前值，下面逐个覆盖）
        const char *cfgJson = g_cmd_json;
        AppConfig newCfg;
        config_load("/sdcard/camera.cfg", newCfg);

        int ival = json_get_int(cfgJson, "interval", -1);
        if (ival >= 10000) newCfg.interval = (uint32_t)ival;

        ival = json_get_int(cfgJson, "video_duration", -1);
        if (ival >= 1000 && ival <= 30000) newCfg.video_duration = (uint32_t)ival;

        ival = json_get_int(cfgJson, "video_quality", -1);
        if (ival >= 10 && ival <= 63) newCfg.video_quality = (uint8_t)ival;

        ival = json_get_int(cfgJson, "video_fps", -1);
        if (ival >= 1 && ival <= 30) newCfg.video_fps = (uint8_t)ival;

        ival = json_get_int(cfgJson, "ble_advertise_timeout", -1);
        if (ival >= 5000 && ival <= 600000) newCfg.ble_advertise_timeout = (uint32_t)ival;

        ival = json_get_int(cfgJson, "flash_threshold", -1);
        if (ival >= 10000 && ival <= 500000) newCfg.flash_threshold = (uint32_t)ival;

        ival = json_get_int(cfgJson, "ble_adv_interval", -1);
        if (ival >= 20 && ival <= 1000) newCfg.ble_adv_interval = (uint16_t)ival;

        // 分辨率（字符串）
        const char *resKey = "\"video_resolution\":\"";
        const char *resStart = strstr(cfgJson, resKey);
        if (resStart) {
            resStart += strlen(resKey);
            const char *resEnd = strchr(resStart, '"');
            if (resEnd && resEnd - resStart < 8) {
                char resStr[8] = {0};
                memcpy(resStr, resStart, resEnd - resStart);
                if (strcmp(resStr, "QVGA") == 0)     newCfg.video_resolution = FRAMESIZE_QVGA;
                else if (strcmp(resStr, "VGA") == 0)  newCfg.video_resolution = FRAMESIZE_VGA;
                else if (strcmp(resStr, "SVGA") == 0) newCfg.video_resolution = FRAMESIZE_SVGA;
                else if (strcmp(resStr, "XGA") == 0)  newCfg.video_resolution = FRAMESIZE_XGA;
                else if (strcmp(resStr, "SXGA") == 0) newCfg.video_resolution = FRAMESIZE_SXGA;
                else if (strcmp(resStr, "UXGA") == 0) newCfg.video_resolution = FRAMESIZE_UXGA;
                else if (strcmp(resStr, "QXGA") == 0) newCfg.video_resolution = FRAMESIZE_QXGA;
            }
        }

        // BLE 设备名（字符串）
        const char *nameKey = "\"ble_device_name\":\"";
        const char *nameStart = strstr(cfgJson, nameKey);
        if (nameStart) {
            nameStart += strlen(nameKey);
            const char *nameEnd = strchr(nameStart, '"');
            if (nameEnd && nameEnd - nameStart < (int)sizeof(newCfg.ble_device_name)) {
                size_t nl = nameEnd - nameStart;
                memcpy(newCfg.ble_device_name, nameStart, nl);
                newCfg.ble_device_name[nl] = '\0';
            }
        }

        config_save("/sdcard/camera.cfg", newCfg);
        g_cfg = newCfg;  // 立即更新运行中配置（ble_device_name 等影响当前会话）
        Serial.printf("[BLE-CMD] 配置已保存，下个周期生效\n");

        // 更新设备信息特征值以供手机立即回读（512 = 特征值默认 max_len）
        char dev_info[512];
        build_device_info_json(dev_info, sizeof(dev_info));
        pDeviceInfo->setValue((uint8_t*)dev_info, strlen(dev_info));

        const char *ack = "{\"status\":\"config_ok\"}";
        pSyncControl->setValue((uint8_t*)ack, strlen(ack));
        pSyncControl->notify();

    } else if (strcmp(cmd, "sync_done") == 0) {
        uint16_t last_id = g_cmd_file_id;
        sd_index_update_last_synced(last_id);
        sd_delete_synced();

        // 通知手机同步完成
        const char *resp = "{\"status\":\"idle\"}";
        pSyncControl->setValue((uint8_t*)resp, strlen(resp));
        pSyncControl->notify();

        g_transfer_done = true;
    }
}

// ── 服务端回调 ──
class ServerCB : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *srv, NimBLEConnInfo &connInfo) override {
        g_connected = true;
        // 请求 7.5ms 连接间隔（参数单位 1.25ms）
        srv->updateConnParams(connInfo.getConnHandle(), 6, 6, 0, 200);
        Serial.println("[BLE] 手机已连接! 请求连接间隔 7.5ms");
    }
    void onDisconnect(NimBLEServer *srv, NimBLEConnInfo &connInfo, int reason) override {
        g_connected = false;
        Serial.printf("[BLE] 手机断开, reason=%d\n", reason);
        // 断开后重新广播（防御已在清理流程中的 nullptr）
        if (!g_transfer_done && pServer != nullptr) {
            pServer->startAdvertising();
        }
    }
};

// ── FileIndex 读回调（GATT Read 时同步构建文件列表）──
// 铁律：列表内容必须装进单次 Read 响应（MTU 517 - ATT 头 = 516B，按 512 封顶）。
// 教训：曾经把 buffer 扩到 640，内容超过特征值默认 max_len(512) 后
// setValue 静默失败，手机读到 0 字节，误判"无新文件"结束会话。
// 文件多了宁可截断留到下周期（进度是连续推进的），绝不能撑爆。
class FileIndexCB : public NimBLECharacteristicCallbacks {
    void onRead(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override {
        char json[512];
        build_file_list_json(json, sizeof(json));
        // 本版本库 setValue 返回 void，无成败可查 —— 所以 512 上限就是生命线，
        // build_file_list_json 内部的 60 字节余量保证内容绝不会超过它
        pChar->setValue((uint8_t*)json, strlen(json));
        Serial.printf("[BLE-READ] 文件列表: %d 字节\n", strlen(json));
    }
};

// ── FileTransfer 读回调（GATT Read 时同步读取文件块）──
class FileTransferCB : public NimBLECharacteristicCallbacks {
    void onRead(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override {
        uint16_t file_id = g_cmd_file_id;
        uint32_t offset  = g_cmd_offset;
        uint16_t chunk   = g_cmd_chunk_size;
        if (chunk > 8192) chunk = 8192;  // 8KB 大块（BLE 长读自动分包）

        char fname[32];
        sd_next_filename(file_id, fname, sizeof(fname));
        FILE *f = fopen(fname, "rb");
        if (!f) {
            const char *eof = "{\"eof\":true}";
            pChar->setValue((uint8_t*)eof, strlen(eof));
            return;
        }

        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        if (offset >= (uint32_t)fsize) {
            fclose(f);
            const char *eof = "{\"eof\":true}";
            pChar->setValue((uint8_t*)eof, strlen(eof));
            return;
        }

        // 大块传输：每次最多 8192 字节（BLE 长读自动分包，约 16 个 MTU 包）
        uint16_t maxChunk = (chunk > 8192) ? 8192 : chunk;
        fseek(f, offset, SEEK_SET);
        uint8_t *data = (uint8_t*)malloc(maxChunk);
        if (!data) { fclose(f); return; }
        size_t n = fread(data, 1, maxChunk, f);
        fclose(f);

        if (n > 0) {
            // 块格式: [4B id LE][4B offset LE][2B len LE][data...]
            uint8_t *pkt = (uint8_t*)malloc(10 + n);
            if (pkt) {
                pkt[0] = file_id & 0xFF;
                pkt[1] = (file_id >> 8) & 0xFF;
                pkt[2] = 0; pkt[3] = 0;
                pkt[4] = offset & 0xFF;
                pkt[5] = (offset >> 8) & 0xFF;
                pkt[6] = (offset >> 16) & 0xFF;
                pkt[7] = (offset >> 24) & 0xFF;
                pkt[8] = n & 0xFF;
                pkt[9] = (n >> 8) & 0xFF;
                memcpy(pkt + 10, data, n);
                pChar->setValue(pkt, 10 + n);
                g_files_sent++;
                free(pkt);
            }
        }
        free(data);
        // 推进偏移量，方便手机连续 GATT Read 无需每次重发 pull
        if (offset + n >= (uint32_t)fsize) {
            g_cmd_offset = fsize;  // 下次 Read 返回 EOF
        } else {
            g_cmd_offset = offset + n;
        }
        Serial.printf("[BLE-READ] 发送块: file=%u offset=%u len=%u\n", file_id, offset, n);
    }
};

// ── SyncControl 写回调 ──
class SyncControlCB : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override {
        std::string val = pChar->getValue();
        const char *json = val.c_str();
        Serial.printf("[BLE-WRITE] 收到: %s\n", json);

        // 缓存完整 JSON（config 等复杂命令需要完整内容，主循环处理时不用再拆字段）
        strncpy(g_cmd_json, json, sizeof(g_cmd_json) - 1);
        g_cmd_json[sizeof(g_cmd_json) - 1] = '\0';

        // 解析 cmd 字段
        const char *cmd_start = strstr(json, "\"cmd\":\"");
        if (!cmd_start) return;
        cmd_start += 7;  // 跳过 "cmd":"

        const char *cmd_end = strchr(cmd_start, '"');
        if (!cmd_end) return;
        size_t len = cmd_end - cmd_start;
        if (len >= sizeof(g_cmd_type)) len = sizeof(g_cmd_type) - 1;
        memcpy(g_cmd_type, cmd_start, len);
        g_cmd_type[len] = '\0';

        // 提取参数 — 注意 last_id 复用 file_id 字段（sync_done/progress 命令用）
        if (strcmp(g_cmd_type, "sync_done") == 0 || strcmp(g_cmd_type, "progress") == 0) {
            g_cmd_file_id = json_get_int(json, "last_id", 0);
        } else {
            g_cmd_file_id    = json_get_int(json, "file_id", 0);
            g_cmd_offset     = json_get_int(json, "offset", 0);
            g_cmd_chunk_size = json_get_int(json, "chunk_size", 488);
            g_cmd_max_chunks = json_get_int(json, "max_chunks", 0);  // 0=不限，兼容旧端
        }

        // 内存屏障：确保 cmd_type/file_id/offset/chunk_size 写入对主循环可见
        __sync_synchronize();
        g_cmd_pending = true;
    }
};

// ── 公开接口 ──

bool ble_run_sync_session(uint32_t timeout_ms) {
    g_transfer_done = false;
    g_connected     = false;
    g_files_sent    = 0;
    g_cmd_pending   = false;

    // 初始化 NimBLE
    NimBLEDevice::init(BLE_DEVICE_NAME_DEFAULT);
    // 调整发射功率 (ESP32 默认 +3dBm)
    NimBLEDevice::setPower(ESP_PWR_LVL_P3);

    pServer = NimBLEDevice::createServer();
    g_serverCb = new ServerCB();
    pServer->setCallbacks(g_serverCb);

    // 协商 MTU 517（BLE 4.2 最大），单包 514 字节有效载荷
    NimBLEDevice::setMTU(517);

    // 创建 Service
    pService = pServer->createService(SERVICE_UUID);

    // SyncControl Characteristic (READ | WRITE | NOTIFY)
    pSyncControl = pService->createCharacteristic(
        CHAR_SYNC_CONTROL,
        NIMBLE_PROPERTY::READ |
        NIMBLE_PROPERTY::WRITE |
        NIMBLE_PROPERTY::NOTIFY
    );
    g_syncControlCb = new SyncControlCB();
    pSyncControl->setCallbacks(g_syncControlCb);
    const char *init_status = "{\"status\":\"idle\"}";
    pSyncControl->setValue((const uint8_t*)init_status, strlen(init_status));

    // FileIndex Characteristic (READ | NOTIFY — 文件列表通过 GATT Read 返回)
    pFileIndex = pService->createCharacteristic(
        CHAR_FILE_INDEX,
        NIMBLE_PROPERTY::READ |
        NIMBLE_PROPERTY::NOTIFY
    );
    g_fileIndexCb = new FileIndexCB();
    pFileIndex->setCallbacks(g_fileIndexCb);  // 必须在 createCharacteristic 之后！

    // FileTransfer Characteristic (READ | NOTIFY — GATT Read 为主，NOTIFY 兼容旧缓存)
    pFileTransfer = pService->createCharacteristic(
        CHAR_FILE_TRANSFER,
        NIMBLE_PROPERTY::READ |
        NIMBLE_PROPERTY::NOTIFY
    );
    g_fileTransferCb = new FileTransferCB();
    pFileTransfer->setCallbacks(g_fileTransferCb);

    // DeviceInfo Characteristic (READ)
    pDeviceInfo = pService->createCharacteristic(
        CHAR_DEVICE_INFO,
        NIMBLE_PROPERTY::READ
    );
    char dev_info[512];
    build_device_info_json(dev_info, sizeof(dev_info));
    pDeviceInfo->setValue((const uint8_t*)dev_info, strlen(dev_info));

    // 启动 Service
    pService->start();

    // 构建广播数据
    NimBLEAdvertising *pAdv = NimBLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);

    // 降低广播间隔以省电
    pAdv->setMinInterval(160);  // 100ms
    pAdv->setMaxInterval(200);  // 125ms

    pServer->startAdvertising();
    uint32_t start = millis();
    uint32_t last_activity = start;
    uint32_t last_blink = start;
    bool led_state = false;

    // 主循环：等待连接 + 处理命令 + 等待传输完成
    // 超时按「无命令活动」计算 —— 传输进行中会话自动续期，
    // 不会再出现 300 秒窗口到点把传了一半的连接切断
    while (millis() - last_activity < timeout_ms) {
        // 有新连接时更新广播状态
        if (g_connected && pServer->getConnectedCount() > 0) {
            // 已连接：红灯常亮
            led_on();
            if (g_cmd_pending) {
                process_command();
                last_activity = millis();  // 有命令往来就续期
            }
        } else {
            // 公告中：红灯闪烁 (~1.5 Hz)
            if (millis() - last_blink > 300) {
                led_state = !led_state;
                led_state ? led_on() : led_off();
                last_blink = millis();
            }
        }

        if (g_transfer_done) break;

        delay(5);  // 窗口化传输下命令往返频繁，轮询间隔小一点响应更快
    }

    // 清理 — deinit(true) 会释放所有 NimBLE 对象（含回调），不要手动 delete 防 double-free
    if (pServer) {
        pServer->stopAdvertising();
    }
    NimBLEDevice::deinit(true);
    g_serverCb = nullptr;
    g_syncControlCb = nullptr;
    g_fileIndexCb = nullptr;
    g_fileTransferCb = nullptr;
    pServer = nullptr;
    pService = nullptr;
    pSyncControl = nullptr;
    pFileIndex = nullptr;
    pFileTransfer = nullptr;
    pDeviceInfo = nullptr;

    return g_transfer_done;
}

int ble_files_transferred() {
    return g_files_sent;
}
