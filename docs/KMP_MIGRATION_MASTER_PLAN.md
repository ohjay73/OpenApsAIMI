# AIMI KMP - Master Plan de Migration

**Objectif** : Créer AIMI-KMP from scratch avec Medtrum + Dash Omnipod support

**Date** : 2025-12-21T22:25+01:00  
**Pumps Priority** : Medtrum (P0), Dash Omnipod (P0)  
**Timeline** : 6 mois → Production ready

---

## 📂 Structure Projet AIMI-KMP

### Arborescence Complète

```
AIMI-KMP/
├── build.gradle.kts                    # Root build config
├── settings.gradle.kts                 # Project settings
├── gradle/
│   └── libs.versions.toml              # Dependencies catalog
│
├── shared/                             # ⭐ MODULE KMP PRINCIPAL
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── commonMain/                 # 🔷 CODE PARTAGÉ ANDROID + iOS
│   │   │   ├── kotlin/app/aimi/
│   │   │   │   ├── algorithm/          # DetermineBasalAIMI, IOB, COB
│   │   │   │   ├── ml/                 # Machine Learning models
│   │   │   │   ├── data/               # Repositories, models
│   │   │   │   ├── database/           # SQLDelight
│   │   │   │   ├── network/            # Ktor (Nightscout sync)
│   │   │   │   ├── cgm/                # CGM interfaces
│   │   │   │   ├── pump/               # Pump interfaces + protocols
│   │   │   │   │   ├── common/         # Base pump classes
│   │   │   │   │   ├── medtrum/        # Medtrum protocol (partagé!)
│   │   │   │   │   └── dash/           # Omnipod Dash protocol (partagé!)
│   │   │   │   ├── loop/               # Automated loop manager
│   │   │   │   └── util/               # Utilities
│   │   │   └── sqldelight/             # Database schema (.sq files)
│   │   │
│   │   ├── androidMain/                # 🤖 CODE ANDROID-SPECIFIC
│   │   │   └── kotlin/app/aimi/
│   │   │       ├── platform/           # Android platform code
│   │   │       ├── cgm/                # Android CGM BLE
│   │   │       └── pump/               # Android pump BLE
│   │   │           ├── medtrum/        # Medtrum BLE Android
│   │   │           └── dash/           # Dash BLE Android
│   │   │
│   │   ├── iosMain/                    # 🍎 CODE iOS-SPECIFIC
│   │   │   └── kotlin/app/aimi/
│   │   │       ├── platform/           # iOS platform code
│   │   │       ├── cgm/                # iOS CGM CoreBluetooth
│   │   │       └── pump/               # iOS pump CoreBluetooth
│   │   │           ├── medtrum/        # Medtrum BLE iOS
│   │   │           └── dash/           # Dash BLE iOS
│   │   │
│   │   ├── commonTest/                 # Tests partagés
│   │   ├── androidUnitTest/            # Tests Android
│   │   └── iosTest/                    # Tests iOS
│   │
│   └── models/                         # ONNX ML models (.onnx files)
│
├── androidApp/                         # 🤖 APPLICATION ANDROID
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── kotlin/app/aimi/android/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ui/                     # Compose UI (ou Views)
│   │   │   └── services/               # Android Background Services
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── google-services.json            # Firebase (optional)
│
├── iosApp/                             # 🍎 APPLICATION iOS (Xcode project)
│   ├── iosApp.xcodeproj/
│   ├── iosApp/
│   │   ├── ContentView.swift           # SwiftUI main view
│   │   ├── AppDelegate.swift           # iOS lifecycle
│   │   ├── Info.plist
│   │   └── Assets.xcassets/
│   └── iosApp.xcworkspace/
│
└── docs/                               # Documentation
    ├── ARCHITECTURE.md
    ├── PUMP_INTEGRATION.md
    └── MIGRATION_GUIDE.md
```

---

## 📋 Fichiers à Migrer depuis OpenApsAIMI

### 🔷 1. ALGORITHMES AIMI (Core Business Logic)

**Source** : `/plugins/aps/`

#### Fichiers à Copier → `shared/commonMain/kotlin/app/aimi/algorithm/`

