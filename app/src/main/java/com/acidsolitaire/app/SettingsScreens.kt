package com.acidsolitaire.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A full-screen window, used for every settings-style screen. */
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
        }
    }
}

/** A title bar with a back arrow (and optional actions on the right) above scrolling content. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

private enum class Route { Main, Backgrounds, Albums, CardBack }

/** Settings: game options, sound volume, backgrounds, card back and About. */
@Composable
fun SettingsDialog(game: GameStore) {
    var route by remember { mutableStateOf(Route.Main) }
    val back = {
        when (route) {
            Route.Main -> game.showSettings = false
            Route.Albums -> route = Route.Backgrounds
            else -> route = Route.Main
        }
    }
    FullScreenDialog(onDismiss = back) {
        when (route) {
            Route.Main -> ScreenFrame("Settings", back) {
                SettingsMain(game, { route = Route.Backgrounds }, { route = Route.CardBack })
            }
            Route.Backgrounds -> ScreenFrame("Backgrounds", back) {
                BackgroundSettings(game) { route = Route.Albums }
            }
            Route.Albums -> AlbumPicker(game, back) { route = Route.Backgrounds }
            Route.CardBack -> ScreenFrame("Card Back", back) { CardBackSettings(game) }
        }
    }
}

/** The background screens, opened from the board's options menu. */
@Composable
fun BackgroundsDialog(game: GameStore) {
    var albums by remember { mutableStateOf(false) }
    val back = { if (albums) albums = false else game.showBackgrounds = false }
    FullScreenDialog(onDismiss = back) {
        if (albums) {
            AlbumPicker(game, back) { albums = false }
        } else {
            ScreenFrame("Backgrounds", back) { BackgroundSettings(game) { albums = true } }
        }
    }
}

@Composable
private fun SettingsMain(game: GameStore, openBackgrounds: () -> Unit, openCardBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader("Game")
        Text("Klondike deal")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(selected = game.dealStyle == 1, onClick = { game.updateDealStyle(1) }, label = { Text("Deal One Card") })
            FilterChip(selected = game.dealStyle == 3, onClick = { game.updateDealStyle(3) }, label = { Text("Deal Three Cards") })
        }
        Text("New Spider deals", modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(selected = game.spiderSuits == 1, onClick = { game.updateSpiderSuits(1) }, label = { Text("One suit") })
            FilterChip(selected = game.spiderSuits == 2, onClick = { game.updateSpiderSuits(2) }, label = { Text("Two suits") })
            FilterChip(selected = game.spiderSuits == 4, onClick = { game.updateSpiderSuits(4) }, label = { Text("Four suits") })
        }

        SectionHeader("Sound")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeMute, contentDescription = null)
            Slider(
                value = game.soundVolume,
                onValueChange = { game.updateSoundVolume(it) },
                // Plays a sample when the slider is released, so the new level can be heard.
                onValueChangeFinished = { game.previewSound() },
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        }

        SectionHeader("Appearance")
        NavRow("Backgrounds", openBackgrounds)
        HorizontalDivider()
        NavRow("Card Back", openCardBack)

        SectionHeader("About")
        Text("Created in 2026 by Cody and Rob.")
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

