#!/usr/bin/env python3
"""
LifeLog Companion — 角色生成 + 场景合成 原型测试
════════════════════════════════════════════════

核心理念：API 只生成角色图 → 手机端叠放到场景帧的对应位置
  ✅ 场景原图不变（不失真）
  ✅ API 调用更便宜（只生角色、不跑 Inpainting）
  ✅ 国内 API 直连可用

用法:
  python companion_compose_test.py
  python companion_compose_test.py --scene my_photo.jpg
  python companion_compose_test.py --scene photo.jpg --pos right --scale 0.3

前置:
  pip install requests Pillow
  设置环境变量: export SILICONFLOW_API_KEY="sk-..."
  注册: https://siliconflow.cn (硅基流动，国内直连)

测试流程:
  ① 加载场景图
  ② 调用文生图 API 生成角色（透明/纯色背景）
  ③ 去除角色背景（BG Removal）
  ④ 缩放角色到目标尺寸
  ⑤ 叠放到场景图的目标位置
  ⑥ 简单光照匹配
  ⑦ 输出合成图
"""

import argparse
import base64
import io
import json
import os
import sys
import time
from pathlib import Path
from typing import Optional, Tuple

# ── Windows 终端 UTF-8 ──
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# ── 依赖 ──
try:
    import requests
except ImportError:
    print("[x] pip install requests")
    sys.exit(1)

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageStat, ImageOps
except ImportError:
    print("[x] pip install Pillow")
    sys.exit(1)


def load_scene(path: str) -> Image.Image:
    """加载场景图，自动修正 EXIF 方向（手机照片常见问题）"""
    img = Image.open(path)
    # 检查 EXIF Orientation 并自动旋转
    exif = img.getexif()
    orientation = exif.get(274, 1)  # 0x0112
    if orientation != 1:
        print(f"  EXIF 方向={orientation}, 自动修正...")
        img = ImageOps.exif_transpose(img)
    return img


# ═══════════════════════════════════════════
# API 配置 — 国内可直连
# ═══════════════════════════════════════════

API_PROVIDERS = {
    "siliconflow": {
        "name": "硅基流动 (SiliconFlow)",
        "base_url": "https://api.siliconflow.cn/v1",
        "env_key": "SILICONFLOW_API_KEY",
        "register": "https://siliconflow.cn",
        "models": {
            "qwen": "Qwen/Qwen-Image",           # 通义千问生图 — 默认
            "qwen-edit": "Qwen/Qwen-Image-Edit",  # 通义千问图像编辑
            "z-image": "Tongyi-MAI/Z-Image",      # 通义万相
            "z-turbo": "Tongyi-MAI/Z-Image-Turbo",# 通义万相快速
            "ernie": "baidu/ERNIE-Image-Turbo",   # 百度文心一格
        },
        "default_model": "qwen",
        "note": "国内直连, 通义千问/万相/文心 全系模型"
    },
}

# 全局配置
API_KEY = ""
API_BASE = ""
API_PROVIDER = "siliconflow"


# ═══════════════════════════════════════════
# 角色 Prompt 模板
# ═══════════════════════════════════════════

def build_character_prompt(desc: str, pose: str = "standing", view: str = "full body") -> str:
    """
    构造角色生成 prompt。
    关键：全身、纯绿幕背景（方便精确去背）、指定姿态。
    """
    base = (
        f"anime style, {view} character, {pose}, "
        f"{desc}, "
        f"solid chroma key green background, plain green screen, "
        f"even lighting on green backdrop, no background objects, "
        f"clean lineart, flat color, 2D anime illustration, "
        f"masterpiece, high quality, detailed character design"
    )
    return base


NEGATIVE_PROMPT = (
    "photorealistic, realistic, 3D render, photograph, "
    "complex background, scenery, room, outdoor, "
    "white background, gray background, "
    "deformed face, bad anatomy, extra limbs, missing limbs, "
    "blurry, low quality, watermark, text, signature, "
    "nsfw, nude, cropped, cut off, out of frame, "
    "green clothing, green hair, green eyes"  # 避免角色自身带绿色
)

# 角色描述预设（测试用，实际使用时由多模态AI从参考图提取）
DEFAULT_CHARACTER = (
    "a young girl with silver short hair and purple eyes, "
    "wearing a white collared shirt and dark blue pleated skirt, "
    "slender build, gentle quiet expression, delicate facial features, "
    "about 160cm tall, soft smile"
)

