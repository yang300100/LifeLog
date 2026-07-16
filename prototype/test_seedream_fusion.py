"""
Seedream 多图融合 (参考图 + 场景图) 局部重绘测试脚本

方案 B：无 Mask 模式
  - 使用 OpenAI SDK 调用火山引擎方舟 Ark
  - 传入场景图 + 角色参考图
  - 用 prompt 描述位置、交互、光照、融合
  - Seedream 自动理解位置并重绘，输出自然融合结果

API: 火山引擎方舟 Ark — client.images.generate
模型: doubao-seedream-5-0-260128

用法:
  1. pip install openai requests
  2. 设置环境变量 ARK_API_KEY (或传 --key)
  3. python test_seedream_fusion.py --scene 场景图 --ref 参考图1 参考图2 ...

示例:
  # 展示模式 (3个变体: 站立 / 倚靠 / 探头)
  python test_seedream_fusion.py --scene scene.jpg --ref ref1.png ref2.png ref3.png

  # 自定义 prompt
  python test_seedream_fusion.py --scene scene.jpg --ref ref.png \\
      --custom-prompt "将图2图3中的角色放在图1右侧草地上，自然站立，匹配场景光照"
"""

import os
import sys
import json
import time
import base64
import argparse
from pathlib import Path
from urllib.request import urlretrieve

# 修复 Windows GBK 终端 emoji 输出问题
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from openai import OpenAI


# ── 配置 ─────────────────────────────────────────────────

BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
MODEL_ID = "doubao-seedream-5-0-260128"
OUTPUT_DIR = Path(__file__).parent / "seedream_output"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


# ── 工具函数 ─────────────────────────────────────────────

def image_to_base64(path: str) -> str:
    """将本地图片转为 Base64 Data URL"""
    ext = Path(path).suffix.lower().lstrip(".")
    mime_map = {
        "jpg": "image/jpeg", "jpeg": "image/jpeg",
        "png": "image/png", "webp": "image/webp", "bmp": "image/bmp",
    }
    mime = mime_map.get(ext, "image/jpeg")
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")
    return f"data:{mime};base64,{b64}"


def resolve_image(image_path: str) -> str:
    """将图片路径解析为 URL 或 Base64 Data URL"""
    if image_path.startswith("data:") or image_path.startswith("http"):
        return image_path
    if os.path.isfile(image_path):
        return image_to_base64(image_path)
    raise FileNotFoundError(f"图片不存在: {image_path}")


def download_result(url: str, save_path: Path) -> bool:
    """下载生成的图片到本地"""
    try:
        # 用 requests 替代 urlretrieve，避免 SSL 问题
        import ssl
        import requests as req
        resp = req.get(url, timeout=60, verify=True)
        resp.raise_for_status()
        save_path.write_bytes(resp.content)
        size_kb = len(resp.content) / 1024
        print(f"  ✅ 已保存: {save_path} ({size_kb:.1f} KB)")
        return True
    except Exception as e:
        print(f"  ❌ 下载失败: {e}")
        print(f"  🌐 图片URL: {url}")
        return False


# ── Prompt 构建 ──────────────────────────────────────────

def build_fusion_prompt(
    position_desc: str,
    interaction_desc: str = "",
    lighting_desc: str = "",
    shadow_desc: str = "",
    ref_count: int = 1,
) -> str:
    """
    构建多图融合 Prompt（有参考图模式，不含角色外观描述）

    图序约定:
      图1 = 场景图 (image[0])
      图2, 图3, ... = 角色参考图 (image[1:])

    参考图已锁定角色外貌，prompt 只描述位置 + 动作 + 融合指令
    """
    # 引用参考图
    if ref_count == 1:
        ref_phrase = "将图2中的角色放在图1"
    elif ref_count == 2:
        ref_phrase = "将图2图3中的角色放在图1"
    else:
        ref_ids = "图" + "图".join(str(i) for i in range(2, ref_count + 2))
        ref_phrase = f"将{ref_ids}中的角色放在图1"

    parts = [f"{ref_phrase}{position_desc}"]

    if interaction_desc:
        parts.append(interaction_desc)

    if lighting_desc:
        parts.append(lighting_desc)
    else:
        parts.append("匹配图1场景的自然光照")

    if shadow_desc:
        parts.append(shadow_desc)
    else:
        parts.append("在角色脚下生成与场景光源方向一致的自然阴影")

    # 融合质量指令 (关键)
    parts.append("与周围环境无缝融合")
    parts.append("匹配原始照片的光照色调和真实摄影风格")
    parts.append("人物比例符合场景透视")
    parts.append("自然摄影，非插画")
    parts.append("无绿幕，无孤立人物，无明显拼贴边界")
    parts.append("全身可见，稳稳站在地面上")

    return "，".join(parts)


# ── Seedream API 客户端 ──────────────────────────────────

