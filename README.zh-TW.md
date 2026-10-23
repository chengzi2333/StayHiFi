[简体中文](README.md) | [繁體中文] | [English](README.en.md) | [한국어](README.ko.md)

# StayHiFi 音質常駐

**一次設定，持續生效。**

連上耳機後，自動把藍牙音訊（A2DP codec）參數拉到這台裝置協商得出的上限——全程無感，不用再每次進開發者選項手動調。

（傳播語：耳機一連，音質滿血。）

## 為什麼會有這個 App

廠商出於相容與功耗考量，藍牙編碼預設走保守參數（如 SBC / 48kHz / 自適應位元率）。想跑滿耳機的真實能力，只能：

- 每次連線後鑽進 **開發者選項** 手動調 codec / 取樣率 / 位元深度；
- 而且 **斷線重連、關掉開發者選項都會復原**，第二天一切重來。

StayHiFi 只做一件事：連上耳機的一瞬間，替你把參數寫回去。

## 原理

透過 **Shizuku 或 Stellar**（任選其一）取得 shell 級權限，呼叫系統藍牙堆疊的隱藏介面 `setCodecConfigPreference()`（反射呼叫）寫入 A2DP codec 設定。

- **不 patch 系統、不解鎖隱藏 codec**，不突破硬體已協商的能力上限
- 常駐前景服務監聽連線事件，近零額外耗電（無輪詢迴圈）
- 沒有現成參數範本的裝置，可用 App 內的「錄製精靈」抓一次目前參數，存成專屬範本

## 截圖

<img src="docs/marketing/screenshots/01-home.png" width="280" alt="首頁狀態卡">
<img src="docs/marketing/screenshots/02-devices.png" width="280" alt="裝置頁">
<img src="docs/marketing/screenshots/03-settings.png" width="280" alt="設定頁">

## 安裝

官方通路僅此兩處（酷安 / GitHub），其他來源的 APK 請勿安裝：

- **酷安**：應用市場內搜尋「音質常駐」
- **GitHub Releases**：<https://github.com/chengzi2333/StayHiFi/releases>（頁面附 APK SHA-256，安裝前請核對）

前置條件：安裝 **Shizuku** 或 **Stellar** 管理器，並依其指引啟用（無線偵錯 / 有線 adb 啟動，無需 root）。

## 使用

1. 首次開啟跟隨 **Onboarding 五步引導**：選擇特權載體（Shizuku/Stellar）→ 授權 → 連接耳機 → 選擇參數來源 → 常駐設定
2. 常駐設定「三道閘」（國產 ROM 殺背景嚴格，全做才穩）：
   - **允許自動啟動**（系統設定 → 應用程式管理）
   - **加入電池白名單**（關閉電池最佳化）
   - **背景高耗電 + 卡片鎖定**：設定 → 電池 → 背景耗電管理 中允許本應用程式；並在最近工作中下拉卡片點「鎖定」，防滑卡清理
3. 之後正常使用即可：耳機一連，參數自動拉滿；通知欄常駐一條靜默通知（可選顯示即時參數）

新裝置適配：不需要任何範本——拉滿目標由 App 按耳機×手機×ROM 現場推導（各編碼器內建最佳檔位表），寫入後讀回驗證。Onboarding 裡的「錄製精靈」是診斷工具，抓一次目前參數存作本機記錄（不參與拉滿決策）。

## 隱私

- **零資料蒐集**：所有資料只儲存在本機，無網路上傳、無統計埋點、無帳號體系
- 唯一的外部互動是與 Shizuku/Stellar 的本機 IPC

## 免責聲明

本專案與 **Shizuku、Stellar、OPPO、vivo** 及其關係企業無任何隸屬、贊助或認可關係；所有商標歸其各自所有者。僅在你自己的裝置上調整系統隱藏介面（反射呼叫）的參數，請自行評估風險。

## 開源

GPL-3.0-or-later，詳見 [LICENSE](LICENSE)。第三方元件宣告見 [NOTICE](NOTICE)。裝置檔案貢獻指引見 [CONTRIBUTING.md](CONTRIBUTING.md)。
