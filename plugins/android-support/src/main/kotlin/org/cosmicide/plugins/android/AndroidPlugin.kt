package org.cosmicide.plugins.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.ui.SettingsUiProvider
import org.cosmicide.ui.UiExtensionPoints
import java.io.File

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
        "30" to "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r30/android-ndk-r30-beta2-aarch64-linux-gnu.tar.xz"
    )

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val sdkPath = remember {
            System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_SDK")
            ?: File(System.getProperty("user.home"), "Android/sdk").absolutePath
        }

        val sdkDir = remember(sdkPath) { File(sdkPath) }
        val buildToolsRoot = remember(sdkDir) { File(sdkDir, "build-tools") }
        val ndkRoot = remember(sdkDir) {
            val ndk = File(sdkDir, "ndk")
            if (ndk.exists()) ndk else File(sdkDir, "ndk-bundle")
        }

        val installedBuildTools = remember(buildToolsRoot) {
            buildToolsRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }
                ?.sortedDescending() ?: emptyList()
        }

        val installedNdk = remember(ndkRoot) {
            ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending()
                ?: emptyList()
        }

        Column(modifier = Modifier.padding(16.dp)) {
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

            Button(
                onClick = {
                    scope.launch {
                        patchAll(sdkPath)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Patch Unpatched Tools")
            }
        }
    }

    private suspend fun patchAll(sdkPath: String) {
        val sdkDir = File(sdkPath)
        val buildToolsRoot = File(sdkDir, "build-tools")
        val ndkRoot = File(sdkDir, "ndk")
        val installScript = File(sdkDir, "install.sh")

        // 1. Download install.sh
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
        )
        commands.execute(
            CommandRequest(
                command = "chmod",
                arguments = listOf("+x", installScript.absolutePath),
                workingDirectory = sdkDir
            )
        )

        // 2. Build Tools
        val supportedBuildTools = setOf("35.0.1", "36.0.0", "36.1.0", "37.0.0")
        buildToolsRoot.listFiles()?.filter { it.isDirectory && it.name in supportedBuildTools }
            ?.forEach { dir ->
                if (!isPatched(dir)) {
                    commands.execute(
                        CommandRequest(
                            command = installScript.absolutePath,
                            arguments = listOf("--version", dir.name),
                            workingDirectory = sdkDir
                        )
                    )
                    File(dir, ".patched").createNewFile()
                }
            }

        // 3. NDK
        ndkRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val version = dir.name
            val roughVersion = version.substringBefore(".")
            val url = NDK_ARM_URLS[roughVersion]
            if (!url.isNullOrBlank() && !isPatched(dir)) {
                patchNdk(sdkPath, dir, url)
            }
        }
    }

    private suspend fun patchNdk(sdkPath: String, dir: File, url: String) {
        val version = dir.name
        val tempDir = File(sdkPath, "tmp_ndk_$version")
        tempDir.mkdirs()
        val archive = File(tempDir, "ndk.tar.gz")

        commands.execute(
            CommandRequest(
                command = "curl",
                arguments = listOf("-L", url, "-o", archive.absolutePath),
                workingDirectory = File(sdkPath)
            )
        )
        commands.execute(
            CommandRequest(
                command = "tar",
                arguments = listOf("-xzf", archive.absolutePath, "-C", tempDir.absolutePath),
                workingDirectory = File(sdkPath)
            )
        )

        val extracted = tempDir.listFiles()?.firstOrNull { it.isDirectory }
        if (extracted != null) {
            commands.execute(
                CommandRequest(
                    command = "rm",
                    arguments = listOf("-rf", dir.absolutePath),
                    workingDirectory = File(sdkPath)
                )
            )
            commands.execute(
                CommandRequest(
                    command = "mv",
                    arguments = listOf(extracted.absolutePath, dir.absolutePath),
                    workingDirectory = File(sdkPath)
                )
            )
            File(dir, ".patched").createNewFile()
        }
        tempDir.deleteRecursively()
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
(grep -q "ANDROID_SDK" ~/.bash_profile || (echo 'export ANDROID_SDK=$HOME/Android/sdk' >> ~/.bash_profile && echo 'export ANDROID_SDK_ROOT=$HOME/Android/sdk' >> ~/.bash_profile && echo 'export PATH=$PATH:$ANDROID_SDK/cmdline-tools/latest/bin' >> ~/.bash_profile)) && \
export ANDROID_SDK=$HOME/Android/sdk && \
export PATH=$PATH:$ANDROID_SDK/cmdline-tools/latest/bin && \
curl -fsSL https://raw.githubusercontent.com/Commit451/android-arm-build-tools/main/install.sh -o $ANDROID_SDK/install.sh && \
chmod +x $ANDROID_SDK/install.sh && \
sdkmanager --sdk_root="$ANDROID_SDK" --licenses && \
sdkmanager --sdk_root="$ANDROID_SDK" "build-tools;37.0.0" && \
$ANDROID_SDK/install.sh --version 37.0.0 && \
touch "$ANDROID_SDK/build-tools/37.0.0/.patched" && \
mkdir -p ~/.gradle &&
(sed -i '/^android\.aapt2FromMavenOverride=/d' ~/.gradle/gradle.properties 2>/dev/null || true) &&
echo "android.aapt2FromMavenOverride=$HOME/Android/sdk/build-tools/37.0.0/aapt2" >> ~/.gradle/gradle.properties
""".trimIndent()
