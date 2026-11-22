package com.omar.zoneshrink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Interleaved vertex data: position (3 floats) + normal (3 floats). */
class Mesh(data: FloatArray) {
    val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .also {
            it.put(data)
            it.position(0)
        }
    val count = data.size / 6
}

object MeshFactory {

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun tri(l: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray) {
        for (p in arrayOf(a, b, c)) {
            l.add(p[0]); l.add(p[1]); l.add(p[2])
            l.add(n[0]); l.add(n[1]); l.add(n[2])
        }
    }

    private fun quad(
        l: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, n: FloatArray
    ) {
        tri(l, a, b, c, n)
        tri(l, a, c, d, n)
    }

    /** Unit cube centered at the origin. */
    fun cube(): Mesh {
        val l = ArrayList<Float>()
        val h = 0.5f
        quad(l, v(h, -h, -h), v(h, h, -h), v(h, h, h), v(h, -h, h), v(1f, 0f, 0f))
        quad(l, v(-h, -h, h), v(-h, h, h), v(-h, h, -h), v(-h, -h, -h), v(-1f, 0f, 0f))
        quad(l, v(-h, h, -h), v(-h, h, h), v(h, h, h), v(h, h, -h), v(0f, 1f, 0f))
        quad(l, v(-h, -h, h), v(-h, -h, -h), v(h, -h, -h), v(h, -h, h), v(0f, -1f, 0f))
        quad(l, v(-h, -h, h), v(h, -h, h), v(h, h, h), v(-h, h, h), v(0f, 0f, 1f))
        quad(l, v(h, -h, -h), v(-h, -h, -h), v(-h, h, -h), v(h, h, -h), v(0f, 0f, -1f))
        return Mesh(l.toFloatArray())
    }

    /** Faceted cylinder or cone, from y = 0 (bottom) to y = 1 (top). */
    fun cylinder(seg: Int, bottomR: Float, topR: Float, capped: Boolean): Mesh {
        val l = ArrayList<Float>()
        for (i in 0 until seg) {
            val a0 = (2.0 * PI * i / seg).toFloat()
            val a1 = (2.0 * PI * (i + 1) / seg).toFloat()
            val am = (a0 + a1) / 2f
            val b0 = v(bottomR * cos(a0), 0f, bottomR * sin(a0))
            val b1 = v(bottomR * cos(a1), 0f, bottomR * sin(a1))
            val t0 = v(topR * cos(a0), 1f, topR * sin(a0))
            val t1 = v(topR * cos(a1), 1f, topR * sin(a1))
            var nx = cos(am)
            var ny = bottomR - topR
            var nz = sin(am)
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= len; ny /= len; nz /= len
            quad(l, b0, b1, t1, t0, v(nx, ny, nz))
            if (capped) {
                if (topR > 0f) tri(l, v(0f, 1f, 0f), t1, t0, v(0f, 1f, 0f))
                tri(l, v(0f, 0f, 0f), b0, b1, v(0f, -1f, 0f))
            }
        }
        return Mesh(l.toFloatArray())
    }
}
