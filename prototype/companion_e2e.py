#!/usr/bin/env python3
"""
LifeLog Companion — 完整端到端原型
════════════════════════════════════════════════

完整流程:
  ① 场景图 → AI 视觉分析 → 位置/姿态/光照建议
  ② 参考图 → AI 视觉分析 → 角色描述提取
  ③ 角色描述 + 场景建议 → 文生图 API → 角色图(绿幕)
  ④ 绿幕抠图 → 缩放/定位 → 光照匹配 → 合成输出

用法:
  python companion_e2e.py --scene 场景.jpg --ref 角色参考.png
  python companion_e2e.py --scene 场景.jpg  (无参考图，用默认角色)

API: 硅基流动 (SiliconFlow) 国内直连
"""

import argparse
import base64
import io
import json
import os
import sys
import time
from pathlib import Path
from typing import Optional, Tuple, Dict

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

try:
    import requests
except ImportError:
    print("[x] pip install requests"); sys.exit(1)

try:
    from PIL import Image, ImageDraw, ImageEnhance, ImageStat, ImageOps
except ImportError:
    print("[x] pip install Pillow"); sys.exit(1)


# ═══════════════════════════════════════════
# 配置
# ═══════════════════════════════════════════

# 硅基流动 — 角色图像生成 (国内直连)
SF_API_KEY = os.environ.get("SILICONFLOW_API_KEY", "")
SF_BASE = "https://api.siliconflow.cn/v1"
IMAGE_MODEL = "Qwen/Qwen-Image-Edit-2509"  # 图生图：保持角色一致性

# Kimi (Moonshot) — 场景分析 + 角色描述提取 (多模态)
KIMI_API_KEY = os.environ.get("KIMI_API_KEY", "")
KIMI_BASE = "https://api.moonshot.cn/v1"
KIMI_MODEL = "kimi-k2.6"  # Kimi 多模态模型 (支持图像输入)

# 默认角色（无参考图时使用）
DEFAULT_CHARACTER = (
    "a young girl with silver short hair and purple eyes, "
    "wearing a white collared shirt and dark blue pleated skirt, "
    "slender build, gentle quiet expression, about 160cm tall"
)

# 角色放置预设 (比例坐标)
PLACEMENTS = {
    "left":    {"x": 0.08, "y": 0.18, "w": 0.28, "h": 0.52},
    "center":  {"x": 0.35, "y": 0.20, "w": 0.28, "h": 0.50},
    "right":   {"x": 0.58, "y": 0.18, "w": 0.28, "h": 0.52},
    "corner":  {"x": 0.55, "y": 0.35, "w": 0.25, "h": 0.45},
    "nearby":  {"x": 0.42, "y": 0.15, "w": 0.22, "h": 0.40},
}

# 陪伴姿态 — 表情自然、温暖、有生命力
POSES = {
    "standing": "standing upright in empty green void, hands relaxed at sides, soft natural smile with slightly tilted head, warm sparkling eyes looking at viewer with quiet affection, relaxed shoulders, full body shot, isolated on pure green chroma key background, no ground no floor no environment",
    "sitting_nearby": "floating in relaxed sitting pose with legs dangling lightly, hands resting loosely on lap, upper body leaning slightly forward with a tender gentle smile, soft eyes looking downward at viewer with warmth, rosy cheeks, natural breathing pose, as if perched on an invisible elevated spot, full body shot, isolated on pure green chroma key, no chair no desk no surface of any kind",
    "leaning": "standing with body casually tilted to one side, one arm relaxed at side, soft contented smile, half-lidded gentle eyes looking at viewer with quiet fondness, relaxed natural posture, as if leaning against invisible support, full body shot, isolated on pure green chroma key, no wall no furniture visible",
    "crouching": "crouching down in green void, hands lightly on knees, looking up at viewer with a bright playful smile, curious sparkling eyes, slightly flushed cheeks, full body shot, isolated on pure green background, no ground visible",
    "waiting": "standing straight with hands gently clasped behind back, calm peaceful expression with a subtle knowing smile, soft eyes looking at viewer with patience and warmth, natural relaxed stance, full body shot, isolated on pure green chroma key, no ground no environment",
    "perched": "floating in casual seated pose with legs dangling freely, hands resting beside hips, leaning forward slightly with an amused gentle smile, warm eyes looking down at viewer with playful fondness, rosy cheeks, as if sitting on invisible elevated surface, full body shot, isolated on pure green chroma key, absolutely no furniture no desk no objects",
    "auto": "",
}


