package com.omar.zoneshrink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val WORLD = 3000f
        const val R = 20f
        const val BOT_COUNT = 24
    }

    private val u = resources.displayMetrics.density
    private val zc = WORLD / 2f
    private val rnd = Random(System.currentTimeMillis())

    private var thread: Thread? = null
    @Volatile private var running = false

    // world
    private val obstacles = ArrayList<RectF>()
    private val bullets = ArrayList<Bullet>()
    private val pickups = ArrayList<Pickup>()
    private val bots = ArrayList<Entity>()
    private lateinit var player: Entity

    // game state: 0 = playing, 1 = won, 2 = lost
    private var state = 0
    private var stateTime = 0f
    private var rank = 0
    @Volatile private var restartRequested = false
    @Volatile private var switchRequested = false

    // safe zone
    private class Phase(val wait: Float, val shrink: Float, val endR: Float, val dps: Float)

    private val phases = listOf(
        Phase(15f, 20f, 1100f, 2f),
        Phase(15f, 20f, 750f, 4f),
        Phase(12f, 18f, 450f, 7f),
        Phase(10f, 15f, 220f, 10f),
        Phase(8f, 15f, 60f, 14f)
    )
    private var phase = 0
    private var phaseTimer = 0f
    private var zoneStartR = 1500f
    private var zoneR = 1500f
    private var zoneDps = 2f

    // input
    private val stickR = 55f * u
    private var moveId = -1
    private var mbx = 0f
    private var mby = 0f
    @Volatile private var mvx = 0f
    @Volatile private var mvy = 0f
    private var aimId = -1
    private var abx = 0f
    private var aby = 0f
    @Volatile private var avx = 0f
    @Volatile private var avy = 0f

    // drawing
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val zonePath = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
        newGame()
    }

    // ------------------------------------------------------------------ setup

    private fun newGame() {
        obstacles.clear()
        bullets.clear()
        pickups.clear()
        bots.clear()

        repeat(80) {
            val w = 70f + rnd.nextFloat() * 150f
            val h = 70f + rnd.nextFloat() * 150f
            val x = 100f + rnd.nextFloat() * (WORLD - 200f - w)
            val y = 100f + rnd.nextFloat() * (WORLD - 200f - h)
            obstacles.add(RectF(x, y, x + w, y + h))
        }

        player = Entity(zc, zc, true, Color.CYAN)
        place(player, null)

        repeat(BOT_COUNT) {
            val b = Entity(zc, zc, false, Color.rgb(200, 60 + rnd.nextInt(60), 60))
            place(b, player)
            if (rnd.nextFloat() < 0.4f) {
                val w = Weapons.ALL.random(rnd)
                b.slots.add(Slot(w, w.ammo))
                b.cur = 1
            }
            b.think = rnd.nextFloat() * 0.5f
            bots.add(b)
        }

        repeat(70) {
            for (t in 0 until 50) {
                val a = rnd.nextFloat() * 6.2831f
                val r = sqrt(rnd.nextFloat()) * 1400f
                val x = zc + cos(a) * r
                val y = zc + sin(a) * r
                if (blocked(x, y, 22f)) continue
                val roll = rnd.nextFloat()
                pickups.add(
                    if (roll < 0.45f) Pickup(x, y, 0, Weapons.ALL.random(rnd))
                    else if (roll < 0.75f) Pickup(x, y, 1, null)
                    else Pickup(x, y, 2, null)
                )
                break
            }
        }

        phase = 0
        phaseTimer = 0f
        zoneStartR = 1500f
        zoneR = 1500f
        zoneDps = phases[0].dps
        state = 0
        stateTime = 0f
        mvx = 0f; mvy = 0f; avx = 0f; avy = 0f
        moveId = -1; aimId = -1
    }

    private fun place(e: Entity, avoid: Entity?) {
        for (i in 0 until 200) {
            val a = rnd.nextFloat() * 6.2831f
            val r = sqrt(rnd.nextFloat()) * 1350f
            val x = zc + cos(a) * r
            val y = zc + sin(a) * r
            if (blocked(x, y, R + 8f)) continue
            if (avoid != null && hypot(x - avoid.x, y - avoid.y) < 450f) continue
            e.x = x; e.y = y; e.tx = x; e.ty = y
            return
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun blocked(x: Float, y: Float, r: Float): Boolean {
        if (x < r || y < r || x > WORLD - r || y > WORLD - r) return true
        for (o in obstacles) {
            val nx = x.coerceIn(o.left, o.right)
            val ny = y.coerceIn(o.top, o.bottom)
            if (hypot(x - nx, y - ny) < r) return true
        }
        return false
    }

    private fun move(e: Entity, dx: Float, dy: Float) {
        if (!blocked(e.x + dx, e.y, R)) e.x += dx
        if (!blocked(e.x, e.y + dy, R)) e.y += dy
    }

    private fun los(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val d = hypot(x2 - x1, y2 - y1)
        val n = (d / 30f).toInt() + 1
        for (i in 1 until n) {
            val t = i / n.toFloat()
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            for (o in obstacles) if (o.contains(x, y)) return false
        }
        return true
    }

    private fun aliveCount(): Int = bots.count { it.alive } + (if (player.alive) 1 else 0)

    // ------------------------------------------------------------------ combat

    private fun nextWeapon(e: Entity) {
        val n = e.slots.size
        for (i in 1..n) {
            val idx = (e.cur + i) % n
            if (e.slots[idx].ammo != 0) {
                e.cur = idx
                return
            }
        }
    }

    private fun shoot(e: Entity) {
        if (e.cd > 0f) return
        val s = e.slots[e.cur]
        if (s.ammo == 0) {
            nextWeapon(e)
            return
        }
        val w = s.w
        e.cd = w.cooldown
        if (s.ammo > 0) s.ammo--
        repeat(w.pellets) {
            val a = e.angle + (rnd.nextFloat() - 0.5f) * 2f * w.spread
            val c = cos(a)
            val sn = sin(a)
            bullets.add(Bullet(e.x + c * 24f, e.y + sn * 24f, c * w.speed, sn * w.speed, w.dmg, w.range, e))
        }
        if (s.ammo == 0) nextWeapon(e)
    }

    private fun damage(e: Entity, amount: Float, by: Entity?) {
        if (!e.alive) return
        e.hp -= amount
        if (e.hp <= 0f) {
            e.hp = 0f
            e.alive = false
            if (by != null && by !== e) by.kills++
            for (s in e.slots) {
                if (s.w !== Weapons.PISTOL && s.ammo != 0) {
                    pickups.add(
                        Pickup(
                            e.x + (rnd.nextFloat() - 0.5f) * 30f,
                            e.y + (rnd.nextFloat() - 0.5f) * 30f,
                            0,
                            s.w
                        )
                    )
                }
            }
            if (e.isPlayer) {
                state = 2
                stateTime = 0f
                rank = bots.count { it.alive } + 1
            }
        }
    }

    private fun zoneDamage(e: Entity, dt: Float) {
        if (hypot(e.x - zc, e.y - zc) > zoneR) damage(e, zoneDps * dt, null)
    }

    private fun collect(e: Entity) {
        val iter = pickups.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (hypot(p.x - e.x, p.y - e.y) > 34f) continue
            var took = false
            when (p.type) {
                0 -> {
                    val w = p.weapon!!
                    val s = e.slots.find { it.w === w }
                    if (s != null) {
                        s.ammo += w.ammo
                    } else {
                        e.slots.add(Slot(w, w.ammo))
                        e.cur = e.slots.size - 1
                    }
                    took = true
                }
                1 -> if (e.hp < 100f) {
                    e.hp = min(100f, e.hp + 35f)
                    took = true
                }
                2 -> {
                    for (s in e.slots) {
                        if (s.ammo >= 0) {
                            s.ammo += max(5, s.w.ammo / 3)
                            took = true
                        }
                    }
                }
            }
            if (took) iter.remove()
        }
    }

    private fun bulletHit(b: Bullet): Boolean {
        if (b.left <= 0f || b.x < 0f || b.y < 0f || b.x > WORLD || b.y > WORLD) return true
        for (o in obstacles) if (o.contains(b.x, b.y)) return true
        if (player.alive && player !== b.owner && hypot(player.x - b.x, player.y - b.y) < R + 3f) {
            damage(player, b.dmg, b.owner)
            return true
        }
        for (e in bots) {
            if (e.alive && e !== b.owner && hypot(e.x - b.x, e.y - b.y) < R + 3f) {
                damage(e, b.dmg, b.owner)
                return true
            }
        }
        return false
    }

    private fun updateBullets(dt: Float) {
        val bi = bullets.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            val spd = hypot(b.vx, b.vy)
            val n = (spd * dt / 18f).toInt() + 1
            val sdt = dt / n
            var dead = false
            for (i in 0 until n) {
                b.x += b.vx * sdt
                b.y += b.vy * sdt
                b.left -= spd * sdt
                if (bulletHit(b)) {
                    dead = true
                    break
                }
            }
            if (dead) bi.remove()
        }
    }

    // ------------------------------------------------------------------ bots

    private fun wanted(b: Entity, p: Pickup): Boolean = when (p.type) {
        0 -> true
        1 -> b.hp < 100f
        else -> b.slots.any { it.ammo >= 0 }
    }

    private fun pickWander(b: Entity) {
        var best: Pickup? = null
        var bd = 700f
        for (p in pickups) {
            if (!wanted(b, p)) continue
            val d = hypot(p.x - b.x, p.y - b.y)
            if (d < bd) {
                bd = d
                best = p
            }
        }
        if (best != null && rnd.nextFloat() < 0.7f) {
            b.tx = best.x
            b.ty = best.y
        } else {
            val a = rnd.nextFloat() * 6.2831f
            val r = rnd.nextFloat() * zoneR * 0.8f
            b.tx = zc + cos(a) * r
            b.ty = zc + sin(a) * r
        }
    }

    private fun pickTarget(b: Entity) {
        var best: Entity? = null
        var bd = 520f
        fun consider(e: Entity) {
            if (e === b || !e.alive) return
            val d = hypot(e.x - b.x, e.y - b.y)
            if (d < bd && los(b.x, b.y, e.x, e.y)) {
                bd = d
                best = e
            }
        }
        consider(player)
        for (o in bots) consider(o)
        b.target = best
    }

    private fun moveToward(b: Entity, tx: Float, ty: Float, sp: Float, dt: Float) {
        val dx = tx - b.x
        val dy = ty - b.y
        val d = hypot(dx, dy)
        if (d < 1f) return
        val vx = dx / d * sp * dt
        val vy = dy / d * sp * dt
        val ox = b.x
        val oy = b.y
        move(b, vx, vy)
        if (hypot(b.x - ox, b.y - oy) < 0.3f * sp * dt) {
            move(b, -vy * 1.5f, vx * 1.5f)
            b.think = 0f
        }
        if (b.target == null) b.angle = atan2(dy, dx)
    }

    private fun aimAndShoot(b: Entity, t: Entity) {
        val d = hypot(t.x - b.x, t.y - b.y)
        b.angle = atan2(t.y - b.y, t.x - b.x) + (rnd.nextFloat() - 0.5f) * 0.2f
        if (d < b.slots[b.cur].w.range * 0.9f) shoot(b)
    }

    private fun botAI(b: Entity, dt: Float) {
        b.cd = max(0f, b.cd - dt)
        b.think -= dt
        if (b.think <= 0f) {
            b.think = 0.25f + rnd.nextFloat() * 0.25f
            pickTarget(b)
            if (b.target == null) pickWander(b)
        }
        val sp = 150f
        val t = b.target
        val dz = hypot(zc - b.x, zc - b.y)

        if (dz > zoneR - 90f) {
            moveToward(b, zc, zc, sp * 1.15f, dt)
            if (t != null && t.alive) aimAndShoot(b, t)
            return
        }
        if (t != null && t.alive) {
            val dx = t.x - b.x
            val dy = t.y - b.y
            val d = hypot(dx, dy)
            aimAndShoot(b, t)
            if (d > 280f) {
                moveToward(b, t.x, t.y, sp, dt)
            } else if (d < 140f) {
                moveToward(b, b.x - dx, b.y - dy, sp * 0.8f, dt)
            } else if (d > 1f) {
                val dir = if (b.hashCode() % 2 == 0) 1f else -1f
                move(b, -dy / d * sp * 0.6f * dt * dir, dx / d * sp * 0.6f * dt * dir)
            }
        } else {
            if (hypot(b.tx - b.x, b.ty - b.y) > 20f) {
                moveToward(b, b.tx, b.ty, sp * 0.8f, dt)
            } else {
                b.think = 0f
            }
        }
    }

    // ------------------------------------------------------------------ update

    private fun updateZone(dt: Float) {
        if (phase >= phases.size) {
            zoneR = phases.last().endR
            zoneDps = phases.last().dps
            return
        }
        val p = phases[phase]
        phaseTimer += dt
        zoneDps = p.dps
        if (phaseTimer < p.wait) {
            zoneR = zoneStartR
        } else if (phaseTimer < p.wait + p.shrink) {
            zoneR = zoneStartR + (p.endR - zoneStartR) * ((phaseTimer - p.wait) / p.shrink)
        } else {
            zoneStartR = p.endR
            zoneR = p.endR
            phase++
            phaseTimer = 0f
        }
    }

    private fun update(dt: Float) {
        stateTime += dt
        if (switchRequested) {
            switchRequested = false
            if (player.alive) nextWeapon(player)
        }
        updateZone(dt)

        if (player.alive) {
            player.cd = max(0f, player.cd - dt)
            if (hypot(mvx, mvy) > 0.05f) {
                move(player, mvx * 230f * dt, mvy * 230f * dt)
            }
            val al = hypot(avx, avy)
            if (al > 0.15f) player.angle = atan2(avy, avx)
            if (al > 0.5f) shoot(player)
            collect(player)
            zoneDamage(player, dt)
        }
        for (b in bots) {
            if (b.alive) {
                botAI(b, dt)
                collect(b)
                zoneDamage(b, dt)
            }
        }
        updateBullets(dt)

        if (state == 0 && player.alive && bots.none { it.alive }) {
            state = 1
            stateTime = 0f
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
    }

    private fun drawEntity(c: Canvas, e: Entity) {
        fill(if (e.isPlayer) Color.rgb(40, 180, 230) else e.color)
        c.drawCircle(e.x, e.y, R, paint)
        stroke(if (e.isPlayer) Color.WHITE else Color.argb(160, 0, 0, 0), 3f)
        c.drawCircle(e.x, e.y, R, paint)

        stroke(e.slots[e.cur].w.color, 7f)
        paint.strokeCap = Paint.Cap.ROUND
        val ca = cos(e.angle)
        val sa = sin(e.angle)
        c.drawLine(e.x + ca * 8f, e.y + sa * 8f, e.x + ca * (R + 12f), e.y + sa * (R + 12f), paint)
        paint.strokeCap = Paint.Cap.BUTT

        if (!e.isPlayer && e.hp < 100f) {
            fill(Color.argb(180, 0, 0, 0))
            c.drawRect(e.x - 20f, e.y - R - 14f, e.x + 20f, e.y - R - 8f, paint)
            fill(Color.rgb(230, 60, 60))
            c.drawRect(e.x - 20f, e.y - R - 14f, e.x - 20f + 40f * e.hp / 100f, e.y - R - 8f, paint)
        }
    }

    private fun render(c: Canvas) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        c.drawColor(Color.rgb(20, 30, 20))

        val zoom = min(w, h) / 650f
        val camX = player.x
        val camY = player.y

        c.save()
        c.translate(w / 2f, h / 2f)
        c.scale(zoom, zoom)
        c.translate(-camX, -camY)

        // floor
        fill(Color.rgb(62, 95, 58))
        c.drawRect(0f, 0f, WORLD, WORLD, paint)

        // grid
        stroke(Color.argb(40, 0, 0, 0), 2f)
        val x0 = max(0f, camX - w / 2f / zoom)
        val x1 = min(WORLD, camX + w / 2f / zoom)
        val y0 = max(0f, camY - h / 2f / zoom)
        val y1 = min(WORLD, camY + h / 2f / zoom)
        var gx = (x0 / 200f).toInt() * 200f
        while (gx <= x1) {
            c.drawLine(gx, y0, gx, y1, paint)
            gx += 200f
        }
        var gy = (y0 / 200f).toInt() * 200f
        while (gy <= y1) {
            c.drawLine(x0, gy, x1, gy, paint)
            gy += 200f
        }
        stroke(Color.rgb(120, 40, 40), 10f)
        c.drawRect(0f, 0f, WORLD, WORLD, paint)

        // danger zone
        zonePath.reset()
        zonePath.fillType = Path.FillType.EVEN_ODD
        zonePath.addRect(-2000f, -2000f, WORLD + 2000f, WORLD + 2000f, Path.Direction.CW)
        zonePath.addCircle(zc, zc, zoneR, Path.Direction.CW)
        fill(Color.argb(90, 220, 30, 30))
        c.drawPath(zonePath, paint)
        stroke(Color.argb(230, 90, 170, 255), 4f)
        c.drawCircle(zc, zc, zoneR, paint)

        // pickups
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f
        text.color = Color.WHITE
        for (p in pickups) {
            when (p.type) {
                0 -> {
                    val wp = p.weapon!!
                    fill(wp.color)
                    c.drawRoundRect(p.x - 14f, p.y - 8f, p.x + 14f, p.y + 8f, 4f, 4f, paint)
                    stroke(Color.WHITE, 2f)
                    c.drawRoundRect(p.x - 14f, p.y - 8f, p.x + 14f, p.y + 8f, 4f, 4f, paint)
                    c.drawText(wp.name, p.x, p.y - 14f, text)
                }
                1 -> {
                    fill(Color.rgb(46, 160, 70))
                    c.drawCircle(p.x, p.y, 11f, paint)
                    stroke(Color.WHITE, 3f)
                    c.drawLine(p.x - 6f, p.y, p.x + 6f, p.y, paint)
                    c.drawLine(p.x, p.y - 6f, p.x, p.y + 6f, paint)
                }
                else -> {
                    fill(Color.rgb(255, 190, 40))
                    c.drawCircle(p.x, p.y, 9f, paint)
                    stroke(Color.rgb(120, 80, 0), 2f)
                    c.drawCircle(p.x, p.y, 9f, paint)
                }
            }
        }

        // obstacles
        for (o in obstacles) {
            if (o.right < x0 || o.left > x1 || o.bottom < y0 || o.top > y1) continue
            fill(Color.rgb(93, 74, 54))
            c.drawRect(o, paint)
            stroke(Color.rgb(60, 45, 30), 4f)
            c.drawRect(o, paint)
        }

        // bullets
        stroke(Color.rgb(255, 235, 59), 3f)
        for (b in bullets) {
            c.drawLine(b.x, b.y, b.x - b.vx * 0.02f, b.y - b.vy * 0.02f, paint)
        }

        // entities
        for (b in bots) if (b.alive) drawEntity(c, b)
        if (player.alive) drawEntity(c, player)

        c.restore()
        drawHud(c, w, h)
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        // minimap
        val ms = 120f * u
        val mx = 12f * u
        val my = 12f * u
        fill(Color.argb(110, 0, 0, 0))
        c.drawRect(mx, my, mx + ms, my + ms, paint)
        stroke(Color.argb(230, 90, 170, 255), 2f * u)
        c.drawCircle(mx + zc / WORLD * ms, my + zc / WORLD * ms, zoneR / WORLD * ms, paint)
        fill(Color.CYAN)
        c.drawCircle(mx + player.x / WORLD * ms, my + player.y / WORLD * ms, 3f * u, paint)

        // top info
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f * u
        c.drawText("الأحياء: ${aliveCount()}     القتلى: ${player.kills}", w / 2f, 28f * u, text)

        text.textSize = 15f * u
        val msg = if (phase < phases.size) {
            val p = phases[phase]
            if (phaseTimer < p.wait) "الدائرة تضيق بعد ${ceil(p.wait - phaseTimer).toInt()} ث"
            else "الدائرة تضيق الآن!"
        } else {
            "الدائرة الأخيرة"
        }
        c.drawText(msg, w / 2f, 52f * u, text)
        if (player.alive && hypot(player.x - zc, player.y - zc) > zoneR) {
            text.color = Color.rgb(255, 90, 90)
            c.drawText("أنت خارج الدائرة الآمنة! ادخل بسرعة", w / 2f, 76f * u, text)
            text.color = Color.WHITE
        }

        // weapon info
        val s = player.slots[player.cur]
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 18f * u
        c.drawText("${s.w.name}   ${if (s.ammo < 0) "∞" else s.ammo.toString()}", w - 14f * u, 30f * u, text)

        // switch button
        stroke(Color.argb(200, 255, 255, 255), 2f * u)
        c.drawCircle(w - 70f * u, 110f * u, 36f * u, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f * u
        c.drawText("تبديل", w - 70f * u, 115f * u, text)

        // health bar
        val bw = 220f * u
        val bx = w / 2f - bw / 2f
        val by = h - 30f * u
        fill(Color.argb(150, 0, 0, 0))
        c.drawRect(bx, by, bx + bw, by + 14f * u, paint)
        fill(Color.rgb(70, 200, 90))
        c.drawRect(bx, by, bx + bw * player.hp / 100f, by + 14f * u, paint)

        // sticks
        drawStick(c, moveId != -1, mbx, mby, mvx, mvy, 130f * u, h - 120f * u, Color.WHITE)
        drawStick(c, aimId != -1, abx, aby, avx, avy, w - 130f * u, h - 120f * u, Color.rgb(255, 120, 100))

        // end overlay
        if (state != 0) {
            fill(Color.argb(170, 0, 0, 0))
            c.drawRect(0f, 0f, w, h, paint)
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            text.textSize = 40f * u
            c.drawText(if (state == 1) "فزت بالمباراة!" else "خسرت - المركز $rank", w / 2f, h / 2f - 10f * u, text)
            text.textSize = 20f * u
            c.drawText("القتلى: ${player.kills}", w / 2f, h / 2f + 28f * u, text)
            if (stateTime > 1.5f) {
                c.drawText("المس الشاشة للعب من جديد", w / 2f, h / 2f + 64f * u, text)
            }
        }
    }

    private fun drawStick(
        c: Canvas, active: Boolean, bx: Float, by: Float,
        vx: Float, vy: Float, hx: Float, hy: Float, col: Int
    ) {
        val a = if (active) 200 else 70
        val cxp = if (active) bx else hx
        val cyp = if (active) by else hy
        stroke(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)), 3f * u)
        c.drawCircle(cxp, cyp, stickR, paint)
        fill(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)))
        c.drawCircle(cxp + vx * stickR, cyp + vy * stickR, 20f * u, paint)
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                if (state != 0) {
                    if (stateTime > 1.5f) restartRequested = true
                    return true
                }
                if (hypot(x - (w - 70f * u), y - 110f * u) < 44f * u) {
                    switchRequested = true
                } else if (x < w / 2f) {
                    if (moveId == -1) {
                        moveId = id; mbx = x; mby = y; mvx = 0f; mvy = 0f
                    }
                } else {
                    if (aimId == -1) {
                        aimId = id; abx = x; aby = y; avx = 0f; avy = 0f
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    if (id == moveId) {
                        var dx = (ev.getX(i) - mbx) / stickR
                        var dy = (ev.getY(i) - mby) / stickR
                        val l = hypot(dx, dy)
                        if (l > 1f) { dx /= l; dy /= l }
                        mvx = dx; mvy = dy
                    } else if (id == aimId) {
                        var dx = (ev.getX(i) - abx) / stickR
                        var dy = (ev.getY(i) - aby) / stickR
                        val l = hypot(dx, dy)
                        if (l > 1f) { dx /= l; dy /= l }
                        avx = dx; avy = dy
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == moveId) { moveId = -1; mvx = 0f; mvy = 0f }
                if (id == aimId) { aimId = -1; avx = 0f; avy = 0f }
            }
            MotionEvent.ACTION_CANCEL -> {
                moveId = -1; mvx = 0f; mvy = 0f
                aimId = -1; avx = 0f; avy = 0f
            }
        }
        return true
    }

    // ------------------------------------------------------------------ loop

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            thread?.join()
        } catch (e: InterruptedException) {
        }
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min(0.033f, (now - last) / 1e9f)
            last = now
            if (restartRequested) {
                restartRequested = false
                newGame()
            }
            update(dt)
            val c: Canvas? = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                null
            }
            if (c != null) {
                try {
                    render(c)
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
            val spent = (System.nanoTime() - now) / 1_000_000L
            if (spent < 16L) {
                try {
                    Thread.sleep(16L - spent)
                } catch (e: InterruptedException) {
                }
            }
        }
    }
}