# 姿态预设
POSES = {
    "standing": "standing naturally, arms relaxed at sides, facing forward",
    "sitting": "sitting on a chair, hands resting on lap, facing slightly left",
    "walking": "walking casually, one hand in pocket, looking ahead",
    "leaning": "leaning against a wall, arms crossed, relaxed posture",
    "holding_drink": "standing, holding a coffee cup with both hands, gentle smile",
    "looking_away": "standing, looking to the side thoughtfully, hair swaying gently",
}


# ═══════════════════════════════════════════
# 场景合成配置
# ═══════════════════════════════════════════

# 角色在画面中的位置 (比例坐标 0.0-1.0)
PLACEMENT_PRESETS = {
    "center":   {"x": 0.35, "y": 0.20, "w": 0.28, "h": 0.50},
    "right":    {"x": 0.58, "y": 0.18, "w": 0.28, "h": 0.52},
    "left":     {"x": 0.08, "y": 0.18, "w": 0.28, "h": 0.52},
    "corner":   {"x": 0.55, "y": 0.35, "w": 0.25, "h": 0.45},
    "nearby":   {"x": 0.42, "y": 0.15, "w": 0.22, "h": 0.40},  # 更近、像在身边
}


# ═══════════════════════════════════════════
# API 调用
# ═══════════════════════════════════════════

def call_image_gen(
    prompt: str,
    model: str = None,
    negative_prompt: str = NEGATIVE_PROMPT,
    size: str = "1024x1024",
    n: int = 1,
    timeout: int = 120
) -> Optional[str]:
    """
    调用文生图 API 生成角色图。
    返回: 生成图片的 URL（或 None）
    """
    if not API_KEY:
        print("[x] 未设置 API Key")
        return None

    provider = API_PROVIDERS[API_PROVIDER]
    model_name = model or provider["default_model"]
    model_id = provider["models"].get(model_name, model_name)

    print(f"\n{'='*60}")
    print(f"API: {provider['name']}")
    print(f"模型: {model_id}")
    print(f"Prompt: {prompt[:120]}...")
    print(f"{'='*60}")

    url = f"{API_BASE}/image/generations"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    body = {
        "model": model_id,
        "prompt": prompt,
        "negative_prompt": negative_prompt,
        "n": n,
        "size": size,
    }

    try:
        print("  发送请求...")
        resp = requests.post(url, headers=headers, json=body, timeout=timeout)
        if resp.status_code != 200:
            print(f"  [x] API 错误 ({resp.status_code}): {resp.text[:300]}")
            return None

        data = resp.json()
        images = data.get("data", [])
        if not images:
            print(f"  [x] 未返回图片: {json.dumps(data, ensure_ascii=False)[:200]}")
            return None

        img_url = images[0].get("url", "")
        if img_url:
            print(f"  OK 生成成功: {img_url[:80]}...")
            return img_url
        else:
            # 有些 API 返回 base64
            b64 = images[0].get("b64_json", "")
            if b64:
                print(f"  OK 生成成功 (base64, {len(b64)} chars)")
                return f"data:image/png;base64,{b64}"
            print(f"  [x] 未知响应格式")
            return None

    except requests.exceptions.Timeout:
        print(f"  [x] 请求超时 ({timeout}s)")
        return None
    except Exception as e:
        print(f"  [x] 异常: {e}")
        return None


def download_image(url: str, output_path: str) -> bool:
    """下载图片（支持 URL 和 data URI）"""
    try:
        if url.startswith("data:"):
            # base64 data URI
            header, b64 = url.split(",", 1)
            data = base64.b64decode(b64)
        else:
            resp = requests.get(url, timeout=30)
            if resp.status_code != 200:
                return False
            data = resp.content

        with open(output_path, "wb") as f:
            f.write(data)
        print(f"  已保存: {output_path} ({len(data)} bytes)")
        return True
    except Exception as e:
        print(f"  [x] 下载失败: {e}")
        return False


# ═══════════════════════════════════════════
# 图像合成
# ═══════════════════════════════════════════