# ═══════════════════════════════════════════
# 图像工具
# ═══════════════════════════════════════════

def load_scene(path: str) -> Image.Image:
    """加载场景图，自动修正 EXIF 方向"""
    img = Image.open(path)
    exif = img.getexif()
    if exif.get(274, 1) != 1:
        img = ImageOps.exif_transpose(img)
    return img


def image_to_data_url(path: str) -> str:
    """图片 → base64 data URL (for API)"""
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")
    ext = Path(path).suffix.lower()
    mime = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png", "webp": "webp"}.get(ext, "jpeg")
    return f"data:image/{mime};base64,{b64}"


# ═══════════════════════════════════════════
# Step ①: 场景分析 (Vision Model)
# ═══════════════════════════════════════════

def analyze_scene(scene_path: str, custom_prompt: str = None) -> dict:
    """
    用 Kimi 视觉模型分析场景，核心问题：
      "如果有一个虚拟同伴角色在这里陪伴用户，
       她应该出现在画面中的什么位置？
       以什么姿态静静陪伴？"
    """
    prompt_text = custom_prompt or DEFAULT_PROMPTS["scene_analysis"]
    if not KIMI_API_KEY:
        print("  [!] 无 Kimi API Key，使用默认分析")
        return {
            "position_key": "center",
            "pose_key": "standing",
            "scene_type": "indoor",
            "lighting": "natural",
            "rationale": "默认",
            "generation_hint": "",
        }

    print(f"\n  [Step ①] 场景分析 (Kimi {KIMI_MODEL})...")
    scene_url = image_to_data_url(scene_path)
    prompt_text = custom_prompt or DEFAULT_PROMPTS["scene_analysis"]

    try:
        body = {
            "model": KIMI_MODEL,
            "messages": [
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": scene_url}},
                    {"type": "text", "text": prompt_text}
                ]}
            ],
            "max_tokens": 800,
            "temperature": 0.6,  # Kimi k2.6 仅支持 0.6
            "thinking": {"type": "disabled"},
        }

        resp = requests.post(f"{KIMI_BASE}/chat/completions",
            headers={"Authorization": f"Bearer {KIMI_API_KEY}", "Content-Type": "application/json"},
            json=body, timeout=60)

        if resp.status_code != 200:
            print(f"  [!] Kimi 分析失败 ({resp.status_code}): {resp.text[:200]}")
            return {"position_key": "center", "pose_key": "standing",
                    "scene_type": "unknown", "lighting": "natural",
                    "rationale": "API错误", "generation_hint": ""}

        content = resp.json()["choices"][0]["message"]["content"]

        # 提取 JSON
        json_start = content.find('{')
        json_end = content.rfind('}') + 1
        if json_start >= 0 and json_end > json_start:
            analysis = json.loads(content[json_start:json_end])
        else:
            analysis = json.loads(content)

        pos = analysis.get("recommended_placement", "center")
        pose = analysis.get("recommended_pose", "standing")
        # 验证值在预设范围内
        if pos not in PLACEMENTS: pos = "center"
        if pose not in POSES: pose = "standing"

        # 提取锚点数据
        anchor_ratio = float(analysis.get("vertical_anchor_ratio", 0.55))
        anchor_point = analysis.get("anchor_point", "")

        result = {
            "position_key": pos,
            "pose_key": pose,
            "scene_type": analysis.get("scene_type", ""),
            "lighting": analysis.get("lighting", {}).get("description", ""),
            "rationale": analysis.get("rationale", ""),
            "generation_hint": analysis.get("generation_hint", ""),
            "spatial_analysis": analysis.get("spatial_analysis", ""),
            "vertical_anchor_ratio": anchor_ratio,
            "anchor_point": anchor_point,
            "raw": analysis,
        }

        print(f"  场景: {result['scene_type']}")
        print(f"  空间: {result['spatial_analysis'][:100]}...")
        print(f"  位置: {pos} | 姿态: {pose}")
        print(f"  理由: {result['rationale']}")
        if result['generation_hint']:
            print(f"  融合: {result['generation_hint']}")

        return result

    except Exception as e:
        print(f"  [!] 分析异常: {e}")
        return {"position_key": "center", "pose_key": "standing",
                "scene_type": "unknown", "lighting": "natural",
                "rationale": str(e), "generation_hint": ""}


# ═══════════════════════════════════════════
# Step ②: 角色描述提取 (Vision Model)
# ═══════════════════════════════════════════

