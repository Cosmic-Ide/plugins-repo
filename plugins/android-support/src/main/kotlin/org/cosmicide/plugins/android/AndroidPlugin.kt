package org.cosmicide.plugins.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
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
        context.extensions.register(
            point = UiExtensionPoints.SETTINGS_UI,
            extension = AndroidSettingsUiProvider(),
            ownerPluginId = context.descriptor.id
        ).let(context::registerDisposable)
    }
}

private class AndroidSettingsUiProvider : SettingsUiProvider {
    override val id: String = "org.cosmicide.plugins.android.settings"
    override val label: String = "Android SDK"

    @Composable
    override fun Content() {
        val sdkPath = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_SDK")
        ?: File(System.getProperty("user.home"), "Android/sdk").absolutePath

        val sdkDir = File(sdkPath)
        val isInstalled = sdkDir.exists()

        val buildToolsDir = File(sdkDir, "build-tools/37.0.0")
        val hasBuildTools = buildToolsDir.exists()

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Android SDK Status",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(text = "Path: $sdkPath", modifier = Modifier.padding(bottom = 4.dp))
            Text(
                text = "SDK Installed: ${if (isInstalled) "Yes" else "No"}",
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(text = "Build Tools 37.0.0: ${if (hasBuildTools) "Installed" else "Not Found"}")
        }
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
yes 2>/dev/null | sdkmanager --sdk_root="$ANDROID_SDK" --licenses && \
yes 2>/dev/null | sdkmanager --sdk_root="$ANDROID_SDK" "platform-tools" "build-tools;37.0.0" && \
curl -fsSL https://raw.githubusercontent.com/Commit451/android-arm-build-tools/main/install.sh | bash && \
mkdir -p ~/.gradle &&
(sed -i '/^android\.aapt2FromMavenOverride=/d' ~/.gradle/gradle.properties 2>/dev/null || true) &&
echo "android.aapt2FromMavenOverride=$HOME/Android/sdk/build-tools/37.0.0/aapt2" >> ~/.gradle/gradle.properties
""".trimIndent()
