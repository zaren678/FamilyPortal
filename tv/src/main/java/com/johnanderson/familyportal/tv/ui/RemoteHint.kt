package com.johnanderson.familyportal.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** A remote button drawn as an outlined key, followed by an icon and/or label for what it does. */
@Composable
internal fun RemoteHint(
    key: String,
    icon: ImageVector? = null,
    iconDescription: String? = null,
    label: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            modifier = Modifier
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        icon?.let { Icon(it, contentDescription = iconDescription, tint = Color.White, modifier = Modifier.size(24.dp)) }
        label?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall) }
    }
}