def describe_character(ref_path: str) -> str:
    """用 Kimi 视觉模型从参考图提取角色文字描述"""
    if not KIMI_API_KEY:
        print("  [!] 无 Kimi API Key")
        return DEFAULT_CHARACTER

    print(f"\n  [Step ②] 角色描述 (Kimi {KIMI_MODEL})...")
    ref_url = image_to_data_url(ref_path)
    prompt_text = custom_prompt or DEFAULT_PROMPTS["character_describe"]

    try:
        body = {
            "model": KIMI_MODEL,
            "messages": [
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": ref_url}},
                    {"type": "text", "text": prompt_text}
                ]}
            ],
            "max_tokens": 300,
            "temperature": 0.6,  # Kimi k2.6 仅支持 0.6
            "thinking": {"type": "disabled"},
        }

        resp = requests.post(f"{KIMI_BASE}/chat/completions",
            headers={"Authorization": f"Bearer {KIMI_API_KEY}", "Content-Type": "application/json"},
            json=body, timeout=60)

        if resp.status_code != 200:
            print(f"  [!] Kimi 描述失败 ({resp.status_code}): {resp.text[:200]}")
            return DEFAULT_CHARACTER

        desc = resp.json()["choices"][0]["message"]["content"].strip()
        print(f"  角色描述: {desc[:150]}...")
        return desc

    except Exception as e:
        print(f"  [!] 描述异常: {e}")
        return DEFAULT_CHARACTER


# ═══════════════════════════════════════════
# Step ③: 角色生成 (Image Gen)
# ═══════════════════════════════════════════

def generate_character(
    character_desc: str,
    ref_image_path: str = None,
    pose_key: str = "standing",
    scene_hint: str = "",
    output_path: str = "char_generated.png"
) -> Optional[Image.Image]:
    """
    图生图模式 — 从参考图出发，改变姿态+添加绿幕背景，保持角色一致性。

    ref_image_path: 角色参考图路径
    pose_key: 目标姿态
    scene_hint: 场景融合提示 (来自场景分析)
    """
    if not SF_API_KEY:
        print("  [!] 无硅基流动 API Key，跳过生成")
        return None

    pose_desc = POSES.get(pose_key, POSES["standing"])
    if pose_key == "auto":
        pose_desc = "natural relaxed pose fitting the environment"

    # 图生图 prompt — 用可编辑模板
    hint_text = f" Adjust character lighting to: {scene_hint}." if scene_hint else ""
    prompt = DEFAULT_PROMPTS["image_generation"].format(
        pose_desc=pose_desc, scene_hint=hint_text
    )

    negative = (
        "different face, different character, changed outfit, changed hair, "
        "furniture, desk, table, chair, wall, floor, ground, "
        "room, window, building, indoor, outdoor, environment, scenery, "
        "any objects, props, items, surface to sit or stand on, "
        "complex background, white background, gray background, "
        "photorealistic, realistic, 3D render, "
        "deformed face, bad anatomy, extra limbs, missing limbs, "
        "blurry, low quality, watermark, text, signature, "
        "nsfw, nude, cropped, cut off, out of frame"
    )

    print(f"\n  [Step ③] 图生图 ({IMAGE_MODEL})...")
    print(f"  参考图: {ref_image_path or '(无)'}")
    print(f"  目标姿态: {pose_key}")
    print(f"  Prompt: {prompt[:120]}...")

    try:
        body = {
            "model": IMAGE_MODEL,
            "prompt": prompt,
            "negative_prompt": negative,
            "n": 1,
            "size": "1024x1024",
        }

        # 如果有参考图，以 base64 传入
        if ref_image_path and os.path.exists(ref_image_path):
            with open(ref_image_path, "rb") as f:
                ref_b64 = base64.b64encode(f.read()).decode("utf-8")
            ext = Path(ref_image_path).suffix.lower()
            mime = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png"}.get(ext, "png")
            body["image"] = f"data:image/{mime};base64,{ref_b64}"
            print(f"  参考图已编码 ({len(ref_b64)} chars)")

        resp = requests.post(f"{SF_BASE}/image/generations",
            headers={"Authorization": f"Bearer {SF_API_KEY}", "Content-Type": "application/json"},
            json=body, timeout=120)

        if resp.status_code != 200:
            print(f"  [!] 生成失败: {resp.status_code} {resp.text[:200]}")
            return None

        img_url = resp.json()["data"][0]["url"]
        print(f"  生成成功, 下载中...")

        img_resp = requests.get(img_url, timeout=30)
        with open(output_path, "wb") as f:
            f.write(img_resp.content)
        print(f"  已保存: {output_path} ({len(img_resp.content)} bytes)")

        return Image.open(output_path)

    except Exception as e:
        print(f"  [!] 生成异常: {e}")
        return None


