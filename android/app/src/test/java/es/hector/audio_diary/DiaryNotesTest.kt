package es.hector.audio_diary
import java.time.LocalDate
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
class DiaryNotesTest {
 @Test fun utf8_note_is_atomic_per_day_and_empty_deletes() { val dir=createTempDir("notes"); val repo=DiaryNoteRepository(dir); val day=LocalDate.parse("2026-09-09"); repo.save(day,"# Día\nmañana ☕"); assertEquals("# Día\nmañana ☕",repo.load(day)); assertEquals(1,repo.snapshot().size); repo.save(day,""); assertEquals("",repo.load(day)); dir.deleteRecursively() }
 @Test fun inline_toolbar_preserves_selection_and_places_empty_cursor_inside() {
  val selected=applyMarkdownMarkup(TextFieldValue("abcd",selection=TextRange(1,3)),"**")
  assertEquals("a**bc**d",selected.text);assertEquals(TextRange(3,5),selected.selection)
  listOf("**" to "****","*" to "**","~~" to "~~~~").forEach { (mark,text) ->
   val result=applyMarkdownMarkup(TextFieldValue("",selection=TextRange(0)),mark)
   assertEquals(text,result.text);assertEquals(TextRange(mark.length),result.selection)
  }
 }
 @Test fun bullets_toggle_and_enter_continue_or_exit() {
  val added=toggleMarkdownBullet(TextFieldValue("texto",selection=TextRange(5)));assertEquals("- texto",added.text);assertEquals(TextRange(7),added.selection)
  val removed=toggleMarkdownBullet(added);assertEquals("texto",removed.text);assertEquals(TextRange(5),removed.selection)
  val continued=applyMarkdownEnter(TextFieldValue("- leche",selection=TextRange(7)),TextFieldValue("- leche\n",selection=TextRange(8)))
  assertEquals("- leche\n- ",continued.text);assertEquals(TextRange(10),continued.selection)
  val exited=applyMarkdownEnter(TextFieldValue("- ",selection=TextRange(2)),TextFieldValue("- \n",selection=TextRange(3)))
  assertEquals("\n",exited.text);assertEquals(TextRange(1),exited.selection)
 }
 @Test fun headings_replace_toggle_and_keep_logical_cursor() {
  val h1=applyMarkdownHeading(TextFieldValue("texto",selection=TextRange(3)),1);assertEquals("# texto",h1.text);assertEquals(TextRange(5),h1.selection)
  val h3=applyMarkdownHeading(h1,3);assertEquals("### texto",h3.text);assertEquals(TextRange(7),h3.selection)
  val plain=applyMarkdownHeading(h3,3);assertEquals("texto",plain.text);assertEquals(TextRange(3),plain.selection)
 }
 @Test fun indent_outdent_only_lists_and_preserve_nested_selection() {
  val value=TextFieldValue("- principal\n- [ ] secundaria\npárrafo",selection=TextRange(0,27));val indented=indentMarkdownList(value)
  assertEquals("  - principal\n  - [ ] secundaria\npárrafo",indented.text);assertEquals(value, outdentMarkdownList(indented))
 }
 @Test fun checklist_toggle_enter_and_direct_read_toggle() {
  val added=toggleMarkdownChecklist(TextFieldValue("comprar leche",selection=TextRange(13)));assertEquals("- [ ] comprar leche",added.text)
 val continued=applyMarkdownEnter(TextFieldValue("  - [x] subtask",selection=TextRange(15)),TextFieldValue("  - [x] subtask\n",selection=TextRange(16)))
  assertEquals("  - [x] subtask\n  - [ ] ",continued.text)
  val exited=applyMarkdownEnter(TextFieldValue("- [ ] ",selection=TextRange(6)),TextFieldValue("- [ ] \n",selection=TextRange(7)));assertEquals("\n",exited.text);assertEquals(TextRange(1),exited.selection)
  val checked=applyMarkdownEnter(TextFieldValue("- [x] ",selection=TextRange(6)),TextFieldValue("- [x] \n",selection=TextRange(7)));assertEquals("\n",checked.text);assertEquals(TextRange(1),checked.selection)
  val nested=applyMarkdownEnter(TextFieldValue("  - [ ] ",selection=TextRange(8)),TextFieldValue("  - [ ] \n",selection=TextRange(9)));assertEquals("  \n",nested.text);assertEquals(TextRange(3),nested.selection)
  val deeplyNested=applyMarkdownEnter(TextFieldValue("    - [x] ",selection=TextRange(10)),TextFieldValue("    - [x] \n",selection=TextRange(11)));assertEquals("    \n",deeplyNested.text);assertEquals(TextRange(5),deeplyNested.selection)
  assertEquals("- [x] uno\n- [ ] dos",toggleMarkdownCheckboxLine("- [ ] uno\n- [ ] dos",0))
 }
 @Test fun renderer_supports_required_plain_structure_including_h3() { assertEquals("Título\nSub\nTres\n• uno\nnegrita cursiva tachado",markdownPlainText("# Título\n## Sub\n### Tres\n- uno\n**negrita** *cursiva* ~~tachado~~"));assertEquals("Tres",renderMarkdown("### Tres").text) }
}
