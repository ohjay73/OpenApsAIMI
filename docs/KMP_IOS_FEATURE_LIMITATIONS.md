# Features AIMI : Limitations iOS vs Android

**Question**: Quelles features d'AIMI ne seront PAS possibles sur iOS ?

**Date**: 2025-12-21T21:29+01:00  
**Analyse**: Comparaison feature-by-feature avec raisons techniques iOS

---

## 🚨 Features IMPOSSIBLES sur iOS

### ❌ **1. Boucle Fermée Automatique Complète (True Closed-Loop)**

**Status**: **IMPOSSIBLE** sur iOS tel quel

**Raison**: Limitations background execution iOS

#### Sur Android (AIMI actuel)
```kotlin
// Service Android - tourne 24/7 en background
class LoopService : Service() {
    override fun onStartCommand(): Int {
        // Boucle infinie qui tourne même écran éteint
        scope.launch {
            while (true) {
                // Toutes les 5 minutes
                val glucose = glucoseSource.getLatest()
                val decision = aimiAlgorithm.determine(glucose)
                
                // ENVOIE AUTOMATIQUEMENT à la pompe
                pump.setTempBasal(decision.rate)
                pump.deliverSMB(decision.smb)
                
                delay(5.minutes)
            }
        }
    }
}
```

**✅ Fonctionne**: App peut être complètement fermée, service continue

#### Sur iOS (IMPOSSIBLE)
```swift
// iOS - PAS de vrai background service
class LoopService {
    func start() {
        // ❌ PROBLÈME: iOS tue ce code après 30 secondes en background
        Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { _ in
            // Cette boucle NE TOURNE PAS quand app est fermée
            let glucose = fetchGlucose()
            let decision = determineBasal(glucose)
            
            // ❌ N'atteint JAMAIS ce code si app fermée
            sendToPump(decision)
        }
    }
}
```

**❌ Échoue**: Dès que l'utilisateur ferme l'app ou verrouille l'écran, le code s'arrête

#### Workarounds iOS (Tous Limités)

| Workaround | Durée Max | Fiabilité | Apple Approval |
|------------|-----------|-----------|----------------|
| **Background fetch** | 15s toutes les 15min+ | ⚠️ Aléatoire | ✅ OK |
| **Silent push notifications** | 30s | ⚠️ Dépend réseau | ✅ OK |
| **Location updates** (abuse) | Continu | ❌ Batterie + rejet App Store | ❌ Risqué |
| **Audio background** (silence) | Continu | ❌ Détecté par Apple | ❌ REJET |
| **VoIP** (abuse pour loop) | Continu | ❌ Abuse du système | ❌ REJET |

**Conséquence AIMI iOS**:
- ⚠️ Boucle fonctionne SEULEMENT si app au premier plan
- ⚠️ Ou via réveil toutes les 15-30 min (trop lent pour SMB réactifs)
- ⚠️ User DOIT garder app active ou utiliser Loop via Nightscout remote commands

---

### ❌ **2. Super Micro Bolus (SMB) Automatiques Réactifs**

**Status**: **FORTEMENT DÉGRADÉ** sur iOS

**Raison**: Nécessite exécution toutes les 5 minutes, impossible en background iOS

#### Sur Android
```kotlin
// Détection spike glucose et réaction immédiate
fun determineSMB(glucose: List<GlucoseValue>): Double {
    val delta = glucose.last().value - glucose[glucose.size - 2].value
    
    return when {
        delta > 15 -> {
            // Spike détecté! Délivre SMB dans les 5 minutes
            calculateAggressiveSMB(delta)  // Ex: 2.5 U
        }
        delta > 8 -> calculateModerateSMB(delta)  // Ex: 1.0 U
        else -> 0.0
    }
}
```

**✅ Android**: Réaction en ~5 minutes max

#### Sur iOS (Dégradé)
```swift
// Même algorithme MAIS exécution retardée
func determineSMB(glucose: [GlucoseValue]) -> Double {
    // ⚠️ Ce code ne s'exécute que:
    // - Quand app est ouverte
    // - OU toutes les 15-30min via background fetch
    // OU via silent push (si serveur Nightscout envoie)
    
    let delta = glucose.last!.value - glucose[glucose.count - 2].value
    
    // MÊME logique qu'Android
    if delta > 15 {
        return calculateAggressiveSMB(delta)
    }
    // ...
}
```

