package com.example.audiotester.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigLoaderTest {

    @Test
    fun stripComments_removesLineComments() {
        val input = """
            {
              // line comment
              "key": "value"
            }
        """.trimIndent()
        val result = ConfigLoader.stripComments(input)
        assert(!result.contains("//"))
        assert(result.contains("\"key\": \"value\""))
    }

    @Test
    fun stripComments_removesBlockComments() {
        val input = """{ /* block comment */ "key": "value" }"""
        val result = ConfigLoader.stripComments(input)
        assert(!result.contains("/*"))
        assert(!result.contains("block"))
        assert(result.contains("\"key\": \"value\""))
    }

    @Test
    fun stripComments_preservesSlashInsideString() {
        val input = """{ "path": "asset://sample/48k_2ch_16bit.wav" }"""
        assertEquals(input, ConfigLoader.stripComments(input))
    }

    @Test
    fun stripComments_parsesToValidJson() {
        val jsonc = """
            {
              // section a
              "player": [ { "a": 1 } ],
              /* section b */
              "recorder": []
            }
        """.trimIndent()
        val stripped = ConfigLoader.stripComments(jsonc)
        org.json.JSONObject(stripped)  // 不抛异常
    }

    @Test
    fun stripComments_preservesBlockMarkerInsideString() {
        val input = """{ "a": "x/*y" }"""
        assertEquals(input, ConfigLoader.stripComments(input))
    }

    @Test
    fun stripComments_escapedQuoteThenLineComment() {
        val input = "{ \"a\": \"a\\\"b\" // comment\n}"
        val result = ConfigLoader.stripComments(input)
        assertTrue(result.contains("\"a\\\"b\""))
        assertTrue(!result.contains("// comment"))
        assertTrue(result.endsWith("}"))
    }

    @Test
    fun stripComments_unterminatedBlockComment_noCrash() {
        val result = ConfigLoader.stripComments("{ \"a\": 1 /* unterminated")
        assertTrue(result.contains("\"a\": 1"))
    }
}
