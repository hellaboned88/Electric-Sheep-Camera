package com.mikeyb.electricsheepcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
//  Activity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        enableEdgeToEdge()

        val required = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        setContent {
            MaterialTheme {
                ElectricSheepCameraApp(cameraExecutor = cameraExecutor)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ElectricSheepCameraApp(
    cameraExecutor: ExecutorService,
    vm: SheepCameraViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }

    // Re-bind camera when front/back is toggled
    val cameraSelector = if (state.useFrontCamera)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    // ML Kit face detector
    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)) {

        // ── Camera preview ────────────────────────────────────────────────
        CameraPreview(
            cameraSelector = cameraSelector,
            cameraExecutor = cameraExecutor,
            onCameraReady = { imgCap, vidCap ->
                imageCapture = imgCap
                videoCapture = vidCap
            },
            onAnalysisFrame = { imageProxy ->
                // Face detection on every frame
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val input = InputImage.fromMediaImage(
                        mediaImage, imageProxy.imageInfo.rotationDegrees
                    )
                    faceDetector.process(input)
                        .addOnSuccessListener { faces ->
                            val w = imageProxy.width.toFloat()
                            val h = imageProxy.height.toFloat()
                            vm.updateFaces(faces.map { f ->
                                val bb = f.boundingBox
                                TrackedFace(
                                    cx = (bb.centerX() / w).coerceIn(0f, 1f),
                                    cy = (bb.centerY() / h).coerceIn(0f, 1f),
                                    w  = (bb.width()  / w).coerceIn(0f, 1f),
                                    h  = (bb.height() / h).coerceIn(0f, 1f)
                                )
                            })
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }
        )

        // ── Visual overlays ───────────────────────────────────────────────
        SheepOverlay(
            overlayIndex = state.overlayIndex,
            intensity    = state.intensity + if (state.audioReactive) state.audioLevel * 0.4f else 0f,
            pulse        = state.pulse + if (state.audioReactive) state.audioPeak * 0.5f else 0f,
            mode         = state.mode,
            faces        = state.trackedFaces
        )

        // ── Audio VU bar (top-right) ───────────────────────────────────────
        if (state.audioReactive) {
            AudioVuMeter(
                level = state.audioLevel,
                peak  = state.audioPeak,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 12.dp)
            )
        }

        // ── REC indicator ─────────────────────────────────────────────────
        if (state.isRecording) {
            RecIndicator(modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp, start = 16.dp))
        }

        // ── Controls panel ────────────────────────────────────────────────
        ControlsPanel(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter),
            onIntensity        = vm::setIntensity,
            onMode             = vm::setMode,
            onNextOverlay      = vm::nextOverlay,
            onToggleCamera     = vm::toggleCamera,
            onToggleAudio      = vm::toggleAudioReactive,
            onGlitch = {
                scope.launch {
                    repeat(12) { vm.pulse(); delay(120) }
                }
            },
            onSnap = { capturePhoto(context, imageCapture, cameraExecutor) },
            onToggleRecord = {
                if (state.isRecording) {
                    activeRecording?.stop()
                    activeRecording = null
                    vm.setRecording(false)
                } else {
                    activeRecording = startVideoRecording(
                        context, videoCapture, cameraExecutor,
                        onStarted = { vm.setRecording(true) },
                        onStopped = { vm.setRecording(false) }
                    )
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Camera preview + use-cases
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CameraPreview(
    cameraSelector: CameraSelector,
    cameraExecutor: ExecutorService,
    onCameraReady: (ImageCapture, VideoCapture<Recorder>) -> Unit,
    onAnalysisFrame: (ImageProxy) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    // Re-bind whenever cameraSelector changes (front/back toggle)
    LaunchedEffect(cameraSelector) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, onAnalysisFrame)
                }

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture,
                    imageAnalysis
                )
                onCameraReady(imageCapture, videoCapture)
            } catch (e: Exception) {
                Log.e("ESCamera", "Bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sheep overlay — effects + face halos
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SheepOverlay(
    overlayIndex: Int,
    intensity: Float,
    pulse: Float,
    mode: CameraMode,
    faces: List<TrackedFace>
) {
    val overlays = remember {
        listOf(
            R.drawable.sheep_01, R.drawable.sheep_02, R.drawable.sheep_03,
            R.drawable.sheep_04, R.drawable.sheep_05, R.drawable.sheep_06,
            R.drawable.sheep_07, R.drawable.sheep_08
        )
    }
    val bitmap = imageResource(id = overlays[overlayIndex % overlays.size]).asImageBitmap()
    val alpha = when (mode) {
        CameraMode.DREAM  -> 0.18f + (intensity * 0.45f)
        CameraMode.NEURAL -> 0.12f + (intensity * 0.35f)
        CameraMode.GLITCH -> 0.08f + (intensity * 0.25f) + pulse * 0.25f
    }.coerceIn(0f, 1f)

    val modeColor = when (mode) {
        CameraMode.DREAM  -> Color(0xFF34D3FF)
        CameraMode.NEURAL -> Color(0xFF5CFF9D)
        CameraMode.GLITCH -> Color(0xFFFF4DA6)
    }

    Box(Modifier.fillMaxSize()) {
        // Sheep image
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = alpha
        )

        // Scanlines + glitch blocks
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scanlineGap = when (mode) {
                CameraMode.DREAM  -> 26f
                CameraMode.NEURAL -> 16f
                CameraMode.GLITCH -> 10f
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color.White.copy(alpha = 0.03f + pulse * 0.04f),
                    start = Offset(0f, y),
                    end   = Offset(size.width, y),
                    strokeWidth = if (mode == CameraMode.GLITCH) 2f else 1f,
                    blendMode = BlendMode.Screen
                )
                y += scanlineGap
            }

            val blockCount = (8 + intensity * 32).toInt()
            repeat(blockCount) { i ->
                val bw = size.width  / (5 + (i % 7))
                val bh = 14f + (i % 5) * 12f + pulse * 18f
                val left = (i * 97 % size.width.toInt()).toFloat()
                val top  = (i * 57 % size.height.toInt()).toFloat()
                drawRect(
                    color = modeColor.copy(alpha = 0.05f + intensity * 0.16f + pulse * 0.16f),
                    topLeft = Offset(left, top),
                    size = Size(bw, bh),
                    blendMode = BlendMode.Screen
                )
            }

            // Face halos
            faces.forEach { face ->
                val cx = face.cx * size.width
                val cy = face.cy * size.height
                val rx = face.w * size.width  * 0.6f
                val ry = face.h * size.height * 0.6f
                val haloR = (rx + ry) / 2f

                // Outer glow ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            modeColor.copy(alpha = 0.55f + pulse * 0.2f),
                            modeColor.copy(alpha = 0f)
                        ),
                        center = Offset(cx, cy),
                        radius = haloR * 1.6f
                    ),
                    radius = haloR * 1.6f,
                    center = Offset(cx, cy),
                    blendMode = BlendMode.Screen
                )
                // Inner ring outline
                drawCircle(
                    color = modeColor.copy(alpha = 0.8f),
                    radius = haloR,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3f + pulse * 4f
                    ),
                    blendMode = BlendMode.Screen
                )
                // Crosshair
                val crossLen = haloR * 0.35f
                listOf(
                    Offset(cx - crossLen, cy) to Offset(cx - crossLen * 0.3f, cy),
                    Offset(cx + crossLen * 0.3f, cy) to Offset(cx + crossLen, cy),
                    Offset(cx, cy - crossLen) to Offset(cx, cy - crossLen * 0.3f),
                    Offset(cx, cy + crossLen * 0.3f) to Offset(cx, cy + crossLen)
                ).forEach { (s, e) ->
                    drawLine(
                        color = modeColor.copy(alpha = 0.9f),
                        start = s, end = e, strokeWidth = 2f,
                        blendMode = BlendMode.Screen
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Audio VU meter
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AudioVuMeter(level: Float, peak: Float, modifier: Modifier = Modifier) {
    val barCount = 12
    Column(
        modifier = modifier.width(18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.End
    ) {
        repeat(barCount) { i ->
            val threshold = (barCount - i - 1).toFloat() / barCount
            val lit = level >= threshold || peak >= threshold + 0.05f
            val color = when {
                threshold > 0.75f -> Color(0xFFFF4444)
                threshold > 0.5f  -> Color(0xFFFFCC00)
                else              -> Color(0xFF44FF88)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (lit) color else color.copy(alpha = 0.18f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  REC indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.Red.copy(alpha = alpha))
        )
        Text("REC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Controls panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlsPanel(
    state: SheepCameraUiState,
    modifier: Modifier = Modifier,
    onIntensity: (Float) -> Unit,
    onMode: (CameraMode) -> Unit,
    onNextOverlay: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleAudio: () -> Unit,
    onGlitch: () -> Unit,
    onSnap: () -> Unit,
    onToggleRecord: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xBB000000))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Title row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Electric Sheep Camera", color = Color.White, fontWeight = FontWeight.Bold)
                val faceText = if (state.trackedFaces.isEmpty()) "No faces"
                    else "${state.trackedFaces.size} face${if (state.trackedFaces.size > 1) "s" else ""} tracked"
                Text(faceText, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            // Camera flip button
            Button(
                onClick = onToggleCamera,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF223344))
            ) {
                Text(if (state.useFrontCamera) "⟳ Front" else "⟳ Back")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Intensity slider
        Text(
            "Intensity  ${(state.intensity * 100).toInt()}%",
            color = Color.White, fontSize = 13.sp
        )
        Slider(
            value = state.intensity,
            onValueChange = onIntensity,
            valueRange = 0f..1f
        )

        Spacer(Modifier.height(4.dp))

        // Mode buttons
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CameraMode.entries) { mode ->
                val selected = state.mode == mode
                Button(
                    onClick = { onMode(mode) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF336699) else Color(0xFF1A1A2E)
                    )
                ) {
                    Text(if (selected) "• ${mode.label}" else mode.label)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Action row 1 — Next sheep / Glitch / Audio
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNextOverlay) { Text("Next Sheep") }
            Button(onClick = onGlitch) { Text("Glitch") }
            Button(
                onClick = onToggleAudio,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.audioReactive) Color(0xFF553300) else Color(0xFF1A1A2E)
                )
            ) {
                Text(if (state.audioReactive) "🎙 On" else "🎙 Off")
            }
        }

        Spacer(Modifier.height(6.dp))

        // Action row 2 — Snap / Record
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSnap,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF224422))
            ) { Text("📸 Snap") }

            Button(
                onClick = onToggleRecord,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRecording) Color(0xFF661111) else Color(0xFF442211)
                )
            ) {
                Text(if (state.isRecording) "⏹ Stop" else "⏺ Record")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Photo capture
// ─────────────────────────────────────────────────────────────────────────────

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    executor: ExecutorService
) {
    val capture = imageCapture ?: return
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val cv = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "sheep_$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ElectricSheepCamera")
    }
    val output = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
    ).build()
    capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(r: ImageCapture.OutputFileResults) =
            Toast.makeText(context, "Saved to Pictures/ElectricSheepCamera", Toast.LENGTH_SHORT).show()
        override fun onError(e: ImageCaptureException) =
            Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
    })
}

// ─────────────────────────────────────────────────────────────────────────────
//  Video recording
// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
private fun startVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>?,
    executor: ExecutorService,
    onStarted: () -> Unit,
    onStopped: () -> Unit
): Recording? {
    val vc = videoCapture ?: return null
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val cv = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "sheep_$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ElectricSheepCamera")
    }
    return vc.output
        .prepareRecording(context, MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build())
        .withAudioEnabled()
        .start(executor) { event ->
            when (event) {
                is VideoRecordEvent.Start  -> onStarted()
                is VideoRecordEvent.Finalize -> {
                    onStopped()
                    if (event.hasError()) {
                        Log.e("ESCamera", "Video error: ${event.error}")
                    }
                }
            }
        }
}
