# AIMI KMP - Migration Plan PARTIE 2

Suite de `KMP_MIGRATION_MASTER_PLAN.md`

---

## 🗓️ Plan d'Exécution (Suite)

### **PHASE 3 : Data Layer** (Semaines 7-9)

```
Semaine 7-8: SQLDelight Migration
  ├─> Créer schemas .sq (glucose_values, treatments, profiles)
  ├─>Configure SQLDelight driver Android
  ├─> Configure SQLDelight driver iOS
  ├─> Migrer repositories (Room → SQLDelight)
  └─> Tests data persistence

Semaine 9: Models & Repositories
  ├─> Copier data models (GlucoseValue, Profile, Treatment)
  ├─> Créer repositories interfaces
  └─> Implementation repositories

Livrable: Base données fonctionne Android + iOS
Effort: 60h
```

### **PHASE 4 : Pompe Medtrum** (Semaines 10-14)

```
Semaine 10-11: Protocol Layer (Partagé)
  ├─> Copier packets/ → shared/commonMain
  ├─> Copier crypto/ → shared/commonMain
  ├─> Extraire & migrer MedtrumStateMachine
  └─> Tests protocol (mock BLE)

Semaine 12-13: Android BLE
  ├─> Adapter BLEComm.kt → MedtrumBLEAndroid.kt
  ├─> expect/actual pattern
  ├─> Tests avec vraie pompe Medtrum

Semaine 14: iOS BLE
  ├─> Implémenter MedtrumBLEiOS.kt (CoreBluetooth)
  ├─> Tests avec vraie pompe Medtrum
  └─> Validation connection 24/7

Livrable: Medtrum pump fonctionne Android + iOS
Effort: 150h
```

### **PHASE 5 : Pompe Dash** (Semaines 15-19)

```
Semaine 15-16: Protocol Layer (Partagé)
  ├─> Copier message packets → shared/commonMain
  ├─> Copier commands → shared/commonMain
  ├─> Copier crypto (LTK, nonce) → shared/commonMain
  └─> Tests protocol

Semaine 17-18: Android BLE
  ├─> Adapter Dash BLE → DashBLEAndroid.kt
  ├─> Tests vraie pompe Dash

Semaine 19: iOS BLE
  ├─> Implémenter DashBLEiOS.kt
  ├─> Tests vraie pompe Dash

Livrable: Dash pump fonctionne Android + iOS
Effort: 192h
```

### **PHASE 6 : CGM & Heartbeat** (Semaines 20-23)

```
Semaine 20-21: CGM Drivers
  ├─> Créer CGMDriver interface (commonMain)
  ├─> xDrip4iOS bridge support
  ├─> Dexcom G6/G7 BLE (Android)
  └─> Dexcom G6/G7 BLE (iOS)

Semaine 22-23: Heartbeat Manager (iOS 24/7)
  ├─> Implémenter CGMHeartbeat.kt
  ├─> Integration avec LoopManager
  ├─> Tests: App iOS reste vivante 24/7
  └─> Validation loop tourne background

Livrable: CGM heartbeat permet loop 24/7 iOS
Effort: 110h
```

### **PHASE 7 : Automated Loop** (Semaines 24-27)

```
Semaine 24-25: Loop Manager
  ├─> Créer ContinuousLoopManager.kt (commonMain)
  ├─> Orchestration CGM → Algorithm → Pump
  ├─> Safety constraints
  └─> Tests end-to-end (mock devices)

Semaine 26: Integration Android
  ├─> Android Service pour loop
  ├─> Foreground service notifications
  └─> Tests loop 24/7 Android

Semaine 27: Integration iOS
  ├─> iOS lifecycle integration
  ├─> Background via CGM heartbeat
  └─> Tests loop 24/7 iOS (vraies devices)

Livrable: Boucle fermée automatique fonctionne Android + iOS
Effort: 90h
```

### **PHASE 8 : Network & Sync** (Semaines 28-29)

```
Semaine 28: Ktor Network Layer
  ├─> Migrer NightscoutAPI (Retrofit → Ktor)
  ├─> HTTP client Android (OkHttp engine)
  ├─> HTTP client iOS (Darwin engine)
  └─> Tests sync

Semaine 29: Data Sync
  ├─> Upload loop results → Nightscout
  ├─> Download remote commands
  └─> Silent push notifications (iOS)

Livrable: Synchronisation Nightscout fonctionne
Effort: 40h
```

### **PHASE 9 : UI** (Semaines 30-34)

