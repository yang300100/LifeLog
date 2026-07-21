// LifeLog Camera 外壳 v2
// 硬件: 电池(长36宽27高22) + ESP32(长43宽27高23)
// 按钮: 3mm, 位于电池顶部; USB-C: 位于电池底部

battery_w  = 27;   // 电池宽
battery_h  = 22;   // 电池高
battery_d  = 36;   // 电池长

esp32_w    = 27;   // ESP32宽
esp32_h    = 23;   // ESP32高
esp32_d    = 43;   // ESP32长

gap        = 4; clearance  = 1.5;
wall       = 1.8; round_r    = 4;

// 内部尺寸
inner_w = battery_w + gap + esp32_w + clearance * 2;  // 长61
inner_d = 23;           // 高23
inner_h = max(battery_d, esp32_d) + clearance * 2;  // 宽46

shelf_wall = 2; shelf_h = 5;
cover_ledge = 1.5; cover_d = 2;

// 外部尺寸
body_w = inner_w + 2 * shelf_wall;  // 长65
body_h = shelf_wall + inner_h + cover_d;  // 高27
body_d = inner_d + 2 * wall;         // 宽50

// 元件位置
clo = shelf_wall;
elo = clo + battery_w + gap;
cbo = shelf_wall + (inner_h - battery_h) / 2;  // 电池Y居中
ebo = shelf_wall + (inner_h - esp32_h) / 2;    // ESP32 Y居中
cdo = clearance;
edo = clearance;

cam_sz = 8;
cam_cx = elo + esp32_w / 2;
cam_top_margin = cam_sz + (shelf_h - cover_d);
cam_cy = body_h - cover_ledge - cam_top_margin - cam_sz/2;

btn_d = 3.2; btn_h = 11;
btn_cx = clo + battery_w / 2;
btn_cz = cdo + battery_d / 2;

usb_w = 10; usb_h = 4;
usb_cx = clo + battery_w / 2;
usb_cz = cdo + battery_d / 2 + 2.6;

lanyard_d = 3.5; lanyard_num = 2; lanyard_sp = 5;
lanyard_at_x = body_w / 2;
lanyard_at_z = body_d / 2;
lanyard_dir = "z";

cover_w = body_w - 2 * cover_ledge;
cover_h = body_h - 2 * cover_ledge;
cover_r = round_r - 0.5;

module rb(w, h, d, r) {
    hull() for(x=[r, w-r]) for(y=[r, h-r])
        translate([x, y, 0]) cylinder(r=r, h=d, $fn=32);
}

module front_shell() {
    difference() {
        color("LightSteelBlue", 0.9)
        translate([cover_ledge, cover_ledge, 0])
            rb(cover_w, cover_h, cover_d, cover_r);
        translate([cam_cx, cam_cy, -1])
            cube([cam_sz, cam_sz, cover_d + 2]);  // 去掉center=true, 从Z=-1贯穿到Z=3
    }
}

module back_shell() {
    bd = body_d;
    difference() {
        color("LightSlateGray", 0.9) rb(body_w, body_h, bd, round_r);
        hull() {
            translate([shelf_wall, shelf_wall, cover_d])
                rb(inner_w, inner_h, 0.1, round_r - wall);
            translate([wall, wall, shelf_h])
                rb(body_w - 2*wall, body_h - 2*wall, 0.1, round_r - wall);
        }
        translate([wall, wall, shelf_h])
            rb(body_w - 2*wall, body_h - 2*wall, bd - wall - shelf_h, round_r - wall);
        translate([cover_ledge, cover_ledge, 0])
            rb(cover_w, cover_h, cover_d, cover_r);
        btn_ty = cbo + battery_h;
        btn_ch = body_h - btn_ty;
        translate([btn_cx, body_h, btn_cz])
            rotate([90, 0, 0]) cylinder(d=btn_d, h=wall + 2, $fn=48);
        translate([btn_cx, btn_ty, btn_cz])
            rotate([90, 0, 0])
                difference() {
                    cylinder(d=btn_d + 3, h=btn_ch, $fn=48);
                    translate([0, 0, -1])
                        cylinder(d=btn_d, h=btn_ch + 2, $fn=48);
                }
        translate([usb_cx, wall + (wall + 3)/2, usb_cz])
            cube([usb_w, wall + 3, usb_h], center=true);
        translate([usb_cx, wall - 2, usb_cz])
            cube([usb_w + 2, 4, usb_h + 1.5], center=true);
        if (lanyard_num == 1) {
            translate([lanyard_at_x, body_h, lanyard_at_z])
                rotate([90, 0, 0]) cylinder(d=lanyard_d, h=wall + 2, $fn=32);
        } else {
            if (lanyard_dir == "x") {
                for (x = [lanyard_at_x - lanyard_sp/2, lanyard_at_x + lanyard_sp/2])
                    translate([x, body_h, lanyard_at_z])
                        rotate([90, 0, 0]) cylinder(d=lanyard_d, h=wall + 2, $fn=32);
            } else {
                for (z = [lanyard_at_z - lanyard_sp/2, lanyard_at_z + lanyard_sp/2])
                    translate([lanyard_at_x, body_h, z])
                        rotate([90, 0, 0]) cylinder(d=lanyard_d, h=wall + 2, $fn=32);
            }
        }
    }
}

translate([0, 0, 0]) front_shell();
translate([body_w + 20, 0, 0]) back_shell();
// front_shell();
// back_shell();