package com.omar.zoneshrink

class Weapon(
    val name: String,
    val dmg: Float,
    val cooldown: Float,
    val speed: Float,
    val range: Float,
    val spread: Float,
    val pellets: Int,
    val ammo: Int,
    val color: Int
)

object Weapons {
    val PISTOL = Weapon("مسدس", 18f, 0.40f, 800f, 550f, 0.04f, 1, -1, 0xFFBDBDBD.toInt())
    val SMG = Weapon("رشاش", 9f, 0.09f, 850f, 450f, 0.10f, 1, 90, 0xFFFFB300.toInt())
    val RIFLE = Weapon("بندقية", 17f, 0.15f, 1100f, 750f, 0.05f, 1, 60, 0xFF43A047.toInt())
    val SHOTGUN = Weapon("شوتغن", 11f, 0.80f, 800f, 300f, 0.22f, 6, 16, 0xFFE53935.toInt())
    val SNIPER = Weapon("قناصة", 70f, 1.20f, 2000f, 1400f, 0.005f, 1, 10, 0xFF8E24AA.toInt())

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
