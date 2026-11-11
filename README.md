# 동영상 다운로더 (Android)

웹 페이지에서 재생되는 동영상을 찾아 기기에 저장하는 안드로이드 앱입니다.
"모든 동영상 다운로더" 류 앱의 핵심 동작 — **내장 브라우저로 페이지를 열면 받을 수 있는
영상이 자동으로 목록에 뜨고, 눌러서 받는다** — 를 구현했습니다.

---

## 1. 빌드하기

이 PC에는 JDK도 Android SDK도 설치되어 있지 않습니다. 아래 순서로 준비하세요.

1. **Android Studio 설치** (JDK 17 + Android SDK가 함께 설치됩니다)
   - https://developer.android.com/studio
2. Android Studio에서 `File > Open` → 이 폴더(`VideoDownloader`) 선택
3. 최초 열기 시 Gradle Sync가 자동으로 돌아갑니다
   - 이 프로젝트에는 `gradle-wrapper.jar`와 `gradlew` 실행 스크립트가 없습니다(바이너리라 생성 불가).
     Android Studio가 "Gradle wrapper가 없다"고 하면 **Use Gradle from: 'gradle-wrapper.properties' 또는
     번들 Gradle** 중 아무거나 선택하면 됩니다. 터미널에서 쓰고 싶다면 Gradle 설치 후
     `gradle wrapper --gradle-version 8.9` 를 한 번 실행하세요.
4. `compileSdk 35` 를 쓰므로 SDK Manager에서 **Android 15 (API 35)** 를 설치
5. 기기 또는 에뮬레이터 선택 후 Run

### 검증 상태
**이 코드는 아직 한 번도 컴파일되지 않았습니다.** 작성자(AI)의 환경에 Android 툴체인이 없어
빌드 검증을 못 했습니다. 첫 Sync에서 의존성 버전 충돌이나 사소한 컴파일 오류가 날 수 있습니다.

### 버전 조합
| 항목 | 버전 |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 (Android 7.0) |

---

## 2. 할 수 있는 것 / 없는 것

### 지원
- 일반 웹사이트의 **MP4 등 단일 파일** 영상 (이어받기 지원)
- **HLS(.m3u8) 스트리밍** — 세그먼트를 모두 받아 하나로 합침
  - 마스터 플레이리스트면 화질 선택 가능
  - AES-128 암호화 플레이리스트 복호화
  - TS → MP4 재컨테이너화(재인코딩 없음)
- 다른 앱에서 **공유하기**로 링크 전달
- 백그라운드 다운로드(포그라운드 서비스 + 알림), 일시정지 / 이어받기 / 취소
- `Movies/동영상 다운로더` 폴더에 저장 → 갤러리에서 바로 보임

### 지원하지 않음 (의도적)
| 대상 | 이유 |
|---|---|
| **YouTube** | 서비스 약관 위반이며 Google Play 정책상 스토어 등록 불가. 벤치마킹한 앱들도 지원하지 않습니다. |
| **DRM 콘텐츠** (Netflix, Disney+, 웨이브, 티빙 등) | Widevine/FairPlay로 보호됨. 기술적으로 우회 불가이며 시도해서도 안 됩니다. |
| **SAMPLE-AES HLS** | FairPlay DRM. 명시적으로 거부합니다. |
| **DASH (.mpd)** | 미구현. 필요하면 `M3u8Parser`와 같은 자리에 DASH 파서를 추가하는 구조입니다. |
| **라이브 스트림** | 끝이 없어 "전체 다운로드" 개념이 성립하지 않습니다. 명확한 오류로 알립니다. |
| **blob: URL** | 메모리 상의 스트림이라 직접 받을 수 없습니다. 대신 네트워크 단계에서 실제 스트림 주소를 잡습니다. |

> 받은 영상의 이용은 해당 사이트 약관과 저작권법의 적용을 받습니다. 앱은 기술적 수단만 제공합니다.

---

## 3. 구조

