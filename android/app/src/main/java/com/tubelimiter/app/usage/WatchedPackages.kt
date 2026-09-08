package com.tubelimiter.app.usage

/**
 * 감시 대상 앱 패키지 목록 (documents/QA_REVIEW.md §1.8).
 *
 * 예전에는 `com.google.android.youtube` **하나**가 감시 대상 전부였다. 그래서 YouTube Kids로
 * 옮겨 앉으면 사용시간이 아예 집계되지 않았고, 차단 오버레이도 뜨지 않았다. 목록을 상수 하나가
 * 아니라 순수 함수로 꺼내는 건 테스트로 고정하기 위해서다 — 여기서 패키지가 하나 빠지면 그
 * 앱은 조용히 무제한이 되고, 조용하다는 게 이 결함의 본질이었다.
 *
 * ## 목록에 **없는** 것들과 그 이유
 *
 * - **모바일 브라우저의 `m.youtube.com`**: 원천적으로 불가능하다. [android.app.usage.UsageStatsManager]가
 *   주는 건 패키지 이름과 전환 시각뿐이고 URL은 없다. 크롬을 통째로 감시 대상에 넣으면 유튜브를
 *   1분도 안 봐도 브라우저를 켠 시간이 한도에서 깎인다. 접근성 서비스를 쓰면 URL을 볼 수 있지만
 *   배터리를 이유로 배제한 결정이 documents/MOBILE_PLAN.md에 있다. 고칠 수 없으므로 **온보딩에
 *   사실대로 적는다**(`onboarding_coverage_note`).
 * - **리패키지 클라이언트(ReVanced 등)·서드파티 프론트엔드**: 패키지 이름을 사용자가 정할 수
 *   있어 열거가 불가능하다.
 * - **YouTube TV(`com.google.android.youtube.tv`)**: TV 런처 전용 빌드라 폰에는 설치되지 않는다.
 */

const val YOUTUBE_PACKAGE = "com.google.android.youtube"
const val YOUTUBE_KIDS_PACKAGE = "com.google.android.apps.youtube.kids"
const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

/** 설정과 무관하게 항상 감시하는 앱. 둘 다 "영상을 보는" 앱이라 한도의 정의에 그대로 맞는다. */
val ALWAYS_WATCHED_PACKAGES: List<String> = listOf(YOUTUBE_PACKAGE, YOUTUBE_KIDS_PACKAGE)

/**
 * 지금 감시해야 할 패키지들.
 *
 * **YouTube Music이 기본으로 빠져 있는 이유**: 확장 쪽 같은 문제(documents/QA_REVIEW.md §2.4)에서
 * "작업용 BGM 때문에 영상 한도가 깎이고, 한도를 다 쓰면 음악까지 차단되면 신뢰를 잃는다"고
 * 판단해 `music.youtube.com`을 화이트리스트에 넣을 수 있게 열어뒀다. 안드로이드도 같은 결론으로
 * 맞춘다 — 켜고 싶은 사람만 설정에서 켠다(`settings_watch_music`). 두 클라이언트가 같은 계정에서
 * 서로 다른 기본값을 쓰면 "폰에서만 한도가 빨리 닳는다"가 되므로, 이건 취향이 아니라 계약이다.
 */
fun watchedPackages(includeMusic: Boolean): List<String> =
    if (includeMusic) ALWAYS_WATCHED_PACKAGES + YOUTUBE_MUSIC_PACKAGE else ALWAYS_WATCHED_PACKAGES
