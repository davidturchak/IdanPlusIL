package com.idanplusil.tv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import com.idanplusil.resolver.model.Channel
import com.idanplusil.tv.R
import com.idanplusil.tv.ui.theme.BrandColors

/**
 * One channel tile: card art, name, live dot. Nothing else.
 *
 * With no EPG in v1 there is genuinely nothing more to say, and a card that
 * pretends to carry a subtitle reads as broken. [subtitle] exists and renders
 * nothing today; when EPG lands, "Now: ..." drops into that slot with no
 * layout rework.
 */
@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        // Four redundant focus signals - scale, border, a warm container lift,
        // and label promotion - because focus has to read from three metres.
        // Deliberately no Glow: a soft shadow costs a real-time blur per
        // focused item during D-pad key repeat.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BrandColors.CardSurface,
            focusedContainerColor = BrandColors.CardSurfaceFocused,
            pressedContainerColor = BrandColors.CardSurfaceFocused,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContentColor = Color.White,
            pressedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.borderVariant),
                shape = shape,
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, BrandColors.FocusRing),
                shape = shape,
            ),
        ),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Full-bleed card art. The bundled logos are opaque 16:9 plates
            // carrying their own background (white, black, brand colour), so
            // they fill the image slot edge to edge; padding them onto the
            // dark card would read as a pasted-on sticker.
            val logo = rememberLogoModel(channel.logo)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                if (logo == null) {
                    Monogram(channel.badge())
                } else {
                    SubcomposeAsyncImage(
                        model = logo,
                        contentDescription = stringResource(R.string.cd_channel_logo),
                        contentScale = ContentScale.Crop,
                        error = { Monogram(channel.badge()) },
                        loading = { Monogram(channel.badge()) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = channel.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .background(BrandColors.LiveRed, CircleShape)
                )
            }
        }
    }
}

/**
 * Deterministic fallback for a missing or broken logo. Never blank, and it
 * looks intentional rather than like a failure.
 */
/**
 * Channel number if we have one, otherwise initials. On a TV grid the number is
 * what viewers actually recognise, and it stays unique where two-letter
 * initials collide.
 */
private fun Channel.badge(): String =
    id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: title.filter { it.isLetterOrDigit() }.take(2).uppercase()

@Composable
private fun Monogram(badge: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(BrandColors.MonogramTop, BrandColors.MonogramBottom)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge,
            fontSize = if (badge.length > 2) 26.sp else 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFFFDCC2),
            textAlign = TextAlign.Center,
        )
    }
}
