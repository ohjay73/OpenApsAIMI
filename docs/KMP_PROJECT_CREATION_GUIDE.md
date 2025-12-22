# AIMI-KMP - Guide de Création Projet (30 Minutes)

**Objectif**: Créer projet KMP fonctionnel avec Medtrum + Dash + AIMI

**Date**: 2025-12-22T12:46+01:00  
**Durée**: 30 minutes de setup → Projet compile Android + iOS

---

## 🚀 ÉTAPE 1: Créer Projet KMP (5 min)

### Via Android Studio

```bash
1. Android Studio → File → New → New Project
2. Sélectionner: "Kotlin Multiplatform App"
3. Configurer:
   - Name: AIMI-KMP
   - Package name: app.aimi
   - Save location: /Users/mtr/StudioProjects/AIMI-KMP
   - Minimum SDK: 24
   - iOS minimum: 15.0
4. Cliquer "Finish"
5. Attendre sync Gradle (2-3 min)
```

**✅ Résultat**: Projet KMP de base qui compile

---

## 📁 ÉTAPE 2: Structure Folders (2 min)

### Commandes Terminal

```bash
cd /Users/mtr/StudioProjects/AIMI-KMP

# Créer structure shared/commonMain
mkdir -p shared/src/commonMain/kotlin/app/aimi/{algorithm,data,pump,cgm,loop,network,ml}
mkdir -p shared/src/commonMain/kotlin/app/aimi/data/{models,repositories}
mkdir -p shared/src/commonMain/kotlin/app/aimi/pump/{medtrum,dash,common}
mkdir -p shared/src/commonMain/kotlin/app/aimi/pump/medtrum/{protocol,crypto}
mkdir -p shared/src/commonMain/kotlin/app/aimi/pump/dash/{protocol,crypto}
mkdir -p shared/src/commonMain/kotlin/app/aimi/cgm
mkdir -p shared/src/commonMain/sqldelight/app/aimi/database

# Créer structure androidMain
mkdir -p shared/src/androidMain/kotlin/app/aimi/{platform,pump,cgm}
mkdir -p shared/src/androidMain/kotlin/app/aimi/pump/{medtrum,dash}

# Créer structure iosMain  
mkdir -p shared/src/iosMain/kotlin/app/aimi/{platform,pump,cgm}
mkdir -p shared/src/iosMain/kotlin/app/aimi/pump/{medtrum,dash}

# Créer structure tests
mkdir -p shared/src/commonTest/kotlin/app/aimi
```

**✅ Résultat**: Folders prêts pour recevoir code

---

## 📋 ÉTAPE 3: Copier Fichiers Data Models (5 min)

### Commandes de Copie

```bash
cd /Users/mtr/StudioProjects

# GlucoseValue
cp OpenApsAIMI/core/objects/src/main/kotlin/app/aaps/core/data/model/GV.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/data/models/GlucoseValue.kt

# Profile  
cp OpenApsAIMI/core/objects/src/main/kotlin/app/aaps/core/data/model/Profile.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/data/models/Profile.kt

# Treatment (Bolus)
cp OpenApsAIMI/core/objects/src/main/kotlin/app/aaps/core/data/model/BS.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/data/models/Bolus.kt

# TempBasal
cp OpenApsAIMI/core/objects/src/main/kotlin/app/aaps/core/data/model/TB.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/data/models/TempBasal.kt
```

### Adapter les Fichiers (Supprimer Android deps)

Ouvrir chaque fichier et:
1. Changer package: `app.aaps.core.data.model` → `app.aimi.data.models`
2. Supprimer imports Android:
   - `import androidx.room.*` → SUPPRIMER
   - `@Entity`, `@PrimaryKey`, `@ColumnInfo` → SUPPRIMER
3. Garder seulement data class pure Kotlin

**Exemple GlucoseValue.kt après nettoyage**:
```kotlin
package app.aimi.data.models

data class GlucoseValue(
    val timestamp: Long,
    val value: Double,
    val raw: Double? = null,
    val noise: Double? = null,
    val trendArrow: TrendArrow = TrendArrow.NONE,
    val sourceSensor: SourceSensor
)

enum class TrendArrow {
    NONE, TRIPLE_UP, DOUBLE_UP, SINGLE_UP,
    FORTY_FIVE_UP, FLAT, FORTY_FIVE_DOWN,
    SINGLE_DOWN, DOUBLE_DOWN, TRIPLE_DOWN
}

enum class SourceSensor {
    DEXCOM_G6_NATIVE, LIBRE_2_NATIVE, XDRIP, NIGHTSCOUT
}
```

