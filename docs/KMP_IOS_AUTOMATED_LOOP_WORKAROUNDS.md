# iOS Automated Loop : Workarounds pour Boucle Fermée 100% Automatique

**Question MTR**: "100% automatique. Il n'y a RIEN de possible avec KMP sur iOS ?"

**Réponse directe**: Si, il y a des **workarounds**, mais tous ont des **risques majeurs**.

**Date**: 2025-12-21T21:54+01:00

---

## 🎯 Clarification Critique

### KMP n'est PAS le problème !

```
❌ FAUX: "KMP ne permet pas boucle automatique iOS"
✅ VRAI: "iOS bloque l'exécution background, KMP ou pas"
```

**KMP fonctionne parfaitement sur iOS**. Le code s'exécute. Les algorithmes tournent.

**Le VRAI problème** : Apple tue les apps en background après 30 secondes.

---

## 🔓 Workarounds "Grey Area" pour Background iOS

### **Option 1: Silent Push Notifications** ⚠️ VIABLE

**Comment ça marche**:

```
Serveur (Nightscout/Cloud)
  ├─> Parse CGM data toutes les 5min
  ├─> Calcule si action nécessaire
  └─> Envoie silent push à iOS app
       ├─> iOS réveille app (30 secondes max)
       ├─> App lit données
       ├─> App exécute algorithme AIMI
       ├─> App envoie commande BLE pompe
       └─> App se rendort

Répète toutes les 5min via push
```

**Architecture KMP avec Silent Push**:

```kotlin
// shared/commonMain/loop/AutomatedLoop.kt

class AutomatedLoop(
    private val glucoseRepository: GlucoseRepository,
    private val pumpDriver: PumpDriver,
    private val aimiAlgorithm: DetermineBasalAIMI
) {
    /**
     * Exécuté quand silent push reçu (30s max exécution)
     */
    suspend fun executeLoopCycle(): Result<LoopResult> = withTimeout(25_000) {
        try {
            // 1. Fetch latest glucose (local DB synced by Nightscout)
            val glucose = glucoseRepository.getLatest()
            
            // 2. Run AIMI algorithm
            val decision = aimiAlgorithm.determineBasal(
                glucose = glucose,
                currentTemp = pumpDriver.getCurrentBasal(),
                iob = calculateIOB(),
                profile = getActiveProfile()
            )
            
            // 3. Send to pump via BLE (CRITIQUE: must complete in <25s)
            val pumpResult = when {
                decision.smb > 0 -> pumpDriver.deliverBolus(decision.smb)
                decision.rate != null -> pumpDriver.setTempBasal(decision.rate, decision.duration)
                else -> Result.success(Unit)
            }
            
            // 4. Log and return
            Result.success(LoopResult(
                timestamp = System.currentTimeMillis(),
                decision = decision,
                pumpResult = pumpResult
            ))
            
        } catch (e: TimeoutCancellationException) {
            // 30s timeout atteint - iOS va killer l'app
            Result.failure(Exception("Loop cycle timeout"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**iOS App (Swift)**:

```swift
// iosApp/AppDelegate.swift

import UIKit
import UserNotifications
import shared

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    
    var loopExecutor: AutomatedLoop?
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        
        // Register for remote notifications
        UNUserNotificationCenter.current().delegate = self
        application.registerForRemoteNotifications()
        
        // Initialize KMP loop
        loopExecutor = AutomatedLoop(
            glucoseRepository: DIContainer.shared.glucoseRepo,
            pumpDriver: DIContainer.shared.pumpDriver,
            aimiAlgorithm: DIContainer.shared.aimiAlgorithm
        )
        
        return true
    }
    
    // ✅ CRITIQUE: Ce callback est appelé même app fermée!
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable : Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let loopData = userInfo["loop"] as? [String: Any] else {
            completionHandler(.noData)
            return
        }
        
        // Execute loop cycle (KMP code!)
        Task {
            let result = try? await loopExecutor?.executeLoopCycle()
            
            switch result {
            case .success:
                completionHandler(.newData)
            case .failure:
                completionHandler(.failed)
            case .none:
                completionHandler(.noData)
            }
        }
    }
}
```

**Serveur (Node.js/Python)**:

```javascript
// Serveur qui envoie silent push toutes les 5min
const apn = require('apn');

