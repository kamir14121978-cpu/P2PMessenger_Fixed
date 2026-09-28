package kz.p2pmessenger.internet

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun InternetScreen(model: InternetViewModel, modifier: Modifier = Modifier) {
    val state = model.state
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { model.acceptQr(it) } // Cancellation keeps the current session intact.
    }
    fun launchScanner() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(QrCaptureActivity::class.java).setOrientationLocked(false)
            .setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setPrompt("Наведите камеру на QR второго телефона"))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        if (granted) launchScanner()
    }
    val frames = remember(state.output) {
        runCatching { if (state.output.isBlank()) emptyList() else QrSignal.frames(state.output) }
    }
    var page by remember(state.output) { mutableIntStateOf(0) }
    val qrFrames = frames.getOrDefault(emptyList())
    val status = when (state.phase) {
        Phase.IDLE -> "Создайте подключение или сканируйте QR второго телефона."
        Phase.GATHERING_OFFER, Phase.GATHERING_ANSWER -> "Подготовка QR…"
        Phase.WAITING_ANSWER -> "Покажите QR второму телефону, затем сканируйте его ответный QR."
        Phase.CONNECTED -> "Подключено"
        Phase.CONNECTING -> if (state.output.startsWith("P2PM1.answer."))
            "Покажите ответный QR первому телефону." else "Устанавливается соединение…"
        Phase.DISCONNECTED -> "Связь прервана. Ожидание восстановления…"
        Phase.FAILED -> "Не удалось подключиться. Можно повторить попытку."
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Интернет • WebRTC", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.titleMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(onClick = { model.resetQr(); model.session.createOffer() }, enabled = state.canStart,
                modifier = Modifier.fillMaxWidth()) { Text("Создать подключение") }
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraDenied = false
                    launchScanner()
                } else permission.launch(Manifest.permission.CAMERA)
            }, enabled = state.canStart || state.phase == Phase.WAITING_ANSWER,
                modifier = Modifier.fillMaxWidth()) { Text("Сканировать QR") }
            if (cameraDenied) {
                Text("Для сканирования нужен доступ к камере. Если запрос больше не появляется, разрешите доступ в настройках приложения.")
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("Настройки разрешения камеры") }
            }
            if (model.qrProgress.isNotEmpty()) {
                Text(model.qrProgress)
                TextButton(onClick = { model.resetQr() }) { Text("Сбросить сбор QR") }
            }
        }
        if (state.phase != Phase.CONNECTED && qrFrames.isNotEmpty()) {
            item {
                Text(if (state.output.startsWith("P2PM1.answer.")) "Ответный QR" else "QR подключения")
                QrImage(qrFrames[page])
                if (qrFrames.size > 1) {
                    Text("QR ${page + 1} из ${qrFrames.size}. На втором телефоне сканируйте все части; после каждой нажимайте «Сканировать QR» снова.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("Назад") }
                        OutlinedButton(onClick = { page++ }, enabled = page < qrFrames.lastIndex) { Text("Следующий QR") }
                    }
                }
            }
        }
        frames.exceptionOrNull()?.let {
            item { Text("Не удалось подготовить QR. Используйте резервное ручное подключение.", color = MaterialTheme.colorScheme.error) }
        }
        item {
            TextButton(onClick = { model.resetQr(); model.session.disconnect(); model.input = "" },
                enabled = state.phase != Phase.IDLE) { Text("Завершить подключение") }
            TextButton(onClick = { diagnostic = !diagnostic }) {
                Text(if (diagnostic) "Скрыть диагностику" else "Диагностика: ручной offer/answer")
            }
        }
        if (diagnostic) {
            item {
                Text("Резервное подключение без камеры", style = MaterialTheme.typography.titleSmall)
                Text(state.status, style = MaterialTheme.typography.bodySmall)
                if (state.output.isNotEmpty()) {
                    Text("Данные готовы • ${state.output.length} символов")
                    OutlinedButton(onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("P2PMessenger", state.output))
                        Toast.makeText(context, "Данные скопированы целиком", Toast.LENGTH_SHORT).show()
                    }) { Text("Копировать") }
                }
                OutlinedTextField(value = model.input, onValueChange = {
                    if (it.length <= ManualSignal.MAX_INPUT_CHARS) model.input = it
                    else model.session.reportError("Данные подключения слишком длинные.")
                }, label = { Text("Offer / answer второго телефона") },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (value.isNullOrBlank()) model.session.reportError("В буфере обмена нет текста.")
                        else if (value.length > ManualSignal.MAX_INPUT_CHARS) model.session.reportError("Данные подключения слишком длинные.")
                        else model.input = value
                    }) { Text("Вставить") }
                    Button(onClick = { model.resetQr(); model.session.connect(model.input) },
                        enabled = model.input.isNotBlank() && (state.canStart || state.phase == Phase.WAITING_ANSWER)) {
                        Text("Подключиться")
                    }
                }
            }
        }
        item { Text("Сообщения", style = MaterialTheme.typography.titleMedium) }
        items(state.messages) { Text(it) }
        item {
            OutlinedTextField(model.draft, {
                if (it.length <= InternetSession.MAX_MESSAGE_BYTES) model.draft = it
            }, label = { Text("Сообщение • до 16 КиБ") },
                minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (model.session.send(model.draft)) model.draft = "" },
                enabled = state.phase == Phase.CONNECTED && model.draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Отправить") }
            Text("Без TURN некоторые сети не допускают прямое соединение. При смене сети создайте новое подключение.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QrImage(content: String) {
    val bitmap = remember(content) {
        runCatching {
            val matrix = QrCode.matrix(content)
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    bitmap.getOrNull()?.let {
        Image(it, contentDescription = "QR для подключения второго телефона",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
    } ?: Text("QR не удалось показать. Используйте ручную диагностику.", color = MaterialTheme.colorScheme.error)
}
