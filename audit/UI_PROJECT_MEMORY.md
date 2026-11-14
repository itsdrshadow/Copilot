# DrivingReport UI/UX Engineering & Project Memory

**Application:** DrivingReport Automotive Infotainment & Cockpit Launcher  
**Package:** `com.antigravity.drivingreport`  
**Audited Release:** `v1.18.0` (Build 33)  
**Form Factors:** 1280×800 Automotive Landscape Infotainment & High-DPI Mobile Portrait  
**Architecture:** Jetpack Compose Material 3, Clean Layered Architecture, Reactive StateFlow, Room SQLite, Android Foreground Services  

---

## 1. Executive Summary & Design Vision

DrivingReport is an autonomous vehicle tracking launcher and digital instrument cockpit engineered specifically for in-car infotainment head units and high-resolution mobile devices. The visual identity follows the **Samsung One UI Dark Automotive Cockpit** philosophy:
- **Maximum Driver Glanceability:** High-contrast primary metrics visible at an arm's length (60–80 cm) within a 0.5-second driver gaze window.
- **Deep Obsidian & Celestial Glow:** OLED-optimized pure dark background (`#040811` / `#060A14`) with cyan (`#00E5FF`), sky blue (`#38BDF8`), royal purple (`#A855F7`), and eco-emerald (`#10B981`) accents.
- **Tactile Touch Feedback:** Every interactive element delivers visual spring-scale compression (`0.96f` on press) and ambient border glow.
- **Zero Distraction & Monotonic State:** Telemetry numbers never jitter or jump abruptly; transitions are smoothly interpolated via low-pass filters and Compose spring physics.
- **Interconnected System Invariance:** Visual state directly reflects physical reality (geofence departure, motion, idling, refueling, geofence arrival) without synthetic mock data or brittle UI-only state.

---

## 2. Design Tokens & Visual Hierarchy

### 2.1 Color Palette System

| Token Name | Hex Code | Semantic Role | Automotive Contrast Ratio |
|---|---|---|---|
| `Cockpit_DarkBg` | `#060A14` | Cockpit base backdrop, immersive full-screen background | WCAG AAA (>15:1 against White) |
| `Cockpit_DarkBgSecondary` | `#0A1020` | Secondary surface background, dialog scrims | AAA |
| `Cockpit_CardSurface` | `#0E172C` | Default card container surface | AAA |
| `Cockpit_CardSurfaceElevated` | `#131F3B` | Inner tile containers, metric capsules, input fields | AA (>7:1) |
| `Cockpit_CardBorder` | `#1F325A` | Standard subtle container boundary (1.2dp) | N/A |
| `Cockpit_CardBorderGlow` | `#2B467E` | Active container border highlight | N/A |
| `Cockpit_Cyan` | `#00E5FF` | Primary electric accent, active trip highlights, GPS locks | AAA on dark |
| `Cockpit_BlueAccent` | `#2979FF` | Primary action buttons, active navigation rail selection | AA |
| `Cockpit_BlueGlow` | `#448AFF` | Outer glow sweeps, speedometer progression | AA |
| `Cockpit_GreenEco` | `#00E676` / `#10B981` | Safe driving, eco-efficiency, trip started flash indicator | AAA on dark |
| `Cockpit_AmberWarning` | `#FFAB00` / `#F59E0B` | Speed warning approach, low fuel advisory, idle duration | AAA on dark |
| `Cockpit_RedAlert` | `#FF3D00` / `#EF4444` | Speed limit violation, reserve fuel alert, trip completion flash | AAA on dark |
| `Cockpit_TextPrimary` | `#FFFFFF` | Primary numbers, card headers, critical telemetry readouts | AAA (>15:1) |
| `Cockpit_TextSecondary` | `#8FA7D1` | Units (km, km/h, L), metric subtitles, timestamp labels | AA (>4.5:1) |
| `Cockpit_TextTertiary` | `#5A7099` | Inactive labels, subtle divider lines, secondary units | AA (>3:1) |

### 2.2 Typography Scale (Material 3 Adaptive Monospace)

All numerical readouts use tabular, monospace font figures with `PlatformTextStyle(includeFontPadding = false)` to prevent vertical jitter during dynamic value updates:

