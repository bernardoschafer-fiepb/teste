package com.estudo.pokerassistant

import kotlin.random.Random

object Engine {
    const val RANKS = "23456789TJQKA"
    const val SUITS = "shdc"
    val FOLD_WORDS = listOf("fold", "desist", "correu", "correr")
    val CHECK_WORDS = listOf("check", "mesa", "passou", "passar")

    fun id(name: String): Int? {
        if (name.length != 2) return null
        val r = RANKS.indexOf(name[0]); val s = SUITS.indexOf(name[1])
        return if (r < 0 || s < 0) null else r * 4 + s
    }

    private fun straightHigh(mask: Int): Int {
        val m2 = (mask shl 1) or (if ((mask and (1 shl 12)) != 0) 1 else 0)
        for (h in 13 downTo 4) if (((m2 shr (h - 4)) and 31) == 31) return h - 1
        return -1
    }

    private fun pack(cat: Int, r: List<Int>): Long {
        var v = cat.toLong()
        for (i in 0 until 5) v = v * 16 + r.getOrElse(i) { 0 }
        return v
    }

    fun score(cards: IntArray, n: Int): Long {
        val rc = IntArray(13); val sc = IntArray(4); val sm = IntArray(4); var all = 0
        for (i in 0 until n) {
            val x = cards[i]; val r = x shr 2; val s = x and 3
            rc[r]++; sc[s]++; sm[s] = sm[s] or (1 shl r); all = all or (1 shl r)
        }
        var fm = 0
        for (s in 0..3) if (sc[s] >= 5) fm = sm[s]
        if (fm != 0) { val sh = straightHigh(fm); if (sh >= 0) return pack(8, listOf(sh)) }
        val groups = (12 downTo 0).filter { rc[it] > 0 }.sortedByDescending { rc[it] }
        fun kick(ex: List<Int>, k: Int) = (12 downTo 0).filter { rc[it] > 0 && it !in ex }.take(k)
        val top = groups[0]; val c0 = rc[top]
        if (c0 == 4) return pack(7, listOf(top) + kick(listOf(top), 1))
        if (c0 == 3 && groups.size > 1 && rc[groups[1]] >= 2) return pack(6, listOf(top, groups[1]))
        if (fm != 0) return pack(5, (12 downTo 0).filter { ((fm shr it) and 1) == 1 }.take(5))
        val sh = straightHigh(all)
        if (sh >= 0) return pack(4, listOf(sh))
        if (c0 == 3) return pack(3, listOf(top) + kick(listOf(top), 2))
        if (c0 == 2 && groups.size > 1 && rc[groups[1]] == 2) {
            val a = groups[0]; val b = groups[1]
            return pack(2, listOf(a, b) + kick(listOf(a, b), 1))
        }
        if (c0 == 2) return pack(1, listOf(top) + kick(listOf(top), 3))
        return pack(0, kick(emptyList(), 5))
    }

    fun equity(hero: IntArray, board: IntArray, opps: Int, sims: Int = 1500): Double {
        val used = BooleanArray(52)
        hero.forEach { used[it] = true }; board.forEach { used[it] = true }
        val rest = (0 until 52).filter { !used[it] }.toIntArray()
        val need = 2 * opps + 5 - board.size
        val rnd = Random(System.nanoTime())
        val buf = IntArray(7)
        var total = 0.0
        repeat(sims) {
            for (i in 0 until need) {
                val j = i + rnd.nextInt(rest.size - i)
                val t = rest[i]; rest[i] = rest[j]; rest[j] = t
            }
            buf[0] = hero[0]; buf[1] = hero[1]
            for (i in 0 until 5) buf[2 + i] = if (i < board.size) board[i] else rest[2 * opps + (i - board.size)]
            val h = score(buf, 7)
            var best = Long.MIN_VALUE
            for (o in 0 until opps) {
                buf[0] = rest[2 * o]; buf[1] = rest[2 * o + 1]
                val s = score(buf, 7)
                if (s > best) best = s
            }
            total += if (h > best) 1.0 else if (h == best) 0.5 else 0.0
        }
        return total / sims
    }

    fun suggest(hero: IntArray, board: IntArray, opps: Int, pot: Double, toCall: Double, stackBb: Double): Pair<String, Double> {
        if (board.isEmpty()) {
            val eq = equity(hero, board, 1)
            val act = when {
                stackBb <= 10 -> if (eq >= 0.52) "ALL-IN" else "FOLD"
                stackBb <= 20 -> if (eq >= 0.58) "RAISE / ALL-IN" else if (eq >= 0.54 && toCall > 0) "CALL" else "FOLD"
                else -> if (eq >= 0.60) "RAISE 2.2-3x" else if (eq >= 0.54) "CALL / LIMP" else "FOLD"
            }
            return Pair(act, eq)
        }
        val eq = equity(hero, board, opps)
        if (toCall <= 0) return Pair(if (eq >= 0.65) "APOSTAR 50-70% do pot" else "CHECK", eq)
        val need = if (pot + toCall > 0) toCall / (pot + toCall) else 0.33
        if (eq >= 0.75) return Pair("RAISE", eq)
        return Pair(if (eq >= need + 0.05) "CALL" else "FOLD", eq)
    }

    fun parseNum(t: String): Double? {
        var s = t.uppercase().replace('O', '0').replace(" ", "").filter { it.isDigit() || it in ".,KM" }
        var mult = 1.0
        if (s.endsWith("K")) { mult = 1e3; s = s.dropLast(1) } else if (s.endsWith("M")) { mult = 1e6; s = s.dropLast(1) }
        s = s.filter { it.isDigit() || it in ".," }
        s = if (mult == 1.0) s.replace(".", "").replace(",", "") else s.replace(",", ".")
        return s.toDoubleOrNull()?.times(mult)
    }

    fun parseAction(t: String): String? {
        val l = t.lowercase()
        if (FOLD_WORDS.any { l.contains(it) }) return "fold"
        if (CHECK_WORDS.any { l.contains(it) }) return "check"
        return null
    }
}