```
FICHIERS CRITIQUES (Migration Priority P0):

1. DetermineBasalAIMI2.kt
   Source: plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAMA/DetermineBasalAIMI2.kt
   Destination: shared/commonMain/kotlin/app/aimi/algorithm/DetermineBasalAIMI.kt
   Action: Copier + Refactor (supprimer dépendances Android)
   Effort: 40h
   Code partagé: 95%

2. IOB Calculator
   Source: plugins/aps/src/main/kotlin/app/aaps/plugins/aps/logger/LoggerCallback.kt
   Destination: shared/commonMain/kotlin/app/aimi/algorithm/IOBCalculator.kt
   Action: Extraire logique pure Kotlin
   Effort: 20h
   Code partagé: 100%

3. COB Calculator
   Source: plugins/aps/src/main/kotlin/app/aaps/plugins/aps/*/
   Destination: shared/commonMain/kotlin/app/aimi/algorithm/COBCalculator.kt
   Effort: 15h
   Code partagé: 100%

4. Auto-Sensitivity
   Source: plugins/sensitivity/
   Destination: shared/commonMain/kotlin/app/aimi/algorithm/AutoSensitivity.kt
   Effort: 25h
   Code partagé: 90%

5. Dynamic ISF
   Source: (à extraire de DetermineBasalAIMI2.kt)
   Destination: shared/commonMain/kotlin/app/aimi/algorithm/DynamicISF.kt
   Effort: 15h
   Code partagé: 100%

TOTAL ALGORITHMES: ~115h migration
```

---

### 🔷 2. DATA MODELS & PERSISTENCE

**Source** : `/core/objects/` + `/database/`

#### Fichiers à Migrer → `shared/commonMain/kotlin/app/aimi/data/models/`

```
MODELS CRITIQUES:

1. GlucoseValue
   Source: core/objects/src/main/kotlin/app/aaps/core/data/model/GV.kt
   Destination: shared/commonMain/kotlin/app/aimi/data/models/GlucoseValue.kt
   Action: Copier tel quel (pure Kotlin)
   Effort: 2h
   Code partagé: 100%

2. Profile
   Source: core/objects/src/main/kotlin/app/aaps/core/data/model/Profile.kt
   Destination: shared/commonMain/kotlin/app/aimi/data/models/Profile.kt
   Effort: 5h
   Code partagé: 100%

3. Treatment, Bolus, TempBasal
   Source: core/objects/src/main/kotlin/app/aaps/core/data/model/TE.kt, BS.kt, TB.kt
   Destination: shared/commonMain/kotlin/app/aimi/data/models/Treatment.kt
   Effort: 10h
   Code partagé: 100%

4. LoopResult
   Source: plugins/aps/*/
   Destination: shared/commonMain/kotlin/app/aimi/data/models/LoopResult.kt
   Effort: 5h
   Code partagé: 100%

TOTAL MODELS: ~22h migration
```

#### Database Schema → `shared/commonMain/sqldelight/`

```
MIGRATION Room → SQLDelight:

1. glucose_values.sq
   Source: database/entities/src/main/kotlin/app/aaps/database/entities/GlucoseValue.kt
   Action: Convertir Room @Entity → SQLDelight .sq
   Effort: 8h

2. treatments.sq
   Source: database/entities/*/
   Effort: 12h

3. profiles.sq
   Effort: 8h

4. loop_history.sq
   Effort: 6h

TOTAL DATABASE: ~34h migration
```

---

### 🔷 3. POMPE MEDTRUM

**Source** : `/pump/medtrum/`

#### Protocol Layer (PARTAGÉ Android + iOS)

```
FICHIERS À COPIER → shared/commonMain/kotlin/app/aimi/pump/medtrum/:

✅ PROTOCOLE (100% partageable):

1. Packets & Commands
   Source: pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/packets/
   Destination: shared/commonMain/kotlin/app/aimi/pump/medtrum/protocol/packets/
   Fichiers:
     - AuthorizePacket.kt
     - ActivatePacket.kt
     - SetBasalProfilePacket.kt
     - SetBolusPacket.kt
     - SetTempBasalPacket.kt
     - NotificationPacket.kt
     - ReadDataPacket.kt
     - WriteCommandPackets.kt
   Action: Copier tel quel (pure Kotlin, pas dépendances Android)
   Effort: 15h
   Code partagé: 100%

2. Crypto & Encoding
   Source: pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/encryption/
   Destination: shared/commonMain/kotlin/app/aimi/pump/medtrum/protocol/crypto/
   Fichiers:
     - Crypt.kt
     - ManufacturerData.kt
   Effort: 5h
   Code partagé: 100%

3. State Machine
   Source: pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/MedtrumService.kt
   Destination: shared/commonMain/kotlin/app/aimi/pump/medtrum/MedtrumStateMachine.kt
   Action: Extraire logique state machine (sans Services Android)
   Effort: 30h
   Code partagé: 80%

TOTAL MEDTRUM PROTOCOL: ~50h migration
```