```
Option A: Compose Multiplatform (Recommandée)

Semaine 30-32: UI Shared
  ├─> Setup Compose Multiplatform
  ├─> Design system (colors, typography)
  ├─> Shared components (GlucoseCard, IOBCard, etc.)
  └─> Navigation

Semaine 33-34: Platform-specific UI
  ├─> Android app integration
  ├─> iOS app integration (SwiftUI wrapper)
  └─> Polish & animations

Livrable: UI complète Android + iOS (75% code partagé)
Effort: 120h

Option B: Native UI
  - Android: Jetpack Compose (60h)
  - iOS: SwiftUI (80h)
  - Total: 140h (0% partagé)
```

### **PHASE 10 : Tests & Production** (Semaines 35-40)

```
Semaine 35-37: Tests Beta
  ├─> Recrutement beta testers (10-20 users)
  ├─> Android beta (Google Play Beta)
  ├─> iOS beta (TestFlight)
  └─> Bug fixes

Semaine 38-39: App Store Preparation
  ├─> Privacy policy
  ├─> Terms of service
  ├─> App Store metadata (screenshots, description)
  ├─> Disclaimers médicaux
  └─> Review checklist

Semaine 40: Release
  ├─> Submit Google Play
  ├─> Submit App Store
  ├─> Documentation utilisateur
  └─> 🎉 RELEASE v1.0 !

Livrable: AIMI-KMP v1.0 en production
Effort: 100h
```

---

## 📝 Code Skeleton - Exemples Concrets

### **1. Project Setup - build.gradle.kts (root)**

```kotlin
// AIMI-KMP/build.gradle.kts

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.cocoapods) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
```

### **2. Shared Module - build.gradle.kts**

```kotlin
// shared/build.gradle.kts

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
    id("app.cash.sqldelight") version "2.0.1"
}

kotlin {
    // Android target
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    
    // iOS targets
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }
    
    sourceSets {
        // Common (partagé Android + iOS)
        commonMain.dependencies {
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            
            // DateTime
            implementation(libs.kotlinx.datetime)
            
            // Serialization JSON
            implementation(libs.kotlinx.serialization.json)
            
            // Ktor (Network)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            
            // SQLDelight (Database)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            
            // DI (Koin)
            implementation(libs.koin.core)
            
            // Logging
            implementation(libs.kermit)
            
            // ONNX Runtime (ML)
            implementation(libs.onnxruntime.common)
        }
        
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        
        // Android-specific
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.onnxruntime.android)
            implementation(libs.koin.android)
        }
        
        // iOS-specific
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
            implementation(libs.onnxruntime.ios)
        }
    }
}

// SQLDelight configuration
sqldelight {
    databases {
        create("AimiDatabase") {
            packageName.set("app.aimi.database")
            srcDirs("src/commonMain/sqldelight")
        }
    }
}

android {
    namespace = "app.aimi.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### **3. Libs Versions Catalog**

```toml
# gradle/libs.versions.toml

[versions]
kotlin = "1.9.21"
coroutines = "1.7.3"
ktor = "2.3.7"
sqldelight = "2.0.1"
koin = "3.5.0"
compose = "1.5.11"
onnx = "1.16.3"

[libraries]
# Kotlin
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-datetime = "org.jetbrains.kotlinx:kotlinx-datetime:0.5.0"
kotlinx-serialization-json = "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2"

# Ktor (Network)
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-contentNegotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-logging = { module = "io.ktor:ktor-client-logging", version.ref = "ktor" }
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }

# SQLDelight (Database)
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }

# DI (Koin)
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }

# Logging
kermit = "co.touchlab:kermit:2.0.2"

# ML (ONNX)
onnxruntime-common = { module = "com.microsoft.onnxruntime:onnxruntime", version.ref = "onnx" }
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnx" }
onnxruntime-ios = { module = "com.microsoft.onnxruntime:onnxruntime-mobile", version.ref = "onnx" }

