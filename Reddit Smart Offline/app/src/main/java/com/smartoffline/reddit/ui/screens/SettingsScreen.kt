package com.smartoffline.reddit.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.data.SortMode
import com.smartoffline.reddit.data.StorageTarget
import com.smartoffline.reddit.data.ThemeMode
import com.smartoffline.reddit.net.AccessMode
import com.smartoffline.reddit.net.RedditClient
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.Success
import com.smartoffline.reddit.ui.components.InfoBanner
import com.smartoffline.reddit.ui.components.SectionHeader
import com.smartoffline.reddit.ui.components.SettingRow
import com.smartoffline.reddit.util.Format

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var clientId by remember(prefs.clientId) { mutableStateOf(prefs.clientId) }
    var customLimit by remember { mutableStateOf(false) }
    val mode = vm.accessMode()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.onTreePicked(uri) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

        // ---------------------------------------------------------------- account
        SectionHeader("Reddit account")
        Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mode == AccessMode.ACCOUNT) Icons.Filled.AccountCircle else Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mode == AccessMode.ACCOUNT) "u/${prefs.username ?: "…"}" else "Guest mode", style = MaterialTheme.typography.titleMedium)
                    Text(mode.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mode == AccessMode.ACCOUNT) TextButton(onClick = { vm.signOut() }) { Icon(Icons.Filled.ExitToApp, null); Spacer(Modifier.width(4.dp)); Text("Sign out") }
            }
            if (prefs.authExpired) InfoBanner("Your previous sign-in expired or was revoked. Sign in again to restore account features.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text("Your Reddit app client ID", style = MaterialTheme.typography.titleSmall)
                Text("Reddit requires every app to use its own API credentials. Create a free “installed app” at reddit.com/prefs/apps with redirect URI:",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val clip = LocalClipboardManager.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(RedditClient.REDIRECT_URI, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clip.setText(AnnotatedString(RedditClient.REDIRECT_URI)); vm.say("Redirect URI copied") }) { Text("Copy") }
                }
                OutlinedTextField(clientId, { clientId = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") },
                    placeholder = { Text("e.g. a1B2c3D4e5F6g7") })
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setClientId(clientId); vm.say(if (clientId.isBlank()) "Client ID removed" else "Client ID saved") }, enabled = clientId != prefs.clientId) { Text("Save") }
                    if (mode != AccessMode.ACCOUNT) Button(onClick = {
                        if (clientId != prefs.clientId) vm.setClientId(clientId)
                        vm.signInUrl()?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    }, enabled = clientId.isNotBlank()) { Text("Sign in with Reddit") }
                    TextButton(onClick = { vm.navigate(Screen.About) }) { Text("Setup guide") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (mode) {
                        AccessMode.ACCOUNT -> "Signed in: subscriptions can be imported and requests use your account's API quota."
                        AccessMode.APP_GUEST -> "Guest mode with your client ID: public communities and posts, no account needed. Sign in to import your subscriptions."
                        AccessMode.PUBLIC_GUEST -> "Guest mode without a client ID uses Reddit's limited public access: slow (about 10 requests a minute) and sometimes blocked. Subscriptions require signing in."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------------------- downloads
        SectionHeader("Smart downloads")
        Card {
            SwitchRow("Automatic downloads", "Keep subreddits up to date in the background", prefs.autoDownloads) { v -> vm.updatePrefs { it.copy(autoDownloads = v) } }
            SwitchRow("Include NSFW content", "Off: NSFW posts are skipped", prefs.showNsfw) { v -> vm.updatePrefs { it.copy(showNsfw = v) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text("Defaults for new subreddits", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortMode.entries.forEach { m -> FilterChip(prefs.defaultSort == m, { vm.updatePrefs { it.copy(defaultSort = m) } }, { Text(m.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Posts", modifier = Modifier.weight(1f))
                    NumberField(prefs.defaultCount, 1, 1000, "", Modifier.width(120.dp)) { v -> vm.updatePrefs { it.copy(defaultCount = v) } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 48, 168).forEach { h -> FilterChip(prefs.defaultInterval == h, { vm.updatePrefs { it.copy(defaultInterval = h) } }, { Text(Format.interval(h).removePrefix("Every ")) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Top comments", modifier = Modifier.weight(1f))
                    Switch(prefs.defaultComments, { v -> vm.updatePrefs { it.copy(defaultComments = v) } })
                    Spacer(Modifier.width(8.dp))
                    if (prefs.defaultComments) NumberField(prefs.defaultCommentCount, 1, 200, "", Modifier.width(100.dp)) { v -> vm.updatePrefs { it.copy(defaultCommentCount = v) } }
                }
            }
        }

        // ---------------------------------------------------------------- storage
        SectionHeader("Storage")
        if (prefs.storageUnavailable) InfoBanner("The selected storage location is unavailable. Choose another location below.", color = Danger)
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Save new downloads to", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                vm.storageOptions().forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(prefs.storage == o.target, { vm.chooseStorage(o.target) })
                        Column { Text(o.label); Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val tree = prefs.storage as? StorageTarget.Tree
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(tree != null, { treePicker.launch(null) })
                    Column(Modifier.weight(1f)) {
                        Text("A folder I choose")
                        Text(tree?.let { vm.describeStorage(it) } ?: "Any folder, including on an SD card", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { treePicker.launch(null) }) { Icon(AppIcons.Folder, null); Spacer(Modifier.width(4.dp)); Text("Choose") }
                }
                Text("Files already downloaded stay where they are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text("Storage limit", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(0L to "Unlimited", 1024L to "1 GB", 2048L to "2 GB", 5120L to "5 GB", 10240L to "10 GB")
                    presets.forEach { (mb, label) -> FilterChip(prefs.storageLimitMb == mb, { vm.updatePrefs { it.copy(storageLimitMb = mb) } }, { Text(label) }) }
                    val isCustom = presets.none { it.first == prefs.storageLimitMb }
                    FilterChip(isCustom, { customLimit = true }, { Text(if (isCustom) "Custom · ${Format.bytes(prefs.storageLimitMb * 1024 * 1024)}" else "Custom") })
                }
                Text("When the limit is reached, unused files are removed first; favorites are never deleted. If space is still short, new downloads pause and you're notified.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingRow("Storage dashboard", "Usage by subreddit, cleanup tools", AppIcons.Storage, onClick = { vm.navigate(Screen.StorageDashboard) })
        }

        // ---------------------------------------------------------------- sync & network
        SectionHeader("Synchronization & network")
        Card {
            SwitchRow("Wi-Fi only", "Don't use mobile data for downloads", prefs.wifiOnly) { v -> vm.updatePrefs { it.copy(wifiOnly = v) } }
            SwitchRow("Only when battery isn't low", "Background syncs wait for enough battery", prefs.batteryNotLow) { v -> vm.updatePrefs { it.copy(batteryNotLow = v) } }
            SwitchRow("Only while charging", "Background syncs run only when plugged in", prefs.requireCharging) { v -> vm.updatePrefs { it.copy(requireCharging = v) } }
            SettingRow("Download activity", "Progress, queue, history, pause and retry", AppIcons.History, onClick = { vm.navigate(Screen.SyncDashboard) })
        }

        // ---------------------------------------------------------------- appearance
        SectionHeader("Appearance")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, t ->
                SegmentedButton(prefs.theme == t, { vm.updatePrefs { it.copy(theme = t) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                    Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }

        SectionHeader("About")
        Card { SettingRow("Reddit Smart Offline", "Version 1.0 · setup guide and limitations", Icons.Filled.Info, onClick = { vm.navigate(Screen.About) }) }
    }

    if (customLimit) {
        var gb by remember { mutableStateOf((prefs.storageLimitMb / 1024).coerceAtLeast(1).toString()) }
        AlertDialog(
            onDismissRequest = { customLimit = false },
            title = { Text("Custom storage limit") },
            text = {
                OutlinedTextField(gb, { gb = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, singleLine = true, suffix = { Text("GB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(onClick = {
                    gb.toDoubleOrNull()?.takeIf { it > 0 }?.let { v -> vm.updatePrefs { it.copy(storageLimitMb = (v * 1024).toLong().coerceAtLeast(100)) } }
                    customLimit = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { customLimit = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) { Switch(checked, onChange) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(vm: AppViewModel) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("About & setup") }, navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Text("Reddit Smart Offline", style = MaterialTheme.typography.headlineSmall)
            Text("Smart Downloads for Reddit: chosen communities are kept on your device automatically and readable without a connection.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Connect to Reddit (recommended)")
            Markdown(
                """1. Open **reddit.com/prefs/apps** in a browser while signed in to Reddit.
                  |2. Tap **create another app…**, choose the **installed app** type and give it any name.
                  |3. Set the redirect URI to `${RedditClient.REDIRECT_URI}` and create the app.
                  |4. Copy the client ID shown under the app's name (a string of about 22 characters).
                  |5. Paste it in **Settings → Reddit account → Client ID** and tap Save.
                  |
                  |With the client ID saved you can use **guest mode** (no Reddit account) or tap **Sign in with Reddit** to import your subscriptions. The app never stores your password: Reddit's own sign-in page is used, and only an encrypted access token is kept on this device.""".trimMargin(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { vm.back() }) { Text("Back to Settings") }
            SectionHeader("What needs an account")
            Markdown(
                """• **Guest mode** – search communities, add public subreddits, download public posts, media and comments.
                  |• **Signed in** – everything above, plus importing your subscribed communities and a higher API quota.
                  |• Without any client ID, Reddit's public access is used: slow and may be blocked by Reddit at any time.""".trimMargin(),
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionHeader("Limitations")
            Markdown(
                """• **Background timing** – Android delays background work to save battery, so scheduled syncs run approximately on time, not exactly.
                  |• **External videos** (YouTube, Streamable, Redgifs…) can't be downloaded through Reddit's API; only their preview image is saved and the post links to the original.
                  |• **Reddit videos** are stored with sound when Reddit provides an audio track; some clips have none.
                  |• **Linked web pages** are not saved; the link and any preview image are.
                  |• **Comments** – only the top-ranked comments and a few replies are saved, as configured per subreddit.
                  |• **Rate limits** – Reddit limits how fast apps may make requests. Large selections take longer and continue at the next run when limits are hit.
                  |• **Deleted content** – files that Reddit removed can't be downloaded anymore and are marked as unavailable.""".trimMargin(),
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionHeader("Privacy")
            Text("Everything is stored on this device. The app talks only to Reddit.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Success)
                Spacer(Modifier.width(8.dp))
                Text("Not affiliated with Reddit, Inc.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