setInterval(async () => {
    const latestGlucose = await nightscout.getLatestGlucose();
    
    // Simple check si action nécessaire
    if (latestGlucose.delta > 10 || latestGlucose.value > 180) {
        const notification = {
            topic: 'com.aimi.ios',
            payload: {
                aps: {
                    'content-available': 1  // Silent push!
                },
                loop: {
                    trigger: 'glucose_change',
                    glucose: latestGlucose.value
                }
            }
        };
        
        await apnProvider.send(notification, deviceTokens);
    }
}, 5 * 60 * 1000); // Toutes les 5 minutes
```

**Avantages**:
- ✅ Fonctionne app fermée
- ✅ Acceptable App Store (apps CGM font ça)
- ✅ Fiable si réseau stable
- ✅ KMP fonctionne parfaitement

**Inconvénients**:
- ⚠️ Dépend connexion internet
- ⚠️ Dépend serveur externe
- ⚠️ Pas de garantie delivery push (iOS décide)
- ⚠️ 30s timeout strict (BLE pump peut échouer)

**Verdict**: ⚠️ **Viable mais pas 100% fiable**

---

### **Option 2: HealthKit Background Delivery** ⚠️ LIMITÉ

**Principe**: HealthKit peut réveiller app quand nouvelle donnée glucose arrive

```swift
// iosApp/HealthKitManager.swift

import HealthKit

class HealthKitManager {
    let healthStore = HKHealthStore()
    
    func enableBackgroundDelivery() {
        let glucoseType = HKObjectType.quantityType(forIdentifier: .bloodGlucose)!
        
        // ✅ iOS réveille app quand nouvelle valeur glucose
        healthStore.enableBackgroundDelivery(for: glucoseType, frequency: .immediate) { success, error in
            if success {
                print("Background glucose delivery enabled")
            }
        }
    }
}

extension AppDelegate: HKObserver {
    func healthStore(_ store: HKHealthStore, didUpdate query: HKObserverQuery) {
        // ✅ Called even when app closed!
        // Execute loop cycle immediately
        Task {
            await loopExecutor?.executeLoopCycle()
        }
    }
}
```

**Avec KMP**:

```kotlin
// shared/iosMain/healthkit/HealthKitIntegration.kt

class HealthKitGlucoseSource : GlucoseSource {
    
    // Called by iOS when new glucose available
    suspend fun onNewGlucoseValue(value: Double, timestamp: Long) {
        // Store in DB
        glucoseRepository.insert(GlucoseValue(value, timestamp))
        
        // Trigger loop cycle
        AutomatedLoop.instance.executeLoopCycle()
    }
}
```

**Avantages**:
- ✅ Réveillée par iOS sur nouvelle glucose
- ✅ Acceptable App Store
- ✅ Pas besoin serveur externe

**Inconvénients**:
- ⚠️ Seulement si CGM écrit dans HealthKit
- ⚠️ Timing aléatoire (iOS contrôle)
- ⚠️ Toujours 30s timeout

**Verdict**: ⚠️ **Fonctionne mais timing non garanti**

---

### **Option 3: Location Background Mode** ❌ TRÈS RISQUÉ

**Principe**: Abuser du mode "location updates" pour garder app vivante

```swift
// ⚠️ ABUSE - Apple détecte et rejette!

import CoreLocation

class FakeLocationManager: NSObject, CLLocationManagerDelegate {
    let locationManager = CLLocationManager()
    
    func startContinuousUpdates() {
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 1  // Update every 1 meter
        
        // ❌ App reste "vivante" en prétendant tracker location
        locationManager.startUpdatingLocation()
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // On every location update (frequent), execute loop
        Task {
            await AutomatedLoop.instance.executeLoopCycle()
        }
    }
}
```

**Avantages**:
- ✅ App reste active continuellement
- ✅ Loop tourne vraiment toutes les 5min

**Inconvénients**:
- ❌ **Drain batterie massif**
- ❌ **Apple REJETTE** ces apps (détection abuse)
- ❌ **Violation App Store guidelines**
- ❌ User voit "app uses location" en permanence
- ❌ Antipattern flagrant

**Verdict**: ❌ **NE PAS UTILISER - Rejet App Store garanti**

---

### **Option 4: Audio Background Mode** ❌ DÉTECTÉ PAR APPLE

**Principe**: Jouer silence en boucle pour rester actif

```swift
// ❌ ABUSE - Apple détecte!

