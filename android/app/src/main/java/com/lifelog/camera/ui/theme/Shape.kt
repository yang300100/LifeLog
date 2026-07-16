package com.lifelog.camera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val PeachShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),  // 胶囊导航、大圆角容器
    large = RoundedCornerShape(24.dp),        // 主卡片（便签、信笺、分组卡片）
    medium = RoundedCornerShape(16.dp),       // 小卡片、输入框
    small = RoundedCornerShape(12.dp),        // 按钮、芯片
    extraSmall = RoundedCornerShape(8.dp)     // 标签、徽章
)
