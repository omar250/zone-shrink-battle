package com.omar.zoneshrink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Transparent overlay on top of the GL view: HUD drawing and touch controls. */
class HudView(
    context: Context,
    private val game: Game,
    private val renderer: GameRenderer
) : View(context) {

    private val u = resources.displayMetrics.density
    private val stickR = 55f * u

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private var moveId = -1
    private var mbx = 0f
    private var mby = 0f
    private var lookId = -1
    private var lx = 0f
    private var ly = 0f
    private var fireId = -1
    private var fxl = 0f
    private var fyl = 0f

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = game.player

        // damage flash and out-of-zone tint
        if (game.hitFlash > 0f) {
            val a = (game.hitFlash / 0.35f * 110f).toInt().coerceIn(0, 255)
            fill(Color.argb(a, 255, 0, 0))
            c.drawRect(0f, 0f, w, h, paint)
        }
        if (p.alive && hypot(p.x - game.zc, p.y - game.zc) > game.zoneR) {
            fill(Color.argb(40, 255, 0, 0))
            c.drawRect(0f, 0f, w, h, paint)
        }

        // crosshair
        val cx = w / 2f
        val cy = h / 2f
        stroke(Color.argb(210, 255, 255, 255), 2f * u)
        c.drawLine(cx - 16f * u, cy, cx - 6f * u, cy, paint)
        c.drawLine(cx + 6f * u, cy, cx + 16f * u, cy, paint)
        c.drawLine(cx, cy - 16f * u, cx, cy - 6f * u, paint)
        c.drawLine(cx, cy + 6f * u, cx, cy + 16f * u, paint)
        fill(Color.argb(210, 255, 80, 80))
        c.drawCircle(cx, cy, 2f * u, paint)

        // enemy health bars
        val n = min(renderer.markCount, 32)
        for (i in 0 until n) {
            val sx = renderer.marks[i * 3]
            val sy = renderer.marks[i * 3 + 1]
            val hp = renderer.marks[i * 3 + 2]
            fill(Color.argb(170, 0, 0, 0))
            c.drawRect(sx - 22f * u, sy - 3f * u, sx + 22f * u, sy + 3f * u, paint)
            fill(Color.rgb(230, 60, 60))
            c.drawRect(sx - 22f * u, sy - 3f * u, sx - 22f * u + 44f * u * hp / 100f, sy + 3f * u, paint)
        }

        // minimap
        val ms = 110f * u
        val mx = 12f * u
        val my = 12f * u
        fill(Color.argb(110, 0, 0, 0))
        c.drawRect(mx, my, mx + ms, my + ms, paint)
        stroke(Color.argb(230, 90, 170, 255), 2f * u)
        c.drawCircle(mx + game.zc / Game.WORLD * ms, my + game.zc / Game.WORLD * ms, game.zoneR / Game.WORLD * ms, paint)
        fill(Color.CYAN)
        c.drawCircle(mx + p.x / Game.WORLD * ms, my + p.y / Game.WORLD * ms, 3f * u, paint)

        // top info
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f * u
        c.drawText("الأحياء: ${game.aliveCount()}     القتلى: ${p.kills}", w / 2f, 28f * u, text)
        text.textSize = 15f * u
        c.drawText(game.zoneMessage(), w / 2f, 52f * u, text)
        if (p.alive && hypot(p.x - game.zc, p.y - game.zc) > game.zoneR) {
            text.color = Color.rgb(255, 90, 90)
            c.drawText("أنت خارج الدائرة الآمنة! ادخل بسرعة", w / 2f, 76f * u, text)
            text.color = Color.WHITE
        }

        // weapon info
        val s = p.slots[p.cur]
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 18f * u
        c.drawText("${s.w.name}   ${if (s.ammo < 0) "∞" else s.ammo.toString()}", w - 14f * u, 30f * u, text)

        // switch weapon button
        stroke(Color.argb(200, 255, 255, 255), 2f * u)
        c.drawCircle(w - 70f * u, 110f * u, 36f * u, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f * u
        c.drawText("تبديل", w - 70f * u, 115f * u, text)

        // fire button
        val fxp = w - 120f * u
        val fyp = h - 120f * u
        fill(Color.argb(if (fireId != -1) 150 else 80, 255, 70, 60))
        c.drawCircle(fxp, fyp, 52f * u, paint)
        stroke(Color.argb(220, 255, 255, 255), 3f * u)
        c.drawCircle(fxp, fyp, 52f * u, paint)
        text.textSize = 18f * u
        c.drawText("نار", fxp, fyp + 6f * u, text)

        // health bar
        val bw = 220f * u
        val bx = w / 2f - bw / 2f
        val by = h - 30f * u
        fill(Color.argb(150, 0, 0, 0))
        c.drawRect(bx, by, bx + bw, by + 14f * u, paint)
        fill(Color.rgb(70, 200, 90))
        c.drawRect(bx, by, bx + bw * p.hp / 100f, by + 14f * u, paint)

        // move stick
        val active = moveId != -1
        val a = if (active) 200 else 70
        val sbx = if (active) mbx else 130f * u
        val sby = if (active) mby else h - 120f * u
        stroke(Color.argb(a, 255, 255, 255), 3f * u)
        c.drawCircle(sbx, sby, stickR, paint)
        fill(Color.argb(a, 255, 255, 255))
        c.drawCircle(sbx + game.mvx * stickR, sby + game.mvy * stickR, 20f * u, paint)

        // end overlay
        if (game.state != 0) {
            fill(Color.argb(170, 0, 0, 0))
            c.drawRect(0f, 0f, w, h, paint)
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            text.textSize = 40f * u
            c.drawText(
                if (game.state == 1) "فزت بالمباراة!" else "خسرت - المركز ${game.rank}",
                w / 2f, h / 2f - 10f * u, text
            )
            text.textSize = 20f * u
            c.drawText("القتلى: ${p.kills}", w / 2f, h / 2f + 28f * u, text)
            if (game.stateTime > 1.5f) {
                c.drawText("المس الشاشة للعب من جديد", w / 2f, h / 2f + 64f * u, text)
            }
        }

        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ input

    private fun look(dx: Float, dy: Float) {
        game.camYaw = game.camYaw + dx * 0.006f
        game.camPitch = (game.camPitch + dy * 0.004f).coerceIn(-0.1f, 0.8f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                if (game.state != 0) {
                    if (game.stateTime > 1.5f) game.restartRequested = true
                    return true
                }
                if (hypot(x - (w - 70f * u), y - 110f * u) < 44f * u) {
                    game.switchRequested = true
                } else if (hypot(x - (w - 120f * u), y - (h - 120f * u)) < 60f * u) {
                    if (fireId == -1) {
                        fireId = id
                        fxl = x
                        fyl = y
                        game.firing = true
                    }
                } else if (x < w / 2f) {
                    if (moveId == -1) {
                        moveId = id
                        mbx = x
                        mby = y
                        game.mvx = 0f
                        game.mvy = 0f
                    }
                } else {
                    if (lookId == -1) {
                        lookId = id
                        lx = x
                        ly = y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val x = ev.getX(i)
                    val y = ev.getY(i)
                    if (id == moveId) {
                        var dx = (x - mbx) / stickR
                        var dy = (y - mby) / stickR
                        val l = hypot(dx, dy)
                        if (l > 1f) {
                            dx /= l
                            dy /= l
                        }
                        game.mvx = dx
                        game.mvy = dy
                    } else if (id == lookId) {
                        look(x - lx, y - ly)
                        lx = x
                        ly = y
                    } else if (id == fireId) {
                        look(x - fxl, y - fyl)
                        fxl = x
                        fyl = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == moveId) {
                    moveId = -1
                    game.mvx = 0f
                    game.mvy = 0f
                }
                if (id == lookId) lookId = -1
                if (id == fireId) {
                    fireId = -1
                    game.firing = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                moveId = -1
                lookId = -1
                fireId = -1
                game.mvx = 0f
                game.mvy = 0f
                game.firing = false
            }
        }
        return true
    }
}
