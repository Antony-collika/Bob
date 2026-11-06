package com.bob.ai.data.tools

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bob.ai.domain.SettingsRepository
import com.bob.ai.domain.ToolExecutionResult
import com.bob.ai.domain.ToolUids
import com.bob.ai.domain.int
import com.bob.ai.domain.requireArgs
import com.bob.ai.domain.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow

private val Context.dailyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bob_daily"
)

/**
 * Daily tools (§13). No clipboard-read tool by design; clipboard-write is kept.
 * Notes and to-dos are persisted in DataStore Preferences ("bob_daily") as JSON.
 */
class DailyToolsExecutor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dataStore: DataStore<Preferences> get() = context.dailyDataStore
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun handles(uid: String): Boolean = uid.startsWith("daily.")

    suspend fun execute(uid: String, args: JsonObject): ToolExecutionResult {
        return try {
            when (uid) {
                ToolUids.DAILY_CALC -> calc(args)
                ToolUids.DAILY_NOW -> now()
                ToolUids.DAILY_DAYS_BETWEEN -> daysBetween(args)
                ToolUids.DAILY_FETCH_URL -> fetchUrl(args)
                ToolUids.DAILY_WEB_SEARCH -> webSearch(args)
                ToolUids.DAILY_NOTE_ADD -> noteAdd(args)
                ToolUids.DAILY_NOTE_LIST -> noteList()
                ToolUids.DAILY_TODO_ADD -> todoAdd(args)
                ToolUids.DAILY_TODO_LIST -> todoList()
                ToolUids.DAILY_TODO_DONE -> todoDone(args)
                ToolUids.DAILY_ALARM -> alarm(args)
                ToolUids.DAILY_CALENDAR_EVENT -> calendarEvent(args)
                ToolUids.DAILY_CLIPBOARD_WRITE -> clipboardWrite(args)
                else -> ToolExecutionResult(false, "", "Không có công cụ với UID: $uid")
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "", e.message ?: "Đã xảy ra lỗi khi chạy công cụ.")
        }
    }

    // -- Calculator (§13): own evaluator, no script engine -------------------

    private fun calc(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "expression")?.let { return ToolExecutionResult(false, "", it) }
        val expression = args.string("expression")
        val value = try {
            CalcParser(expression).parse()
        } catch (e: IllegalArgumentException) {
            return ToolExecutionResult(false, "", "Biểu thức không hợp lệ: ${e.message}")
        } catch (e: ArithmeticException) {
            return ToolExecutionResult(false, "", "Lỗi tính toán: ${e.message}")
        }
        return ToolExecutionResult(true, "$expression = ${formatNumber(value)}")
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val asLong = value.toLong()
        if (value == asLong.toDouble() && asLong in -9_000_000_000_000_000L..9_000_000_000_000_000L) {
            return asLong.toString()
        }
        return value.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    /** Recursive-descent parser: + - * / % ^, parentheses, decimals,
     *  unary minus, functions sqrt/min/max. ^ is right-associative. */
    private class CalcParser(private val input: String) {
        private var pos = 0

        fun parse(): Double {
            val value = parseExpression()
            skipWhitespace()
            if (pos < input.length) throw IllegalArgumentException("ký tự không hợp lệ '${input[pos]}'")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when {
                    consume('+') -> value += parseTerm()
                    consume('-') -> value -= parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                when {
                    consume('*') -> value *= parseFactor()
                    consume('/') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("không thể chia cho 0")
                        value /= divisor
                    }
                    consume('%') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("không thể chia cho 0")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            var base = parseUnary()
            skipWhitespace()
            if (consume('^')) {
                // Right-associative: 2^3^2 = 2^(3^2)
                val exponent = parseFactor()
                base = base.pow(exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            return when {
                consume('-') -> -parseUnary()
                consume('+') -> parseUnary()
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("biểu thức chưa hoàn chỉnh")
            val ch = input[pos]
            return when {
                ch == '(' -> {
                    pos++
                    val value = parseExpression()
                    skipWhitespace()
                    if (!consume(')')) throw IllegalArgumentException("thiếu dấu đóng ngoặc ')'")
                    value
                }
                ch.isLetter() -> parseFunction()
                ch.isDigit() || ch == '.' -> parseNumber()
                else -> throw IllegalArgumentException("ký tự không hợp lệ '$ch'")
            }
        }

        private fun parseFunction(): Double {
            val start = pos
            while (pos < input.length && input[pos].isLetter()) pos++
            val name = input.substring(start, pos).lowercase(Locale.ROOT)
            skipWhitespace()
            if (!consume('(')) throw IllegalArgumentException("sau hàm $name phải có '('")
            val args = mutableListOf<Double>()
            skipWhitespace()
            if (!consume(')')) {
                while (true) {
                    args += parseExpression()
                    skipWhitespace()
                    if (consume(',')) continue
                    if (!consume(')')) throw IllegalArgumentException("thiếu dấu đóng ngoặc ')' sau hàm $name")
                    break
                }
            }
            return when (name) {
                "sqrt" -> {
                    if (args.size != 1) throw IllegalArgumentException("sqrt cần đúng 1 tham số")
                    if (args[0] < 0) throw ArithmeticException("không thể tính căn bậc hai của số âm")
                    kotlin.math.sqrt(args[0])
                }
                "min" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("min cần ít nhất 1 tham số")
                    args.min()
                }
                "max" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("max cần ít nhất 1 tham số")
                    args.max()
                }
                else -> throw IllegalArgumentException("không hỗ trợ hàm $name")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            var seenDot = false
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                if (input[pos] == '.') {
                    if (seenDot) break
                    seenDot = true
                }
                pos++
            }
            return input.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("số không hợp lệ")
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun consume(c: Char): Boolean {
            if (pos < input.length && input[pos] == c) {
                pos++
                return true
            }
            return false
        }
    }

    // -- Date / time ----------------------------------------------------------

    private fun now(): ToolExecutionResult {
        val now = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ofPattern(
            "EEEE, dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("vi")
        )
        val zone = ZoneId.systemDefault()
        return ToolExecutionResult(
            true,
            "Bây giờ là ${now.format(formatter)} (múi giờ $zone)."
        )
    }

    private fun daysBetween(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "from", "to")?.let { return ToolExecutionResult(false, "", it) }
        val fromText = args.string("from")
        val toText = args.string("to")
        val from = runCatching { LocalDate.parse(fromText) }.getOrNull()
            ?: return ToolExecutionResult(false, "", "Ngày bắt đầu không hợp lệ, dùng định dạng yyyy-MM-dd (ví dụ 2026-11-06).")
        val to = runCatching { LocalDate.parse(toText) }.getOrNull()
            ?: return ToolExecutionResult(false, "", "Ngày kết thúc không hợp lệ, dùng định dạng yyyy-MM-dd (ví dụ 2026-11-07).")
        val days = ChronoUnit.DAYS.between(from, to)
        val earlier = if (from <= to) fromText else toText
        val later = if (from <= to) toText else fromText
        val direction = when {
            days > 0 -> "ngày $toText đến sau ngày $fromText"
            days < 0 -> "ngày $toText đến trước ngày $fromText"
            else -> "hai ngày trùng nhau"
        }
        return ToolExecutionResult(
            true,
            "Từ $fromText đến $toText: ${kotlin.math.abs(days)} ngày ($direction). " +
                "Ngày sớm hơn là $earlier, ngày muộn hơn là $later."
        )
    }

    // -- Web ------------------------------------------------------------------

    private suspend fun fetchUrl(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "url")?.let { return ToolExecutionResult(false, "", it) }
        val url = args.string("url")
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Bob/1.0 (Android)")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Trang web trả về mã ${response.code}")
                }
                response.body?.string() ?: ""
            }
        }
        val text = htmlToText(body)
        val truncated = if (text.length > 8000) {
            text.take(8000) + "\n\n…(đã cắt ngắn, nội dung gốc dài hơn 8000 ký tự)"
        } else text
        if (truncated.isBlank()) {
            return ToolExecutionResult(false, "", "Không đọc được nội dung văn bản từ $url")
        }
        return ToolExecutionResult(true, "Nội dung trang $url:\n\n$truncated")
    }

    private fun htmlToText(html: String): String {
        var text = html
        text = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(text, " ")
        text = Regex("(?i)<br[^>]*>").replace(text, "\n")
        text = Regex("(?i)</(p|div|h[1-6]|li|tr|section|article)>").replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        // Numeric character references such as &#233;
        text = Regex("&#(\\d+);").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            runCatching { code.toChar().toString() }.getOrElse { match.value }
        }
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("[ \\t]+"), " ")
    }

    private suspend fun webSearch(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "query")?.let { return ToolExecutionResult(false, "", it) }
        val apiKey = settingsRepository.observeSettings().first().searchApiKey
        if (apiKey.isBlank()) {
            return ToolExecutionResult(
                false, "",
                "Chưa có API key tìm kiếm. Vui lòng thêm API key tìm kiếm (Tavily) trong phần Cài đặt rồi thử lại."
            )
        }
        val query = args.string("query")
        val payload = """{"api_key":${json.encodeToString(apiKey)},"query":${json.encodeToString(query)},"max_results":5}"""
        val responseBody = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Dịch vụ tìm kiếm trả về mã ${response.code}")
                }
                response.body?.string() ?: ""
            }
        }
        val results = runCatching {
            json.parseToJsonElement(responseBody).jsonObject["results"]?.jsonArray
        }.getOrNull().orEmpty()
        if (results.isEmpty()) {
            return ToolExecutionResult(true, "Không tìm thấy kết quả nào cho \"$query\".")
        }
        val lines = results.mapIndexed { index, element ->
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: ""
            val url = obj["url"]?.jsonPrimitive?.content ?: ""
            val snippet = obj["content"]?.jsonPrimitive?.content ?: ""
            "${index + 1}. $title\n   $url\n   $snippet"
        }
        return ToolExecutionResult(
            true,
            "Kết quả tìm kiếm cho \"$query\":\n\n" + lines.joinToString("\n\n")
        )
    }

    // -- Notes / to-dos (persisted in DataStore "bob_daily") ------------------

    @Serializable
    private data class NoteItem(
        val id: String,
        val text: String,
        val createdAt: Long
    )

    @Serializable
    private data class TodoItem(
        val id: String,
        val title: String,
        val due: String = "",
        val done: Boolean = false,
        val createdAt: Long
    )

    @Serializable
    private data class DailyStore(
        val notes: List<NoteItem> = emptyList(),
        val todos: List<TodoItem> = emptyList()
    )

    private suspend fun loadStore(): DailyStore {
        val raw = dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[Keys.STORE_JSON]
        if (raw.isNullOrBlank()) return DailyStore()
        return runCatching { json.decodeFromString<DailyStore>(raw) }
            .getOrElse { DailyStore() }
    }

    private suspend fun saveStore(store: DailyStore) {
        dataStore.edit { prefs -> prefs[Keys.STORE_JSON] = json.encodeToString(store) }
    }

    private fun formatDateTime(millis: Long): String {
        val dateTime = Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }

    private suspend fun noteAdd(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "text")?.let { return ToolExecutionResult(false, "", it) }
        val store = loadStore()
        val note = NoteItem(
            id = UUID.randomUUID().toString(),
            text = args.string("text"),
            createdAt = System.currentTimeMillis()
        )
        saveStore(store.copy(notes = store.notes + note))
        return ToolExecutionResult(true, "Đã lưu ghi chú: ${note.text}")
    }

    private suspend fun noteList(): ToolExecutionResult {
        val notes = loadStore().notes
        if (notes.isEmpty()) return ToolExecutionResult(true, "Chưa có ghi chú nào.")
        val lines = notes.mapIndexed { index, note ->
            "${index + 1}. ${note.text} (${formatDateTime(note.createdAt)})"
        }
        return ToolExecutionResult(true, "Ghi chú (${notes.size}):\n" + lines.joinToString("\n"))
    }

    private suspend fun todoAdd(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "title")?.let { return ToolExecutionResult(false, "", it) }
        val store = loadStore()
        val todo = TodoItem(
            id = UUID.randomUUID().toString(),
            title = args.string("title"),
            due = args.string("due"),
            createdAt = System.currentTimeMillis()
        )
        saveStore(store.copy(todos = store.todos + todo))
        val dueText = if (todo.due.isNotBlank()) " (hạn: ${todo.due})" else ""
        return ToolExecutionResult(true, "Đã thêm việc cần làm: ${todo.title}$dueText")
    }

    private suspend fun todoList(): ToolExecutionResult {
        val todos = loadStore().todos
        if (todos.isEmpty()) return ToolExecutionResult(true, "Danh sách việc cần làm đang trống.")
        val lines = todos.mapIndexed { index, todo ->
            val box = if (todo.done) "[x]" else "[ ]"
            val dueText = if (todo.due.isNotBlank()) " — hạn: ${todo.due}" else ""
            "${index + 1}. $box ${todo.title}$dueText (id: ${todo.id})"
        }
        return ToolExecutionResult(true, "Việc cần làm (${todos.size}):\n" + lines.joinToString("\n"))
    }

    private suspend fun todoDone(args: JsonObject): ToolExecutionResult {
        val store = loadStore()
        if (store.todos.isEmpty()) {
            return ToolExecutionResult(false, "", "Danh sách việc cần làm đang trống.")
        }
        val id = args.string("id")
        val index = args.int("index", -1)
        val targetIndex = when {
            id.isNotBlank() -> store.todos.indexOfFirst { it.id == id }
            index > 0 -> index - 1 // index shown in todo_list is 1-based
            else -> -1
        }
        if (targetIndex !in store.todos.indices) {
            return ToolExecutionResult(
                false, "",
                "Không tìm thấy việc cần làm. Dùng id trong kết quả của todo_list, hoặc số thứ tự trong danh sách."
            )
        }
        val todo = store.todos[targetIndex]
        if (todo.done) return ToolExecutionResult(true, "Việc \"${todo.title}\" đã được đánh dấu xong từ trước.")
        val updated = store.todos.toMutableList()
        updated[targetIndex] = todo.copy(done = true)
        saveStore(store.copy(todos = updated))
        return ToolExecutionResult(true, "Đã đánh dấu xong: ${todo.title}")
    }

    // -- Alarm -----------------------------------------------------------------

    private fun alarm(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "hour", "minute")?.let { return ToolExecutionResult(false, "", it) }
        val hour = args.int("hour", -1)
        val minute = args.int("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult(false, "", "Giờ báo thức không hợp lệ: giờ 0-23, phút 0-59.")
        }
        val label = args.string("label").ifBlank { "Báo thức" }
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.bob.ai.ALARM"
            putExtra("label", label)
        }
        val requestCode = ("$hour:$minute:$label").hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent
            )
        } else {
            // Exact-alarm permission missing: fall back to an inexact alarm.
            alarmManager.set(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
        }
        val timeText = "%02d:%02d".format(hour, minute)
        val dayText = if (trigger.get(Calendar.DAY_OF_YEAR) ==
            Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        ) "hôm nay" else "ngày mai"
        val note = if (!canExact) {
            " Lưu ý: app chưa được cấp quyền báo thức chính xác nên báo thức có thể reo muộn hơn giờ đã đặt."
        } else ""
        return ToolExecutionResult(
            true,
            "Đã đặt báo thức \"$label\" lúc $timeText $dayText.$note"
        )
    }

    // -- Calendar ---------------------------------------------------------------

    private fun calendarEvent(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "title", "startIso", "endIso")?.let {
            return ToolExecutionResult(false, "", it)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolExecutionResult(
                false, "",
                "Chưa được cấp quyền truy cập lịch. Vui lòng cấp quyền Lịch trong Cài đặt của điện thoại rồi thử lại."
            )
        }
        val startMillis = parseIsoToMillis(args.string("startIso"))
            ?: return ToolExecutionResult(false, "", "Thời gian bắt đầu không hợp lệ, dùng định dạng ISO (ví dụ 2026-11-07T09:00:00+07:00).")
        val endMillis = parseIsoToMillis(args.string("endIso"))
            ?: return ToolExecutionResult(false, "", "Thời gian kết thúc không hợp lệ, dùng định dạng ISO (ví dụ 2026-11-07T10:00:00+07:00).")
        if (endMillis <= startMillis) {
            return ToolExecutionResult(false, "", "Thời gian kết thúc phải sau thời gian bắt đầu.")
        }
        val calendarId = findPrimaryCalendarId()
            ?: return ToolExecutionResult(false, "", "Không tìm thấy lịch nào trên thiết bị.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, args.string("title"))
            put(CalendarContract.Events.DESCRIPTION, args.string("description"))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolExecutionResult(false, "", "Không tạo được sự kiện lịch.")
        return ToolExecutionResult(
            true,
            "Đã tạo sự kiện \"${args.string("title")}\" trong lịch " +
                "từ ${formatDateTime(startMillis)} đến ${formatDateTime(endMillis)}."
        )
    }

    private fun findPrimaryCalendarId(): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = context.contentResolver.query(
            uri, projection,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null
        ) ?: return null
        cursor.use {
            var firstId: Long? = null
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                if (firstId == null) firstId = id
                if (primaryIndex >= 0 && it.getInt(primaryIndex) == 1) return id
            }
            return firstId
        }
    }

    /** Accepts ISO instants, ISO offsets, and local date-times (device zone). */
    private fun parseIsoToMillis(text: String): Long? {
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }
        return null
    }

    // -- Clipboard (write only) -------------------------------------------------

    private fun clipboardWrite(args: JsonObject): ToolExecutionResult {
        requireArgs(args, "text")?.let { return ToolExecutionResult(false, "", it) }
        val text = args.string("text")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bob", text))
        return ToolExecutionResult(true, "Đã sao chép vào clipboard: $text")
    }

    private object Keys {
        val STORE_JSON = stringPreferencesKey("daily_store_json")
    }
}
