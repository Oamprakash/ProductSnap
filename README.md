# ProductSnap (base)

Android photo editor focused on one job: clean product photos for online sellers.

## What works
- Pick photo (Photo Picker, no permissions) or share into the app from Gallery
- On-device background removal (ML Kit Subject Segmentation, no cloud cost)
- Background swap: plain colours (incl. transparent → PNG), studio lights, gradients, or your own photo with optional blur; non-plain backgrounds switch on background removal automatically (editor and batch)
- 1:1 marketplace canvas with padding
- Magic eraser: paint over price tags, dust, cables or clutter and it's filled from the surrounding background (on-device, no model download), with stroke undo, up to 3 erase undos, and pinch-to-zoom (brush stays the same size on screen, so zooming in gives finer strokes). If strokes touch the item itself, the app asks first: hiding stains or wear misrepresents condition
- Crop in the editor: drag corners/edges/middle, presets Free, 1:1, 4:5, 3:4, 16:9, and "Fit to product" when the background is removed
- Crop in batch: Off, Fit product (trims empty space), 1:1, 4:5, 3:4; ratio crops are centred on the product
- Pinch-to-zoom and double-tap zoom on the editor preview
- Undo for the last 3 crops/erases
- Brightness / contrast (live preview uses the same matrix as export). No saturation: it changes the item's colour
- Save to Pictures/ProductSnap via MediaStore
- Batch mode: up to 30 photos (premium), one set of settings, saved one by one with per-photo status, stop/retry
- Batch runs in WorkManager as a foreground job: keeps going in the background, shows a progress notification with Stop, and resumes after process death
- Share one photo into the editor, or several straight into batch
- Soft shadow under the product (editor and batch), so swapped backgrounds don't look pasted on
- Premium subscription via Google Play Billing: paywall with plans and free trial, restore, manage subscription; free = one finished photo at full quality with a watermark, then the paywall (processing and previews stay free); premium = unlimited saves, no watermark, 30-photo batches

## Setup
1. Open the folder in Android Studio (Ladybug or newer). If it asks for the Gradle wrapper, run `gradle wrapper` once or let Studio create it.
2. Run on a device with Google Play services. The segmentation model downloads on first install; the first removal may fail offline.
3. Bump dependency versions to latest stable.

## Structure
- `editor/` – single-photo screen + ViewModel, crop and erase canvases, `ZoomState`/`pinchToZoom`, shared swatch component
- `batch/` – batch screen + ViewModel (UI only), `BatchStore` (the batch on disk), `BatchWorker` (the run), `BatchNotifications`
- `image/` – loading, background removal, rendering, saving; `ExportSettings` is shared by editor and batch; `Cropper` does subject bounds and ratio crops
- `image/background/` – `BackgroundStyle` + presets, `BackgroundPhotos` (import, prepare), `Blur` (fast soft blur)
- `image/erase/` – `MagicEraser` (crop, scale, blend) and `PatchMatchInpainter` (pure-Kotlin hole filling)
- `billing/` – `Billing` (Play Billing client), `PaywallSheet`, `PremiumManager` (the single premium flag)

## Next steps
- Server-side purchase verification (Play Developer API)
- Undo/redo stack, crop & rotate
- Drop shadow under subject
- Swap theme tokens for your shared Compose design system

## How batch survives the background
1. Picked photos are copied into app storage (`files/batch/inputs`), so the job never depends on picker or share grants that expire with the process.
2. The batch (photos, settings, per-photo status) is saved to `files/batch/job.json` after every change.
3. `BatchWorker` runs as a foreground service (`dataSync`) and processes one photo at a time, freeing every bitmap before the next.
4. If Android kills the process, WorkManager restarts the worker and it continues from the first unfinished photo. Copies are deleted as each photo finishes.

## How the magic eraser works
1. Strokes are stored in image pixels, so they don't depend on screen size.
2. Only the area around the strokes (plus context) is processed, scaled to 400px for speed.
3. `PatchMatchInpainter` fills the hole coarse-to-fine by copying the most similar patches from outside it.
4. The fill is scaled back up and blended in with a feathered edge; the rest of the photo stays full resolution.

Best on plain or repeating backgrounds (studio shots, tabletops, walls). For large objects on busy scenes, swap the inpainter call in `MagicEraser` for an ML model (LaMa or MI-GAN via TFLite); the crop/scale/blend around it stays the same.

## Setting up Premium in Play Console
Billing only works for a build uploaded to Play with the same application ID and signing key.
1. Upload a signed build to the **Internal testing** track.
2. Monetize › Subscriptions › Create subscription with product ID `productsnap_premium`.
3. Add two auto-renewing base plans, `weekly` (1 week) and `yearly` (1 year), and set prices. The paywall lists whatever base plans Play returns, longest first.
4. Add a free-trial offer to the yearly plan (eligibility: new customers). The paywall shows a trial automatically when Play says the user is eligible, and labels the button "Start free trial".
5. Activate the base plans and offer.
6. Settings › License testing: add your Google account(s) so test purchases aren't charged and renew quickly.
7. Install from the internal-testing link (not from Android Studio) the first time, then buy from the paywall.

Debug builds also show "Unlock Premium (debug builds only)" in the paywall for testing without Play.
