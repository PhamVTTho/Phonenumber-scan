# Quét số điện thoại

Ứng dụng Android chụp ảnh trong bộ nhớ, nhận diện chữ bằng ML Kit trên thiết bị, lọc số điện thoại và mở số đã chọn trong ứng dụng gọi điện.

## Quyền riêng tư và pin

- Chỉ yêu cầu quyền `CAMERA`.
- Ảnh đi thẳng từ CameraX đến OCR trong RAM, sau đó `ImageProxy` được đóng; không ghi tệp và không xin quyền bộ nhớ.
- Mô hình OCR Latin được đóng gói trong APK, không cần tải ảnh lên máy chủ.
- Dùng `ACTION_DIAL`, không dùng quyền `CALL_PHONE`: người dùng vẫn chủ động bấm gọi.
- Camera được gắn với lifecycle và chủ động tháo khi ứng dụng rời foreground.
- Không có service, foreground service, receiver, WorkManager hay tác vụ chạy ngầm.
- Chỉ các dòng nằm trong dải quét giữa màn hình mới được đưa vào bộ lọc số.
- Camera lấy nét/đo sáng tại vùng quét và giảm phơi sáng nhẹ để chụp màn hình rõ hơn.

## Mở và chạy

1. Cài Android Studio Panda 1 (2025.3.1) hoặc mới hơn, JDK 17 và Android SDK 36.
2. Mở thư mục dự án này và chờ Gradle Sync hoàn tất.
3. Chạy trên điện thoại Android 6.0 (API 23) trở lên. Thiết bị thật cho kết quả camera tốt nhất.

## Luồng sử dụng

1. Cho phép camera.
2. Đưa số điện thoại vào khung và chọn **Chụp và quét**.
3. Chạm vào số nhận diện được để mở màn hình quay số.
4. Quay lại ứng dụng và chọn **Chụp lại** cho lần tiếp theo.

Khi chụp từ một màn hình khác, nên giảm độ sáng của màn hình nguồn và nghiêng điện thoại
nhẹ nếu thấy vùng phản chiếu màu trắng. Có thể chạm trực tiếp lên số để lấy nét trước khi chụp.
