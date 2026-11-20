# 华米数据提取器 (Huami Extractor)

从 **Zepp / 华米手表** 的 Zepp App 沙箱里提取真实数据，解析成可用的训练依据。

## 它解决什么

| 数据 | 状态 |
|---|---|
| 力量训练逐组（组数 / 每组次数 / 动作码 / 组间休息 / 运动负荷 / TE） | ✅ 自动提取 |
| 睡眠分期（浅睡 / 深睡 / REM / 清醒，逐段） | ✅ 自动提取 |
| 夜间心率、血氧 | ✅ 自动提取 |
| 重量 (kg) | ❌ 手表自由训练模式不记录重量（用模板模式才有） |

## 技术要点

- **不需要解密**。Zepp 库文件头是 `SQLite format 3`，**明文**，直接用 Android 原生 `SQLiteDatabase` 读。
- **需要跨应用读取权限**。Zepp 数据在 `/data/data/com.huami.watch.hmwatchmanager/databases/`，
  属于 Zepp App 私有沙箱，普通 App 读不到。
  本 App 通过 **Shizuku** 借系统 root 权限把库拷出来（不修改原库，只读）。
- **WAL 模式**：必须连 `-wal` / `-shm` 一起拷，否则数据不全或损坏。

## 前置条件

1. 手机已 root（Shizuku 以 uid=0 运行，Magisk 环境直接给 root）
2. Shizuku 已启动
3. 首次使用点「检查 Shizuku」授权

## 用法

1. 「检查 Shizuku」→ 授权
2. 「拉取并分析」→ 拷贝数据库 + 解析 + 生成恢复评估与今日课表
3. 「导出 JSON」→ 分享到任意应用

## 训练计划逻辑

基于谭成义 × 凯圣王三分化框架：

- 推 / 拉 / 腿 循环，5 天工作日 PPL 交替，保证每肌群两周 3-4 次
- 大肌群 4-6 组、小肌群 2-4 组，总组数 ≤ 20，单次 ≤ 60 分钟
- 强度用 RPE 区间，不用固定 %1RM
- 渐进：次数达标 → 加重量；掉组数 → 减载
- **恢复闸门**：拿近 28 天睡眠做个人基线
  - 少睡 ≥ 90 分钟 或 深睡降 ≥ 30% → 主项减 1 组、RPE 上限压到 7.5
  - 连续两晚不达标 → 不加重量
  - 连续三晚不达标 → 建议改轻量恢复

## 构建

GitHub Actions 云构建（`Actions → Build APK → Run workflow`），
产物在 Artifacts 里下载 `huami-extractor-debug-apk`。

需要签名包时，在仓库 Settings → Secrets 添加：
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

## 数据来源

- `HealthMatrix_<uid>.db` → `Samples` / `CategorySamples` / `ExtraDataValues`
  - `sampleType=11003` 睡眠分期（`startOffset`/`endOffset` 为分钟偏移）
  - `sampleType=17001` 心率，`sampleType=4001` 血氧
- `origin_db_<hash>` → `TRACKRECORD.ORIGINAL_JSON_DATA`
  - `strength_training_group` = `[{"actionType":码,"count":次数}]`
  - `total_group` / `exercise_load` / `te` / `rpe` / `restTimeBetweenGroupsWithMillis`