**✅ Résultat**: 4 data models prêts

---

## 🔧 ÉTAPE 4: Copier Medtrum Protocol (10 min)

### Packets (100% réutilisables)

```bash
cd /Users/mtr/StudioProjects

# Copier TOUS les packets Medtrum
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/packets/*.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/medtrum/protocol/

# Copier crypto
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/encryption/*.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/medtrum/crypto/

# Copier ManufacturerData
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/ManufacturerData.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/medtrum/protocol/

# Copier ReadDataPacket, WriteCommandPackets
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/ReadDataPacket.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/medtrum/protocol/
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/WriteCommandPackets.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/medtrum/protocol/
```

### Adapter les Packets

Pour chaque fichier `.kt` copié:
1. Changer package:
   - `app.aaps.pump.medtrum.comm.packets` → `app.aimi.pump.medtrum.protocol`
   - `app.aaps.pump.medtrum.encryption` → `app.aimi.pump.medtrum.crypto`
2. Supprimer imports inutiles (AAPSLogger, etc.)
3. Si logger nécessaire, remplacer par `println()` temporairement

**✅ Résultat**: Protocol Medtrum complet (~15 fichiers)

---

## 🔧 ÉTAPE 5: Copier Dash Protocol (10 min)

### Commandes Copie Dash

```bash
cd /Users/mtr/StudioProjects

# Messages
cp -r OpenApsAIMI/pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/message/ \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/dash/protocol/messages/

# Commands
cp -r OpenApsAIMI/pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/command/ \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/dash/protocol/commands/

# Crypto/Session
cp -r OpenApsAIMI/pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/session/ \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/dash/crypto/

# Pod State
cp OpenApsAIMI/pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/pod/state/*.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/pump/dash/protocol/
```

### Adapter Dash Files

Même process:
1. Changer packages
2. Supprimer Android deps
3. Logger temporaire

**✅ Résultat**: Protocol Dash complet (~20 fichiers)

---

## 📝 ÉTAPE 6: Configuration Gradle (5 min)

