package com.liquidglass.flacplayer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Capturador de errores en pantalla para que puedas copiar cualquier fallo y enviarmelo al chat
        var crashReport by mutableStateOf<String?>(null)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            crashReport = sw.toString()
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer = player

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF70D6FF),
                    secondary = Color(0xFFA8E6FF),
                    background = Color(0xFF070B14),
                    surface = Color(0xFF101828)
                )
            ) {
                if (crashReport != null) {
                    CrashDiagnosticScreen(
                        errorText = crashReport!!,
                        onCopy = {
                            copyToClipboard(this, crashReport!!)
                        }
                    )
                } else {
                    LiquidFlacApp(player = player)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@Composable
fun LiquidFlacApp(player: ExoPlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var tracks by remember { mutableStateOf<List<FlacTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var currentTrackIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var isFullScreenPlayerOpen by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }

    val currentTrack = tracks.getOrNull(currentTrackIndex)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            scope.launch {
                isScanning = true
                tracks = FlacScanner.scanFlacFiles(context)
                isScanning = false
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isScanning = true
            tracks = FlacScanner.scanFlacFiles(context)
            isScanning = false
        }
    }

    // Escuchar eventos de ExoPlayer
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(1L)
                } else if (playbackState == Player.STATE_ENDED) {
                    if (tracks.isNotEmpty() && currentTrackIndex + 1 < tracks.size) {
                        val nextIdx = currentTrackIndex + 1
                        currentTrackIndex = nextIdx
                        playTrack(player, tracks[nextIdx])
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                diagnosticMessage = "Error decodificando FLAC: ${error.errorCodeName} - ${error.message}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    // Actualizar barra de progreso cada 200ms mientras suena
    LaunchedEffect(isPlaying, isFullScreenPlayerOpen) {
        while (isPlaying) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(currentTrack?.durationMs ?: 1L).coerceAtLeast(1L)
            delay(200L)
        }
    }

    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    BackHandler(enabled = isFullScreenPlayerOpen) {
        isFullScreenPlayerOpen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060A12))
    ) {
        // 1. FONDO DINÁMICO CON CARÁTULA DIFUMINADA Y ORBES LIQUID GLASS
        DynamicLiquidBackground(currentTrack = currentTrack)

        // 2. PANTALLA PRINCIPAL (LISTA DE CANCIONES FLAC + BUSCADOR EN CRISTAL LÍQUIDO)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Cabecera Liquid Glass
            TopGlassHeader(
                flacCount = tracks.size,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onRescan = {
                    if (hasPermission) {
                        scope.launch {
                            isScanning = true
                            tracks = FlacScanner.scanFlacFiles(context)
                            isScanning = false
                            Toast.makeText(
                                context,
                                "Escaneo completado: ${tracks.size} archivos .FLAC",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        permissionLauncher.launch(audioPermission)
                    }
                }
            )

            // Alerta de error si un archivo esta corrupto
            diagnosticMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .liquidGlassPanel(cornerRadius = 16.dp, glassOpacity = 0.25f)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { diagnosticMessage = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }

            // Contenido principal según permisos y archivos
            if (!hasPermission) {
                PermissionGlassCard(
                    onGrantPermission = { permissionLauncher.launch(audioPermission) }
                )
            } else if (isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF70D6FF))
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Buscando archivos .FLAC sin pérdida...",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp
                        )
                    }
                }
            } else if (filteredTracks.isEmpty()) {
                EmptyFlacLibraryCard(
                    hasSearch = searchQuery.isNotBlank(),
                    onRescan = {
                        scope.launch {
                            isScanning = true
                            tracks = FlacScanner.scanFlacFiles(context)
                            isScanning = false
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = if (currentTrack != null) 120.dp else 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = filteredTracks,
                        key = { _, item -> item.id }
                    ) { _, track ->
                        val isSelected = currentTrack?.id == track.id
                        FlacTrackGlassItem(
                            track = track,
                            isSelected = isSelected,
                            isPlaying = isSelected && isPlaying,
                            onClick = {
                                val realIdx = tracks.indexOfFirst { it.id == track.id }
                                if (realIdx != -1) {
                                    if (currentTrackIndex == realIdx) {
                                        isFullScreenPlayerOpen = true
                                    } else {
                                        currentTrackIndex = realIdx
                                        playTrack(player, track)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // 3. BARRA FLOTANTE INFERIOR EN CRISTAL LÍQUIDO
        AnimatedVisibility(
            visible = currentTrack != null && !isFullScreenPlayerOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            currentTrack?.let { activeTrack ->
                FloatingLiquidMiniPlayer(
                    track = activeTrack,
                    isPlaying = isPlaying,
                    progress = (currentPositionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                    onTogglePlay = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onNext = {
                        if (tracks.isNotEmpty()) {
                            val nextIdx = (currentTrackIndex + 1) % tracks.size
                            currentTrackIndex = nextIdx
                            playTrack(player, tracks[nextIdx])
                        }
                    },
                    onOpenFullScreen = {
                        isFullScreenPlayerOpen = true
                    }
                )
            }
        }

        // 4. REPRODUCTOR A PANTALLA COMPLETA CON CARÁTULA DE FONDO Y PANELES LIQUID GLASS
        AnimatedVisibility(
            visible = isFullScreenPlayerOpen && currentTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
            ) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            currentTrack?.let { activeTrack ->
                FullScreenLiquidPlayer(
                    track = activeTrack,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs.coerceAtLeast(activeTrack.durationMs.coerceAtLeast(1L)),
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onCollapse = { isFullScreenPlayerOpen = false },
                    onTogglePlay = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onSeek = { fraction ->
                        val target = (fraction * durationMs).toLong()
                        player.seekTo(target)
                        currentPositionMs = target
                    },
                    onPrevious = {
                        if (player.currentPosition > 4000L) {
                            player.seekTo(0L)
                        } else if (tracks.isNotEmpty()) {
                            val prevIdx = if (currentTrackIndex - 1 < 0) tracks.lastIndex else currentTrackIndex - 1
                            currentTrackIndex = prevIdx
                            playTrack(player, tracks[prevIdx])
                        }
                    },
                    onNext = {
                        if (tracks.isNotEmpty()) {
                            val nextIdx = if (shuffleEnabled) {
                                tracks.indices.random()
                            } else {
                                (currentTrackIndex + 1) % tracks.size
                            }
                            currentTrackIndex = nextIdx
                            playTrack(player, tracks[nextIdx])
                        }
                    },
                    onToggleShuffle = {
                        shuffleEnabled = !shuffleEnabled
                    },
                    onToggleRepeat = {
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player.repeatMode = repeatMode
                    }
                )
            }
        }
    }
}

private fun playTrack(player: ExoPlayer, track: FlacTrack) {
    player.setMediaItem(MediaItem.fromUri(track.contentUri))
    player.prepare()
    player.play()
}

@Composable
fun DynamicLiquidBackground(currentTrack: FlacTrack?) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        // Gradiente base profundo
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D1B2A),
                            Color(0xFF070B14),
                            Color(0xFF04070D)
                        )
                    )
                )
        )

        // Si hay canción activa, proyectamos su carátula ampliada y difuminada a 90px
        if (currentTrack != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentTrack.albumArtUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.35f)
                    .hardwareBlur(95f)
            )
            // Velo oscuro translúcido para mantener contraste perfecto con los paneles de cristal
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF050811).copy(alpha = 0.55f),
                                Color(0xFF050811).copy(alpha = 0.72f),
                                Color(0xFF03050B).copy(alpha = 0.88f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun TopGlassHeader(
    flacCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRescan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .liquidGlassPanel(cornerRadius = 26.dp, glassOpacity = 0.16f, borderOpacity = 0.45f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LiquidFLAC",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF70D6FF).copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "HI-RES LOSSLESS",
                            color = Color(0xFF90E0EF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$flacCount pistas .FLAC detectadas en tu dispositivo",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = onRescan,
                modifier = Modifier
                    .size(42.dp)
                    .liquidGlassPanel(cornerRadius = 21.dp, glassOpacity = 0.22f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Actualizar biblioteca FLAC",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Buscador translúcido
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    text = "Filtrar por canción, artista o álbum...",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Limpiar",
                            tint = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedBorderColor = Color(0xFF70D6FF).copy(alpha = 0.65f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun FlacTrackGlassItem(
    track: FlacTrack,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val glassAlpha = if (isSelected) 0.26f else 0.12f
    val borderAlpha = if (isSelected) 0.75f else 0.32f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassPanel(
                cornerRadius = 22.dp,
                glassOpacity = glassAlpha,
                borderOpacity = borderAlpha,
                shaderIntensity = if (isSelected) 1.3f else 0.7f
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Carátula con respaldo elegante si el archivo no tiene imagen incrustada
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F3B5B), Color(0xFF0F1E32))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(30.dp)
            )
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.albumArtUri)
                    .crossfade(true)
                    .build(),
                contentDescription = track.album,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isSelected) Color(0xFF90E0EF) else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${track.artist} • ${track.album}",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "FLAC • ${track.bitrateKbps} kbps",
                        color = Color(0xFFB5E2FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDuration(track.durationMs),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .liquidGlassPanel(cornerRadius = 19.dp, glassOpacity = 0.24f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = if (isSelected) Color(0xFF70D6FF) else Color.White
            )
        }
    }
}

@Composable
fun FloatingLiquidMiniPlayer(
    track: FlacTrack,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 24.dp, shape = RoundedCornerShape(28.dp), spotColor = Color(0xFF00B4D8))
            .liquidGlassPanel(
                cornerRadius = 28.dp,
                glassOpacity = 0.24f,
                borderOpacity = 0.65f,
                shaderIntensity = 1.25f
            )
            .clickable(onClick = onOpenFullScreen)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1A2B45)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f)
                )
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track.albumArtUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • FLAC ${track.bitrateKbps} kbps",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(44.dp)
                    .liquidGlassPanel(cornerRadius = 22.dp, glassOpacity = 0.28f)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Reproducir / Pausar",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onNext,
                modifier = Modifier
                    .size(40.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp, glassOpacity = 0.18f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Siguiente",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(0xFF90E0EF),
            trackColor = Color.White.copy(alpha = 0.18f)
        )
    }
}

