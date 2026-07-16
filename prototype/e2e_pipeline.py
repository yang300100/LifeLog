"""
LifeLog Companion — 完整 E2E 管线测试脚本

管线流程:
  1. 图像预处理 (缩放 640 短边)
  2. YOLOv8n-seg 实例分割
  3. 分割后处理 (mask映射, 按类分组, 深度层估计)
  4. 规则候选生成 (占用图, 地面图, standable搜索, 交互匹配)
  5. 标注图生成 (colored masks + 候选标记)
  6. Kimi k2.6 候选筛选 + 交互选择
  7. Seedream 5.0 多图融合生成

用法:
  python e2e_pipeline.py \
    --scene scene.jpg \
    --ref ref1.png ref2.png ref3.png \
    --kimi-key sk-xxx \
    --seedream-key ark-xxx
"""

import os
import sys
import io
import json
import time
import base64
import math
import argparse
from pathlib import Path
from urllib.request import urlretrieve
from dataclasses import dataclass, field, asdict
from typing import Optional

# 修复 Windows GBK 终端
if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

import cv2
import numpy as np
import torch

# ── 修复 torchvision NMS 不兼容问题 ──
# 在 import ultralytics 之前 monkey-patch torchvision.ops.nms
try:
    import torchvision.ops as tv_ops
    _original_nms = tv_ops.nms
except ImportError:
    _original_nms = None

def _patched_nms(boxes, scores, iou_threshold):
    """纯 PyTorch 实现的 NMS，避免 torchvision C++ ops 兼容性问题"""
    if boxes.numel() == 0:
        return torch.empty((0,), dtype=torch.int64, device=boxes.device)

    # 按 score 降序
    _, order = scores.sort(0, descending=True)
    boxes = boxes[order]

    keep = []
    while order.numel() > 0:
        if order.numel() == 1:
            keep.append(order.item())
            break
        i = order[0]
        keep.append(i.item())

        # 计算 i 与其余框的 IoU
        x1 = boxes[1:, 0].clamp(min=boxes[0, 0].item())
        y1 = boxes[1:, 1].clamp(min=boxes[0, 1].item())
        x2 = boxes[1:, 2].clamp(max=boxes[0, 2].item())
        y2 = boxes[1:, 3].clamp(max=boxes[0, 3].item())

        inter = (x2 - x1).clamp(min=0) * (y2 - y1).clamp(min=0)
        area1 = (boxes[0, 2] - boxes[0, 0]) * (boxes[0, 3] - boxes[0, 1])
        area2 = (boxes[1:, 2] - boxes[1:, 0]) * (boxes[1:, 3] - boxes[1:, 1])
        iou = inter / (area1 + area2 - inter + 1e-6)

        # 保留 IoU < threshold 的框
        mask = iou < iou_threshold
        boxes = boxes[1:][mask]
        order = order[1:][mask]

    return torch.tensor(keep, dtype=torch.int64, device=boxes.device)

# 应用 patch
import torchvision.ops
torchvision.ops.nms = _patched_nms

from ultralytics import YOLO
from openai import OpenAI

# ── 配置 ─────────────────────────────────────────────────

# Pipeline output
OUTPUT_DIR = Path(__file__).parent / "e2e_output"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# YOLO
YOLO_MODEL = os.path.expanduser("~/.cache/ultralytics/yolov8n-seg.pt")
YOLO_INPUT_SIZE = 640
YOLO_CONF = 0.25
YOLO_IOU = 0.45

# Kimi
KIMI_BASE_URL = "https://api.moonshot.cn/v1"
KIMI_MODEL = "kimi-k2.6"

# Seedream
SEEDREAM_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
SEEDREAM_MODEL = "doubao-seedream-5-0-260128"

# ── COCO 类别定义 ────────────────────────────────────────

# 必须避开的障碍物 (占用图中标记为 1)
OBSTACLE_CLASSES = {
    0: "person",       # 人物 — 避开
    56: "chair",        # 椅子 — 可能占用
    57: "couch",        # 沙发
    58: "potted plant", # 盆栽 — 前景物
    2: "bicycle",       # 自行车
    3: "car",           # 汽车
    4: "motorcycle",    # 摩托车
    5: "airplane",
    6: "bus",
    7: "train",
    8: "truck",
    9: "boat",
    43: "knife",        # 刀 — 不安全
    76: "scissors",     # 剪刀
}

# 可互动物体
INTERACTABLE_CLASSES = {
    41: {"name": "cup", "action": "探头好奇地看着{位置}的杯子"},
    39: {"name": "bottle", "action": "好奇地看向{位置}的水瓶"},
    40: {"name": "wine glass", "action": "歪头看着{位置}的酒杯"},
    44: {"name": "spoon", "action": ""},
    73: {"name": "book", "action": "凑过去看{位置}摊开的书"},
    62: {"name": "tv", "action": "歪着头好奇地看着{位置}的屏幕"},
    63: {"name": "laptop", "action": "歪着头好奇地看着{位置}的笔记本电脑"},
    60: {"name": "dining table", "action": "双手撑在{位置}桌边，身体微微前倾"},
    66: {"name": "keyboard", "action": "手指轻轻放在{位置}键盘上，歪头看向用户"},
    67: {"name": "cell phone", "action": "歪头看向{位置}的手机屏幕"},
    58: {"name": "potted plant", "action": "蹲在{位置}盆栽旁，伸手轻触叶子"},
    56: {"name": "chair", "action": "放松地靠在{位置}椅子旁，手搭在椅背上"},
    57: {"name": "couch", "action": "放松地靠在{位置}沙发旁"},
    15: {"name": "bench", "action": "放松地站在{位置}长椅旁"},
    75: {"name": "vase", "action": "欣赏{位置}的花瓶"},
    77: {"name": "teddy bear", "action": "开心地看向{位置}的泰迪熊"},
}

