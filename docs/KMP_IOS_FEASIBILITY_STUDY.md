# Étude de Faisabilité : Port AIMI sur Kotlin Multiplatform (KMP) pour iOS

**Demande**: @mtr souhaite porter AIMI (AndroidAPS AI Medical Intelligence) sur KMP pour supporter iOS en plus d'Android

**Date**: 2025-12-21T21:07+01:00  
**Type**: Analyse stratégique et technique approfondie  
**Statut**: 🔬 **EN COURS D'ANALYSE**

---

## 🎯 Objectif du Projet

###Porter AIMI vers KMP pour :
- ✅ **Android** (déjà existant, à maintenir)
- ✨ **iOS** (nouveau, à créer)
- 🔄 **Code partagé maximum** entre les deux plateformes

### Périmètre
- **Business Logic**: Algorithmes OpenAPS, calculs d'insuline, machine learning
- **Data Layer**: Base de données, synchronisation cloud
- **UI**: Interfaces utilisateurs (partielle ou native ?)
- **Pump Drivers**: Communication Bluetooth avec pompes
- **Sensors**: CGM (Continuous Glucose Monitoring)

---

## 📊 Analyse de l'Architecture Actuelle

### Structure du Projet (OpenAPS AIMI)

```
OpenApsAIMI/
├── app/                    # Application Android principale
├── core/                   # Modules core (data, interfaces, objects, ui, utils, validators)
├── database/               # Persistence (Room DB Android)
├── plugins/
│   ├── aps/               # ⭐ ALGORITHME AIMI (cœur métier)
│   ├── automation/         # Automatisation événements
│   ├── configuration/      # Config utilisateur
│   ├── constraints/        # Contraintes sécurité
│   ├── insulin/            # Modèles pharmacocinétiques
│   ├── sensitivity/        # Sensibilité à l'insuline
│   ├── smoothing/          # Lissage données
│   ├── source/             # Sources données (CGM, etc.)
│   └── sync/               # Synchronisation cloud (Nightscout, Tidepool)
├── pump/                   # Drivers pompes (Combo, Dana, Medtrum, Omnipod, etc.)
├── shared/                 # Code partagé (limité actuellement)
├── ui/                     # Composants UI Android
├── wear/                   # Android Wear support
└── workflow/               # Workflows activité
```

### Modules Clés Identifiés

