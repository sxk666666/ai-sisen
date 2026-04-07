package com.goanalyzer.ui.theme

import androidx.compose.ui.graphics.Color

// ============ 棋盘配色（亮色模式）============
val BoardColor = Color(0xFFDCB35C)          // 木色棋盘
val BoardColorLight = Color(0xFFE8C96D)     // 棋盘高光
val BoardDarkSide = Color(0xFFC9A33E)       // 棋盘暗面
val GridColor = Color(0xFF8B7335)           // 网格线（柔化）
val GridBorder = Color(0xFF6B5630)          // 棋盘外框
val StarPointColor = Color(0xFF5D4E37)      // 星位
val CoordColor = Color(0xFF7A6B50)          // 坐标文字

// ============ 棋盘配色（暗色模式 - 优化白棋对比度）============
val BoardColorDarkMode = Color(0xFF6B5D30)  // 深色木纹（降低饱和度，让白棋更突出）
val BoardColorDarkModeLight = Color(0xFF8A7240) // 暗色高光
val BoardColorDarkModeAccent = Color(0xFF9A854A) // 暗色强调色
val GridColorDark = Color(0xFF4A4028)       // 更深的网格线（对比度更高）
val CoordColorDark = Color(0xFFB8A880)      // 更亮的坐标文字
val StarPointColorDark = Color(0xFFB8A880)   // 暗色星位

// ============ 棋子颜色 ============
val BlackStoneColor = Color(0xFF1A1A1A)
val BlackStoneHighlight = Color(0xFF505050)
val BlackStoneEdge = Color(0xFF0A0A0A)      // 黑棋边缘描边

// 白棋颜色（优化版）
val WhiteStoneColor = Color(0xFFFEFEFE)       // 白棋主色
val WhiteStonePearl = Color(0xFFF5F5F5)      // 珍珠白
val WhiteStoneEdge = Color(0xFFB8B8B8)       // 白棋边缘
val WhiteStoneDarkEdge = Color(0xFF9A9A9A)   // 暗色模式边缘（更明显）
val WhiteStoneShadow = Color(0xFFA0A0A0)
val StoneShadowColor = Color(0x30000000)     // 棋子投影

// ============ 分析颜色 ============
val GoodMoveColor = Color(0xFF00BFA5)       // 好手 - 薄荷绿
val BadMoveColor = Color(0xFFFF5252)        // 坏手 - 红色
val BestMoveColor = Color(0xFF4CAF50)       // 最佳推荐 - 翠绿
val SecondMoveColor = Color(0xFF42A5F5)     // 次选推荐 - 天蓝
val ThirdMoveColor = Color(0xFFFFA726)      // 第三推荐 - 橙色
val FourthMoveColor = Color(0xFFAB47BC)     // 第四推荐 - 紫色
val FifthMoveColor = Color(0xFF78909C)      // 第五推荐 - 蓝灰
val WinrateLineColor = Color(0xFF42A5F5)    // 胜率曲线

// ============ 胜率图颜色 ============
val WinrateBlackArea = Color(0xFF1A1A1A)
val WinrateWhiteArea = Color(0xFFE0E0E0)

// ============ 辅助：根据暗色模式获取棋盘色 ============
fun boardColor(isDark: Boolean) = if (isDark) BoardColorDarkMode else BoardColor
fun boardColorLight(isDark: Boolean) = if (isDark) BoardColorDarkModeLight else BoardColorLight
fun gridColor(isDark: Boolean) = if (isDark) GridColorDark else GridColor
fun coordColor(isDark: Boolean) = if (isDark) CoordColorDark else CoordColor
