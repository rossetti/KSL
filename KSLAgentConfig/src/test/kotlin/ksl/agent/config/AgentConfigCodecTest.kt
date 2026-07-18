package ksl.agent.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentConfigCodecTest {

    private val spec = LaunchSpec("ksl-bridge", listOf("--url", "http://127.0.0.1:3001/"))

    @Test
    @DisplayName("Claude merge into empty creates the keyed entry with command and args")
    fun claudeMergeIntoEmpty() {
        val out = AgentConfigCodec.mergeClaudeJson(null, "ksl-suite", spec)
        assertTrue(out.contains("\"ksl-suite\""))
        assertTrue(out.contains("\"ksl-bridge\""))
        assertTrue(out.contains("--url"))
    }

    @Test
    @DisplayName("Claude merge preserves other servers and replaces the same key")
    fun claudeMergePreservesAndReplaces() {
        val existing =
            """{"mcpServers":{"other":{"command":"x","args":[]},"ksl-suite":{"command":"old","args":[]}}}"""
        val out = AgentConfigCodec.mergeClaudeJson(existing, "ksl-suite", spec)
        assertTrue(out.contains("\"other\""), "other server preserved")
        assertTrue(out.contains("ksl-bridge"), "entry replaced with new command")
        assertFalse(out.contains("\"old\""), "old command gone")
    }

    @Test
    @DisplayName("Claude remove returns null when the key is absent or input is null")
    fun claudeRemoveAbsentIsNull() {
        assertNull(
            AgentConfigCodec.removeClaudeJson(
                """{"mcpServers":{"other":{"command":"x","args":[]}}}""", "ksl-suite"
            )
        )
        assertNull(AgentConfigCodec.removeClaudeJson(null, "ksl-suite"))
    }

    @Test
    @DisplayName("Claude remove drops only the keyed entry")
    fun claudeRemoveDropsOnlyKey() {
        val existing = AgentConfigCodec.mergeClaudeJson(
            """{"mcpServers":{"other":{"command":"x","args":[]}}}""", "ksl-suite", spec
        )
        val out = AgentConfigCodec.removeClaudeJson(existing, "ksl-suite")!!
        assertTrue(out.contains("\"other\""))
        assertFalse(out.contains("ksl-suite"))
    }

    @Test
    @DisplayName("Codex merge writes the table, preserves other tables, and replaces the same key")
    fun codexMergePreservesAndReplaces() {
        val empty = AgentConfigCodec.mergeCodexToml(null, "ksl-suite", spec)
        assertTrue(empty.contains("[mcp_servers.ksl-suite]"))
        assertTrue(empty.contains("command = 'ksl-bridge'"))

        val withOther = "[mcp_servers.other]\ncommand = 'x'\nargs = []\n"
        val merged = AgentConfigCodec.mergeCodexToml(withOther, "ksl-suite", spec)
        assertTrue(merged.contains("[mcp_servers.other]"), "other table preserved")
        assertTrue(merged.contains("[mcp_servers.ksl-suite]"))

        val replaced = AgentConfigCodec.mergeCodexToml(merged, "ksl-suite", LaunchSpec("newcmd", listOf("--url", "u2")))
        assertTrue(replaced.contains("command = 'newcmd'"), "replaced with new command")
        assertFalse(replaced.contains("ksl-bridge"))
        assertTrue(replaced.contains("[mcp_servers.other]"), "other table still preserved after replace")
    }

    @Test
    @DisplayName("Codex remove drops the table when present, returns null when absent")
    fun codexRemove() {
        assertNull(AgentConfigCodec.removeCodexToml("[mcp_servers.other]\ncommand = 'x'\n", "ksl-suite"))
        val merged = AgentConfigCodec.mergeCodexToml("[mcp_servers.other]\ncommand = 'x'\nargs = []\n", "ksl-suite", spec)
        val out = AgentConfigCodec.removeCodexToml(merged, "ksl-suite")!!
        assertTrue(out.contains("[mcp_servers.other]"))
        assertFalse(out.contains("ksl-suite"))
    }

    @Test
    @DisplayName("the entry key is parameterized (works for the plain ksl key too)")
    fun entryKeyIsParameterized() {
        assertTrue(AgentConfigCodec.mergeClaudeJson(null, "ksl", spec).contains("\"ksl\""))
        assertTrue(AgentConfigCodec.mergeCodexToml(null, "ksl", spec).contains("[mcp_servers.ksl]"))
    }
}
