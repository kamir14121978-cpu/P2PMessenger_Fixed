package kz.p2pmessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val messenger = LanMessenger()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        messenger.start()
        setContent {
            var host by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            val messages = remember { mutableStateListOf<String>() }
            DisposableEffect(Unit) {
                messenger.onMessage = { runOnUiThread { messages.add("Собеседник: $it") } }
                onDispose { messenger.onMessage = null }
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("P2P Messenger 0.1", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(host, { host = it }, label = { Text("IP второго телефона") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) { items(messages) { Text(it, Modifier.padding(vertical = 4.dp)) } }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedTextField(text, { text = it }, label = { Text("Сообщение") }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { if (host.isNotBlank() && text.isNotBlank()) { messenger.send(host, text); messages.add("Я: $text"); text = "" } }) { Text("Отправить") }
                    }
                }
            }
        }
    }
}
