package com.smartoffline.reddit.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.OfflineState
import com.smartoffline.reddit.data.PostListItem
import com.smartoffline.reddit.data.PostType
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Orange
import com.smartoffline.reddit.ui.Success
import com.smartoffline.reddit.ui.Warn
import com.smartoffline.reddit.util.Format

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, background: Color = MaterialTheme.colorScheme.surfaceVariant, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(12.dp), tint = color); Spacer(Modifier.width(4.dp)) }
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun typeIcon(t: PostType): ImageVector = when (t) {
    PostType.IMAGE -> AppIcons.Image
    PostType.GALLERY -> AppIcons.Collections
    PostType.VIDEO -> AppIcons.Video
    PostType.GIF -> AppIcons.Gif
    PostType.TEXT -> AppIcons.Text
    PostType.LINK -> AppIcons.Link
}

@Composable
fun OfflineBadge(state: OfflineState) {
    val (color, label) = when (state) {
        OfflineState.COMPLETE -> Success to "Offline"
        OfflineState.TEXT_ONLY -> Success to "Offline"
        OfflineState.PARTIAL -> Warn to "Partial"
        OfflineState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant to "Queued"
        OfflineState.UNAVAILABLE -> Danger to "Media unavailable"
    }
    Pill(label, color, color.copy(alpha = 0.14f))
}

@Composable
fun SubIcon(name: String, location: String?, size: Int = 36) {
    if (location != null) {
        LocalImage(location, Modifier.size(size.dp).clip(CircleShape), maxDim = 128)
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(Orange), contentAlignment = Alignment.Center) {
            Text("r/", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
        }
    }
}

/** A downloaded post in a list. Everything shown comes from local storage. */
@Composable
fun PostCard(item: PostListItem, onClick: () -> Unit, onToggleFavorite: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val p = item.post
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("r/${p.subreddit}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("  ·  u/${p.author}  ·  ${Format.ago(p.createdUtc)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(if (item.protected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (item.protected) "Remove from favorites" else "Protect as favorite",
                        tint = if (item.protected) Orange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
            val cover = item.cover
            if (!compact && cover != null && cover.state.name == "COMPLETE" && (cover.thumbLocation ?: cover.location) != null) {
                Spacer(Modifier.height(10.dp))
                val ratio = if (cover.width > 0 && cover.height > 0) (cover.width.toFloat() / cover.height).coerceIn(0.8f, 2.2f) else 1.6f
                Box {
                    val loc = cover.thumbLocation ?: cover.location!!
                    LocalImage(loc, Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)), maxDim = 720)
                    if (cover.kind != MediaKind.IMAGE) {
                        Surface(Modifier.align(Alignment.Center).size(48.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                            Icon(if (cover.kind == MediaKind.GIF) AppIcons.Gif else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(10.dp))
                        }
                    }
                    if (item.mediaCount > 1) {
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill("1/${item.mediaCount}", Color.White, Color.Black.copy(alpha = 0.6f), AppIcons.Collections) }
                    }
                }
            } else if (!compact && p.type == PostType.TEXT && p.selftext.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(p.selftext, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else if (!compact && cover != null && cover.state.name != "COMPLETE") {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.offlineState == OfflineState.UNAVAILABLE) Icons.Filled.Warning else AppIcons.Download, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(p.mediaNote ?: cover.error ?: "Media waiting to download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(Format.count(p.score.toLong()), icon = AppIcons.ArrowUp)
                Pill(Format.count(p.numComments.toLong()), icon = AppIcons.Comment)
                Pill(p.type.label, icon = typeIcon(p.type))
                Spacer(Modifier.weight(1f))
                OfflineBadge(p.offlineState)
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector = Icons.Filled.Warning, color: Color = Warn, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.12f), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

@Composable
fun FullBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
