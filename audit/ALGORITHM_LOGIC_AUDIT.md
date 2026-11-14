# Algorithm & Business Logic Audit: DrivingReport Android Application

**Application:** DrivingReport Automotive Infotainment & Cockpit Launcher (`com.antigravity.drivingreport`)  
**Audited Version:** v1.17.1 (versionCode: 31, targetSdk: 30, compileSdk: 34)  
**Audit Scope:** Mathematical formulas, telemetry algorithms, physics models, state machines, edge-case resilience, and statistical convergence.  
**Audit Date:** 2026-11-09  
**Audit Status:** STRICT READ-ONLY COMPREHENSIVE TECHNICAL AUDIT  

---

## 1. Executive Summary: Algorithm Health & Integrity

The core mathematical foundations of the DrivingReport application exhibit advanced automotive telemetry design, notably:
- High-precision decimal arithmetic using `java.math.BigDecimal` (`FuelMath.kt`).
- A multi-tier adaptive fuel estimation engine with 3-point bounds (conservative, nominal, upper).
- Non-linear 10-bar instrument cluster MID calibration with slosh filtering.
- Pure geofence-based trip state machine with 15m anti-jitter hysteresis and 25m accuracy gating.

However, deep mathematical and source-level tracing revealed **several critical algorithm vulnerabilities, circularities, and data splits**:
1. **Split-Brain Consumption Calculation:** Active trips calculate fuel consumption in two completely independent, conflicting engines: `TripDetectionEngine` divides distance by a hardcoded constant `BASE_MILEAGE_KMPL = 15.0`, while `FuelRepository` deducts continuous kinematic fuel via `FuelAIEngine.calculateLiveConsumption()`.
2. **Aggressive Recency Weighting:** `AdaptiveMileageEngine` applies `exp((i / (n-1)) * 4.0)` recency weighting. For 5 samples, the newest sample receives **54.6x the weight** of the oldest, dominating the estimate by over 63%. With $n \ge 2$, the prior baseline is instantly zeroed (`priorWeight = 0.0`), rendering the model hypersensitive to noisy single refuels.
3. **Hardcoded Fallback Contamination:** Multiple core modules (`TripRepository`, `DashboardScreen`, `LiveCurrentTripCard`, `TripDetectionEngine`) use hardcoded fallback literals `18.2` and `18.4 km/L` and `15.0 km/L`, ignoring user configuration and learned data.
4. **Float Precision Loss on Physical Distance:** `OdometerManager` and `FuelRepository` store cumulative distances and odometer readings in Android `SharedPreferences` as 32-bit `Float`. When odometer readings exceed 100,000 km, IEEE 754 float precision degrades, causing loss of sub-kilometer precision.

---

## 2. Fuel Engine Mathematical Verification

### 2.1 Arithmetic Precision (`FuelMath.kt`)
The application centralizes core arithmetic in `FuelMath`:
- `FUEL_SCALE = 3` (e.g., 9.158 L) with `RoundingMode.HALF_UP`
- `CURRENCY_SCALE = 2` (e.g., ₹1447.50) with `RoundingMode.HALF_UP`
- `DISTANCE_SCALE = 2` (e.g., 120.45 km) with `RoundingMode.HALF_UP`
- `MILEAGE_SCALE = 2` (e.g., 17.14 km/L) with `RoundingMode.HALF_UP`

#### Formulas:
1. **Liters from Currency Amount:**
   $$\text{Liters} = \left\lfloor \frac{\text{Amount}}{\text{Price per Liter}} \right\rceil_{3}$$
   *Verification:* For ₹1,000.00 at ₹109.20/L:
   $$\frac{1000}{109.20} = 9.157509... \xrightarrow{\text{HALF\_UP}} 9.158\text{ L} \quad (\text{or } 9.16\text{ L displayed})$$
   *Guards:* Safe zero return on `totalAmount <= 0.0 || pricePerLiter <= 0.0 || !isFinite()`.