class SeedreamFusionClient:
    """Seedream 多图融合客户端 — 基于 OpenAI SDK"""

    def __init__(self, api_key: str, model: str = MODEL_ID,
                 base_url: str = BASE_URL, verbose: bool = True):
        self.api_key = api_key
        self.model = model
        self.verbose = verbose

        self.client = OpenAI(
            base_url=base_url,
            api_key=api_key,
        )

    def _log(self, msg: str):
        if self.verbose:
            print(msg)

    def generate(
        self,
        scene_image: str,          # 场景图 (本地路径 / URL / Base64)
        reference_images: list,    # 角色参考图列表
        prompt: str,               # 位置 + 交互 + 融合描述
        size: str = "2K",
        response_format: str = "url",
        watermark: bool = False,
        timeout: int = 120,
    ) -> dict | None:
        """
        多图融合生成

        Args:
            scene_image: 场景照片 (映射为图1)
            reference_images: 角色参考图列表 (映射为图2, 图3, ...)
            prompt: 描述位置/交互/融合的文本
            size: 输出尺寸 "1K" / "2K" / "4K"
            response_format: "url" 或 "b64_json"
            watermark: 是否保留 "AI生成" 水印
            timeout: 超时秒数

        Returns:
            成功: {"image_url": "...", "raw": {...}}
            失败: None
        """
        # 组装 image 数组: [场景图(图1), 参考图1(图2), 参考图2(图3), ...]
        images = [resolve_image(scene_image)]
        for ref in reference_images:
            images.append(resolve_image(ref))

        ref_count = len(reference_images)
        self._log(f"\n📤 调用 Seedream 多图融合...")
        self._log(f"  模型: {self.model}")
        self._log(f"  场景图: 1 张 → 图1")
        for i, ref in enumerate(reference_images):
            self._log(f"  参考图{i+1}: {Path(ref).name if os.path.isfile(ref) else '(URL/Base64)'} → 图{i+2}")
        self._log(f"  Prompt: {prompt[:150]}{'...' if len(prompt) > 150 else ''}")
        self._log(f"  输出尺寸: {size}")

        try:
            # 使用 OpenAI SDK 调用
            resp = self.client.images.generate(
                model=self.model,
                prompt=prompt,
                size=size,
                response_format=response_format,
                extra_body={
                    "image": images,
                    "watermark": watermark,
                    "sequential_image_generation": "disabled",
                },
                timeout=timeout,
            )

            if not resp or not resp.data or len(resp.data) == 0:
                self._log(f"  ❌ 响应为空或无 data 字段")
                return None

            image_url = resp.data[0].url

            if not image_url:
                self._log(f"  ❌ data[0] 中无 url")
                # 尝试 b64_json
                if hasattr(resp.data[0], 'b64_json') and resp.data[0].b64_json:
                    image_url = resp.data[0].b64_json
                    self._log(f"  ℹ️ 使用 b64_json 数据")
                else:
                    self._log(f"  ❌ 未找到任何图片数据")
                    return None

            self._log(f"  ✅ 生成成功!")

            return {
                "image_url": image_url,
                "raw": resp.model_dump() if hasattr(resp, "model_dump") else str(resp),
            }

        except Exception as e:
            self._log(f"  ❌ API 调用异常: {e}")
            return None


# ── 测试场景 ─────────────────────────────────────────────

def test_showcase(client: SeedreamFusionClient,
                   scene_path: str, ref_paths: list,
                   size: str = "2K") -> dict:
    """
    展示模式: 生成多个变体方便对比

    变体:
      A — 自然站立 (右侧)
      B — 倚靠支撑物
      C — 前倾探头 (桌面/台面)
    """
    print("\n" + "=" * 60)
    print("🎨 展示模式: 多姿态 + 多位置变体")
    print("=" * 60)

    variants = [
        {
            "label": "A_standing",
            "pos": "场景的右侧空地上",
            "interaction": "自然站立，全身可见，面朝左侧，带着温柔的微笑",
        },
        {
            "label": "B_sitting_area",
            "pos": "场景中适合坐下的空地区域",
            "interaction": "轻松地坐着，双手环抱膝盖，歪头看向镜头，像在等用户一起",
        },
        {
            "label": "C_lean_forward",
            "pos": "场景中前方有台面或桌面的位置",
            "interaction": "身体微微前倾，双手轻轻撑在面前，探头好奇地看着，嘴角带着俏皮的笑意",
        },
    ]

    results = {}
    for i, v in enumerate(variants):
        print(f"\n{'─' * 50}")
        print(f"  变体 {i+1}/3: {v['label']}")

        prompt = build_fusion_prompt(
            position_desc=v["pos"],
            interaction_desc=v["interaction"],
            lighting_desc="匹配图1场景的自然光照方向和色温",
            shadow_desc="根据场景主光源方向生成正确的柔和阴影",
            ref_count=len(ref_paths),
        )

        result = client.generate(
            scene_image=scene_path,
            reference_images=ref_paths,
            prompt=prompt,
            size=size,
        )

        if result:
            ts = time.strftime("%Y%m%d_%H%M%S")
            save_path = OUTPUT_DIR / f"showcase_{v['label']}_{ts}.png"
            download_result(result["image_url"], save_path)
            results[v["label"]] = result
        else:
            print(f"  ⚠️ 变体 {v['label']} 生成失败，跳过")

        if i < len(variants) - 1:
            time.sleep(1)  # 避免频率限制

    return results