#### BLE Layer (PLATFORM-SPECIFIC)

```
❌ BLE NON PARTAGEABLE (implémentation séparée):

ANDROID:
Source: pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt
Destination: shared/androidMain/kotlin/app/aimi/pump/medtrum/MedtrumBLEAndroid.kt
Action: Adapter pour KMP (expect/actual pattern)
Effort: 40h

iOS:
Destination: shared/iosMain/kotlin/app/aimi/pump/medtrum/MedtrumBLEiOS.kt
Action: Réécrire avec CoreBluetooth
Effort: 60h

TOTAL MEDTRUM BLE: ~100h
```

**TOTAL MEDTRUM: ~150h**

---

### 🔷 4. POMPE OMNIPOD DASH

**Source** : `/pump/omnipod/dash/`

#### Protocol Layer (PARTAGÉ)

```
FICHIERS À COPIER → shared/commonMain/kotlin/app/aimi/pump/dash/:

✅ PROTOCOLE (100% partageable):

1. Message Packets
   Source: pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/message/
   Destination: shared/commonMain/kotlin/app/aimi/pump/dash/protocol/messages/
   Fichiers clés:
     - MessagePacket.kt
     - PayloadJoiner.kt
     - PayloadSplitter.kt
   Effort: 20h
   Code partagé: 100%

2. Commands
   Source: pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/command/
   Destination: shared/commonMain/kotlin/app/aimi/pump/dash/protocol/commands/
   Fichiers:
     - BaseCommand.kt
     - BlusCommand.kt
     - SetBasalProfileCommand.kt
     - SetTempBasalCommand.kt
     - SuspendDeliveryCommand.kt
   Effort: 25h
   Code partagé: 100%

3. Crypto (LTK, Nonce)
   Source: pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/session/
   Destination: shared/commonMain/kotlin/app/aimi/pump/dash/protocol/crypto/
   Effort: 15h
   Code partagé: 100%

4. Pod State
   Source: pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/pod/state/
   Destination: shared/commonMain/kotlin/app/aimi/pump/dash/DashPodState.kt
   Effort: 12h
   Code partagé: 100%

TOTAL DASH PROTOCOL: ~72h
```

#### BLE Layer (PLATFORM-SPECIFIC)

```
❌ BLE NON PARTAGEABLE:

ANDROID:
Source: pump/omnipod/dash/src/main/kotlin/app/aaps/plugins/pump/omnipod/dash/driver/comm/ble/
Destination: shared/androidMain/kotlin/app/aimi/pump/dash/DashBLEAndroid.kt
Effort: 50h

iOS:
Destination: shared/iosMain/kotlin/app/aimi/pump/dash/DashBLEiOS.kt
Effort: 70h

TOTAL DASH BLE: ~120h
```

**TOTAL DASH: ~192h**

---

### 🔷 5. CGM SUPPORT

**Source** : `/plugins/source/` + nouveau code

#### CGM Drivers → `shared/commonMain/kotlin/app/aimi/cgm/`

```
NOUVEAU CODE (CGM heartbeat pour iOS):

1. CGM Interfaces
   Destination: shared/commonMain/kotlin/app/aimi/cgm/CGMDriver.kt
   Action: Créer interface commune
   Effort: 10h
   Code partagé: 100%

2. xDrip4iOS Bridge Support
   Destination: shared/commonMain/kotlin/app/aimi/cgm/xDripBridge.kt
   Effort: 15h
   Code partagé: 80%

3. Dexcom G6/G7 Direct BLE
   Android: shared/androidMain/kotlin/app/aimi/cgm/DexcomBLEAndroid.kt
   iOS: shared/iosMain/kotlin/app/aimi/cgm/DexcomBLEiOS.kt
   Effort: 60h (30h Android + 30h iOS)

4. Heartbeat Manager (iOS 24/7)
   Source: Nouveau (architecture Trio)
   Destination: shared/commonMain/kotlin/app/aimi/cgm/CGMHeartbeat.kt
   Effort: 25h
   Code partagé: 90%

TOTAL CGM: ~110h
```

