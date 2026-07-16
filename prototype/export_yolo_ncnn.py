"""
YOLOv8n 检测模型 → NCNN 格式导出脚本

转换链: pt → ONNX → ONNX Simplify → NCNN (param + bin)

输出文件:
  app/src/main/assets/yolov8n.param  (~200KB)
  app/src/main/assets/yolov8n.bin    (~6.2MB)

在 Android 上使用 NCNN 库加载推理。

用法:
  # Step 1: pt → ONNX
  python export_yolo_ncnn.py

  # Step 2 (需要 NCNN 工具): ONNX → NCNN
  onnxsim yolov8n.onnx yolov8n-sim.onnx
  onnx2ncnn yolov8n-sim.onnx yolov8n.param yolov8n.bin

  # 如果系统没有 onnx2ncnn, 下载 NCNN 工具:
  # https://github.com/Tencent/ncnn/releases
"""

import os
import sys
from pathlib import Path

ROOT = Path(__file__).parent
ASSETS_DIR = ROOT.parent / "android" / "app" / "src" / "main" / "assets"
MODEL_NAME = "yolov8n"


def step1_export_onnx():
    """pt → ONNX (使用 ultralytics 内置导出)"""
    print("=" * 50)
    print("Step 1: YOLOv8n.pt → ONNX")
    print("=" * 50)

    from ultralytics import YOLO

    # 加载预训练模型 (检测版, 无 seg)
    model = YOLO("yolov8n.pt")  # 6.2MB, 80类 COCO

    # 导出 ONNX
    # opset=12 兼容 ncnn
    # simplify=True 自动简化
    onnx_path = model.export(
        format="onnx",
        opset=12,
        simplify=True,
        imgsz=640,
        half=False,  # FP32 — NCNN 也可处理 FP16
    )

    print(f"\n✅ ONNX 导出完成: {onnx_path}")
    print(f"   大小: {Path(onnx_path).stat().st_size / 1024 / 1024:.1f} MB")

    # 复制到 assets
    target = ROOT / f"{MODEL_NAME}.onnx"
    import shutil
    shutil.copy(onnx_path, target)
    print(f"   已复制到: {target}")

    return target


def step2_check_ncnn_tools():
    """检查 NCNN 工具是否可用"""
    import subprocess
    print("\n" + "=" * 50)
    print("Step 2: 检查 NCNN 转换工具")
    print("=" * 50)

    try:
        result = subprocess.run(["onnxsim", "--version"],
                                capture_output=True, text=True, timeout=10)
        print(f"  ✅ onnxsim 可用: {result.stdout.strip() or 'OK'}")
    except Exception:
        print("  ❌ onnxsim 未安装. pip install onnx-simplifier")

    try:
        result = subprocess.run(["onnx2ncnn", "--help"],
                                capture_output=True, text=True, timeout=10)
        print(f"  ✅ onnx2ncnn 可用")
    except Exception:
        print("  ❌ onnx2ncnn 未找到")
        print("     下载: https://github.com/Tencent/ncnn/releases")
        print("     Windows: 下载 windows 版本, 解压后把 exe 加入 PATH")

    print("\n手动执行 (如果工具已安装):")
    onnx_path = ROOT / f"{MODEL_NAME}.onnx"
    print(f"  onnxsim {onnx_path} {ROOT / f'{MODEL_NAME}-sim.onnx'}")
    print(f"  onnx2ncnn {ROOT / f'{MODEL_NAME}-sim.onnx'} {ASSETS_DIR / f'{MODEL_NAME}.param'} {ASSETS_DIR / f'{MODEL_NAME}.bin'}")


def print_model_info():
    """打印模型结构信息 (用于编写后处理)"""
    print("\n" + "=" * 50)
    print("模型输入/输出信息")
    print("=" * 50)
    print("""
YOLOv8n (detect) ONNX 输出:

输入:
  images  [1, 3, 640, 640]  float32  RGB, 归一化到 [0, 1] (除以255)

输出:
  output0 [1, 84, 8400]  float32
    - 8400 个候选 (80×80 + 40×40 + 20×20 网格)
    - 84 = 4 (xywh) + 80 (类别分数)

后处理:
  1. 从 output0 解析: cx, cy, w, h, 80 class scores
  2. 坐标转换: xywh → xyxy, 缩放到原图
  3. NMS with IoU=0.45, conf_thresh=0.25
  4. 映射 class_id → class_name (COCO 80类)
""")

    # COCO 类别映射 (我们在 Android 端需要的)
    print("COCO 类别 ID 映射 (需要的类别):")
    print("""
  person=0, bicycle=2, car=3, motorcycle=4,
  bench=15, cup=41, knife=43, bottle=39,
  chair=56, couch=57, potted plant=58,
  dining table=60, tv=62, laptop=63,
  keyboard=66, cell phone=67, book=73,
  vase=75, scissors=76, teddy bear=77
""")


def main():
    import argparse
    parser = argparse.ArgumentParser(description="YOLOv8n → NCNN 导出")
    parser.add_argument("--step", choices=["1", "2", "info", "all"],
                        default="all", help="执行步骤")
    args = parser.parse_args()

    if args.step in ("1", "all"):
        step1_export_onnx()

    if args.step in ("2", "all"):
        step2_check_ncnn_tools()

    if args.step in ("info", "all"):
        print_model_info()

    print("\n✅ 完成!")


if __name__ == "__main__":
    main()