def remove_background(
    image: Image.Image,
    method: str = "chroma",
    feather: int = 2
) -> Image.Image:
    """
    AI 去背景 / 色键抠图。

    method:
      - "chroma": 绿幕色键抠图（默认，需 prompt 中指定绿色背景）
      - "threshold": 简单白色阈值（备选）
      - "rembg": AI 模型去背景（需 pip install rembg）

    色键原理：检测绿色像素 (G > R+B)，转为透明。
    比白色阈值法精确得多，因为角色很少带绿色元素。
    """
    img = image.convert("RGBA")
    pix = img.load()
    w, h = img.size

    if method == "chroma":
        # 绿幕色键抠图
        for y in range(h):
            for x in range(w):
                r, g, b, a = pix[x, y]
                # 判断是否为绿色背景：
                # 1. 绿色通道明显高于红蓝通道
                # 2. 排除暗部噪点
                brightness = (r + g + b) / 3
                green_dominance = g - max(r, b)

                if green_dominance > 40:
                    # 明显绿色 → 完全透明
                    pix[x, y] = (r, g, b, 0)
                elif green_dominance > 20 and brightness > 40:
                    # 过渡边缘/阴影 → 半透明
                    alpha = max(0, min(255, int((40 - green_dominance) / 20 * 255)))
                    pix[x, y] = (r, g, b, min(a, alpha))

    elif method == "threshold":
        # 简单白色阈值（备选）
        for y in range(h):
            for x in range(w):
                r, g, b, a = pix[x, y]
                if r > 235 and g > 235 and b > 235:
                    pix[x, y] = (r, g, b, 0)

    elif method == "rembg":
        try:
            from rembg import remove
            # rembg 直接返回去背景后的 RGBA
            return remove(image).convert("RGBA")
        except ImportError:
            print("  [!] rembg 未安装，回退到色键抠图")
            return remove_background(image, method="chroma", feather=feather)

    return img


def composite_character(
    scene_path: str,
    character_img: Image.Image,
    placement: dict,
    output_path: str = "composite_output.png",
    brightness_match: bool = True,
    shadow: bool = True
) -> str:
    """
    将角色合成到场景图中。

    参数:
      scene_path: 场景原图路径
      character_img: 角色图 (RGBA, 已去背景)
      placement: 位置参数 {x, y, w, h} (比例)
      brightness_match: 是否匹配场景亮度
      shadow: 是否添加地面阴影
    """
    scene = load_scene(scene_path).convert("RGBA")
    sw, sh = scene.size

    # 计算角色在场景中的像素位置和大小
    cx = int(placement["x"] * sw)
    cy = int(placement["y"] * sh)
    cw = int(placement["w"] * sw)
    ch = int(placement["h"] * sh)

    print(f"\n  场景: {sw}x{sh}")
    print(f"  角色放置: ({cx}, {cy}) 尺寸 {cw}x{ch}")

    # 缩放角色到目标大小（保持宽高比，fit inside）
    char_w, char_h = character_img.size
    scale = min(cw / char_w, ch / char_h)
    new_w = int(char_w * scale)
    new_h = int(char_h * scale)
    char_resized = character_img.resize((new_w, new_h), Image.LANCZOS)
    print(f"  角色缩放: {char_w}x{char_h} → {new_w}x{new_h} (scale={scale:.2f})")

    # 光照匹配：调整角色亮度/色温以匹配场景
    if brightness_match:
        char_resized = match_lighting(char_resized, scene, (cx, cy, cw, ch))

    # 创建临时画布
    canvas = scene.copy()

    # 角色居中放在目标区域内
    paste_x = cx + (cw - new_w) // 2
    paste_y = cy + (ch - new_h) // 2

    # 添加地面阴影（可选）
    if shadow:
        canvas = add_ground_shadow(canvas, paste_x, paste_y, new_w, new_h)

    # 粘贴角色
    canvas.paste(char_resized, (paste_x, paste_y), char_resized)

    # 保存
    canvas = canvas.convert("RGB")
    canvas.save(output_path)
    print(f"  OK 合成图已保存: {output_path}")
    return output_path