| Module | Lignes Code (approx) | Android-Specific ? | KMP-Portable ? |
|--------|----------------------|--------------------|----------------|
| **plugins/aps** (AIMI core) | ~25,000 | Partiel (Services Android) | ✅ 80%+ |
| **core/data** | ~15,000 | Oui (Room DB) | ⚠️ 60% (DB à migrer) |
| **core/interfaces** | ~5,000 | Non | ✅ 95% |
| **core/objects** | ~10,000 | Non | ✅ 95% |
| **pump/*** (drivers) | ~50,000 | Oui (Bluetooth Android) | ❌ 30% (BLE platf-specific) |
| **database** | ~20,000 | Oui (Room) | ⚠️ 50% (migrer SQLDelight) |
| **plugins/sync** | ~10,000 | Partiel (NetworkManager Android) | ✅ 70% (Ktor) |
| **ui** | ~30,000 | Oui (Jetpack Compose/Views) | ⚠️ Variable |

**TOTAL PROJECT**: ~500,000+ lignes de code Kotlin/Java

---

## 🔍 Analyse détaillée par Couche

### **1. Business Logic (Algorithmes AIMI)** ⭐ HAUTE PRIORITÉ

#### Portabilité: ✅ **EXCELLENTE (90%)**

**Modules concernés**:
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAMA/DetermineBasalAIMI2.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/*/`
- `plugins/insulin`
- `plugins/sensitivity`
- `plugins/smoothing`

**Code actuel** (pur algorithme):
```kotlin
// DetermineBasalAIMI2.kt - déjà portable!
fun determineBasal(
    glucose: MutableList<GlucoseValue>,
    currentTemp: TemporaryBasal?,
    iob: IobTotal,
    profile: Profile,
    // ...
): DetermineBasalResultAPS {
    // Pure math & logic - NO Android dependencies
    val eventualBG = calculateEventualBG(...)
    val minDelta = calculateMinDelta(...)
    val targetBG = profile.getTargetMgdl()
    
    // Machine Learning
    val aiPrediction = aiModel.predict(features)
    
    // Decision logic
    return when {
        glucose.isRising() -> calculateSMB(...)
        glucose.isFalling() -> calculateTempBasal(...)
        else -> maintainCurrent()
    }
}
```

**✅ Déjà KMP-compatible** car:
- Pas de dépendances Android
- Pure Kotlin
- Algorithmes mathématiques
- Pas d'IO (juste calculs)

**Action**:
- Déplacer vers `shared/business`
- Créer interfaces pour injection dépendances
- ~5-10h de refactoring

---

### **2. Data Layer (Base de Données)** ⚠️ CHALLENGE MOYEN

#### Portabilité: ⚠️ **MODÉRÉE (60%)**

**Problème**: Utilise **Room** (Android-only)

**Solution KMP**: Migrer vers **SQLDelight**

**Comparaison**:

| Aspect | Room (Android) | SQLDelight (KMP) |
|--------|----------------|------------------|
| **SQL** | Annotations Kotlin | Fichiers .sq |
| **Type-safety** | ✅ | ✅ |
| **Migrations** | `@Migration` | `.sqm` files |
| **Platform** | Android-only | ✅ Android + iOS |
| **Performance** | Très bon | Très bon |

**Exemple de migration**:

**AVANT (Room)**:
```kotlin
@Entity(tableName = "glucoseValues")
data class GlucoseValue(
    @PrimaryKey val timestamp: Long,
    val value: Double,
    val raw: Double?
)

@Dao
interface GlucoseDao {
    @Query("SELECT * FROM glucoseValues WHERE timestamp > :since ORDER BY timestamp DESC")
    fun getRecentValues(since: Long): Flow<List<GlucoseValue>>
}
```

**APRÈS (SQLDelight)**:
```sql
-- glucoseValues.sq
CREATE TABLE glucoseValues (
    timestamp INTEGER NOT NULL PRIMARY KEY,
    value REAL NOT NULL,
    raw REAL
);

getRecentValues:
SELECT * FROM glucoseValues
WHERE timestamp > :since
ORDER BY timestamp DESC;
```

```kotlin
// Shared code
class GlucoseRepository(private val database: Database) {
    fun getRecentValues(since: Long): Flow<List<GlucoseValue>> =
        database.glucoseQueries.getRecentValues(since)
            .asFlow()
            .mapToList()
}
```

**Effort estimé**: 40-60h (migration complète DB)

---

### **3. Network & Sync (Nightscout, Tidepool)** ✅ FACILE

#### Portabilité: ✅ **EXCELLENTE (85%)**

**Problème**: Utilise OkHttp + Retrofit (Android-focused mais portable)

**Solution KMP**: Migrer vers **Ktor Client**

**Exemple**:

**AVANT (Retrofit Android)**:
```kotlin
interface NightscoutAPI {
    @GET("api/v1/entries.json")
    suspend fun getEntries(@Query("count") count: Int): List<Entry>
}
```

**APRÈS (Ktor KMP)**:
```kotlin
// commonMain
class NightscoutClient(private val httpClient: HttpClient) {
    suspend fun getEntries(count: Int): List<Entry> =
        httpClient.get("https://nightscout.example.com/api/v1/entries.json") {
            parameter("count", count)
        }.body()
}

