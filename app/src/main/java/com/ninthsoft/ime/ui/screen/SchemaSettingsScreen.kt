package com.ninthsoft.ime.ui.screen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.rime.core.SchemaItem
import com.ninthsoft.ime.ui.screen.ScreenComponent.SectionHeader
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import com.ninthsoft.ime.ui.theme.ExpressiveShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val PREFS_NAME = "schema_settings"
private const val KEY_ENABLED_IDS = "enabled_schema_ids"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val enabledSchemas = remember { mutableStateListOf<SchemaItem>() }
    val availableSchemas = remember { mutableStateListOf<SchemaItem>() }
    var loaded by remember { mutableStateOf(false) }

    EngineFactory.current()?.postSchemeList { allSchemas ->
        val allItems = allSchemas.map { SchemaItem(it.id, it.name) }
        val enabledIds =
            prefs.getString(KEY_ENABLED_IDS, "")?.split(",")?.filter { it.isNotBlank() }
                ?: emptyList()
        val byId = allItems.associateBy { it.id }
        val seen = mutableSetOf<String>()
        enabledSchemas.clear()
        enabledSchemas.addAll(enabledIds.mapNotNull { byId[it]?.also { seen.add(it.id) } })
        availableSchemas.clear()
        availableSchemas.addAll(allItems.filter { it.id !in seen })
        loaded = true
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
                    IconButton(onClick = { saveOrder(prefs, enabledSchemas); onBack() }) {
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
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SectionHeader(stringResource(R.string.enabled_schemas))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                ReorderableSchemaList(enabledSchemas) { schema ->
                    IconButton(onClick = {
                        availableSchemas.add(schema)
                        enabledSchemas.remove(schema)
                        saveOrder(prefs, enabledSchemas)
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (availableSchemas.isNotEmpty()) {
                SectionHeader(stringResource(R.string.available_schemas))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    LazyColumn {
                        itemsIndexed(availableSchemas, key = { _, s -> s.id }) { _, schema ->
                            SchemaListItem(
                                schema = schema,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        enabledSchemas.add(schema)
                                        availableSchemas.remove(schema)
                                        saveOrder(prefs, enabledSchemas)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableSchemaList(
    items: MutableList<SchemaItem>,
    trailingIcon: @Composable (SchemaItem) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    LazyColumn {
        itemsIndexed(items, key = { _, s -> s.id }) { index, schema ->
            val isDragging = dragIndex == index
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.03f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "dragScale",
            )

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .scale(dragScale)
                    .graphicsLayer {
                        this.shadowElevation = if (isDragging) 8f else 0f
                    }
                    .onGloballyPositioned {
                        if (itemHeight == 0f) itemHeight = it.size.height.toFloat()
                    },
            ) {
                SchemaListItem(
                    schema = schema,
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .pointerInput(index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragIndex = index
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                val target =
                                                    (dragIndex + (dragOffset / itemHeight).roundToInt())
                                                        .coerceIn(0, items.size - 1)
                                                if (target != dragIndex) {
                                                    val item = items.removeAt(dragIndex)
                                                    items.add(target, item)
                                                    saveOrder(prefs, items)
                                                }
                                                dragIndex = -1
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingIcon = { trailingIcon(schema) },
                )
            }
        }
    }
}

@Composable
private fun SchemaListItem(
    schema: SchemaItem,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schema.name.ifBlank { schema.id },
                fontSize = rowFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (schema.name.isNotBlank()) {
                Text(
                    schema.id,
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingIcon()
    }
}

private fun saveOrder(prefs: SharedPreferences, schemas: List<SchemaItem>) {
    prefs.edit().putString(KEY_ENABLED_IDS, schemas.joinToString(",") { it.id }).apply()
}