- **Display Giant (Speedometer / Hero Value):** 46–58sp, Font-Weight 900 (Black), Monospace figures.
- **Display Medium (Permanent Odometer / Hero Fuel):** 28–34sp, Font-Weight 900 (Black).
- **Title Large (Card Primary Metric):** 20–22sp, Font-Weight 800 (Bold).
- **Title Medium (Card Subheaders / Chip Labels):** 14–16sp, Font-Weight 700 (Semi-Bold).
- **Body Medium (Descriptions / Secondary Readings):** 12–13sp, Font-Weight 500 (Medium).
- **Label Small (Unit Tags, Monospace Timers):** 9–10.5sp, Font-Weight 700 (Bold), Letter-Spacing 0.8sp.

### 2.3 Surface Geometry & Spacing Grid

- **Base Unit:** 4dp soft grid.
- **Card Outer Corner Radius:** 24dp (Automotive Landscape 1280×800) / 18dp (Mobile / Very Compact).
- **Inner Tile Corner Radius:** 12–14dp.
- **Badge / Pill Corner Radius:** 8–10dp.
- **Standard Card Padding:** 12–14dp outer, 8–10dp inner.
- **Card Border Width:** 1.2–1.5dp with 25–45% alpha glow border.
- **Minimum Touch Target:** $\ge 48\times 48\text{dp}$ across all clickable controls.

---

## 3. Screen & Component Architecture (Dedicated Feature Structure)

```
                    ┌────────────────────────────────────────┐
                    │              MainActivity              │
                    │   Immersive Sticky Mode (Hides Bars)   │
                    └───────────────────┬────────────────────┘
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             ▼                          ▼                          ▼
   ┌───────────────────┐      ┌───────────────────┐      ┌───────────────────┐
   │  DashboardScreen  │      │   TripHistory     │      │   FuelHistory     │
   │  (feature/        │      │   (feature/trips/ │      │   (feature/fuel/  │
   │   dashboard/)     │      │   TripHistory     │      │   FuelHistory     │
   │   (SelectedTab 0) │      │   Screen.kt)      │      │   Screen.kt)      │
   └─────────┬─────────┘      └───────────────────┘      └───────────────────┘
             │
    ┌────────┴────────┬───────────────────┬───────────────────┐
    ▼                 ▼                   ▼                   ▼
┌──────────────┐ ┌───────────────┐ ┌─────────────┐ ┌────────────────────┐
│ OdometerCard │ │ CurrentTrip   │ │ FuelTankCard│ │ SpeedometerCluster │
│ (Lifetime    │ │ Card (Elapsed │ │ (10-Bar MID │ │ 240° Calibrated    │
│  & Delta)    │ │  & Carousel)  │ │  & Add Fuel)│ │ Dial & Eco Trends  │
└──────────────┘ └───────────────┘ └─────────────┘ └────────────────────┘
```

### 3.1 Dedicated Feature File Organization
All source files are strictly structured under domain-specific feature packages:
- **`feature/dashboard/`**:
  - `DashboardScreen.kt`: Studio-Grade Automotive Cockpit Dashboard (Primary Home View).
  - `SpeedometerCluster.kt`: 240° precision speed dial, gear, RPM, speed trends graph, stopping distance.
  - `FuelTankCard.kt`: 10-bar LED MID visualizer, tank liters, DTE range, and calibrated MID gauge dialog.
  - `CurrentTripCard.kt`: Live dynamic trip duration timer, rotating metrics carousel (distance, speed, economy, braking).
  - `OdometerCard.kt`: Permanent lifetime odometer, calibrated delta badge, quick odometer calibration dialog.
- **`feature/fuel/`**:
  - `FuelEntryDialog.kt`: Add Fuel modal dialog with numeric keypad, real-time calculations, dynamic AI insights.
  - `FuelHistoryScreen.kt`: Multi-month fuel ledger history, consumption analytics, price trends, editing.
  - `RefuelStationPromptDialog.kt`: Geofence-triggered fuel station detection prompt.
- **`feature/maps/`**:
  - `LiveMapView.kt`: osmdroid offline & satellite map view with multi-pass concentric trail coloring.
  - `IndiaOfflineMapDialog.kt`: Regional and all-India offline map tile pack manager & storage inspector.
- **`feature/trips/`**:
  - `TripHistoryScreen.kt`: Historical trip logs, qualifying trip filters, GPX/KML exports.
- **`feature/settings/`**:
  - `LauncherSettingsDialog.kt`: Vehicle specifications, audio warnings, auto-start, reset.
  - `GeofenceSettingsContent.kt`: Home geofence radius slider (50m–150m) & visual map radius.