# 地面类别 (可站立)
GROUND_CLASSES = {
    0: "road",        # 道路 (COCO 里没有专门的 road, 用 0 占位)
    9: "grass",       # 没有；COCO 不区分地面
}

# 实际上 COCO 没有地面类，我们依赖 Y 坐标启发式
# 但在室内场景，检测到 floor 类会很有用

# 不可交互黑名单
INTERACTION_BLACKLIST = {43, 76, 61, 0}  # knife, scissors, toilet, person


# ── 数据结构 ─────────────────────────────────────────────

@dataclass
class DetectedObject:
    """YOLO 检测到的单个物体"""
    id: int
    class_id: int
    class_name: str
    bbox: tuple          # (x1, y1, x2, y2) — 已映射到原图坐标
    center: tuple        # (cx, cy)
    area: float
    mask: np.ndarray     # 二进制 mask (原图尺寸)
    depth_layer: str     # "foreground" / "midground" / "background"
    confidence: float
    is_interactable: bool = False
    interaction_templates: list = field(default_factory=list)


@dataclass
class CandidatePosition:
    """候选插入位置"""
    id: int
    center_x: int
    center_y: int
    x_ratio: float
    y_ratio: float
    region_description: str
    standable_score: float
    nearby_objects: list = field(default_factory=list)
    interaction_suggestions: list = field(default_factory=list)


@dataclass
class KimiSelection:
    """Kimi 筛选结果"""
    best_candidate_id: int
    selected_interaction: dict
    person_facing: str
    light_direction: str
    confidence: float
    evaluations: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    raw_response: str = ""


# ── 工具函数 ─────────────────────────────────────────────

def image_to_base64(path: str) -> str:
    ext = Path(path).suffix.lower().lstrip(".")
    mime_map = {"jpg": "image/jpeg", "jpeg": "image/jpeg",
                 "png": "image/png", "webp": "image/webp"}
    mime = mime_map.get(ext, "image/jpeg")
    with open(path, "rb") as f:
        return f"data:{mime};base64,{base64.b64encode(f.read()).decode('utf-8')}"


def pil_to_base64(pil_image, fmt="PNG") -> str:
    """PIL Image → Base64 Data URL"""
    import io as _io
    from PIL import Image
    buf = _io.BytesIO()
    pil_image.save(buf, format=fmt)
    b64 = base64.b64encode(buf.getvalue()).decode("utf-8")
    mime = f"image/{fmt.lower()}"
    return f"data:{mime};base64,{b64}"


def download_result(url: str, save_path: Path) -> bool:
    try:
        import requests as req
        resp = req.get(url, timeout=60)
        resp.raise_for_status()
        save_path.write_bytes(resp.content)
        print(f"  ✅ 已保存: {save_path} ({len(resp.content)/1024:.1f} KB)")
        return True
    except Exception as e:
        print(f"  ❌ 下载失败: {e}")
        print(f"  🌐 图片URL: {url}")
        return False


# ═══════════════════════════════════════════════════════════
# Step 1: 图像预处理
# ═══════════════════════════════════════════════════════════

class ImagePreprocessor:
    """图像预处理：读取 + 缩放至 640 短边 (YOLO 推理用)"""

    @staticmethod
    def process(image_path: str) -> tuple[np.ndarray, np.ndarray, float, tuple]:
        """
        Returns:
            img_yolo: 缩放后的 RGB 图像 (用于 YOLO)
            img_orig: 原始 RGB 图像
            scale: 缩放比例
            orig_size: (H, W)
        """
        img = cv2.imread(image_path)
        if img is None:
            raise ValueError(f"无法读取图像: {image_path}")

        img_orig = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        h_orig, w_orig = img_orig.shape[:2]

        # 等比例缩放至短边 640
        scale = YOLO_INPUT_SIZE / min(h_orig, w_orig)
        new_h = int(h_orig * scale)
        new_w = int(w_orig * scale)
        img_yolo = cv2.resize(img_orig, (new_w, new_h), interpolation=cv2.INTER_AREA)

        print(f"  📐 图像预处理: {w_orig}x{h_orig} → {new_w}x{new_h} (scale={scale:.3f})")
        return img_yolo, img_orig, scale, (h_orig, w_orig)


# ═══════════════════════════════════════════════════════════
# Step 2: YOLOv8n-seg 实例分割
# ═══════════════════════════════════════════════════════════