2. **Fuel Consumed from Distance & Mileage:**
   $$\text{Consumed (L)} = \left\lfloor \frac{\text{Distance (km)}}{\text{Mileage (km/L)}} \right\rceil_{3}$$
   *Verification:* 120.0 km at 18.0 km/L:
   $$\frac{120.0}{18.0} = 6.66666... \xrightarrow{\text{HALF\_UP}} 6.667\text{ L}$$

3. **Continuous Partial Refill Fuel Consumed:**
   $$\text{Consumed} = \left(\text{Starting Fuel} + \sum \text{Added Fuel}\right) - \text{Ending Fuel}$$
   $$\text{Mileage} = \frac{\Delta\text{Odometer}}{\text{Consumed}}$$
   *Example Verification:*
   - Starting fuel at 50,000 km: $14.0\text{ L}$
   - Refuel 1: $9.16\text{ L}$
   - Refuel 2: $4.58\text{ L}$
   - Ending fuel at 50,200 km: $16.07\text{ L}$
   - $\text{Consumed} = (14.0 + 9.16 + 4.58) - 16.07 = 27.74 - 16.07 = 11.67\text{ L}$
   - $\text{Distance} = 50,200 - 50,000 = 200.0\text{ km}$
   - $\text{Calculated Mileage} = \frac{200.0}{11.67} = 17.1379... \xrightarrow{} 17.14\text{ km/L}$
   *Mathematical Correctness:* Verified. The formula correctly cancels out partial refuels to calculate true consumption between two anchor points.

---

### 2.2 Continuous Balance & 3-Point Bounding (`FuelStateEstimator.kt`)
The estimator maintains 3 concurrent fuel bounds to represent physical volume uncertainty:
1. **Nominal Fuel ($F_{\text{nom}}$):** The expected balance.
2. **Conservative Fuel ($F_{\text{cons}}$):** Lower bound fail-safe for DTE (Distance-To-Empty).
3. **Upper Fuel ($F_{\text{upper}}$):** Upper volumetric bound.

#### Uncertainty Spread Formula:
$$\text{Uncertainty} = \max\left(0.5, \text{BaseVolume} \times P_{\text{conf}}\right)$$
Where $P_{\text{conf}}$ is:
- `CONFIRMED`: $\pm 3\%$
- `HIGH`: $\pm 6\%$
- `MEDIUM`: $\pm 12\%$
- `PROVISIONAL`: $\pm 22\%$
- `LOW`: $\pm 35\%$

#### Tank Capacity and Overfill Expansion:
- Physical tank limit: $C_{\text{design}} \in [10.0, 200.0]\text{ L}$
- Overfill ceiling:
  $$C_{\text{max}} = \min\left(C_{\text{design}} \times 1.35, F_{\text{nom}} + F_{\text{added}}\right)$$
  *Analysis:* Allows filler neck overfill up to 35% above design capacity without throwing an exception or clamping valid fuel away.
- Clamping:
  $$F_{\text{nom}} = \operatorname{clamp}\left(F_{\text{nom}} - F_{\text{consumed}}, 0.0, C_{\text{max}}\right)$$
  *Guards:* Tank balance is strictly prevented from becoming negative.

#### Distance-to-Empty (DTE) Calculations:
$$\text{Range}_{\text{nom}} = \left\lfloor F_{\text{nom}} \times M_{\text{eff}} \right\rceil_{2}$$
$$\text{Range}_{\text{min}} = \left\lfloor F_{\text{cons}} \times M_{\text{eff}} \times 0.90 \right\rceil_{2}$$
$$\text{Range}_{\text{max}} = \left\lfloor F_{\text{upper}} \times M_{\text{eff}} \times 1.05 \right\rceil_{2}$$
Where $M_{\text{eff}} = \operatorname{clamp}(M_{\text{learned}}, 5.0, 45.0)\text{ km/L}$.
*Safety Feature:* In fuel reserve ($F_{\text{nom}} < 7.0\text{ L}$ or $F_{\text{nom}} / C_{\text{design}} < 0.15$), DTE switches to a conservative 85th percentile multiplier ($M_{\text{eff}} \times 0.85$) so drivers are not stranded by optimistic predictions.

