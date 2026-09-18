package com.ninthsoft.ime.ui.screen

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.input.handwriting.HandwritingEngineHolder
import com.ninthsoft.ime.input.handwriting.HandwritingManager
import com.ninthsoft.ime.input.handwriting.HwEngineMode
import com.ninthsoft.ime.input.handwriting.MlKitSupport
import com.ninthsoft.ime.ui.screen.ScreenComponent.ActionRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.ProgressButton
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Google 手写模型的**四态**。这四个状态刻意分开，因为它们对用户的含义完全不同：
 *
 * - [UNPROBED]：什么都没查过，别的信息一概不能推断；
 * - [DOWNLOADED_UNVERIFIED]：`RemoteModelManager` 说「已下载」，但**只是标记** ——
 *   实测踩过：下载中断留下的残缺模型，标记照样是已下载，每次识别都失败；
 * - [VERIFIED]：跑过一次真实的自检识别并通过，这才是「真的能用」；
 * - [INCOMPLETE]：标记说已下载、GMS 也在，但自检没过 —— 只能解释为模型残缺，
 *   修法是「重新下载」，而不是反复重试识别。
 *
 * 这些事实全部来自 [MlKitSupport] 与 [HandwritingManager] 的三态缓存，
 * 页面自己不落任何盘（写缓存是 [HandwritingEngineHolder] 的职责）。
 */
private enum class GoogleModelState {
    UNPROBED,
    DOWNLOADED_UNVERIFIED,
    VERIFIED,
    INCOMPLETE,
}

/** 下载按钮的三种形态。进度由 [HandwritingSettingsScreen] 的估算计时器驱动。 */
private sealed interface HwDownloadState {
    data object Idle : HwDownloadState
    data object Downloading : HwDownloadState
    data object Failed : HwDownloadState
}

/** 一次「只读事实」快照。取值都在 IO 线程上取（`Tasks.await` 是阻塞的）。 */
private class GoogleSnapshot(
    val gmsAvailable: Boolean,
    val state: GoogleModelState,
)

/**
 * 采集 Google 模型相关的只读事实。**必须在后台线程调用**（[MlKitSupport.isModelDownloaded] 阻塞）。
 *
 * [holderHint] 是 [HandwritingEngineHolder.googleHint]，也就是上一次解析时记下的失败原因。
 * 它在这里的作用是当「自检没过」的判据：GMS 在、标记已下载、解析跑完却没有通过 ——
 * 解析路径里能留下 hint 的只剩自检失败这一种，所以不用再猜字符串内容。
 */
private fun readGoogleSnapshot(context: Context, holderHint: String?): GoogleSnapshot {
    val gms = MlKitSupport.isPlayServicesAvailable(context)
    val downloaded = MlKitSupport.isModelDownloaded()
    val cachedUsable = HandwritingManager.googleUsable(context)
    val state = when {
        // 三态缓存为 true 的含义就是「标记已下载且自检通过」
        cachedUsable == true -> GoogleModelState.VERIFIED
        downloaded && gms && holderHint != null -> GoogleModelState.INCOMPLETE
        downloaded -> GoogleModelState.DOWNLOADED_UNVERIFIED
        else -> GoogleModelState.UNPROBED
    }
    return GoogleSnapshot(gms, state)
}

