<div align="center">
  <img src="docs/marketing/logo.png" width="128" alt="StayHiFi logo">
  <h1>StayHiFi</h1>
  <p><strong>블루투스만 연결하면 음질이 최대치로 — 루트 불필요, 한 번 설치하면 끝</strong></p>
  <p><a href="README.md">简体中文</a> | <a href="README.zh-TW.md">繁體中文</a> | <a href="README.en.md">English</a> | [한국어]</p>
  <p>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/v/release/chengzi2333/StayHiFi" alt="Release"></a>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/downloads/chengzi2333/StayHiFi/total" alt="Downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/chengzi2333/StayHiFi" alt="License"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-green" alt="Android 10+">
  </p>
</div>

## 왜 StayHiFi가 필요한가

제조사는 호환성과 전력 소모를 고려해 블루투스 코덱을 보수적인 기본값으로 설정합니다(SBC / 48kHz / 적응형 비트레이트)——HiFi 값을 내고 MP3 소리를 듣는 셈입니다. 헤드폰의 실제 성능을 최대로 끌어쓰려면 연결할 때마다 **개발자 옵션**에 들어가 코덱 / 샘플링 레이트 / 비트 뎁스를 수동으로 조정해야 하고, **연결 해제 시나 개발자 옵션을 닫으면 설정이 되돌아가**므로 매일 다시 시작해야 했습니다.

StayHiFi는 정확히 한 가지만 합니다. 헤드폰이 연결되는 순간, 파라미터를 이 기기가 협상해 낸 상한까지 되돌려 써 넣는 것——전 과정이 무감각하게 진행됩니다.

## 스크린샷

<p>
  <img src="docs/marketing/screenshots/01-home.png" width="260" alt="홈 상태 카드">
  <img src="docs/marketing/screenshots/02-devices.png" width="260" alt="기기 페이지">
  <img src="docs/marketing/screenshots/03-settings.png" width="260" alt="설정 페이지">
</p>

## 주요 기능

- **연결 즉시 최대치**: 어떤 블루투스 헤드폰과도 별도 적응 불필요——목표 파라미터는 헤드폰 × 휴대폰 × ROM 조합에 맞춰 그 자리에서 파생되며, 기록 후 다시 읽어 검증합니다
- **두 가지 상태 카드**: 「본 기기 최고 음질 출력 도달」/ 「아직 최대치가 아님 · 탭하여 재시도」——한눈에 확인
- **재연결 시 자동 재설정**: 포그라운드 서비스가 연결 이벤트만 감시하므로 배터리 소모가 거의 없습니다(폴링 루프 없음)
- **상시 무음 알림**: 옵션으로 실시간 파라미터 표시; QS 타일로 한 번에 확인 가능
- **iOS 스타일 플로팅 Dock 네비게이션**: 스프링 렌즈 + 젤리 변형 물리 모션
- **기록 마법사**(선택 진단 도구): 현재 협상된 파라미터를 한 번 캡처해 기기 페이지에 보관하고, 기종/Android 버전 출처를 첨부
- **Material You**: 동적 색상 추출, 라이트/다크 테마가 시스템을 따름

## 작동 원리

**Shizuku 또는 Stellar**(둘 중 하나)를 통해 shell 수준 권한을 얻은 뒤, 시스템 블루투스 스택의 숨겨진 `setCodecConfigPreference()` 인터페이스를 **리플렉션으로 호출**하여 A2DP 코덱 설정을 기록합니다.

- 시스템을 **패치하지 않고**, 숨겨진 코덱을 해금하지 않으며, 하드웨어에서 협상된 능력 상한을 넘기지 않습니다
- 기능상 유일한 외부 상호작용은 Shizuku/Stellar와의 로컬 IPC이며, 유일한 네트워크 요청은 GitHub Releases 버전 확인입니다

## 설치

> ⚠️ 현재 공식 배포 채널은 **GitHub Releases**뿐입니다(쿨안 등록 진행 중). 다른 출처의 APK는 설치하지 마십시오.

