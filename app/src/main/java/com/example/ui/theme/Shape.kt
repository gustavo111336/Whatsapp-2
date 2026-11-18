package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Zap2Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

object Zap2ExpressiveShapes {
    val IncomingBubbleFirst = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomEnd = 22.dp,
        bottomStart = 6.dp
    )
    val IncomingBubbleGrouped = RoundedCornerShape(
        topStart = 8.dp,
        topEnd = 22.dp,
        bottomEnd = 22.dp,
        bottomStart = 8.dp
    )
    val OutgoingBubbleFirst = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = 22.dp,
        bottomEnd = 6.dp
    )
    val OutgoingBubbleGrouped = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 8.dp,
        bottomStart = 22.dp,
        bottomEnd = 8.dp
    )
    val SquircleAvatar = RoundedCornerShape(18.dp)
    val PillContainer = RoundedCornerShape(50)
}
