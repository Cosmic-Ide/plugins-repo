package org.cosmicide.plugins.android

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.ui.SettingsUiProvider
import org.cosmicide.ui.UiExtensionPoints
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class AndroidPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.android.installSdk",
            label = "Install Android SDK",
            command = INSTALL_COMMAND,
            description = "Download and install Android Command Line Tools and Build Tools 37.0.0"
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        context.extensions.register(
            point = UiExtensionPoints.SETTINGS_UI,
            extension = AndroidSettingsUiProvider(commandService),
            ownerPluginId = context.descriptor.id
        ).let(context::registerDisposable)
    }
}

private class AndroidSettingsUiProvider(private val commands: CommandExecutionService) :
    SettingsUiProvider {
    override val id: String = "org.cosmicide.plugins.android.settings"
    override val label: String = "Android SDK"

    private val NDK_ARM_URLS = mapOf(
        "26" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r26/android-ndk-r26d-aarch64-linux-gnu.tar.xz",
        "27" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r27/android-ndk-r27d-aarch64-linux-gnu.tar.xz",
        "28" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r28/android-ndk-r28c-aarch64-linux-gnu.tar.xz",
        "29" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r29/android-ndk-r29-aarch64-linux-gnu.tar.xz",
        "30" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r30/android-ndk-r30-beta3-aarch64-linux-gnu.tar.xz"
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var refreshTrigger by remember { mutableIntStateOf(0) }
        val sdkPath = remember(context, refreshTrigger) {
            val candidates = listOfNotNull(
                File(context.filesDir, "arch/home/Android/sdk").absolutePath,
                File(System.getProperty("user.home"), "Android/sdk").absolutePath,
                File(
                    Environment.getExternalStorageDirectory(),
                    "Android/sdk"
                ).absolutePath
            )
            candidates.firstOrNull { File(it).exists() }
                ?: candidates.first()
        }

        val sdkDir = remember(sdkPath, refreshTrigger) { File(sdkPath) }
        val buildToolsRoot = remember(sdkDir, refreshTrigger) { File(sdkDir, "build-tools") }
        val ndkRoot = remember(sdkDir, refreshTrigger) { File(sdkDir, "ndk") }

        val installedBuildTools = remember(buildToolsRoot, refreshTrigger) {
            buildToolsRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }
                ?.sortedDescending() ?: emptyList()
        }

        val installedNdk = remember(ndkRoot, refreshTrigger) {
            ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending()
                ?: emptyList()
        }

        var isExecuting by remember { mutableStateOf(false) }
        val outputLogs = remember { mutableStateListOf<String>() }

        fun log(message: String) {
            message.lines().filter { it.isNotBlank() }.forEach { line ->
                outputLogs.add(line)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(label) },
                    navigationIcon = {
                        IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "Android SDK Status",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(text = "SDK Path", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = sdkPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SectionTitle("Build Tools")
                if (installedBuildTools.isEmpty()) {
                    Text(
                        text = "No Build Tools installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    installedBuildTools.forEach { version ->
                        VersionItem(
                            version = version,
                            isSupported = isBuildToolSupported(version),
                            isPatched = isPatched(File(buildToolsRoot, version))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                SectionTitle("NDK")
                if (installedNdk.isEmpty()) {
                    Text(
                        text = "No NDK installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    installedNdk.forEach { version ->
                        VersionItem(
                            version = version,
                            isSupported = isNdkSupported(version),
                            isPatched = isPatched(File(ndkRoot, version))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!isExecuting) {
                                isExecuting = true
                                outputLogs.clear()
                                scope.launch {
                                    try {
                                        installSdk(sdkPath, ::log)
                                    } finally {
                                        isExecuting = false
                                        refreshTrigger += 1
                                    }
                                }
                            }
                        },
                        enabled = !isExecuting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Install SDK")
                    }

                    Button(
                        onClick = {
                            if (!isExecuting) {
                                isExecuting = true
                                outputLogs.clear()
                                scope.launch {
                                    try {
                                        patchAll(sdkPath, context, ::log)
                                    } finally {
                                        isExecuting = false
                                        refreshTrigger += 1
                                    }
                                }
                            }
                        },
                        enabled = !isExecuting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Patch Tools")
                    }
                }

                if (isExecuting || outputLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isExecuting) "Terminal Output (Running...)" else "Terminal Output",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val listState = rememberLazyListState()
                    LaunchedEffect(outputLogs.size) {
                        if (outputLogs.isNotEmpty()) {
                            listState.animateScrollToItem(outputLogs.size - 1)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = Color(0xFF1E1E1E),
                        contentColor = Color(0xFFD4D4D4)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(outputLogs) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFFD4D4D4)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun installSdk(sdkPath: String, log: (String) -> Unit) {
        val sdkDir = File(sdkPath)
        sdkDir.mkdirs()
        log("Installing Android SDK at ${sdkDir.absolutePath}...")
        val result = commands.execute(
            CommandRequest(
                command = "bash",
                arguments = listOf("-c", INSTALL_COMMAND),
                workingDirectory = sdkDir
            )
        ) { log(it) }

        if (result.exitCode == 0) {
            log("Android SDK installation completed successfully.")
        } else {
            log("Android SDK installation failed with exit code ${result.exitCode}")
        }
    }

    private suspend fun patchAll(
        sdkPath: String,
        context: Context,
        log: (String) -> Unit
    ) {
        val sdkDir = File(sdkPath)
        val buildToolsRoot = File(sdkDir, "build-tools")
        val ndkRoot = File(sdkDir, "ndk")
        val installScript = File(sdkDir, "install.sh")

        log("Downloading build-tools patch script...")
        commands.execute(
            CommandRequest(
                command = "curl",
                arguments = listOf(
                    "-fsSL",
                    "https://raw.githubusercontent.com/Commit451/android-arm-build-tools/main/install.sh",
                    "-o",
                    installScript.absolutePath
                ),
                workingDirectory = sdkDir
            )
        ) { log(it) }

        commands.execute(
            CommandRequest(
                command = "chmod",
                arguments = listOf("+x", installScript.absolutePath),
                workingDirectory = sdkDir
            )
        ) { log(it) }

        // 2. Build Tools
        val supportedBuildTools = setOf("35.0.1", "36.0.0", "36.1.0", "37.0.0")
        val unpatchedBuildTools = buildToolsRoot.listFiles()
            ?.filter { it.isDirectory && it.name in supportedBuildTools && !isPatched(it) }
            ?: emptyList()

        if (unpatchedBuildTools.isEmpty()) {
            log("No unpatched build-tools found.")
        } else {
            unpatchedBuildTools.forEach { dir ->
                log("Patching build-tools ${dir.name}...")
                val result = commands.execute(
                    CommandRequest(
                        command = installScript.absolutePath,
                        arguments = listOf("--version", dir.name),
                        workingDirectory = sdkDir
                    )
                ) { log(it) }

                if (result.exitCode == 0) {
                    File(dir, ".patched").createNewFile()
                    log("Successfully patched build-tools ${dir.name}")
                } else {
                    log("Failed to patch build-tools ${dir.name} (exit code ${result.exitCode})")
                }
            }
        }

        // 3. NDK
        val unpatchedNdks = ndkRoot.listFiles()
            ?.filter { it.isDirectory && !isPatched(it) }
            ?: emptyList()

        if (unpatchedNdks.isEmpty()) {
            log("No unpatched NDK found.")
        } else {
            unpatchedNdks.forEach { dir ->
                val version = dir.name
                val roughVersion = version.substringBefore(".")
                val url = NDK_ARM_URLS[roughVersion]
                if (!url.isNullOrBlank()) {
                    log("Patching NDK $version...")
                    patchNdk(sdkPath, dir, url, context, log)
                } else {
                    log("No custom ARM NDK URL available for version $version (rough $roughVersion)")
                }
            }
        }

        log("Patch process finished.")
    }

    private suspend fun patchNdk(
        sdkPath: String,
        dir: File,
        url: String,
        context: Context,
        log: (String) -> Unit
    ) {
        val version = dir.name
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.cacheDir
        val tempDownloadFile = File(downloadDir, "ndk_$version.tar.xz")

        log("Downloading NDK $version via Android DownloadManager...")
        val downloaded = downloadWithDownloadManager(
            context = context,
            url = url,
            title = "Android NDK $version",
            destinationFile = tempDownloadFile,
            onProgress = log
        )

        if (!downloaded || !tempDownloadFile.exists()) {
            log("NDK download failed for $version. Aborting NDK patch.")
            return
        }

        val tempDir = File(sdkPath, "tmp_ndk_$version")
        tempDir.mkdirs()

        log("Extracting NDK $version...")
        val extractResult = commands.execute(
            CommandRequest(
                command = "tar",
                arguments = listOf(
                    "-xf",
                    tempDownloadFile.absolutePath,
                    "-C",
                    tempDir.absolutePath
                ),
                workingDirectory = File(sdkPath)
            )
        ) { log(it) }

        tempDownloadFile.delete()

        if (extractResult.exitCode != 0) {
            log("Extraction failed for NDK $version (exit code ${extractResult.exitCode})")
            tempDir.deleteRecursively()
            return
        }

        val extracted = tempDir.listFiles()?.firstOrNull { it.isDirectory }
        if (extracted != null) {
            log("Replacing legacy NDK files with custom ARM binaries...")
            commands.execute(
                CommandRequest(
                    command = "rm",
                    arguments = listOf("-rf", dir.absolutePath),
                    workingDirectory = File(sdkPath)
                )
            ) { log(it) }

            commands.execute(
                CommandRequest(
                    command = "mv",
                    arguments = listOf(extracted.absolutePath, dir.absolutePath),
                    workingDirectory = File(sdkPath)
                )
            ) { log(it) }

            File(dir, ".patched").createNewFile()
            log("Successfully patched NDK $version")
        } else {
            log("Extraction succeeded but no inner directory was found in NDK archive.")
        }
        tempDir.deleteRecursively()
    }

    private suspend fun downloadWithDownloadManager(
        context: Context,
        url: String,
        title: String,
        destinationFile: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            destinationFile.parentFile?.mkdirs()

            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: run {
                        onProgress("DownloadManager service unavailable.")
                        return@withContext false
                    }

            val request = DownloadManager.Request(url.toUri())
                .setTitle(title)
                .setDescription("Downloading...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destinationFile))

            val downloadId = downloadManager.enqueue(request)
            onProgress("Enqueued $title (ID: $downloadId)")

            var downloading = true
            var success = false
            var lastLogTime = 0L

            while (downloading && currentCoroutineContext().isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                        val bytesDownloadedIdx =
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIdx =
                            cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val downloaded =
                            if (bytesDownloadedIdx >= 0) cursor.getLong(bytesDownloadedIdx) else 0L
                        val total = if (bytesTotalIdx >= 0) cursor.getLong(bytesTotalIdx) else 0L

                        val now = System.currentTimeMillis()
                        if (now - lastLogTime >= 1000) {
                            lastLogTime = now
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                val mbDownloaded = downloaded / (1024 * 1024)
                                val mbTotal = total / (1024 * 1024)
                                onProgress("$title: $mbDownloaded MB / $mbTotal MB ($percent%)")
                            } else if (downloaded > 0) {
                                val mbDownloaded = downloaded / (1024 * 1024)
                                onProgress("$title: $mbDownloaded MB downloaded")
                            }
                        }

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloading = false
                                success = true
                                onProgress("Download completed successfully: ${destinationFile.name}")
                            }

                            DownloadManager.STATUS_FAILED -> {
                                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                                downloading = false
                                success = false
                                onProgress("Download failed with reason code: $reason")
                            }
                        }
                    } else {
                        downloading = false
                    }
                }
                if (downloading) {
                    delay(500.milliseconds)
                }
            }
            success
        } catch (e: Exception) {
            onProgress("Download error: ${e.message}")
            false
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    @Composable
    private fun VersionItem(version: String, isSupported: Boolean, isPatched: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = version,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            val (status, color) = when {
                isSupported && isPatched -> "Supported" to Color(0xFF4CAF50)
                isSupported && !isPatched -> "Unpatched" to Color(0xFFFFC107)
                else -> "Unsupported" to Color(0xFFF44336)
            }

            Surface(
                color = color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.extraSmall,
                border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
            ) {
                Text(
                    text = status.uppercase(),
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }

    private fun isPatched(dir: File) =
        File(dir, ".patched").exists() || File(dir, "aapt2.bak").exists() || File(
            dir,
            ".arm64_patched"
        ).exists()

    private fun isBuildToolSupported(version: String) =
        version in setOf("35.0.1", "36.0.0", "36.1.0", "37.0.0")

    private fun isNdkSupported(version: String): Boolean {
        val prefixes = listOf("26.", "27.", "28.", "29.", "30.")
        return prefixes.any { version.startsWith(it) }
    }
}

private val INSTALL_COMMAND = $$"""
mkdir -p ~/Android/sdk && \
curl -L https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/cmdline-tools.zip && \
unzip /tmp/cmdline-tools.zip -d /tmp/android-sdk-tools && \
mkdir -p ~/Android/sdk/cmdline-tools/latest && \
cp -r /tmp/android-sdk-tools/cmdline-tools/* ~/Android/sdk/cmdline-tools/latest/ && \
rm -rf /tmp/android-sdk-tools /tmp/cmdline-tools.zip && \
(grep -q "ANDROID_HOME" ~/.bash_profile || (echo 'export ANDROID_HOME=$HOME/Android/sdk' >> ~/.bash_profile && echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bash_profile)) && \
export ANDROID_HOME=$HOME/Android/sdk && \
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin && \
curl -fsSL https://raw.githubusercontent.com/Commit451/android-arm-build-tools/main/install.sh -o $ANDROID_HOME/install.sh && \
chmod +x $ANDROID_HOME/install.sh && \
sdkmanager --licenses && \
sdkmanager "build-tools;37.0.0" && \
$ANDROID_HOME/install.sh --version 37.0.0 && \
touch "$ANDROID_HOME/build-tools/37.0.0/.patched" && \
mkdir -p ~/.gradle &&
(sed -i '/^android\.aapt2FromMavenOverride=/d' ~/.gradle/gradle.properties 2>/dev/null || true) &&
echo "android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/37.0.0/aapt2" >> ~/.gradle/gradle.properties
""".trimIndent()