### 1. Remplacer `shared/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    kotlin("plugin.serialization") version "1.9.21"
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    
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
        commonMain.dependencies {
            // Coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            
            // DateTime
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
            
            // Serialization
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            
            // Logging (temporaire)
            implementation("co.touchlab:kermit:2.0.2")
        }
        
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        }
        
        commonTest.dependencies {
            implementation(kotlin("test"))
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

### 2. Créer `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "1.9.21"
agp = "8.2.0"

[libraries]
# Pas besoin pour minimal POC

[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

### 3. Modifier `settings.gradle.kts`

```kotlin
rootProject.name = "AIMI-KMP"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
```

**✅ Résultat**: Configuration Gradle minimal

---

## ✅ ÉTAPE 7: Test Compilation (3 min)

### Terminal

```bash
cd /Users/mtr/StudioProjects/AIMI-KMP

# Sync Gradle
./gradlew --refresh-dependencies

# Compile shared module
./gradlew :shared:build

# Si succès:
echo "✅ AIMI-KMP compile! Projet créé avec succès!"

# Si erreurs:
# - Vérifier packages dans fichiers copiés
# - Vérifier imports manquants
# - Supprimer code Android-specific
```

**✅ Résultat**: Projet compile!

---

## 📊 Inventaire Fichiers Créés

### Structure Finale

```
AIMI-KMP/
├── shared/
│   ├── src/
│   │   ├── commonMain/
│   │   │   └── kotlin/app/aimi/
│   │   │       ├── data/models/
│   │   │       │   ├── GlucoseValue.kt ✅
│   │   │       │   ├── Profile.kt ✅
│   │   │       │   ├── Bolus.kt ✅
│   │   │       │   └── TempBasal.kt ✅
│   │   │       ├── pump/
│   │   │       │   ├── medtrum/
│   │   │       │   │   ├── protocol/
│   │   │       │   │   │   ├── SetBolusPacket.kt ✅
│   │   │       │   │   │   ├── SetTempBasalPacket.kt ✅
│   │   │       │   │   │   ├── AuthorizePacket.kt ✅
│   │   │       │   │   │   └── ~12 autres packets ✅
│   │   │       │   │   └── crypto/
│   │   │       │   │       ├── Crypt.kt ✅
│   │   │       │   │       └── ManufacturerData.kt ✅
│   │   │       │   └── dash/
│   │   │       │       ├── protocol/
│   │   │       │       │   ├── messages/ (✅ ~8 fichiers)
│   │   │       │       │   └── commands/ (✅ ~10 fichiers)
│   │   │       │       └── crypto/ (✅ ~5 fichiers)
│   │   │       ├── cgm/ (vide pour l'instant)
│   │   │       ├── algorithm/ (vide - à migrer)
│   │   │       └── loop/ (vide - à créer)
│   │   ├── androidMain/
│   │   │   └── kotlin/app/aimi/
│   │   │       └── (vide - BLE Android à créer)
│   │   └── iosMain/
│   │       └── kotlin/app/aimi/
│   │           └── (vide - BLE iOS à créer)
│   └── build.gradle.kts ✅
├── androidApp/ (créé par wizard)
├── gradle/
│   └── libs.versions.toml ✅
├── build.gradle.kts ✅
├── settings.gradle.kts ✅
└── gradlew ✅

TOTAL: ~50 fichiers copiés + adaptés
```

---

## 🎯 Prochaines Étapes (After Compilation OK)

### Phase 2: Ajouter Algorithme AIMI (2-3h)

```bash
# Copier DetermineBasalAIMI
cp OpenApsAIMI/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAMA/DetermineBasalAIMI2.kt \
   AIMI-KMP/shared/src/commonMain/kotlin/app/aimi/algorithm/DetermineBasalAIMI.kt

# Adapter:
# - Supprimer Android Services
# - Créer interfaces (ProfileProvider, GlucoseProvider)
# - Remplacer AAPSLogger par Kermit
```

### Phase 3: BLE Medtrum Android (4-5h)

```bash
# Copier BLEComm
cp OpenApsAIMI/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt \
   AIMI-KMP/shared/src/androidMain/kotlin/app/aimi/pump/medtrum/MedtrumBLEAndroid.kt

# Adapter pour expect/actual pattern
```

### Phase 4: BLE Medtrum iOS (6-8h)

```bash
# Créer nouveau fichier
# AIMI-KMP/shared/src/iosMain/kotlin/app/aimi/pump/medtrum/MedtrumBLEiOS.kt

# Implémenter avec CoreBluetooth (voir exemples dans docs)
```

---

## 🐛 Troubleshooting

### Erreur: "Cannot find symbol GV"

**Solution**: Tu as oublié de changer le package name dans `GlucoseValue.kt`

### Erreur: "Unresolved reference: AAPSLogger"

**Solution**: Supprimer les lignes avec `aapsLogger` ou remplacer par:
```kotlin
println("MY LOG MESSAGE")
```

### Erreur: "Unresolved reference: room"

**Solution**: Supprimer toutes les annotations Room (`@Entity`, `@PrimaryKey`, etc.)

### Gradle Sync Failed

**Solution**:
```bash
./gradlew clean
./gradlew --refresh-dependencies
```

---

## ✅ Checklist de Succès

Après avoir suivi ce guide:

- [ ] Projet AIMI-KMP créé
- [ ] Structure folders complète
- [ ] Data models copiés (4 fichiers)
- [ ] Medtrum protocol copié (~15 fichiers)
- [ ] Dash protocol copié (~20 fichiers)
- [ ] build.gradle.kts configuré
- [ ] `./gradlew :shared:build` succède
- [ ] Aucune erreur de compilation

**Si toutes les cases cochées** : 🎉 **PROJET KMP READY !**

---

## 📞 Support

Si tu rencontres un problème:
1. Vérifie que TOUS les packages sont changés
2. Vérifie qu'AUCUN import Android ne reste
3. Compile fichier par fichier pour isoler erreur
4. Demande-moi de l'aide sur fichier spécifique

---

**Auteur**: Lyra  
**Date**: 2025-12-22T12:46+01:00  
**Durée estimée**: 30 minutes  
**Résultat**: Projet KMP avec Medtrum + Dash protocols prêts
