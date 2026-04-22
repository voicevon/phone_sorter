package com.example.asparagusclassifier.util

import org.opencv.core.Mat
import java.util.LinkedList

/**
 * OpenCV Mat 资源管理作用域
 * 自动释放作用域内创建的所有 Mat 对象，防止内存泄露
 */
class MatScope : AutoCloseable {
    private val mats = LinkedList<Mat>()

    /**
     * 创建一个新的 Mat 并加入作用域管理
     */
    fun createMat(): Mat {
        val mat = Mat()
        mats.add(mat)
        return mat
    }

    /**
     * 将已有的 Mat 加入作用域管理
     */
    fun manage(mat: Mat): Mat {
        mats.add(mat)
        return mat
    }

    /**
     * 释放作用域内所有的 Mat
     */
    override fun close() {
        while (mats.isNotEmpty()) {
            mats.removeFirst().release()
        }
    }
}

/**
 * Kotlin 扩展：便捷使用 MatScope
 */
inline fun <R> useMatScope(block: (MatScope) -> R): R {
    return MatScope().use { scope ->
        block(scope)
    }
}
