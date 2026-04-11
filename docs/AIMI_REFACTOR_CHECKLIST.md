# AIMI refactor — check-list d’avancement

Dernière mise à jour : Phase 2 **P1** quasi complet (tags `logDecisionFinal` couverts sauf 3 cas lourds) ; **P2** amorcé (`CompressionReboundGuard`) ; checklist backlog P3/P4 actualisée.

## Légende

- **Fait** — dans le dépôt, comportement métier inchangé dans le périmètre concerné  
- **Partiel**  
- **À faire**  
- **Décision** — nécessite une règle produit / clinique écrite  

---

## Sécurité & hypo

| Item | Statut |
|------|--------|
| `HypoThresholdMath` | Fait |
| `HypoGuard` + `HighBgOverride` aligné + tests | Fait |
| Logique LGS / bruit → `safety/LgsSafetyTriage.kt` + `LgsSafetyTriageTest` ; `trySafetyStart` = wrapper | Fait |

## Prédictions

| Item | Statut |
|------|--------|
| `PredictionSanity` + tests | Fait |
| `minPredictedAcrossCurves` → `PredictionCurveMath` + tests | Fait |

## Stacking & SMB

| Item | Statut |
|------|--------|
| `InsulinStackingSignals` + tests | Fait |
| `clampSmbToMaxSmbAndMaxIob` + tests | Fait |
| `capSmbDose` → `safety/CapSmbDose.kt` + tests | Fait |

## Circadien (SMB / sensibilité heure)

| Item | Statut |
|------|--------|
| Polynôme unifié `circadianSensitivityHourly` + tests vs formule legacy SMB | Fait |
| Variable `circadianSmb` (jamais lue) supprimée — aucun impact décisionnel | Fait |

## `DetermineBasalAIMI2` — structure & perf

| Item | Statut |
|------|--------|
| Inventaire `runBlocking` | Fait — KDoc sur `plugins/aps/.../DetermineBasalInvocationCaches.kt` + sites non couverts |
| Cache `calculateDaily(-24,0)` par invocation `determine_basal` | Fait |
| Autres `runBlocking` (DB, HC, …) : cache TTL ou async | Partiel — caches stats + **warmup** : COB futur / âge dernier carb / notes 4h regroupés en **un** `runBlocking` ; reste HC, boluses, site, etc. |
| Découpe continue du gros fichier | Partiel — caches d’invocation → `DetermineBasalInvocationCaches.kt` ; garde **compression rebound** → `safety/CompressionReboundGuard.kt` + tests ; suite par domaines (SMB finalize, autodrive, …) |
| `logDecisionFinal` → phases (résumé → ML/gov → TICK) | Fait |
| `updateLearning` aussi sur chemin nominal ? | **Décision A (produit)** — learning **uniquement** quand `logDecisionFinal` s’exécute (~sorties notables) ; **pas** sur le chemin nominal (plus safe, moins de bruit d’apprentissage). |
| Implémentation « learning sur nominal » | **N/A** (hors périmètre tant que décision A reste la règle). |

## Qualité tests

| Item | Statut |
|------|--------|
| `:plugins:aps:testFullDebugUnitTest` (régressions tests corrigées) | Fait |
| Tests scénario / golden moteur (branches clés) | **Partiel (cible P1)** — harnais partagé `DetermineBasalAimiScenarioTestHarness` ; `DetermineBasalAimiLogDecisionFinalScenarioTest` : `SAFETY`, `TRAJ_SAFETY`, `HARD_BRAKE`, `COMPRESSION`, `MEAL_ADVISOR`, `AUTODRIVE_V3` (+ learning 1×) ; + `DetermineBasalInvocationCachesTest`, `DetermineBasalAimiPerInvocationCacheTest` (nominal + `STALE_DATA`), exercice std/T3c, `TherapySportDetectionTest`, `CompressionReboundGuardTest`. **Reste** (harnais lourd / fin de pipeline) : `AUTODRIVE` V2 seul, `DRIFT_TERMINATOR`, `MAX_IOB`. |

## Dual-brain / Sentinel

