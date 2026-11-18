# MAP_SETUP — building and installing the offline India map

The app renders **one local vector file, `india.pmtiles`** (PMTiles v3, **OpenMapTiles schema**,
gzip tiles, z0–14). It never downloads tiles from a tile server: public OSM tile servers forbid
bulk downloading, and Geofabrik does not publish MBTiles (the old in-app URLs returned 404).

## How rendering works (why no MapLibre upgrade)

* The app uses MapLibre Native Android **13.7.1, OpenGL ES variant**
  (`org.maplibre.gl:android-sdk-opengl`; not the default Vulkan build), which reads PMTiles natively.
  The style source is `"url": "pmtiles://file:///…/maps/india.pmtiles"` (bound at runtime by
  `ActiveMapFile` / `OfflineStyle`); MapLibre range-reads header, directories and tiles straight from
  flash. There is no tile server, interceptor or Java-side tile cache.
* MapLibre ≥ 11 **requires OpenGL ES 3.0**. The RK3566 / Mali-G31 head unit has GLES 3.2 and is fine,
  but a GLES 2-only device or emulator crashes when the map is created (`eglCreateContext` fails).
  Use an emulator image with host GPU / GLES 3 for map testing.
* Imports are checked by `ActiveMapFile.headerProblem`: PMTiles v3, gzip or uncompressed, MVT tiles.
* MapLibre's HTTP client is replaced by `MapNetworkGuard`, which blocks every request (the map
  needs none); **debug builds crash on purpose**, logging the URL.
* Style JSON, sprites and the (future) glyphs folder ship in `app/src/main/assets/offline_style/`
  and are loaded via `asset://`.
* RAM (2 GB unit): `MapMemoryGuard` polls native heap every 2 s while the map is visible: above
  192 MB it drops cached tiles, above 256 MB it turns MapLibre's tile cache off until heap falls
  below 160 MB. Tilted (3D) views use tile level-of-detail from 40° pitch, so the far half of the
  screen loads lower-zoom tiles.

## 1. Build `india.pmtiles` on a PC (recommended: Planetiler)

Planetiler writes the OpenMapTiles schema that the bundled style expects, and can write PMTiles
directly.

Requirements: Java 21+, about 16 GB RAM, 30 GB free disk. Time: roughly 15–40 minutes.

```bash
mkdir india-map && cd india-map
# 1) Planetiler (single jar)
curl -L -o planetiler.jar https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar

# 2) Build. Planetiler downloads the Geofabrik India extract (~1.5 GB) plus the small
#    water/land helper datasets by itself.
java -Xmx12g -jar planetiler.jar \
  --download \
  --area=india \
  --output=india.pmtiles \
  --maxzoom=14 \
  --nodemap-type=array --storage=mmap

# 3) Optional: check the file
pip install pmtiles        # or: go install github.com/protomaps/go-pmtiles/cmd/pmtiles@latest
pmtiles show india.pmtiles # expect: spec version 3, tile type mvt, tile compression gzip, max zoom 14
```

The result is usually 4–7 GB. The app enforces a 15 GB budget and checks free space before
importing or downloading.

### Alternative: Protomaps `pmtiles extract`

```bash
pmtiles extract https://build.protomaps.com/<YYYYMMDD>.pmtiles india.pmtiles \
  --bbox=68.0,6.0,97.5,37.5 --maxzoom=14
```

⚠ Protomaps builds use the **Protomaps basemap schema** (layers `earth`, `water`, `roads`…), not
OpenMapTiles. The bundled style draws nothing from such a file unless you also replace
`offline_style/style.json` with a Protomaps-schema style. Brotli- or zstd-compressed archives are
rejected; rebuild them with gzip. Use Planetiler unless you need that schema.

## 2. Copy it to the phone / head unit

Pick one:

1. **Import map file (recommended).** Copy `india.pmtiles` anywhere the device can see it (USB
   stick, SD card, `Download/` via USB cable or `adb push india.pmtiles /sdcard/Download/`). In
   the app, open the map, tap **Offline maps** (cloud icon), tap **IMPORT MAP FILE**, then pick the
   file. The app uses the Storage Access Framework, so it needs no storage permission. It checks the
   PMTiles v3 header, the compression and free space, copies the file to
   `Android/data/com.antigravity.copilot/files/maps/india.pmtiles` and shows its size. **DELETE**
   removes it.
2. **Download inside the app.** Host the file on any HTTP(S) server you control (a NAS, or a PC on
   the same Wi-Fi: `python -m http.server 8000`). In Offline maps, tap the link icon on
   *India Vector Map (PMTiles)*, enter the URL, then tap **DOWNLOAD**. Downloads resume (HTTP
   `Range`), and the app checks free space against the real file size before writing.
3. **USB / SD scan.** The **IMPORT FROM USB / STORAGE** action on the download error card scans
   USB drives, the SD card and `Download/` for `*.pmtiles`. Android 11+ needs "All files access"
   for this, so prefer option 1.

## 3. Verify offline

1. Turn on airplane mode and force-stop the app.
2. Launch the app. The map should show roads and water around the vehicle.
3. `adb logcat | grep -E "MapNetworkGuard|Mbgl"` should show **no** `BLOCKED map network request`
   lines (the map makes no HTTP requests at all). A debug build crashes on any map request, so you
   cannot miss one. `adb logcat -s MapPerf MapMemoryGuard` shows FPS, native heap and style load time.

## Road intelligence (pill, speed limit, sharp turns)

`OfflineRoadIntelligenceManager` adds two invisible query layers on `transportation` and
`transportation_name` (opacity **0.01**, not 0: MapLibre ignores opacity-0 layers in
`queryRenderedFeatures`). Stock Planetiler OpenMapTiles output has **no `maxspeed`** on roads, so
limits are MoRTH estimates by road class (shown as "est."); a tile build that adds `maxspeed` to
`transportation` is picked up automatically.

Test on a parked unit (debug build): pan the map so it stops following, then inject fixes, e.g.
`adb shell am broadcast -a com.antigravity.copilot.DEBUG_MAP_FIX --ef x 420 --ef y 300 --ef speed 50`
(map-view pixels; or `--ef lat … --ef lon …`). Watch `adb logcat -s RoadIntel`.

## Labels (optional, not bundled)

The style has no text layers today, so it needs no glyphs. To add street names:

1. Download OpenMapTiles fonts, for example from the `openmaptiles/fonts` GitHub release
   (`v2.0`, Noto Sans).
2. Copy them to
   `app/src/main/assets/offline_style/glyphs/<Font Name>/<range>.pbf`
   (for example `Noto Sans Regular/0-255.pbf`).
3. Add `symbol` layers that use `"text-font": ["Noto Sans Regular"]`. The
   `OfflineMapAirplaneModeTest` check fails if a text layer is added without glyph PBFs.
