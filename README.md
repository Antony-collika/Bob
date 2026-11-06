# Bob — Ứng dụng chat AI trên Android (BYOK)

Bob là ứng dụng chat AI chạy trên điện thoại Android. Bạn tự nhập API key của mình
(BYOK — Bring Your Own Key) rồi trò chuyện với Claude, OpenAI (ChatGPT), Gemini
hoặc một nhà cung cấp tương thích OpenAI do bạn tự cấu hình. Mọi tin nhắn, API key
và ghi chú đều chỉ lưu trên máy của bạn.

## Bob làm được gì

- **Chat AI:** nhắn tin, AI trả lời theo thời gian thực (streaming), hiển thị
  Markdown đẹp, có nút **Dừng** giữa chừng, gửi ảnh từ thư viện hoặc chụp ảnh mới.
- **Nhiều nhà cung cấp:** Claude, OpenAI, Gemini và mục tùy chỉnh tương thích OpenAI.
  API key được mã hóa và lưu trên máy.
- **Lịch sử trò chuyện:** tạo, đổi tên, xóa cuộc trò chuyện. Mỗi cuộc trò chuyện là
  một đường thẳng. Tính năng **Branch** (tách nhánh sang cuộc mới) sao chép từ đầu
  đến tin nhắn bạn chọn, kể cả ảnh, cuộc gốc giữ nguyên.
- **Sửa / tạo lại tin nhắn:** bấm giữ (hoặc bấm nút ⋮) trên một tin nhắn để Copy,
  Edit, Edit & Regenerate, Regenerate, Delete. Tin nhắn đã sửa có nhãn nhỏ
  "đã chỉnh sửa" và được dùng làm ngữ cảnh cho lần chat sau. Xóa lượt có nút
  "Hoàn tác" trong vài giây. Khi tạo lại một lượt không phải lượt cuối, app hỏi
  bạn có muốn tạo nhánh khác không.
- **Công cụ cho AI:** AI có thể đọc/ghi file trong thư mục workspace bạn chọn,
  làm toán, xem giờ, đọc trang web, tìm kiếm web, ghi chú, danh sách việc cần làm,
  đặt báo thức, tạo sự kiện lịch. Bạn quản lý từng công cụ theo mã UID: bật/tắt,
  đổi tên, đổi mô tả cho AI, chọn mức kiểm soát (Tự động / Hỏi trước khi làm / Tắt).
  Thao tác xóa luôn hỏi xác nhận.
- **Thư mục workspace:** bạn chọn một thư mục trên máy làm workspace. AI chỉ được
  làm việc trong thư mục đó, không ra ngoài được. Chưa chọn thư mục thì các công cụ
  file sẽ bị ẩn khỏi AI và app hiện nút để bạn chọn thư mục.
- **Tìm kiếm:** tìm trong tất cả cuộc trò chuyện (bấm kết quả để mở đúng cuộc và
  cuộn tới đúng tin nhắn) và tìm trong từng cuộc trò chuyện (tô sáng mọi chỗ khớp,
  nút lên/xuống để nhảy giữa các kết quả).
- **Giao diện:** sáng/tối, nhanh, mượt, hiện đại.

## Lấy API key ở đâu

Bạn cần có key của ít nhất một nhà cung cấp. Key chỉ lưu trên máy bạn, không gửi
đi đâu ngoài nhà cung cấp đó.

| Nhà cung cấp | Lấy key ở đâu |
| --- | --- |
| Claude (Anthropic) | Vào https://console.anthropic.com → đăng nhập/đăng ký → mục **API Keys** → **Create Key**, sao chép key (bắt đầu bằng `sk-ant-...`). |
| OpenAI (ChatGPT) | Vào https://platform.openai.com/api-keys → **Create new secret key**, sao chép key (bắt đầu bằng `sk-...`). Cần nạp tiền vào tài khoản OpenAI thì key mới dùng được. |
| Gemini (Google) | Vào https://aistudio.google.com/app/apikey → **Create API key**, sao chép key. |
| Tùy chỉnh (tương thích OpenAI) | Dùng key và địa chỉ máy chủ (base URL) do nhà cung cấp đó cho bạn, nhập vào mục **Tùy chỉnh** trong Cài đặt. |
| Tìm kiếm web (không bắt buộc) | Cần key của dịch vụ tìm kiếm Tavily: https://tavily.com → đăng ký → lấy key, nhập vào ô **Search API key** trong Cài đặt. Không có key này thì công cụ tìm kiếm web sẽ báo lỗi, các tính năng khác vẫn chạy bình thường. |

> **Lưu ý:** API key giống như mật khẩu — đừng gửi cho ai, đừng đăng lên mạng.

## Cách 1 — Cài APK từ GitHub Actions (không cần máy tính mạnh, dễ nhất)

1. Mở repo **Bob** trên GitHub bằng trình duyệt, vào tab **Actions**.
2. Chọn lần chạy (workflow run) mới nhất của workflow **Build APK** trên nhánh `dev`
   (mỗi lần có code mới đẩy lên nhánh `dev`, GitHub sẽ tự build).
3. Đợi tới khi lần chạy hiện dấu tick xanh (thường vài phút).
4. Kéo xuống cuối trang lần chạy, mục **Artifacts**, tải file **bob-debug-apk** về.
5. Giải nén file vừa tải, bạn sẽ có file `app-debug.apk`.
6. Chép file `.apk` vào điện thoại (qua cáp USB, Zalo, Drive...), mở file trên điện
   thoại và bấm **Cài đặt**. Nếu điện thoại hỏi "Cho phép cài đặt từ nguồn không
   xác định", bấm **Cho phép** cho ứng dụng bạn đang dùng để mở file (ví dụ Chrome
   hoặc Trình quản lý file), rồi bấm cài đặt lại.