---

### 🔷 6. AUTOMATED LOOP

**Source** : `/plugins/aps/loop/` + nouveau code

#### Loop Manager → `shared/commonMain/kotlin/app/aimi/loop/`

```
FICHIERS À CRÉER/MIGRER:

1. ContinuousLoopManager.kt
   Source: plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt
   Action: Refactor sans Android Services
   Effort: 40h
   Code partagé: 95%

2. LoopCycleExecutor.kt
   Action: Orchestrate CGM → Algorithm → Pump
   Effort: 30h
   Code partagé: 100%

3. SafetyConstraints.kt
   Source: plugins/constraints/
   Effort: 20h
   Code partagé: 100%

TOTAL LOOP: ~90h
```

---

### 🔷 7. NETWORK & SYNC

**Source** : `/plugins/sync/`

#### Nightscout Sync → `shared/commonMain/kotlin/app/aimi/network/`

```
MIGRATION Retrofit → Ktor:

1. NightscoutAPI
   Source: plugins/sync/nsclient/src/main/kotlin/app/aaps/plugins/sync/nsclient/
   Destination: shared/commonMain/kotlin/app/aimi/network/NightscoutClient.kt
   Action: Convertir Retrofit → Ktor
   Effort: 25h
   Code partagé: 100%

2. Data Sync Manager
   Effort: 15h
   Code partagé: 95%

TOTAL NETWORK: ~40h
```

---

## 📊 Résumé Effort Migration

| Composant | Effort (heures) | % Code Partagé |
|-----------|-----------------|----------------|
| **Algorithmes AIMI** | 115h | 95% |
| **Data Models** | 22h | 100% |
| **Database (SQLDelight)** | 34h | 100% |
| **Pompe Medtrum** | 150h | 70% |
| **Pompe Dash** | 192h | 65% |
| **CGM Support** | 110h | 80% |
| **Automated Loop** | 90h | 95% |
| **Network/Sync** | 40h | 100% |
| **Setup & Config** | 50h | - |
| **Tests & Debug** | 100h | - |
| **UI (Compose MP)** | 120h | 75% |
| **TOTAL** | **~1023h** | **~82% moyen** |

**Équivalent** : ~6 mois à temps plein (1 dev) ou **3 mois avec 2 devs**

---

## 🗓️ Plan d'Exécution Détaillé

### **PHASE 1 : Setup & Foundation** (Semaines 1-2)

```
Semaine 1: Project Setup
  ├─> Créer projet KMP (Android Studio wizard)
  ├─> Configurer build.gradle.kts (KMP, SQLDelight, Ktor, ONNX)
  ├─> Setup iOS Xcode project
  ├─> Configurer libs.versions.toml
  └─> Hello World compile Android + iOS

Semaine 2: Base Architecture
  ├─> Créer structure folders (commonMain, androidMain, iosMain)
  ├─> Setup DI (Koin KMP)
  ├─> Logging (Kermit)
  └─> Configuration management

Livrable: Projet vide qui compile Android + iOS
Effort: 50h
```

### **PHASE 2 : Core Algorithm** (Semaines 3-6)

```
Semaine 3-4: DetermineBasalAIMI Migration
  ├─> Copier DetermineBasalAIMI2.kt → shared/commonMain
  ├─> Supprimer dépendances Android
  ├─> Créer interfaces (ProfileProvider, GlucoseProvider, etc.)
  └─> Tests unitaires (commonTest)

Semaine 5: IOB/COB Calculators
  ├─> Migrer IOBCalculator (pure Kotlin)
  ├─> Migrer COBCalculator
  └─> Tests

Semaine 6: Auto-Sensitivity & Dynamic ISF
  ├─> Migrer Auto-Sensitivity logic
  ├─> Implémenter Dynamic ISF
  └─> Integration tests

Livrable: Algorithme AIMI fonctionne en KMP (test avec mock data)
Effort: 140h
```

### **PHASE 3 : Data Layer** (Semaines 7-9)

```
Semaine 7-8: (to continue in next message)
```

---

**(Document en cours - Suite dans prochain message)**
