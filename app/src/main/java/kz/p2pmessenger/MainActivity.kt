package kz.p2pmessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kz.p2pmessenger.internet.InternetScreen
import kz.p2pmessenger.internet.InternetViewModel

class MainActivity : ComponentActivity() {
    private val internet by lazy { ViewModelProvider(this)[InternetViewModel::class.java] }
    private val messenger get() = (application as MessengerApplication).messenger
    private var nsd: NsdDiscovery? = null
    private val peers = mutableStateListOf<Peer>()
    private var discoveryStatus by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        nsd?.stop()
        nsd = null
        super.onStop()
    }

    private fun startDiscovery() {
        nsd?.stop()
        nsd = NsdDiscovery(this, { found ->
            peers.clear()
            peers.addAll(found)
        }, { discoveryStatus = it }).also { it.start() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var internetMode by rememberSaveable { mutableStateOf(false) }
            var host by rememberSaveable { mutableStateOf("") }
            var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
            var text by rememberSaveable { mutableStateOf("") }
            val messages = remember { mutableStateListOf<String>() }
            val selected = peers.firstOrNull { it.name == selectedName }
            // Keep the selected address current after a service is re-resolved.
            LaunchedEffect(selected) { selected?.let { host = it.host } }
            DisposableEffect(Unit) {
                messenger.onMessage = { runOnUiThread { messages.add("Собеседник: $it") } }
                onDispose { messenger.onMessage = null }
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                    Text("P2P Messenger 0.3", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !internetMode, onClick = { internetMode = false },
                            label = { Text("Локальная сеть") })
                        FilterChip(selected = internetMode, onClick = { internetMode = true },
                            label = { Text("Интернет") })
                    }
                    if (internetMode) {
                        InternetScreen(internet, Modifier.weight(1f))
                    } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Устройства в сети", Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { startDiscovery() }) { Text("Обновить") }
                    }
                    Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
                    if (peers.isEmpty()) {
                        Text("Пока никого нет. Откройте P2PMessenger 0.2 или новее на втором телефоне в той же Wi-Fi сети.",
                            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                            items(peers, key = { it.name }) { peer ->
                                OutlinedButton(onClick = {
                                    selectedName = peer.name
                                    host = peer.host
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${if (selectedName == peer.name) "✓ " else ""}${peer.name}\n${peer.host}:${peer.port}")
                                }
                            }
                        }
                    }
                    selectedName?.let { name ->
                        Text(if (selected == null) "$name больше не виден. Обновите поиск или введите IP."
                            else "Выбрано: $name", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(host, { host = it; selectedName = null },
                        label = { Text("IP второго телефона (резервный ввод)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(messages) { Text(it, Modifier.padding(vertical = 4.dp)) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedTextField(text, { text = it }, label = { Text("Сообщение") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = host.isNotBlank() && text.isNotBlank() &&
                            (selectedName == null || selected != null), onClick = {
                            val destination = selected?.host ?: host.trim()
                            if (destination.isNotBlank() && text.isNotBlank()) {
                                messenger.send(destination, text)
                                messages.add("Я: $text")
                                text = ""
                            }
                        }) { Text("Отправить") }
                    }
                    }
                }
            }
        }
    }
}