# ═══════════════════════════════════════════
# Step ④: 绿幕抠图
# ═══════════════════════════════════════════

def chroma_key_remove(image: Image.Image) -> Image.Image:
    """绿幕色键抠图 → 透明背景 RGBA → 裁剪到角色边界"""
    img = image.convert("RGBA")
    pix = img.load()
    w, h = img.size

    for y in range(h):
        for x in range(w):
            r, g, b, a = pix[x, y]
            green_dominance = g - max(r, b)
            if green_dominance > 40:
                pix[x, y] = (r, g, b, 0)
            elif green_dominance > 20 and (r + g + b) / 3 > 40:
                alpha = max(0, min(255, int((40 - green_dominance) / 20 * 255)))
                pix[x, y] = (r, g, b, min(a, alpha))

    # 裁剪到角色边界 (去除四周透明/绿幕空白)
    bbox = img.getbbox()  # 返回不透明像素的边界框 (left, top, right, bottom)
    if bbox:
        # 加一点边距
        pad = 4
        left = max(0, bbox[0] - pad)
        top = max(0, bbox[1] - pad)
        right = min(w, bbox[2] + pad)
        bottom = min(h, bbox[3] + pad)
        img = img.crop((left, top, right, bottom))
        print(f"  裁剪: {w}x{h} → {img.size} (边界: {bbox})")

    return img


# ═══════════════════════════════════════════
# Step ⑤: 合成
# ═══════════════════════════════════════════

def find_empty_region(scene_path: str) -> dict:
    """
    扫描场景图，找到最空的区域（颜色方差最小的大块区域）。
    返回最佳放置区域的像素坐标 + 该区域的平均颜色。
    """
    img = load_scene(scene_path).convert("RGB")
    w, h = img.size
    pix = img.load()

    # 将画面分为 6×8 网格，计算每格的颜色方差
    grid_w, grid_h = 6, 8
    cell_w, cell_h = w // grid_w, h // grid_h

    best_score = float('inf')
    best_cell = None
    cells = []

    for gy in range(grid_h):
        for gx in range(grid_w):
            x1, y1 = gx * cell_w, gy * cell_h
            x2, y2 = min(w, x1 + cell_w), min(h, y1 + cell_h)

            # 计算均值和方差
            r_sum, g_sum, b_sum = 0.0, 0.0, 0.0
            r_sq, g_sq, b_sq = 0.0, 0.0, 0.0
            n = 0
            for y in range(y1, y2, 3):
                for x in range(x1, x2, 3):
                    r, g, b = pix[x, y]
                    r_sum += r; g_sum += g; b_sum += b
                    r_sq += r*r; g_sq += g*g; b_sq += b*b
                    n += 1

            if n == 0: continue
            variance = (r_sq/n - (r_sum/n)**2) + (g_sq/n - (g_sum/n)**2) + (b_sq/n - (b_sum/n)**2)
            avg_r, avg_g, avg_b = r_sum/n, g_sum/n, b_sum/n

            # 得分：方差越小越空，但排除过暗（<30）或过亮（>240）的区域
            score = variance
            if avg_r + avg_g + avg_b < 90: score *= 3  # 太暗可能是物品阴影
            if avg_r + avg_g + avg_b > 700: score *= 2  # 过曝

            cells.append({
                'gx': gx, 'gy': gy,
                'x_ratio': gx / grid_w, 'y_ratio': gy / grid_h,
                'avg_color': (avg_r, avg_g, avg_b),
                'variance': variance,
            })

            if score < best_score:
                best_score = score
                best_cell = cells[-1]

    if best_cell:
        print(f"  最空区域: 网格({best_cell['gx']},{best_cell['gy']}) "
              f"x={best_cell['x_ratio']:.2f} y={best_cell['y_ratio']:.2f} "
              f"RGB({best_cell['avg_color'][0]:.0f},{best_cell['avg_color'][1]:.0f},{best_cell['avg_color'][2]:.0f}) "
              f"方差={best_cell['variance']:.0f}")
    return best_cell or {'x_ratio': 0.6, 'y_ratio': 0.5, 'avg_color': (128, 128, 128)}