---

### 2.3 Statistical Learning & Outlier Rejection (`AdaptiveMileageEngine.kt`)

#### 1. Interval Qualification Gate:
An interval between two anchors is only processed if:
- $\Delta\text{Odometer} \ge 15.0\text{ km}$ (`MIN_INTERVAL_KM`)
- $F_{\text{consumed}} \ge 1.0\text{ L}$ (`MIN_INTERVAL_LITERS`)
- $\text{Calculated Mileage} \in [4.0, 45.0]\text{ km/L}$ (`MIN_PLAUSIBLE` to `MAX_PLAUSIBLE`)

#### 2. Interquartile Range (IQR) Outlier Rejection:
For $N \ge 4$ samples:
- Sort ascending: $S = [s_0, s_1, \dots, s_{N-1}]$
- First quartile: $Q_1 = S[\lfloor N / 4 \rfloor]$
- Third quartile: $Q_3 = S[\lfloor 3N / 4 \rfloor]$
- $\text{IQR} = Q_3 - Q_1$
- Valid interval: $[Q_1 - 1.5 \times \text{IQR}, Q_3 + 1.5 \times \text{IQR}]$
*Audit Finding:* Uses integer division for quartiles. For small $N \in [4, 7]$, integer division produces coarse approximations (e.g., for $N=4$: $Q_1=S[1], Q_3=S[3]$). While mathematically crude, it is array-safe and filters gross outliers without crashing.

#### 3. Exponential Recency Weighting:
For $N$ clean samples with index $i \in [0, N-1]$:
$$k_i = \begin{cases} \left(\frac{i}{N-1}\right) \times 4.0 & N > 1 \\ 0.0 & N = 1 \end{cases}$$
$$w_i = \exp(k_i)$$
$$\bar{M}_{\text{adapt}} = \frac{\sum_{i=0}^{N-1} s_i \cdot w_i}{\sum_{i=0}^{N-1} w_i}$$

#### Numerical Example of Recency Skew:
Suppose a car records 5 valid refuel intervals:
- $s_0 = 15.0\text{ km/L} \implies k_0 = 0.0 \implies w_0 = 1.00$
- $s_1 = 15.5\text{ km/L} \implies k_1 = 1.0 \implies w_1 = 2.72$
- $s_2 = 16.0\text{ km/L} \implies k_2 = 2.0 \implies w_2 = 7.39$
- $s_3 = 16.2\text{ km/L} \implies k_3 = 3.0 \implies w_3 = 20.09$
- $s_4 = 21.0\text{ km/L} \implies k_4 = 4.0 \implies w_4 = 54.60$
$$\sum w_i = 85.80$$
$$\bar{M}_{\text{adapt}} = \frac{15.0(1) + 15.5(2.72) + 16.0(7.39) + 16.2(20.09) + 21.0(54.60)}{85.80} = \frac{15.0 + 42.16 + 118.24 + 325.46 + 1146.60}{85.80} = \frac{1647.46}{85.80} = 19.20\text{ km/L}$$
*Simple Average:* $(15.0 + 15.5 + 16.0 + 16.2 + 21.0) / 5 = 16.74\text{ km/L}$.  
*Impact:* The single last outlier ($21.0$) drives the weighted mean up by **+2.46 km/L** (a 14.7% shift) because the last sample holds **63.6% of the entire weighting power**.
*Recommendation:* Dampen the maximum recency exponent from $4.0$ to $2.0$ ($\exp(2.0) \approx 7.4$ max ratio) to prevent a single high-speed highway trip from distorting the overall learned city/highway composite mileage.