1. **Shizuku** 또는 **Stellar** 매니저를 설치하고 안내에 따라 활성화하십시오(무선 디버깅 / 유선 adb, 루트 불필요)
2. [GitHub Releases](https://github.com/chengzi2333/StayHiFi/releases)에서 APK 다운로드——**단일 패키지로 모든 기기에 통용**되므로 ABI 버전을 고를 필요가 없습니다. 릴리스 페이지에 SHA-256과 서명 인증서 지문이 첨부되어 있으니 설치 전 확인하십시오

요구 사항: Android 10+.

## 사용 방법

1. 처음 실행 시 **온보딩 5단계 가이드**를 따라 하십시오. 권한 캐리어 선택(Shizuku / Stellar) → 권한 부여 → 헤드폰 연결 → 파라미터 출처 선택 → 상시 유지 설정
2. 상시 유지 「세 가지 문턱」(중국산 ROM의 백그라운드 강제 종료가 엄격하므로, 모두 설정해야 안정적입니다):
   - **자동 실행 허용**(시스템 설정 → 애플리케이션 관리)
   - **배터리 화이트리스트 등록**(배터리 최적화 끄기)
   - **백그라운드 높은 전력 사용 허용 + 최근 앱 카드 고정**: 설정 → 배터리 → 백그라운드 전력 관리에서 이 앱을 허용하고, 최근 앱 화면에서 카드를 아래로 끌어 「잠금」을 눌러 밀어서 종료되는 것을 방지하십시오
3. 그 이후는 평소처럼 사용하시면 됩니다. 헤드폰이 연결되면 파라미터가 자동으로 최대치로 맞춰지고, 홈 상태 카드가 실시간으로 최고 음질에 도달했는지 알려줍니다

## 피드백과 소통

기기 모델이 워낙 많고 제조사별 ROM 차이가 커서, 커버되지 않는 기기가 나올 수밖에 없습니다. 문제가 생기면 아무 채널로든 편하게 연락 주십시오.

- **GitHub Issues**: 버그 리포트와 기능 요청의 가장 좋은 창구입니다. 앱 내 「로그」 페이지 내용을 첨부해 주시면 가장 큰 도움이 됩니다
- **쿨안(CoolApk)**: @一般社畜橙子
- **샤오홍슈(Xiaohongshu)**: @橙橙橙子Orenji

## 개인정보 보호

- **데이터 수집 제로**: 모든 데이터는 본 기기에만 저장되며, 네트워크 업로드·통계 수집·계정 체계가 없습니다
- **유일한 네트워크 요청**은 GitHub Releases 버전 확인입니다(버전 번호 비교만 수행하며, 어떠한 기기 정보도 싣지 않습니다. 하루 최대 한 번 자동으로 조용히 확인하며, 설정 페이지에서 수동으로도 확인할 수 있습니다)
- 그 외 유일한 외부 상호작용은 Shizuku/Stellar와의 로컬 IPC입니다

## 면책 조항

본 프로젝트는 **Shizuku, Stellar, OPPO, vivo** 및 그 계열사와 아무런 소속·후원·공인 관계가 없으며, 모든 상표는 각 소유자의 것입니다. 본 앱은 **본인 기기에서만** 시스템 숨겨진 인터페이스(리플렉션 호출)의 파라미터를 조정합니다. 리스크는 스스로 평가하십시오.

## 라이선스

GPL-3.0-or-later, 자세한 내용은 [LICENSE](LICENSE)를 참조하십시오. 제3자 구성 요소 명세는 [NOTICE](NOTICE)에, 기기 프로파일 기여 가이드는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.

소스 코드는 자유롭게 사용하고 수정할 수 있지만, **「StayHiFi」 이름과 아이콘은 라이선스 부여 대상이 아닙니다**——fork나 재배포 시 이름과 아이콘을 변경해 주십시오(Shizuku / Seal의 통용 관행과 동일).