class YOLOSegmenter:
    """YOLOv8n-seg 实例分割"""

    def __init__(self, model_path: str = YOLO_MODEL):
        print(f"  🤖 加载 YOLO 模型: {model_path}")
        if not os.path.isfile(model_path):
            raise FileNotFoundError(f"YOLO 模型不存在: {model_path}")
        self.model = YOLO(model_path, task="segment")
        self.model.to("cpu")  # 桌面端用 CPU

    def segment(self, img: np.ndarray, orig_size: tuple) -> list[DetectedObject]:
        """
        Args:
            img: 缩放后的 RGB 图像
            orig_size: 原始图像 (H, W)
        Returns:
            检测到的物体列表
        """
        h_orig, w_orig = orig_size
        h_yolo, w_yolo = img.shape[:2]

        results = self.model(
            img,
            conf=YOLO_CONF,
            iou=YOLO_IOU,
            max_det=100,
            verbose=False,
        )

        objects = []
        if not results or len(results) == 0:
            print("  ⚠️ YOLO 未检测到任何物体")
            return objects

        result = results[0]

        if result.masks is None or result.boxes is None:
            print("  ⚠️ YOLO 未检测到分割 mask")
            return objects

        boxes = result.boxes
        masks_data = result.masks.data.cpu().numpy()  # N x H_mask x W_mask
        classes = boxes.cls.cpu().numpy().astype(int)
        confs = boxes.conf.cpu().numpy()
        xyxy = boxes.xyxy.cpu().numpy()

        for i in range(len(boxes)):
            class_id = classes[i]
            class_name = self.model.names.get(class_id, f"class_{class_id}")

            # 坐标映射回原图
            scale_x = w_orig / w_yolo
            scale_y = h_orig / h_yolo
            x1 = int(xyxy[i][0] * scale_x)
            y1 = int(xyxy[i][1] * scale_y)
            x2 = int(xyxy[i][2] * scale_x)
            y2 = int(xyxy[i][3] * scale_y)
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            area = (x2 - x1) * (y2 - y1)

            # Mask 映射回原图
            mask_yolo = masks_data[i]
            mask_orig = cv2.resize(
                mask_yolo.astype(np.uint8),
                (w_orig, h_orig),
                interpolation=cv2.INTER_NEAREST,
            )
            mask_binary = (mask_orig > 0.5).astype(np.uint8)

            # 深度层估计
            if cy > 0.7 * h_orig:
                depth = "foreground"
            elif cy < 0.3 * h_orig:
                depth = "background"
            else:
                depth = "midground"

            # 可交互性
            is_interactable = (
                class_id in INTERACTABLE_CLASSES
                and class_id not in INTERACTION_BLACKLIST
            )
            interactions = []
            if is_interactable:
                info = INTERACTABLE_CLASSES[class_id]
                interactions.append(info)

            obj = DetectedObject(
                id=i,
                class_id=class_id,
                class_name=class_name,
                bbox=(x1, y1, x2, y2),
                center=(cx, cy),
                area=float(area),
                mask=mask_binary,
                depth_layer=depth,
                confidence=float(confs[i]),
                is_interactable=is_interactable,
                interaction_templates=interactions,
            )
            objects.append(obj)

        # 统计
        by_class = {}
        for o in objects:
            by_class[o.class_name] = by_class.get(o.class_name, 0) + 1
        print(f"  🔍 YOLO 检测到 {len(objects)} 个物体: {dict(sorted(by_class.items(), key=lambda x: -x[1]))}")
        return objects


# ═══════════════════════════════════════════════════════════
# Step 3 + 4: 规则候选生成
# ═══════════════════════════════════════════════════════════

