package com.MeshLink.android.ai

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AriaMessage(
    val text: String,
    val isUser: Boolean
)

class AriaViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<AriaMessage>>(emptyList())
    val messages: StateFlow<List<AriaMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _activeEngine = MutableStateFlow(AriaEngineManager.activeEngineName)
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    private val _contextMessageCount = MutableStateFlow(0)
    val contextMessageCount: StateFlow<Int> = _contextMessageCount.asStateFlow()

    val modelState: StateFlow<ModelManager.ModelState> = ModelManager.modelState

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    var chatContextProvider: (() -> String)? = null
    var chatContextCountProvider: (() -> Int)? = null

    init {
        // Probe and initialize local Gemma 3 1B model
        viewModelScope.launch {
            initializeLocalModel()
        }

        _messages.value = listOf(
            AriaMessage(
                text = "👋 Hi! I'm **Aria**, running 100% offline using **Gemma 3 1B** on your device.\n\nAsk me anything!",
                isUser = false
            )
        )
    }

    fun initializeLocalModel() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            AriaEngineManager.initializeGemma(context)
            _isModelReady.value = AriaEngineManager.isLocalModelReady()
            _activeEngine.value = AriaEngineManager.activeEngineName
        }
    }

    fun refreshContextCount() {
        _contextMessageCount.value = chatContextCountProvider?.invoke() ?: 0
        initializeLocalModel()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val app = getApplication<Application>()
        
        val userMsg = AriaMessage(text, isUser = true)
        _messages.value = _messages.value + userMsg
        
        _isTyping.value = true

        viewModelScope.launch {
            try {
                val chatCtx = chatContextProvider?.invoke() ?: ""
                val (response, engineName) = AriaEngineManager.chatWithContext(text, chatCtx, app)
                
                _activeEngine.value = engineName
                val ariaMsg = AriaMessage(response, isUser = false)
                _messages.value = _messages.value + ariaMsg
            } catch (e: Exception) {
                _messages.value = _messages.value + AriaMessage(
                    "⚠️ AI error: ${e.message}",
                    isUser = false
                )
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun clearChat() {
        _messages.value = listOf(
            AriaMessage(
                text = "🔄 Chat cleared. Ask me anything!",
                isUser = false
            )
        )
    }
}
