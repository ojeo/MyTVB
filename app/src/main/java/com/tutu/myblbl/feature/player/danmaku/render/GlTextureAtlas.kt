package com.tutu.myblbl.feature.player.danmaku.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.tutu.myblbl.feature.player.danmaku.model.SharedCacheEntry

/**
 * 弹幕纹理槽位：引用一个 Atlas 页面上的矩形区域及其 UV。
 * [pageId] 是页槽位索引（0..maxPages-1，固定不变，页内容可被淘汰重建）。
 */
internal class AtlasSlot(
    val pageId: Int,
    val width: Int,
    val height: Int,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
)

/**
 * Paged Texture Atlas：把异步栅格化产出的弹幕 Bitmap 打包进 1024×1024 大纹理，
 * 使整屏弹幕只需 1~N 次 Draw Call（按页绑定一次纹理 + 一次 Instanced 绘制）。
 *
 * 设计要点（对齐高性能弹幕方案）：
 * - Shelf Allocator 行式分配：高度相近的弹幕文本行复用同一 shelf，实现简单且利用率足够。
 * - 上传预算：每帧限制 CPU→GPU 上传字节数（默认 1MB），超预算条目本帧跳过（与 Canvas 引擎
 *   的 cache miss 语义一致），避免一帧上传大量纹理阻塞 RenderThread。
 * - 页淘汰：达到页数上限时淘汰"最久未使用"页（重置其 shelf 状态，纹理内存保留复用）。
 *   被淘汰页上的弹幕若仍被引用，下一帧会重新上传（bitmap 由 CacheManager 引用计数保活）。
 * - 所有方法只能在 RenderThread（持有 GL context）调用。
 */
