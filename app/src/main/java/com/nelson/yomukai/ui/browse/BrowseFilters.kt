package com.nelson.yomukai.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MultiSelectFilter
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter

/** Generic filter sheet: renders whatever filters the source declares. Applies on "Aplicar". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: List<SourceFilter>,
    initial: FilterValues,
    onApply: (FilterValues) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initial) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            filters.forEach { filter ->
                Text(filter.label, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filter.options.forEach { option ->
                        val selected = when (filter) {
                            is SelectFilter -> (draft[filter.key]?.firstOrNull() ?: filter.default) == option.value
                            is MultiSelectFilter -> option.value in draft[filter.key].orEmpty()
                        }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                draft = when (filter) {
                                    is SelectFilter -> draft + (filter.key to setOf(option.value))
                                    is MultiSelectFilter -> {
                                        val current = draft[filter.key].orEmpty()
                                        draft + (filter.key to if (option.value in current) current - option.value else current + option.value)
                                    }
                                }
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { draft = emptyMap() }) { Text("Restablecer") }
                Button(onClick = { onApply(draft); onDismiss() }) { Text("Aplicar") }
            }
        }
    }
}
