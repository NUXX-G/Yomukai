package com.nelson.yomukai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.source.DiagnosticStep
import com.nelson.yomukai.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico de fuentes") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Prueba novedades, búsqueda, detalle, capítulos y lectura/reproducción de cada fuente " +
                            "con tu conexión actual.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = viewModel::runAll, enabled = running.isEmpty()) { Text("Probar todas") }
                }
            }
            items(viewModel.sources, key = { it.id }) { source ->
                val steps = results[source.id]
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        when {
                            source.id in running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            steps == null -> OutlinedButton(onClick = { viewModel.run(source.id) }) { Text("Probar") }
                            else -> {
                                Text(if (steps.all { it.ok }) "OK" else "Error", style = MaterialTheme.typography.labelLarge)
                                OutlinedButton(onClick = { viewModel.run(source.id) }) { Text("Repetir") }
                            }
                        }
                    }
                    steps?.forEach { StepRow(it) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: DiagnosticStep) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (step.ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (step.ok) "OK" else "Error",
            tint = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text("${step.name}: ${step.detail}", style = MaterialTheme.typography.bodySmall)
    }
}