- **`feature/launcher/`**:
  - `AppDrawerDialog.kt`: In-car app launcher with 60 FPS fast-scroll and categorization.
  - `LauncherManager.kt`: Package manager query and app icon caching.

### 3.2 Target Display Specification: 1280×800 Automotive Head Unit
All touch targets, dialogs, ADB clicks, and bounding boxes are designed and verified against the **1280×800 (16:10 landscape)** automotive form factor:
- **Total Resolution:** $1280\text{px} \times 800\text{px}$ (Head Unit Screen Space).
- **Landscape Screen Partition:**
  - **Left Pane (Map):** $0 \le X \le 640$, $0 \le Y \le 728$ (~52% width)
  - **Right Pane (2×2 Telemetry Cards):** $640 \le X \le 1280$, $0 \le Y \le 728$ (~48% width)
    - *Speedometer Cluster:* Top-Left ($X \in [644, 958], Y \in [8, 364]$)
    - *Fuel System Card:* Top-Right ($X \in [962, 1276], Y \in [8, 364]$)
    - *Current Trip Card:* Bottom-Left ($X \in [644, 958], Y \in [368, 724]$)
    - *Odometer Card:* Bottom-Right ($X \in [962, 1276], Y \in [368, 724]$)
  - **Bottom Action Dock:** $0 \le X \le 1280$, $728 \le Y \le 800$ (Height 72dp)
- **Modal Centered Geometry (Screen-Space Bounds):**
  - *Calibrate Instrument Gauge Dialog:* Width 580dp ($X \approx 350-930, Y \approx 120-680$).
  - *Calibrate Vehicle Odometer Dialog:* Width 560dp ($X \approx 360-920, Y \approx 100-700$).
  - *Add Fuel Modal Dialog:* Centered 85% width ($1088 \times 680$, $X \in [96, 1184]$), Numpad at right 35% ($X \in [800, 1160]$).
  - *Geofence Settings Dialog:* Centered modal ($800 \times 600$, $X \in [240, 1040], Y \in [100, 700]$).
- **Strict Touch Target Rule:**
  - Every interactive element maintains $\ge 48\times 48\text{dp}$ touch bounds (minimum $64\times 64\text{dp}$ recommended for primary in-motion automotive controls).
  - ADB input tap actions must strictly fall within valid bounds $(0..1280, 0..800)$.

### 3.3 Home Dashboard (`DashboardScreen.kt`)
The landscape layout divides the 1280×800 screen into two ergonomic zones:
1. **Left 50% Panel:** Live Offline Map (`LiveMapView.kt`) with GPS satellite beacon, current speed limit sign, compass orientation, and follow mode.
2. **Right 50% Panel:** 2×2 Telemetry Grid:
   - **Top-Left (Card 1):** All-Time Driving Stats & Permanent Odometer.
   - **Top-Right (Card 2):** Live Current Trip Card with permanent elapsed timer and 9 rotating metrics.
   - **Bottom-Left (Card 3):** Live Fuel System Card with 10-bar LED MID visualizer, liters, range, and "+ ADD FUEL" button.
   - **Bottom-Right (Card 4):** Automotive Digital Speedometer with 240° dial, speed trend, gear, and RPM.
3. **Bottom Ergonomic Action Dock:** Cockpit, Full Map, Trip History, Fuel History, All Apps, Launcher Settings, Live Satellites count, and System Clock.

### 3.2 Trip History (`TripHistoryScreen.kt`)
- **Split-View Landscape Layout:** 40% journey list grouped by month / 60% interactive inspector.
- **Multi-Tab Inspector:**
  - *Route Replay:* Interactive GPS playback with play/pause, restart, seek scrubber, and 1x–20x speed multipliers.
  - *Timeline Chronicle:* Structured immutable lifecycle events (Departure, Motion Started, Stationary periods, Refuel logged, Engine states, Peak Speed, Arrival).
  - *Trip Charts:* High-contrast speed profile and elevation distribution graphs.
- **Mobile Responsive Parity:** Full-screen dialog overlay on portrait phones ensuring zero horizontal clipping.

