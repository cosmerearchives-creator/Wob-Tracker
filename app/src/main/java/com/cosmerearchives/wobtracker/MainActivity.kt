package com.cosmerearchives.wobtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Plum = Color(0xFF6B5C87)
private val Lavender = Color(0xFFECE2F8)
private val Background = Color(0xFFF6F4F0)
private val Ink = Color(0xFF37343C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updates = UpdateManager(this)
        updates.check()
        setContent {
            val state by updates.state.collectAsState()
            MaterialTheme(colorScheme = lightColorScheme(primary = Plum, background = Background, surface = Color.White, onBackground = Ink)) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Background).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text("WoB Tracker", fontSize = 33.sp, color = Ink, fontWeight = FontWeight.Bold)
                    Text("Word of Brandon companion", color = Plum, fontSize = 16.sp)
                    Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.message, color = Ink)
                            if (state.downloadUrl != null) {
                                Button(onClick = { updates.installLatest() }, colors = ButtonDefaults.buttonColors(containerColor = Plum)) {
                                    Text("Install update")
                                }
                            }
                            OutlinedButton(onClick = { updates.check() }) { Text("Check for updates") }
                        }
                    }
                    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Text("Your collection starts here", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("Update test: if you can read this message, the new APK installed successfully!", color = Ink)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Version " + BuildConfig.VERSION_NAME, color = Plum, fontSize = 12.sp)
                }
            }
        }
    }
}
