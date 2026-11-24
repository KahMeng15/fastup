# **Sony A7 III → Android USB Tethering Project**

## **1. Project objective**

Build a custom Android application that connects directly to your **Sony A7 III using USB**, receives photos automatically as you shoot, and uploads them to your own server/gallery.

The intended workflow:

[Sony Alpha 7 III Body - Alpha-Kamera](https://images.openai.com/static-rsc-4/-6Jw-wDYAOcmx5tJZDNzCLbtmNdPe1zQVG8shEkQIXB4PTl5SW84vtiw-CMyU-UDIzaDKMnEVIJVhq-MYjECe1DsbTwgtPLoY9H-C5v-Mi2Qh-ZSrKJ_iLgI5hbF-J6nSwhfQbvPhMdxhlV-y36Wq6ohHgxSEFyYASAJlW2ImPE?purpose=inline)

**Sony A7 III**

Take photos using the physical shutter

[Acheter Samsung Galaxy A52s 6GB/128GB Blanc - Livraison 24h⌛  · MaxMovil](https://images.openai.com/static-rsc-4/PB9KyfDMw_6QuvhOwgnrY3PPd76lXtfCzHCquj0JG4oscDKEN22poTDqwCiBFtq1XFIWHtWrFJZft2M6877fIITLF9L8wpvv2iC2TeWqHl6Nxrghh2Zyy0EbVL3P4vwoQ0mzgR0MSkqYJ3jz-ENgGY3ce6WKStTnP9mAKnzvfWo?purpose=inline)

**Android USB receiver**

Receive original photos automatically

**Upload queue**

Save locally, retry failed uploads, sync when internet is available.

**Your home server / gallery**

Store originals and integrate with your existing gallery API.

The main reason for choosing USB is that you tested FTP and found its transfer speed extremely slow, even when the camera and server were on the same LAN.

## **2. Your existing hardware**

| Component          | Details                                     |
| ------------------ | ------------------------------------------- |
| Camera             | Sony A7 III (ILCE-7M3)                      |
| Android phone      | Samsung Galaxy A52s 5G                      |
| Camera connection  | USB cable / USB OTG                         |
| Computer available | MacBook Pro M3 Pro, 18GB RAM                |
| Home server        | Proxmox / TrueNAS environment               |
| Storage/gallery    | Existing home server and gallery webapp     |
| Network            | Venue Wi-Fi or mobile data, potentially VPN |
| Camera storage     | SD card should remain enabled as backup     |

## **3. What we have established**

### **Sony A7 III USB modes**

The camera offers three relevant USB connection modes:

| Mode         | Behaviour                                              | Suitability                                         |
| ------------ | ------------------------------------------------------ | --------------------------------------------------- |
| Mass Storage | Exposes storage to connected device                    | Not yet tested                                      |
| MTP          | Exposes media files                                    | You tested this; it disables shooting in your setup |
| PC Remote    | Designed for tethered shooting using computer software | Main approach to investigate                        |

**Our decision: investigate PC Remote instead of MTP.**

Sony's PC Remote workflow is designed around compatible computer software. We cannot assume an Android application can use it without implementing the relevant camera communication protocol.

### **Why PC Remote is interesting**

We identified an open-source project:

**sony-camera-android**

[View the GitHub repository](https://github.com/argallo/sony-camera-android?utm_source=chatgpt.com)

It provides an Android/Kotlin implementation for communicating with Sony Alpha cameras over wired USB.

Its documented functionality includes:

- Camera communication over USB.
- Live view.
- Autofocus.
- App-triggered image capture.
- Full-resolution JPEG retrieval.

However, there are two major uncertainties:

1. Its documented compatibility is with the Sony α6600, not confirmed A7 III compatibility.
2. Its documented capture workflow retrieves JPEG, not confirmed RAW `.ARW` files.

It is a starting point for investigation, not a proven solution.

## **4. Things to test**

This is the most important part of the project. We should validate each stage before developing the complete application.

## **Testing checklist**

**A. USB connection**
- [x] Connect the Samsung A52s to the Sony A7 III using a data-capable USB cable and OTG-compatible connection.
- [x] Confirm that Android recognises the camera as a USB device (VID: 0x054C, PID: 0x0C34, Class 6 PTP).
- [x] Set Sony USB Connection to PC Remote.
- [x] Check whether the camera remains in shooting-ready mode (PC+Camera destination).

**B. PC Remote communication**
- [x] Test PC Remote communication between A7 III and Galaxy A52s.
- [x] Check whether physical camera shutter works while connected.
- [x] Verify live view and communication over USB PTP.

**C. Image retrieval**
- [x] Capture a photo using the physical shutter and check whether Android detects it.
- [x] Determine whether the camera exposes new captures through the USB protocol.
- [x] Retrieve full-resolution RAW `.ARW` file (verified: 24.7MB, 6000x4000, full EXIF).
- [x] Verify file integrity (confirmed standard Sony TIFF / ARW structure).

**D. Automatic transfer**

Test whether every physical-shutter capture is detected automatically.

Test continuous shooting and burst sequences.

Test RAW+JPEG if supported.

Check whether the camera can keep shooting while Android retrieves files.

Measure transfer speed and check for missing or corrupted files.

**E. Upload integration**

Save received files to persistent Android storage before uploading.

Implement an upload queue with retry and duplicate detection.

Test upload through mobile data and Wi-Fi.

Test VPN connectivity to your home server.

Verify that the gallery receives and displays the original files correctly.

**F. Reliability**

Disconnect and reconnect USB while shooting.

Test Android screen-off and background operation.

Test camera sleep, phone battery usage, and overheating.

Simulate internet loss during upload.

Verify that no image is lost if the phone or server temporarily disconnects.

## **5. Most important technical questions**

These are the questions that determine whether the project is feasible.

| Question                                                        | Why it matters                                    |
| --------------------------------------------------------------- | ------------------------------------------------- |
| Can Android communicate with the A7 III in PC Remote mode?      | Determines whether we can use USB at all.         |
| Can Android detect photos taken with the physical shutter?      | Essential for automatic tethering.                |
| Can we retrieve RAW `.ARW` files?                               | Essential if original RAW files are required.     |
| Can we retrieve photos while the camera remains ready to shoot? | Prevents interruptions during events.             |
| How fast is USB transfer?                                       | Determines whether it solves your FTP bottleneck. |
| Can Android keep the USB connection active in the background?   | Essential for long events.                        |

## **6. Proposed development architecture**

If the USB tests succeed, this is the architecture I recommend.

| Module              | Responsibility                                              |
| ------------------- | ----------------------------------------------------------- |
| USB Camera Manager  | Detect camera, manage USB permissions and connections       |
| Sony PTP Controller | Communicate with the camera and retrieve captures           |
| Photo Receiver      | Receive JPEG/RAW files and validate them                    |
| Local Storage       | Preserve received originals                                 |
| Upload Queue        | Track pending, uploading, successful and failed transfers   |
| Server API Client   | Upload files to your existing gallery                       |
| Photo Analysis      | AI duplicate/burst selection and image evaluation           |
| Editing Module      | Exposure correction and manual adjustments                  |
| Dashboard           | Show camera connection, transfer progress and upload status |

**Important design principle:** image reception and server uploading must be independent. Never make the camera wait for the internet or server to become available.

## **7. Recommended development priorities**

1.

   First

   &#x20;Prove USB PC Remote communication between the A7 III and Samsung A52s.

2. Prove physical-shutter capture detection.

3. Prove original `.ARW` retrieval.

4. Measure USB transfer speed and test continuous shooting.

5. Build a reliable Android receiver and local storage queue.

6. Integrate upload with your existing home server.

7. Add AI photo selection and editing features later.

## **8. Confirmed Architecture & Design Decisions**

| Requirement | Decision |
| :--- | :--- |
| **Android Framework** | Native Kotlin + Jetpack Compose |
| **Primary App Focus** | **Media viewer & Upload Dashboard** (Queue progress, live speed in kB/s, retry management). Camera live-view is secondary. |
| **Upload Format Options** | User-configurable toggle: **RAW + JPEG**, **JPEG only**, or **RAW only**. |
| **Backend Integration** | Target backend is **`hellomyphotos`** (Fastify + SvelteKit + pgvector + Docker). |
| **Staging Volume Strategy** | Writable `staging_rw` volume for live/temp uploads, keeping `media_ro` strictly read-only (`:ro`). |
| **Share Link Generation** | **On-demand** (triggered by photographer via API request button in the app, generating an instant public token & QR code for clients/guests). |
| **Final Archive Workflow** | Original RAWs saved to phone / camera SD &rarr; post-processed in Lightroom &rarr; final edited photos uploaded to permanent `media_ro` &rarr; staging cleaned up. |

---

## **9. Implementation Status**

### **Phase 1: `hellomyphotos` Backend Staging & Share API** ✅ COMPLETED
- [x] Added `staging_rw` volume to `docker-compose.yml`, `docker-compose.dev.yml`, and `docker-compose.prod.yml` (`./volumes/staging_rw:/app/staging:rw`).
- [x] Implemented `resolveMediaFullPath` in `backend/src/utils/mediaPath.ts` supporting dual-root media paths.
- [x] Configured `@fastify/multipart` (v9.0.3) with 150MB file size limit for high-res RAW uploads.
- [x] Built Fastify staging routes in `backend/src/modules/staging/`:
  - `POST /api/staging/upload`: Streaming multipart upload for RAW/JPEG with `exifr` thumbnail extraction.
  - `POST /api/staging/create-share`: On-demand public guest share link creation.
  - `GET /api/staging/sessions` & `GET /api/staging/:sessionId`: Staging session listings and metadata.
  - `DELETE /api/staging/:sessionId`: Complete post-event cleanup of disk files and cache.
- [x] Authenticated via `X-API-Key: <STAGING_API_KEY>` or standard Bearer JWT.
- [x] Verified with TypeScript compilation and integration tests passing.

### **Phase 2: Android App Scaffold (Kotlin + Jetpack Compose)** ✅ COMPLETED
- [x] Initialized Gradle Kotlin DSL project (`fastup`) targeting Android API 34.
- [x] Built `SonyPtpDriver`, `UsbCameraManager`, and `UsbTetherService` foreground service.
- [x] Created `ArwParser` for instant embedded JPEG preview extraction and EXIF parsing.
- [x] Implemented `UploadManager` with real-time transfer speed (kB/s or MB/s), queue counters, retry logic, network policies (Wi-Fi only vs Cellular), and upload mode toggle (**RAW only**, **JPEG only**, **RAW + JPEG**).
- [x] Built `ApiClient` for `hellomyphotos` staging API with on-demand ZXing QR code rendering.
- [x] Developed Jetpack Compose UI:
  - **Dashboard**: Unified twin pipeline card layout (Local Transfer with Camera connection status & Sony model; Upload Pipeline with Server connectivity & host probe; real-time speed in kB/s or MB/s; storage used & free stats; session config and QR share generation).
  - **Gallery**: 3-column media grid with format chips, upload status badges, and zoomable detail view with full EXIF sheet.
  - **Settings**: Upload format picker, backend URL, auto-upload toggle, and server cleanup trigger.
- [x] Compiled APK (`app/build/outputs/apk/debug/app-debug.apk`), deployed directly to the connected Samsung Galaxy A52s, launched and verified on-screen.

### **Phase 3: Server-Centric AI Burst Curation & Dual-View Staging** ✅ COMPLETED
- [x] **Database Schema**: Added `burst_group_id`, `is_hero`, `quality_score`, and `face_count` to `media_files`; added `allow_view_mode_toggle`, `show_hidden_alternates`, and `allow_hero_selection` to `shared_folders`.
- [x] **Server-Side AI Pipeline (`hellomyphotos`)**:
  - Implemented debounced burst clustering ($\Delta t \le 2.2\text{s}$) in `StagingService.evaluateBurstsForFolder`.
  - Added **People-Count Guardrail** ($\Delta \text{Faces} \ne 0$ immediately splits burst to prevent hiding shots where people joined).
  - Evaluates focus sharpness via Laplacian convolution kernel stats on `sharp` thumbnail, factoring face count & gaze.
  - Automatically designates best pick hero (`is_hero = true`) and alternates (`is_hero = false`).
- [x] **Dual-View Staging API**:
  - `GET /api/staging/*?view=curated` (heroes & standalones) vs `?view=all` (full sequence).
  - `GET /api/staging/media/:id/alternates` & `/api/shares/:token/media/:id/alternates` (fetches hidden burst peers for lightbox).
  - `POST /api/staging/media/:id/set-hero` & `/api/shares/:token/media/:id/set-hero` (1-click hero promotion).
- [x] **Web Gallery Lightbox (`hellomyphotos/frontend`)**:
  - Staging view toggle pill: `[ ★ Curated | All ]` (respecting `allow_view_mode_toggle`).
  - Added burst badge on grid photos (`★ N shots`).
  - Lightbox `.burst-filmstrip-drawer` displaying hidden alternate shots with active preview switcher.
  - 1-click "Set Best" hero promotion and direct alternate download buttons.
  - Share link parameter enforcement (`allow_view_mode_toggle`, `show_hidden_alternates`, `allow_hero_selection`).
- [x] **FastUp Android App Sync & UI**:
  - Added `curationViewMode` state flow and dual-view switcher (`★ Curated` vs `All`) in `GalleryScreen.kt`.
  - Added burst counter badge (`★ N`) on grid items in `GalleryScreen.kt`.
  - Verified compilation and built debug APK (`assembleDebug` passing).

### **Phase 4: Dual Share Links (Hellomyphotos + Google Drive) & In-App GDrive Sync Initialization** ✅ COMPLETED
- [x] **Backend API Enhancements (`hellomyphotos/backend`)**:
  - Enabled photographer token / staging API key authentication (`requireStagingAuth`) on Google Drive endpoints (`/api/gdrive/accounts`, `/api/gdrive/folders`, `/api/gdrive/sync`, `/api/gdrive/media/:mediaId/link`).
  - Added `GET /api/gdrive/accounts` to allow clients to list connected Google accounts.
  - Updated `StagingService.createShare` and `POST /api/staging/create-share` to accept `mediaId` parameter for generating on-demand single photo share links in addition to folder share links.
- [x] **FastUp Android App Architecture (`fastup`)**:
  - Created `GDriveModels.kt` containing `GoogleAccount`, `GDriveFolder`, `FolderGDriveSync`, `FolderSyncInfo`, `MediaGDriveLinkResult`, and `DualShareLinks`.
  - Enhanced `ApiClient.kt` with methods:
    - `fetchFolderGDriveSync`
    - `fetchGoogleAccounts`
    - `fetchGoogleDriveFolders`
    - `initializeGDriveSync`
    - `fetchMediaGDriveLink`
    - `createMediaShareLink`
  - Integrated `UploadManager.kt` to capture uploaded `serverMediaId` immediately upon upload completion and track `folderGDriveSync`.
  - Added ViewModel orchestration in `MainViewModel.kt` for `fetchFolderShareLinks`, `fetchPhotoShareLinks`, and `initializeGDriveSync`.
- [x] **Dual-Share & Cloud Sync UI (`fastup/ui/share/ShareModalDialog.kt`)**:
  - Unified `ShareModalDialog` supporting both folder and individual photo levels.
  - Displays **Hellomyphotos Link** (Web gallery / single photo view) with 1-click **Copy Link** and **Show QR**.
  - Displays **Google Drive Link** (Folder or direct file link) with 1-click **Copy Link** and **Show QR**.
  - If Google Drive Sync is uninitialized: displays prompt card with 1-click setup sheet:
    - Account selection (from connected Google Accounts).
    - Destination mode toggle: **Create New Folder** (with name input) vs **Select Existing Drive Folder** (with folder tree selection).
    - "Enable & Start Sync" trigger.
  - Interactive `QrCodeDialog` with ZXing high-res rendering, copy to clipboard, and native Android `Intent.ACTION_SEND` system sharing.
  - Added 1-tap "Share Event" action on **DashboardScreen**, "Share" icon in **PhotoDetailScreen**, and upgraded session sharing in **SettingsScreen**.
- [x] **Verification**:
  - `hellomyphotos/backend` TypeScript check passing with 0 errors (`npx tsc --noEmit`).
  - `fastup` Android debug APK successfully compiled and packaged (`./gradlew assembleDebug` passing).
  - Added dedicated lower sub-header in `GalleryScreen.kt` displaying current folder name with 1-tap "Share Folder" action.
  - Implemented strictly on-demand generation for Hellomyphotos links: links are no longer eagerly created on opening the dialog; they are generated on-the-fly when the user explicitly clicks "Copy Link" or "Show QR".
  - Installed and verified live on connected Samsung Galaxy A52s device.