**❌ iOS**: Réaction peut prendre 15-30 minutes → SMB inutile (trop tard)

**Impact**:
- 🔴 **Gestion repas dégradée**: SMB pour couvrir pics post-prandiaux trop lents
- 🔴 **Corrections hyperglycémie**: Moins réactives qu'Android
- 🟡 **Workaround**: User peut ouvrir app pour forcer calcul, ou configurer alertes Nightscout

---

### ❌ **3. Ajustements Basal Automatiques Continus**

**Status**: **IMPOSSIBLE** en background total

**Raison**: Même problème que SMB - pas d'exécution continue

#### Sur Android
```kotlin
// Ajuste basal toutes les 5 min selon prédictions
fun adjustBasal(prediction: Prediction) {
    val currentBasal = pump.getCurrentBasal()
    val targetBasal = when {
        prediction.eventualBG > targetHigh -> currentBasal * 1.3  // Augmente
        prediction.eventualBG < targetLow -> currentBasal * 0.5   // Réduit
        else -> currentBasal
    }
    
    // Envoie IMMÉDIATEMENT
    pump.setTempBasal(targetBasal, duration = 30.minutes)
}
```

**✅ Android**: Basal s'ajuste automatiquement toutes les 5min

#### Sur iOS
```swift
// MÊME algorithme mais timing cassé
func adjustBasal(prediction: Prediction) {
    // ❌ Ce code ne s'exécute que si app foreground ou background fetch
    
    let currentBasal = pump.getCurrentBasal()
    let targetBasal = // ... même calcul
    
    // ⚠️ Envoi retardé de 15-30min = dangereux
    pump.setTempBasal(targetBasal, duration: 30)
}
```

**❌ iOS**: Ajustements trop lents → risque hypo/hyper non détectées à temps

---

### ❌ **4. Alertes Prédictives Temps Réel**

**Status**: **DÉGRADÉES** sur iOS

**Raison**: Calculs prédictifs nécessitent exécution fréquente

#### Sur Android
```kotlin
// Toutes les 5min, calcule prédictions 4h
fun predictHypo(): Boolean {
    val predictions = aiModel.predict(next4Hours)
    
    return predictions.any { it.bg < 70 && it.timestamp < now + 30.minutes }
}

// Si détecté, alerte IMMÉDIATE
if (predictHypo()) {
    notificationManager.notify("Hypo prédite dans 30min!")
    // ET réduit automatiquement le basal
    pump.setTempBasal(0.0, 30.minutes)
}
```

**✅ Android**: Prédiction et action préventive en temps réel

#### Sur iOS
```swift
// Même algorithme mais exécution sporadique
func predictHypo() -> Bool {
    // ⚠️ Calculé seulement toutes les 15-30min
    let predictions = aiModel.predict(next4Hours: 4 * 60)
    
    return predictions.contains { $0.bg < 70 }
}

// ⚠️ Alerte arrive avec retard
if predictHypo() {
    // L'hypo peut déjà avoir commencé!
    sendNotification("Hypo prédite")
}
```

**❌ iOS**: Fenêtre de prévention réduite → moins efficace

---

## ⚠️ Features DÉGRADÉES sur iOS

### 🟡 **5. Auto-Sensitivity (Calcul ISF/IC Dynamique)**

**Status**: **FONCTIONNE** mais moins précis

**Raison**: Nécessite historique continu de données

#### Sur Android
```kotlin
// Toutes les 5min, enregistre résultat loop
fun recordLoopResult(result: LoopResult) {
    database.insert(LoopHistory(
        timestamp = now,
        glucoseBefore = result.glucoseBefore,
        glucoseAfter = result.glucoseAfter,
        insulinDelivered = result.insulin,
        carbsAbsorbed = result.carbs
    ))
}

// Toutes les 24h, ajuste sensibilité
fun calculateAutoSens(): Double {
    val history = database.getLastNDays(7)  // 7 jours complets
    
    // Régression sur 2000+ datapoints
    return calculateISFAdjustment(history)  // Ex: ISF * 0.9 (plus sensible)
}
```

**✅ Android**: Historique complet 24/7 → calcul précis