## Cách 2 — Build bằng Android Studio trên máy tính

1. **Cài Android Studio:** tải tại https://developer.android.com/studio , cài đặt
   bình thường. Lần đầu mở Android Studio, nó sẽ tự tải Android SDK (cần mạng,
   chờ vài phút).
2. **Lấy code:** mở Android Studio → **Get from VCS** (hoặc **File → New →
   Project from Version Control**) → dán link repo
   `https://github.com/Antony-collika/Bob` → chọn nhánh `dev` → **Clone**.
   (Hoặc tải ZIP của nhánh `dev` trên GitHub rồi giải nén, sau đó mở thư mục bằng
   Android Studio.)
3. **Đợi đồng bộ:** Android Studio sẽ tải Gradle và các thư viện (lần đầu hơi lâu,
   khoảng 5–15 phút tùy mạng). Đợi tới khi góc dưới hiện "Gradle sync finished"
   và không còn báo lỗi đỏ.
4. **Chạy thử trên điện thoại thật:**
   - Trên điện thoại: vào **Cài đặt → Giới thiệu về điện thoại**, bấm 7 lần vào
     **Số phiên bản (Build number)** để bật **Tùy chọn nhà phát triển**.
   - Vào **Cài đặt → Tùy chọn nhà phát triển**, bật **Gỡ lỗi USB (USB debugging)**.
   - Cắm điện thoại vào máy tính bằng cáp USB, trên điện thoại bấm **Cho phép**
     khi hỏi "Cho phép gỡ lỗi USB?".
   - Trong Android Studio, chọn tên điện thoại của bạn ở thanh công cụ trên cùng,
     bấm nút ▶ **Run**. App sẽ được cài và mở trên điện thoại.
5. **Xuất file APK để cài cho máy khác:** trong Android Studio, vào menu
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Đợi báo "Build
   finished", bấm **locate** trong thông báo để mở thư mục chứa file
   `app-debug.apk` (thường nằm ở `app/build/outputs/apk/debug/`).

## Cách 3 — Build bằng dòng lệnh (cho người quen dùng terminal)

```bash
git clone -b dev https://github.com/Antony-collika/Bob.git
cd Bob
./gradlew assembleDebug
# File APK: app/build/outputs/apk/debug/app-debug.apk
```

Cần có JDK 17 và Android SDK (biến môi trường `ANDROID_HOME` trỏ tới thư mục SDK).
Nếu chưa có, cách 2 (Android Studio) sẽ tự lo phần này.

## Dùng app lần đầu

1. Mở app **Bob**, bấm biểu tượng **Cài đặt** (⚙️).
2. Chọn nhà cung cấp (ví dụ Claude), dán **API key** của bạn vào, chọn mô hình,
   bấm lưu. Lặp lại cho nhà cung cấp khác nếu muốn.
3. (Không bắt buộc) Nhập **System prompt** — câu hướng dẫn chung cho AI, ví dụ
   "Trả lời ngắn gọn bằng tiếng Việt".
4. Chọn **thư mục workspace**: trong Cài đặt, bấm **Chọn thư mục**, chọn một thư
   mục trên máy (ví dụ tạo thư mục `BobWorkspace`). AI chỉ được đọc/ghi trong
   thư mục này.
5. Quay lại màn hình chính, bấm **+** để tạo cuộc trò chuyện mới, chọn nhà cung
   cấp và mô hình, rồi nhắn tin.
6. Muốn AI dùng công cụ (đọc/ghi file...), chỉ cần nhắn bình thường — ví dụ
   "Đọc file ghi-chu.txt trong workspace và tóm tắt giúp mình". Khi AI muốn ghi,
   sửa hoặc xóa file, app sẽ hỏi bạn xác nhận trước (trừ các thao tác chỉ đọc).
7. Quản lý công cụ: vào mục **Công cụ** (🔧) để bật/tắt từng công cụ, đổi tên hoặc
   đổi mô tả mà AI nhìn thấy, chọn mức kiểm soát.

## Quyền app sẽ xin (và vì sao)

- **Ảnh / Camera:** để gửi ảnh cho AI.
- **Thông báo:** để báo thức kêu đúng giờ.
- **Lịch:** để tạo sự kiện lịch khi bạn nhờ AI đặt lịch.
- **Bộ nhớ (qua hộp chọn thư mục):** để AI đọc/ghi file trong workspace bạn chọn.

## Cấu trúc mã nguồn

```
app/                  Module ứng dụng Android (Kotlin + Jetpack Compose)
  src/main/java/com/bob/ai/
    domain/           Model, ChatEngine, TokenEstimator (luật nghiệp vụ)
    data/             Room (lịch sử chat), DataStore + EncryptedSharedPreferences
                      (cài đặt, API key), Providers (Claude/OpenAI/Gemini/Custom),
                      Tools (file, text, daily), Workspace (SAF)
    di/               AppContainer (nối các thành phần lại)
    ui/               Màn hình Compose: danh sách chat, chat, cài đặt, công cụ,
                      tìm kiếm; Markdown, bong bóng tin nhắn, thẻ gọi công cụ
  src/main/res/       Giao diện, chuỗi, icon
.github/workflows/   Build APK tự động mỗi khi đẩy code lên nhánh dev
```

## Giấy phép

Mã nguồn thuộc về chủ repo. API key và dữ liệu của bạn chỉ lưu trên thiết bị.
