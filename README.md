# TabDeck Android

남는 안드로이드 폰·태블릿을 스트림 덱처럼 쓰게 해 주는 TabDeck의 **앱**이야.
같은 Wi-Fi에서 켜 둔 [TabDeck PC 프로그램](https://github.com/appie96/TabDeck-PC)과 연결해서, 버튼을 누르면 PC에서 동작이 실행돼.

## 기능
- PC 자동 찾기 (안 되면 PC IP 직접 입력)
- 비밀 키로 PC와 서로 확인 (HMAC-SHA256, 키는 네트워크로 보내지 않음)
- 버튼 이미지·아이콘·색은 PC 프로그램에서 편집하면 바로 반영
- 화면 꾸미기: 움직이는 배경, 내 사진·GIF 배경(위치·확대 조정), 유리 효과, 누를 때 효과 5종

## 빌드
Android Studio로 이 폴더를 열고 실행해. 명령줄로는:
```
./gradlew assembleDebug testDebugUnitTest
```
- 최소 Android 8.0 (API 26), 대상 API 37
- Android 17 이상에서는 같은 Wi-Fi 기기에 접속하려고 '근처 기기(로컬 네트워크)' 권한을 물어봐

## 사용한 것
- [OkHttp](https://square.github.io/okhttp/) (Apache 2.0)
- 아이콘 디자인: [Lucide](https://lucide.dev) (ISC 라이선스) — PC 프로그램에서 그려서 보냄