#### 4. Prior Blending & Transition:
$$W_{\text{prior}} = \begin{cases} 0.0 & N \ge 2 \\ \max\left(0.05, \frac{1}{1 + 0.5N}\right) & N < 2 \end{cases}$$
When $N=0$: $W_{\text{prior}} = 1.0 \implies M = M_{\text{baseline}}$  
When $N=1$: $W_{\text{prior}} = 1 / 1.5 = 0.667 \implies M = 0.333 \cdot s_0 + 0.667 \cdot M_{\text{baseline}}$  
When $N=2$: $W_{\text{prior}} = 0.0 \implies M = \bar{M}_{\text{adapt}}$ (100% data driven)  
*State Machine Progression:*
- `INITIAL_BASELINE` ($N=0$)
- `LEARNING` ($N=1$)
- `LEARNED` ($N \ge 2$ and `measuredMileage != null`)

---

### 2.4 Instrument Cluster 10-Point MID Calibration (`FuelGaugeCalibrationEngine.kt`)
Automotive fuel tanks are non-rectangular (trapezoidal, saddle-shaped, indented for exhaust/suspension), so a linear float produces severe gauge inaccuracies.

#### 1. Volumetric Curve Profile:
The calibration engine initializes with standard automotive geometric tank fractions:
$$\{0: 0.00, 1: 0.08, 2: 0.17, 3: 0.27, 4: 0.38, 5: 0.49, 6: 0.60, 7: 0.71, 8: 0.82, 9: 0.91, 10: 1.00\}$$
- Reserve threshold: 0 bar = $0\%$, 2 bars = $17\%$ ($8.16\text{ L}$ on $48\text{ L}$ tank).
- Half-tank: 5 bars = $49\%$ ($23.52\text{ L}$).

#### 2. Adaptive Calibration Learning:
When a ground-truth refill or baseline occurs at observed bar $B$:
$$f_{\text{new}}[B] = f_{\text{curr}}[B] \times (1 - \alpha) + \left(\frac{V_{\text{confirmed}}}{C_{\text{design}}}\right) \times \alpha$$
Where learning rate $\alpha \in [0.15, 0.40]$.
- Post-learning, `enforceMonotonicity()` ensures:
  $$f[0] = 0.0, \quad f[i] \ge f[i-1] + 0.03, \quad f[10] \ge 1.0$$
*Verdict:* Mathematically sound. Monotonicity enforcement prevents inverted gauge readings where higher bars represent lower volumes.

#### 3. Slosh & Slope Median Filter:
Maintains a 60-second rolling window of $(t, \text{bar})$ readings:
$$\text{FilteredBar} = \operatorname{median}\left(R_{\text{recent}}\right)$$
*Analysis:* Rejects transient slosh (hard braking/acceleration) and road slopes up to 60 seconds without false gauge adjustments.

---

### 2.5 Missed Entry Detection Intelligence (`MissedEntryIntelligence.kt`)
Detects unlogged refuels when the physical instrument cluster reading contradicts calculated consumption:
$$\Delta_{\text{nominal}} = V_{\text{observed}} - F_{\text{nom}}$$
- Detection condition: $\Delta_{\text{nominal}} \ge 4.0\text{ L}$ (`thresholdLiters`).
- Derived plausible bounds:
  $$V_{\text{lower}} = \frac{V_{\text{bar}-1} + V_{\text{bar}}}{2}, \quad V_{\text{upper}} = \frac{V_{\text{bar}+1} + V_{\text{bar}}}{2}$$
  $$\text{PlausibleMin} = \max\left(1.0, V_{\text{lower}} - F_{\text{upper}}\right)$$
  $$\text{PlausibleMax} = \min\left(C_{\text{max}} - F_{\text{cons}}, V_{\text{upper}} - F_{\text{cons}}\right)$$
- If detected, an immutable `INFERRED_REFUEL` ledger event is recorded with `PROVISIONAL` confidence and ₹0.00 cost (never fabricating fictitious financial receipts).
- Confirmation: When the user confirms, `createConfirmedResolutionEvent` links to the original provisional event via `parentId`, maintaining a non-destructive audit log.

---

### 2.6 Real-Time Kinematic Consumption Model (`FuelAIEngine.kt`)

