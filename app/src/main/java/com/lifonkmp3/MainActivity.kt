
package com.lifonkmp3

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lifonkmp3.ui.theme.LifonkMP3Theme
import com.google.common.util.concurrent.ListenableFuture
import android.content.ComponentName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight


data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri
)

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SessionToken(
            this,
            ComponentName(this, MusicService::class.java)
        )

        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        future.addListener({
            if (isFinishing || isDestroyed) return@addListener

            try {
                val controller = future.get()
                setContent {
                    LifonkMP3Theme {
                        MusicLibrary(controller)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "LifonkMP3",
                    "No se pudo conectar con MusicService",
                    e
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

@Composable
fun MusicLibrary(player: Player) {
    val context = LocalContext.current

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, audioPermission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var currentPlaylistId by remember { mutableStateOf<String?>(null) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var repeatMode by remember { mutableIntStateOf(player.repeatMode) }
    var shuffleEnabled by remember { mutableStateOf(player.shuffleModeEnabled) }

    var playlists by remember {
        mutableStateOf(PlaylistStorage.load(context))
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddSongDialog by remember { mutableStateOf<Song?>(null) }
    var renamePlaylistId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }

    val currentPlaylist = playlists.find { it.id == currentPlaylistId }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    fun refreshPlaylists() {
        playlists = PlaylistStorage.load(context)
    }

    fun playSongs(queue: List<Song>, startIndex: Int) {
        if (queue.isEmpty() || startIndex !in queue.indices) return

        selectedSong = queue[startIndex]
        showPlayer = true

        player.setMediaItems(
            queue.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.uri.toString())
                    .setUri(song.uri)
                    .build()
            },
            startIndex,
            0L
        )

        player.prepare()
        player.play()
    }

    DisposableEffect(player, songs) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                    ?: mediaItem?.mediaId

                selectedSong = songs.find {
                    it.uri.toString() == uri
                } ?: selectedSong
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffleEnabled = enabled
            }
        }

        player.addListener(listener)

        isPlaying = player.isPlaying
        repeatMode = player.repeatMode
        shuffleEnabled = player.shuffleModeEnabled

        val current = player.currentMediaItem
        if (current != null) {
            val uri = current.localConfiguration?.uri?.toString()
                ?: current.mediaId
            selectedSong = songs.find { it.uri.toString() == uri }
                ?: selectedSong
        }

        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player, selectedSong) {
        while (true) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            totalDuration = player.duration
                .takeIf { it > 0L }
                ?: selectedSong?.duration
                        ?: 0L
            delay(500.milliseconds)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loading = true
            songs = withContext(Dispatchers.IO) {
                loadSongs(context)
            }
            loading = false

            val current = player.currentMediaItem
            if (current != null) {
                val uri = current.localConfiguration?.uri?.toString()
                    ?: current.mediaId
                selectedSong = songs.find { it.uri.toString() == uri }
                    ?: selectedSong
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (!showPlayer && selectedSong != null) {
                val song = selectedSong!!

                Surface(
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArt(song.uri, Modifier.size(48.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showPlayer = true }
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                song.title,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        }) {
                            Text(
                                if (isPlaying) "Ⅱ" else "▶",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        IconButton(onClick = {
                            player.seekToNextMediaItem()
                        }) {
                            Text("»", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (showPlayer && selectedSong != null) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.background
                    }
                )
                .padding(
                    if (showPlayer && selectedSong != null) {
                        PaddingValues(0.dp)
                    } else {
                        padding
                    }
                )
        ) {
            when {
                !hasPermission -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Accede a tu música",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Lifonk necesita permiso para leer los archivos de audio.")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            permissionLauncher.launch(audioPermission)
                        }) {
                            Text("Permitir acceso")
                        }
                    }
                }

                showPlayer && selectedSong != null -> {
                    FullPlayerScreen(
                        song = selectedSong!!,
                        player = player,
                        position = currentPosition,
                        duration = totalDuration,
                        isPlaying = isPlaying,
                        repeatMode = repeatMode,
                        shuffleEnabled = shuffleEnabled,
                        onBack = { showPlayer = false },
                        onTogglePlay = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        onPrevious = { player.seekToPreviousMediaItem() },
                        onNext = { player.seekToNextMediaItem() },
                        onSeek = { player.seekTo(it) },
                        onToggleRepeat = {
                            val next = when (player.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            player.repeatMode = next
                        },
                        onToggleShuffle = {
                            player.shuffleModeEnabled = !player.shuffleModeEnabled
                        }
                    )
                }

                showPlaylists && currentPlaylist != null -> {
                    PlaylistDetailScreen(
                        playlist = currentPlaylist!!,
                        songs = songs,
                        onBack = { currentPlaylistId = null },
                        onPlay = { song ->
                            val queue = currentPlaylist!!.songs.mapNotNull { saved ->
                                songs.find { it.uri.toString() == saved.uri }
                            }
                            val index = queue.indexOfFirst {
                                it.uri == song.uri
                            }
                            if (index >= 0) playSongs(queue, index)
                        },
                        onRemove = { song ->
                            PlaylistStorage.removeSong(
                                context,
                                currentPlaylist!!.id,
                                song.uri.toString()
                            )
                            refreshPlaylists()
                        },
                        onMove = { from, to ->
                            PlaylistStorage.moveSong(
                                context,
                                currentPlaylist!!.id,
                                from,
                                to
                            )
                            refreshPlaylists()
                        }
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Lifonk Music",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Tu música, tus momentos",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            FilledTonalButton(
                                onClick = {
                                    showPlaylists = !showPlaylists
                                    currentPlaylistId = null
                                    refreshPlaylists()
                                }
                            ) {
                                Text(if (showPlaylists) "Biblioteca" else "Playlists")
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        if (showPlaylists) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Tus playlists",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    nameInput = ""
                                    showCreateDialog = true
                                }) {
                                    Text("+ Crear")
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (playlists.isEmpty()) {
                                Text(
                                    "Todavía no tienes playlists. Crea una para organizar tu música."
                                )
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(playlists, key = { it.id }) { playlist ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    currentPlaylistId = playlist.id
                                                },
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            "♫",
                                                            style = MaterialTheme.typography.headlineMedium,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }

                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .padding(horizontal = 12.dp)
                                                ) {
                                                    Text(
                                                        playlist.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        "${playlist.songs.size} canciones",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                TextButton(onClick = {
                                                    renamePlaylistId = playlist.id
                                                    nameInput = playlist.name
                                                    showRenameDialog = true
                                                }) {
                                                    Text("Editar")
                                                }

                                                TextButton(onClick = {
                                                    PlaylistStorage.delete(context, playlist.id)
                                                    refreshPlaylists()
                                                }) {
                                                    Text("Eliminar")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            when {
                                loading -> {
                                    Box(
                                        Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }

                                songs.isEmpty() -> {
                                    Text(
                                        "No encontramos canciones. Guarda archivos de audio en tu teléfono y vuelve a abrir la biblioteca."
                                    )
                                }

                                else -> {
                                    Text(
                                        "${songs.size} canciones",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(bottom = 12.dp)
                                    ) {
                                        items(songs, key = { it.id }) { song ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        playSongs(songs, songs.indexOf(song))
                                                    }
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AlbumArt(song.uri, Modifier.size(56.dp))

                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .padding(horizontal = 12.dp)
                                                ) {
                                                    Text(
                                                        song.title,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        song.artist,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        formatDuration(song.duration),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                IconButton(onClick = {
                                                    refreshPlaylists()
                                                    showAddSongDialog = song
                                                }) {
                                                    Text(
                                                        "+",
                                                        style = MaterialTheme.typography.headlineSmall
                                                    )
                                                }
                                            }
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Nueva playlist") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val created = PlaylistStorage.create(context, nameInput)
                    refreshPlaylists()
                    showCreateDialog = false
                    if (created != null) {
                        showPlaylists = true
                    }
                }) {
                    Text("Crear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renombrar playlist") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nuevo nombre") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renamePlaylistId?.let {
                        PlaylistStorage.rename(context, it, nameInput)
                    }
                    refreshPlaylists()
                    showRenameDialog = false
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    showAddSongDialog?.let { song ->
        AlertDialog(
            onDismissRequest = { showAddSongDialog = null },
            title = { Text("Añadir a playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("Primero crea una playlist en la pestaña Playlists.")
                } else {
                    Column {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    PlaylistStorage.addSong(
                                        context,
                                        playlist.id,
                                        PlaylistSong(
                                            id = song.id,
                                            title = song.title,
                                            artist = song.artist,
                                            duration = song.duration,
                                            uri = song.uri.toString()
                                        )
                                    )
                                    refreshPlaylists()
                                    showAddSongDialog = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    playlist.name,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${playlist.songs.size}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddSongDialog = null }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: SavedPlaylist,
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val playlistSongs = playlist.songs.mapNotNull { saved ->
        songs.find { it.uri.toString() == saved.uri }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onBack) {
            Text("‹ Volver a playlists")
        }

        Text(
            playlist.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            "${playlistSongs.size} canciones disponibles",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        if (playlistSongs.isEmpty()) {
            Text("Esta playlist está vacía o algunas canciones ya no están disponibles.")
        } else {
            Button(
                onClick = { onPlay(playlistSongs.first()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("▶  Reproducir playlist")
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(playlistSongs, key = { it.uri.toString() }) { song ->
                    val index = playlistSongs.indexOf(song)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArt(song.uri, Modifier.size(46.dp))

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onPlay(song) }
                                    .padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    song.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row {
                                    IconButton(
                                        onClick = { onMove(index, index - 1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Text("↑")
                                    }
                                    IconButton(
                                        onClick = {
                                            onMove(index, index + 1)
                                        },
                                        enabled = index < playlistSongs.lastIndex,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Text("↓")
                                    }
                                }

                                TextButton(
                                    onClick = { onRemove(song) },
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("Quitar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extractSoftAlbumColor(
    bitmap: android.graphics.Bitmap
): Color {
    val sample = android.graphics.Bitmap.createScaledBitmap(
        bitmap, 24, 24, true
    )

    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0

    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val pixel = sample.getPixel(x, y)

            // Ignorar píxeles transparentes.
            if (android.graphics.Color.alpha(pixel) < 128) continue

            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
    }

    if (sample !== bitmap) sample.recycle()

    if (count == 0) return Color(0xFF35313B)

    // Color medio de la portada.
    val r = (red / count).toInt()
    val g = (green / count).toInt()
    val b = (blue / count).toInt()

    // Aclarar el fondo mezclándolo con blanco al 30 %.
    val softR = (r * 0.70f + 255 * 0.30f).toInt()
    val softG = (g * 0.70f + 255 * 0.30f).toInt()
    val softB = (b * 0.70f + 255 * 0.30f).toInt()

    return Color(
        android.graphics.Color.rgb(softR, softG, softB)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayerScreen(
    song: Song,
    player: Player,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit
) {
    val context = LocalContext.current

    val albumBackground by produceState(
        initialValue = Color(0xFF35313B),
        key1 = song.uri
    ) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(context, song.uri)
                val bytes = retriever.embeddedPicture

                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size
                    )

                    if (bitmap != null) {
                        try {
                            extractSoftAlbumColor(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    } else {
                        Color(0xFF35313B)
                    }
                } else {
                    Color(0xFF35313B)
                }
            } catch (_: Exception) {
                Color(0xFF35313B)
            } finally {
                retriever.release()
            }
        }
    }

    val animatedBackground by animateColorAsState(
        targetValue = albumBackground,
        animationSpec = tween(durationMillis = 700),
        label = "albumBackground"
    )


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBackground)
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(
                    WindowInsetsSides.Bottom
                )
            )
    ){
        // Extiende el fondo dinámico detrás de la hora y la batería.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("‹ Volver")
                }

                Spacer(Modifier.weight(1f))

                Text(
                    "LIFONK MUSIC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))

            AlbumArt(
                song.uri,
                Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f)
            )

            Spacer(Modifier.height(24.dp))


            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Text(
                song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(22.dp))

            val safeDuration = duration.coerceAtLeast(1L)

            Slider(
                value = position.coerceIn(0L, safeDuration).toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..safeDuration.toFloat(),
                enabled = duration > 0L,
                modifier = Modifier.height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(2.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                        ),
                        trackInsideCornerSize = 1.dp,
                        drawStopIndicator = null,
                        drawTick = { _, _ -> }
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(position), color = Color.White)
                Text(formatDuration(duration), color = Color.White)
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    label = if (shuffleEnabled) "🔀" else "⇄",
                    selected = shuffleEnabled,
                    description = "Aleatorio",
                    onClick = onToggleShuffle
                )

                FilledTonalIconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(54.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Text("|◀", style = MaterialTheme.typography.titleLarge)
                }

                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(76.dp)
                ) {
                    IconButton(onClick = onTogglePlay) {
                        Text(
                            if (isPlaying) "Ⅱ" else "▶",
                            color = Color(0xFF29252E),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onNext,
                    modifier = Modifier.size(54.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Text("▶|", style = MaterialTheme.typography.titleLarge)
                }

                ControlButton(
                    label = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "↻¹"
                        Player.REPEAT_MODE_ALL -> "↻"
                        else -> "↻"
                    },
                    selected = repeatMode != Player.REPEAT_MODE_OFF,
                    description = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repetir canción"
                        Player.REPEAT_MODE_ALL -> "Repetir playlist"
                        else -> "Repetición desactivada"
                    },
                    onClick = onToggleRepeat
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repetir canción"
                    Player.REPEAT_MODE_ALL -> "Repetir cola"
                    else -> "Repetición desactivada"
                } + if (shuffleEnabled) " · Aleatorio activado" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    selected: Boolean,
    description: String,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) {
                Color.White.copy(alpha = 0.35f)
            } else {
                Color.White.copy(alpha = 0.18f)
            },
            contentColor = Color.White
        )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private fun loadSongs(context: android.content.Context): List<Song> {
    val result = mutableListOf<Song>()

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION
    )

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Audio.Media._ID
        )
        val titleColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Audio.Media.TITLE
        )
        val artistColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Audio.Media.ARTIST
        )
        val durationColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Audio.Media.DURATION
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)
                ?: "Canción sin título"
            val artist = cursor.getString(artistColumn)
                ?.takeUnless { it == "<unknown>" }
                ?: "Artista desconocido"
            val duration = cursor.getLong(durationColumn)

            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id
            )

            result.add(Song(id, title, artist, duration, uri))
        }
    }

    return result
}

@Composable
private fun AlbumArt(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = uri
    ) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Portada del álbum",
                contentScale = ContentScale.Fit,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "♫",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
