package com.tutu.myblbl.core.common.format

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.util.concurrent.ConcurrentHashMap

/**
 * 文本 → 首字母分组键（'A'..'Z' / '#'），用于按关注账号昵称首字母筛选。
 *
 * 归类规则：
 * - ASCII 字母开头（不区分大小写）→ 对应大写字母
 * - 汉字开头 → 取拼音首字母（多音字取字典第一个读音，属默认读音）
 * - 数字 / 符号 / emoji / 其他非汉字非 ASCII / 空白 / 空串 / 转换失败 → '#'
 *
 * 说明：
 * - 底层使用 pinyin4j（Maven Central），纯 JVM 实现，单测可直接覆盖。
 * - 结果按完整昵称做内存缓存（关注数有限，上限 2048 足够）。
 */
object PinyinInitials {

    private const val FALLBACK = '#'
    private const val MAX_CACHE_SIZE = 2048

    private val cache = ConcurrentHashMap<String, Char>()

    private val pinyinFormat: HanyuPinyinOutputFormat by lazy {
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }
    }

    /** 取昵称首字母分组键。线程安全，可在任意线程调用。 */
    fun initialOf(text: String): Char {
        val key = text
        cache[key]?.let { return it }
        val result = computeInitial(text)
        if (cache.size < MAX_CACHE_SIZE) {
            cache[key] = result
        }
        return result
    }

    private fun computeInitial(text: String): Char {
        val first = text.trim().firstOrNull() ?: return FALLBACK
        val lowered = first.lowercaseChar()
        if (lowered in 'a'..'z') {
            return lowered.uppercaseChar()
        }
        if (first.code < 128) {
            // 数字、半角符号等
            return FALLBACK
        }
        // 汉字交给 pinyin4j；非汉字（emoji、假名等）toHanyuPinyinStringArray 返回 null
        return try {
            val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(first, pinyinFormat)
            val pinyin = pinyinArray?.firstOrNull() ?: return FALLBACK
            val firstPinyinChar = pinyin.firstOrNull()?.uppercaseChar() ?: return FALLBACK
            if (firstPinyinChar in 'A'..'Z') firstPinyinChar else FALLBACK
        } catch (e: Exception) {
            FALLBACK
        }
    }

    /**
     * 构建昵称搜索键（用于模糊子串匹配，如输入 "xpyjim120" 可命中 "小朋友jim120110"）。
     * 返回三个键：
     * 1. 原始昵称（小写）
     * 2. 完整拼音（汉字转全拼无音调，其余字符原样小写）
     * 3. 拼音首字母（汉字取拼音首字母，其余字符原样小写）
     */
    fun searchKeysOf(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rawLower = trimmed.lowercase()
        val full = StringBuilder(trimmed.length * 2)
        val initials = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val pinyinArray = try {
                PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat)
            } catch (e: Exception) {
                null
            }
            if (pinyinArray.isNullOrEmpty()) {
                val lower = ch.lowercaseChar()
                full.append(lower)
                initials.append(lower)
            } else {
                val pinyin = pinyinArray.first()
                full.append(pinyin)
                initials.append(pinyin.first())
            }
        }
        return listOf(rawLower, full.toString(), initials.toString())
    }

    /** 后台预热 pinyin4j 内部资源（首次调用较慢，避免首帧卡顿）。 */
    fun warmUp() {
        PinyinHelper.toHanyuPinyinStringArray('测', pinyinFormat)
    }
}
