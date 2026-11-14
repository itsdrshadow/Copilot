package com.antigravity.drivingreport.feature.launcher

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.drivingreport.core.designsystem.*
import com.antigravity.drivingreport.core.model.AppDrawerItem

@Composable
fun AppDrawerDialog(
    apps: List<AppDrawerItem>,
    onAppClick: (AppDrawerItem) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val query = searchQuery.trim().lowercase()
            apps.filter { it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var isVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            isVisible = true
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140))
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CockpitTheme.colors.bg.copy(alpha = 0.98f))
            ) {
            val isCompact = maxHeight < 500.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isCompact) 18.dp else 28.dp, vertical = if (isCompact) 12.dp else 20.dp)
            ) {
                // ==================== 1. TOP HEADER & SEARCH BAR ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App Drawer Title & Subtitle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(if (isCompact) 38.dp else 46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CockpitTheme.colors.accentSoft)
                                .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "All Applications",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = if (isCompact) 20.sp else 24.sp
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CockpitTheme.colors.cardElevated)
                                        .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${filteredApps.size} apps",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                        color = CockpitTheme.colors.accent
                                    )
                                }
                            }

                            if (!isCompact) {
                                Text(
                                    text = "Automotive Infotainment System \u2022 Tap any app to launch",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = CockpitTheme.colors.textSecondary
                                )
                            }
                        }
                    }

                    // Search Field & Close Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Live Filter Search Box
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search apps...", color = CockpitTheme.colors.textDisabled, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .width(if (isCompact) 220.dp else 280.dp)
                                .height(if (isCompact) 42.dp else 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CockpitTheme.colors.textPrimary,
                                unfocusedTextColor = CockpitTheme.colors.textPrimary,
                                focusedBorderColor = CockpitTheme.colors.accent,
                                unfocusedBorderColor = CockpitTheme.colors.border,
                                focusedContainerColor = CockpitTheme.colors.cardElevated,
                                unfocusedContainerColor = CockpitTheme.colors.card
                            )
                        )

                        // Close Dialog Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(if (isCompact) 38.dp else 44.dp)
                                .clip(CircleShape)
                                .background(CockpitTheme.colors.cardElevated)
                                .border(1.dp, CockpitTheme.colors.border, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Apps",
                                tint = CockpitTheme.colors.textPrimary,
                                modifier = Modifier.size(if (isCompact) 18.dp else 22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 16.dp))
                HorizontalDivider(color = CockpitTheme.colors.border)
                Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 16.dp))

                // ==================== 2. RESPONSIVE APPS GRID ====================
                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No applications match \"$searchQuery\"",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CockpitTheme.colors.textSecondary
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (isCompact) 92.dp else 118.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredApps,
                            key = { "${it.packageName}/${it.activityName}" },
                            contentType = { "AppDrawerCard" }
                        ) { app ->
                            AppDrawerCard(
                                app = app,
                                isCompact = isCompact,
                                onClick = {
                                    onAppClick(app)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun AppDrawerCard(
    app: AppDrawerItem,
    isCompact: Boolean = false,
    onClick: () -> Unit
) {
    val imageBitmap = remember(app.packageName, app.activityName) {
        app.iconBitmap?.asImageBitmap()
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(if (isCompact) 14.dp else 18.dp))
            .background(CockpitTheme.colors.card)
            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(if (isCompact) 14.dp else 18.dp))
            .clickable { onClick() }
            .padding(if (isCompact) 8.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = app.label,
                modifier = Modifier
                    .size(if (isCompact) 44.dp else 56.dp)
                    .clip(RoundedCornerShape(if (isCompact) 10.dp else 14.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(if (isCompact) 44.dp else 56.dp)
                    .clip(RoundedCornerShape(if (isCompact) 10.dp else 14.dp))
                    .background(CockpitTheme.colors.cardElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = CockpitTheme.colors.accent
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 8.dp))

        Text(
            text = app.label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = if (isCompact) 11.sp else 12.5.sp,
                lineHeight = if (isCompact) 13.sp else 15.sp
            ),
            color = CockpitTheme.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
