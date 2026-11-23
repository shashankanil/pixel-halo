package com.shashankanil.halo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashankanil.halo.Transport
import androidx.compose.foundation.background

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            HaloTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HaloRoot() }
            }
        }
    }
}

private enum class Tab(val label: String) { LIGHT("Light"), ALERTS("Alerts"), INDICATORS("Indicators"), SETUP("Setup") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HaloRoot() {
    val status by Transport.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (status.running) 0 else 3) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Halo")
                        Spacer(Modifier.width(10.dp))
                        val (dot, label) = when {
                            !status.running -> Color(0xFFFF5252) to "offline"
                            status.leds == 0 -> Color(0xFFFFC400) to "no HiLight found"
                            else -> Color(0xFF00E676) to "${status.leds} LEDs"
                        }
                        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.LIGHT -> Icons.Filled.Star
                                    Tab.ALERTS -> Icons.Filled.Notifications
                                    Tab.INDICATORS -> Icons.Filled.Info
                                    Tab.SETUP -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Each tab keeps its own scroll position.
        androidx.compose.runtime.key(tab) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                when (Tab.entries[tab]) {
                    Tab.LIGHT -> LightScreen()
                    Tab.ALERTS -> AlertsScreen()
                    Tab.INDICATORS -> IndicatorsScreen()
                    Tab.SETUP -> SetupScreen()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