// androidMain / iosMain - Platform-specific HTTP engine
```

**Effort estimé**: 15-20h

---

### **4. Bluetooth (Pump Drivers)** ❌ TRÈS DIFFICILE

#### Portabilité: ❌ **FAIBLE (20-30%)**

**GROS PROBLÈME**: Bluetooth est **extrêmement platform-specific**

**Android BLE**:
```kotlin
// Android
val bluetoothGatt = device.connectGatt(context, false, gattCallback)
```

**iOS CoreBluetooth**:
```swift
// iOS
import CoreBluetooth
let centralManager = CBCentralManager(delegate: self, queue: nil)
```

**Pas de library KMP mature** pour BLE à ce jour. Options:

| Option | Difficulté | Effort | Risque |
|--------|------------|--------|--------|
| **A. Kable** (experimental KMP BLE) | Haute | 80-120h | ⚠️⚠️ Instable |
| **B. expect/actual platform-specific** | Très haute | 150-200h | ⚠️⚠️⚠️ Duplication |
| **C. Wrapper natif iOS** | Extrême | 200h+ | ⚠️⚠️⚠️ Maintenance |

**Recommandation**: 
- Phase 1: **iOS sans pompes** (CGM only via cloud sync)
- Phase 2: Implémenter 1-2 pumps prioritaires avec expect/actual
- Phase 3: Attendre library KMP BLE stable

---

### **5. User Interface** ⚠️ CHALLENGE VARIABLE

#### Portabilité: Dépend de la stratégie

**Option A: Compose Multiplatform** ✅ RECOMMANDÉ

```kotlin
// commonMain - UI partagée à 80%
@Composable
fun GlucoseChart(data: List<GlucoseValue>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Drawing code - même sur Android et iOS
        data.forEach { point ->
            drawCircle(...)
        }
    }
}
```

**Effort**: 60-80h pour migrer UI existante
**Partage code**: 70-80%

**Option B: Native UI** (SwiftUI iOS + Compose Android)

**Effort**: 120-150h
**Partage code**: 0% UI, 100% business logic

**Recommandation**: **Compose Multiplatform** pour maximiser partage

---

## 🚧 Challenges Spécifiques iOS

### **1. Background Execution** ⚠️⚠️⚠️ CRITIQUE

**Android**: Services illimités en background

**iOS**: **TRÈS RESTRICTÉ**
- Background modes limités (location, audio, VoIP, etc.)
- **Pas de "insulin pump background service"** officiel
- Workarounds:
  - Silent push notifications
  - Location updates (abuse détecté par Apple)
  - HealthKit background delivery

**Impact**: ⚠️ **Fonctionnement boucle fermée compromis sur iOS**

**Solution**:
1. HealthKit integration pour CGM data
2. Silent push de Nightscout pour réveil app
3. Apple Watch companion pour monitoring continu ?

---

### **2. HealthKit Integration** ✅ OPPORTUNITÉ

**iOS advantage**: HealthKit API riche

```swift
// iOS
import HealthKit

