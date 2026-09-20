package com.smngreenberg.banditviewer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smngreenberg.banditviewer.ui.UiState
import com.smngreenberg.banditviewer.ui.ViewerViewModel
import com.smngreenberg.banditviewer.ui.theme.BanditviewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BanditviewerTheme {
                val viewModel: ViewerViewModel = viewModel()
                BanditViewerApp(viewModel)
            }
        }
    }
}

@Composable
fun BanditViewerApp(viewModel: ViewerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val frame by viewModel.latestFrame.collectAsStateWithLifecycle()
    val fps by viewModel.fps.collectAsStateWithLifecycle()
    val dropped by viewModel.droppedFrames.collectAsStateWithLifecycle()
    val battery by viewModel.batteryPct.collectAsStateWithLifecycle()
    val noFramesWarning by viewModel.noFramesWarning.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val window = (context as? Activity)?.window
    
    var localNetworkPermissionDenied by remember { mutableStateOf(false) }
    
    val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            localNetworkPermissionDenied = false
            viewModel.startStreaming()
        } else {
            localNetworkPermissionDenied = true
        }
    }

    fun handleStartClick() {
        if (Build.VERSION.SDK_INT >= 37) {
            when (ContextCompat.checkSelfPermission(context, localNetworkPermission)) {
                PackageManager.PERMISSION_GRANTED -> {
                    localNetworkPermissionDenied = false
                    viewModel.startStreaming()
                }
                else -> {
                    permissionLauncher.launch(localNetworkPermission)
                }
            }
        } else {
            viewModel.startStreaming()
        }
    }

    // Keep screen on while streaming
    SideEffect {
        if (uiState is UiState.Streaming) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    // Stop on ON_STOP
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.stopStreaming()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bandit Viewer",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Video Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (frame != null) {
                    Image(
                        bitmap = frame!!,
                        contentDescription = "Viewfinder",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = when (uiState) {
                            is UiState.Idle -> "Tap Start to connect"
                            is UiState.Connecting -> (uiState as UiState.Connecting).step
                            is UiState.Error -> "Error"
                            is UiState.Streaming -> "Waiting for frames..."
                        },
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (uiState is UiState.Idle || uiState is UiState.Error) {
                    Button(onClick = { handleStartClick() }) {
                        Text("Start")
                    }
                } else {
                    Button(
                        onClick = { viewModel.stopStreaming() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Line
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (uiState) {
                        is UiState.Idle -> "Status: Idle"
                        is UiState.Connecting -> "Status: Connecting..."
                        is UiState.Streaming -> "Status: Streaming"
                        is UiState.Error -> "Status: Error"
                    }
                )
                if (uiState is UiState.Streaming) {
                    Text("FPS: $fps | Dropped: $dropped")
                    battery?.let { Text("Battery: $it%") }
                }
            }

            // Error or Warning Cards
            if (localNetworkPermissionDenied) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Permission required",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Bandit Viewer needs local network access to talk to the camera. Allow it in Settings > Apps > Bandit Viewer > Permissions.",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        TextButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Open Settings")
                        }
                    }
                }
            } else if (uiState is UiState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = (uiState as UiState.Error).message,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Is the Bandit's Wi-Fi on? Are you joined to its network in Settings?",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (noFramesWarning) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No frames received. Tap Stop, then Start.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
