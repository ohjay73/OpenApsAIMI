# AIMI refactor — check-list d’avancement

Dernière mise à jour : warmup persistance COB/notes en un `runBlocking` ; test STALE → `updateLearning` 1× ; périmètre « fin v1 » ci-dessous.

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
| Découpe continue du gros fichier | Partiel — caches d’invocation extraits vers `DetermineBasalInvocationCaches.kt` ; suite possible par domaines (SMB, autodrive, …) |
| `logDecisionFinal` → phases (résumé → ML/gov → TICK) | Fait |
| `updateLearning` aussi sur chemin nominal ? | **Décision A (produit)** — learning **uniquement** quand `logDecisionFinal` s’exécute (~sorties notables) ; **pas** sur le chemin nominal (plus safe, moins de bruit d’apprentissage). |
| Implémentation « learning sur nominal » | **N/A** (hors périmètre tant que décision A reste la règle). |

## Qualité tests

| Item | Statut |
|------|--------|
| `:plugins:aps:testFullDebugUnitTest` (régressions tests corrigées) | Fait |
| Tests scénario / golden moteur (branches clés) | Partiel — `DetermineBasalInvocationCachesTest` ; `DetermineBasalAimiPerInvocationCacheTest` : caches + **décision A** (nominal **0×** `updateLearning`, STALE **1×** via `logDecisionFinal`) ; golden exhaustif = backlog post-v1 |

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
