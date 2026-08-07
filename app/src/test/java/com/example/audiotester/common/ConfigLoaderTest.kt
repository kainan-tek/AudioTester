package com.example.audiotester.common

import org.junit.Assert.assertEquals
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
}
