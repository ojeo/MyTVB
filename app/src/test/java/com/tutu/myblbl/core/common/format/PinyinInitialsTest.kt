package com.tutu.myblbl.core.common.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinInitialsTest {

    @Test
    fun `中文昵称取拼音首字母`() {
        assertEquals('Z', PinyinInitials.initialOf("张三"))
        assertEquals('A', PinyinInitials.initialOf("阿婆主"))
        assertEquals('B', PinyinInitials.initialOf("百度官方"))
        assertEquals('L', PinyinInitials.initialOf("罗翔说刑法"))
    }

    @Test
    fun `ASCII字母开头不区分大小写`() {
        assertEquals('A', PinyinInitials.initialOf("Alice"))
        assertEquals('A', PinyinInitials.initialOf("alice"))
        assertEquals('B', PinyinInitials.initialOf("bilibili"))
    }

    @Test
    fun `数字或符号开头归井号`() {
        assertEquals('#', PinyinInitials.initialOf("123abc"))
        assertEquals('#', PinyinInitials.initialOf("@nick"))
        assertEquals('#', PinyinInitials.initialOf("★star"))
    }

    @Test
    fun `空串或纯空白归井号`() {
        assertEquals('#', PinyinInitials.initialOf(""))
        assertEquals('#', PinyinInitials.initialOf("   "))
    }

    @Test
    fun `emoji或其他非ASCII非汉字归井号`() {
        assertEquals('#', PinyinInitials.initialOf("😀up"))
        assertEquals('#', PinyinInitials.initialOf("ハロー"))
    }

    @Test
    fun `前导空白不影响首字母`() {
        assertEquals('A', PinyinInitials.initialOf("  Alice"))
        assertEquals('Z', PinyinInitials.initialOf("  张三"))
    }

    @Test
    fun `全量分组结果合理`() {
        val names = listOf("张三", "Alice", "123", "罗翔", "   ", "@官方", "B站")
        val groups = names.map { PinyinInitials.initialOf(it) }
        assertEquals(listOf('Z', 'A', '#', 'L', '#', '#', 'B'), groups)
    }

    @Test
    fun `searchKeysOf 提供原文-全拼-首字母三个键`() {
        val keys = PinyinInitials.searchKeysOf("小朋友jim120110")
        assertEquals(3, keys.size)
        assertEquals("小朋友jim120110", keys[0])
        assertEquals("xiaopengyoujim120110", keys[1])
        assertEquals("xpyjim120110", keys[2])
    }

    @Test
    fun `searchKeysOf 命中拼音首字母加数字的混合查询`() {
        // 用户输入 "xpyjim120" 应同时命中两个账号
        assertTrue(PinyinInitials.searchKeysOf("小朋友jim120110").any { it.contains("xpyjim120") })
        assertTrue(PinyinInitials.searchKeysOf("笑拼音就im120890").any { it.contains("xpyjim120") })
    }

    @Test
    fun `searchKeysOf 命中完整拼音查询`() {
        assertTrue(PinyinInitials.searchKeysOf("张三").any { it.contains("zhangsan") })
        assertTrue(PinyinInitials.searchKeysOf("张三").any { it.contains("zs") })
    }

    @Test
    fun `searchKeysOf 命中原始昵称且大小写统一为小写`() {
        val keys = PinyinInitials.searchKeysOf("BiliBili官方")
        assertTrue(keys.any { it.contains("bilibili") })
        // 键均小写，调用方应先把查询串转小写再做 contains
        assertEquals("bilibili官方", keys[0])
    }

    @Test
    fun `searchKeysOf 空串返回空列表`() {
        assertEquals(0, PinyinInitials.searchKeysOf("").size)
        assertEquals(0, PinyinInitials.searchKeysOf("   ").size)
    }
}
