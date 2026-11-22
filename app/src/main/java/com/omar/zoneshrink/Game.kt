package com.omar.zoneshrink

import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Game logic. World is a flat plane: (x, y) here maps to (x, z) in 3D. Units are meters.
 */
class Game {

    companion object {
        const val WORLD = 200f
        const val R = 0.5f
        const val HITR = 0.65f
        const val BOT_COUNT = 20
    }

    val zc = WORLD / 2f
    private val rnd = Random(System.currentTimeMillis())

    val obstacles = ArrayList<Obstacle>()
    val bullets = ArrayList<Bullet>()
    val pickups = ArrayList<Pickup>()
    val bots = ArrayList<Entity>()
    lateinit var player: Entity

    // 0 = playing, 1 = won, 2 = lost
    @Volatile var state = 0
    @Volatile var stateTime = 0f
    @Volatile var rank = 0
    @Volatile var aliveBots = 0
    @Volatile var hitFlash = 0f
    @Volatile var restartRequested = false
    @Volatile var switchRequested = false
    @Volatile var firing = false
    @Volatile var mvx = 0f
    @Volatile var mvy = 0f
    @Volatile var camYaw = 0f
    @Volatile var camPitch = 0.22f
    @Volatile var zoneR = 98f
    var time = 0f

    private val phases = listOf(
        Phase(15f, 20f, 72f, 2f),
        Phase(15f, 20f, 49f, 4f),
        Phase(12f, 18f, 29f, 7f),
        Phase(10f, 15f, 15f, 10f),
        Phase(8f, 15f, 4f, 14f)
    )
    private var phase = 0
    private var phaseTimer = 0f
    private var zoneStartR = 98f
    private var zoneDps = 2f

    private val wallColors = intArrayOf(
        Color.rgb(176, 168, 154),
        Color.rgb(154, 165, 174),
        Color.rgb(194, 178, 128),
        Color.rgb(161, 136, 127),
        Color.rgb(141, 155, 168)
    )
    private val crateColors = intArrayOf(
        Color.rgb(190, 80, 60),
        Color.rgb(60, 110, 170),
        Color.rgb(210, 150, 50),
        Color.rgb(70, 130, 90)
    )

    init {
        newGame()
    }

    fun aliveCount(): Int = aliveBots + (if (player.alive) 1 else 0)

    fun zoneMessage(): String {
        if (phase < phases.size) {
            val p = phases[phase]
            return if (phaseTimer < p.wait) {
                "الدائرة تضيق بعد ${ceil(p.wait - phaseTimer).toInt()} ث"
            } else {
                "الدائرة تضيق الآن!"
            }
        }
        return "الدائرة الأخيرة"
    }

    // ------------------------------------------------------------------ setup

