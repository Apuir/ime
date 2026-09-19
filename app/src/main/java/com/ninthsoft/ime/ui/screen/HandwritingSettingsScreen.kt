package com.ninthsoft.ime.ui.screen

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
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
import com.ninthsoft.ime.ui.screen.ScreenComponent.SliderRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Google 手写模型的**五态**。这几个状态刻意分开，因为它们对用户的含义完全不同：
 *
 * - [QUERY_FAILED]：连「下载没下载」都读不到（Play 服务异常）；
 * - [NOT_DOWNLOADED]：确认没下载过；
 * - [DOWNLOADED_UNVERIFIED]：Play 服务说「已下载」（模型文件在本应用内），但还没跑过自检 ——
 *   实测踩过：下载中断留下的残缺模型，标记照样是已下载，每次识别都失败；
 * - [VERIFIED]：跑过一次真实的自检识别并通过，这才是「真的能用」；
 * - [INCOMPLETE]：标记说已下载、GMS 也在，但自检没过 —— 只能解释为模型残缺，
 *   修法是「重新下载」，而不是反复重试识别。
 *
 * 这些事实全部来自 [MlKitSupport] 与 [HandwritingManager] 的三态缓存，
 * 页面自己不落任何盘（写缓存是 [HandwritingEngineHolder] 的职责）。
 */
private enum class GoogleModelState {
    QUERY_FAILED,
    NOT_DOWNLOADED,
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
    val queryError: String?,
)

/**
 * 采集 Google 模型相关的只读事实。**必须在后台线程调用**（[MlKitSupport.queryModelDownloaded] 阻塞）。
 *
 * [holderHint] 是 [HandwritingEngineHolder.googleHint]，也就是上一次解析时记下的失败原因。
 * 它在这里的作用是当「自检没过」的判据：GMS 在、标记已下载、解析跑完却没有通过 ——
 * 解析路径里能留下 hint 的只剩自检失败这一种，所以不用再猜字符串内容。
 */
