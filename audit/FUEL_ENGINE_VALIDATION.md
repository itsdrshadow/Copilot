# Fuel Intelligence & Gauge Engine Validation (v1.18.0)

**Document:** `audit/FUEL_ENGINE_VALIDATION.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)

---

## 1. Non-Linear 10-Point Calibration Model

Automotive fuel tanks are irregular geometric containers (narrow filler necks, taper at bottom, wheel arch contours). Consequently, 1 bar does not equal 10% volume.
DrivingReport models tank volume across an 11-point calibration vector $P = [v_0, v_1, \dots, v_{10}]$:

| Bar Index | Nominal Fraction of Capacity | Default Litres (48L Tank) | Cluster Description |
|---|---|---|---|
| **0** | `0.00` | 0.0 L | Empty / Below Reserve Threshold |
| **1** | `0.08` | 3.84 L | Reserve Fuel Lamp Active |
| **2** | `0.17` | 8.16 L | Low Fuel Warning |
| **3** | `0.27` | 12.96 L | Lower Quarter Tank |
| **4** | `0.38` | 18.24 L | Approaching Half Tank |
| **5** | `0.49` | 23.52 L | Mid-Tank (Approx 50%) |
| **6** | `0.60` | 28.80 L | Upper Quarter Tank |
| **7** | `0.71` | 34.08 L | Three-Quarter Tank |
| **8** | `0.82` | 39.36 L | High Tank |
| **9** | `0.91` | 43.68 L | Near Full Tank |
| **10** | `1.00` | 48.00 L | Full Tank (Filler Neck Autocut) |

### 1.1 Mathematical Monotonicity Guarantee
- **Invariant:** $v_i < v_{i+1}$ for all $0 \le i < 10$.
- **Bidirectional Pass:**
  - Forward pass enforces minimum step size ($\ge 0.02$).
  - Full tank anchor ($v_{10}$) is clamped to $\ge 1.0$.
  - Backward pass prevents upper bars from overflowing physical headroom (capped at 1.15).

---

## 2. Dynamic Fuel Consumption & Range Estimation

- **Consumption Formula:**
  $$\Delta V = \frac{\Delta d}{\text{Mileage}_{\text{effective}}}$$
  where $\text{Mileage}_{\text{effective}}$ utilizes the Bayesian learned empirical mileage once sample count $\ge 2$ and confidence score $\ge 70$, and smoothly blends with the user's configured baseline mileage otherwise.
- **Estimated Range:**
  $$\text{Range (km)} = \text{Remaining Litres} \times \text{Mileage}_{\text{effective}}$$

---

## 3. Missed Refill Discrepancy Intelligence

- **Condition:** Triggered when cluster gauge observation $V_{\text{observed}}$ exceeds current estimated conservative fuel balance by $\ge \Delta V_{\text{threshold}}$ (default 3.0 L).
- **Zero Fabrication Principle:**
  - Price per litre is set strictly to `0.0`.
  - Total financial amount is set strictly to `0.0`.
  - Station name and vendor are left blank.
  - Event is marked as `PROVISIONAL` and presents a prompt to the driver: *"Did you refuel ~X Litres recently?"*
  - Only when the driver confirms does it transition to `CONFIRMED` with user-entered financial receipts.
