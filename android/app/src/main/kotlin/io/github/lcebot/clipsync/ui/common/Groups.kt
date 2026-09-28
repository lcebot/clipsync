@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import io.github.lcebot.clipsync.R

/**
 * A group of M3 Expressive segmented list items: one rounded block whose rows sit
 * [ListItemDefaults.SegmentedGap] apart, with the large corners only on the outside of the block.
 * Material decides the shapes from each row's place in the group
 * ([ListItemDefaults.segmentedShapes]), including the press morph.
 *
 * Rows are declared through [GroupScope.item] so the group knows which one is first and last; an
 * item left out (a switch that hides its details) simply is not declared, and the corners move to
 * the new ends.
 */
@Composable
fun Group(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: GroupScope.() -> Unit,
) {
    val items = GroupScope().apply(content).items
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionTitle(title, Modifier.padding(horizontal = 16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(index = i, count = items.size)) }
        }
    }
}

class GroupScope internal constructor() {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    /** One row; [content] receives the shapes that belong to its place in the group. */
    fun item(content: @Composable (shapes: ListItemShapes) -> Unit) {
        items += content
    }
}

/**
 * A row holding content of its own (a text field, a slider, an editable list) rather than a
 * headline. Non-interactive: the content handles its own input. Centred vertically by default,
 * because a trailing chevron or button belongs to the whole row.
 */
@Composable
fun ContentItem(
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    SegmentedListItem(
        shapes = shapes,
        modifier = modifier,
        colors = colors,
        contentPadding = contentPadding,
        verticalAlignment = verticalAlignment,
        trailingContent = trailingContent,
        supportingContent = supportingContent,
        content = content,
    )
}

/** A row that does one thing when pressed, with its icon leading. */
@Composable
fun ActionItem(shapes: ListItemShapes, title: String, icon: Int, onClick: () -> Unit, supporting: String? = null) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = haptics.ticking(onClick),
        shapes = shapes,
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        content = { Text(title) },
    )
}

/**
 * A row whose whole surface is the switch's touch target, announced as a switch. The thumb carries
 * a check while on, the expressive switch's way of stating the state without relying on colour.
 */
@Composable
fun SwitchItem(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = {
            haptics.tick()
            onCheckedChange(!checked)
        },
        shapes = shapes,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        // Centred whatever the supporting text's length. Material tops the trailing element of a
        // tall item, which suits a list of messages, but a settings switch belongs to the whole row.
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        },
        content = { Text(title) },
    )
}