def composite_character(
    scene_path: str,
    character_img: Image.Image,
    placement: dict,
    analysis: dict = None,
    output_path: str = "final.png"
) -> str:
    """将角色合成到场景中 — 含色温/白平衡匹配"""
    scene = load_scene(scene_path).convert("RGBA")
    sw, sh = scene.size

    # ── 智能定位 ──
    # 水平：使用预设位置
    cw = int(placement["w"] * sw)
    # 垂直：使用 Kimi 分析的 anchor_point 确定角色底部位置
    anchor_ratio = 0.55  # 默认桌面高度
    anchor_desc = ""
    if analysis:
        anchor_ratio = analysis.get("vertical_anchor_ratio", 0.55)
        anchor_desc = analysis.get("anchor_point", "")
        # 确保在合理范围内
        anchor_ratio = max(0.25, min(0.92, anchor_ratio))
        if anchor_desc:
            print(f"  锚点: {anchor_desc} (ratio={anchor_ratio:.2f})")

    # 缩放角色 — 高度根据场景比例自适应
    char_w, char_h = character_img.size
    target_h = int(sh * 0.42)  # 角色约占画面42%高度
    scale = min(cw / char_w, target_h / char_h)
    new_w, new_h = int(char_w * scale), int(char_h * scale)
    char_resized = character_img.resize((new_w, new_h), Image.LANCZOS)

    # 水平位置 — 优先放空白区域
    empty = find_empty_region(scene_path)
    # 用空白区中心作为水平参考
    empty_x = empty['x_ratio']
    if empty_x < 0.3:
        h_anchor = 0.15  # 偏左
    elif empty_x > 0.7:
        h_anchor = 0.65  # 偏右
    else:
        h_anchor = placement["x"]  # 用 Kimi 分析的位置
    h_center = int(h_anchor * sw) + cw // 2
    paste_x = h_center - new_w // 2
    print(f"  空白区: x={empty_x:.2f} → 水平锚定={h_anchor:.2f}")

    # 角色底部锚定到场景中的接触面 (桌面/地面/椅面)
    anchor_y = int(anchor_ratio * sh)
    paste_y = anchor_y - new_h  # 角色底部 = 锚点

    # 确保不出界
    paste_x = max(0, min(sw - new_w, paste_x))
    paste_y = max(0, min(sh - new_h, paste_y))
    print(f"  定位: x={paste_x} y={paste_y} size={new_w}x{new_h} (锚点={anchor_ratio:.2f})")

    # ═══ 色温/白平衡匹配 ═══
    # 采样场景中角色周围区域的平均颜色
    sample_w = int(sw * 0.25)
    sample_h = int(sh * 0.25)
    sample_x = max(0, min(sw - sample_w, paste_x - sample_w // 4))
    sample_y = max(0, min(sh - sample_h, paste_y - sample_h // 4))
    region = scene.crop((sample_x, sample_y, sample_x + sample_w, sample_y + sample_h))
    region_rgb = region.convert("RGB")
    r_stat = ImageStat.Stat(region_rgb)
    scene_r, scene_g, scene_b = r_stat.mean[:3]
    scene_brightness = (scene_r + scene_g + scene_b) / 3

    # 采样角色不透明像素的平均颜色
    r_pix = char_resized.load()
    char_r_sum, char_g_sum, char_b_sum = 0.0, 0.0, 0.0
    char_n = 0
    for y in range(char_resized.height):
        for x in range(char_resized.width):
            r, g, b, a = r_pix[x, y]
            if a > 128:
                char_r_sum += r
                char_g_sum += g
                char_b_sum += b
                char_n += 1

    if char_n > 0:
        char_r = char_r_sum / char_n
        char_g = char_g_sum / char_n
        char_b = char_b_sum / char_n
        char_bright = (char_r + char_g + char_b) / 3

        # ① 亮度因子
        bright_factor = scene_brightness / max(char_bright, 1)
        bright_factor = max(0.5, min(1.8, bright_factor))

        # ② 色温/白平衡因子 (每通道独立校正)
        # 计算场景的色偏 (相对于灰度)
        scene_r_ratio = scene_r / max(scene_brightness, 1)
        scene_g_ratio = scene_g / max(scene_brightness, 1)
        scene_b_ratio = scene_b / max(scene_brightness, 1)

        # 计算角色的色偏
        char_r_ratio = char_r / max(char_bright, 1)
        char_g_ratio = char_g / max(char_bright, 1)
        char_b_ratio = char_b / max(char_bright, 1)

        # 每通道校正因子 (向场景色偏靠拢，但限制幅度)
        r_factor = max(0.5, min(2.0, bright_factor * scene_r_ratio / max(char_r_ratio, 0.1)))
        g_factor = max(0.5, min(2.0, bright_factor * scene_g_ratio / max(char_g_ratio, 0.1)))
        b_factor = max(0.5, min(2.0, bright_factor * scene_b_ratio / max(char_b_ratio, 0.1)))

        print(f"  场景色温: RGB({scene_r:.0f},{scene_g:.0f},{scene_b:.0f})")
        print(f"  角色色温: RGB({char_r:.0f},{char_g:.0f},{char_b:.0f})")
        print(f"  校正因子: R={r_factor:.2f} G={g_factor:.2f} B={b_factor:.2f}")

        # 应用逐通道颜色校正
        rgb = char_resized.convert("RGB")
        alpha = char_resized.split()[-1]
        r_chan, g_chan, b_chan = rgb.split()

        # PIL point 操作逐通道调整
        r_chan = r_chan.point(lambda v: min(255, int(v * r_factor)))
        g_chan = g_chan.point(lambda v: min(255, int(v * g_factor)))
        b_chan = b_chan.point(lambda v: min(255, int(v * b_factor)))

        char_resized = Image.merge("RGBA", (r_chan, g_chan, b_chan, alpha))
        print(f"  色温匹配完成")

    # 地面阴影 (场景色温感知，锚点处)
    shadow = Image.new("RGBA", scene.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(shadow)
    sx = paste_x + new_w // 2
    sy = paste_y + new_h
    # 阴影颜色参考场景色温
    shadow_r = int(scene_r * 0.12)
    shadow_g = int(scene_g * 0.12)
    shadow_b = int(scene_b * 0.12)
    for i in range(3):
        r = int(new_w * 0.35 * (1 + i * 0.4))
        draw.ellipse([sx - r, sy - int(new_w * 0.08) - i * 2,
                      sx + r, sy + int(new_w * 0.08) + i * 2],
                     fill=(shadow_r, shadow_g, shadow_b, max(0, 35 - i * 12)))

    canvas = Image.alpha_composite(scene, shadow)
    canvas.paste(char_resized, (paste_x, paste_y), char_resized)
    canvas = canvas.convert("RGB")
    canvas.save(output_path)
    print(f"  合成: {output_path} ({canvas.size})")
    return output_path


# ═══════════════════════════════════════════
# 完整流水线
# ═══════════════════════════════════════════

# ═══════════════════════════════════════════
# 提示词管理
# ═══════════════════════════════════════════

# 默认提示词模板
DEFAULT_PROMPTS = {
    "scene_analysis": """你是一个温馨的"虚拟陪伴角色定位助手"。这张照片来自用户胸前佩戴的摄像头，是第一视角画面。

用户有一个二次元风格的虚拟同伴角色，她像一个安静的同行者，陪伴在用户身边。

你的任务：分析这张照片，**在画面的空白/空闲区域**为这个虚拟同伴找一个最自然的位置。

核心原则：
- **优先空白区域**：找画面中没有物体遮挡的纯色大块空白区（如空桌面、空墙、空地面、窗外天空等），角色绝对不能覆盖任何现有物体
- **空间充足**：位置应该有足够的空间容纳一个完整的人物，不能挤在狭缝里
- **不遮挡主体**：不能挡住显示器、键盘、鼠标等人机交互区域
- 角色是"陪伴者"不是"参与者"——安静待在旁边
- **画面平衡**：角色位置应让整体构图更均衡

请返回严格JSON格式（不要markdown代码块）：
{{
  "scene_type": "场景类型(简短中文)",
  "empty_areas": "详细描述画面中的空白区域，按面积从大到小列出",
  "lighting": {{ "direction": "主光源方向", "temperature": "色温(warm/cool/natural)", "description": "光源描述" }},
  "recommended_placement": "推荐水平位置: left/center/right/corner/nearby",
  "recommended_pose": "推荐姿态: standing/sitting_nearby/leaning/crouching/waiting/perched",
  "anchor_point": "角色底部与场景的接触位置",
  "vertical_anchor_ratio": 0.0,
  "rationale": "为什么选这块空白区域",
  "generation_hint": "英文光照融合提示(15词以内)"
}}

vertical_anchor_ratio: 0.5=画面中部, 0.7=偏下, 0.85=地面""",

    "character_describe": """请详细描述这张图片中的人物/角色，提取以下视觉特征:

1. 发型与发色
2. 瞳色
3. 面部特征
4. 服装（上衣、下装、鞋）
5. 身高体型特征
6. 气质/风格
7. 特殊配件（眼镜、帽子、饰品等）

请用一段流畅的英文描述，60-100词。直接输出描述文字，不要JSON格式，不要额外解释。""",

    "image_generation": """Keep the exact same character identity, face, hair style, outfit, and art style.
Change only the pose and expression to: {pose_desc}.
Replace the entire background with solid chroma key green screen.
No environment, no furniture, no ground, no objects — just the character isolated on pure green background, full body,
green screen studio photography, character floating in green void.{scene_hint}""",
}


def save_prompts(output_dir: str, prompts: dict, analysis: dict = None, character_desc: str = None):
    """保存所有提示词到 JSON 文件，方便用户查看和修改"""
    prompt_file = os.path.join(output_dir, "00_prompts.json")
    out = {
        "description": "这些是 LifeLog Companion 流程中使用的所有提示词。你可以修改后重新运行。",
        "prompts": prompts,
    }
    if analysis:
        out["last_analysis"] = {k: v for k, v in analysis.items() if k != "raw"}
    if character_desc:
        out["last_character_desc"] = character_desc
    with open(prompt_file, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n  [提示词] 已保存到 {prompt_file}")


def load_custom_prompts(prompt_file: str) -> dict:
    """从 JSON 文件加载自定义提示词"""
    if not os.path.exists(prompt_file):
        return {}
    with open(prompt_file, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("prompts", {})


def run_full_pipeline(
    scene_path: str,
    ref_path: Optional[str] = None,
    output_dir: str = "output",
    manual_position: Optional[str] = None,
    manual_pose: Optional[str] = None,
) -> bool:
    """
    完整端到端流水线:
      场景分析 → 角色描述 → 角色生成 → 抠图 → 合成
    """
    print("\n" + "=" * 60)
    print("  LifeLog Companion — 端到端流水线")
    print("=" * 60)

    os.makedirs(output_dir, exist_ok=True)

    scene_img = load_scene(scene_path)
    print(f"\n场景图: {scene_path} ({scene_img.size})")

    # ── Step ①: 场景分析 ──
    if manual_position and manual_pose:
        print(f"\n  [Step ①] 跳过 (手动指定: pos={manual_position}, pose={manual_pose})")
        analysis = {
            "position_key": manual_position,
            "pose_key": manual_pose,
            "scene_type": "manual",
            "lighting": "manual",
            "rationale": "手动指定",
            "generation_hint": "",
        }
    else:
        analysis = analyze_scene(scene_path)

    pos_key = analysis["position_key"]
    pose_key = analysis["pose_key"]

    # ── Step ②: 角色描述 ──
    if ref_path and os.path.exists(ref_path):
        print(f"\n参考图: {ref_path}")
        character_desc = describe_character(ref_path)
    else:
        character_desc = DEFAULT_CHARACTER
        print(f"\n  [Step ②] 跳过 (无参考图) → 默认角色: {character_desc[:80]}...")

    # ── Step ③: 图生图 (从参考图出发，保持角色一致性) ──
    char_path = os.path.join(output_dir, "01_character_raw.png")
    char_img = generate_character(
        character_desc,
        ref_image_path=ref_path,  # 传入参考图做图生图
        pose_key=pose_key,
        scene_hint=analysis.get("generation_hint", ""),
        output_path=char_path
    )
    if char_img is None:
        print("\n[x] 角色生成失败，流水线中断")
        return False

    # ── Step ④: 绿幕抠图 ──
    print(f"\n  [Step ④] 绿幕抠图...")
    char_rgba = chroma_key_remove(char_img)
    nobg_path = os.path.join(output_dir, "02_character_nobg.png")
    char_rgba.save(nobg_path)
    print(f"  已保存: {nobg_path}")

    # 验证抠图质量
    pix = char_rgba.load()
    w, h = char_rgba.size
    transp = sum(1 for y in range(0, h, 3) for x in range(0, w, 3) if pix[x, y][3] < 10)
    green_res = sum(1 for y in range(0, h, 3) for x in range(0, w, 3)
                    if pix[x, y][3] >= 10 and pix[x, y][1] > pix[x, y][0] + 30 and pix[x, y][1] > pix[x, y][2] + 30)
    total = (w // 3 + 1) * (h // 3 + 1)
    print(f"  透明: {transp/total*100:.0f}% | 绿色残留: {green_res/total*100:.1f}%")

    # ── Step ⑤: 合成 ──
    print(f"\n  [Step ⑤] 合成 (位置={pos_key})...")
    placement = PLACEMENTS.get(pos_key, PLACEMENTS["center"])
    print(f"  AI建议: {analysis.get('rationale', '')}")
    print(f"  场景: {analysis.get('scene_type', '')} | 光照: {analysis.get('lighting', '')}")

    output_path = os.path.join(output_dir, "03_final_composite.png")
    composite_character(scene_path, char_rgba, placement, analysis=analysis, output_path=output_path)

    # ── 保存分析结果 ──
    meta = {
        "scene_path": scene_path,
        "ref_path": ref_path,
        "position": pos_key,
        "pose": pose_key,
        "analysis": {k: v for k, v in analysis.items() if k != "raw"},
        "character_desc": character_desc[:200],
    }
    meta_path = os.path.join(output_dir, "00_analysis.json")
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"\n  分析结果: {meta_path}")

    # ── 保存所有提示词 ──
    used_prompts = {
        "scene_analysis": DEFAULT_PROMPTS["scene_analysis"],
        "character_describe": DEFAULT_PROMPTS["character_describe"],
        "image_generation": DEFAULT_PROMPTS["image_generation"],
    }
    save_prompts(output_dir, used_prompts, analysis=analysis, character_desc=character_desc)

    print(f"\n{'=' * 60}")
    print(f"  完成! 输出: {os.path.abspath(output_dir)}/")
    print(f"{'=' * 60}")
    print(f"  00_prompts.json         — 所有提示词(可编辑)")
    print(f"  00_analysis.json        — AI场景分析结果")
    print(f"  01_character_raw.png   — AI生成的角色原图")
    print(f"  02_character_nobg.png   — 绿幕抠图后")
    print(f"  03_final_composite.png  — 最终合成图")

    return True


# ═══════════════════════════════════════════
# 主入口
# ═══════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="LifeLog Companion 端到端原型",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python companion_e2e.py --scene 场景.jpg --ref 角色.png
  python companion_e2e.py --scene 场景.jpg   (无参考图)
  python companion_e2e.py --scene 场景.jpg --ref 角色.png --pos right --pose sitting
        """
    )
    parser.add_argument("--scene", required=True, help="场景照片路径")
    parser.add_argument("--ref", default=None, help="角色参考图路径 (可选)")
    parser.add_argument("--pos", default=None, choices=list(PLACEMENTS.keys()),
                        help="手动指定位置 (跳过AI分析)")
    parser.add_argument("--pose", default=None, choices=list(POSES.keys()),
                        help="手动指定姿态 (跳过AI分析)")
    parser.add_argument("--sf-key", default=None, help="硅基流动 API Key (生图)")
    parser.add_argument("--kimi-key", default=None, help="Kimi API Key (场景分析)")
    parser.add_argument("--prompts", default=None, help="自定义提示词 JSON 文件路径")
    parser.add_argument("--output", default="output", help="输出目录")
    args = parser.parse_args()

    global SF_API_KEY, KIMI_API_KEY
    SF_API_KEY = args.sf_key or os.environ.get("SILICONFLOW_API_KEY", "")
    KIMI_API_KEY = args.kimi_key or os.environ.get("KIMI_API_KEY", "")

    # 加载自定义提示词
    if args.prompts:
        custom = load_custom_prompts(args.prompts)
        if custom:
            for k, v in custom.items():
                if k in DEFAULT_PROMPTS:
                    DEFAULT_PROMPTS[k] = v
            print(f"[提示词] 已从 {args.prompts} 加载自定义提示词")

    if not SF_API_KEY:
        print("[!] 未设置硅基流动 API Key (生图)")
    if not KIMI_API_KEY:
        print("[!] 未设置 Kimi API Key (场景分析)")

    if not os.path.exists(args.scene):
        print(f"[x] 场景图不存在: {args.scene}")
        sys.exit(1)

    run_full_pipeline(
        scene_path=args.scene,
        ref_path=args.ref,
        output_dir=args.output,
        manual_position=args.pos,
        manual_pose=args.pose,
    )


if __name__ == "__main__":
    main()
