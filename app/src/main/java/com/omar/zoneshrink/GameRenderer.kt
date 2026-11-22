package com.omar.zoneshrink

import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(private val game: Game) : GLSurfaceView.Renderer {

    private val vertSrc = """
        attribute vec3 aPos;
        attribute vec3 aNrm;
        uniform mat4 uVP;
        uniform mat4 uModel;
        uniform vec3 uScale;
        uniform vec3 uOff;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vec4 w = uModel * vec4((aPos + uOff) * uScale, 1.0);
            vW = w.xyz;
            vN = (uModel * vec4(aNrm, 0.0)).xyz;
            gl_Position = uVP * w;
        }
    """.trimIndent()

    private val fragSrc = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec3 vN;
        varying vec3 vW;
        uniform vec4 uColor;
        uniform vec3 uCam;
        uniform vec3 uLight;
        uniform vec3 uFog;
        uniform float uFogEnd;
        uniform float uMode;
        void main() {
            vec3 base = uColor.rgb;
            vec3 col;
            if (uMode > 1.5) {
                col = base;
            } else {
                if (uMode > 0.5) {
                    float cx = floor(vW.x / 3.0);
                    float cz = floor(vW.z / 3.0);
                    float n = fract(sin(cx * 12.9898 + cz * 78.233) * 43758.5453);
                    base = base * (0.86 + 0.2 * n);
                }
                vec3 nn = normalize(vN);
                float d = max(dot(nn, uLight), 0.0);
                col = base * (0.45 + 0.65 * d);
            }
            float dist = length(vW - uCam);
            float f = clamp((dist - 25.0) / (uFogEnd - 25.0), 0.0, 1.0);
            col = mix(col, uFog, f);
            gl_FragColor = vec4(col, uColor.a);
        }
    """.trimIndent()

    private var program = 0
    private var aPos = 0
    private var aNrm = 0
    private var uVP = 0
    private var uModel = 0
    private var uScale = 0
    private var uOff = 0
    private var uColor = 0
    private var uCam = 0
    private var uLight = 0
    private var uFog = 0
    private var uFogEnd = 0
    private var uMode = 0

    private lateinit var cube: Mesh
    private lateinit var cyl: Mesh
    private lateinit var cone: Mesh
    private lateinit var ring: Mesh

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)
    private val base = FloatArray(16)
    private val part = FloatArray(16)
    private val vec = FloatArray(4)
    private val res = FloatArray(4)

    private var aspect = 1f
    private var last = 0L
    private var camX = 0f
    private var camZ = 0f

    // data shared with the HUD overlay (enemy health bars in screen space)
    @Volatile var screenW = 1
    @Volatile var screenH = 1
    @Volatile var markCount = 0
    val marks = FloatArray(96)

    private val skin = Color.rgb(224, 172, 105)
    private val roofColor = Color.rgb(110, 60, 50)
    private val doorColor = Color.rgb(70, 45, 30)
    private val windowColor = Color.rgb(90, 140, 190)
    private val trunkColor = Color.rgb(100, 70, 45)
    private val leafDark = Color.rgb(40, 100, 55)
    private val leafLight = Color.rgb(60, 130, 70)
    private val fenceColor = Color.rgb(85, 80, 80)
    private val groundColor = Color.rgb(92, 140, 78)

    // ------------------------------------------------------------------ GL setup

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) Log.e("ZoneShrink", "Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.55f, 0.75f, 0.95f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertSrc))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragSrc))
        GLES20.glLinkProgram(program)

        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNrm = GLES20.glGetAttribLocation(program, "aNrm")
        uVP = GLES20.glGetUniformLocation(program, "uVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uOff = GLES20.glGetUniformLocation(program, "uOff")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uFog = GLES20.glGetUniformLocation(program, "uFog")
        uFogEnd = GLES20.glGetUniformLocation(program, "uFogEnd")
        uMode = GLES20.glGetUniformLocation(program, "uMode")

        cube = MeshFactory.cube()
        cyl = MeshFactory.cylinder(10, 0.5f, 0.5f, true)
        cone = MeshFactory.cylinder(8, 0.5f, 0f, true)
        ring = MeshFactory.cylinder(64, 1f, 1f, false)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.toFloat()
        screenW = width
        screenH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        game.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        renderScene()
    }

    // ------------------------------------------------------------------ draw helpers

    private fun setModel(x: Float, y: Float, z: Float, rotYDeg: Float) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, x, y, z)
        if (rotYDeg != 0f) Matrix.rotateM(base, 0, rotYDeg, 0f, 1f, 0f)
    }

    private fun partM(tx: Float, ty: Float, tz: Float, rotZDeg: Float): FloatArray {
        System.arraycopy(base, 0, part, 0, 16)
        Matrix.translateM(part, 0, tx, ty, tz)
        if (rotZDeg != 0f) Matrix.rotateM(part, 0, rotZDeg, 0f, 0f, 1f)
        return part
    }

    private fun draw(
        mesh: Mesh, m: FloatArray,
        ox: Float, oy: Float, oz: Float,
        sx: Float, sy: Float, sz: Float,
        color: Int, alpha: Float = 1f, mode: Float = 0f
    ) {
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glUniform3f(uScale, sx, sy, sz)
        GLES20.glUniform3f(uOff, ox, oy, oz)
        GLES20.glUniform4f(
            uColor,
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            alpha
        )
        GLES20.glUniform1f(uMode, mode)
        mesh.buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, mesh.buf)
        mesh.buf.position(3)
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 24, mesh.buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count)
    }

    // ------------------------------------------------------------------ scene

    private fun renderScene() {
        val p = game.player
        val yaw = game.camYaw
        val pitch = game.camPitch
        val fx = cos(yaw)
        val fz = sin(yaw)
        val dist = 4.5f
        camX = p.x - fx * dist * cos(pitch)
        camZ = p.y - fz * dist * cos(pitch)
        val camY = 1.7f + dist * sin(pitch)

        Matrix.setLookAtM(view, 0, camX, camY, camZ, p.x + fx * 10f, 1.3f, p.y + fz * 10f, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 65f, aspect, 0.2f, 250f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, camX, camY, camZ)
        GLES20.glUniform3f(uLight, 0.45f, 0.8f, 0.4f)
        GLES20.glUniform3f(uFog, 0.55f, 0.75f, 0.95f)
        GLES20.glUniform1f(uFogEnd, 120f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNrm)

        drawGround()
        drawObstacles()
        drawPickups()
        for (b in game.bots) if (b.alive) drawEntity(b)
        if (p.alive) drawEntity(p)
        drawBullets()

        // translucent things last, without writing depth
        GLES20.glDepthMask(false)
        drawBeams()
        setModel(game.zc, 0f, game.zc, 0f)
        val zr = game.zoneR
        draw(ring, base, 0f, 0f, 0f, zr, 60f, zr, Color.rgb(80, 160, 255), 0.25f, 2f)
        GLES20.glDepthMask(true)

        projectMarks()
    }

    private fun drawGround() {
        val z = game.zc
        setModel(z, -0.1f, z, 0f)
        draw(cube, base, 0f, 0f, 0f, 700f, 0.2f, 700f, groundColor, 1f, 1f)

        val w = Game.WORLD
        setModel(z, 1.25f, -0.5f, 0f)
        draw(cube, base, 0f, 0f, 0f, w + 2f, 2.5f, 1f, fenceColor)
        setModel(z, 1.25f, w + 0.5f, 0f)
        draw(cube, base, 0f, 0f, 0f, w + 2f, 2.5f, 1f, fenceColor)
        setModel(-0.5f, 1.25f, z, 0f)
        draw(cube, base, 0f, 0f, 0f, 1f, 2.5f, w + 2f, fenceColor)
        setModel(w + 0.5f, 1.25f, z, 0f)
        draw(cube, base, 0f, 0f, 0f, 1f, 2.5f, w + 2f, fenceColor)
    }

    private fun drawObstacles() {
        val maxD2 = 90f * 90f
        for (o in game.obstacles) {
            val cx = (o.r.left + o.r.right) / 2f
            val cz = (o.r.top + o.r.bottom) / 2f
            val dx = cx - camX
            val dz = cz - camZ
            if (dx * dx + dz * dz > maxD2) continue
            val w = o.r.width()
            val d = o.r.height()
            when (o.kind) {
                0 -> {
                    setModel(cx, o.h / 2f, cz, 0f)
                    draw(cube, base, 0f, 0f, 0f, w, o.h, d, o.color)
                    setModel(cx, o.h + 0.15f, cz, 0f)
                    draw(cube, base, 0f, 0f, 0f, w + 0.6f, 0.3f, d + 0.6f, roofColor)
                    // door on the +z side
                    setModel(cx, 0.9f, o.r.bottom + 0.02f, 0f)
                    draw(cube, base, 0f, 0f, 0f, 1.2f, 1.8f, 0.08f, doorColor)
                    // windows on both z sides
                    val wy = o.h * 0.6f
                    setModel(cx - w * 0.28f, wy, o.r.bottom + 0.02f, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.9f, 0.9f, 0.08f, windowColor)
                    setModel(cx + w * 0.28f, wy, o.r.bottom + 0.02f, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.9f, 0.9f, 0.08f, windowColor)
                    setModel(cx - w * 0.28f, wy, o.r.top - 0.02f, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.9f, 0.9f, 0.08f, windowColor)
                    setModel(cx + w * 0.28f, wy, o.r.top - 0.02f, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.9f, 0.9f, 0.08f, windowColor)
                    // windows on both x sides
                    setModel(o.r.left - 0.02f, wy, cz, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.08f, 0.9f, 0.9f, windowColor)
                    setModel(o.r.right + 0.02f, wy, cz, 0f)
                    draw(cube, base, 0f, 0f, 0f, 0.08f, 0.9f, 0.9f, windowColor)
                }
                1 -> {
                    val s = o.h / 6f
                    setModel(cx, 0f, cz, 0f)
                    draw(cyl, base, 0f, 0f, 0f, 0.7f, 2.4f * s, 0.7f, trunkColor)
                    setModel(cx, 1.7f * s, cz, 0f)
                    draw(cone, base, 0f, 0f, 0f, 3.4f * s, 3.2f * s, 3.4f * s, leafDark)
                    setModel(cx, 3.4f * s, cz, 0f)
                    draw(cone, base, 0f, 0f, 0f, 2.6f * s, 2.8f * s, 2.6f * s, leafLight)
                }
                else -> {
                    setModel(cx, o.h / 2f, cz, 0f)
                    draw(cube, base, 0f, 0f, 0f, w, o.h, d, o.color)
                }
            }
        }
    }

    private fun drawEntity(e: Entity) {
        val w = e.slots[e.cur].w

        // blob shadow
        setModel(e.x, 0.03f, e.y, 0f)
        draw(cyl, base, 0f, 0f, 0f, 1.1f, 0.02f, 1.1f, Color.BLACK, 0.35f, 2f)

        setModel(e.x, 0f, e.y, -e.angle * 57.29578f)
        val sw = sin(e.walk) * e.stride * 40f
        val legCol = if (e.isPlayer) Color.rgb(40, 50, 70) else Color.rgb(60, 55, 50)
        val helmet = if (e.isPlayer) Color.rgb(30, 90, 140) else Color.rgb(70, 40, 40)

        draw(cube, partM(0f, 0.85f, 0.12f, sw), 0f, -0.5f, 0f, 0.2f, 0.85f, 0.22f, legCol)
        draw(cube, partM(0f, 0.85f, -0.12f, -sw), 0f, -0.5f, 0f, 0.2f, 0.85f, 0.22f, legCol)
        draw(cube, partM(0f, 1.18f, 0f, 0f), 0f, 0f, 0f, 0.32f, 0.6f, 0.52f, e.color)
        draw(cube, partM(0f, 1.68f, 0f, 0f), 0f, 0f, 0f, 0.3f, 0.3f, 0.3f, skin)
        draw(cube, partM(0f, 1.86f, 0f, 0f), 0f, 0f, 0f, 0.36f, 0.14f, 0.36f, helmet)
        draw(cube, partM(0.28f, 1.3f, 0.26f, 0f), 0f, 0f, 0f, 0.5f, 0.12f, 0.12f, e.color)
        draw(cube, partM(0.3f, 1.3f, -0.02f, 0f), 0f, 0f, 0f, 0.55f, 0.12f, 0.12f, e.color)
        draw(cube, partM(0.25f + w.length / 2f, 1.28f, 0.12f, 0f), 0f, 0f, 0f, w.length, 0.12f, 0.1f, w.color)

        if (e.flash > 0f) {
            draw(
                cube, partM(0.25f + w.length + 0.12f, 1.28f, 0.12f, 0f), 0f, 0f, 0f,
                0.28f, 0.28f, 0.28f, Color.rgb(255, 220, 90), 1f, 2f
            )
        }
    }

    private fun drawBullets() {
        val col = Color.rgb(255, 235, 120)
        for (b in game.bullets) {
            setModel(b.x, 1.25f, b.y, -atan2(b.vy, b.vx) * 57.29578f)
            draw(cube, base, 0f, 0f, 0f, 1.2f, 0.05f, 0.05f, col, 1f, 2f)
        }
    }

    private fun pickupColor(p: Pickup): Int = when (p.type) {
        0 -> p.weapon!!.color
        1 -> Color.rgb(60, 200, 90)
        else -> Color.rgb(255, 200, 50)
    }

    private fun drawBeams() {
        val maxD2 = 100f * 100f
        for (p in game.pickups) {
            val dx = p.x - camX
            val dz = p.y - camZ
            if (dx * dx + dz * dz > maxD2) continue
            setModel(p.x, 3f, p.y, 0f)
            draw(cube, base, 0f, 0f, 0f, 0.15f, 6f, 0.15f, pickupColor(p), 0.35f, 2f)
        }
    }

    private fun drawPickups() {
        val maxD2 = 90f * 90f
        val t = game.time
        for (p in game.pickups) {
            val dx = p.x - camX
            val dz = p.y - camZ
            if (dx * dx + dz * dz > maxD2) continue
            val bob = sin(t * 2f + p.x) * 0.1f
            setModel(p.x, 0.7f + bob, p.y, t * 60f)
            when (p.type) {
                0 -> draw(cube, base, 0f, 0f, 0f, 0.9f, 0.25f, 0.3f, p.weapon!!.color)
                1 -> {
                    draw(cube, base, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f, Color.rgb(46, 160, 70))
                    draw(cube, base, 0f, 0f, 0f, 0.56f, 0.14f, 0.14f, Color.WHITE)
                    draw(cube, base, 0f, 0f, 0f, 0.14f, 0.14f, 0.56f, Color.WHITE)
                }
                else -> {
                    draw(cube, base, 0f, 0f, 0f, 0.5f, 0.35f, 0.35f, Color.rgb(255, 190, 40))
                    draw(cube, base, 0f, 0f, 0f, 0.14f, 0.38f, 0.38f, Color.rgb(120, 80, 0))
                }
            }
        }
    }

    private fun projectMarks() {
        var n = 0
        for (b in game.bots) {
            if (!b.alive || b.hp >= 100f || n >= 32) continue
            vec[0] = b.x
            vec[1] = 2.2f
            vec[2] = b.y
            vec[3] = 1f
            Matrix.multiplyMV(res, 0, vp, 0, vec, 0)
            if (res[3] <= 0.1f) continue
            val nx = res[0] / res[3]
            val ny = res[1] / res[3]
            marks[n * 3] = (nx * 0.5f + 0.5f) * screenW
            marks[n * 3 + 1] = (1f - (ny * 0.5f + 0.5f)) * screenH
            marks[n * 3 + 2] = b.hp
            n++
        }
        markCount = n
    }
}