/**
 * 手写设置页。
 *
 * 与其它设置页的分工：这里**只做展示与转发**，引擎怎么选、什么时候加载、
 * 什么时候写探测缓存全部由 [HandwritingEngineHolder] 决定 ——
 * 设置页自己再算一遍的话，会出现「设置页说 Google 可用、面板却用了本地」这种对不上的情况。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(HandwritingManager.engineMode(context)) }
    var engineName by remember { mutableStateOf(HandwritingEngineHolder.engineName) }
    var isReady by remember { mutableStateOf(HandwritingEngineHolder.isReady) }
    var googleHint by remember { mutableStateOf(HandwritingEngineHolder.googleHint) }
    // Holder 没有公开 lastError（只有 googleHint），所以这里保存 ensureReady 回调里那一个。
    var lastError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var modelState by remember { mutableStateOf(GoogleModelState.UNPROBED) }
    var gmsAvailable by remember { mutableStateOf<Boolean?>(null) }
    var downloadState by remember { mutableStateOf<HwDownloadState>(HwDownloadState.Idle) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBtnWidth by remember { mutableStateOf(0.dp) }

    /**
     * 重新解析一遍引擎并刷新页面上的两处状态。
     *
     * 走 [HandwritingEngineHolder.ensureReady] 而不是自己调 MlKitSupport：
     * AUTO 的静默降级、GOOGLE 的不降级都发生在解析阶段，只有 Holder 知道「真正生效的是哪个」。
     * 代价是这一步可能触发下载（AUTO/GOOGLE 且模型没下载时，上限
     * [MlKitSupport.PROBE_TIMEOUT_SECONDS] 秒），所以只在用户点「立即检测」或刚改完模式时调用，
     * 进页面时不自动跑。
     */
    val refreshStatus: () -> Unit = {
        if (!probing) {
            probing = true
            HandwritingEngineHolder.ensureReady(context) { name, error ->
                engineName = name
                isReady = HandwritingEngineHolder.isReady
                googleHint = HandwritingEngineHolder.googleHint
                lastError = error
                probing = false
                // 解析刚跑完，此刻的 hint 才是「这一次」的结论，再取快照。
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        readGoogleSnapshot(context, HandwritingEngineHolder.googleHint)
                    }
                    gmsAvailable = snapshot.gmsAvailable
                    modelState = snapshot.state
                }
            }
        }
    }

    /** 清掉三态缓存与进程内的「下载已失败」标志后重跑，等价于「忘掉上次结论再来一次」。 */
    val redetect: () -> Unit = {
        HandwritingEngineHolder.resetGoogleProbe(context)
        refreshStatus()
    }

    /** 一键落到本地引擎。Holder 会把这个选择写进偏好（与 AUTO 的临时降级不同）。 */
    val switchToLocal: () -> Unit = {
        HandwritingEngineHolder.switchToLocal(context)
        mode = HandwritingManager.engineMode(context)
        refreshStatus()
    }

    val selectMode: (HwEngineMode) -> Unit = { next ->
        if (next != mode) {
            HandwritingManager.setEngineMode(context, next)
            mode = next
            // 偏好写下去后 IME 服务的监听会转发到 KeyboardWindowView.onConfigChanged，
            // 那边也会重新解析一次；这里再走一遍只是为了让本页立刻显示新的生效引擎（幂等）。
            refreshStatus()
        }
    }

    val startDownload: () -> Unit = download@{
        if (downloadState is HwDownloadState.Downloading) return@download
        downloadState = HwDownloadState.Downloading
        downloadProgress = 0f
        scope.launch {
            val limitSeconds = MlKitSupport.MANUAL_DOWNLOAD_TIMEOUT_SECONDS.toFloat()
            val startedAt = SystemClock.elapsedRealtime()
            // ML Kit 的 download() 只有「完成 / 未完成」，拿不到字节进度，
            // 所以这里按「已等时长 / 等待上限」画一个**估算**进度，并封顶 95%：
            // 宁可看着没下完，也不要显示 100% 之后还在等。
            val ticker = launch {
                while (isActive) {
                    val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
                    downloadProgress = (seconds / limitSeconds).coerceIn(0f, 0.95f)
                    delay(200)
                }
            }
            val ok = runCatching { MlKitSupport.downloadModel() }.getOrElse { error ->
                Timber.e(error, "手写模型下载异常")
                false
            }
            ticker.cancel()
            if (ok) {
                downloadProgress = 1f
                // 下载成功 ≠ 模型完整：清掉探测缓存让 Holder 重跑一遍自检，
                // 由它决定要不要把「确认可用」写进三态缓存（页面自己不写缓存）。
                HandwritingEngineHolder.resetGoogleProbe(context)
                refreshStatus()
                downloadState = HwDownloadState.Idle
            } else {
                // 超时/失败**不写三态缓存**：Tasks.await 超时并不会取消后台下载，
                // 网络恢复后重试本该能成功；写死失败会让用户永远用不上 Google。
                downloadState = HwDownloadState.Failed
            }
        }
    }

    // 进页面只取「不需要联网就能确定」的事实（标记、GMS、缓存），不触发探测/下载。
    LaunchedEffect(Unit) {
        val snapshot = withContext(Dispatchers.IO) {
            readGoogleSnapshot(context, HandwritingEngineHolder.googleHint)
        }
        gmsAvailable = snapshot.gmsAvailable
        modelState = snapshot.state
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.handwriting_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ----------------------------------------------------------
            // 引擎三选一
            // ----------------------------------------------------------
            SettingsGroup(title = stringResource(R.string.handwriting_engine_group)) {
                EngineModeRow(
                    title = stringResource(R.string.handwriting_engine_auto),
                    description = stringResource(R.string.handwriting_engine_auto_desc),
                    selected = mode == HwEngineMode.AUTO,
                    onClick = { selectMode(HwEngineMode.AUTO) },
                    showDivider = true,
                )
                EngineModeRow(
                    title = stringResource(R.string.handwriting_engine_google),
                    description = stringResource(R.string.handwriting_engine_google_desc),
                    selected = mode == HwEngineMode.GOOGLE,
                    onClick = { selectMode(HwEngineMode.GOOGLE) },
                    showDivider = true,
                )
                EngineModeRow(
                    title = stringResource(R.string.handwriting_engine_local),
                    description = stringResource(R.string.handwriting_engine_local_desc),
                    selected = mode == HwEngineMode.LOCAL,
                    onClick = { selectMode(HwEngineMode.LOCAL) },
                )
            }

            // ----------------------------------------------------------
            // 当前生效引擎
            // ----------------------------------------------------------
            val currentName = engineName
            val engineLabel = when {
                currentName != null -> currentName
                probing -> stringResource(R.string.handwriting_probing)
                else -> stringResource(R.string.handwriting_engine_not_ready)
            }
            // lastError 与 googleHint 经常是同一句话（显式 GOOGLE 失败时两边都会写），去重后再拼。
            val statusNote: String? = listOfNotNull(lastError, googleHint)
                .distinct()
                .joinToString("；")
                .takeIf { it.isNotEmpty() }

            SettingsGroup(title = stringResource(R.string.handwriting_status_group)) {
                ActionRow(
                    title = stringResource(R.string.handwriting_current_engine),
                    subtitle = engineLabel,
                    trailing = {
                        Button(
                            onClick = refreshStatus,
                            enabled = !probing,
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (probing) R.string.handwriting_probing
                                    else R.string.handwriting_check_now
                                ),
                                fontSize = 13.sp,
                            )
                        }
                    },
                )
                if (statusNote != null) {
                    Text(
                        text = statusNote,
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                // 显式选了 Google 但它起不来：AUTO 会自己降级，GOOGLE 不会 —— 这里给出唯一出口。
                if (mode == HwEngineMode.GOOGLE && !isReady && !probing) {
                    ActionRow(
                        title = stringResource(R.string.handwriting_google_unavailable),
                        subtitle = stringResource(R.string.handwriting_google_unavailable_desc),
                        trailing = {
                            TextButton(onClick = switchToLocal) {
                                Text(
                                    text = stringResource(R.string.handwriting_switch_to_local),
                                    fontSize = rowSubFontSize,
                                )
                            }
                        },
                    )
                }
            }

            // ----------------------------------------------------------
            // Google 模型状态
            // ----------------------------------------------------------
            val stateLabel = stringResource(
                when (modelState) {
                    GoogleModelState.UNPROBED -> R.string.handwriting_google_state_unknown
                    GoogleModelState.DOWNLOADED_UNVERIFIED -> R.string.handwriting_google_state_unverified
                    GoogleModelState.VERIFIED -> R.string.handwriting_google_state_verified
                    GoogleModelState.INCOMPLETE -> R.string.handwriting_google_state_incomplete
                }
            )
            val stateDesc = stringResource(
                when (modelState) {
                    GoogleModelState.UNPROBED -> R.string.handwriting_google_state_unknown_desc
                    GoogleModelState.DOWNLOADED_UNVERIFIED -> R.string.handwriting_google_state_unverified_desc
                    GoogleModelState.VERIFIED -> R.string.handwriting_google_state_verified_desc
                    GoogleModelState.INCOMPLETE -> R.string.handwriting_google_state_incomplete_desc
                }
            )

            SettingsGroup(title = stringResource(R.string.handwriting_google_group)) {
                ActionRow(
                    title = stringResource(R.string.handwriting_google_state_label),
                    subtitle = stringResource(R.string.handwriting_google_state_fmt, stateLabel, stateDesc),
                    trailing = {
                        when {
                            modelState == GoogleModelState.VERIFIED -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            downloadState is HwDownloadState.Downloading -> ProgressButton(
                                progress = downloadProgress,
                                width = downloadBtnWidth,
                            )

                            else -> Unit
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = redetect,
                        enabled = !probing && downloadState !is HwDownloadState.Downloading,
                    ) {
                        Text(
                            text = stringResource(R.string.handwriting_redetect),
                            fontSize = 13.sp,
                        )
                    }
                    // 下载中由上面的 ProgressButton 表示进度，这里收起来，避免两个进度指示并存。
                    if (downloadState !is HwDownloadState.Downloading) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = startDownload,
                            modifier = Modifier
                                .height(32.dp)
                                .onSizeChanged { size ->
                                    downloadBtnWidth = with(density) { size.width.toDp() }
                                },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (downloadState is HwDownloadState.Failed) {
                                        R.string.handwriting_download_retry
                                    } else {
                                        R.string.handwriting_download_model
                                    }
                                ),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                if (downloadState is HwDownloadState.Failed) {
                    Text(
                        text = stringResource(R.string.handwriting_download_failed),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (gmsAvailable == false) {
                    Text(
                        text = stringResource(R.string.handwriting_gms_missing),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // 本地模式不做 Google 探测（这是 Holder 的既定语义），检测按钮在这种模式下
                // 不会改变 Google 那两态，所以要说清楚，免得看起来像「检测没反应」。
                if (mode == HwEngineMode.LOCAL) {
                    Text(
                        text = stringResource(R.string.handwriting_local_mode_note),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Text(
                    text = stringResource(R.string.handwriting_google_note),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 单选一行：标题 + 一句「为什么选它」。选中时整行提色，和候选弹窗里的单选样式一致。 */
@Composable
private fun EngineModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = rowSubFontSize,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