    fun newGame() {
        obstacles.clear()
        bullets.clear()
        pickups.clear()
        bots.clear()

        repeat(26) {
            val w = 5f + rnd.nextFloat() * 6f
            val d = 5f + rnd.nextFloat() * 6f
            val h = 3f + rnd.nextFloat() * 2.5f
            val x = 8f + rnd.nextFloat() * (WORLD - 16f - w)
            val y = 8f + rnd.nextFloat() * (WORLD - 16f - d)
            obstacles.add(Obstacle(RectF(x, y, x + w, y + d), h, 0, wallColors[rnd.nextInt(wallColors.size)]))
        }
        repeat(36) {
            val w = 1.5f + rnd.nextFloat() * 1.5f
            val d = 1.5f + rnd.nextFloat() * 1.5f
            val h = 1.2f + rnd.nextFloat() * 1.4f
            val x = 5f + rnd.nextFloat() * (WORLD - 10f - w)
            val y = 5f + rnd.nextFloat() * (WORLD - 10f - d)
            obstacles.add(Obstacle(RectF(x, y, x + w, y + d), h, 2, crateColors[rnd.nextInt(crateColors.size)]))
        }
        var trees = 0
        var tries = 0
        while (trees < 110 && tries < 800) {
            tries++
            val x = 3f + rnd.nextFloat() * (WORLD - 6f)
            val y = 3f + rnd.nextFloat() * (WORLD - 6f)
            val r = RectF(x - 0.35f, y - 0.35f, x + 0.35f, y + 0.35f)
            if (overlapsAny(r)) continue
            obstacles.add(Obstacle(r, 5f + rnd.nextFloat() * 3f, 1, 0))
            trees++
        }

        player = Entity(zc, zc, true, Color.rgb(40, 150, 230))
        place(player, null)

        repeat(BOT_COUNT) {
            val b = Entity(zc, zc, false, Color.rgb(190, 50 + rnd.nextInt(70), 50))
            place(b, player)
            if (rnd.nextFloat() < 0.4f) {
                val w = Weapons.ALL.random(rnd)
                b.slots.add(Slot(w, w.ammo))
                b.cur = 1
            }
            b.angle = rnd.nextFloat() * 6.28f
            b.think = rnd.nextFloat() * 0.5f
            bots.add(b)
        }

        repeat(60) {
            for (t in 0 until 50) {
                val x = 5f + rnd.nextFloat() * (WORLD - 10f)
                val y = 5f + rnd.nextFloat() * (WORLD - 10f)
                if (blocked(x, y, 1f)) continue
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
        zoneStartR = 98f
        zoneR = 98f
        zoneDps = phases[0].dps
        state = 0
        stateTime = 0f
        rank = 0
        aliveBots = bots.size
        hitFlash = 0f
        camYaw = 0f
        camPitch = 0.22f
        firing = false
    }

    private fun overlapsAny(r: RectF): Boolean {
        for (o in obstacles) if (RectF.intersects(o.r, r)) return true
        return false
    }

    private fun place(e: Entity, avoid: Entity?) {
        for (i in 0 until 300) {
            val a = rnd.nextFloat() * 6.2831f
            val r = sqrt(rnd.nextFloat()) * 85f
            val x = zc + cos(a) * r
            val y = zc + sin(a) * r
            if (blocked(x, y, R + 0.6f)) continue
            if (avoid != null && hypot(x - avoid.x, y - avoid.y) < 40f) continue
            e.x = x
            e.y = y
            e.tx = x
            e.ty = y
            return
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun blocked(x: Float, y: Float, r: Float): Boolean {
        if (x < r || y < r || x > WORLD - r || y > WORLD - r) return true
        for (o in obstacles) {
            val nx = x.coerceIn(o.r.left, o.r.right)
            val ny = y.coerceIn(o.r.top, o.r.bottom)
            if (hypot(x - nx, y - ny) < r) return true
        }
        return false
    }

    private fun move(e: Entity, dx: Float, dy: Float) {
        val ox = e.x
        val oy = e.y
        if (!blocked(e.x + dx, e.y, R)) e.x += dx
        if (!blocked(e.x, e.y + dy, R)) e.y += dy
        val d = hypot(e.x - ox, e.y - oy)
        e.movedFrame += d
        e.walk += d * 4f
    }

    private fun updateStride(e: Entity, dt: Float) {
        val target = min(1f, e.movedFrame / (dt * 3f + 0.0001f))
        e.stride += (target - e.stride) * min(1f, dt * 10f)
    }

    private fun los(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val d = hypot(x2 - x1, y2 - y1)
        val n = (d / 2f).toInt() + 1
        for (i in 1 until n) {
            val t = i / n.toFloat()
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            for (o in obstacles) if (o.r.contains(x, y)) return false
        }
        return true
    }

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
        e.flash = 0.07f
        if (s.ammo > 0) s.ammo--
        repeat(w.pellets) {
            val a = e.angle + (rnd.nextFloat() - 0.5f) * 2f * w.spread
            val c = cos(a)
            val sn = sin(a)
            bullets.add(Bullet(e.x + c * 0.9f, e.y + sn * 0.9f, c * w.speed, sn * w.speed, w.dmg, w.range, e))
        }
        if (s.ammo == 0) nextWeapon(e)
    }

    private fun damage(e: Entity, amount: Float, by: Entity?) {
        if (!e.alive) return
        e.hp -= amount
        if (e.isPlayer) hitFlash = 0.35f
        if (e.hp <= 0f) {
            e.hp = 0f
            e.alive = false
            if (by != null && by !== e) by.kills++
            for (s in e.slots) {
                if (s.w !== Weapons.PISTOL && s.ammo != 0) {
                    pickups.add(
                        Pickup(
                            e.x + (rnd.nextFloat() - 0.5f) * 1.5f,
                            e.y + (rnd.nextFloat() - 0.5f) * 1.5f,
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
            if (hypot(p.x - e.x, p.y - e.y) > 1.8f) continue
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
        for (o in obstacles) if (o.r.contains(b.x, b.y)) return true
        val p = player
        if (p.alive && p !== b.owner && hypot(p.x - b.x, p.y - b.y) < HITR) {
            damage(p, b.dmg, b.owner)
            return true
        }
        for (e in bots) {
            if (e.alive && e !== b.owner && hypot(e.x - b.x, e.y - b.y) < HITR) {
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
            val n = (spd * dt / 0.5f).toInt() + 1
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

    // light aim assist for the player while firing
    private fun assist() {
        val p = player
        val w = p.slots[p.cur].w
        var best: Entity? = null
        var bd = Float.MAX_VALUE
        for (b in bots) {
            if (!b.alive) continue
            val dx = b.x - p.x
            val dy = b.y - p.y
            val d = hypot(dx, dy)
            if (d > w.range * 0.95f || d >= bd) continue
            val a = atan2(dy, dx)
            val diff = atan2(sin(a - camYaw), cos(a - camYaw))
            if (abs(diff) < 0.12f && los(p.x, p.y, b.x, b.y)) {
                bd = d
                best = b
            }
        }
        if (best != null) p.angle = atan2(best.y - p.y, best.x - p.x)
    }

    // ------------------------------------------------------------------ bots

    private fun wanted(b: Entity, p: Pickup): Boolean = when (p.type) {
        0 -> true
        1 -> b.hp < 100f
        else -> b.slots.any { it.ammo >= 0 }
    }

    private fun pickWander(b: Entity) {
        var best: Pickup? = null
        var bd = 45f
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
        var bd = 45f
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
        if (d < 0.1f) return
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
        b.angle = atan2(t.y - b.y, t.x - b.x) + (rnd.nextFloat() - 0.5f) * 0.10f
        if (d < b.slots[b.cur].w.range * 0.9f) shoot(b)
    }

    private fun botAI(b: Entity, dt: Float) {
        b.think -= dt
        if (b.think <= 0f) {
            b.think = 0.25f + rnd.nextFloat() * 0.25f
            pickTarget(b)
            if (b.target == null) pickWander(b)
        }
        val sp = 4.5f
        val t = b.target
        val dz = hypot(zc - b.x, zc - b.y)

        if (dz > zoneR - 6f) {
            moveToward(b, zc, zc, sp * 1.15f, dt)
            if (t != null && t.alive) aimAndShoot(b, t)
            return
        }
        if (t != null && t.alive) {
            val dx = t.x - b.x
            val dy = t.y - b.y
            val d = hypot(dx, dy)
            aimAndShoot(b, t)
            if (d > 18f) {
                moveToward(b, t.x, t.y, sp, dt)
            } else if (d < 8f) {
                moveToward(b, b.x - dx, b.y - dy, sp * 0.8f, dt)
            } else if (d > 0.1f) {
                val dir = if (b.hashCode() % 2 == 0) 1f else -1f
                move(b, -dy / d * sp * 0.6f * dt * dir, dx / d * sp * 0.6f * dt * dir)
            }
        } else {
            if (hypot(b.tx - b.x, b.ty - b.y) > 1.2f) {
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

    fun update(dt: Float) {
        time += dt
        stateTime += dt
        if (hitFlash > 0f) hitFlash = max(0f, hitFlash - dt)

        if (restartRequested) {
            restartRequested = false
            newGame()
            return
        }
        if (switchRequested) {
            switchRequested = false
            if (player.alive) nextWeapon(player)
        }
        updateZone(dt)

        val p = player
        if (p.alive) {
            p.cd = max(0f, p.cd - dt)
            p.flash = max(0f, p.flash - dt)
            p.movedFrame = 0f
            if (hypot(mvx, mvy) > 0.05f) {
                val fx = cos(camYaw)
                val fz = sin(camYaw)
                val fwd = -mvy
                val side = mvx
                move(p, (fx * fwd - fz * side) * 6.5f * dt, (fz * fwd + fx * side) * 6.5f * dt)
            }
            p.angle = camYaw
            if (firing) {
                assist()
                shoot(p)
            }
            updateStride(p, dt)
            collect(p)
            zoneDamage(p, dt)
        }

        for (b in bots) {
            if (!b.alive) continue
            b.cd = max(0f, b.cd - dt)
            b.flash = max(0f, b.flash - dt)
            b.movedFrame = 0f
            botAI(b, dt)
            updateStride(b, dt)
            collect(b)
            zoneDamage(b, dt)
        }

        updateBullets(dt)

        aliveBots = bots.count { it.alive }
        if (state == 0 && p.alive && aliveBots == 0) {
            state = 1
            stateTime = 0f
        }
    }
}