@Composable
fun FullScreenLiquidPlayer(
    track: FlacTrack,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    val context = LocalContext.current
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.91f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "coverScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF04070F))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // 1. CARÁTULA DE FONDO INMERSIVA A PANTALLA COMPLETA CON DESENFOQUE LÍQUIDO
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(track.albumArtUri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.4f)
                .hardwareBlur(110f)
        )

        // Capa de refracción oscura para resaltar el cristal líquido
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF040814).copy(alpha = 0.45f),
                            Color(0xFF060B18).copy(alpha = 0.65f),
                            Color(0xFF03060C).copy(alpha = 0.85f)
                        )
                    )
                )
        )

        // 2. CONTENIDO DEL REPRODUCTOR EN CÁPSULAS DE CRISTAL LÍQUIDO
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Barra superior flotante
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier
                        .size(46.dp)
                        .liquidGlassPanel(cornerRadius = 23.dp, glassOpacity = 0.22f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimizar",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .liquidGlassPanel(cornerRadius = 20.dp, glassOpacity = 0.22f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "FLAC • ${track.bitrateKbps} KBPS • ${(track.sizeBytes / (1024 * 1024))} MB",
                        color = Color(0xFFB5E2FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .liquidGlassPanel(cornerRadius = 23.dp, glassOpacity = 0.22f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Hi-Res Audio",
                        tint = Color(0xFF90E0EF)
                    )
                }
            }

            // Marco de Carátula en Cristal Líquido con refracción de borde
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .aspectRatio(1f)
                    .scale(coverScale)
                    .shadow(32.dp, RoundedCornerShape(36.dp), spotColor = Color(0xFF48CAE4))
                    .liquidGlassPanel(
                        cornerRadius = 36.dp,
                        glassOpacity = 0.20f,
                        borderOpacity = 0.65f,
                        shaderIntensity = 1.4f
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1B3358), Color(0xFF0B1528))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(110.dp)
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(track.albumArtUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Panel Principal de Metadatos y Controles en Cristal Líquido
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassPanel(
                        cornerRadius = 34.dp,
                        glassOpacity = 0.22f,
                        borderOpacity = 0.60f,
                        shaderIntensity = 1.35f
                    )
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${track.artist} — ${track.album}",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Barra de tiempo interactiva
                val progressFraction = (currentPositionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                Slider(
                    value = progressFraction,
                    onValueChange = onSeek,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF90E0EF),
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(currentPositionMs),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatDuration(durationMs),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Botonera de reproducción esculpida en cristal líquido
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier
                            .size(44.dp)
                            .liquidGlassPanel(
                                cornerRadius = 22.dp,
                                glassOpacity = if (shuffleEnabled) 0.35f else 0.14f
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Aleatorio",
                            tint = if (shuffleEnabled) Color(0xFF90E0EF) else Color.White.copy(alpha = 0.7f)
                        )
                    }

                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassPanel(cornerRadius = 28.dp, glassOpacity = 0.24f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Botón central Play/Pause con refracción intensa
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .shadow(20.dp, CircleShape, spotColor = Color(0xFF70D6FF))
                            .liquidGlassPanel(
                                cornerRadius = 38.dp,
                                glassOpacity = 0.36f,
                                borderOpacity = 0.85f,
                                shaderIntensity = 1.6f
                            )
                            .clickable(onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play / Pause",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassPanel(cornerRadius = 28.dp, glassOpacity = 0.24f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Siguiente",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleRepeat,
                        modifier = Modifier
                            .size(44.dp)
                            .liquidGlassPanel(
                                cornerRadius = 22.dp,
                                glassOpacity = if (repeatMode != Player.REPEAT_MODE_OFF) 0.35f else 0.14f
                            )
                    ) {
                        Icon(
                            imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = "Repetir",
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFF90E0EF) else Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionGlassCard(onGrantPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 30.dp, glassOpacity = 0.22f, borderOpacity = 0.6f)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = Color(0xFF90E0EF),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Permiso de Audio Android 14",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Para detectar tus canciones .FLAC y extraer sus carátulas de alta resolución, autoriza el acceso a tus archivos de música.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onGrantPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF70D6FF),
                    contentColor = Color(0xFF041020)
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Conceder Acceso a Música .FLAC",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyFlacLibraryCard(hasSearch: Boolean, onRescan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 28.dp, glassOpacity = 0.18f)
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.AudioFile,
                contentDescription = null,
                tint = Color(0xFF90E0EF),
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (hasSearch) "Sin coincidencias en tu búsqueda" else "No se detectaron archivos .FLAC",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasSearch) {
                    "Prueba con otro nombre de canción, artista o álbum."
                } else {
                    "Asegúrate de tener archivos con extensión .flac en la carpeta Music o Download de tu Android 14 y presiona Volver a Escanear."
                },
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(
                onClick = onRescan,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color(0xFF90E0EF))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Volver a Escanear", color = Color.White)
            }
        }
    }
}

@Composable
fun CrashDiagnosticScreen(errorText: String, onCopy: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 24.dp, glassOpacity = 0.22f)
                .padding(20.dp)
        ) {
            Text(
                text = "Diagnóstico de Error (Copia y envíamelo al chat)",
                color = Color(0xFFFF8A80),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorText.take(1500),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copiar Reporte para Gemini")
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Reporte LiquidFLAC", text))
    Toast.makeText(context, "Reporte copiado al portapapeles", Toast.LENGTH_SHORT).show()
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
