package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    CallBridgeApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CallBridgeApp(
    modifier: Modifier = Modifier,
    viewModel: CallBridgeViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // States observed from ViewModel
    val isConnected by viewModel.isConnected.collectAsState()
    val rawUid by viewModel.currentUid.collectAsState()
    val activeNumber by viewModel.activeNumber.collectAsState()
    val activeTimestamp by viewModel.activeTimestamp.collectAsState()
    val recentCalls by viewModel.recentCalls.collectAsState()

    // Dialog state for manual UID entry
    var showManualUidDialog by remember { mutableStateOf(false) }
    var manualUidText by remember { mutableStateOf("") }

    // Dropdown state for menu
    var showMenu by remember { mutableStateOf(false) }

    // Floating animation for pulsing dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Collect and display toasts
    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Dynamic Permission for Notifications on Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Core scanner initializer
    val scannerLauncher = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    fun startQrScan() {
        scannerLauncher.startScan()
            .addOnSuccessListener { barcode ->
                val scannedUid = barcode.rawValue
                if (!scannedUid.isNullOrBlank()) {
                    viewModel.connectUid(scannedUid)
                } else {
                    Toast.makeText(context, "Empty QR Code scan", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Scan cancelled, enter manually", Toast.LENGTH_SHORT).show()
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // pulsing LED status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(if (isConnected) dotScale else 1.0f)
                        .clip(CircleShape)
                        .background(if (isConnected) CallBridgeGreen else CallBridgeRed)
                )

                Text(
                    text = "CallBridge",
                    color = TextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options menu",
                        tint = TextSubColor
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurface2)
                ) {
                    DropdownMenuItem(
                        text = { Text("Paste UID", color = TextColor) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = TextSubColor) },
                        onClick = {
                            showMenu = false
                            val textFromClip = clipboardManager.getText()?.text ?: ""
                            if (textFromClip.isNotBlank()) {
                                viewModel.connectUid(textFromClip)
                            } else {
                                // open dialogue for manual input
                                manualUidText = ""
                                showManualUidDialog = true
                            }
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Scan QR Code", color = TextColor) },
                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = TextSubColor) },
                        onClick = {
                            showMenu = false
                            startQrScan()
                        }
                    )

                    HorizontalDivider(color = BorderColor, thickness = 1.dp)

                    DropdownMenuItem(
                        text = { Text("Reset & Disconnect", color = CallBridgeRed) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = CallBridgeRed) },
                        onClick = {
                            showMenu = false
                            viewModel.disconnect()
                        }
                    )
                }
            }
        }

        // --- HERO BRIDGED CONNECTED CONTAINER ---
        Box(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Call status avatar circle
                val ringInteractionScale by animateFloatAsState(
                    targetValue = if (!activeNumber.isNullOrBlank()) 1.15f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "ring_scale"
                )

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(ringInteractionScale)
                        .clip(CircleShape)
                        .background(if (!activeNumber.isNullOrBlank()) CallBridgeGreenDim else DarkSurface2)
                        .clickable(enabled = !activeNumber.isNullOrBlank()) {
                            activeNumber?.let { number ->
                                // Fire Dial Intent
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                                context.startActivity(intent)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsing rings behind active calls
                    if (!activeNumber.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.25f)
                                .background(CallBridgeGreenGlow.copy(alpha = 0.12f), CircleShape)
                        )
                    }

                    Text(
                        text = if (!activeNumber.isNullOrBlank()) "📞" else "📵",
                        fontSize = 38.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "INCOMING NUMBER",
                    color = TextDimColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                // Render number block with bangla format
                val displayNum = activeNumber
                if (!displayNum.isNullOrBlank()) {
                    Text(
                        text = CallBridgeService.formatBangladeshiNumber(displayNum),
                        color = CallBridgeGreen,
                        fontSize = 25.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                    
                    val timeString = activeTimestamp?.let { ts ->
                        val sdf = SimpleDateFormat("hh:mm:ss a '·' MMM d", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("Asia/Dhaka")
                        }
                        sdf.format(Date(ts))
                    } ?: ""
                    
                    Text(
                        text = timeString,
                        color = TextSubColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = "Waiting for number…",
                        color = TextDimColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // If connected, show monitoring active pill
                if (isConnected) {
                    Surface(
                        color = CallBridgeGreenDim,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = "Linked to: ${rawUid?.take(8)}...",
                            color = CallBridgeGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BorderColor, modifier = Modifier.padding(horizontal = 24.dp))

        // --- HISTORIC CALL RECENT LIST ---
        Column(
            modifier = Modifier
                .weight(1.7f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT CALLS",
                    color = TextDimColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                if (recentCalls.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        color = TextSubColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { viewModel.clearHistory() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (recentCalls.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recent calls",
                        color = TextDimColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(recentCalls, key = { it.key }) { item ->
                        RecentCallRow(item) {
                            // Dial recent number on click
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.number}"))
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    // Manual UID Dialog Prompt matching traditional popups
    if (showManualUidDialog) {
        AlertDialog(
            onDismissRequest = { showManualUidDialog = false },
            title = { Text("Connect to CallBridge", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paste or manually enter your bridge UID code below to synchronize calls.", color = TextSubColor, fontSize = 14.sp)
                    
                    OutlinedTextField(
                        value = manualUidText,
                        onValueChange = { manualUidText = it },
                        placeholder = { Text("Enter Unique UID Code", color = TextDimColor) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextColor,
                            unfocusedTextColor = TextColor,
                            focusedBorderColor = CallBridgeGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface
                        ),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualUidText.isNotBlank()) {
                            viewModel.connectUid(manualUidText)
                        }
                        showManualUidDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CallBridgeGreen)
                ) {
                    Text("Connect", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualUidDialog = false }) {
                    Text("Cancel", color = TextSubColor)
                }
            },
            containerColor = DarkSurface2
        )
    }
}

@Composable
fun RecentCallRow(
    item: HistoryItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(DarkSurface)
            .clickable { onClick() }
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(DarkSurface2, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "📞", fontSize = 16.sp)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = CallBridgeService.formatBangladeshiNumber(item.number),
                color = TextColor,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            // Parse Bangladesh relative time
            val timeString = remember(item.ts) {
                val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Dhaka")
                }
                val dateFormat = SimpleDateFormat("MMM d", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Dhaka")
                }
                "${timeFormat.format(Date(item.ts))} · ${dateFormat.format(Date(item.ts))}"
            }

            Text(
                text = timeString,
                color = TextSubColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Green Dial Button overlay
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(CallBridgeGreenDim, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = "Dial number",
                tint = CallBridgeGreen,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
