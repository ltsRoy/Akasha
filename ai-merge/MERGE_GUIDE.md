# MeshLink AI Feature — Merge Guide

## Overview
This package contains all files required to add **Aria AI Assistant** (100% offline, powered by **Gemma 3 1B**) to any version of the MeshLink Android app.

---

## 📦 Files Included in This Package

### New Files (Copy As-Is)
These files are **brand new** and do not exist in the original codebase. Copy them directly into the target project.

| File | Target Path | Purpose |
|------|-------------|---------|
| `AriaEngine.kt` | `app/src/main/java/com/MeshLink/android/ai/AriaEngine.kt` | Core `AriaEngine` interface + `AriaEngineManager` singleton orchestrator |
| `GemmaLocalEngine.kt` | `app/src/main/java/com/MeshLink/android/ai/GemmaLocalEngine.kt` | MediaPipe LLM Inference engine for Gemma 3 1B (ultra-fast, max 40 words) |
| `LocalAriaEngine.kt` | `app/src/main/java/com/MeshLink/android/ai/LocalAriaEngine.kt` | Keyword/rule-based fallback engine (zero-dependency, always works) |
| `ModelManager.kt` | `app/src/main/java/com/MeshLink/android/ai/ModelManager.kt` | Extracts bundled Gemma 3 1B model from APK `assets/` to internal storage |
| `AriaViewModel.kt` | `app/src/main/java/com/MeshLink/android/ai/AriaViewModel.kt` | ViewModel exposing AI chat state (messages, typing, model readiness) |
| `AriaChatSheet.kt` | `app/src/main/java/com/MeshLink/android/ui/AriaChatSheet.kt` | Jetpack Compose bottom sheet chat UI for Aria AI |

### Model Asset (Download Separately — NOT in zip due to size)
| File | Target Path | Size | Source |
|------|-------------|------|--------|
| `gemma3-1b-it-int4.task` | `app/src/main/assets/gemma3-1b-it-int4.task` | **554 MB** | [Kaggle Gemma 3 1B MediaPipe](https://www.kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4) |

> ⚠️ **IMPORTANT**: The model file is ~554 MB and is NOT included in this zip. You MUST download it separately and place it at `app/src/main/assets/gemma3-1b-it-int4.task`.

---

## 🔧 Dependencies to Add

Add these lines to `app/build.gradle.kts` inside the `dependencies { }` block:

```kotlin
// MediaPipe LLM Inference (Gemma 3 1B bundled offline model)
implementation("com.google.mediapipe:tasks-genai:0.10.27")

// Google AI Edge (Gemini Nano on-device via AICore - Pixel/Samsung only)
implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
```

---

## 🏗️ Build Config Changes

Add this inside the `android { }` block of `app/build.gradle.kts`:

```kotlin
android {
    // ... existing config ...

    androidResources {
        noCompress += "task"  // Prevents compression of the .task model file in APK
    }
}
```

> This is **critical**. Without `noCompress += "task"`, the APK build will compress the 554 MB model file and MediaPipe will fail to load it at runtime.

---

## 📋 Step-by-Step Merge Instructions

### Step 1: Create the `ai` Package Directory
```
app/src/main/java/com/MeshLink/android/ai/
```

### Step 2: Copy All AI Source Files
Copy these 6 Kotlin files from this package into the `ai/` directory:
- `AriaEngine.kt`
- `GemmaLocalEngine.kt`
- `LocalAriaEngine.kt`
- `ModelManager.kt`
- `AriaViewModel.kt`
- `AriaChatSheet.kt` → goes to `app/src/main/java/com/MeshLink/android/ui/AriaChatSheet.kt`

### Step 3: Download and Place Model Asset
1. Download `gemma3-1b-it-int4.task` (554 MB) from Kaggle
2. Place it at: `app/src/main/assets/gemma3-1b-it-int4.task`
3. Create the `assets/` directory if it doesn't exist

### Step 4: Update `app/build.gradle.kts`
Add the dependencies and `noCompress` config as described above.

### Step 5: Integrate Aria AI Button into Your UI
In your main screen or chat screen, add a button to open the Aria chat sheet. Example:

```kotlin
// In your Activity or main Composable:
import com.MeshLink.android.ai.AriaViewModel
import com.MeshLink.android.ui.AriaChatSheet
import androidx.lifecycle.viewmodel.compose.viewModel

// Inside your Composable:
val ariaViewModel: AriaViewModel = viewModel()
var showAriaChat by remember { mutableStateOf(false) }

// Button to open Aria
IconButton(onClick = { 
    ariaViewModel.refreshContextCount()
    showAriaChat = true 
}) {
    Icon(Icons.Filled.AutoAwesome, contentDescription = "Aria AI")
}

// Aria Chat Sheet
AriaChatSheet(
    isPresented = showAriaChat,
    onDismiss = { showAriaChat = false },
    viewModel = ariaViewModel
)
```

### Step 6: Build and Test
```bash
# Build debug APK
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

---

## 🏛️ Architecture Summary

```
AriaEngine (Interface)
    ├── GemmaLocalEngine (Primary — Gemma 3 1B on-device, max 40 words)
    └── LocalAriaEngine  (Fallback — keyword-based, zero dependencies)

AriaEngineManager (Singleton — routes chat requests through the engine hierarchy)
    ↕
AriaViewModel (AndroidViewModel — exposes StateFlow for UI)
    ↕
AriaChatSheet (Jetpack Compose — bottom sheet chat UI)
```

### Engine Priority:
1. **Gemma 3 1B** (Primary): Ultra-fast on-device AI using MediaPipe LLM Inference. Max 60 tokens (~40 words). 100% offline.
2. **Local Keywords** (Fallback): Rule-based keyword matching engine covering first aid, water, shelter, fire, navigation, signaling. Always works.

---

## ⚡ Performance Specs

| Metric | Value |
|--------|-------|
| Model | Gemma 3 1B INT4 Quantized |
| Model Size | 554 MB |
| Max Tokens | 60 |
| Max Words | ~40 |
| Latency | < 1 second on modern devices |
| Internet Required | ❌ No |
| GPU/NPU Acceleration | ✅ Yes (MediaPipe) |

---

## 🔑 Key Technical Details

- **Package**: `com.MeshLink.android.ai`
- **UI Package**: `com.MeshLink.android.ui`
- **Model Path Resolution**: `ModelManager` checks (in order):
  1. `context.filesDir/gemma3-1b-it-int4.task` (internal storage, extracted from assets)
  2. APK `assets/gemma3-1b-it-int4.task` (bundled in APK, auto-extracted on first launch)
  3. `/data/local/tmp/llm/` (ADB push location)
  4. `/sdcard/Download/` (manual download location)
- **Concurrency**: All inference runs on `Dispatchers.IO` to avoid blocking the main thread.
- **State**: `AriaViewModel` uses `StateFlow` for reactive UI updates.

---

## 📝 Notes

- The AI runs **100% offline** with zero cloud or internet dependencies.
- No API keys, no network callbacks, no cloud services are used.
- The `OkHttp` dependency (already in the project for WebSocket) is NOT used by the AI system.
- The model is automatically extracted from APK assets on first launch (~30 seconds).
- Subsequent launches load instantly from internal storage cache.
