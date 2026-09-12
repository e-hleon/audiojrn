package app.audiojrn
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
@Serializable data class DiaryNote(val day: String, val markdown: String)
class DiaryNoteRepository(filesDir: File) {
    private val directory = File(filesDir, "diary-notes").apply { mkdirs() }
    private fun file(day: LocalDate) = File(directory, "$day.md")
    fun load(day: LocalDate) = file(day).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
    fun save(day: LocalDate, text: String) { val destination = file(day); if (text.isEmpty()) { destination.delete(); return }; val temporary = File(directory, ".$day.md.tmp"); temporary.writeText(text, Charsets.UTF_8); check(temporary.renameTo(destination)) }
    fun snapshot() = directory.listFiles { item -> item.extension == "md" }.orEmpty().mapNotNull { item -> runCatching { LocalDate.parse(item.nameWithoutExtension) }.getOrNull()?.let { DiaryNote(it.toString(), item.readText(Charsets.UTF_8)) } }.sortedBy { it.day }
}
fun applyMarkdownMarkup(value: TextFieldValue, prefix: String, suffix: String = prefix): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
    val alreadyWrapped = start >= prefix.length && end + suffix.length <= value.text.length &&
        value.text.substring(start - prefix.length, start) == prefix &&
        value.text.substring(end, end + suffix.length) == suffix
    if (alreadyWrapped) {
        val text = value.text.removeRange(end, end + suffix.length).removeRange(start - prefix.length, start)
        return value.copy(text = text, selection = TextRange(start - prefix.length, end - prefix.length))
    }
    val text = value.text.substring(0, start) + prefix + selected + suffix + value.text.substring(end)
    val selection = if (start == end) TextRange(start + prefix.length)
        else TextRange(start + prefix.length, end + prefix.length)
    return value.copy(text = text, selection = selection)
}

private fun transformLinePrefixes(value: TextFieldValue, transform: (String) -> String): TextFieldValue {
    val text = value.text
    val first = if (value.selection.min == 0) 0 else text.lastIndexOf('\n', value.selection.min - 1).let { if (it < 0) 0 else it + 1 }
    val lastSelectionPosition = if (value.selection.max > value.selection.min) value.selection.max - 1 else value.selection.max
    val last = text.indexOf('\n', lastSelectionPosition.coerceIn(0, text.length)).let { if (it < 0) text.length else it }
    val original = text.substring(first, last)
    val originalLines = original.split("\n")
    val changedLines = originalLines.map(transform)
    fun mapped(position: Int): Int {
        val relative = (position - first).coerceIn(0, original.length)
        var oldOffset = 0
        var newOffset = 0
        originalLines.zip(changedLines).forEachIndexed { index, (old, new) ->
            val oldEnd = oldOffset + old.length
            if (relative <= oldEnd) {
                val prefixDelta = new.length - old.length
                return first + newOffset + (relative - oldOffset + prefixDelta).coerceIn(0, new.length)
            }
            oldOffset = oldEnd + 1
            newOffset += new.length + if (index < changedLines.lastIndex) 1 else 0
        }
        return first + changedLines.joinToString("\n").length
    }
    val replacement = changedLines.joinToString("\n")
    return value.copy(
        text = text.replaceRange(first, last, replacement),
        selection = TextRange(mapped(value.selection.start), mapped(value.selection.end)),
    )
}

fun toggleMarkdownBullet(value: TextFieldValue): TextFieldValue {
    val selectedText = value.text.substring((if (value.selection.min == 0) 0 else value.text.lastIndexOf('\n', value.selection.min - 1).let { if (it < 0) 0 else it + 1 }),
        value.text.indexOf('\n', (if (value.selection.collapsed) value.selection.max else value.selection.max - 1).coerceIn(0, value.text.length)).let { if (it < 0) value.text.length else it })
    val remove = selectedText.split("\n").all { it.startsWith("- ") }
    return transformLinePrefixes(value) { line -> if (remove) line.removePrefix("- ") else if (line.startsWith("- ")) line else "- $line" }
}

private val markdownListLine = Regex("^( *)(- )(?:\\[[ xX]\\] )?.*")
private val markdownCheckboxPrefix = Regex("^( *)(- )\\[([ xX])\\] ")

fun indentMarkdownList(value: TextFieldValue): TextFieldValue = transformLinePrefixes(value) { line ->
    if (markdownListLine.matches(line)) "  $line" else line
}

fun outdentMarkdownList(value: TextFieldValue): TextFieldValue = transformLinePrefixes(value) { line ->
    if (markdownListLine.matches(line)) line.drop(minOf(2, line.takeWhile { it == ' ' }.length)) else line
}