| Item | Statut |
|------|--------|
| `LocalSentinel.computeAdvice` dans `AuditorOrchestrator.auditDecision` | **Fait** (voir `AuditorOrchestrator.kt` ~L189+) |
| Doc `DUAL_BRAIN_STATUS.md` | **Fait** — aligné sur `AuditorOrchestrator` + Sentinel ; ancien plan « à implémenter » remplacé par état factuel |

## Hygiène

| Item | Statut |
|------|--------|
| TODO `// TODO eliminate` dans `DetermineBasalAIMI2` (doublons / commentaires morts) | Fait (reste : TODO `tbrMaxMode` / `tbrMaxAutoDrive` si tracking un jour) |
| Fichiers `.bak` | **Fait** — `*.bak` dans `.gitignore` ; **3 sauvegardes `.bak` retirées du dépôt** (AIMI + Comboctl) |

---

## Fin livraison « refactor v1 » (périmètre produit)

**Inclus dans cette phase** : sécurité extraite + tests, caches d’invocation stats + warmup DB groupé, `logDecisionFinal` découpé, décision A documentée et couverte par tests moteur légers, hygiène `.bak`/doc dual-brain, pas de changement ONNX.

**Hors v1 (backlog technique / qualité)** : découpe massive `DetermineBasalAIMI2` par domaines, golden sur toutes les branches `logDecisionFinal`, refonte async générale des autres `runBlocking`, TODO pump `tbrMax*`.

---

## Phase 2 — prochaines étapes (priorisées)

| Priorité | Livrable | But |
|----------|----------|-----|
| **P1** | Golden / scénarios moteur par tag `logDecisionFinal` (2–3 tags / itération) | **Quasi fait** — tags couverts : `STALE_DATA`, `EXERCISE_LOCKOUT`, `T3C_EXERCISE_LOCKOUT`, `SAFETY`, `TRAJ_SAFETY`, `HARD_BRAKE`, `COMPRESSION`, `MEAL_ADVISOR`, `AUTODRIVE_V3`. **Backlog ciblé** : `AUTODRIVE` (V2 seul, sans V3), `DRIFT_TERMINATOR`, `MAX_IOB` (fin de `determine_basal`, IOB > plafond + `computeMealHighIobDecision`). |
| **P2** | Découpe `DetermineBasalAIMI2` — premier domaine (ex. SMB + finalize, ou autodrive) | **Amorcé** — `CompressionReboundGuard` ; prochaines extractions : SMB finalize / meal high IOB, ou bloc autodrive. |
| **P3** | `runBlocking` restants (HC pas / pas, boluses site, …) | Perf / réactivité ; chaque site = revue fraîcheur des données. |
| **P4** | TODO pump `tbrMaxMode` / `tbrMaxAutoDrive` | Uniquement si besoin produit / matériel. |

**Prérequis exercice (tests)** : `TherapySportDetectionTest` — note « sport » vs « marche » pour valider la logique therapy avant scénarios EXERCISE complets.

---

## Sorties précoces → `logDecisionFinal` (tags)

| Tag | Contexte (résumé) |
|-----|-------------------|
| `T3C_EXERCISE_LOCKOUT` | Verrou exercice T3c |
| `EXERCISE_LOCKOUT` | Verrou exercice |
| `STALE_DATA` | Données CGM > 12 min |
| `TRAJ_SAFETY` | Sécurité trajectoire |
| `SAFETY` | Pile safety / early |
| `MEAL_ADVISOR` | Meal advisor |
| `HARD_BRAKE` | Frein dur |
| `AUTODRIVE_V3` | Autodrive V3 |
| `AUTODRIVE` | Autodrive |
| `COMPRESSION` | Compression |
| `DRIFT_TERMINATOR` | Drift terminator |
| `MAX_IOB` | Plafond IOB |

Le chemin nominal « succès » complet **ne** passe **pas** par `logDecisionFinal` (comportement inchangé tant que la décision produit sur l’apprentissage basal n’est pas modifiée).

---

## ONNX

Aucun refactor listé ici ne modifie les chemins d’inférence ONNX ; garder cette discipline pour les prochains changements.
