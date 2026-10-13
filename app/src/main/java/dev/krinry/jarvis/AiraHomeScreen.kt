package dev.krinry.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.FloatingBubbleService
import kotlinx.coroutines.launch

private data class ChatLine(val user: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiraHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember { GroqApiClient.getProviders() }
    var providerId by remember { mutableStateOf(SecureKeyStore.getApiProvider(context)) }
    val provider = remember(providerId) { GroqApiClient.getProvider(providerId) ?: providers.first() }
    var apiKey by remember(providerId) { mutableStateOf(provider.getApiKey(context).orEmpty()) }
    var backgroundOn by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }
    var providerMenu by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Aira taiyaar hai.") }
    val lines = remember { mutableStateListOf(ChatLine(false, "Namaste! Main Aira hoon. Hindi ya Hinglish mein baat karo.")) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) status = "Microphone permission mil gayi. Floating bubble se bolkar command do."
        else status = "Voice ke liye microphone permission zaroori hai."
    }

    fun toggleBackground(enabled: Boolean) {
        if (enabled && !isAiraAccessibilityEnabled(context)) {
            status = "Screen control ke liye Aira Accessibility Service enable karo."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            status = "Floating bubble ke liye Display over other apps permission allow karo."
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        backgroundOn = enabled
        SecureKeyStore.setAgentEnabled(context, enabled)
        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            action = if (enabled) FloatingBubbleService.ACTION_START else FloatingBubbleService.ACTION_STOP
        }
        try {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            status = if (enabled) "Background Aira ON — gol bubble screen par aa jayega." else "Background Aira OFF."
        } catch (e: Exception) {
            backgroundOn = false
            SecureKeyStore.setAgentEnabled(context, false)
            status = "Aira start nahi hua: ${e.message?.take(100) ?: "unknown error"}"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Aira") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Aira se chat karo…") },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val message = input.trim()
                            if (message.isNotEmpty() && !busy) {
                                lines.add(ChatLine(true, message)); input = ""; busy = true
                                scope.launch {
                                    try {
                                        val history = lines.dropLast(1).takeLast(12).map {
                                            mapOf("role" to if (it.user) "user" else "assistant", "content" to it.text)
                                        } + mapOf("role" to "user", "content" to message)
                                        val answer = GroqApiClient.chatWithDiagnostics(context, history)
                                        lines.add(ChatLine(false, answer))
                                        status = "Aira online"
                                    } catch (e: Exception) {
                                        val error = e.message ?: "API request failed"
                                        lines.add(ChatLine(false, "Maaf karo, API mein dikkat hai: $error"))
                                        status = error.take(140)
                                    } finally { busy = false }
                                }
                            }
                        })
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        enabled = input.isNotBlank() && !busy,
                        onClick = {
                            val message = input.trim()
                            if (message.isNotEmpty() && !busy) {
                                lines.add(ChatLine(true, message)); input = ""; busy = true
                                scope.launch {
                                    try {
                                        val history = lines.dropLast(1).takeLast(12).map {
                                            mapOf("role" to if (it.user) "user" else "assistant", "content" to it.text)
                                        } + mapOf("role" to "user", "content" to message)
                                        lines.add(ChatLine(false, GroqApiClient.chatWithDiagnostics(context, history)))
                                        status = "Aira online"
                                    } catch (e: Exception) {
                                        val error = e.message ?: "API request failed"
                                        lines.add(ChatLine(false, "API dikkat: $error"))
                                        status = error.take(140)
                                    } finally { busy = false }
                                }
                            }
                        }
                    ) { Icon(Icons.Default.Send, contentDescription = "Send") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("A", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aira AI", style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Background & floating bubble", style = MaterialTheme.typography.titleSmall)
                            Text("Screen par gol bubble aur wake word", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = backgroundOn, onCheckedChange = { toggleBackground(it) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("API setup", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showApi = !showApi }) { Text(if (showApi) "Hide" else "Set API") }
                    }
                    if (showApi) {
                        ExposedDropdownMenuBox(expanded = providerMenu, onExpandedChange = { providerMenu = it }) {
                            OutlinedTextField(
                                value = provider.displayName, onValueChange = {}, readOnly = true,
                                label = { Text("Provider") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                                providers.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.displayName) }, onClick = {
                                        providerId = item.id
                                        SecureKeyStore.setApiProvider(context, item.id)
                                        apiKey = item.getApiKey(context).orEmpty()
                                        providerMenu = false
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it; provider.saveApiKey(context, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("${provider.displayName} API key") },
                            placeholder = { Text("API key isi phone par save hogi") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Text("API key chat mein share mat karna. 403 aane par chat mein actual error dikhega.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.user) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (line.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(line.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}


private fun isAiraAccessibilityEnabled(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
