package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.ngram.GramModelDownloader
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.schemaLayoutTag
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.RimeConfig
import com.ninthsoft.ime.engine.rime.data.DataManager
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlot
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlotPlan
import com.ninthsoft.ime.input.keyboard.slot.SlotSwitchItem
import com.ninthsoft.ime.input.keyboard.slot.buildChineseSlotItems
import com.ninthsoft.ime.ui.screen.ScreenComponent.ActionRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.ProgressButton
import com.ninthsoft.ime.ui.screen.ScreenComponent.SectionHeader
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import com.ninthsoft.ime.ui.theme.ExpressiveShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 输入方案设置页：键盘只有两个槽。
 *
 * - 中文槽：平铺列出方案里真的有的输入方式（九键 / 26键 / 15键 / 手写）。同一个输入方式
 *   有多个方案时全部列出来（九键1 / 九键2）；方案里没有的布局整项不出现。
 * - 英文槽：固定 `wanxiang_english` + Qwerty，只展示不可改 —— ascii 输入留在英文方案内部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 方案列表是阻塞读（会逐个开方案配置取 candidateKind），读到之后就不再重复读。
    var allSchemas by remember { mutableStateOf(EngineFactory.current()?.schemasList() ?: emptyList()) }
    val chineseItems = remember(allSchemas) { buildChineseSlotItems(context, allSchemas) }
    val englishSchema = remember(allSchemas) {
        allSchemas.find { it.id == KeyboardManager.Slot.ENGLISH_SCHEMA_ID }
    }

    // 引擎冷启动 / 首次部署时首帧可能读不到方案：补轮询几次。
    // 少了这段，页面会一直停在「全部缺方案」的占位列表上，用户以为方案丢了。
    LaunchedEffect(Unit) {
        if (allSchemas.isNotEmpty()) return@LaunchedEffect
        repeat(30) {
            delay(500)
            val schemas = withContext(Dispatchers.IO) {
                EngineFactory.current()?.schemasList() ?: emptyList()
            }
            if (schemas.isNotEmpty()) {
                allSchemas = schemas
                return@LaunchedEffect
            }
        }
    }

    val activeSlot = remember { KeyboardManager.Slot.getActiveSlot(context) }
    var chineseKeyboard by remember { mutableStateOf(KeyboardManager.Slot.getChineseKeyboard(context)) }
    var chineseSchemaId by remember { mutableStateOf(KeyboardManager.Slot.getChineseSchemaId(context)) }
    // 偏好为空或已失效时，高亮的应该是键盘真正会用的那一项（回落结果）。
    val selectedItem = remember(chineseItems, chineseKeyboard, chineseSchemaId) {
        KeyboardSlotPlan.resolve(chineseItems, chineseKeyboard, chineseSchemaId)
    }

    var grammarLanguage by remember { mutableStateOf<String?>(null) }
    var grammarReady by remember { mutableStateOf(false) }
    var grammarDownloading by remember { mutableStateOf(false) }
    var grammarProgress by remember { mutableFloatStateOf(0f) }
    var grammarFailed by remember { mutableStateOf(false) }
    var grammarButtonWidth by remember { mutableStateOf(0.dp) }

    fun selectChinese(item: SlotSwitchItem) {
        KeyboardManager.Slot.setChineseSelection(context, item.keyboardName, item.schemaId)
        chineseKeyboard = item.keyboardName
        chineseSchemaId = item.schemaId
    }

    fun downloadGrammar(language: String) {
        if (grammarDownloading) return
        grammarDownloading = true
        grammarFailed = false
        grammarProgress = 0f
        scope.launch {
            val success = GramModelDownloader.download(language) { downloaded, total ->
                scope.launch(Dispatchers.Main.immediate) {
                    grammarProgress = if (total > 0L) {
                        downloaded.toFloat() / total.toFloat()
                    } else {
                        0f
                    }
                }
            }
            grammarDownloading = false
            grammarReady = success && File(DataManager.sharedDataDir, "$language.gram").isFile
            grammarFailed = !success
            if (grammarReady) {
                // The grammar database is loaded during engine startup.
                EngineFactory.current()?.reload()
            }
        }
    }

    LaunchedEffect(Unit) {
        val language = withContext(Dispatchers.IO) {
            runCatching {
                (EngineFactory.current() as? IRimeJob)?.awaitJob<String?>(null) {
                    val currentSchema = currentSchema()
                    RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                        config.getString("grammar/language")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }.getOrNull()
        }
        grammarLanguage = language
        if (language != null) {
            grammarReady = File(DataManager.sharedDataDir, "$language.gram").isFile
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.schema_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
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

            SettingsGroup(title = stringResource(R.string.schema_model)) {
                ActionRow(
                    title = stringResource(R.string.schema_model_grammar),
                    subtitle = when {
                        grammarLanguage == null -> stringResource(R.string.schema_grammar_model_unavailable)
                        grammarReady -> stringResource(R.string.schema_grammar_model_ready)
                        grammarFailed -> stringResource(R.string.schema_grammar_model_failed)
                        else -> stringResource(R.string.schema_grammar_model_desc)
                    },
                    trailing = {
                        when {
                            grammarReady -> Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            grammarDownloading -> ProgressButton(
                                grammarProgress, width = grammarButtonWidth
                            )

                            grammarLanguage != null -> Button(
                                onClick = { downloadGrammar(grammarLanguage!!) },
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        grammarButtonWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(R.string.download), fontSize = rowSubFontSize)
                            }
                        }
                    },
                )
            }

            SectionHeader(stringResource(R.string.slot_section_chinese))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    chineseItems.forEach { item ->
                        ChineseSlotRow(
                            item = item,
                            selected = item.keyboardName == selectedItem?.keyboardName &&
                                item.schemaId == selectedItem.schemaId,
                            active = activeSlot == KeyboardSlot.Chinese,
                            onClick = { selectChinese(item) },
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.slot_section_english))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                EnglishSlotRow(
                    schemaName = englishSchema?.name?.takeIf { it.isNotBlank() }
                        ?: KeyboardManager.Slot.ENGLISH_SCHEMA_ID,
                    active = activeSlot == KeyboardSlot.English,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 中文槽的一行。
 *
 * 只列**方案里真的有的**输入方式（见 [KeyboardSlotPlan.plan]）：方案里没有的布局整项不出现，
 * 不在这里灰掉占位 —— 摆一个点不了的「15键」只会让人以为是自己没装方案。
 */
@Composable
private fun ChineseSlotRow(
    item: SlotSwitchItem,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle
            else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                fontSize = rowFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && selected) {
                    TagBadge(
                        stringResource(R.string.slot_in_use),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                val schema = item.schema
                if (schema != null) {
                    TagBadge(
                        schemaLayoutTag(context, schema.layout),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    val punctText =
                        if (schema.punctuation == "full-width") stringResource(R.string.tag_punctuation_full)
                        else stringResource(R.string.tag_punctuation_half)
                    TagBadge(
                        punctText,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (item.schemaName.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.schemaName,
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 英文槽固定项：只展示当前方案，不提供任何可点的动作。 */
@Composable
private fun EnglishSlotRow(schemaName: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.slot_english),
                fontSize = rowFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    TagBadge(
                        stringResource(R.string.slot_in_use),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                TagBadge(
                    schemaLayoutTag(LocalContext.current, "Qwerty"),
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = schemaName,
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.slot_english_fixed),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagBadge(text: String, bg: Color, fg: Color) {
    Text(
        text = text,
        fontSize = rowSubFontSize * 0.9f,
        lineHeight = rowSubFontSize * 0.9f,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}
