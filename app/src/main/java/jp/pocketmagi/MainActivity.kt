package jp.pocketmagi

import jp.pocketmagi.ui.LogDialog
import android.os.Bundle
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.NavigationBarDefaults
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import jp.pocketmagi.engine.Phase
import jp.pocketmagi.ui.HistoryScreen
import jp.pocketmagi.ui.MagiColors
import jp.pocketmagi.ui.MagiScreen
import jp.pocketmagi.ui.ModelsScreen
import jp.pocketmagi.ui.Palettes
import jp.pocketmagi.ui.PocketMagiTheme
import jp.pocketmagi.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MagiViewModel by viewModels()

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    companion object {
        /** Whether the app is on screen (result notifications are only posted when it is not). */
        @Volatile var visible = false
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text or a URL shared from another app goes into the question field. */
    private fun handleShare(i: android.content.Intent?) {
        if (i?.action != android.content.Intent.ACTION_SEND) return
        val text = i.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = i.getStringExtra(android.content.Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val q = when {
            text.isBlank() -> subject
            subject.isNotBlank() && !text.contains(subject) && text.startsWith("http") -> "$subject\n$text"
            else -> text
        }
        if (q.isNotBlank()) vm.shareIn(q)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        jp.pocketmagi.ui.CustomPalettes.load(this)
        handleShare(intent)
        // download progress is shown as a notification (Android 13+ asks once)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val settings by vm.settings.collectAsState()
            val fonts by vm.fonts.collectAsState()
            val bodyFont = remember(settings.bodyFontId, fonts) { vm.fontStore.family(settings.bodyFontId) ?: FontFamily.Default }
            val headFont = remember(settings.headFontId, fonts) { vm.fontStore.family(settings.headFontId) ?: FontFamily.Monospace }
            PocketMagiTheme(Palettes.byId(settings.theme), bodyFont, headFont) {
                val st by vm.state.collectAsState()
                // keep the screen (and CPU at full speed) on while the sages deliberate
                LaunchedEffect(st.phase) {
                    if (st.phase == Phase.RUNNING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Back never closes the app: it is sent to the background like Home,
                // so a running deliberation and the current screen are kept.
                Box(Modifier.fillMaxSize()) {
                    AppRoot(vm, onBackAtRoot = { moveTaskToBack(true) })
                    LaunchOverlay()
                }
            }
        }
    }
}

private val TABS = listOf("◆" to "審議", "≡" to "記録", "⬇" to "モデル", "⚙" to "設定")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRoot(vm: MagiViewModel, onBackAtRoot: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // other tabs -> back to 審議; on 審議 -> background (dialogs and the keyboard handle back themselves first)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BackHandler {
        when {
            imeVisible -> { keyboard?.hide(); focus.clearFocus() }   // back first closes the keyboard
            tab != 0 -> tab = 0
            else -> onBackAtRoot()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.message.collectAsState()
    val crash by vm.crashReport.collectAsState()
    val pendingUpdates by vm.offers.collectAsState()
    val wantTab by vm.requestTab.collectAsState()
    LaunchedEffect(wantTab) { wantTab?.let { tab = it; vm.requestTab.value = null } }
    crash?.let { r ->
        LogDialog(
            title = "前回、処理中にアプリが終了しました",
            message = "場所: ${r.stage}" +
                (if (r.onGpu) "\nGPU処理中だったため、このモデルだけ以後はCPUで動かします（設定の「GPUを使わないモデル」から戻せます）。" else ""),
            log = r.logTail,
            onDismiss = { vm.dismissCrash() },
        )
    }
    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    // first launch with nothing installed: open the models tab
    LaunchedEffect(Unit) {
        if (vm.catalog.none { vm.store.local(it) != null }) tab = 2
    }

    // the whole screen (tab bar included) sits above the keyboard, so tabs stay usable while typing
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MagiColors.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MagiColors.Panel,
                // no system-bar gap under the tabs while the keyboard is up
                windowInsets = if (imeVisible) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets,
            ) {
                TABS.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Text(if (i == 2 && pendingUpdates.isNotEmpty()) "$icon•" else icon, fontSize = 18.sp)
                        },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MagiColors.Amber, selectedTextColor = MagiColors.Amber,
                            unselectedIconColor = MagiColors.TextDim, unselectedTextColor = MagiColors.TextDim,
                            indicatorColor = MagiColors.AmberDim.copy(alpha = 0.4f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            when (tab) {
                0 -> MagiScreen(vm)
                1 -> HistoryScreen(vm)
                2 -> ModelsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}


/** Full PersonalMAGI logo shown briefly on a cold start (tap to skip). */
@Composable
private fun LaunchOverlay() {
    var shown by rememberSaveable { mutableStateOf(true) }
    var visible by remember { mutableStateOf(shown) }
    LaunchedEffect(Unit) {
        if (shown) { delay(1400); visible = false; delay(400); shown = false }
    }
    if (!shown) return
    AnimatedVisibility(visible = visible, enter = EnterTransition.None, exit = fadeOut(tween(400))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF161616)).clickable { visible = false; shown = false },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.launch_logo), contentDescription = "PersonalMAGI",
                modifier = Modifier.fillMaxWidth(0.82f), contentScale = ContentScale.Fit,
            )
        }
    }
}