def test_custom(client: SeedreamFusionClient,
                 scene_path: str, ref_paths: list,
                 prompt: str, size: str = "2K") -> dict | None:
    """自定义 prompt 模式"""
    print("\n" + "=" * 60)
    print("🔧 自定义 Prompt 模式")
    print("=" * 60)
    print(f"\n📝 完整 Prompt:\n  {prompt}\n")

    result = client.generate(
        scene_image=scene_path,
        reference_images=ref_paths,
        prompt=prompt,
        size=size,
    )

    if result:
        ts = time.strftime("%Y%m%d_%H%M%S")
        save_path = OUTPUT_DIR / f"custom_{ts}.png"
        download_result(result["image_url"], save_path)
    return result


# ── 主入口 ───────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Seedream 多图融合 (B方案: 无Mask, Prompt控位) 测试脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 展示模式 (推荐 — 3个变体对比)
  python test_seedream_fusion.py --scene scene.jpg --ref ref1.png ref2.png ref3.png

  # 展示模式 + 小尺寸快速测试
  python test_seedream_fusion.py --scene scene.jpg --ref ref.png --size 1K

  # 自定义 prompt
  python test_seedream_fusion.py --scene scene.jpg --ref ref1.png ref2.png \\
      --custom-prompt "将图2图3中的角色放在图1右侧长椅旁，放松站立，手搭在椅背上，面朝左侧微笑"
        """,
    )

    parser.add_argument("--scene", required=True,
                        help="场景照片路径 (图1)")
    parser.add_argument("--ref", nargs="+", required=True,
                        help="角色参考图路径 (图2, 图3, ...), 可传多张")
    parser.add_argument("--key",
                        help="ARK API Key (默认读环境变量 ARK_API_KEY)")
    parser.add_argument("--model", default=MODEL_ID,
                        help=f"模型 ID (默认: {MODEL_ID})")
    parser.add_argument("--size", default="2k",
                        choices=["2k", "3k", "4k", "1024x1024", "2048x2048"],
                        help="输出尺寸 (默认: 2k)")
    parser.add_argument("--watermark", action="store_true",
                        help="保留 'AI生成' 水印 (默认不保留)")
    parser.add_argument("--custom-prompt",
                        help="自定义完整 prompt (跳过模板, 直接传给 Seedream)")
    parser.add_argument("--quiet", action="store_true",
                        help="安静模式, 减少日志输出")

    args = parser.parse_args()

    # ── API Key ──
    api_key = args.key or os.environ.get("ARK_API_KEY")
    if not api_key:
        print("❌ 请设置 API Key:")
        print("     export ARK_API_KEY=your-key-here")
        print("   或 --key your-key-here")
        sys.exit(1)

    # ── 验证输入 ──
    if not os.path.isfile(args.scene):
        print(f"❌ 场景图不存在: {args.scene}")
        sys.exit(1)
    for r in args.ref:
        if not os.path.isfile(r):
            print(f"❌ 参考图不存在: {r}")
            sys.exit(1)

    # ── 启动 ──
    print("=" * 60)
    print("🚀 Seedream 多图融合 (B方案: 无Mask Prompt控位)")
    print("=" * 60)
    print(f"  API Key: {api_key[:12]}...{api_key[-4:]}")
    print(f"  模型: {args.model}")
    print(f"  场景图: {args.scene}")
    print(f"  参考图 ({len(args.ref)} 张):")
    for r in args.ref:
        print(f"    - {r}")
    print(f"  输出尺寸: {args.size}")
    print(f"  输出目录: {OUTPUT_DIR}")
    print(f"  水印: {'保留' if args.watermark else '不保留'}")

    client = SeedreamFusionClient(
        api_key=api_key,
        model=args.model,
        verbose=not args.quiet,
    )

    # ── 自定义 Prompt ──
    if args.custom_prompt:
        test_custom(client, args.scene, args.ref, args.custom_prompt, args.size)
    else:
        # 默认: 展示模式
        test_showcase(client, args.scene, args.ref, args.size)

    print("\n" + "=" * 60)
    print("✅ 测试完成!")
    print(f"  输出目录: {OUTPUT_DIR}")
    print("=" * 60)


if __name__ == "__main__":
    main()
