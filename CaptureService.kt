package com.estudo.pokerassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var mp: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var frame: Bitmap? = null
    @Volatile private var busy = false
    private var W = 0
    private var H = 0
    private lateinit var tpl: Templates

    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var actionView: TextView
    private lateinit var infoView: TextView

    private val folded = HashSet<Int>()
    private var lastKey: String? = null
    private val ocrCache = HashMap<String, Pair<Int, String>>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ch", "Assistente", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ch").setContentTitle("Assistente ativo")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tpl = Templates(this)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mp = mpm.getMediaProjection(code, data)
        mp!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        W = dm.widthPixels; H = dm.heightPixels
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vd = mp!!.createVirtualDisplay("cap", W, H, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)

        buildPanel()
        exec.scheduleWithFixedDelay({
            try { tick() } catch (t: Throwable) { show("Erro", t.message ?: t.toString()) }
        }, 1, 1, TimeUnit.SECONDS)
        return START_NOT_STICKY
    }

    // ---------- janela flutuante ----------
    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 11f; isAllCaps = false
        setPadding(12, 4, 12, 4); minWidth = 0; minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xCC000000.toInt()); setPadding(16, 8, 16, 8) }
        actionView = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); text = "Calibre as areas"; gravity = Gravity.CENTER }
        infoView = TextView(this).apply { textSize = 11f; setTextColor(0xFFCCCCCC.toInt()); gravity = Gravity.CENTER }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Calibrar") { startCalibration() })
        row.addView(btn("Ensinar") { startLearn() })
        row.addView(btn("BB÷2") { Cfg.setBb(this, Cfg.bb(this) / 2); lastKey = null })
        row.addView(btn("BB×2") { Cfg.setBb(this, Cfg.bb(this) * 2); lastKey = null })
        row.addView(btn("Sair") { stopSelf() })
        panel.addView(actionView); panel.addView(infoView); panel.addView(row)
        panelLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        panelLp.gravity = Gravity.TOP or Gravity.START; panelLp.x = 0; panelLp.y = 0
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        actionView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = panelLp.x; py = panelLp.y }
                MotionEvent.ACTION_MOVE -> {
                    panelLp.x = px + (e.rawX - sx).toInt(); panelLp.y = py + (e.rawY - sy).toInt()
                    wm.updateViewLayout(panel, panelLp)
                }
            }
            true
        }
        wm.addView(panel, panelLp)
    }

    private fun show(action: String, info: String) {
        ui.post { if (::actionView.isInitialized) { actionView.text = action; infoView.text = info } }
    }

    // ---------- calibracao ----------
    inner class CalibView(ctx: Context, private val items: List<Pair<String, String>>, private val onEnd: () -> Unit) : FrameLayout(ctx) {
        private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f; private var has = false
        private var idx = 0
        private val rectPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val textPaint = Paint().apply { color = Color.WHITE; textSize = 48f }
        init {
            setWillNotDraw(false); setBackgroundColor(0x44000000)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC000000.toInt()) }
            row.addView(btn("Confirmar") { confirm() })
            row.addView(btn("Pular") { idx++; reset() })
            row.addView(btn("Terminar") { onEnd() })
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        private fun reset() { has = false; if (idx >= items.size) onEnd() else invalidate() }
        private fun confirm() {
            if (has && abs(ex - sx) > 4 && abs(ey - sy) > 4) {
                Cfg.set(this@CaptureService, items[idx].first,
                    Box(min(sx, ex).toInt(), min(sy, ey).toInt(), abs(ex - sx).toInt(), abs(ey - sy).toInt()))
            }
            idx++; reset()
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ex = sx; ey = sy; has = true }
                else -> { ex = e.rawX; ey = e.rawY }
            }
            invalidate(); return true
        }
        override fun onDraw(c: Canvas) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            if (has) c.drawRect(min(sx, ex) - loc[0], min(sy, ey) - loc[1], maxOf(sx, ex) - loc[0], maxOf(sy, ey) - loc[1], rectPaint)
            if (idx < items.size) c.drawText(items[idx].second, 40f, 100f, textPaint)
        }
    }

    private fun fullscreen(v: View): WindowManager.LayoutParams {
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, lp); return lp
    }

    private fun startCalibration() {
        busy = true; panel.visibility = View.GONE; Cfg.clearBoxes(this); Cfg.setBb(this, Cfg.bb(this))
        val items = ArrayList<Pair<String, String>>()
        items += "h1" to "Arraste sobre SUA carta 1"; items += "h2" to "Arraste sobre SUA carta 2"
        for (i in 1..5) items += "b$i" to "Board carta $i (Pular se nao existir)"
        items += "pot" to "Valor do POT"; items += "stack" to "SEU stack"; items += "hb" to "SUA aposta atual"
        for (i in 1..7) {
            items += "s${i}b" to "Adversario $i: valor da APOSTA"
            items += "s${i}a" to "Adversario $i: texto de ACAO (fold/check)"
        }
        lateinit var view: CalibView
        view = CalibView(this, items) {
            wm.removeView(view); panel.visibility = View.VISIBLE
            lastKey = null; folded.clear(); busy = false
        }
        fullscreen(view)
    }

    // ---------- ensinar cartas ----------
    private fun startLearn() {
        val snap = frame ?: return
        val keys = listOf("h1", "h2", "b1", "b2", "b3", "b4", "b5").filter { Cfg.get(this, it) != null }
        if (keys.isEmpty()) { show("Calibre primeiro", ""); return }
        busy = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xEE000000.toInt()); setPadding(24, 24, 24, 24) }
        val img = ImageView(this).apply { minimumHeight = 220 }
        val prompt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        root.addView(img, LinearLayout.LayoutParams(300, 220)); root.addView(prompt)
        var idx = 0; var rank: Char? = null
        fun close() { wm.removeView(root); busy = false; lastKey = null
            show("Templates: ${tpl.count()}/52", "") }
        fun load() {
            rank = null
            if (idx >= keys.size) { close(); return }
            img.setImageBitmap(crop(snap, Cfg.get(this, keys[idx])!!))
            prompt.text = "Carta ${keys[idx]}: toque no VALOR e depois no NAIPE (${tpl.count()}/52)"
        }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Engine.RANKS.forEachIndexed { i, ch -> (if (i < 7) r1 else r2).addView(btn(ch.toString()) { rank = ch }) }
        listOf("♠" to 's', "♥" to 'h', "♦" to 'd', "♣" to 'c').forEach { (sym, s) ->
            r3.addView(btn(sym) {
                val rk = rank
                if (rk != null) { tpl.save("$rk$s", crop(snap, Cfg.get(this, keys[idx])!!)); idx++; load() }
            })
        }
        r3.addView(btn("Pular") { idx++; load() }); r3.addView(btn("Fechar") { close() })
        root.addView(r1); root.addView(r2); root.addView(r3)
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        lp.gravity = Gravity.CENTER
        wm.addView(root, lp); load()
    }

    // ---------- loop principal ----------
    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val p = img.planes[0]
            val pad = p.rowStride - p.pixelStride * W
            val bm = Bitmap.createBitmap(W + pad / p.pixelStride, H, Bitmap.Config.ARGB_8888)
            bm.copyPixelsFromBuffer(p.buffer)
            frame = if (pad == 0) bm else Bitmap.createBitmap(bm, 0, 0, W, H)
        } finally { img.close() }
    }

    private fun readText(f: Bitmap, key: String): String {
        val b = Cfg.get(this, key) ?: return ""
        val c = crop(f, b)
        val px = IntArray(c.width * c.height); c.getPixels(px, 0, c.width, 0, 0, c.width, c.height)
        val h = px.contentHashCode()
        ocrCache[key]?.let { if (it.first == h) return it.second }
        val t = Ocr.text(c)
        ocrCache[key] = Pair(h, t); return t
    }

    private fun tick() {
        grab()
        val f = frame ?: return
        if (busy) return
        val heroNames = listOf("h1", "h2").map { k -> Cfg.get(this, k)?.let { tpl.match(crop(f, it)) } }
        if (heroNames.any { it == null }) {
            folded.clear(); lastKey = null
            show("Aguardando cartas", "Templates: ${tpl.count()}/52"); return
        }
        val boardNames = listOf("b1", "b2", "b3", "b4", "b5").mapNotNull { k -> Cfg.get(this, k)?.let { tpl.match(crop(f, it)) } }
        if (boardNames.size !in listOf(0, 3, 4, 5)) return

        val pot = Engine.parseNum(readText(f, "pot")) ?: 0.0
        val bb = Cfg.bb(this)
        val stack = Engine.parseNum(readText(f, "stack")) ?: (bb * 30)
        val heroBet = Engine.parseNum(readText(f, "hb")) ?: 0.0
        val bets = ArrayList<Double>(); var seats = 0
        for (i in 1..7) {
            if (Cfg.get(this, "s${i}b") == null && Cfg.get(this, "s${i}a") == null) continue
            seats++
            bets += Engine.parseNum(readText(f, "s${i}b")) ?: 0.0
            if (Engine.parseAction(readText(f, "s${i}a")) == "fold") folded.add(i)
        }
        val toCall = ((bets.maxOrNull() ?: 0.0) - heroBet).coerceAtLeast(0.0)
        val opps = maxOf(1, seats - folded.size)
        val stackBb = stack / maxOf(bb, 1.0)
        val key = "$heroNames$boardNames$pot$toCall$opps${stackBb.toInt()}$bb"
        if (key == lastKey) return
        lastKey = key
        val hero = heroNames.map { Engine.id(it!!)!! }.toIntArray()
        val board = boardNames.mapNotNull { Engine.id(it) }.toIntArray()
        val (act, eq) = Engine.suggest(hero, board, opps, pot, toCall, stackBb)
        show(act, "${heroNames.joinToString(" ")} | ${boardNames.joinToString(" ").ifEmpty { "-" }} | eq ${(eq * 100).toInt()}% | " +
            "pot ${pot.toInt()} | pagar ${toCall.toInt()} | $opps adv | ${stackBb.toInt()} BB (BB=${bb.toInt()})")
    }

    override fun onDestroy() {
        exec.shutdownNow()
        try { wm.removeView(panel) } catch (_: Exception) {}
        vd?.release(); reader?.close(); mp?.stop()
        super.onDestroy()
    }
}
