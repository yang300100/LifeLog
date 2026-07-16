#include "ble_service.h"
#include "sd_storage.h"
#include "config.h"
#include <NimBLEDevice.h>
#include <stdio.h>
#include <string.h>

// ── UUID 定义 ──
#define SERVICE_UUID      "0000ff00-0000-1000-8000-00805f9b34fb"
#define CHAR_SYNC_CONTROL "0000ff01-0000-1000-8000-00805f9b34fb"
#define CHAR_FILE_INDEX   "0000ff02-0000-1000-8000-00805f9b34fb"
#define CHAR_FILE_TRANSFER "0000ff03-0000-1000-8000-00805f9b34fb"
#define CHAR_DEVICE_INFO  "0000ff04-0000-1000-8000-00805f9b34fb"

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

// volatile：在 BLE 回调中写入，主循环中读取，防止编译器优化掉内存访问
static volatile bool  g_connected      = false;
static volatile bool  g_transfer_done  = false;
static int   g_files_sent     = 0;

// ── 命令队列 (从回调写入，主循环读取) ──
static volatile bool g_cmd_pending = false;
static char   g_cmd_type[16];
static uint16_t g_cmd_file_id;
static uint32_t g_cmd_offset;
static uint16_t g_cmd_chunk_size;

// ── 简易 JSON 解析 ──
// 从 JSON 字符串中提取指定 key 的整数值
static int json_get_int(const char *json, const char *key, int default_val) {
    char search[32];
    snprintf(search, sizeof(search), "\"%s\":", key);
    const char *p = strstr(json, search);
    if (!p) return default_val;
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

        // 防御 buffer 溢出（bufsz 必须大于 60 字节才有意义）
        if (bufsz <= 60 || pos >= (int)bufsz - 60) break;

        if (!first) buf[pos++] = ',';
        first = false;
        pos += snprintf(buf + pos, bufsz - pos,
            "{\"id\":%u,\"size\":%ld}", id, fsize);
    }
    // 安全写入结尾
    if (pos < (int)bufsz - 2) {
        pos += snprintf(buf + pos, bufsz - pos, "]}");
    } else {
        snprintf(buf + bufsz - 3, 3, "]}");
    }
}

// ── 设备信息 JSON ──
static void build_device_info_json(char *buf, size_t bufsz) {
    uint16_t last_id, last_synced;
    sd_index_read(&last_id, &last_synced);
    snprintf(buf, bufsz,
        "{\"name\":\"" BLE_DEVICE_NAME_DEFAULT "\","
        "\"fw_version\":\"1.0\","
        "\"total_clips\":%u,"
        "\"sd_free_mb\":%u}",
        last_id, sd_free_mb());
}

// ── 发送文件块 ──
// 块格式: [4B file_id LE][4B offset LE][2B chunk_len LE][data...]
// 注：当前 file_id 为 uint16_t（clip ID 从 1 递增），高 2 字节始终为 0
// 若未来升级为 32-bit ID，需同步修改参数类型和 Android 端解析
static void send_file_chunk(uint16_t file_id, uint32_t offset,
                            const uint8_t *data, uint16_t len) {
    // 头 10 字节 + 数据
    uint8_t pkt[512];  // 足够大
    pkt[0] = file_id & 0xFF;
    pkt[1] = (file_id >> 8) & 0xFF;
    pkt[2] = (file_id >> 16) & 0xFF;
    pkt[3] = (file_id >> 24) & 0xFF;

    pkt[4] = offset & 0xFF;
    pkt[5] = (offset >> 8) & 0xFF;
    pkt[6] = (offset >> 16) & 0xFF;
    pkt[7] = (offset >> 24) & 0xFF;

    pkt[8] = len & 0xFF;
    pkt[9] = (len >> 8) & 0xFF;

    memcpy(pkt + 10, data, len);

    pFileTransfer->setValue(pkt, 10 + len);
    pFileTransfer->notify();
}

// ── 发送 EOF 通知 ──
static void send_eof(uint16_t file_id) {
    char buf[64];
    snprintf(buf, sizeof(buf), "{\"eof\":true,\"file_id\":%u}", file_id);
    pFileTransfer->setValue((uint8_t*)buf, strlen(buf));
    pFileTransfer->notify();
}