```
app/src/main/java/com/aiutil/videodownloader/
├─ App.kt                       앱 전역 싱글턴(저장소, 다운로드 매니저, 알림 채널)
├─ model/Models.kt              DownloadItem, DetectedVideo, HlsVariant, 상태 enum
├─ core/Http.kt                 OkHttp 클라이언트 2종(다운로드용 / 탐색용)
├─ data/
│  ├─ DownloadStore.kt          다운로드 목록 영속화(JSON) + StateFlow
│  └─ DetectionStore.kt         현재 페이지에서 감지한 영상(메모리)
├─ browser/
│  ├─ VideoSniffer.kt           ★ 요청을 보고 영상인지 판정 (확장자 → HEAD 2단계)
│  ├─ SnifferWebViewClient.kt   shouldInterceptRequest로 모든 요청 관찰
│  └─ VideoJsBridge.kt          페이지에 주입하는 DOM 스캐너 + JS 브리지
├─ download/
│  ├─ DownloadManagerCore.kt    ★ 큐/동시성/상태 전이의 주인
│  ├─ FileDownloader.kt         Range 이어받기 단일 파일 다운로드
│  ├─ HlsDownloader.kt          세그먼트 병렬 선취 + AES-128 복호화 + 이어받기
│  ├─ M3u8Parser.kt             마스터/미디어 플레이리스트 파서
│  ├─ TsRemuxer.kt              MediaExtractor+MediaMuxer로 TS→MP4
│  ├─ MediaStoreSaver.kt        완료 파일을 공용 저장소로 이동
│  ├─ DownloadService.kt        포그라운드 서비스(프로세스 유지 + 알림)
│  └─ Notifications.kt          알림 채널/빌더
├─ ui/
│  ├─ MainActivity.kt           진입점, 알림 권한, 공유 인텐트 수신
│  ├─ ShareReceiverActivity.kt  공유 텍스트에서 URL 추출
│  ├─ AppRoot.kt                3탭 Scaffold
│  ├─ browser/                  BrowserScreen, BrowserViewModel, WebViewHolder, DetectedVideoSheet
│  ├─ downloads/                진행 중 목록
│  ├─ library/                  완료된 영상 목록
│  └─ components/               VideoThumbnail
└─ util/                        Names(파일명), Format(표시), MediaIntents(재생/공유)
```

### 영상 감지 흐름

```
WebView 요청 ──▶ SnifferWebViewClient.shouldInterceptRequest
                        │  (UI 스레드 아님. 절대 블로킹하지 않을 것)
                        ▼
                 VideoSniffer.offer
                   ├─ .ts/.m4s/광고 도메인  → 버림
                   ├─ .m3u8                → HLS로 즉시 등록
                   ├─ .mp4/.webm/…         → 등록(크기만 비동기 확인)
                   └─ video/stream 힌트    → 백그라운드 HEAD → Content-Type으로 판정
                        │
페이지 로드 완료 ──▶ SCANNER_JS 주입 ──▶ VideoJsBridge.onVideosFound ──┤
                   (<video>, <source>, og:video, 1.5초마다 재스캔)      ▼
                                                            DetectionStore (StateFlow)
                                                                        ▼
                                                           브라우저 화면의 "영상 N개" 버튼
```

### 다운로드 흐름

```
DetectedVideo ─▶ DownloadManagerCore.enqueue
                   │  (중복 URL은 기존 작업 재사용)
                   ▼
            DownloadStore에 QUEUED로 기록 + 포그라운드 서비스 기동
                   ▼
            Semaphore(3)로 동시 실행 제한
                   ▼
     ┌─────────────┴─────────────┐
     ▼                           ▼
FileDownloader              HlsDownloader
(Range 이어받기)        (세그먼트 3개 선취, AES-128 복호화)
     │                           │
     │                    TS면 TsRemuxer로 MP4 변환 (실패 시 .ts 유지)
     └─────────────┬─────────────┘
                   ▼
      캐시(.part) ──▶ MediaStoreSaver ──▶ Movies/동영상 다운로더/
                   ▼
            COMPLETED + 완료 알림
```

**받는 중에는 앱 캐시(`cacheDir/downloads/<id>.part`)에 쓰고, 완료 시점에만 공용 저장소로 옮깁니다.**
중간에 끊긴 파일이 갤러리에 나타나지 않고, 이어받기 로직도 단순해집니다.

---

## 4. 손볼 만한 곳

| 하고 싶은 것 | 볼 파일 |
|---|---|
| 감지가 너무 많다/적다 | `VideoSniffer.kt` 의 `PROBE_HINTS`, `NOISE_HOSTS`, `MIN_MP4_BYTES` |
| 시작 화면 사이트 목록 | `BrowserScreen.kt` 의 `StartPage` 안 `shortcuts` |
| 동시 다운로드 개수 | `DownloadManagerCore.MAX_CONCURRENT` |
| 저장 폴더 이름 | `MediaStoreSaver.FOLDER` |
| 앱 아이콘 | 현재 벡터 임시본. Android Studio의 Image Asset Studio로 교체 권장 |
| Wi-Fi에서만 받기 | 미구현. `DownloadManagerCore.startJob`에서 `ConnectivityManager` 확인을 추가하면 됩니다 |

---

## 5. 알려진 제약

- **다운로드 재시작 지점**: HLS는 세그먼트 경계로만 이어받습니다(세그먼트 중간에 끊기면 그 세그먼트는 다시 받음).
- **TS→MP4 변환**: 스트림에 불연속(discontinuity)이 많으면 `MediaMuxer`가 실패할 수 있고, 이때는 `.ts` 그대로 저장됩니다.
- **쿠키 의존 사이트**: 로그인이 필요한 페이지는 브라우저 탭에서 로그인한 뒤에 받아야 합니다(쿠키를 그대로 물려줍니다).
- **프로세스 종료**: 비정상 종료 시 진행 중이던 항목은 다음 실행에서 `일시정지`로 복구되며, 이어받기로 계속할 수 있습니다.