internal class GlTextureAtlas(
    private val pageWidth: Int = ATLAS_PAGE_WIDTH,
    private val pageHeight: Int = ATLAS_PAGE_HEIGHT,
    private val maxPages: Int = ATLAS_MAX_PAGES,
    private val uploadBudgetBytesPerFrame: Long = ATLAS_UPLOAD_BUDGET_BYTES,
) {
    private val pages = arrayOfNulls<AtlasPage>(maxPages)
    private val entryToSlot = HashMap<SharedCacheEntry, AtlasSlot>(64)
    private val pageTexturesOut = IntArray(maxPages)
    private var frameUploadBytes = 0L
    private var frameIndex = 0L

    fun onFrameBegin() {
        frameIndex++
        frameUploadBytes = 0L
    }

    /**
     * 获取（或上传）某共享缓存条目的纹理槽位。
     * 返回 null 表示本帧不可用（bitmap 已回收 / 超上传预算 / 无可用页），调用方应跳过绘制。
     */
    fun obtain(entry: SharedCacheEntry): AtlasSlot? {
        val existing = entryToSlot[entry]
        if (existing != null) {
            pages[existing.pageId]?.lastTouchedFrame = frameIndex
            return existing
        }
        if (entry.isRecycled) return null
        val bmp = entry.bitmap
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return null
        // Do not eagerly instantiate every page for an item that can never fit.
        if (bmp.width > pageWidth || bmp.height > pageHeight) return null
        val bytes = bmp.allocationByteCount.toLong().coerceAtLeast(0L)
        if (frameUploadBytes + bytes > uploadBudgetBytesPerFrame) return null

        var page = findPageWithSpace(bmp)
        if (page == null) {
            if (!evictLeastRecentPage()) return null
            page = findPageWithSpace(bmp)
        }
        val alloc = page?.alloc(bmp.width, bmp.height) ?: return null

        upload(page, alloc.left, alloc.top, bmp)
        val slot = AtlasSlot(
            pageId = page.id,
            width = bmp.width,
            height = bmp.height,
            u0 = alloc.left.toFloat() / pageWidth,
            v0 = alloc.top.toFloat() / pageHeight,
            u1 = (alloc.left + bmp.width).toFloat() / pageWidth,
            v1 = (alloc.top + bmp.height).toFloat() / pageHeight,
        )
        entryToSlot[entry] = slot
        page.lastTouchedFrame = frameIndex
        return slot
    }

    /** 每帧输出各页纹理 id（未分配页为 0），供 Instanced Renderer 按页分批绘制。 */
    fun fillPageTextures(out: IntArray) {
        for (i in out.indices) {
            out[i] = pages[i]?.textureId ?: 0
        }
    }

    fun textureIds(): IntArray {
        fillPageTextures(pageTexturesOut)
        return pageTexturesOut
    }

    fun pageCount(): Int {
        var count = 0
        for (p in pages) if (p != null) count++
        return count
    }

    /** 样式代际变化（字号/描边/字重）时清空全部纹理，强制下帧重建。 */
    fun clear() {
        for (i in pages.indices) {
            pages[i]?.resetShelf()
        }
        entryToSlot.clear()
        frameUploadBytes = 0L
    }

    fun release() {
        clear()
        for (i in pages.indices) {
            pages[i]?.destroy()
            pages[i] = null
        }
    }

    private fun findPageWithSpace(bmp: Bitmap): AtlasPage? {
        for (i in pages.indices) {
            val page = ensurePage(i) ?: continue
            if (page.hasRoom(bmp.width, bmp.height)) return page
        }
        return null
    }

    private fun ensurePage(index: Int): AtlasPage? {
        var page = pages[index]
        if (page == null) {
            val created = AtlasPage(index, pageWidth, pageHeight)
            if (!created.init()) return null
            pages[index] = created
            page = created
        }
        return page
    }

    private fun upload(page: AtlasPage, x: Int, y: Int, bmp: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, page.textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, x, y, bmp)
        frameUploadBytes += bmp.allocationByteCount.toLong().coerceAtLeast(0L)
    }

    /** 淘汰最久未使用页；返回 false 表示没有任何可用页（全空）。 */
    private fun evictLeastRecentPage(): Boolean {
        var victimIndex = -1
        var victimFrame = Long.MAX_VALUE
        for (i in pages.indices) {
            val page = pages[i] ?: continue
            if (page.lastTouchedFrame < victimFrame) {
                victimFrame = page.lastTouchedFrame
                victimIndex = i
            }
        }
        if (victimIndex < 0) return false
        val victim = pages[victimIndex] ?: return false
        // 移除该页所有槽位映射，防止继续引用已重置的内容。
        val it = entryToSlot.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.pageId == victim.id) it.remove()
        }
        victim.resetShelf()
        victim.lastTouchedFrame = frameIndex
        return true
    }

    private class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    private class Shelf(val y: Int, val height: Int, var usedX: Int)

    /**
     * 单页 Atlas：shelf（行）分配器 + 一个 GL 纹理。
     * [id] 固定为槽位索引；页被淘汰时仅重置 shelf 状态（纹理内存复用）。
     */
    private class AtlasPage(val id: Int, val width: Int, val height: Int) {
        var textureId = 0
        var lastTouchedFrame = 0L
        private val shelves = ArrayList<Shelf>(16)
        private var usedHeight = 0

        fun init(): Boolean {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
            if (textureId == 0) return false
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return true
        }

        fun hasRoom(w: Int, h: Int): Boolean {
            if (w !in 1..width || h !in 1..height) return false
            // hasRoom must be a pure probe. Calling findShelfRoom here used to advance usedX,
            // then alloc() advanced it a second time, wasting half of every shelf and eventually
            // making UVs point at regions that were never uploaded.
            for (s in shelves) {
                if (s.height >= h && width - s.usedX >= w) return true
            }
            return height - usedHeight >= h
        }

        fun alloc(w: Int, h: Int): Rect? {
            if (w <= 0 || h <= 0 || w > width || h > height) return null
            findShelfRoom(w, h)?.let { return it }
            if (height - usedHeight < h) return null
            val y = usedHeight
            shelves.add(Shelf(y, h, w))
            usedHeight += h
            return Rect(0, y, w, h)
        }

        private fun findShelfRoom(w: Int, h: Int): Rect? {
            for (s in shelves) {
                if (s.height >= h && width - s.usedX >= w) {
                    val x = s.usedX
                    s.usedX += w
                    return Rect(x, s.y, w, h)
                }
            }
            return null
        }

        fun resetShelf() {
            shelves.clear()
            usedHeight = 0
            // 重新分配纹理内容（整页清空，旧内容失效）。
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
        }

        fun destroy() {
            if (textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            shelves.clear()
            usedHeight = 0
        }
    }

    companion object {
        // Danmaku textures are wide and shallow. Square 1024 pages reject ordinary long lines
        // and waste most memory vertically; 2048x512 keeps the same 4MB/page footprint.
        const val ATLAS_PAGE_WIDTH = 2048
        const val ATLAS_PAGE_HEIGHT = 512
        const val ATLAS_MAX_PAGES = 6
        private const val ATLAS_UPLOAD_BUDGET_BYTES = 1_048_576L
    }
}
