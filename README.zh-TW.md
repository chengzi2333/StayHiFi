<div align="center">
  <img src="docs/marketing/logo.png" width="128" alt="StayHiFi logo">
  <h1>StayHiFi 音質常駐</h1>
  <p><strong>藍牙耳機一連，音質自動拉滿——免 root，裝完就不用管</strong></p>
  <p><a href="README.md">簡體中文</a> | [繁體中文] | <a href="README.en.md">English</a> | <a href="README.ko.md">한국어</a></p>
  <p>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/v/release/chengzi2333/StayHiFi" alt="Release"></a>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/downloads/chengzi2333/StayHiFi/total" alt="Downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/chengzi2333/StayHiFi" alt="License"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-green" alt="Android 10+">
  </p>
</div>

## 為什麼需要 StayHiFi

廠商出於相容與功耗考量，藍牙編碼預設走保守參數（如 SBC / 48kHz / 自適應碼率）——花 HiFi 的錢，聽 MP3 的聲。想跑滿耳機的真實能力，只能每次連線後鑽進**開發者選項**手動調 codec / 取樣率 / 位元深度，而且**斷線重連、關掉開發者選項都會復原**，第二天一切重來。

StayHiFi 只做一件事：連上耳機的一瞬間，替你把參數寫回這台裝置協商得出的上限，全程無感。

## 螢幕截圖

<p>
  <img src="docs/marketing/screenshots/01-home.png" width="260" alt="首頁狀態卡">
  <img src="docs/marketing/screenshots/02-devices.png" width="260" alt="裝置頁">
  <img src="docs/marketing/screenshots/03-settings.png" width="260" alt="設定頁">
</p>

## 功能特性

- **連上即拉滿**：任何藍牙耳機零適配——目標參數按「耳機×手機×ROM」現場推導，寫入後讀回驗證
- **兩態狀態卡**：「已達本機最高音質輸出」/「未拉滿 · 點擊重試」，一眼可知
- **斷線重連自動再拉**：前景服務監聽連線事件，近零耗電，無輪詢空轉
- **常駐靜默通知**：可選即時顯示目前參數；另有 QS 快捷方塊一鍵查看
- **iOS 風浮動 Dock 導覽**：彈簧透鏡 + 果凍變形物理動效（移植自 FlClash，GPL 署名）
- **錄製精靈**（可選診斷工具）：抓一次目前協商參數存檔到裝置頁，附機型/Android 版本出處
- **Material You**：動態取色，深淺色主題隨系統

## 運作原理

透過 **Shizuku 或 Stellar**（任選其一）取得 shell 級特權，反射呼叫系統藍牙堆疊的隱藏介面 `setCodecConfigPreference()` 寫入 A2DP codec 設定。

- **不 patch 系統、不解鎖隱藏 codec**，不突破硬體已協商的能力上限
- 唯一的外部互動是與 Shizuku/Stellar 的本機 IPC，零網路請求

## 安裝

> ⚠️ 官方通路目前**僅有 GitHub Releases**（酷安上架中），其他來源的 APK 請勿安裝。

1. 安裝 **Shizuku** 或 **Stellar** 管理器，並依其指引啟動（無線偵錯 / 有線 adb，無需 root）
2. 從 [GitHub Releases](https://github.com/chengzi2333/StayHiFi/releases) 下載 APK——**單一包通用所有機型**，無需挑選 ABI 版本；頁面附 SHA-256 與簽名憑證指紋，安裝前可核對

要求：Android 10+。

## 使用

1. 首次開啟跟隨 **Onboarding 五步引導**：選擇特權載體 → 授權 → 連接耳機 → 選擇參數來源 → 常駐設定
2. 常駐「三道閘」（國產 ROM 殺背景嚴格，全做才穩）：
   - **允許自動啟動**（系統設定 → 應用程式管理）
   - **加入電池白名單**（關閉電池最佳化）
   - **背景高耗電 + 卡片鎖定**：設定 → 電池 → 背景耗電管理 中允許本應用程式；並在最近工作中下拉卡片點「鎖定」，防滑卡清理
3. 之後正常使用即可：耳機一連，參數自動拉滿；首頁狀態卡即時告訴你有沒有到頂

## 意見回饋與交流

裝置型號眾多、各家 ROM 差異大，難免有覆蓋不到的機器。遇到問題歡迎透過任一管道找我：

- **GitHub Issues**：回報 bug、提出需求的首選，附 App 內「日誌」頁內容最佳
- **酷安**：@一般社畜橙子（CoolApk 帳號）
- **小紅書**：@橙橙橙子Orenji（Xiaohongshu 帳號）

## 隱私

- **零資料蒐集**：所有資料只儲存在本機，無網路上傳、無統計埋點、無帳號體系
- 唯一的外部互動是與 Shizuku/Stellar 的本機 IPC

## 免責聲明

本專案與 **Shizuku、Stellar、OPPO、vivo** 及其關係企業無任何隸屬、贊助或認可關係；所有商標歸其各自所有者。僅在你自己的裝置上調整系統隱藏介面（反射呼叫）的參數，請自行評估風險。

## 開源許可

GPL-3.0-or-later，詳見 [LICENSE](LICENSE)。第三方元件宣告見 [NOTICE](NOTICE)，裝置檔案貢獻指引見 [CONTRIBUTING.md](CONTRIBUTING.md)。

原始碼可自由使用與修改，但 **「StayHiFi / 音質常駐」名稱與圖示不屬於授權範圍**——fork 或二次發布請更換名稱與圖示（參照 Shizuku / Seal 的通行做法）。