#### 1. Engine Idle Model:
When speed $v < 1.5\text{ km/h}$ or $\Delta d < 1.0\text{ m}$:
$$F_{\text{idle}} = \left(\frac{0.75\text{ L/h}}{3600\text{ s}}\right) \times \Delta t_{\text{sec}} = 0.0002083 \times \Delta t_{\text{sec}}\text{ L}$$
- Clamped to $\max 0.05\text{ L}$ per tick to prevent GPS freeze spikes.
- Live instant mileage during standstill: strictly $0.0\text{ km/L}$.

#### 2. Acceleration Penalty Model:
$$\text{accel} = \frac{v_{\text{curr}} - v_{\text{prev}}}{\Delta t} \in [-8.0, +8.0]\text{ m/s}^2$$
$$M_{\text{accel}} = \begin{cases}
0.45 & \text{accel} > 2.0\text{ m/s}^2\text{ (launch/hard acceleration)} \\
0.55 & \text{accel} \in (1.2, 2.0] \\
0.75 + (1.2 - \text{accel}) \times \frac{0.25}{0.8} & \text{accel} \in (0.4, 1.2] \\
1.00 & \text{accel} \in [-0.3, 0.4]\text{ (steady cruise)} \\
1.10 + (-0.3 - \text{accel}) \times \frac{2.0}{0.3} & \text{accel} \in [-0.6, -0.3)\text{ (coasting)} \\
3.50 & \text{accel} \in [-1.5, -0.6)\text{ (fuel cut-off deceleration)} \\
4.50 & \text{accel} < -1.5\text{ m/s}^2\text{ (engine braking)}
\end{cases}$$

#### 3. BSFC Thermal Efficiency Speed Factor:
$$F_{\text{speed}}(v) = \begin{cases}
0.50 & v \le 5\text{ km/h} \\
0.55 + \frac{v - 5}{15} \times 0.25 & v \in (5, 20]\text{ km/h} \\
0.80 + \frac{v - 20}{25} \times 0.25 & v \in (20, 45]\text{ km/h} \\
1.05 + \frac{v - 45}{17} \times 0.10 & v \in (45, 62]\text{ km/h (peak efficiency)} \\
1.15 - \frac{v - 62}{18} \times 0.10 & v \in (62, 80]\text{ km/h} \\
1.05 - \frac{v - 80}{20} \times 0.17 & v \in (80, 100]\text{ km/h} \\
0.88 - \frac{v - 100}{20} \times 0.18 & v \in (100, 120]\text{ km/h} \\
\max\left(0.50, 0.70 - \frac{v - 120}{40} \times 0.10\right) & v > 120\text{ km/h}
\end{cases}$$

#### 4. Exponential Moving Average (EMA) Smoothing:
$$M_{\text{live}} = (1 - 0.20) \times M_{\text{live, prev}} + 0.20 \times M_{\text{instant}}$$
$$\Delta F_{\text{live}} = \min\left(F_{\text{remaining}}, \operatorname{clamp}\left(\frac{\Delta d}{M_{\text{live}}}, 0.0, 0.30\right)\right)$$
*Evaluation:* Excellent thermodynamic and kinematic model. Accurately reflects that ICE engines achieve peak efficiency around 60–65 km/h, suffer fuel enrichment on hard acceleration, cut fuel on closed-throttle overrun, and burn ~0.75 L/h at idle.

---

## 3. Trip & GPS Engine Mathematical Verification

### 3.1 Geodesic Distance & Speed Resolution (`TripDetectionEngine.kt`)
1. **Speed Resolution:**
   - Doppler sensor speed: $v_{\text{sensor}} = \text{location.speed} \times 3.6\text{ km/h}$
   - Geodesic computed speed:
     $$v_{\text{computed}} = \left(\frac{\operatorname{distanceTo}(\text{prev}, \text{curr})}{\Delta t}\right) \times 3.6\text{ km/h}$$
   - Priority: If $v_{\text{sensor}} > 0.3\text{ m/s}$ (1.08 km/h), Doppler speed is preferred. Fallback to $v_{\text{computed}}$ if within $[3.0, 220.0]\text{ km/h}$. Otherwise 0 km/h.