#### Sur iOS
```kotlin
// Même algo mais données manquantes
fun recordLoopResult(result: LoopResult) {
    // ⚠️ Seulement enregistré si app active
    // Donc historique avec "trous" de plusieurs heures
    database.insert(LoopHistory(...))
}

fun calculateAutoSens(): Double {
    val history = database.getLastNDays(7)
    // ⚠️ Historique incomplet (ex: seulement 60% des datapoints)
    
    // Calcul moins précis
    return calculateISFAdjustment(history)  // Moins fiable
}
```

**🟡 iOS**: Auto-sens fonctionne mais basé sur données partielles

**Impact**: 
- Ajustements ISF/IC moins précis
- Peut nécessiter ajustements manuels plus fréquents

---

### 🟡 **6. Unannounced Meal Detection (UAM)**

**Status**: **FONCTIONNE** mais détection retardée

**Raison**: Détection nécessite monitoring continu glucose

#### Sur Android
```kotlin
// Détecte repas non annoncés via analyse delta
fun detectUAM(glucose: List<GlucoseValue>): Boolean {
    val delta30min = glucose.last().value - glucose[6].value  // 6 * 5min = 30min
    
    return delta30min > 30 && iob.total < 0.5  // Montée rapide sans insuline = repas
}

// Réaction IMMÉDIATE
if (detectUAM()) {
    aapsLogger.info("UAM détecté! Augmente basal")
    pump.setTempBasal(basal * 1.5, 60.minutes)
}
```

**✅ Android**: Détection en ~15-20min après début repas

#### Sur iOS
```swift
// Même algorithme mais exécution retardée
func detectUAM(glucose: [GlucoseValue]) -> Bool {
    // ⚠️ Calculé toutes les 15-30min seulement
    let delta30min = glucose.last!.value - glucose[6].value
    
    return delta30min > 30 && iob.total < 0.5
}

// ⚠️ Réaction 15-30min APRÈS détection possible
if detectUAM() {
    pump.setTempBasal(basal * 1.5, duration: 60)
}
```

**🟡 iOS**: UAM détecté mais trop tard → pic glucose plus élevé

**Impact**:
- User doit annoncer repas plus systématiquement
- Ou accepter pics post-prandiaux plus élevés

---

### 🟡 **7. Dynamic ISF (Ajustement Temps Réel)**

**Status**: **FONCTIONNE** mais recalcul moins fréquent

**Raison**: Ajustement dynamique nécessite calculs fréquents

#### Sur Android
```kotlin
// Toutes les 5min, ajuste ISF selon BG actuel
fun getDynamicISF(currentBG: Double, profile: Profile): Double {
    val baseISF = profile.isf
    
    return when {
        currentBG > 180 -> baseISF * 0.8  // Plus agressif si haut
        currentBG < 100 -> baseISF * 1.2  // Plus conservateur si bas
        else -> baseISF
    }
}

// Utilisé IMMÉDIATEMENT pour SMB/basal
val isf = getDynamicISF(glucose.last().value, profile)
val smb = (targetBG - currentBG) / isf
```

**✅ Android**: ISF s'adapte toutes les 5min

#### Sur iOS
```swift
// Même algorithme mais recalcul espacé
func getDynamicISF(currentBG: Double, profile: Profile) -> Double {
    // ⚠️ Recalculé toutes les 15-30min seulement
    
    let baseISF = profile.isf
    return currentBG > 180 ? baseISF * 0.8 : baseISF
}

// ⚠️ ISF peut être obsolète de 15-30min
let isf = getDynamicISF(glucose.last!.value, profile)
```

**🟡 iOS**: Dynamic ISF moins réactif

---

## ✅ Features QUI FONCTIONNENT sur iOS

### ✅ **8. Calculs Algorithmiques (Pure Logic)**

**Status**: **100% FONCTIONNEL**

**Raison**: Pure math, pas de dépendance timing

Features OK:
- ✅ Algorithme AIMI (DetermineBasal)
- ✅ Calculs IOB (Insulin On Board)
- ✅ Calculs COB (Carbs On Board)
- ✅ Prédictions glucose (quand déclenchées)
- ✅ Calculs bolus (insulin calculator)
- ✅ Pharmacocinétique modèles

**Exemple**:
```kotlin
// Fonctionne IDENTIQUEMENT sur Android et iOS
fun calculateIOB(treatments: List<Treatment>, now: Long): IOB {
    return treatments
        .filter { it.timestamp > now - 6.hours }
        .sumOf { treatment ->
            val elapsed = (now - treatment.timestamp).minutes
            val dia = profile.dia.hours.inWholeMinutes
            
            // Courbe exponentielle decay
            val percentRemaining = when {
                elapsed >= dia -> 0.0
                else -> exp(-elapsed / (dia * 0.4))
            }
            
            treatment.insulin * percentRemaining
        }
}
```

