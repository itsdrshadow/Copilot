# MAP_SETUP — building and installing the offline India map

The app renders **one local vector file, `india.pmtiles`** (PMTiles v3, **OpenMapTiles schema**,
gzip tiles, z0–14). It never downloads tiles from a tile server: public OSM tile servers forbid
bulk downloading, and Geofabrik does not publish MBTiles (the old in-app URLs returned 404).

## How rendering works (why no MapLibre upgrade)

* MapLibre Native Android **10.3.2** (used by the app) cannot open local MBTiles or PMTiles.
* The **minimum** version that can is **11.8.0** (`pmtiles://`; checked: present in 11.8.8's
  `libmaplibre.so`, absent in 11.7.1). But MapLibre 11.x **requires OpenGL ES 3.0**. On a GLES 2
  device it crashes the whole app when the map is created (`eglCreateContext` fails; reproduced on
  the emulator, which only exposes GLES 2.0). Many head units also ship GLES 2-only GPUs.
* So the app **stays on 10.3.2** and serves the tiles itself: the style asks for
  `http://tiles.copilot.invalid/{map_id}/{z}/{x}/{y}.pbf`. MapLibre's HTTP client is replaced by an
  OkHttp interceptor (`MapNetworkGuard`) that answers those URLs from `india.pmtiles`
  (`PmTilesReader`) **inside the process**. No socket is ever opened, and `.invalid` can never
  resolve. Every other map request is blocked; **debug builds crash on purpose**, logging the URL.
* Style JSON, sprites and the (future) glyphs folder ship in `app/src/main/assets/offline_style/`.

If you later move to a GLES 3 head unit you can upgrade to MapLibre ≥ 11.8 and point the source at
`pmtiles://file://…` directly. The rest of the pipeline stays the same.

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
3. `adb logcat | grep -E "MapNetworkGuard|Mbgl-HttpRequest"` should show `Request was successful`
   only for `tiles.copilot.invalid` and **no** `BLOCKED map network request` lines. A debug build
   crashes on any other map request, so you cannot miss one.

## Labels (optional, not bundled)

The style has no text layers today, so it needs no glyphs. To add street names:

1. Download OpenMapTiles fonts, for example from the `openmaptiles/fonts` GitHub release
   (`v2.0`, Noto Sans).
2. Copy them to
   `app/src/main/assets/offline_style/glyphs/<Font Name>/<range>.pbf`
   (for example `Noto Sans Regular/0-255.pbf`).
3. Add `symbol` layers that use `"text-font": ["Noto Sans Regular"]`. The
   `OfflineMapAirplaneModeTest` check fails if a text layer is added without glyph PBFs.
