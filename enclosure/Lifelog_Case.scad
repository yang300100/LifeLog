// LifeLog Camera 外壳 — 挂绳式
// 打印: PLA/PETG, 0.2mm 层高, 15% 填充, 无需支撑
// 组装: M2x6 自攻螺丝 x4

/* [整体尺寸] */
body_w  = 45;    // 宽
body_h  = 60;    // 高
body_d  = 15;    // 总厚度

/* [壁厚] */
wall   = 1.5;
round  = 3;

/* [前壳深度占比] */
front_ratio = 0.35;

/* [摄像头孔 - 顶部偏右] */
cam_d  = 9;
cam_x  = 9;       // 孔中心距右边缘
cam_y  = 13;      // 孔中心距上边缘

/* [挂绳孔] */
lanyard_w = 14;
lanyard_d = 3.5;

/* [USB-C 开口] */
usb_w = 9;
usb_h = 5.5;
usb_z = 0.38;   // USB 口在后壳的深度位置比例

/* [螺丝] */
screw_d      = 2.2;
screw_head_d = 5;
screw_margin = 7;

// ── 基础体 ──
module rbrick(w, h, d, r) {
    hull() for(x=[r, w-r]) for(y=[r, h-r])
        translate([x, y, 0]) cylinder(r=r, h=d, $fn=32);
}

// ── 前壳 ──
module front_shell() {
    fh = body_d * front_ratio;

    difference() {
        union() {
            // 外壳主体
            rbrick(body_w, body_h, fh, round);

            // 合模定位唇 (嵌入后壳)
            translate([1, 1, fh - 0.5])
            difference() {
                rbrick(body_w - 2, body_h - 2, 2, round - 0.5);
                translate([0.8, 0.8, -1])
                    rbrick(body_w - 3.6, body_h - 3.6, 4, round - 1.2);
            }
        }

        // 内部挖空
        translate([wall, wall, wall])
            rbrick(body_w - wall*2, body_h - wall*2, fh + 2, round - wall);

        // 摄像头孔
        translate([body_w - cam_x, body_h - cam_y, -1])
            cylinder(d=cam_d, h=wall + 3, $fn=48);

        // 摄像头外圈沉台 (镜头法兰)
        translate([body_w - cam_x, body_h - cam_y, wall - 0.8])
            cylinder(d=cam_d + 4, h=2, $fn=48);
    }
}

// ── 后壳 ──
module back_shell() {
    bh = body_d * (1 - front_ratio);

    difference() {
        union() {
            // 外壳主体
            rbrick(body_w, body_h, bh, round);
        }

        // 内部挖空
        translate([wall, wall, 0])
            rbrick(body_w - wall*2, body_h - wall*2, bh - wall, round - wall);

        // 合模凹槽 (接收前壳定位唇)
        translate([1, 1, -1])
            rbrick(body_w - 2, body_h - 2, 2.2, round - 0.5);

        // USB-C 开口 (底部)
        translate([body_w/2 - usb_w/2, -1, bh * usb_z - usb_h/2])
            cube([usb_w, wall + 3, usb_h]);

        // 螺丝孔
        for (x = [screw_margin, body_w - screw_margin]) {
            for (y = [screw_margin, body_h - screw_margin]) {
                translate([x, y, bh - wall - 0.5]) {
                    cylinder(d=screw_d, h=wall + 2, $fn=24);
                    cylinder(d=screw_head_d, h=1.5, $fn=24);
                }
            }
        }
    }
}

// ── 分别展示 (渲染用) ──
module display() {
    // 前壳 - 左下
    color("LightSteelBlue", 0.9) front_shell();

    // 后壳 - 右上，内部朝上便于查看
    translate([body_w + 15, 0, 0])
        color("LightSlateGray", 0.9) back_shell();
}

display();

// 打印时用下面两行:
// front_shell();
// back_shell();
