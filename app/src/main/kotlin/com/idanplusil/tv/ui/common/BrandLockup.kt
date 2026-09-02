package com.idanplusil.tv.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idanplusil.tv.R

/**
 * The brand mark, keyed out of its original white background by
 * `tools/branding/build_assets.py` and recoloured for a dark surface.
 *
 * The source art is soft, so it is only ever downscaled - never render this
 * above about 400px wide.
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    Image(
        painter = painterResource(R.drawable.logo_lockup_dark),
        contentDescription = stringResource(R.string.cd_app_logo),
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}
