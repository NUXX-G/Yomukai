package com.nelson.yomukai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.DiagnosticStep
import com.nelson.yomukai.data.source.SourceDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    val sources: List<CatalogueSource> = container.sourceManager.all

    private val _results = MutableStateFlow<Map<String, List<DiagnosticStep>>>(emptyMap())
    val results: StateFlow<Map<String, List<DiagnosticStep>>> = _results.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    fun run(sourceId: String) {
        val source = container.sourceManager.get(sourceId) ?: return
        if (sourceId in _running.value) return
        _running.update { it + sourceId }
        viewModelScope.launch(Dispatchers.IO) {
            val steps = try {
                SourceDiagnostics.run(source)
            } catch (e: Exception) {
                listOf(DiagnosticStep("Diagnóstico", false, e.message ?: e::class.java.simpleName))
            }
            _results.update { it + (sourceId to steps) }
            _running.update { it - sourceId }
        }
    }

    /** Sources are tested one after another so the per-host rate limiter and the network are not flooded. */
    fun runAll() {
        viewModelScope.launch(Dispatchers.IO) {
            for (source in sources) {
                _running.update { it + source.id }
                val steps = try {
                    SourceDiagnostics.run(source)
                } catch (e: Exception) {
                    listOf(DiagnosticStep("Diagnóstico", false, e.message ?: e::class.java.simpleName))
                }
                _results.update { it + (source.id to steps) }
                _running.update { it - source.id }
            }
        }
    }
}