**✅ Ce code donne EXACTEMENT le même résultat sur Android et iOS**

---

### ✅ **9. Affichage & Monitoring**

**Status**: **100% FONCTIONNEL**

Features OK:
- ✅ Graphiques glucose (temps réel si app ouverte)
- ✅ Affichage IOB/COB
- ✅ Historique traitements
- ✅ Statistiques (TIR, A1C estimé, etc.)
- ✅ Rapports
- ✅ Nightscout sync (upload/download)

---

### ✅ **10. Bolus Calculateur Manuel**

**Status**: **100% FONCTIONNEL**

**Raison**: User-initiated, pas besoin background

```kotlin
// Fonctionne parfaitement sur iOS
fun calculateBolusWizard(
    carbs: Double,
    currentBG: Double,
    targetBG: Double
): BolusRecommendation {
    val carbInsulin = carbs / profile.ic
    val correctionInsulin = (currentBG - targetBG) / profile.isf
    val totalInsulin = carbInsulin + correctionInsulin - iob.total
    
    return BolusRecommendation(
        carbs = carbs Insulin = maxOf(0.0, totalInsulin),
        explanation = "..."
    )
}
```

**✅ iOS**: User ouvre app, entre carbs, reçoit suggestion, confirme
- Identique à Android

---

## 📊 Tableau Récapitulatif Features

| Feature | Android | iOS | Note iOS |
|---------|---------|-----|----------|
| **Boucle fermée 24/7** | ✅ Auto | ❌ Impossible | App doit rester ouverte |
| **SMB automatiques** | ✅ 5min | ⚠️ 15-30min | Trop lent pour être efficace |
| **Ajustements basal auto** | ✅ 5min | ❌ Limité | Dangereux avec délais |
| **Alertes prédictives** | ✅ Temps réel | 🟡 Retardées | Fenêtre prévention réduite |
| **Auto-Sensitivity** | ✅ Précis | 🟡 Approximatif | Historique incomplet |
| **UAM Detection** | ✅ 15-20min | 🟡 30-45min | Détection tardive |
| **Dynamic ISF** | ✅ Temps réel | 🟡 Espacé | Moins réactif |
| **IOB/COB calculs** | ✅ | ✅ | Identique |
| **Bolus calculator** | ✅ | ✅ | Identique |
| **Nightscout sync** | ✅ | ✅ | Identique |
| **Graphiques/Stats** | ✅ | ✅ | Identique |
| **Profiles/Settings** | ✅ | ✅ | Identique |

---

## 🎯 Conclusion : iOS = Boucle "Hybride" Forcée

### Ce qui sera possible sur iOS:
1. ✅ **Monitoring avancé** (glucose, tendances, prédictions)
2. ✅ **Recommandations intelligentes** (bolus, basals)
3. ✅ **Semi-automation** si user garde app ouverte
4. ✅ **Remote monitoring** via Nightscout parfait

### Ce qui NE sera PAS possible:
1. ❌ **True closed-loop** (boucle 100% automatique 24/7)
2. ❌ **SMB réactifs** (trop de délai)
3. ❌ **Ajustements basal continus** (dangereux avec délais)

### iOS AIMI serait plutôt:
- **"Smart Advisor"** : Conseille, mais user agit
- **"Hybrid Loop"** : Automatique SI app ouverte
- **"Remote Monitor"** : Parfait pour caregivers

---

## 💡 Recommandation Finale

**Pour un vrai système de boucle fermée performant** :
➡️ **Android reste supérieur techniquement**

**Si port iOS** :
➡️ **Positionner comme "Advisor & Monitor"**, pas "Closed-Loop System"
➡️ Évite problèmes App Store (pas de claims médicaux automatiques)
➡️ Délivre quand même beaucoup de valeur aux users iOS

**Alternative** :
➡️ iOS app en "companion" d'un Android phone/watch qui fait la vraie loop ?

---

**Auteur**: Lyra  
**Date**: 2025-12-21T21:29+01:00  
**Verdict**: iOS peut faire **AIMI Advisor**, pas **AIMI Closed-Loop**