2. **Distance Accumulation Anti-Drift Gate:**
   Distance is ONLY added if:
   $$\left(v_{\text{effective}} \ge 2.5\text{ km/h} \land \Delta d \ge 0.8\text{ m}\right) \lor \left(\Delta d \ge 4.0\text{ m} \land v_{\text{implied}} \in [3.0, 220.0]\text{ km/h}\right)$$
   And $v_{\text{implied}} < 220.0\text{ km/h}$ (rejects GPS teleportation/tunnel jumps).
   *Analysis:* Effectively prevents GPS jitter while parked from accumulating phantom distance.

3. **Average Speed Calculation:**
   $$\bar{v} = \begin{cases}
   \frac{\text{TotalDistance (km)}}{\text{DrivingDuration (h)}} & \text{DrivingHours} > 0.002\text{ h} (7.2\text{ s}) \\
   \frac{\sum v_i}{N_{\text{moving}}} & N_{\text{moving}} > 0 \\
   0.0 & \text{otherwise}
   \end{cases}$$
   *Verdict:* Mathematically exact harmonic-free average speed based on physical distance over actual driving time (stationary time excluded).

4. **Driver Score Algorithm:**
   $$\text{Score} = \operatorname{clamp}\left(100 - (3 \times N_{\text{harsh\_brakes}} + 2 \times N_{\text{rapid\_accels}} + 2 \times N_{\text{overspeed}}), 50, 100\right)$$
   Where:
   - Harsh brake: $\text{accel} < -3.2\text{ m/s}^2$
   - Rapid accel: $\text{accel} > +3.0\text{ m/s}^2$
   - Overspeed: $v > 80.0\text{ km/h}$

---

### 3.2 Geofence Boundary Transition Engine (`GeofenceTripLifecycleEngine.kt`)
The geofence lifecycle engine operates on a strict finite state machine:
```
WAITING_FOR_GEOFENCE
       │ (inside enabled geofence, accuracy <= 25m)
       ▼
INSIDE_GEOFENCE
       │ (dist > radius, accuracy <= 25m)
       ▼
DEPARTURE_PENDING ──(turned back inside radius)──► INSIDE_GEOFENCE
       │ (dist >= radius + 15m OR 2 consecutive outward fixes with speed >= 3 km/h)
       ▼
TRIP_ACTIVE (starts trip & timer)
       │ (dist <= destination radius - 5m [immediate] OR 2 consecutive fixes inside)
       ▼
TRIP_COMPLETED (ends trip & timer)
```

#### Verification of Guarantees:
1. **Departure Confirmation:** Requires crossing radius PLUS 15m hysteresis (`DEBOUNCE_HYSTERESIS_METERS = 15.0f`) OR sustained motion outward with $v \ge 3.0\text{ km/h}$.
2. **Rolling Pre-Trip Buffer:** Maintains up to 60 points / 5 minutes of pre-trip positions inside the geofence. On confirmed departure, extracts points from the initial movement anchor to capture the departure trajectory and internal distance traveled before crossing the boundary.
3. **Arrival Confirmation:** Penetration $> 5\text{ m}$ inside the destination radius confirms arrival immediately. Penetration at the edge boundary requires 2 consecutive fixes to prevent false arrivals on peripheral fly-bys.
4. **Accuracy Gate:** GPS fixes with accuracy $> 25\text{ m}$ are rejected from triggering transitions, preventing multipath reflections from starting or ending trips.

---

## 4. Critical Logical & Mathematical Bugs Discovered

