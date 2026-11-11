package com.nelson.yomukai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Neobrutalist buttons, the same as the web's .btn (docs/referencias/ESTILO.md): a 3 dp ink border, nearly square
 * corners and a hard 4 dp shadow with no blur. Pressing pushes the button onto its shadow, which disappears.
 * Primary is the one accent; outlined is white «paper» with black ink and an accent shadow.
 */

private val InkShape = RoundedCornerShape(4.dp)
private val InkShadow = 4.dp
private val InkPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)

@Composable
private fun InkButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors,
    shadowColor: Color,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = MaterialTheme.colorScheme.onBackground
    val shift = if (pressed || !enabled) InkShadow else 0.dp
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = InkShape,
        colors = colors,
        border = BorderStroke(3.dp, if (enabled) ink else ink.copy(alpha = 0.4f)),
        elevation = null,
        contentPadding = InkPadding,
        interactionSource = interaction,
        modifier = modifier
            .padding(end = InkShadow, bottom = InkShadow)
            .offset(shift, shift)
            .drawBehind {
                if (shift == 0.dp) {
                    val outline = InkShape.createOutline(size, layoutDirection, this)
                    translate(InkShadow.toPx(), InkShadow.toPx()) { drawOutline(outline, shadowColor) }
                }
            }
            .heightIn(min = 46.dp),
        content = {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold)) {
                content()
            }
        }
    )
}

/** The main action: the accent with black text. */
@Composable
fun InkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit
) = InkButtonBase(onClick, modifier, enabled, colors, MaterialTheme.colorScheme.onBackground, content)

/** A secondary action: white «paper» with black ink and a shadow in the accent. */
@Composable
fun InkOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = InkButtonBase(
    onClick,
    modifier,
    enabled,
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.onBackground,
        contentColor = MaterialTheme.colorScheme.background
    ),
    MaterialTheme.colorScheme.primary,
    content
)
