# 打卡状态（daka-status）

企业微信上下班打卡状态小组件。本地运行、静默读取（root + 虚拟屏 + 无障碍），不做自动打卡。
唯一的联网行为：从自有服务器下载法定节假日表（判定休息日）；离线时有内置表 + 周末规则兜底。

## 构建

```
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物 `daka-status.apk`（versionCode = epoch 秒，versionName = 1.0.yyMMdd.HHmm）。
需要 Android SDK build-tools 35 + JBR（路径见 build.ps1 顶部）。

## 安装 / 更新

```
adb -s <serial> install -r daka-status.apk

# install 会取消全部闹钟，装完必须重新武装（或打开一次 App）：
adb -s <serial> shell su -c "am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -n com.daka.status/.BootReceiver -f 32"
```

部分厂商系统会在更新或强停后关闭无障碍；更新后应在主页确认连接状态。

## 运维注意

- 若 App 被"强行停止"（手动或系统省电策略），系统会同时清空全部闹钟：提醒、自动刷新、02:00 日切会静默停摆；重启手机也不会自愈（强停状态下收不到开机广播）。此时**打开一次 App 即全部恢复**（自动重排闹钟并刷新数据）。小组件时间戳显示 **"已过期 M-d"** 即代表数据陈旧。
- 建议在系统设置中为"打卡状态"开启**自启动**许可、电池策略设为**不限制**，减少被系统杀停的概率。
- 时间/时区变更会自动重排全部闹钟（监听 TIME_SET / TIMEZONE_CHANGED）。
- 已知限制：企业微信打卡页组件名为硬编码，企微大版本改版可能导致读取失败（App 会显示具体错误信息）。
- 提醒是"闹钟级"的：用 `AlarmManager.setAlarmClock` 排程（Doze / 省电策略下不延迟，状态栏会显示闹钟图标），到点全屏弹出 + 铃声 + 震动。
- 响铃由独立 `mediaPlayback` 前台服务使用 `USAGE_ALARM` 播放，不依赖弹窗成功显示。优先系统闹钟铃声，读取或播放失败时回退内置提示音。响铃最多 2 分钟、震动最多 90 秒；点击提醒按钮或通知中的“停止响铃”可停止。按系统允许的音量控制临时提高闹钟音量；停止时恢复原值，用户期间自行调音量时保留用户选择。勿扰是否允许闹钟仍由系统设置决定。
- 无障碍服务被 OEM 杀进程后会留在系统 `Crashed services` 里且**不会自动重绑**。App 在每次刷新前和打开时用 root 重写无障碍开关强制重绑（5 分钟节流），无需手动去设置里开关。
- 日报：企微"汇报"是单实例 Flutter 页面，一旦在主屏存在实例就会强制跳回主屏，无法在虚拟屏静默读取（强行读会把企微弹到前台）。因此 App 内提供"日报 → 打开汇报"一键入口，直达"汇报"页查看是否已提交。
- 早卡时间：企微在打完下班卡后，打卡页只显示最近一次（下班）时间。早卡时间只有在"早卡之后、晚卡之前"成功读取过一次才会保留显示。

## 节假日数据（服务器侧）

- 手机端主源：`http://81.70.63.171/downloads/holiday_days.json`（备源 timor.tech）
- 数据源：NateScarlet/holiday-cn（源自国办公告），由维护者服务器定时自动重新生成（2027 年安排公布后会自动纳入）

格式：`{"YYYY-MM-DD": 2|3}`，2 = 节日休息、3 = 调休补班；普通周末不在表内（按星期判定）。

## 调试

### 本地考勤数据库优先读取（2026-10-26）

- 已在企业微信 Android 4.1.27 真机验证：`Profiles/<profile>/attendance/attendance.db` 的 `Attendence_Furture4` 保存按天的二进制明细。`data` 声明为文本但实际是 protobuf，必须 `CAST(data AS BLOB)` 读取。
- 当前采用 root 在虚拟屏进入“打卡 → 统计”，让企微刷新本地明细；复制数据库及 WAL，只在本应用缓存中解码并只读查询。原企微数据库不改写。
- 数据格式兼容 wxSQLite3 AES128，算法来源与 MIT 许可证见 `THIRD_PARTY_NOTICES.md`（亦随 APK 资源附带）。发现账号/配置文件不唯一、解密/校验失败时回退，不猜测账号。
- 必须校验新缓存的 `fetch_time`，只接受本次请求后更新的数据；校验记录日期、账号、班次和实际打卡时间，不把计划时间或“尚未打卡”占位记录认作已完成。
- 页面采集只用于导航和兼容回退。数据库结果优先于页面文字，被动页面事件不会覆盖已确认的数据库记录。
- Android 的应用数据隔离会让普通 `su` 看不到其他应用的数据；本地文件复制使用 Magisk 的 master mount namespace。导航仍使用原有方式。
- 屏幕关闭/锁屏时无法让企微刷新统计缓存，解锁后再尝试。此方案仍依赖当前企微缓存格式，并非官方接口；不是任意版本、任意账号结构均可用。企微升级导致格式变化时会回退。
- 真机已读取 2026-10-26 上班 07:55:47、下班 19:03:39，两条均有账号、日期和班次字段。测试脚本新增 10 项本地记录断言；另用真机副本验证完整 WAL 解码与独立 Python 解码结果逐字节一致。

