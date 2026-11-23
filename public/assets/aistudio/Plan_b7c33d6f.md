Master Execution Plan: Real-Time Block Blast Android Overlay


0. Project Goal & Constraints

Build a non-root native Android overlay app (Kotlin + optional C++ JNI) that:
1. Captures the screen in real time via MediaProjection + ImageReader inside a compliant Foreground Service.
2. Detects game states (Main Menu, Active Gameplay, Animations/Dragging, Game Over) without heavy native vision libraries (pure ByteBuffer pixel sampling).
3. Solves the current 3-piece tray across all permutations while optimizing for future turns (preserving space for bulky upcoming pieces like 3×3, 1×5, 5×1, maintaining combos, and avoiding trapped holes).
4. Renders click-through placement hints (1, 2, 3) directly over the game board via WindowManager (TYPE_APPLICATION_OVERLAY).
5. Builds 100% via CLI (gradle + Android Command-Line Tools) with zero IDE or emulator dependencies so it works identically on low-spec or high-spec Linux machines.

1. Repository Structure

Create a clean, decoupled multi-module workspace so vision and solver logic can be verified offline via JVM unit tests before deploying to Android:
Показать код
Скачать диаграмму в формате SVG

2. Phase 1: .apks Unpacking & Screenshot Calibration (Python CLI)

Before writing Android code, execute two lightweight Python scripts (Pillow, numpy optional) on input_assets/:
1. Unpack .apks Split Archive (tools/extract_apks.py):
	• .apks is a ZIP container holding base.apk and split APKs (e.g., split_config.arm64_v8a.apk, split_config.xxhdpi.apk, asset packs).
	• Extract the .apks archive, then unzip base.apk and all split_config.*.apk files into unpacked_apk/.
	• Inspect res/drawable*, assets/, and decompiled classes (jadx CLI if installed) to extract:
		• Exact piece catalog (all polyomino bitmasks and spawn weights/tiers).
		• Exact board tile colors (empty cell background RGB vs. colored block RGBs) and UI icons.
2. Calibrate Normalized ROIs (tools/calibrate_rois.py):
	• Using the provided screenshots (menu.png, gameplay_start.png, gameplay_mid.png, gameplay_partial_tray.png, gameover.png), output config.json with resolution-independent float coordinates (0.0f–1.0f):
		• state_anchors: 6–10 distinct (x, y, expected_rgb) points uniquely identifying Main Menu, Active Gameplay, and Game Over / Revive.
		• grid_roi: [left, top, right, bottom] of the exact 8×8 board, plus the 64 normalized cell center coordinates.
		• tray_rois: [left, top, right, bottom] for each of the 3 bottom piece slots.
		• color_thresholds: Empty-cell reference RGB + Euclidean/HSV distance threshold to classify occupied cells and tray pixels.

3. Phase 2: Pure-Kotlin Vision & Lookahead Solver (core-engine/)

Build and unit-test this module on the JVM against the screenshots first:

A. Ultra-Fast ByteBuffer Screen Parser (< 2 ms/frame)

• State Gatekeeper: Check state_anchors. If not in Active Gameplay, return State.IDLE (overlay hides and capture sleeps 500 ms).
• Stability / Anti-Drag Lock: Hash a sparse 16×16 grid of sample pixels. Only trigger a solve when 2 consecutive frames match (prevents false reads while a finger is dragging a piece or line-clear particles are animating).
• 8×8 Bitboard Extraction: Sample the center 3×3 pixel patch of each of the 64 cells. Set bit (r * 8 + c) in a Long (uint64) if color distance from empty-tile RGB exceeds threshold.
• 3-Slot Tray Extraction: For each tray slot, sample a dense sub-grid (16×16 points), isolate foreground block pixels, compute the tight bounding box, divide by the calibrated mini-tile pitch, and match the resulting bitmask against the APK's known piece catalog. Mark empty slots as USED.

B. 64-Bit Bitboard Solver with Future-Piece Lookahead (< 15 ms/solve)

