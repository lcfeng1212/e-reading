# BOOX Go 7 語音閱讀器

Android 13 / BOOX Go 7 開發預覽。書架右上角「＋」可選本機書檔與 Android 選檔器提供的 Google 雲端硬碟來源。可選一本直接開書，或多選加入書架。

- TXT 轉成 EPUB 後閱讀。EPUB、PDF、CBZ 可開啟，並保留位置與書籤。
- 無數位版權保護的 MOBI 以 libmobi 轉成 EPUB，包含常見的 MOBI／KF8 文字與圖檔。固定版面複製本與受保護的檔案不在支援範圍。
- 流式正文可切換繁簡，字形轉換使用 OpenCC 字典。Google ML Kit 英文、日文翻譯需要先在 Wi-Fi 下載模型。
- 閱讀頁點中央開工具列。在閱讀頁開啟語音後說「下一頁」或「上一頁」。離線語音模型為 Vosk 小型中文 0.22。
- Google Drive 入口取決於 Go 7 選檔器。若沒有提供，先在 Drive 下載後從「下載」選檔。

## 編譯與下載

[GitHub Actions](https://github.com/lcfeng1212/e-reading/actions/workflows/android.yml) 會安裝 Android SDK、取得離線模型與繁簡字典、編譯 APK。到成功的執行紀錄下載 `BOOX-Go7-e-reading`，解壓後安裝 `app-debug.apk`。APK 為開發測試版。

## MOBI 元件

[libmobi v0.12 原始碼與授權](https://github.com/bfabiszewski/libmobi/tree/v0.12)按 LGPL 3.0 或更新版本使用。編譯工作流程取得該原始碼、關閉加密功能並動態連結。APK 內附上游授權。可替換 `vendor/libmobi` 中的來源後以相同流程重建。

## Go 7 實機驗收

實體鍵碼、Drive 選檔、TXT 轉檔速度、EPUB 版型與續讀、MOBI 樣本、語音誤觸／延遲／耗電、離線翻譯品質都需要實機驗證。鎖屏後測語音是否停止，再回閱讀頁測恢復。

流式書籍支援全書搜尋。有文字層的 PDF 可翻本頁或擷取後選取文字。替換書檔時可保留上次閱讀位置，跨版次定位需要實機驗證。掃描 PDF 或漫畫的文字辨識留待後續。