# Tests
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
android-application = { id = "com.android.application", version = "8.2.0" }
android-library = { id = "com.android.library", version = "8.2.0" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-cocoapods = { id = "org.jetbrains.kotlin.native.cocoapods", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose" }
```

### **4. Example: Algorithm Migration**

```kotlin
// shared/commonMain/kotlin/app/aimi/algorithm/DetermineBasalAIMI.kt

package app.aimi.algorithm

import app.aimi.data.models.GlucoseValue
import app.aimi.data.models.Profile
import app.aimi.data.models.IOBTotal
import app.aimi.data.models.COBTotal
import app.aimi.data.models.TemporaryBasal
import kotlinx.datetime.Clock

/**
 * Core AIMI algorithm - 100% partagé Android + iOS
 * 
 * Migré depuis: plugins/aps/src/main/kotlin/.../DetermineBasalAIMI2.kt
 * Changements:
 * - Supprimé dépendances Android (AAPSLogger → Kermit, etc.)
 * - Rendu suspend pour coroutines
 * - Interfaces pour injection dépendances
 */
class DetermineBasalAIMI(
    private val profileProvider: ProfileProvider,
    private val glucoseProvider: GlucoseProvider,
    private val iobCalculator: IOBCalculator,
    private val cobCalculator: COBCalculator,
    private val mlPredictor: MLPredictor,
    private val logger: co.touchlab.kermit.Logger
) {
    
    /**
     * Détermine basal/SMB à administrer
     *  
     * @return DetermineBasalResult avec rate/smb/raison
     */
    suspend fun determineBasal(
        currentGlucose: GlucoseValue,
        currentTemp: TemporaryBasal?,
        timestamp: Long = Clock.System.now().toEpochMilliseconds()
    ): DetermineBasalResult {
        
        logger.d { "Starting determineBasal at $timestamp" }
        
        // 1. Get data (via interfaces - testable!)
        val glucoseHistory = glucoseProvider.getRecentValues(since = timestamp - 2.hours)
        val profile = profileProvider.getCurrentProfile(timestamp)
        val iob = iobCalculator.calculate(timestamp)
        val cob = cobCalculator.calculate(timestamp)
        
        // 2. ML Prediction
        val prediction = mlPredictor.predictGlucose4h(
            glucose = glucoseHistory,
            iob = iob.total,
            cob = cob.total,
            profile = profile
        )
        
        // 3. Calculate eventualBG
        val eventualBG = prediction.last().value
        val minPredictedBG = prediction.minOf { it.value }
        
        // 4. Determine action (logique identique original)
        return when {
            // Hypo prédite
            minPredictedBG < profile.targetLow -> {
                logger.i { "Predicted low ($minPredictedBG < ${profile.targetLow})" }
                DetermineBasalResult(
                    rate = 0.0,
                    duration = 30,
                    reason = "Predicted low in ${prediction.timeToLow} minutes",
                    predictions = prediction
                )
            }
            
            // Hyperglycémie => SMB
            eventualBG > profile.targetHigh && iob.total < profile.maxIOB -> {
                val correction = (eventualBG - profile.target) / profile.isf
                val smb = minOf(correction, profile.maxSMB, profile.maxIOB - iob.total)
                
                logger.i { "High BG predicted ($eventualBG > ${profile.targetHigh}), SMB: $smb U" }
                DetermineBasalResult(
                    smb = smb,
                    rate = profile.basalRate * 1.2,  // Augmente basal aussi
                    duration = 30,
                    reason = "High BG correction, eventualBG: $eventualBG",
                    predictions = prediction
                )
            }
            
            // Stable => maintain
            else -> {
                logger.d { "BG stable, maintaining current basal" }
                DetermineBasalResult(
                    rate = currentTemp?.rate ?: profile.basalRate,
                    duration = 30,
                    reason = "BG stable at $eventualBG",
                    predictions = prediction
                )
            }
        }
    }
}

// Interfaces pour injection (testable!)
interface ProfileProvider {
    suspend fun getCurrentProfile(timestamp: Long): Profile
}

interface GlucoseProvider {
    suspend fun getRecentValues(since: Long): List<GlucoseValue>
}

// Result data class
data class DetermineBasalResult(
    val rate: Double? = null,
    val duration: Int? = null,
    val smb: Double? = null,
    val reason: String,
    val predictions: List<PredictedGlucose> = emptyList()
)

data class PredictedGlucose(
    val timestamp: Long,
    val value: Double
)

// Extension helper
private val Int.hours: Long get() = this * 60 * 60 * 1000L
```

### **5. Example: Medtrum Protocol (Partagé)**

```kotlin
// shared/commonMain/kotlin/app/aimi/pump/medtrum/protocol/packets/SetBolusPacket.kt

package app.aimi.pump.medtrum.protocol.packets

import app.aimi.pump.medtrum.protocol.MedtrumPacket
import kotlinx.serialization.Serializable

/**
 * Medtrum Set Bolus command packet
 * 
 * Migré depuis: pump/medtrum/src/main/kotlin/.../SetBolusPacket.kt
 * 100% partageable (pure Kotlin, pas de dépendances plateforme)
 */
@Serializable
class SetBolusPacket(
    val bolusAmount: Double,  // En unités (U)
    val bolusSpeed: Int = 5   // Speed 1-5 (5 = fastest)
) : MedtrumPacket() {
    
    override val opCode: Byte = 0x12
    
    override fun getPayload(): ByteArray {
        // Convert bolus to internal units (0.05U precision)
        val bolusInternal = (bolusAmount / 0.05).toInt()
        
        return byteArrayOf(
            opCode,
            (bolusInternal shr 8).toByte(),  // High byte
            (bolusInternal and 0xFF).toByte(), // Low byte
            bolusSpeed.toByte()
        )
    }
    
    override fun toString(): String =
        "SetBolusPacket(amount=${bolusAmount}U, speed=$bolusSpeed)"
}
```

**✅ Ce fichier fonctionne IDENTIQUEMENT sur Android et iOS !**

---

## ✅ Liste Fichiers à Copier - Récapitulatif

### **📂 Fichiers RÉUTILISABLES Tel Quel** (Copier-coller)

```
SOURCE → DESTINATION (commonMain)

ALGORITHMES:
✅ DetermineBasalAIMI2.kt → algorithm/DetermineBasalAIMI.kt
✅ (Fichiers IOB/COB/AutoSens à extraire)

DATA MODELS:
✅ core/objects/.../GV.kt → data/models/GlucoseValue.kt
✅ core/objects/.../Profile.kt → data/models/Profile.kt  
✅ core/objects/.../TE.kt, TB.kt, BS.kt → data/models/Treatment.kt

MEDTRUM PROTOCOL (100% réutilisables):
✅ pump/medtrum/comm/packets/*.kt → pump/medtrum/protocol/packets/*.kt
   - AuthorizePacket.kt
   - ActivatePacket.kt
   - SetBasalProfilePacket.kt
   - SetBolusPacket.kt
   - SetTempBasalPacket.kt
   - NotificationPacket.kt
   - ReadDataPacket.kt
   - WriteCommandPackets.kt
   
✅ pump/medtrum/encryption/*.kt → pump/medtrum/protocol/crypto/*.kt
   - Crypt.kt
   - ManufacturerData.kt

DASH PROTOCOL (100% réutilisables):
✅ pump/omnipod/dash/comm/message/*.kt → pump/dash/protocol/messages/*.kt
✅ pump/omnipod/dash/comm/command/*.kt → pump/dash/protocol/commands/*.kt
✅ pump/omnipod/dash/comm/session/*.kt → pump/dash/protocol/crypto/*.kt
```

### **🔧 Fichiers à REFACTOR** (Adapter)

```
SOURCE → DESTINATION (Refactor Android deps)

⚠️ MedtrumService.kt → pump/medtrum/MedtrumStateMachine.kt
   Action: Extraire state machine sans Android Service

⚠️ BLEComm.kt → androidMain/pump/medtrum/MedtrumBLEAndroid.kt  
   Action: Adapter pour expect/actual pattern

⚠️ LoopPlugin.kt → loop/ContinuousLoopManager.kt
   Action: Supprimer Android Services, interfaces

⚠️ plugins/sync/nsclient/*.kt → network/NightscoutClient.kt
   Action: Retrofit → Ktor
```

### **❌ Fichiers NON RÉUTILISABLES** (Réécrire)

```
À RÉÉCRIRE pour iOS (CoreBluetooth):

❌ BLEComm.kt → iosMain/pump/medtrum/MedtrumBLEiOS.kt
   Raison: Android BLE API incompatible iOS

❌ Dash BLE → iosMain/pump/dash/DashBLEiOS.kt  
   Raison: Même problème BLE

❌ CGM BLE drivers → iosMain/cgm/*.kt
   Raison: CoreBluetooth complètement différent
```

---

## 🎯 Quick Start Actions

### **Action Immédiate (Semaine 1)**

```bash
# 1. Créer nouveau projet KMP
cd ~/Dev/
Android Studio → New Project → Kotlin Multiplatform App
  - Package name: app.aimi
  - Project name: AIMI-KMP

# 2. Setup Git
cd AIMI-KMP/
git init
git add .
git commit -m "Initial KMP project setup"

# 3. Copier premier fichier (test rapide)
# Copier GlucoseValue.kt depuis OpenApsAIMI
cp ~/StudioProjects/OpenApsAIMI/core/objects/src/main/kotlin/app/aaps/core/data/model/GV.kt \
   shared/src/commonMain/kotlin/app/aimi/data/models/GlucoseValue.kt

# Adapter package name et supprimer @Entity annotations
# Compiler → Should work immédiatement!

# 4. First commit
git add shared/src/commonMain/kotlin/app/aimi/data/models/GlucoseValue.kt
git commit -m "Migration: GlucoseValue model (first shared code!)"
```

---

**Prochains Documents** :
- `KMP_CODE_EXAMPLES.md` : Exemples concrets pour chaque module
- `KMP_TESTING_STRATEGY.md` : Tests KMP
- `KMP_DEPLOYMENT_GUIDE.md` : CI/CD Android + iOS

**MTR - Tu as maintenant** :
✅ Plan complet 6 mois
✅ Liste exacte fichiers à copier
✅ Estimation effort (1023h ≈ 6 mois 1 dev ou 3 mois 2 devs)
✅ Code skeleton
✅ Quick start actions

**Prêt à démarrer ?** 🚀