// region Backgrounds

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackgroundCell(
    store: BackgroundStore, id: String, selected: Boolean, onSelect: () -> Unit, onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val thumbnail by produceState<ImageBitmap?>(null, id) { value = store.thumbnail(id) }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .aspectRatio(1.3f)
            // Clipping first keeps the picture, and the area that reacts to a touch, inside the cell.
            .clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) accent else Color.Gray.copy(alpha = 0.4f), shape)
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
    ) {
        val key = store.builtInKey(id)
        val loaded = thumbnail
        if (key != null) {
            Image(painterResource(backgroundResource(key)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (loaded != null) {
            Image(loaded, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = "Selected", tint = accent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.White, RoundedCornerShape(50)),
            )
        }
    }
}

/** Pick a background for all games, add any number of photos, and add whole albums. */
@Composable
fun BackgroundSettings(game: GameStore, openAlbums: () -> Unit) {
    val store = game.backgrounds
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = store.importPhotos(uris, true) { index, total -> progress = "Importing $index of $total…" }
                progress = null
                if (added == 0) failed = true
            }
        }
    }
    val albumIds = store.albumIDs
    val albums by produceState(emptyList(), albumIds) {
        value = withContext(Dispatchers.IO) { AlbumLibrary.albums(context).filter { it.id in albumIds } }
    }
    val current = store.currentId(game.selectedGame)
    val rows = store.gridIDs.chunked(3)

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Background for all games", style = MaterialTheme.typography.titleMedium) }
        items(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (id in row) {
                    BackgroundCell(
                        store, id, selected = current == id, onSelect = { store.select(id) },
                        onLongPress = { if (store.isPhoto(id)) removeId = id }, modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Text(
                if (store.photoIDs.isNotEmpty()) "Touch and hold a photo to remove it."
                else if (!store.hasUserBackgrounds) "Add your own photos or albums and they replace the built-in artwork."
                else "Your albums are in use.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (store.hasUserBackgrounds) {
            item {
                OutlinedButton(onClick = { store.shuffle(game.selectedGame) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pick a random one now")
                }
            }
        }
        item {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = progress == null, modifier = Modifier.fillMaxWidth(),
            ) { Text("Add Photos") }
        }
        progress?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item {
            OutlinedButton(onClick = openAlbums, modifier = Modifier.fillMaxWidth()) { Text("Add Whole Albums") }
        }
        if (albums.isNotEmpty()) {
            item { Text("Albums in use", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
            items(albums) { album ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(album.title)
                        Text("${album.count} photos", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { store.removeAlbum(album.id) }) { Text("Remove") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Change background after each win", modifier = Modifier.weight(1f))
                Switch(checked = store.rotateAfterWin, onCheckedChange = { store.updateRotateAfterWin(it) })
            }
        }
        item {
            Text(
                "Each win picks a random new background from your photos and albums, or from the built-in artwork if you haven’t added any.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    removeId?.let { id ->
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text("Remove this photo?") },
            confirmButton = { TextButton(onClick = { store.removePhoto(id); removeId = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
        )
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text("Couldn’t load those photos") },
            confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
        )
    }
}

/**
 * Lists the photo albums so whole albums can be used as backgrounds. Draws its own title bar so the
 * Save button sits at the top, where it can't be hidden behind the system navigation bar.
 */
@Composable
fun AlbumPicker(game: GameStore, onBack: () -> Unit, onDone: () -> Unit) {
    val store = game.backgrounds
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AlbumLibrary.hasAccess(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = AlbumLibrary.hasAccess(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(AlbumLibrary.permissionsToRequest())
    }
    val albums by produceState<List<AlbumSummary>?>(null, granted) {
        if (granted) value = withContext(Dispatchers.IO) { AlbumLibrary.albums(context) }
    }
    val chosen = remember { mutableStateListOf<String>().also { it.addAll(store.albumIDs) } }
    val list = albums

    ScreenFrame(
        "Albums", onBack,
        actions = {
            if (granted && list != null) {
                TextButton(onClick = {
                    val kept = store.albumIDs.filter { it in chosen }
                    val added = list.map { it.id }.filter { it in chosen && it !in kept }
                    store.setAlbums(kept + added, true)
                    onDone()
                }) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        },
    ) {
        if (!granted) {
            AlbumAccessOff()
        } else if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading albums…") }
        } else {
            AlbumList(list, chosen)
        }
    }
}

@Composable
private fun AlbumAccessOff() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Photo access is off", style = MaterialTheme.typography.titleMedium)
        Text(
            "Allow access to photos in Settings to use whole albums as backgrounds.",
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            )
        }) { Text("Open Settings") }
    }
}

@Composable
private fun AlbumList(list: List<AlbumSummary>, chosen: MutableList<String>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        if (list.isEmpty()) item { Text("No albums with photos were found.") }
        items(list) { album ->
            val on = album.id in chosen
            Row(
                Modifier.fillMaxWidth().clickable { if (on) chosen.remove(album.id) else chosen.add(album.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlbumThumbnail(album)
                Column(Modifier.weight(1f)) {
                    Text(album.title, fontWeight = FontWeight.Medium)
                    Text("${album.count} photos", style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (on) "Selected" else "Not selected",
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text(
                "Tap albums to select them, then tap Save. Every photo in a chosen album can appear as a background, and each win picks one at random. Photos added to the album later are included.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AlbumThumbnail(album: AlbumSummary) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, album.id) {
        value = withContext(Dispatchers.IO) {
            AlbumLibrary.load(context, album.newestMediaId, 160)?.asImageBitmap()
        }
    }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(alpha = 0.25f))) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// endregion

// region Card backs

@Composable
private fun BackTile(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, art: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White)
                .padding(2.5.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(if (selected) 3.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(5.dp))
        ) { art() }
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Ten built-in designs, or a photo from the player's own library. */
@Composable
fun CardBackSettings(game: GameStore) {
    val store = game.cardBacks
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (!store.setCustom(context, uri)) failed = true }
    }
    val custom = store.customImage
    // Ten designs, then the player's own photo when there is one.
    val tiles = CardBackStyle.entries.map<CardBackStyle, Any> { it } + (if (custom != null) listOf<Any>(CardBackStore.CUSTOM) else emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Choose a card back", style = MaterialTheme.typography.titleMedium)
        for (row in tiles.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (tile in row) {
                    if (tile is CardBackStyle) {
                        BackTile(tile.title, store.choice == tile.name, { store.choose(tile.name) }, Modifier.weight(1f)) {
                            CardBackArt(tile, Modifier.fillMaxSize())
                        }
                    } else if (custom != null) {
                        BackTile("My Photo", store.usesCustom, { store.choose(CardBackStore.CUSTOM) }, Modifier.weight(1f)) {
                            Image(custom, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (custom == null) "Use One of My Photos" else "Choose a Different Photo") }
        if (custom != null) {
            OutlinedButton(onClick = { store.removeCustom() }, modifier = Modifier.fillMaxWidth()) { Text("Remove My Photo") }
        }
        Text("Your photo is cropped to fit the card.", style = MaterialTheme.typography.bodySmall)
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text("Couldn’t use that photo") },
            confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
        )
    }
}

// endregion
