# External Project Analysis: HMx-assistant-main (Jarvis)

**Path:** `/storage/emulated/0/AIProjects/HMx-assistant-main/`
**Package:** `dev.krinry.jarvis`

## Project Type
- Jetpack Compose application (Kotlin)
- Single `:app` module
- Kotlin DSL (`.gradle.kts`)
- Version catalog (`libs.versions.toml`)

## Build Configuration

| Setting | Value |
|---------|-------|
| namespace | dev.krinry.jarvis |
| applicationId | dev.krinry.jarvis |
| compileSdk | 36 |
| targetSdk | 36 |
| minSdk | 24 |
| versionCode | 1 |
| versionName | 1.0 |
| Java compat | 11 |
| Compose | enabled |
| AGP | 9.0.1 |
| Kotlin | 2.2.10 |

## Dependencies (direct)

| Dependency | Version | Type |
|------------|---------|------|
| androidx.core.ktx | 1.17.0 | AAR |
| androidx.lifecycle.runtime.ktx | 2.10.0 | AAR |
| androidx.activity.compose | 1.12.4 | AAR |
| androidx.compose.bom | 2025.06.00 | BOM |
| androidx.compose.ui | (BOM) | AAR |
| androidx.compose.material3 | (BOM) | AAR |
| androidx.navigation.compose | 2.9.1 | AAR |
| androidx.lifecycle.viewmodel.compose | (BOM) | AAR |
| kotlinx.coroutines.android | 1.10.2 | JAR |
| okhttp | 5.3.2 | JAR |
| gson | 2.13.2 | JAR |
| coil-compose | 3.3.0 | AAR |
| coil-network-okhttp | 3.3.0 | AAR |
| security-crypto | 1.1.0-alpha06 | AAR |
| material-icons-extended | (BOM) | AAR |
| haze | 1.0.1 | AAR |
| haze-materials | 1.0.1 | AAR |

## AndroidManifest.xml

### Permissions (9)
RECORD_AUDIO, INTERNET, SYSTEM_ALERT_WINDOW, WAKE_LOCK, VIBRATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES, RECEIVE_BOOT_COMPLETED

### Components
- **MainActivity** — launcher activity
- **AutoAgentService** — AccessibilityService with meta-data
- **FloatingBubbleService** — foreground service (microphone)
- **WakeWordService** — foreground service (microphone)
- **BootReceiver** — BOOT_COMPLETED receiver

### Namespace declarations
- `xmlns:android` — standard
- `xmlns:tools` — used for tools:ignore

## Source Files (~30+ Kotlin files)
- agent/ (ActionExecutor, AgentLlmEngine, AgentTtsManager, UiTreeExtractor)
- ai/ (GeminiProvider, GroqApiClient, GroqProvider, LlmProvider, OpenRouterProvider)
- chat/ (ChatScreen, ChatViewModel, ChatViewModelFactory, components/)
- navigation/ (AppNavHost)
- security/ (SecureKeyStore)
- service/ (AutoAgentService, BootReceiver, FloatingBubbleService, WakeWordService)
- settings/ (SettingsScreen)
- ui/components/ (AICoreOrb, AnimatedCyberBackground, BottomNavBar, GlassComponents, Glowing3DIcon, TerminalInput, TerminalLoadingIndicator)

## Resource Files
- res/xml/accessibility_service_config.xml
- res/xml/data_extraction_rules.xml
- res/xml/backup_rules.xml
- res/values/strings.xml (referenced: app_name)
- res/values/themes.xml (referenced: Theme.Jarvis)
- res/mipmap/ic_launcher (referenced)

## Signing Configuration
- Release signing with keystore (local.properties or env vars)
- Debug uses default debug keystore

## Transitive Dependencies (estimated 50+)
Including: androidx.compose.animation, androidx.compose.foundation, androidx.compose.material, androidx.compose.runtime, androidx.compose.ui, androidx.activity, androidx.core, androidx.lifecycle, androidx.navigation, androidx.savedstate, kotlinx.coroutines, okhttp, gson, coil, security-crypto, haze, material-icons, etc.