// ── 处理命令 (在主循环中调用) ──
static void process_command() {
    if (!g_cmd_pending) return;
    g_cmd_pending = false;

    char *cmd = g_cmd_type;

    if (strcmp(cmd, "list") == 0) {
        char json[512];
        build_file_list_json(json, sizeof(json));
        pFileIndex->setValue((uint8_t*)json, strlen(json));
        pFileIndex->notify();

    } else if (strcmp(cmd, "pull") == 0) {
        uint16_t file_id = g_cmd_file_id;
        uint32_t offset  = g_cmd_offset;
        uint16_t chunk   = g_cmd_chunk_size;

        if (chunk > 492) chunk = 492;  // 限制在 MTU 安全范围

        char fname[32];
        sd_next_filename(file_id, fname, sizeof(fname));
        FILE *f = fopen(fname, "rb");
        if (!f) {
            // 文件不存在，发 EOF
            send_eof(file_id);
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
        uint8_t data[492];
        size_t n = fread(data, 1, chunk, f);
        fclose(f);

        if (n > 0) {
            send_file_chunk(file_id, offset, data, n);
            g_files_sent++;
        }
        if (offset + n >= (uint32_t)fsize) {
            send_eof(file_id);
        }

    } else if (strcmp(cmd, "sync_done") == 0) {
        uint16_t last_id = g_cmd_file_id;
        sd_index_update_last_synced(last_id);

        // 通知手机同步完成
        const char *resp = "{\"status\":\"idle\"}";
        pSyncControl->setValue((uint8_t*)resp, strlen(resp));
        pSyncControl->notify();

        g_transfer_done = true;
    }
}

// ── 服务端回调 ──
class ServerCB : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *srv, ble_gap_conn_desc *desc) override {
        g_connected = true;
    }
    void onDisconnect(NimBLEServer *srv) override {
        g_connected = false;
        // 断开后重新广播（防御已在清理流程中的 nullptr）
        if (!g_transfer_done && pServer != nullptr) {
            pServer->startAdvertising();
        }
    }
};

// ── SyncControl 写回调 ──
class SyncControlCB : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pChar) override {
        std::string val = pChar->getValue();
        const char *json = val.c_str();

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

        // 提取参数 — 注意 last_id 复用 file_id 字段（sync_done 命令用）
        if (strcmp(g_cmd_type, "sync_done") == 0) {
            g_cmd_file_id = json_get_int(json, "last_id", 0);
        } else {
            g_cmd_file_id    = json_get_int(json, "file_id", 0);
            g_cmd_offset     = json_get_int(json, "offset", 0);
            g_cmd_chunk_size = json_get_int(json, "chunk_size", 488);
        }

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

    // FileIndex Characteristic (READ | NOTIFY)
    pFileIndex = pService->createCharacteristic(
        CHAR_FILE_INDEX,
        NIMBLE_PROPERTY::READ |
        NIMBLE_PROPERTY::NOTIFY
    );

    // FileTransfer Characteristic (NOTIFY)
    pFileTransfer = pService->createCharacteristic(
        CHAR_FILE_TRANSFER,
        NIMBLE_PROPERTY::NOTIFY
    );

    // DeviceInfo Characteristic (READ)
    pDeviceInfo = pService->createCharacteristic(
        CHAR_DEVICE_INFO,
        NIMBLE_PROPERTY::READ
    );
    char dev_info[256];
    build_device_info_json(dev_info, sizeof(dev_info));
    pDeviceInfo->setValue((const uint8_t*)dev_info, strlen(dev_info));

    // 启动 Service
    pService->start();

    // 构建广播数据
    NimBLEAdvertising *pAdv = NimBLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);

    // 降低广播间隔以省电
    pAdv->setMinInterval(160);  // 100ms
    pAdv->setMaxInterval(200);  // 125ms

    pServer->startAdvertising();
    uint32_t start = millis();

    // 主循环：等待连接 + 处理命令 + 等待传输完成
    while (millis() - start < timeout_ms) {
        // 有新连接时更新广播状态
        if (g_connected && pServer->getConnectedCount() > 0) {
            // 已连接，处理命令
            process_command();
        }

        if (g_transfer_done) break;

        delay(20);
    }

    // 清理 — 先停止广播，再释放回调，最后 deinit
    if (pServer) {
        pServer->stopAdvertising();
    }
    // 释放回调对象防止内存泄漏
    if (g_syncControlCb) {
        delete g_syncControlCb;
        g_syncControlCb = nullptr;
    }
    if (g_serverCb) {
        delete g_serverCb;
        g_serverCb = nullptr;
    }
    NimBLEDevice::deinit(true);
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