Represent the 8×8 board as a single 64-bit integer (Long) where row/column masks and collision checks ((board & pieceMask) == 0L) run in single CPU instructions:
1. Exact Permutation Search: Evaluate all valid (piece_order, row, col) combinations for the remaining 1 to 3 tray pieces (3! = 6 max orders).
2. Future-Turn Survivability Heuristic (Core Intelligence): Score the resulting board after the final piece is placed using weighted terms:
	• Bulky Piece Accommodation (Highest Priority): Count exact valid placements remaining on the board for run-ending pieces: 3×3 square, 1×5 horizontal bar, 5×1 vertical bar, and 3×3 L-shapes. Apply a severe penalty if count(3×3) == 0 or count(1×5) == 0.
	• 1-Step Monte Carlo Rollout (Top-8 Boards): For the top 8 candidate end-boards, sample 12 random 3-piece draws from the APK piece pool and check survival rate / average mobility to avoid trap states.
	• Combo & Line-Clear Efficiency: Reward multi-line clears and clearing a line on the 3rd piece to preserve active combo streaks into the next turn.
	• Board Topology: Penalize isolated 1×1 and 1×2 holes (empty cells surrounded on 3–4 sides) and fragmented empty islands; reward contiguous open regions and flat edges.

4. Phase 3: Native Android Overlay App (android-app/)

Keep dependencies minimal (standard AndroidX Core + Kotlin Coroutines, no heavy third-party SDKs):
• Permissions & Manifest:
	• SYSTEM_ALERT_WINDOW (prompt user once via Settings.ACTION_MANAGE_OVERLAY_PERMISSION).
	• FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PROJECTION with android:foregroundServiceType="mediaProjection" (compatible with Android 10 through Android 15+).
• Screen Capture Loop (ScreenCaptureService):
	• Obtain MediaProjection via ActivityResultContracts.StartActivityForResult().
	• Create a VirtualDisplay at half screen resolution (width / 2, height / 2) into an ImageReader (PixelFormat.RGBA_8888, maxImages = 2)—cuts memory bandwidth by 75% while keeping cell center sampling exact.
	• Poll frames at 5 FPS (200 ms delay) on a background HandlerThread.
• Click-Through Overlay (OverlayCanvasView):
	• Attach to WindowManager with TYPE_APPLICATION_OVERLAY and flags:
FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS.
	• Render:
		• Move 1 (Next Action): Bright green translucent fill + solid border on target grid cells, plus a 1 badge over its tray slot.
		• Move 2 & Move 3 (Planned Sequence): Yellow (2) and cyan (3) dashed outlines on target grid cells so the user sees the full 3-move combo setup.
• Floating Control Pill:
	• A small, draggable edge button (FLAG_NOT_FOCUSABLE only—touchable) to Pause/Resume overlay rendering, toggle Survival vs. High-Score heuristic weights, or stop the service.

5. Build & Verification Commands (CLI-Only)

1. Unpack & Calibrate:bash
python3 tools/extract_apks.py input_assets/game.apks
python3 tools/calibrate_rois.py input_assets/
Используйте код с осторожностью.
2. Verify Vision & Solver Offline:bash
./gradlew :core-engine:test
Используйте код с осторожностью.
3. Build Debug APK (Adaptive Memory):bash
./gradlew :android-app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m"
Используйте код с осторожностью.
4. Install & Grant Permissions via ADB:bash
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb shell appops set <package.name> SYSTEM_ALERT_WINDOW allow
Используйте код с осторожностью.

Execution Order for the AI Agent

1. Inspect input_assets/ (.apks + screenshots) and run extract_apks.py + calibrate_rois.py first; print a visual ASCII verification of the parsed 8×8 boards and tray pieces from every screenshot to confirm 100% vision accuracy.
2. Implement core-engine (64-bit bitboard + future-space lookahead + ByteBuffer parser) and pass all screenshot unit tests.
3. Generate android-app with MediaProjection Foreground Service, half-res ImageReader, click-through OverlayCanvasView, and draggable control pill, then verify clean CLI compilation of android-app-debug.apk.