# BoxAFK

Người chơi đứng trong vùng AFK sẽ nhận thưởng theo thời gian. Ai có nhiều tài khoản cùng một IP thì có thể liên kết
chúng lại: càng nhiều tài khoản trong nhóm đang online, thời gian chờ mỗi lần nhận thưởng càng ngắn.

Paper 1.21.1, Java 21. Cần WorldGuard. PlaceholderAPI không bắt buộc.

## Cách hoạt động

- Vùng AFK là region WorldGuard, khai báo trong `afk-regions` (có thể nhiều vùng, nhiều world).
- Cứ đủ `reward-interval` giây trong vùng thì nhận một lượt thưởng. Thanh actionbar hiện thời gian còn lại.
- Nhóm liên kết: tối đa `max-group-size` tài khoản, phải cùng IP. Số tài khoản trong nhóm đang online quyết định
  phần trăm rút ngắn (`group-reduction`). Mức giảm được chốt lúc bắt đầu đếm, thay đổi giữa chừng tính từ lượt sau.
- Mỗi IP chỉ có `max-rewarded-per-ip` tài khoản được nhận thưởng cùng lúc, kể cả khi không liên kết. Ai vào vùng sau
  thì chờ đến khi có chỗ trống.
- Liên kết gồm hai bước: người kia bấm `[Xác nhận]` trong chat, rồi gõ đúng con số hiện trên màn hình (captcha).
  Nếu IP hoặc nhóm thay đổi trong lúc chờ thì lời mời bị huỷ.
- Phần thưởng chia hai loại. `scaled` được cộng thêm theo rank (`rank-bonus`, lấy quyền cao nhất, không cộng dồn);
  phần lẻ được giữ lại cho lượt sau (10 + 15% = 11, rồi 12...). `fixed` là các lệnh console chạy nguyên văn.

Dữ liệu nhóm lưu trong `plugins/BoxAFK/data.db` (SQLite).

## Lệnh

| Lệnh | Quyền | Mô tả |
|---|---|---|
| `/afk` | - | Hiện hướng dẫn lệnh |
| `/afk link <tên>` | `boxafk.use` | Mời một tài khoản cùng IP vào nhóm |
| `/afk accept` / `/afk deny` | `boxafk.use` | Đồng ý / từ chối lời mời |
| `/afk unlink` | `boxafk.use` | Rời nhóm |
| `/afk info` | `boxafk.use` | Xem nhóm hiện tại |
| `/afk admin info <tên>` | `boxafk.admin` | Xem nhóm của người khác |
| `/afk admin unlink <tên>` | `boxafk.admin` | Gỡ người khác khỏi nhóm |
| `/afk reload` | `boxafk.admin` | Đọc lại config, kể cả danh sách vùng |

Quyền thưởng thêm theo rank (`boxafk.bonus.elite` ...) do bạn tự đặt tên trong `rank-bonus` của `config.yml`.

## Placeholder

| Placeholder | Giá trị |
|---|---|
| `%boxafk_in_zone%` | `true` / `false` |
| `%boxafk_time_left%` | Thời gian đến lượt thưởng tiếp theo (rỗng khi ngoài vùng hoặc đang chờ chỗ) |
| `%boxafk_group_size%` | Số tài khoản trong nhóm (tối thiểu 1) |
| `%boxafk_reduction%` | Phần trăm rút ngắn đang được hưởng |
| `%boxafk_bonus%` | Phần trăm thưởng thêm theo rank |
| `%boxafk_zone_count%` | Số người đang AFK trong các vùng |

## Build

```
./gradlew build
```

Ra file `build/libs/BoxAFK-<version>.jar`. Repo chỉ chứa mã nguồn; jar duy nhất có trong repo là
`gradle/wrapper/gradle-wrapper.jar` của Gradle.
