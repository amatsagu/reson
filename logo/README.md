# Reson Official Brand Identity & Logo Assets

## The Official Logo: Puffy Bubble "R" (Cotton Candy Theme)
The official logo for **Reson** is the whimsical, tactile **Puffy Bubble "R"** in the **Cotton Candy** palette. It represents a playful, friendly, and modern audio identity without leaning on generic play triangles or character faces.

---

## 🎨 Color Palette Specifications

### App Launcher (Full Color)
- **Background**: Dark Nebula Navy Gradient
  - Start (Top): `#0E1324`
  - End (Bottom): `#060912`
- **Puffy Bubble Glyph**: Cotton Candy Linear Gradient (45° angle)
  - Start (Upper Left): `#F472B6` (Bubblegum Pink)
  - End (Lower Right): `#38BDF8` (Sky Blue)
- **Gloss Highlights**:
  - Upper Rim Gloss: Pure White with 65% opacity (`#A6FFFFFF`), rounded cap stroke
  - Stem Vertical Gloss: Pure White with 55% opacity (`#8CFFFFFF`), rounded cap stroke

### Monochrome & Notification (Android 12 / 13+)
- **Body**: `#FFFFFF` (100% white) with EvenOdd cutout for the organic pebble counter hole.
- **Background**: Transparent / System Wallpaper Dynamic Tint.

---

## 📁 Source Vector Files
Located in [`logo/`](./):
- [`reson_logo_cotton_candy.svg`](./reson_logo_cotton_candy.svg) — 512×512 Master Vector SVG in full Cotton Candy theme.
- [`reson_logo_monochrome.svg`](./reson_logo_monochrome.svg) — 512×512 Master Monochrome Vector SVG.

Alternative explorations and colorways remain preserved in [`logo_designs/`](../logo_designs/).

---

## 📱 Android Resource Registration & Architecture

### 1. Adaptive App Icons (API 26+)
- **Standard Launcher**: [`app/src/main/res/mipmap-anydpi/ic_launcher.xml`](../app/src/main/res/mipmap-anydpi/ic_launcher.xml)
- **Round Launcher**: [`app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`](../app/src/main/res/mipmap-anydpi/ic_launcher_round.xml)
  - **Background Vector**: [`app/src/main/res/drawable/ic_launcher_background.xml`](../app/src/main/res/drawable/ic_launcher_background.xml) (108×108dp canvas)
  - **Foreground Vector**: [`app/src/main/res/drawable/ic_launcher_foreground.xml`](../app/src/main/res/drawable/ic_launcher_foreground.xml) (centered within the 72dp safe zone)
  - **Themed Monochrome Vector**: [`app/src/main/res/drawable/ic_launcher_monochrome.xml`](../app/src/main/res/drawable/ic_launcher_monochrome.xml) (Material You dynamic wallpaper theming on Android 13+)

### 2. Status Bar & Media Notifications
- **Small Icon**: [`app/src/main/res/drawable/ic_notification.xml`](../app/src/main/res/drawable/ic_notification.xml) (24×24dp monochrome vector)
- Linked in [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) under `ResonPlaybackService` via:
  ```xml
  <meta-data
      android:name="androidx.media3.session.notification.small_icon"
      android:resource="@drawable/ic_notification" />
  ```

### 3. Legacy Density Mipmaps (Fallback for non-adaptive launchers)
Optimized WebP raster assets generated at standard density buckets:
- `mipmap-mdpi/`: 48×48 px (`ic_launcher.webp`, `ic_launcher_round.webp`)
- `mipmap-hdpi/`: 72×72 px (`ic_launcher.webp`, `ic_launcher_round.webp`)
- `mipmap-xhdpi/`: 96×96 px (`ic_launcher.webp`, `ic_launcher_round.webp`)
- `mipmap-xxhdpi/`: 144×144 px (`ic_launcher.webp`, `ic_launcher_round.webp`)
- `mipmap-xxxhdpi/`: 192×192 px (`ic_launcher.webp`, `ic_launcher_round.webp`)