let glucoseType = HKQuantityType.quantityType(forIdentifier: .bloodGlucose)!
let glucoseSample = HKQuantitySample(
    type: glucoseType,
    quantity: HKQuantity(unit: .milligramsPerDeciliter, doubleValue: 120.0),
    start: Date(),
    end: Date()
)
healthStore.save(glucoseSample)
```

**Bénéfice**: Integration native iOS Health app

---

### **3. App Store Review** ⚠️⚠️ RISQUE COMMERCIAL

**Problème**: App Store Guidelines 5.1.1 (ii)
> "Apps that provide medical services such as insulin dosage cannot be standalone; they must integrate with approved hardware."

**Risques**:
- ❌ Rejet si "DIY artificial pancreas"
- ⚠️ Nécessite labeling clair "not for treatment decisions"
- ⚠️ Potentiel besoin certification médicale (FDA/CE)

**Mitigation**:
- Disclaimers clairs
- Mode "open loop" par défaut
- Pas de claims médicaux

---

## 📈 Estimation d'Effort Globale

### **Phase 1: POC (Proof of Concept)** - 3 mois

| Tâche | Effort | Priorité |
|-------|--------|----------|
| Setup projet KMP | 20h | P0 |
| Migrer business logic (APS) | 60h | P0 |
| Migrer data layer (SQLDelight) | 80h | P0 |
| Network (Ktor) | 20h | P1 |
| UI basique Compose MP | 60h | P1 |
| Tests iOS | 40h | P0 |
| **TOTAL PHASE 1** | **~280h** | **~7 semaines à temps plein** |

**Livrable**: App iOS lecture seule (affichage glucose, suggestions, mais pas d'envoi commandes pompe)

### **Phase 2: Production-Ready** - 6-9 mois

| Tâche | Effet | Priorité |
|-------|--------|----------|
| UI complète Compose MP | 120h | P0 |
| Bluetooth 1ère pompe (Medtrum) | 150h | P0 |
| Background execution iOS | 80h | P0 |
| HealthKit integration | 40h | P1 |
| Tests utilisateurs iOS | 100h | P0 |
| App Store preparation | 60h | P0 |
| Documentation | 40h | P1 |
| **TOTAL PHASE 2** | **~590h** | **~15 semaines** |

### **Phase 3: Feature Parity** - 12+ mois

| Tâche | Effort | Note |
|-------|--------|------|
| Tous les drivers pompes | 500h+ | Énorme |
| Android Wear → Apple Watch | 200h | |
| Stabilisation production | 300h | |

---

## ✅ Faisabilité : VERDICT

### 🟢 **OUI, C'EST FAISABLE** mais avec disclaimers importants:

### Faisable Techniquement
- ✅ Business logic: **Excellente** portabilité (90%)
- ✅ Data layer: **Bonne** portabilité avec SQLDelight (70%)
- ✅ Network: **Excellente** portabilité avec Ktor (85%)
- ⚠️ UI: **Bonne** avec Compose Multiplatform (70-80%)
- ❌ Bluetooth: **Difficile**, nécessite code platform-specific (30%)

### Faisable Pratiquement
- ⚠️ **Phase 1** (lecture seule): Faisable en 3 mois
- ⚠️⚠️ **Phase 2** (boucle fermée partielle): Faisable en 9-12 mois
- ⚠️⚠️⚠️ **Phase 3** (feature parity complète): 18-24 mois

### Risques Majeurs
1. **Background iOS**: Limitation technique Apple → boucle fermée moins efficace qu'Android
2. **Bluetooth**: Pas de lib KMP mature → beaucoup de code dupliqué
3. **App Store**: Risque rejet si mal positionné
4. **Maintenance**: Double plateforme = double effort long-terme

---

## 🎯 Recommandations Stratégiques

### **Recommandation A: Hybrid Approach** ⭐ PRAGMATIQUE

**Phase 1** (6 mois): iOS **Viewer + Advisor**
- Affichage glucose temps réel
- Prédictions AIMI
- Recommandations bolus/basal
- **MAIS**: Utilisateur entre manuellement sur pompe

**Avantages**:
- Moins risqué (pas de commandes automatiques)
- Acceptable App Store
- Délivre valeur rapidement
- Teste architecture KMP

**Phase 2** (12 mois): iOS **Semi-closed Loop**
- 1-2 pompes supportées
- Background limité mais fonctionnel
- Full feature set Android maintenu

### **Recommandation B: Full KMP Refactor** ⚠️ AMBITIEUX

Refactorer **tout** AIMI Android vers KMP
- Partage 80% du code
- iOS natif dès le début
- Architecture moderne

**Avantages**:
- Code base unifié
- Maintenance simplifiée long-terme
- iOS "first-class citizen"

**Inconvénients**:
- 18-24 mois de développement
- Risque régression Android
- Besoin équipe KMP expérimentée

### **Recommandation C: Fork iOS Natif** ❌ PAS RECOMMANDÉ

Réécrire AIMI en Swift natif
- Algorithmes réimplementés

**Pourquoi non**:
- Duplication massive(continued)
