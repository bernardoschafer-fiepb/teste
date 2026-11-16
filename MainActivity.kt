package com.estudo.pokerassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "Assistente de estudo para mesas PRIVADAS entre amigos, com todos cientes.\n\n" +
                "1) Permita a sobreposicao.\n2) Toque em Iniciar e aceite a captura de tela.\n" +
                "3) Abra o PixPoker. Na janelinha flutuante: Calibrar (marque as areas), " +
                "Ensinar (ensine as cartas) e ajuste o BB.\n\n" +
                "Nao gire a tela depois de iniciar."
        })
        col.addView(Button(this).apply {
            text = "1. Permitir sobreposicao"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        col.addView(Button(this).apply {
            text = "2. Iniciar assistente"
            setOnClickListener { start() }
        })
        setContentView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita a sobreposicao primeiro", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            finish()
        }
    }
}
