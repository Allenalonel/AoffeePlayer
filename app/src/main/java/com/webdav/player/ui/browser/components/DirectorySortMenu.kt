package com.webdav.player.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.webdav.player.domain.model.DirectorySortOrder
import com.webdav.player.domain.model.SortDirection
import com.webdav.player.domain.model.SortField
import com.webdav.player.ui.theme.AppIcons

@Composable
fun DirectorySortActionButton(
    sortOrder: DirectorySortOrder,
    onSortOrderChanged: (DirectorySortOrder) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        FilledTonalIconButton(
            onClick = { isMenuExpanded = true },
            enabled = enabled,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = AppIcons.Sort,
                contentDescription = "排序方式",
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            SortFieldMenuItem(
                label = "名称",
                field = SortField.NAME,
                currentField = sortOrder.field,
                onClick = {
                    onSortOrderChanged(sortOrder.copy(field = SortField.NAME))
                    isMenuExpanded = false
                }
            )
            SortFieldMenuItem(
                label = "修改时间",
                field = SortField.LAST_MODIFIED,
                currentField = sortOrder.field,
                onClick = {
                    onSortOrderChanged(sortOrder.copy(field = SortField.LAST_MODIFIED))
                    isMenuExpanded = false
                }
            )
            SortFieldMenuItem(
                label = "文件大小",
                field = SortField.SIZE,
                currentField = sortOrder.field,
                onClick = {
                    onSortOrderChanged(sortOrder.copy(field = SortField.SIZE))
                    isMenuExpanded = false
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SortDirectionMenuItem(
                label = "升序",
                direction = SortDirection.ASCENDING,
                currentDirection = sortOrder.direction,
                onClick = {
                    onSortOrderChanged(sortOrder.copy(direction = SortDirection.ASCENDING))
                    isMenuExpanded = false
                }
            )
            SortDirectionMenuItem(
                label = "降序",
                direction = SortDirection.DESCENDING,
                currentDirection = sortOrder.direction,
                onClick = {
                    onSortOrderChanged(sortOrder.copy(direction = SortDirection.DESCENDING))
                    isMenuExpanded = false
                }
            )
        }
    }
}

@Composable
private fun SortFieldMenuItem(
    label: String,
    field: SortField,
    currentField: SortField,
    onClick: () -> Unit
) {
    val isSelected = field == currentField
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick
    )
}

@Composable
private fun SortDirectionMenuItem(
    label: String,
    direction: SortDirection,
    currentDirection: SortDirection,
    onClick: () -> Unit
) {
    val isSelected = direction == currentDirection
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick
    )
}