import AVFoundation

class SilentAudioPlayer {
    var audioPlayer: AVAudioPlayer?
    
    func startSilentPlayback() {
        // Play silent audio file in loop
        let silentAudioURL = Bundle.main.url(forResource: "silence", withExtension: "mp3")!
        audioPlayer = try? AVAudioPlayer(contentsOf: silentAudioURL)
        audioPlayer?.numberOfLoops = -1  // Infinite
        audioPlayer?.volume = 0.0  // Silent
        audioPlayer?.play()
        
        // App reste "vivante" en prétendant jouer audio
    }
}
```

**Verdict**: ❌ **Même problème que location - REJET**

---

## ✅ Solution RÉALISTE : Hybrid Approach

### **Combinaison Silent Push + HealthKit**

```
Normal operation (app foreground):
  └─> Loop tourne toutes les 5min (KMP code)
  └─> Envoie commandes pompe
  └─> ✅ Boucle fermée 100%

App en background/fermée:
  ├─> HealthKit delivery → réveille app sur nouvelle glucose
  │   └─> Execute loop cycle (30s window)
  │       └─> ✅ Semi-automatique
  │
  └─> Silent push (backup toutes les 5-15min)
      └─> Execute loop cycle (30s window)
          └─> ✅ Semi-automatique

User notification si échec:
  └─> "Please open AIMI to resume full automated loop"
```

**Implémentation KMP**:

```kotlin
// shared/commonMain/loop/HybridLoopManager.kt

sealed class LoopMode {
    object FullAutomated : LoopMode()      // App foreground
    object BackgroundAssisted : LoopMode()  // Silent push/HealthKit
    object Manual : LoopMode()              // App pas accessible
}

class HybridLoopManager(
    private val automatedLoop: AutomatedLoop,
    private val notificationManager: NotificationManager
) {
    private var currentMode = MutableStateFlow<LoopMode>(LoopMode.Manual)
    
    /**
     * Called by iOS lifecycle events
     */
    fun onAppStateChange(state: AppState) {
        currentMode.value = when (state) {
            AppState.Foreground -> {
                // Start continuous loop
                startContinuousLoop()
                LoopMode.FullAutomated
            }
            AppState.Background -> {
                // Stop continuous, rely on push/HealthKit
                stopContinuousLoop()
                LoopMode.BackgroundAssisted
            }
            AppState.Terminated -> {
                LoopMode.Manual
            }
        }
    }
    
    /**
     * Full automated - app foreground
     */
    private fun startContinuousLoop() {
        scope.launch {
            while (currentMode.value == LoopMode.FullAutomated) {
                try {
                    automatedLoop.executeLoopCycle()
                } catch (e: Exception) {
                    aapsLogger.error("Loop cycle failed", e)
                }
                delay(5.minutes)
            }
        }
    }
    
    /**
     * Background assisted - triggered by push/HealthKit
     */
    suspend fun onBackgroundTrigger(source: TriggerSource) {
        val result = automatedLoop.executeLoopCycle()
        
        if (result.isFailure) {
            // Notify user to open app
            notificationManager.sendCriticalAlert(
                title = "AIMI Loop Failed",
                body = "Please open AIMI app to resume automated loop",
                sound = .critical
            )
        }
    }
}
```

**iOS Integration**:

```swift
// iosApp/LoopCoordinator.swift

class LoopCoordinator {
    let hybridManager: HybridLoopManager
    
    init() {
        hybridManager = DIContainer.shared.hybridLoopManager
    }
    
    // App lifecycle
    func sceneDidBecomeActive(_ scene: UIScene) {
        // ✅ App foreground → Full automated
        hybridManager.onAppStateChange(state: .foreground)
    }
    
    func sceneDidEnterBackground(_ scene: UIScene) {
        // ⚠️ App background → Assisted mode
        hybridManager.onAppStateChange(state: .background)
    }
    