private fun readGoogleSnapshot(context: Context, holderHint: String?): GoogleSnapshot {
    val gms = MlKitSupport.isPlayServicesAvailable(context)
    val downloaded = MlKitSupport.queryModelDownloaded()
    val cachedUsable = HandwritingManager.googleUsable(context)
    val state = when {
        // 三态缓存为 true 的含义就是「标记已下载且自检通过」
        cachedUsable == true -> GoogleModelState.VERIFIED
        downloaded == true && gms && holderHint != null -> GoogleModelState.INCOMPLETE
        downloaded == true -> GoogleModelState.DOWNLOADED_UNVERIFIED
        // 查询失败不能当成「没下载」：那会让页面去引导一次注定失败的下载
        downloaded == null -> GoogleModelState.QUERY_FAILED
        else -> GoogleModelState.NOT_DOWNLOADED
    }
    return GoogleSnapshot(gms, state, MlKitSupport.lastQueryError)
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
    var modelState by remember { mutableStateOf(GoogleModelState.NOT_DOWNLOADED) }
    var gmsAvailable by remember { mutableStateOf<Boolean?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }
    var downloadState by remember { mutableStateOf<HwDownloadState>(HwDownloadState.Idle) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBtnWidth by remember { mutableStateOf(0.dp) }
    var deleting by remember { mutableStateOf(false) }
    var deleteConfirming by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    /** 上一次「立即检测」的结论与时刻：结果与上次一样时，靠时刻也能看出这一按生效了。 */
    var lastCheck by remember { mutableStateOf<String?>(null) }

    /**
     * 停手识别时长，滑杆用「秒」表示。
     *
     * 进来时从偏好读一次、之后只在本页内维护：偏好写下去后由 IME 服务的偏好监听转发给
     * `KeyboardWindowView.onConfigChanged`，面板会重读一次（见 `refreshRecognizeDelay`），
     * 本页不需要再回读。
     */
    var recognizeDelaySeconds by remember {
        mutableStateOf(HandwritingManager.recognizeDelayMs(context) / 1000f)
    }

    /**
     * 重新解析一遍引擎并刷新页面上的两处状态。
     *
     * 走 [HandwritingEngineHolder.ensureReady] 而不是自己调 MlKitSupport：
     * AUTO 的静默降级、GOOGLE 的不降级都发生在解析阶段，只有 Holder 知道「真正生效的是哪个」。
     * 代价是这一步可能触发下载（AUTO/GOOGLE 且模型没下载时，上限
     * [MlKitSupport.PROBE_TIMEOUT_SECONDS] 秒），所以只在用户点「立即检测」、刚改完模式，
     * 或进页面时「标记说已下载」（那一条不可能触发下载）时才调用。
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
                lastCheck = buildString {
                    append(
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    ).append(" · ")
                    if (name != null) {
                        append("引擎 ").append(name)
                        if (error != null) append("（").append(error).append("）")
                    } else {
                        append("不可用")
                        if (error != null) append("：").append(error)
                    }
                }
                // 解析刚跑完，此刻的 hint 才是「这一次」的结论，再取快照。
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        readGoogleSnapshot(context, HandwritingEngineHolder.googleHint)
                    }
                    gmsAvailable = snapshot.gmsAvailable
                    modelState = snapshot.state
                    queryError = snapshot.queryError
                    // 模型最终可用时，之前那次下载失败就不再是结论：下载任务失败不等于
                    // 模型不能用（已经下过、或只缺一小块被补回，都是这个结果）。
                    if (snapshot.state == GoogleModelState.VERIFIED) {
                        downloadState = HwDownloadState.Idle
                    }
                }
            }
        }
    }

    /** 取一次快照（不触发解析 / 下载）：进页面、删除模型、下载结束后的只读刷新。 */
    val refreshSnapshot: suspend () -> Unit = {
        val snapshot = withContext(Dispatchers.IO) {
            readGoogleSnapshot(context, HandwritingEngineHolder.googleHint)
        }
        gmsAvailable = snapshot.gmsAvailable
        modelState = snapshot.state
        queryError = snapshot.queryError
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
        queryError = null
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
            withContext(Dispatchers.IO) {
                runCatching { MlKitSupport.downloadModel() }.getOrElse { error ->
                    Timber.e(error, "手写模型下载异常")
                }
            }
            // 判据不是下载任务的返回值，而是「模型现在到底能不能用」：下载任务会因为
            // 「已经下载过」或服务端拒绝而直接失败，模型却完全可用。
            // 这里走 verifyNow（只判定、不再下载），拿到的是本机现状的结论；
            // 进度条在这期间继续走，别让用户以为卡住了。
            val (name, error) = awaitVerifyNow(context)
            engineName = name
            isReady = HandwritingEngineHolder.isReady
            googleHint = HandwritingEngineHolder.googleHint
            lastError = error
            refreshSnapshot()
            ticker.cancel()
            if (modelState == GoogleModelState.VERIFIED) {
                downloadProgress = 1f
                downloadState = HwDownloadState.Idle
            } else {
                downloadState = HwDownloadState.Failed
            }
        }
    }

    /**
     * 删除已下载的模型。
     *
     * 删的是模型文件本身（`files/mlkit_digital_ink_recognition/…`，ML Kit 按需下载下来的），
     * 不是「标记」；删完必须重新下载才能再用 Google 引擎，本地引擎不受影响。
     */
    val deleteModel: () -> Unit = delete@{
        if (deleting) return@delete
        deleting = true
        deleteError = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { MlKitSupport.deleteModel() }.getOrElse { error ->
                    Timber.e(error, "删除手写模型异常")
                    false
                }
            }
            deleting = false
            if (ok) {
                // 探测缓存必须一起清：留着「确认可用」会让 Holder 继续用已经不存在的模型。
                HandwritingManager.setGoogleUsable(context, null)
                HandwritingEngineHolder.reset()
                downloadState = HwDownloadState.Idle
                // 立刻按本机现状重新判定一次：这时引擎**必须**从 Google 掉到本地，
                // 否则页面上那个「引擎 Google」和实际删光了的模型对不上。
                val (name, error) = awaitVerifyNow(context)
                engineName = name
                isReady = HandwritingEngineHolder.isReady
                googleHint = HandwritingEngineHolder.googleHint
                lastError = error
                Toast.makeText(context, R.string.handwriting_delete_done, Toast.LENGTH_SHORT).show()
            } else {
                deleteError = MlKitSupport.lastDeleteError
            }
            refreshSnapshot()
        }
    }

    // 进页面只取「不需要联网就能确定」的事实（标记、GMS、缓存），不触发下载。
    LaunchedEffect(Unit) {
        refreshSnapshot()
        // 标记说「已下载」但还没跑过自检时，顺手解析一次：不解析就永远停在
        // 「已下载但未确认」这个对用户没有结论的状态上，而这时解析不可能触发下载
        // （标记为已下载时解析路径不会走下载分支），代价只有一次自检识别。
        if (modelState == GoogleModelState.DOWNLOADED_UNVERIFIED && mode != HwEngineMode.LOCAL) {
            refreshStatus()
        }
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
            // 识别时机（停手多久算「写完一个字」）
            // ----------------------------------------------------------
            SettingsGroup(title = stringResource(R.string.handwriting_timing_group)) {
                SliderRow(
                    title = stringResource(R.string.handwriting_recognize_delay),
                    value = recognizeDelaySeconds,
                    valueLabel = stringResource(
                        R.string.handwriting_recognize_delay_value, recognizeDelaySeconds
                    ),
                    range = (HandwritingManager.RECOGNIZE_DELAY_MS_MIN / 1000f)..
                        (HandwritingManager.RECOGNIZE_DELAY_MS_MAX / 1000f),
                    onValueChange = {
                        // 量化到 0.1 秒一档：连续值会滑出 0.63 秒这种既没法复现、
                        // 也没法在文档里描述的档位（与「上滑触发距离」滑杆同款做法）。
                        val quantized = (it * 10f).roundToInt() / 10f
                        recognizeDelaySeconds = quantized
                        HandwritingManager.setRecognizeDelayMs(
                            context, (quantized * 1000f).roundToInt()
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.handwriting_recognize_delay_desc),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
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
                // 检测结论与上次一样时界面本来不会有任何变化，看起来就像「点了没反应」；
                // 带上时刻，这一按到底有没有生效一眼可辨。
                lastCheck?.let { check ->
                    Text(
                        text = stringResource(R.string.handwriting_last_check, check),
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
                    GoogleModelState.QUERY_FAILED -> R.string.handwriting_google_state_query_failed
                    GoogleModelState.NOT_DOWNLOADED -> R.string.handwriting_google_state_not_downloaded
                    GoogleModelState.DOWNLOADED_UNVERIFIED -> R.string.handwriting_google_state_unverified
                    GoogleModelState.VERIFIED -> R.string.handwriting_google_state_verified
                    GoogleModelState.INCOMPLETE -> R.string.handwriting_google_state_incomplete
                }
            )
            val stateDesc = stringResource(
                when (modelState) {
                    GoogleModelState.QUERY_FAILED -> R.string.handwriting_google_state_query_failed_desc
                    GoogleModelState.NOT_DOWNLOADED -> R.string.handwriting_google_state_not_downloaded_desc
                    GoogleModelState.DOWNLOADED_UNVERIFIED -> R.string.handwriting_google_state_unverified_desc
                    GoogleModelState.VERIFIED -> R.string.handwriting_google_state_verified_desc
                    GoogleModelState.INCOMPLETE -> R.string.handwriting_google_state_incomplete_desc
                }
            )
            // 只在「GMS 那边的标记说已下载」时才提供删除：没下载过就没什么可删的。
            val hasModelMarker = modelState == GoogleModelState.DOWNLOADED_UNVERIFIED ||
                modelState == GoogleModelState.VERIFIED ||
                modelState == GoogleModelState.INCOMPLETE
            val busy = deleting || downloadState is HwDownloadState.Downloading

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
                        enabled = !probing && !busy,
                    ) {
                        Text(
                            text = stringResource(R.string.handwriting_redetect),
                            fontSize = 13.sp,
                        )
                    }
                    if (hasModelMarker) {
                        TextButton(
                            onClick = { deleteConfirming = true },
                            enabled = !busy,
                        ) {
                            Text(
                                text = stringResource(R.string.handwriting_delete_model),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    // 下载中由上面的 ProgressButton 表示进度，这里收起来，避免两个进度指示并存。
                    // 已确认可用时也不显示：模型已经能用，再点一次下载没有意义
                    // （Play 服务多半只会回一张「已经下过」的失败回执）。
                    if (modelState != GoogleModelState.VERIFIED &&
                        downloadState !is HwDownloadState.Downloading
                    ) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = startDownload,
                            enabled = !deleting,
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

                // 失败一定要带上 Play 服务给的原话：只说「未完成」用户没法判断是网络、
                // 是标记失效，还是模型残缺，「重试」也就变成了盲点。
                // 判据是「这一次下载的结论 + 模型当前能不能用」两条：模型最终可用时不报失败，
                // 否则「失败」与「确认可用」会并排出现，互相打架。
                if (downloadState is HwDownloadState.Failed &&
                    modelState != GoogleModelState.VERIFIED
                ) {
                    MlKitSupport.lastDownloadError?.let { reason ->
                        Text(
                            text = reason,
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }

                queryError?.let { reason ->
                    Text(
                        text = reason,
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                deleteError?.let { reason ->
                    Text(
                        text = stringResource(R.string.handwriting_delete_failed, reason),
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

    if (deleteConfirming) {
        AlertDialog(
            onDismissRequest = { deleteConfirming = false },
            title = { Text(stringResource(R.string.handwriting_delete_model)) },
            text = { Text(stringResource(R.string.handwriting_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirming = false
                    deleteModel()
                }) {
                    Text(stringResource(R.string.handwriting_delete_model))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * 把 [HandwritingEngineHolder.verifyNow] 的回调包成挂起。
 *
 * Holder 保证一定回调（解析里的异常也被兜成失败），所以这里不会永久挂起。
 */
private suspend fun awaitVerifyNow(context: Context): Pair<String?, String?> =
    suspendCancellableCoroutine { continuation ->
        HandwritingEngineHolder.verifyNow(context) { name, error ->
            if (continuation.isActive) continuation.resume(name to error)
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