def match_lighting(
    char_img: Image.Image,
    scene_img: Image.Image,
    target_region: Tuple[int, int, int, int]
) -> Image.Image:
    """
    光照匹配：根据场景目标区域的平均亮度调整角色。
    只统计角色不透明像素的亮度，排除透明背景的干扰。
    """
    tx, ty, tw, th = target_region

    # 取场景目标区域的亮度
    region = scene_img.crop((tx, ty, tx + tw, ty + th))
    stat = ImageStat.Stat(region)
    scene_brightness = sum(stat.mean[:3]) / 3

    # 只统计角色不透明像素的亮度（排除透明/半透明背景）
    pix = char_img.load()
    w, h = char_img.size
    total_brightness = 0.0
    opaque_count = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = pix[x, y]
            if a > 128:  # 只统计不透明像素
                total_brightness += (r + g + b) / 3
                opaque_count += 1

    if opaque_count == 0:
        return char_img
    char_brightness = total_brightness / opaque_count

    # 调整系数
    factor = scene_brightness / max(char_brightness, 1)
    factor = max(0.55, min(1.8, factor))  # 放宽范围

    if abs(factor - 1.0) > 0.03:
        # 分离 RGB 和 Alpha 通道
        rgb = char_img.convert("RGB")
        alpha = char_img.split()[-1]  # 保留原 alpha
        enhancer = ImageEnhance.Brightness(rgb)
        adjusted_rgb = enhancer.enhance(factor)
        # 合并调整后的 RGB + 原 Alpha
        char_img = Image.merge("RGBA", (*adjusted_rgb.split(), alpha))
        print(f"  光照匹配: 场景={scene_brightness:.0f}, 角色={char_brightness:.0f} → factor={factor:.2f}")

    return char_img


def add_ground_shadow(
    canvas: Image.Image,
    x: int, y: int, w: int, h: int
) -> Image.Image:
    """
    在角色脚下添加简单的椭圆阴影。
    """
    shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(shadow_layer)

    # 椭圆阴影在角色底部
    shadow_cx = x + w // 2
    shadow_cy = y + h
    shadow_rx = int(w * 0.35)
    shadow_ry = int(w * 0.08)

    # 渐变椭圆：多层叠加
    for i in range(3):
        alpha = 40 - i * 15
        r = int(shadow_rx * (1 + i * 0.4))
        draw.ellipse(
            [shadow_cx - r, shadow_cy - shadow_ry - i*2,
             shadow_cx + r, shadow_cy + shadow_ry + i*2],
            fill=(0, 0, 0, max(0, alpha))
        )

    return Image.alpha_composite(canvas, shadow_layer)


# ═══════════════════════════════════════════
# 测试场景图生成
# ═══════════════════════════════════════════

def create_test_scene(output_path: str = "test_scene.jpg") -> str:
    """生成模拟室内场景（1024x1024）"""
    img = Image.new("RGB", (1024, 1024), (245, 238, 228))
    draw = ImageDraw.Draw(img)

    # 地板
    draw.rectangle([0, 680, 1024, 1024], fill=(185, 165, 140))
    # 地毯
    draw.rectangle([100, 720, 900, 1024], fill=(160, 140, 110))

    # 左边桌子
    draw.rectangle([80, 480, 480, 700], fill=(130, 90, 60))
    # 桌腿
    draw.rectangle([100, 700, 130, 900], fill=(120, 80, 50))
    draw.rectangle([430, 700, 460, 900], fill=(120, 80, 50))

    # 右边空椅
    draw.rectangle([630, 530, 770, 700], fill=(90, 70, 50))
    draw.rectangle([640, 380, 760, 530], fill=(90, 70, 50))

    # 窗户 (右上)
    draw.rectangle([720, 40, 980, 340], fill=(190, 220, 250))
    draw.line([850, 40, 850, 340], fill=(160, 190, 220), width=3)
    draw.line([720, 190, 980, 190], fill=(160, 190, 220), width=3)

    # 桌上物品
    draw.ellipse([320, 450, 370, 480], fill=(210, 195, 175))  # 杯子

    img.save(output_path)
    print(f"已生成测试场景: {output_path} (1024x1024)")
    return output_path


# ═══════════════════════════════════════════
# 测试用例
# ═══════════════════════════════════════════