| ID | Module | Exact Location | Mathematical / Logic Flaw | Severity |
|---|---|---|---|---|
| **LOGIC-001** | `TripDetectionEngine` | `TripDetectionEngine.kt:58, 371, 414` | Hardcoded `BASE_MILEAGE_KMPL = 15.0` used for all trip fuel consumption and trip cost calculations. Completely ignores vehicle baseline and learned adaptive mileage. | **CRITICAL** |
| **LOGIC-002** | `TripRepository` | `TripRepository.kt:145, 152, 289, 320` | Hardcoded fallback constants `18.2` and `18.4 km/L` used when trip mileage is 0 or uninitialized. Overwrites actual vehicle configuration. | **HIGH** |
| **LOGIC-003** | `AdaptiveMileageEngine` | `AdaptiveMileageEngine.kt:210-218` | Recency exponent factor of $4.0$ creates a $54.6:1$ weight skew favoring the single latest sample. When $N \ge 2$, `priorWeight = 0.0` immediately discards prior baseline, making mileage fluctuate wildly on single atypical drives. | **HIGH** |
| **LOGIC-004** | `DrivingTrackingService` | `DrivingTrackingService.kt:415, 420, 428` | Simultaneous registration of FusedLocationProviderClient AND LocationManager (GPS + Network) without arbitration. Feeds interleaved fixes into `TripDetectionEngine` and `GeofenceEngine`, risking distance inflation. | **HIGH** |
| **LOGIC-005** | `DashboardScreen` | `DashboardScreen.kt:103, 107` | Lifetime fuel and mileage calculation defaults to hardcoded `18.2 km/L` if trip calculated mileage is $\le 0$. Contaminates all-time dashboard statistics. | **MEDIUM** |
| **LOGIC-006** | `OdometerManager` | `OdometerManager.kt:59-60` | Odometer baseline and lifetime distance are cast to 32-bit `Float` before saving in SharedPreferences (`putFloat`). At $> 100,000\text{ km}$, IEEE 754 float precision degrades below 0.01 km. | **MEDIUM** |
| **LOGIC-007** | `FuelAIEngine` | `FuelAIEngine.kt:142` | In historical refill pair synthesis: `tankKmpl = deltaKm / curr.liters`. Assumes `curr.liters` was consumed over `deltaKm`. This is only valid for **full tank-to-tank fills**. For partial fills, this formula produces completely invalid mileage figures. (Mitigated because `FuelRepository` now routes partial refills through `AdaptiveMileageEngine.processAnchorInterval`, but legacy AI engine still has this flaw). | **MEDIUM** |
| **LOGIC-008** | `MissedEntryIntelligence` | `MissedEntryIntelligence.kt:71-79` | When computing `plausibleNominal`, uses `deltaNominal.coerceIn(plausibleMin, max(plausibleMin, plausibleMax))`. If `plausibleMin > plausibleMax` (which can occur when uncertainty spread is wide), `coerceIn` can throw `IllegalArgumentException: Cannot coerce value to an empty range`. | **HIGH** |

---

## 5. Mathematical Summary & Edge Case Verification

```
┌─────────────────────────────────────────────────────────────┬──────────┬───────────┐
│ Edge Case / Calculation                                     │ Verified │ Risk Level│
├─────────────────────────────────────────────────────────────┼──────────┼───────────┤
│ Division by zero on zero distance or zero fuel             │ YES      │ SAFE      │
│ Negative remaining fuel clamped at 0.0 L                   │ YES      │ SAFE      │
│ Tank overfill beyond OEM design capacity (up to 1.35x)     │ YES      │ SAFE      │
│ IEEE 754 floating point drift during 1 Hz GPS ticks        │ YES      │ SAFE      │
│ GPS teleportation / tunnel speed spikes (> 220 km/h)        │ YES      │ SAFE      │
│ Stationary engine idle fuel burn rate (0.75 L/h)           │ YES      │ SAFE      │
│ Multi-day trip timer formatting across midnight             │ YES      │ SAFE      │
│ Geofence departure hysteresis buffer (15m buffer)           │ YES      │ SAFE      │
│ Geofence GPS accuracy gate (reject fixes > 25m)            │ YES      │ SAFE      │
│ SharedPreferences Float truncation on large odometer values │ NO       │ AT RISK   │
│ Cross-module mileage consistency (15.0 vs 18.0 vs 18.2)     │ NO       │ FLAWED    │
│ Missed refuel range bounds when plausibleMin > plausibleMax│ NO       │ CRASH RISK│
└─────────────────────────────────────────────────────────────┴──────────┴───────────┘
```
