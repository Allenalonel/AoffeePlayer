package com.webdav.player.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.ui.common.FluidTopAppBar
import com.webdav.player.ui.player.MiniPlayerDefaults
import com.webdav.player.ui.server.components.ServerCardItem
import com.webdav.player.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerManagementViewModel,
    modifier: Modifier = Modifier,
    onNavigateToBrowser: () -> Unit = {},
    onSelectServerAndNavigate: (WebDavServer) -> Unit = { server ->
        viewModel.onSelectActiveServer(server.id)
        onNavigateToBrowser()
    },
    fabBottomPadding: Dp = 80.dp,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FluidTopAppBar(
                title = "存储",
                subtitle =
                    if (uiState.activeServer != null) {
                        "活跃节点: ${uiState.activeServer?.name}"
                    } else {
                        "未选定活跃节点"
                    },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onAddServerClicked() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = fabBottomPadding),
            ) {
                Icon(imageVector = AppIcons.Add, contentDescription = "添加服务器")
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        top = 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = MiniPlayerDefaults.PenetrativeContentBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. Pinned Local Storage Card
                item(key = "local_storage_card") {
                    LocalStorageCardItem(
                        isActive = uiState.activeServer?.id == LOCAL_STORAGE_ID,
                        mountedFolderCount = uiState.mountedFolders.size,
                        onCardClick = {
                            onSelectServerAndNavigate(uiState.localStorageServer)
                        },
                        onManageFoldersClick = {
                            viewModel.onOpenManageFolders()
                        },
                    )
                }

                // 2. Section Header for Remote WebDAV Servers
                item(key = "remote_servers_header") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "WebDAV 服务器",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }

                // 3. Remote Servers or Empty State
                val remoteServers = uiState.remoteServers
                if (remoteServers.isEmpty()) {
                    item(key = "remote_servers_empty") {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = AppIcons.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "暂无配置的 WebDAV 服务器",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "点击右下角 \"+\" 按钮添加您的私人 NAS 或云端存储",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(remoteServers, key = { it.id }) { server ->
                        val isActive = server.id == uiState.activeServer?.id
                        val isTesting = uiState.testingServerId == server.id
                        val testResult = uiState.serverConnectionResults[server.id]

                        ServerCardItem(
                            server = server,
                            isActive = isActive,
                            isTestingConnection = isTesting,
                            connectionTestResult = testResult,
                            onCardClick = {
                                onSelectServerAndNavigate(server)
                            },
                            onTestConnection = {
                                viewModel.onTestConnection(server)
                            },
                            onEdit = {
                                viewModel.onEditServerClicked(server)
                            },
                            onDelete = {
                                viewModel.onRequestDeleteServer(server)
                            },
                        )
                    }
                }
            }

            // Manage Folders Sheet
            if (uiState.isManageFoldersSheetVisible) {
                ManageFoldersSheet(
                    folders = uiState.mountedFolders,
                    onAddFolder = { uriString, displayName ->
                        viewModel.onAddMountedFolder(uriString, displayName)
                    },
                    onRemoveFolder = { uriString ->
                        viewModel.onRemoveMountedFolder(uriString)
                    },
                    onDismiss = {
                        viewModel.onDismissManageFolders()
                    },
                )
            }

            // Add/Edit Dialog
            if (uiState.isAddEditDialogVisible) {
                ServerEditDialog(
                    serverToEdit = uiState.serverToEdit,
                    testingConnection = uiState.testingConnection,
                    testResult = uiState.connectionTestResult,
                    onTestConnection = { viewModel.onTestConnection(it) },
                    onSave = { viewModel.onSaveServer(it) },
                    onDismiss = { viewModel.onDismissAddEditDialog() },
                )
            }

            // Delete Confirm Dialog
            uiState.serverToDelete?.let { server ->
                DeleteConfirmationDialog(
                    server = server,
                    onConfirm = { viewModel.onConfirmDeleteServer() },
                    onDismiss = { viewModel.onDismissDeleteDialog() },
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    server: WebDavServer,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("删除服务器配置")
        },
        text = {
            Text("确定要删除服务器 \"${server.name}\" 吗？此操作无法撤销。")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