class CandidateGenerator:
    """规则候选生成器"""

    def __init__(self, img_shape: tuple, objects: list[DetectedObject]):
        self.h, self.w = img_shape[:2]
        self.objects = objects

    def build_occupancy_map(self) -> np.ndarray:
        """构建占用图：障碍物区域 = 1"""
        occupied = np.zeros((self.h, self.w), dtype=np.uint8)
        for obj in self.objects:
            if obj.class_id in OBSTACLE_CLASSES or obj.class_name == "person":
                occupied[obj.mask > 0] = 1
        return occupied

    def build_ground_map(self) -> np.ndarray:
        """构建地面图：可站立区域 = 1 (Y坐标启发式 + 检测到的地面类别)"""
        ground = np.zeros((self.h, self.w), dtype=np.uint8)

        # 启发式: 画面下半部分可能是地面
        ground[self.h // 2:, :] = 1

        # 排除已占用区域
        occupied = self.build_occupancy_map()
        ground[occupied > 0] = 0

        return ground

    def find_standable_regions(self, ground_map: np.ndarray) -> list[dict]:
        """在 ground_map 中找到连通的可站立区域"""
        # 形态学闭运算，连接离散区域
        kernel = np.ones((7, 7), np.uint8)
        ground_closed = cv2.morphologyEx(ground_map, cv2.MORPH_CLOSE, kernel)

        # 连通域分析
        num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(
            ground_closed, connectivity=8
        )

        regions = []
        min_area = (self.w * self.h) * 0.01  # 至少占画面 1%

        for i in range(1, num_labels):  # 跳过背景(0)
            area = stats[i, cv2.CC_STAT_AREA]
            if area < min_area:
                continue

            x1 = stats[i, cv2.CC_STAT_LEFT]
            y1 = stats[i, cv2.CC_STAT_TOP]
            x2 = x1 + stats[i, cv2.CC_STAT_WIDTH]
            y2 = y1 + stats[i, cv2.CC_STAT_HEIGHT]
            cx = int(centroids[i, 0])
            cy = int(centroids[i, 1])

            regions.append({
                "bbox": (x1, y1, x2, y2),
                "center": (cx, cy),
                "area": int(area),
                "area_ratio": area / (self.w * self.h),
            })

        regions.sort(key=lambda r: -r["area"])
        return regions

    def _describe_position(self, cx: int, cy: int) -> str:
        """将像素坐标转为自然语言区域描述"""
        w, h = self.w, self.h

        # 水平定位
        if cx < w * 0.33:
            h_desc = "画面左侧"
        elif cx > w * 0.66:
            h_desc = "画面右侧"
        else:
            h_desc = "画面中央"

        # 垂直定位
        if cy < h * 0.33:
            v_desc = "上方"
        elif cy > h * 0.66:
            v_desc = "下方"
        else:
            v_desc = "中部"

        # 精确区域描述
        if h_desc == "画面中央" and v_desc == "下方":
            return "画面中央偏下"
        return f"{h_desc}{v_desc}"

    def find_nearby_objects(self, cx: int, cy: int, max_dist: float = None) -> list[dict]:
        """搜索候选位置附近的可交互物体"""
        if max_dist is None:
            max_dist = max(self.w * 0.2, self.h * 0.2)

        nearby = []
        for obj in self.objects:
            if not obj.is_interactable:
                continue

            ox, oy = obj.center
            dist = math.sqrt((cx - ox)**2 + (cy - oy)**2)

            if dist < max_dist:
                dx = ox - cx
                dy = oy - cy
                if abs(dx) > abs(dy):
                    rel_pos = "右侧" if dx > 0 else "左侧"
                else:
                    rel_pos = "下方" if dy > 0 else "上方"

                for tmpl in obj.interaction_templates:
                    nearby.append({
                        "object": tmpl["name"],
                        "class": obj.class_name,
                        "relative_position": rel_pos,
                        "distance_px": int(dist),
                        "action_template": tmpl["action"].replace("{位置}", rel_pos),
                    })

        return nearby

    def generate_candidates(self, max_candidates: int = 5) -> list[CandidatePosition]:
        """生成候选位置列表"""
        print(f"\n  📍 规则候选生成...")

        ground_map = self.build_ground_map()
        occupied_map = self.build_occupancy_map()

        # 计算占用比例
        occupied_ratio = occupied_map.sum() / occupied_map.size
        print(f"      占用比例: {occupied_ratio:.1%}")
        print(f"      地面区域: {ground_map.sum() / ground_map.size:.1%}")

        # 找到地面区域
        regions = self.find_standable_regions(ground_map)

        if not regions:
            print("  ⚠️ 未找到可站立区域，回退到三分法候选")
            return self._fallback_candidates()

        region_summaries = [f'#{i} area={r["area_ratio"]:.1%}' for i, r in enumerate(regions[:5])]
        print(f"      找到 {len(regions)} 个可站立区域: {region_summaries}")

        # 从最大的 3 个区域生成候选
        candidates = []
        candidate_id = 1
        top_regions = regions[:3]

        for region in top_regions:
            cx, cy = region["center"]

            # 如果区域足够大，再尝试在区域内找三分法对齐点
            points = [(cx, cy)]
            w_third = self.w / 3
            h_third = self.h * 2 / 3
            rule_thirds = [
                (int(w_third), int(h_third)),
                (int(2 * w_third), int(h_third)),
            ]
            for tx, ty in rule_thirds:
                if (region["bbox"][0] <= tx <= region["bbox"][2]
                        and region["bbox"][1] <= ty <= region["bbox"][3]):
                    points.append((tx, ty))

            for px, py in set(points):
                if candidate_id > max_candidates:
                    break

                pos_desc = self._describe_position(px, py)
                nearby = self.find_nearby_objects(px, py)

                # standable 评分 (基于区域大小和位置)
                standable = region["area_ratio"] * (1.0 - abs(py / self.h - 0.6) * 1.5)

                candidates.append(CandidatePosition(
                    id=candidate_id,
                    center_x=px,
                    center_y=py,
                    x_ratio=px / self.w,
                    y_ratio=py / self.h,
                    region_description=f"{pos_desc}，{region['area_ratio']:.0%}面积的空地",
                    standable_score=round(standable, 3),
                    nearby_objects=[o["object"] for o in nearby],
                    interaction_suggestions=nearby,
                ))
                candidate_id += 1

        # 如果候选不够，从其他区域补充
        if len(candidates) < 3 and len(regions) > 3:
            for region in regions[3:]:
                if candidate_id > max_candidates:
                    break
                cx, cy = region["center"]
                pos_desc = self._describe_position(cx, cy)
                nearby = self.find_nearby_objects(cx, cy)
                standable = region["area_ratio"] * 0.8
                candidates.append(CandidatePosition(
                    id=candidate_id,
                    center_x=cx,
                    center_y=cy,
                    x_ratio=cx / self.w,
                    y_ratio=cy / self.h,
                    region_description=f"{pos_desc}，{region['area_ratio']:.0%}面积的空地",
                    standable_score=round(standable, 3),
                    nearby_objects=[o["object"] for o in nearby],
                    interaction_suggestions=nearby,
                ))
                candidate_id += 1

        if not candidates:
            return self._fallback_candidates()

        print(f"      生成 {len(candidates)} 个候选位置:")
        for c in candidates:
            objs = ", ".join(c.nearby_objects[:3]) if c.nearby_objects else "无"
            print(f"        #{c.id}: {c.region_description} | 附近: {objs}")

        return candidates

    def _fallback_candidates(self) -> list[CandidatePosition]:
        """回退：纯三分法候选"""
        w, h = self.w, self.h
        points = [
            (int(w/3), int(h*2/3), "画面左下区域"),
            (int(w/2), int(h*2/3), "画面中央偏下"),
            (int(2*w/3), int(h*2/3), "画面右下区域"),
        ]
        candidates = []
        for i, (px, py, desc) in enumerate(points):
            candidates.append(CandidatePosition(
                id=i + 1,
                center_x=px, center_y=py,
                x_ratio=px / w, y_ratio=py / h,
                region_description=desc,
                standable_score=0.5,
            ))
        return candidates


# ═══════════════════════════════════════════════════════════
# Step 5: 标注图生成
# ═══════════════════════════════════════════════════════════

class AnnotatedImageGenerator:
    """生成分割标注图 + 候选标记"""

    # 颜色映射
    COLOR_OBSTACLE = (220, 50, 50, 100)      # 红色 — 障碍物 (person, car...)
    COLOR_INTERACTABLE = (50, 100, 220, 100)  # 蓝色 — 可互动物体
    COLOR_GROUND = (50, 200, 50, 60)          # 绿色 — 地面区域 (轻)
    COLOR_CANDIDATE = (255, 220, 50, 255)     # 黄色 — 候选位置

    @staticmethod
    def generate(
        img_orig: np.ndarray,
        objects: list[DetectedObject],
        candidates: list[CandidatePosition],
        ground_map: np.ndarray = None,
        occupied_map: np.ndarray = None,
    ) -> np.ndarray:
        """生成标注图"""
        from PIL import Image, ImageDraw, ImageFont

        h, w = img_orig.shape[:2]
        overlay = Image.fromarray(img_orig.copy()).convert("RGBA")

        # 1. 地面蒙版 (绿色半透明)
        if ground_map is not None:
            ground_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            ground_pixels = ground_layer.load()
            green_mask = np.where(ground_map > 0)
            for y, x in zip(green_mask[0], green_mask[1]):
                if y < h and x < w:
                    ground_pixels[x, y] = (50, 200, 50, 50)
            overlay = Image.alpha_composite(overlay, ground_layer)

        # 2. 障碍物蒙版 (红色半透明)
        obs_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        obs_pixels = obs_layer.load()
        for obj in objects:
            if obj.class_id in OBSTACLE_CLASSES or obj.class_name == "person":
                ys, xs = np.where(obj.mask > 0)
                for y, x in zip(ys, xs):
                    if y < h and x < w:
                        obs_pixels[x, y] = (220, 50, 50, 80)
        overlay = Image.alpha_composite(overlay, obs_layer)

        # 3. 可互动物体 (蓝色标框)
        draw = ImageDraw.Draw(overlay)
        for obj in objects:
            if obj.is_interactable:
                x1, y1, x2, y2 = obj.bbox
                draw.rectangle([x1, y1, x2, y2], outline=(50, 100, 220, 200), width=3)
                draw.text((x1, max(0, y1 - 18)), obj.class_name, fill=(50, 100, 220, 255))

        # 4. 候选位置 (黄色圆点 + 编号)
        for c in candidates:
            cx, cy = c.center_x, c.center_y
            r = max(12, min(w, h) // 60)

            # 外圈 (黄色)
            draw.ellipse([cx - r, cy - r, cx + r, cy + r],
                         outline=(255, 220, 50, 220), width=3)
            # 内点
            draw.ellipse([cx - 4, cy - 4, cx + 4, cy + 4],
                         fill=(255, 220, 50, 255))

            # 编号
            try:
                font = ImageFont.truetype("arial.ttf", size=max(18, r * 2))
            except Exception:
                font = ImageFont.load_default()

            # 编号背景
            text = str(c.id)
            bbox_text = draw.textbbox((0, 0), text, font=font)
            tw = bbox_text[2] - bbox_text[0]
            th = bbox_text[3] - bbox_text[1]
            draw.rectangle(
                [cx - tw//2 - 3, cy - r - th - 6, cx + tw//2 + 3, cy - r - 2],
                fill=(255, 220, 50, 230),
            )
            draw.text((cx - tw//2, cy - r - th - 4), text, fill=(0, 0, 0, 255), font=font)

        return np.array(overlay.convert("RGB"))


# ═══════════════════════════════════════════════════════════
# Step 6: Kimi 候选筛选
# ═══════════════════════════════════════════════════════════

KIMI_SELECTION_PROMPT = """你是一位摄影构图助手和虚拟陪伴角色的"行为导演"。

这张标注图是在原图上叠加了分割信息：
- 红色半透明 = 必须避开的人物/障碍物
- 蓝色方框 = 可互动的物体（杯子、书本、电脑等）
- 绿色半透明 = 地面/可站立区域
- 黄色圆点+编号 = 候选插入位置

以下结构化数据补充了每个候选的详细信息：
{candidate_json}

请完成以下任务：

1. **逐一审视**每个候选位置，综合考虑：
   - 位置自然度：角色站在这里是否合理？
   - 互动机会：附近有可互动的物体吗？互动是否自然、有陪伴感？
   - 遮挡风险：角色是否会被前景物体遮挡？
   - 光照方向：从场景光线推测，该位置是顺光/侧光/逆光？
   - 构图平衡：角色放在这里后整体画面是否平衡？

2. 如果候选附近有可互动物体，为角色选择一个**最自然、最有陪伴感**的互动动作。

3. 选出最佳候选，如果全部不合适返回 -1。

严格按以下 JSON 格式输出（不要 markdown 代码块）：
{
  "evaluations": [
    {"id": 1, "score": 4, "reason": "..."},
    {"id": 2, "score": 2, "reason": "..."}
  ],
  "best_candidate_id": 1,
  "selected_interaction": {
    "object": "cup",
    "detailed_description": "身体微微前倾，双手撑在桌边，探头好奇地看着桌上的杯子，嘴角带着温柔的笑意",
    "pose": "leaning_forward",
    "facing": "toward_right",
    "gaze_target": "水杯"
  },
  "person_facing": "toward_left",
  "light_direction": "from_left",
  "confidence": 0.85,
  "warnings": []
}"""


class KimiSelector:
    """Kimi k2.6 候选筛选器"""

    def __init__(self, api_key: str):
        self.api_key = api_key
        self.client = OpenAI(
            base_url=KIMI_BASE_URL,
            api_key=api_key,
        )

    def select(
        self,
        scene_base64: str,
        annotated_base64: str,
        candidates: list[CandidatePosition],
        timeout: int = 120,
    ) -> KimiSelection:
        """让 Kimi 从候选中选择最佳位置和交互"""

        # 构建候选 JSON
        candidate_data = []
        for c in candidates:
            interactions = []
            for s in c.interaction_suggestions[:3]:
                interactions.append({
                    "object": s["object"],
                    "action": s["action_template"],
                    "position": s["relative_position"],
                })
            candidate_data.append({
                "id": c.id,
                "description": c.region_description,
                "nearby_objects": c.nearby_objects[:5],
                "interaction_options": interactions,
                "coordinates": f"({c.center_x}, {c.center_y})",
            })

        prompt = KIMI_SELECTION_PROMPT.replace(
            "{candidate_json}",
            json.dumps(candidate_data, ensure_ascii=False, indent=2),
        )

        print(f"\n  🧠 调用 Kimi k2.6 筛选...")
        print(f"      候选数: {len(candidates)}")
        print(f"      Prompt 长度: {len(prompt)} 字符")

        try:
            resp = self.client.chat.completions.create(
                model=KIMI_MODEL,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": scene_base64}},
                        {"type": "image_url", "image_url": {"url": annotated_base64}},
                        {"type": "text", "text": prompt},
                    ],
                }],
                max_tokens=800,
                temperature=0.6,
                extra_body={
                    "thinking": {"type": "disabled"},
                },
                timeout=timeout,
            )

            content = resp.choices[0].message.content or ""
            print(f"      Kimi 原始响应长度: {len(content)} 字符")
            print(f"      响应预览: {content[:200]}...")

            return self._parse_response(content)

        except Exception as e:
            print(f"  ❌ Kimi 调用失败: {e}")
            return self._fallback(candidates)

    def _parse_response(self, content: str) -> KimiSelection:
        """解析 Kimi JSON 响应"""
        # 去 markdown 代码块
        json_str = content
        for marker in ["```json", "```"]:
            json_str = json_str.replace(marker, "")

        json_start = json_str.find('{')
        json_end = json_str.rfind('}') + 1
        if json_start >= 0 and json_end > json_start:
            json_str = json_str[json_start:json_end]

        try:
            data = json.loads(json_str)
            return KimiSelection(
                best_candidate_id=data.get("best_candidate_id", 1),
                selected_interaction=data.get("selected_interaction", {}),
                person_facing=data.get("person_facing", "toward_camera"),
                light_direction=data.get("light_direction", ""),
                confidence=data.get("confidence", 0.5),
                evaluations=data.get("evaluations", []),
                warnings=data.get("warnings", []),
                raw_response=content,
            )
        except json.JSONDecodeError as e:
            print(f"  ⚠️ JSON 解析失败: {e}")
            return KimiSelection(
                best_candidate_id=1,
                selected_interaction={},
                person_facing="toward_camera",
                light_direction="",
                confidence=0.3,
                raw_response=content,
            )

    def _fallback(self, candidates: list[CandidatePosition]) -> KimiSelection:
        """Kimi 失败时回退到规则 Top-1"""
        if not candidates:
            return KimiSelection(
                best_candidate_id=-1,
                selected_interaction={},
                person_facing="toward_camera",
                light_direction="",
                confidence=0,
            )
        best = max(candidates, key=lambda c: c.standable_score)
        return KimiSelection(
            best_candidate_id=best.id,
            selected_interaction=best.interaction_suggestions[0] if best.interaction_suggestions else {},
            person_facing="toward_camera",
            light_direction="",
            confidence=0.5,
        )


# ═══════════════════════════════════════════════════════════
# Step 7: Seedream 多图融合生成
# ═══════════════════════════════════════════════════════════

class SeedreamGenerator:
    """Seedream 5.0 多图融合"""

    def __init__(self, api_key: str):
        self.api_key = api_key
        self.client = OpenAI(
            base_url=SEEDREAM_BASE_URL,
            api_key=api_key,
        )

    def generate(
        self,
        scene_path: str,
        reference_paths: list[str],
        position_desc: str,
        interaction_desc: str,
        light_desc: str = "",
        size: str = "2k",
    ) -> str | None:
        """
        多图融合生成
        Returns:
            图片 URL 或 None
        """
        # 构建融合 prompt (有参考图模式，不含角色外观)
        parts = [
            f"将图中参考角色放在场景的{position_desc}",
        ]
        if interaction_desc:
            parts.append(interaction_desc)
        if light_desc:
            parts.append(f"匹配{light_desc}的光照")
        else:
            parts.append("匹配场景的自然光照方向和强度")

        parts += [
            "在角色脚下生成与场景光源方向一致的自然阴影",
            "与周围环境无缝融合",
            "匹配原始照片的光照色调和真实摄影风格",
            "人物比例符合场景透视，全身可见，稳稳站在地面上",
            "自然摄影，非插画",
            "无绿幕，无孤立人物，无明显拼贴边界",
        ]
        prompt = "，".join(parts)

        # 组装 image 数组
        images = [image_to_base64(scene_path)]
        for ref in reference_paths:
            images.append(image_to_base64(ref))

        print(f"\n  🎨 调用 Seedream 多图融合...")
        print(f"      场景图: 1 → 图1")
        for i, p in enumerate(reference_paths):
            print(f"      参考图{i+1}: {Path(p).name} → 图{i+2}")
        print(f"      Prompt: {prompt[:200]}...")

        try:
            resp = self.client.images.generate(
                model=SEEDREAM_MODEL,
                prompt=prompt,
                size=size,
                response_format="url",
                extra_body={
                    "image": images,
                    "watermark": False,
                    "sequential_image_generation": "disabled",
                },
                timeout=180,
            )

            url = resp.data[0].url
            if url:
                print(f"  ✅ Seedream 生成成功!")
                return url
            else:
                print(f"  ❌ Seedream 未返回 URL")
                return None

        except Exception as e:
            print(f"  ❌ Seedream 调用失败: {e}")
            return None


# ═══════════════════════════════════════════════════════════
# E2E Pipeline 编排
# ═══════════════════════════════════════════════════════════

class E2EPipeline:
    """完整端到端管线"""

    def __init__(
        self,
        scene_path: str,
        reference_paths: list[str],
        kimi_key: str,
        seedream_key: str,
        output_dir: Path = OUTPUT_DIR,
        size: str = "2k",
    ):
        self.scene_path = scene_path
        self.reference_paths = reference_paths
        self.kimi_key = kimi_key
        self.seedream_key = seedream_key
        self.output_dir = output_dir
        self.size = size

        self.output_dir.mkdir(parents=True, exist_ok=True)

    def run(self) -> dict:
        """运行完整管线"""
        ts = time.strftime("%Y%m%d_%H%M%S")
        print("=" * 70)
        print(f"🚀 LifeLog Companion E2E 管线 (完整流程)")
        print("=" * 70)
        print(f"  场景图: {self.scene_path}")
        print(f"  参考图: {len(self.reference_paths)} 张")
        print(f"  输出尺寸: {self.size}")
        print(f"  输出目录: {self.output_dir}")

        # ═══ Step 1: 图像预处理 ═══
        print("\n" + "─" * 50)
        print("📐 Step 1/7: 图像预处理")
        img_yolo, img_orig, scale, orig_size = ImagePreprocessor.process(self.scene_path)

        # ═══ Step 2: YOLO 分割 ═══
        print("\n" + "─" * 50)
        print("🤖 Step 2/7: YOLOv8n-seg 实例分割")
        segmenter = YOLOSegmenter()
        objects = segmenter.segment(img_yolo, orig_size)

        if not objects:
            print("  ⚠️ 无检测结果 → 回退纯三分法 + 跳过 Kimi")

        # ═══ Step 3 + 4: 候选生成 ═══
        print("\n" + "─" * 50)
        print("📍 Step 3/7: 规则候选生成")
        generator = CandidateGenerator(img_orig.shape, objects)
        candidates = generator.generate_candidates(max_candidates=5)

        # ═══ Step 5: 标注图 ═══
        print("\n" + "─" * 50)
        print("🎨 Step 4/7: 生成分割标注图")
        ground_map = generator.build_ground_map()
        occupied_map = generator.build_occupancy_map()
        annotated_img = AnnotatedImageGenerator.generate(
            img_orig, objects, candidates, ground_map, occupied_map,
        )

        # 保存中间结果
        from PIL import Image as PILImage
        annotated_path = self.output_dir / f"annotated_{ts}.png"
        PILImage.fromarray(annotated_img).save(annotated_path)
        print(f"  ✅ 标注图已保存: {annotated_path}")

        # 保存 YOLO 分割可视化
        seg_viz_path = self.output_dir / f"yolo_seg_{ts}.png"
        seg_viz = img_orig.copy()
        for obj in objects:
            colored_mask = np.zeros_like(seg_viz)
            if obj.class_id in OBSTACLE_CLASSES:
                colored_mask[obj.mask > 0] = [220, 50, 50]  # 红
            elif obj.is_interactable:
                colored_mask[obj.mask > 0] = [50, 100, 220]  # 蓝
            else:
                colored_mask[obj.mask > 0] = [100, 100, 100]  # 灰
            alpha = 0.4
            seg_viz = np.where(colored_mask > 0,
                               (seg_viz * (1 - alpha) + colored_mask * alpha).astype(np.uint8),
                               seg_viz)
            x1, y1, x2, y2 = obj.bbox
            cv2.rectangle(seg_viz, (x1, y1), (x2, y2), (0, 255, 0), 2)
            cv2.putText(seg_viz, obj.class_name, (x1, max(y1 - 5, 15)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
        PILImage.fromarray(seg_viz).save(seg_viz_path)
        print(f"  ✅ YOLO 分割图已保存: {seg_viz_path}")

        # ═══ Step 6: Kimi 筛选 ═══
        print("\n" + "─" * 50)
        print("🧠 Step 5/7: Kimi k2.6 候选筛选 + 交互选择")

        scene_b64 = image_to_base64(self.scene_path)
        annotated_b64 = pil_to_base64(PILImage.fromarray(annotated_img), "PNG")

        selector = KimiSelector(self.kimi_key)
        selection = selector.select(scene_b64, annotated_b64, candidates)

        # 找到最佳候选
        if selection.best_candidate_id > 0:
            best_candidate = next(
                (c for c in candidates if c.id == selection.best_candidate_id), None
            )
            if best_candidate is None:
                best_candidate = max(candidates, key=lambda c: c.standable_score)
        else:
            best_candidate = max(candidates, key=lambda c: c.standable_score)
            print(f"  ⚠️ Kimi 认为全部不合适 → 回退规则 Top-1")

        print(f"\n  📊 Kimi 评估结果:")
        for ev in selection.evaluations:
            print(f"      候选 {ev['id']}: 评分 {ev['score']}/5 — {ev.get('reason', '')[:60]}")
        print(f"      最佳候选: #{selection.best_candidate_id}")
        if selection.selected_interaction:
            inter = selection.selected_interaction
            print(f"      选择交互: {inter.get('object', '')} — {inter.get('detailed_description', '')[:80]}")
        print(f"      面向: {selection.person_facing}")
        print(f"      光源: {selection.light_direction}")
        print(f"      置信度: {selection.confidence:.2f}")

        # 保存 Kimi 响应
        kimi_resp_path = self.output_dir / f"kimi_response_{ts}.json"
        kimi_resp_path.write_text(json.dumps({
            "evaluations": selection.evaluations,
            "best_candidate_id": selection.best_candidate_id,
            "selected_interaction": selection.selected_interaction,
            "person_facing": selection.person_facing,
            "light_direction": selection.light_direction,
            "confidence": selection.confidence,
            "warnings": selection.warnings,
            "candidates": [
                {
                    "id": c.id,
                    "description": c.region_description,
                    "coords": (c.center_x, c.center_y),
                    "nearby": c.nearby_objects,
                    "score": c.standable_score,
                }
                for c in candidates
            ],
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  ✅ Kimi 响应已保存: {kimi_resp_path}")

        # ═══ Step 7: Seedream 生成 ═══
        print("\n" + "─" * 50)
        print("🎨 Step 6/7: Seedream 5.0 多图融合生成")

        # 从 Kimi 选择中提取位置描述
        pos_desc = best_candidate.region_description if best_candidate else "画面中央偏下"

        # 交互描述
        interaction_desc = ""
        if selection.selected_interaction:
            interaction_desc = selection.selected_interaction.get("detailed_description", "")

        if not interaction_desc and best_candidate and best_candidate.interaction_suggestions:
            interaction_desc = best_candidate.interaction_suggestions[0].get("action_template", "")

        if not interaction_desc:
            interaction_desc = "自然站立，面朝前方，带着温柔的微笑"

        # 光照描述
        light_desc = selection.light_direction if selection.light_direction else ""

        generator_sd = SeedreamGenerator(self.seedream_key)
        result_url = generator_sd.generate(
            scene_path=self.scene_path,
            reference_paths=self.reference_paths,
            position_desc=pos_desc,
            interaction_desc=interaction_desc,
            light_desc=light_desc,
            size=self.size,
        )

        # ═══ 保存最终结果 ═══
        print("\n" + "─" * 50)
        print("📦 Step 7/7: 保存最终结果")

        result = {
            "timestamp": ts,
            "candidates_count": len(candidates),
            "best_candidate": {
                "id": best_candidate.id if best_candidate else -1,
                "description": pos_desc,
                "coords": (best_candidate.center_x, best_candidate.center_y) if best_candidate else (0, 0),
            } if best_candidate else None,
            "kimi_selection": {
                "best_id": selection.best_candidate_id,
                "interaction": selection.selected_interaction,
                "facing": selection.person_facing,
                "light": selection.light_direction,
                "confidence": selection.confidence,
            },
        }

        if result_url:
            save_path = self.output_dir / f"final_result_{ts}.png"
            if download_result(result_url, save_path):
                result["output_path"] = str(save_path)
                result["url"] = result_url

        # 保存结果 JSON
        result_path = self.output_dir / f"pipeline_result_{ts}.json"
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  ✅ 管线结果已保存: {result_path}")

        print("\n" + "=" * 70)
        print("✅ E2E 管线完成!")
        print("=" * 70)
        print(f"  标注图: {annotated_path}")
        print(f"  YOLO 分割: {seg_viz_path}")
        print(f"  Kimi 响应: {kimi_resp_path}")
        if result_url:
            print(f"  🌟 最终结果: {result.get('output_path', 'N/A')}")
        print(f"  管线日志: {result_path}")

        return result


# ── 主入口 ───────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="LifeLog Companion E2E 完整管线测试",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python e2e_pipeline.py \\
    --scene scene_desktop.jpg \\
    --ref ref_desktop_01.png ref_desktop_02.png ref_desktop_03.png \\
    --kimi-key sk-xxx \\
    --seedream-key ark-xxx

环境变量:
  KIMI_API_KEY      Kimi API Key
  SEEDREAM_API_KEY  Seedream API Key
        """,
    )

    parser.add_argument("--scene", required=True, help="场景照片路径")
    parser.add_argument("--ref", nargs="+", required=True, help="角色参考图路径 (可多张)")
    parser.add_argument("--kimi-key", help="Kimi API Key (默认 $KIMI_API_KEY)")
    parser.add_argument("--seedream-key", help="Seedream API Key (默认 $SEEDREAM_API_KEY)")
    parser.add_argument("--size", default="2k",
                        choices=["2k", "3k", "4k", "1024x1024", "2048x2048"],
                        help="Seedream 输出尺寸 (默认: 2k)")
    parser.add_argument("--skip-kimi", action="store_true",
                        help="跳过 Kimi 筛选 (直接规则 Top-1)")
    parser.add_argument("--skip-seedream", action="store_true",
                        help="跳过 Seedream 生成 (仅分析)")

    args = parser.parse_args()

    # 验证输入
    for path_arg, name in [(args.scene, "场景图")] + [(r, f"参考图{i+1}") for i, r in enumerate(args.ref)]:
        if not os.path.isfile(path_arg):
            print(f"❌ {name}不存在: {path_arg}")
            sys.exit(1)

    # API Keys
    kimi_key = args.kimi_key or os.environ.get("KIMI_API_KEY")
    seedream_key = args.seedream_key or os.environ.get("SEEDREAM_API_KEY")

    if not args.skip_kimi and not kimi_key:
        print("❌ 需要 Kimi API Key: --kimi-key 或 $KIMI_API_KEY")
        sys.exit(1)
    if not args.skip_seedream and not seedream_key:
        print("❌ 需要 Seedream API Key: --seedream-key 或 $SEEDREAM_API_KEY")
        sys.exit(1)

    # 运行
    pipeline = E2EPipeline(
        scene_path=args.scene,
        reference_paths=args.ref,
        kimi_key=kimi_key or "",
        seedream_key=seedream_key or "",
        size=args.size,
    )

    pipeline.run()


if __name__ == "__main__":
    main()