def test_single_compose(
    scene_path: str,
    character_desc: str = DEFAULT_CHARACTER,
    placement_key: str = "center",
    pose_key: str = "standing",
    model: str = None,
    output_dir: str = "output",
    skip_api: bool = False
) -> bool:
    """
    完整流程测试：生成角色 → 去背景 → 合成
    """
    print(f"\n{'='*60}")
    print(f"测试: pose={pose_key}, placement={placement_key}")
    print(f"{'='*60}")

    os.makedirs(output_dir, exist_ok=True)
    placement = PLACEMENT_PRESETS[placement_key]
    pose_desc = POSES.get(pose_key, POSES["standing"])

    # Step 1: 生成角色图
    if skip_api:
        print("\n  [跳过] API 调用（无 Key）→ 用纯色占位图模拟")
        char_img = Image.new("RGBA", (512, 1024), (255, 200, 200, 200))
        # 画个简笔小人
        draw = ImageDraw.Draw(char_img)
        draw.ellipse([180, 40, 332, 192], fill=(255, 220, 180, 255))  # 头
        draw.rectangle([200, 200, 312, 550], fill=(255, 255, 255, 255))  # 上衣
        draw.rectangle([200, 550, 312, 800], fill=(50, 50, 140, 255))  # 裙子
        draw.rectangle([200, 800, 240, 1000], fill=(255, 220, 180, 255))  # 腿
        draw.rectangle([272, 800, 312, 1000], fill=(255, 220, 180, 255))
        char_path = os.path.join(output_dir, "char_placeholder.png")
        char_img.save(char_path)
        print(f"  占位角色图: {char_path}")
    else:
        prompt = build_character_prompt(character_desc, pose_desc)
        print(f"\n  Prompt:\n    {prompt[:200]}...")

        result_url = call_image_gen(prompt, model=model, timeout=120)
        if not result_url:
            print("  [x] API 生成失败")
            return False

        char_path = os.path.join(output_dir, f"char_{pose_key}.png")
        if not download_image(result_url, char_path):
            return False
        char_img = Image.open(char_path)

    # Step 2: 去背景
    print("\n  去背景...")
    char_rgba = remove_background(char_img if not skip_api else char_img)
    nobg_path = os.path.join(output_dir, f"char_{pose_key}_nobg.png")
    char_rgba.save(nobg_path)
    print(f"  去背景后: {nobg_path}")

    # Step 3: 合成
    print("\n  合成...")
    output_path = os.path.join(
        output_dir,
        f"composite_{placement_key}_{pose_key}.png"
    )
    composite_character(
        scene_path, char_rgba, placement, output_path,
        brightness_match=not skip_api,
        shadow=True
    )

    return True


def test_multi_placement(
    scene_path: str,
    character_desc: str = DEFAULT_CHARACTER,
    pose_key: str = "standing",
    model: str = None,
    output_dir: str = "output",
    skip_api: bool = False
) -> bool:
    """
    测试同一角色在不同位置的合成效果。
    """
    print(f"\n{'='*60}")
    print(f"多位置对比测试")
    print(f"{'='*60}")

    os.makedirs(output_dir, exist_ok=True)
    pose_desc = POSES.get(pose_key, POSES["standing"])
    placement_keys = ["left", "center", "right"]

    # 只生成一次角色（节省 API 费用）
    if skip_api:
        char_img = Image.new("RGBA", (512, 1024), (200, 200, 255, 200))
        draw = ImageDraw.Draw(char_img)
        draw.ellipse([180, 40, 332, 192], fill=(255, 220, 180, 255))
        draw.rectangle([200, 200, 312, 550], fill=(255, 255, 255, 255))
        draw.rectangle([200, 550, 312, 800], fill=(50, 50, 140, 255))
        char_rgba = char_img
        print("  [跳过] API → 占位角色图")
    else:
        prompt = build_character_prompt(character_desc, pose_desc)
        result_url = call_image_gen(prompt, model=model, timeout=120)
        if not result_url:
            print("  [x] API 生成失败")
            return False

        char_path = os.path.join(output_dir, f"char_{pose_key}.png")
        if not download_image(result_url, char_path):
            return False
        char_img = Image.open(char_path)
        char_rgba = remove_background(char_img)
        nobg_path = os.path.join(output_dir, f"char_{pose_key}_nobg.png")
        char_rgba.save(nobg_path)

    # 在每个位置合成
    for pkey in placement_keys:
        placement = PLACEMENT_PRESETS[pkey]
        output_path = os.path.join(output_dir, f"composite_{pkey}_{pose_key}.png")
        composite_character(scene_path, char_rgba, placement, output_path,
                          brightness_match=not skip_api, shadow=True)

    print(f"\n  OK 生成了 {len(placement_keys)} 张不同位置的合成图")
    return True