    // Background triggers
    func didReceiveRemoteNotification() {
        Task {
            await hybridManager.onBackgroundTrigger(source: .silentPush)
        }
    }
    
    func didReceiveHealthKitUpdate() {
        Task {
            await hybridManager.onBackgroundTrigger(source: .healthKit)
        }
    }
}
```

**Résultat**:
- ✅ **Boucle 100% automatique** quand app foreground
- ⚠️ **Boucle semi-automatique** quand app background (15-30min cycle)
- ⚠️ **User doit ouvrir app** si échecs répétés

---

## 🎯 Réponse Finale à MTR

### Est-ce que iOS peut faire du 100% automatique ?

**OUI** ✅ ... **MAIS** :

### Scénario 1: App Foreground (iPhone déverrouillé, app visible)
```
✅ Boucle fermée 100% automatique
✅ Loop toutes les 5min
✅ SMB automatiques
✅ Ajustements basal continus
✅ Identique à Android
```

### Scénario 2: App Background (iPhone verrouillé/app fermée)
```
⚠️ Boucle semi-automatique
⚠️ Loop toutes les 15-30min (via silent push)
⚠️ Peut rater des cycles
⚠️ 30s timeout (BLE peut échouer)
⚠️ Dépend réseau/serveur
```

### Scénario 3: Pas de réseau / Push échouent
```
❌ Boucle s'arrête
❌ User doit ouvrir app
❌ Notifications critiques envoyées
```

---

## 💡 La Vraie Question

### **Acceptes-tu** que iOS AIMI soit:

**Cas 1**: User **garde app ouverte** pendant moments critiques ?
- Pendant repas (2-3h)
- Pendant nuit (avec chargeur, écran allumé ?)
- ✅ Boucle 100% automatique pendant ces périodes

**Cas 2**: Quand app fermée → **Mode dégradé acceptable** ?
- Loop toutes les 15-30min (vs 5min Android)
- Peut nécessiter intervention manuelle occasionnelle
- User averti si problème

**Cas 3**: Solution **hybride** ?
- iOS app pour monitoring/advisor
- Android phone/watch pour vraie loop
- Ou attendre que iOS supporte background execution (jamais ?)

---

## 📱 Référence: Loop App (Open Source)

**Loop** (lookit/LoopKit sur GitHub) fait exactement ça:

```
Mode 1 (App foreground):
  └─> ✅ Full automated closed loop

Mode 2 (App background):
  └─> ⚠️ Degraded mode avec silent push
      └─> Fonctionne "assez bien"
      └─> Users rapportent gaps occasionnels

Apple acceptance:
  └─> ✅ Loop app est dans App Store (TestFlight)
  └─> Uses silent push + HealthKit
  └─> Disclaimers clairs "not for treatment decisions"
```

**Tu peux faire PAREIL avec AIMI !**

---

## 🎯 Ma Recommandation Finale

### Pour toi MTR:

**Si objectif = vraie boucle fermée 24/7 sans intervention**:
- ➡️ **Android reste supérieur**
- ➡️ iOS sera toujours en "mode dégradé" background

**Si objectif = supporter users iOS qui acceptent limitations**:
- ➡️ **Oui, faisable** avec hybrid approach
- ➡️ **100% auto** quand app ouverte
- ➡️ **Semi-auto** quand app fermée (comme Loop app)
- ➡️ **Acceptable** App Store avec disclaimers

**Si objectif = architecture KMP long-terme**:
- ➡️ **KMP fonctionne parfaitement**
- ➡️ Même code business logic Android + iOS
- ➡️ Juste mode exécution différent

---

## ❓ Question Directe pour Toi

**Version iOS qui fait**:
- ✅ Boucle 100% auto quand app foreground
- ⚠️ Boucle semi-auto (15-30min) quand app background via silent push
- ⚠️ Utilisateur averti si doit ouvrir app

**C'est suffisant pour toi ?** Ou tu considères que si c'est pas 100% auto 24/7, ça ne vaut pas le coup ?

**Sois honnête** - ça va guider la décision ! 🤔

---

**Auteur**: Lyra  
**Date**: 2025-12-21T21:54+01:00  
**Verdict**: iOS peut faire **"100% quand app ouverte + semi-auto background"**