fun toggleMarkdownChecklist(value: TextFieldValue): TextFieldValue {
    val selectedStart = if (value.selection.min == 0) 0 else value.text.lastIndexOf('\n', value.selection.min - 1).let { if (it < 0) 0 else it + 1 }
    val selectedEnd = value.text.indexOf('\n', (if (value.selection.collapsed) value.selection.max else value.selection.max - 1).coerceIn(0, value.text.length)).let { if (it < 0) value.text.length else it }
    val lines = value.text.substring(selectedStart, selectedEnd).split("\n")
    val remove = lines.all { markdownCheckboxPrefix.containsMatchIn(it) }
    return transformLinePrefixes(value) { line ->
        val indent = line.takeWhile { it == ' ' }
        val body = line.drop(indent.length)
        if (remove) body.replaceFirst(Regex("^- \\[[ xX]\\] "), "").let { indent + it }
        else when {
            markdownCheckboxPrefix.containsMatchIn(line) -> line
            body.startsWith("- ") -> "$indent- [ ] ${body.drop(2)}"
            else -> "$indent- [ ] $body"
        }
    }
}

fun toggleMarkdownCheckboxLine(markdown: String, lineIndex: Int): String = markdown.lines().mapIndexed { index, line ->
    if (index != lineIndex) line else markdownCheckboxPrefix.find(line)?.let { match ->
        val checked = match.groupValues[3].equals("x", ignoreCase = true)
        line.replaceRange(match.range, "${match.groupValues[1]}- [${if (checked) " " else "x"}] ")
    } ?: line
}.joinToString("\n")

fun applyMarkdownHeading(value: TextFieldValue, level: Int): TextFieldValue {
    require(level in 1..3)
    val wanted = "#".repeat(level) + " "
    return transformLinePrefixes(value) { line ->
        val existing = Regex("^#{1,3}\\s+").find(line)?.value
        when {
            existing == wanted -> line.removePrefix(existing)
            existing != null -> wanted + line.removePrefix(existing)
            else -> wanted + line
        }
    }
}

fun applyMarkdownEnter(previous: TextFieldValue, proposed: TextFieldValue): TextFieldValue {
    if (!previous.selection.collapsed || proposed.text.length != previous.text.length + 1) return proposed
    val cursor = previous.selection.start
    if (proposed.text.getOrNull(cursor) != '\n') return proposed
    val lineStart = if (cursor == 0) 0 else previous.text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
    val beforeCursor = previous.text.substring(lineStart, cursor)
    val checkbox = Regex("^( *)(- )\\[([ xX])\\] (.*)$").matchEntire(beforeCursor)
    return when {
        checkbox != null && checkbox.groupValues[4].isEmpty() -> {
            val indentation = checkbox.groupValues[1]
            proposed.copy(
                text = proposed.text.removeRange(lineStart + indentation.length, cursor),
                selection = TextRange(lineStart + indentation.length + 1),
            )
        }
        checkbox != null -> {
            val prefix = "${checkbox.groupValues[1]}- [ ] "
            proposed.copy(text = proposed.text.substring(0, cursor + 1) + prefix + proposed.text.substring(cursor + 1), selection = TextRange(cursor + 1 + prefix.length))
        }
        beforeCursor == "- " -> proposed.copy(
            text = proposed.text.removeRange(lineStart, lineStart + 2),
            selection = TextRange((cursor - 1).coerceAtLeast(lineStart)),
        )
        beforeCursor.startsWith("- ") -> proposed.copy(
            text = proposed.text.substring(0, cursor + 1) + "- " + proposed.text.substring(cursor + 1),
            selection = TextRange(cursor + 3),
        )
        else -> proposed
    }
}

fun markdownPlainText(markdown: String) = markdown.lines().joinToString("\n") { it.replace(Regex("^#{1,3}\\s+"), "").replace(Regex("^( *)- \\[[ xX]\\] "), "${'$'}1").replace(Regex("^( *)-\\s+"), "${'$'}1• ").replace("**", "").replace("~~", "").replace("*", "") }
fun renderMarkdown(markdown: String): AnnotatedString = buildAnnotatedString {
    markdown.lines().forEachIndexed { lineIndex, source ->
        val indent = source.takeWhile { it == ' ' }.length
        var line = source.drop(indent); val heading = Regex("^(#{1,3})\\s+").find(line)
        if (heading != null) line = line.removeRange(heading.range)
        val checkbox = Regex("^- \\[[ xX]\\] ").find(line)
        if (checkbox != null) line = line.removeRange(checkbox.range)
        if (line.startsWith("- ")) line = "• " + line.drop(2)
        val start = length; append(" ".repeat(indent * 2)); val contentStart = length
        val plain = line.replace("**", "").replace("~~", "").replace("*", ""); append(plain)
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = when (heading.groupValues[1].length) { 1 -> 22.sp; 2 -> 18.sp; else -> 16.sp }), contentStart, length)
        listOf("**" to SpanStyle(fontWeight = FontWeight.Bold), "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough), "*" to SpanStyle(fontStyle = FontStyle.Italic)).forEach { (mark, style) ->
            Regex(Regex.escape(mark) + "(.+?)" + Regex.escape(mark)).findAll(line).forEach { match ->
                val before = line.substring(0, match.range.first).replace("**", "").replace("~~", "").replace("*", "").length
                val content = match.groupValues[1].replace("**", "").replace("~~", "").replace("*", "")
                addStyle(style, contentStart + before, contentStart + before + content.length)
            }
        }
        if (lineIndex < markdown.lines().lastIndex) append('\n')
    }
}