# ═══════════════════════════════════════════
# 主入口
# ═══════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="LifeLog Companion 角色生成+合成原型",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 不用 API Key — 生成场景图 + 占位角色 + 合成预览
  python companion_compose_test.py

  # 用硅基流动 API 生成真实角色
  python companion_compose_test.py --scene photo.jpg --key sk-xxx

  # 多位置对比
  python companion_compose_test.py --scene photo.jpg --key sk-xxx --test multi

  # 用不同模型
  python companion_compose_test.py --scene photo.jpg --key sk-xxx --model sdxl

设置 API Key:
  export SILICONFLOW_API_KEY="sk-..."
  注册: https://siliconflow.cn
        """
    )
    parser.add_argument("--scene", default=None,
                        help="场景照片 (不提供则自动生成)")
    parser.add_argument("--key", default=None,
                        help="API Key (或设环境变量 SILICONFLOW_API_KEY)")
    parser.add_argument("--provider", default="siliconflow",
                        choices=list(API_PROVIDERS.keys()),
                        help="API 提供商 (默认: siliconflow)")
    parser.add_argument("--model", default=None,
                        choices=["qwen", "qwen-edit", "z-image", "z-turbo", "ernie"],
                        help="模型 (默认: qwen 通义千问)")
    parser.add_argument("--test", default="single",
                        choices=["single", "multi", "all"],
                        help="测试模式 (默认: single)")
    parser.add_argument("--pos", default="center",
                        choices=list(PLACEMENT_PRESETS.keys()),
                        help="角色位置 (默认: center)")
    parser.add_argument("--pose", default="standing",
                        choices=list(POSES.keys()),
                        help="角色姿态 (默认: standing)")
    parser.add_argument("--desc", default=None,
                        help="自定义角色描述")
    parser.add_argument("--output", default="output",
                        help="输出目录 (默认: output)")
    args = parser.parse_args()

    # ── API 配置 ──
    global API_KEY, API_BASE, API_PROVIDER
    API_PROVIDER = args.provider
    provider = API_PROVIDERS[API_PROVIDER]
    API_BASE = provider["base_url"]

    API_KEY = args.key or os.environ.get(provider["env_key"], "")

    if API_KEY:
        print(f"API: {provider['name']} ({API_BASE}) [已配置]")
    else:
        print(f"\n[!] 未设置 API Key")
        print(f"   提供商: {provider['name']}")
        print(f"   注册: {provider['register']}")
        print(f"   设置: export {provider['env_key']}='sk-...'")
        print(f"   或: python companion_compose_test.py --key 'sk-...'")
        print(f"   ")
        print(f"   无 API Key 时，用占位角色图做合成流程测试。\n")

    skip_api = not bool(API_KEY)

    # ── 场景图 ──
    scene_path = args.scene
    if not scene_path:
        scene_path = create_test_scene("test_scene.jpg")
    elif not os.path.exists(scene_path):
        print(f"[x] 场景图不存在: {scene_path}")
        sys.exit(1)
    print(f"\n场景图: {scene_path} ({load_scene(scene_path).size})")

    # ── 角色描述 ──
    character_desc = args.desc or DEFAULT_CHARACTER
    print(f"角色描述: {character_desc[:100]}...")

    # ── 运行测试 ──
    model = args.model or provider["default_model"]

    if args.test in ("single", "all"):
        success = test_single_compose(
            scene_path, character_desc,
            placement_key=args.pos, pose_key=args.pose,
            model=model, output_dir=args.output, skip_api=skip_api
        )
    else:
        success = test_multi_placement(
            scene_path, character_desc,
            pose_key=args.pose, model=model,
            output_dir=args.output, skip_api=skip_api
        )

    # ── 结果 ──
    print(f"\n{'='*60}")
    print(f"输出目录: {os.path.abspath(args.output)}/")
    print(f"{'='*60}")
    print("请检查合成图：")
    print("  1. 角色比例是否合适")
    print("  2. 位置是否自然")
    print("  3. 光照是否基本匹配")
    print("  4. 阴影是否增加真实感")
    if skip_api:
        print("\n[提示] 目前用占位角色图。设置 API Key 后可生成真实角色。")


if __name__ == "__main__":
    main()