### 2026-10-26 修复

- 状态采集只采用可见节点，并避免混合多个窗口。当前“下班打卡”按钮或“上班自动打卡·正常”优先于残留的全天完成文字；单独的“更新下班卡”不再作为完成证据。提示时间、班次时间、按钮时钟与实际打卡时间分别处理。
- 已补充六张参考图的文字场景及固定时钟回归测试。第三张图显示的是已完成的上一考勤日，截图拍摄时间 02:30 已超过本应用 02:00 日切，应归为上一日记录。
- 在原有刷新窗口、工作日及自动刷新开关约束内，每次开屏触发刷新；锁屏快速跳过，解锁立即重试。保留用户可配置的刷新间隔；亮屏期间增加应用内计时兜底，避免完全依赖厂商可能延后的系统广播。
- 闹钟广播先根据缓存状态立即提醒，再异步刷新，提醒不再等待页面读取。并发服务请求排队，不能因刷新正忙而停止服务或丢掉提醒。打开主页不再取消“稍后提醒”。
- 修复下班时间未保存；移除排程中提前 5 秒跳到次日的边界错误。监听精确闹钟授权变化，授权后重新排程。
- 更新首次清除旧识别器的状态缓存，保留班次、刷新间隔和提前提醒量。后续更新保留新识别器缓存。
- 无障碍服务须保持启用。熄屏/锁屏不会主动点亮或解锁手机；解锁后再读取。系统强停、撤销权限或厂商后台限制仍可能中断运行。
- root 调试：`am start-foreground-service -n com.daka.status/.RefreshService -a com.daka.status.action.TIMINGTEST`，15 秒后走正式提醒相同的 AlarmClock/Receiver 路径，只记录投递延迟，不响铃、不更改提醒配置。

- 关键日志 tag：`DakaVD`
- prefs：`/data/data/com.daka.status/shared_prefs/daka.xml`（键：s_m/s_e/s_mt/s_et/s_up/s_note/s_err/h_type_*/h_sync…）
- 刷新窗口：02:00-11:00 与 17:00-01:00；11:00-17:00 与 01:00-02:00 停刷；休息日不刷
- 提醒：同一提醒间隔用于上班提前、下班延后。例如 08:30 / 18:00、间隔 30 分钟，对应 08:00 / 18:30。刷新补提醒使用同一时间规则，不会在下班延后提醒点之前补响。上班补提醒截止上班后 60 分钟，下班补提醒截止延后提醒点后 60 分钟；每个工作日每个班次最多一次，手动“稍后”除外。跨午夜按班次所属日期判断节假日。
- 闹钟排程可用 `adb shell dumpsys alarm | grep com.daka.status` 查看：`REMIND_*` 应为 `windowLength 0`（精确）、`type=RTC_WAKEUP`、`exactAllowReason=permission`
- 调试动作（root）：`am start-foreground-service -n com.daka.status/.RefreshService -a com.daka.status.action.ALERTTEST --es shift evening` 立即触发一次提醒（响约 2 分钟，点"知道了"停止）；`...action.REFRESH --es mode manual` 立即刷新
- 日报入口：App 内"日报"卡片 = 一键打开企微汇报页（不静默读取）
- 小组件时间戳：当天更新显示 `HH:mm`，跨天显示 `M-d HH:mm`，超过 26 小时未更新显示 `已过期 M-d`

## 离线测试

```
powershell -File tests\run-tests.ps1
```

解析器回归（TestDaka：三张真机截图场景；TestStale：凌晨 00:00-01:59 陈旧数据边界；TestWidget：小组件时间戳/过期标记）。
结果输出到 `tests\result-daka.txt` / `tests\result-stale.txt` / `tests\result-widget.txt`。注意陈旧归零类用例与运行时刻相关。

## 签名

`daka.keystore`（密码在 build.ps1 内）是应用升级的唯一签名凭据，请离线备份。

## 2026-11-03 闹钟修复

- 修正下班提醒减去间隔的错误，统一排程和刷新补提醒的计算。设置页明确标注“上班提前、下班延后”。
- 响铃和震动从 Activity 移入独立前台服务；弹窗被遮挡或销毁仍可继续响铃，提供通知停止入口和最长时限。
- 新增 `tests/TestReminderTime.java` 的 29 项固定时间断言，覆盖全部间隔选项、分钟进借位、补提醒边界、跨午夜。
- Root 调试 `...RefreshService -a com.daka.status.action.RINGTEST --es shift evening`：排一个 15 秒后的测试闹钟，走 AlarmClock → Receiver → 响铃服务/弹窗；不改班次、提醒间隔或每日去重记录。测试可能排在正在进行的刷新之后。已完成打卡时弹窗会按既有规则自动关闭。
