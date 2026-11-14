# Product Improvement Register (UI/UX Prioritization)

**Application:** DrivingReport Automotive Infotainment & Cockpit Launcher  
**Audited Release:** `v1.18.0` (Build 33)  
**Package:** `com.antigravity.drivingreport`  

---

## 1. Prioritization Framework

- **P0 (Critical HMI & Glanceability):** Directly impacts driver safety, readability within 0.5-second gaze window, or tactile touch responsiveness.
- **P1 (Visual Unification & Ergonomics):** Harmonizes design tokens, eliminates hardcoded ad-hoc styles, refines animations, and optimizes responsive scaling.
- **P2 (Micro-Interactions & Aesthetic Polish):** Subtle motion enhancements, smooth state transitions, and polished empty states.

---

## 2. Prioritized Improvements Matrix

| ID | Priority | Subsystem | Improvement Target | Description & Rationale | Implementation Status |
|---|---|---|---|---|---|
| **P0-01** | **P0** | Design System | Tactile Press-Scale Feedback (`Modifier.bounceClick()`) | Custom clickable containers (Dock buttons, "+ ADD FUEL" button, settings rail items, numeric keypad) should compress subtly (`scale = 0.96f`) on touch with instant spring release, providing physical button tactility for drivers. | **IMPLEMENTED** |
| **P0-02** | **P0** | Dashboard UI | Monospace Tabular Figures on All Dynamic Telemetry | Ensure all dynamic numerical readouts (Speed, Odometer, Distance, Elapsed Time, Fuel Litres, DTE Range) use tabular monospace figures with `includeFontPadding = false` to eliminate micro-jitter during 1Hz telemetry updates. | **IMPLEMENTED** |
| **P0-03** | **P0** | Dashboard UI | Glanceable Status Hierarchy on Live Trip Card | Strengthen visual distinction between active moving drive (`MOVING` in bright emerald) and stopped intervals (`STATIONARY` in amber), ensuring 10-second green/red flashing badges are prominent without obscuring elapsed timer. | **IMPLEMENTED** |
| **P0-04** | **P0** | Speedometer | High-Contrast Speed Limit Badge & Over-speed Halo | Enhance European/Indian speed limit circular badge with glowing red alert halo when current vehicle speed exceeds statutory speed limit by $> 5\%$. | **IMPLEMENTED** |
| **P1-01** | **P1** | Design Tokens | Color Token Consolidation (`Theme.kt`) | Replace ad-hoc hardcoded hex values (`Color(0xFF070E1A)`, `Color(0xFF142033)`) with semantic theme tokens (`Cockpit_DarkBg`, `Cockpit_CardSurfaceElevated`, `Cockpit_CardBorderGlow`). | **IMPLEMENTED** |
| **P1-02** | **P1** | Fuel System | Non-Linear MID LED Cluster Gradient Polish | Enhance the 10-bar fuel gauge with high-contrast LED segment styling, segmented dividers, and smooth color grading from reserve red (bars 1-2) to amber (bars 3-4) to vivid eco-teal (bars 5-10). | **IMPLEMENTED** |
| **P1-03** | **P1** | Bottom Dock | Ergonomic Touch Dock Height & Active Glow Pill | Ensure bottom dock buttons have $\ge 48$dp touch targets with a distinct illuminated blue capsule indicator on the currently selected tab. | **IMPLEMENTED** |
| **P1-04** | **P1** | Trip History | Replay Playback Scrubber & Speed Chip Responsiveness | Make route replay seek bar and speed multiplier chips (1x, 2x, 5x, 10x, 20x) tactile with immediate visual response during interactive scrubbing. | **IMPLEMENTED** |
| **P1-05** | **P1** | Settings Rail | Samsung One UI Vertical Rail Animation | Animate tab indicator transitions smoothly between General, Vehicle, Tracking, Geofences, Maps, Speed, Data, and About tabs. | **IMPLEMENTED** |
| **P2-01** | **P2** | Empty States | Cockpit & History Polished Empty Placeholders | Provide styled automotive empty state illustrations with informative guidance when no trips or fuel entries exist yet. | **IMPLEMENTED** |
| **P2-02** | **P2** | Night Mode Maps | Dark Cockpit Map Styling Alignment | Ensure offline OpenStreetMap layer blends into the cockpit border with deep dark cartography colors. | **IMPLEMENTED** |
| **P2-03** | **P2** | Odometer Card | Adaptive Font Scaling at 6-Digit Readings | Ensure odometer numbers scale down seamlessly from 24sp to 20sp when lifetime distance exceeds 100,000 km, preventing any clipping. | **IMPLEMENTED** |
