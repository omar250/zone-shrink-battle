package com.omar.zoneshrink

import android.graphics.RectF

class Weapon(
    val name: String,
    val dmg: Float,
    val cooldown: Float,
    val speed: Float,
    val range: Float,
    val spread: Float,
    val pellets: Int,
    val ammo: Int,
    val color: Int,
    val length: Float
)

object Weapons {
    val PISTOL = Weapon("مسدس", 18f, 0.40f, 70f, 35f, 0.03f, 1, -1, 0xFFBDBDBD.toInt(), 0.3f)
    val SMG = Weapon("رشاش", 9f, 0.09f, 75f, 28f, 0.06f, 1, 90, 0xFFFFB300.toInt(), 0.5f)
    val RIFLE = Weapon("بندقية", 17f, 0.15f, 90f, 50f, 0.03f, 1, 60, 0xFF43A047.toInt(), 0.8f)
    val SHOTGUN = Weapon("شوتغن", 11f, 0.80f, 65f, 16f, 0.10f, 6, 16, 0xFFE53935.toInt(), 0.7f)
    val SNIPER = Weapon("قناصة", 70f, 1.20f, 160f, 110f, 0.003f, 1, 10, 0xFF8E24AA.toInt(), 1.0f)

    val ALL = listOf(SMG, RIFLE, SHOTGUN, SNIPER)
}

class Slot(val w: Weapon, var ammo: Int)

class Entity(
    var x: Float,
    var y: Float,
    val isPlayer: Boolean,
    val color: Int
) {
    var hp = 100f
    var angle = 0f
    var alive = true
    var cd = 0f
    var kills = 0
    val slots = mutableListOf(Slot(Weapons.PISTOL, -1))
    var cur = 0

    // animation
    var walk = 0f
    var stride = 0f
    var movedFrame = 0f
    var flash = 0f

    // bot state
    var tx = x
    var ty = y
    var think = 0f
    var target: Entity? = null
}

class Bullet(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val dmg: Float,
    var left: Float,
    val owner: Entity
)

// type: 0 = weapon, 1 = health, 2 = ammo
class Pickup(val x: Float, val y: Float, val type: Int, val weapon: Weapon?)

// kind: 0 = building, 1 = tree, 2 = crate
class Obstacle(val r: RectF, val h: Float, val kind: Int, val color: Int)

class Phase(val wait: Float, val shrink: Float, val endR: Float, val dps: Float)