### 3.3 Fuel System & Ledgers (`FuelEntrySheet.kt` & `FuelHistorySheet.kt`)
- **Fuel Entry:** Responsive numeric keypad with spring touch feedback, synchronized calculation ($L = \text{Amount} / \text{Price}$), 10-bar instrument cluster calibration slider, and OEM tank overfill warning.
- **Fuel History:** Yearly and monthly analytics, YoY comparison, highest/lowest expenditure months, individual refill deletion with full transactional balance recalculation.

### 3.4 Launcher Settings (`LauncherSettingsDialog.kt`)
- **Samsung One UI Vertical Navigation Rail:** General, Vehicle, Tracking, Geofences, Maps, Speed & Safety, Data & Storage, About.
- **Data Safety:** Two-step factory reset confirmation, full backup export (.zip archive), and pre-restore snapshot creation.

### 3.5 High-Fidelity Automotive Statistics Dashboards (`TripStatisticsDashboard.kt` & `FuelStatisticsDashboard.kt`)
- **Trip Statistics Dashboard:**
  - *Core KPI Pods:* Total Distance, Driving Duration, Idle & Stoppage time, Estimated Fuel spend (₹ & L), Average & Peak speed, Average trip distance & duration, Daily cadence, and Driver safety score (0–100).
  - *Donut Charts (Canvas):* Trips by time of day (Morning 6–12, Afternoon 12–17, Evening 17–21, Night 21–6), Vehicle time allocation (Moving vs Idle vs Stoppage share), Distance tiers (<5km, 5–20km, >20km), and Velocity spectrum (City crawl, Urban cruise, Highway).
  - *Bar & Line Charts:* Day-of-week frequency & distance profile (Mon–Sun), 12-month accumulated distance trajectory, and Top visited hubs & destinations.
  - *Heatmap & Scatter:* Hour × Day-of-week intensity matrix and Distance vs Duration scatter correlation (traffic bottleneck detection).
  - *Advanced Insights:* Overspeed events, rapid throttle variance, harsh braking frequency, and month-over-month comparisons.
- **Fuel Statistics Dashboard:**
  - *Financial & Volume KPIs:* Total Fuel spend (₹), Total Liters (L), Fleet average mileage (km/L), Running cost per km (₹/km), Average tariff price (₹/L), Refill cadence, and Monthly spend average.
  - *Combo Spend & Mileage Chart:* 12-month dual-axis monthly spend bars (₹) with overlaid mileage curve (km/L).
  - *Brand & Retail Share:* Donut chart & ranked tariffs for fuel retail brands (HPCL, IndianOil, BPCL, Shell, Nayara, Jio-bp).
  - *Price Trends & Scatter:* Continuous fuel price trend (₹/L), Liters vs Amount scatter plot, and Day-of-Week × Week-of-Month refill matrix.
  - *Arbitrage & Eco Insights:* Cheapest vs priciest pump analysis with potential savings calculation, next month spend forecast & refill ETA, and carbon footprint (kg CO₂) with tree offset equivalents.
  - *GPS + Fuel Cross-Telemetry:* Satellite tracking distance vs fuel pump odometer delta calibration, stationary engine idling fuel waste (~0.8 L/hr) & money burned standing still, and MoM consumption comparisons.

---

## 4. Motion Design & Animation Specifications

1. **Spring Physics for Gauges:**
   - Dial needles & speed arcs: `spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)` ensuring zero overshoot jitter while remaining responsive.
2. **Card Transition & Metrics Carousel:**
   - Metric carousel rotation: `tween(durationMillis = 400, easing = FastOutSlowInEasing)` with vertical slide-and-fade (`slideInVertically + fadeIn` / `slideOutVertically + fadeOut`).
3. **Visual Indicators:**
   - Trip Start: 10-second green pulse (`tween(450, RepeatMode.Reverse)`).
   - Trip End: 10-second red pulse transitioning into persistent red badge.
4. **Tactile Touch Response:**
   - `Modifier.bounceClick()` scaling to `0.96f` on press with immediate spring release.

---

## 5. Architectural Invariants & Single Source of Truth

1. **Zero Dummy State:** Every metric displayed connects directly to `MainViewModel` and Room Database entities.
2. **Internal Precision Preservation:** All calculations maintain full `Double` precision internally; rounding to 2 decimal places is strictly confined to `NumberFormatUtils` at the presentation layer.
3. **Decoupled Lifecycle:** Foreground tracking service and Room SQLite operations operate independently of UI composition lifecycle. App backgrounding or screen recreation never causes data loss.
