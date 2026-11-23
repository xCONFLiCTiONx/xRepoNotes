package com.xconflictionx.xreponotes

import com.xconflictionx.xreponotes.util.AiFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFormatterTest {

    @Test
    fun testStripThinkingBlocks() {
        val input = "<think>reasoning here</think>\n**Models:**\n- llama3"
        assertEquals("**Models:**\n- llama3", AiFormatter.stripThinkingBlocks(input))
    }

    @Test
    fun testCleanMarkdownConvertsHeadersAndFencesShellCommands() {
        val input = "## Deploy\nsudo systemctl restart xreponotes"
        val cleaned = AiFormatter.cleanMarkdown(input)
        assertTrue(cleaned.contains("**Deploy:**"))
        assertTrue(cleaned.contains("```bash\nsudo systemctl restart xreponotes\n```"))
    }

    @Test
    fun testCleanMarkdownUnwrapsOuterMarkdownFence() {
        val input = "```markdown\n**Tasks:**\n- [ ] Ship Android AI\n```"
        assertEquals("**Tasks:**\n- [ ] Ship Android AI", AiFormatter.cleanMarkdown(input))
    }

    @Test
    fun testCleanMarkdownFormatsPathsAndTrailingListItems() {
        val input = "- first item\nsecond item\n\n~/.config/app/config.yaml"
        val cleaned = AiFormatter.cleanMarkdown(input)
        assertEquals("- first item\n- second item\n\n`~/.config/app/config.yaml`", cleaned)
    }
}
