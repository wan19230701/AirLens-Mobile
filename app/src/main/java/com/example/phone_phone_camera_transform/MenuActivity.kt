package com.example.phone_phone_camera_transform

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        findViewById<Button>(R.id.btnModeCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        findViewById<Button>(R.id.btnModeController).setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
