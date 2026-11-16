package com.estudo.pokerassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.sqrt

data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

object Cfg {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun get(c: Context, key: String): Box? {
        val s = sp(c).getString(key, null) ?: return null
        val p = s.split(",").map { it.toInt() }
        return Box(p[0], p[1], p[2], p[3])
    }
    fun set(c: Context, key: String, b: Box) {
        sp(c).edit().putString(key, "${b.x},${b.y},${b.w},${b.h}").apply()
    }
    fun clearBoxes(c: Context) {
        val e = sp(c).edit()
        sp(c).all.keys.filter { it != "bb" }.forEach { e.remove(it) }
        e.apply()
    }
    fun bb(c: Context) = sp(c).getFloat("bb", 100f).toDouble()
    fun setBb(c: Context, v: Double) { sp(c).edit().putFloat("bb", v.toFloat()).apply() }
}

fun crop(b: Bitmap, r: Box): Bitmap {
    val x = r.x.coerceIn(0, b.width - 1); val y = r.y.coerceIn(0, b.height - 1)
    val w = r.w.coerceIn(1, b.width - x); val h = r.h.coerceIn(1, b.height - y)
    return Bitmap.createBitmap(b, x, y, w, h)
}

class Templates(ctx: Context) {
    private val dir = File(ctx.filesDir, "tpl").apply { mkdirs() }
    private val map = HashMap<String, FloatArray>()
    init {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".png")) BitmapFactory.decodeFile(f.path)?.let { map[f.name.removeSuffix(".png")] = vec(it) }
        }
    }
    fun count() = map.size

    private fun vec(b: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(b, 40, 56, true)
        val px = IntArray(40 * 56); s.getPixels(px, 0, 40, 0, 0, 40, 56)
        val v = FloatArray(px.size * 3); var i = 0
        for (p in px) { v[i++] = Color.red(p).toFloat(); v[i++] = Color.green(p).toFloat(); v[i++] = Color.blue(p).toFloat() }
        val mean = v.average().toFloat(); var n = 0f
        for (k in v.indices) { v[k] -= mean; n += v[k] * v[k] }
        val d = sqrt(n).let { if (it < 1e-3f) 1f else it }
        for (k in v.indices) v[k] /= d
        return v
    }

    fun save(name: String, b: Bitmap) {
        val s = Bitmap.createScaledBitmap(b, 40, 56, true)
        File(dir, "$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        map[name] = vec(s)
    }

    fun match(b: Bitmap): String? {
        val v = vec(b); var best = 0f; var name: String? = null
        for ((n, t) in map) {
            var d = 0f
            for (k in v.indices) d += v[k] * t[k]
            if (d > best) { best = d; name = n }
        }
        return if (best >= 0.80f) name else null
    }
}

object Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    fun text(bmp: Bitmap): String {
        return try {
            var b = bmp
            if (b.height < 64) {
                val f = (64 + b.height - 1) / b.height
                b = Bitmap.createScaledBitmap(b, b.width * f, b.height * f, true)
            }
            Tasks.await(rec.process(InputImage.fromBitmap(b, 0))).text.replace("\n", " ")
        } catch (e: Exception) { "" }
    }
}
