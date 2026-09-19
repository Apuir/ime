# 设置备份格式说明

> 📖 返回 [README](../README.md) · [开发与定制手册](./DEVELOPMENT.md)

本文件说明「设置 → 设置分享」导出的 JSON 文件的字段、包含哪些设置项，以及导入规则。
导出的文件既用于换机迁移，也可以手改后再导入。

---

## 1. 文件结构

```json
{
  "format": "ime-settings",
  "version": 1,
  "appVersion": "2.6.0",
  "exportedAt": 1789000000000,
  "settings": {
    "keyboard_settings": { "keyboard.height": 30, "keyboard.follow_system": true },
    "candidate_settings": { "show_index": false },
    "schema_settings": { "grammar_model": true },
    "clipboard_settings": { "max_entries": 200 }
  },
  "themes": [
    { "id": "custom_seafoam", "name": "海盐蓝", "colors": { "...": "#FF1A3F4E" } }
  ]
}
```

| 字段 | 类型 | 含义 |
|------|------|------|
| `format` | string | 固定 `ime-settings`，用于识别文件是否属于本应用 |
| `version` | int | 备份格式版本；当前为 `1` |
| `appVersion` | string | 导出时的应用版本，仅供排查 |
| `exportedAt` | long | 导出时间戳（毫秒） |
| `settings` | object | 设置项，见下 |
| `themes` | array | 用户自定义主题，格式与 `themes.json` 相同（见 [THEME_FORMAT.md](./THEME_FORMAT.md)） |

## 2. `settings`

`settings` 是「SharedPreferences 文件名 → 设置键 → 值」两层映射，键名与存储里完全一致
（清单见 [DEVELOPMENT.md 12.1](./DEVELOPMENT.md#121-sharedpreferences-清单)），因此可以直接和
`/data/data/com.ninthsoft.ime/shared_prefs/*.xml` 对照。值按原始类型存：
`Bool` / `Int` / `Float` / `String`（Float 与 Int 在 JSON 里都是数字，靠白名单里的类型区分）。

- **只导出存过的键**：没有被用户改动过（SharedPreferences 里不存在）的设置不会进文件，
  导入时保持目标设备的默认值 —— 这样以后调整默认值也能跟着走。
- **白名单在 `data/settings/SettingsBackup.kt` 的 `SettingsBackupSpec.PREFS`**：
  新增设置项要一并加进去，否则只是「不参与备份」，不会破坏格式。
- 包含 `keyboard_settings` / `candidate_settings` / `schema_settings` / `clipboard_settings`
  四个文件里全部用户可改的设置，含键盘几何、按键反馈、按键映射、侧栏符号、工具栏工具、
  按键气泡、悬浮键盘、中文槽的输入方式与方案、手写引擎与识别时机、候选词与上屏设置、
  剪贴板上限 / 保留天数 / 读取间隔等。
- **不导出**：
  - 一次性迁移标记与内部代次（`keyboard.feedback.vibration_scale`、
    `keyboard.toolbar_tools.handwriting_added`）；
  - 设备相关的探测缓存（`handwriting.google_usable`）；
  - 已被新键取代的 legacy 键（`keyboard.feedback.vibration`）；
  - 运行时状态（`keyboard.slot.active` 当前键盘槽）；
  - 剪贴板记录、常用语、候选学习数据（都在 Room 数据库里，不属于「设置」）。

## 3. 导入规则

- 先校验 `format` 与 `version`：不是本应用的备份、或 `version` 高于本端时直接判为无效文件。
- **合并写入**：只覆盖文件里出现的设置键，目标设备上的其它设置、剪贴板、常用语、学习数据都不受影响。
- 类型不符、格式越界（如非有限浮点）、字符串超长的值会被忽略，只统计写入成功的项。
- 主题按 `id` 合并：同 `id` 原位覆盖，否则追加；内置主题随包，不参与导入。
- 校验通过后仍会二次确认再写入，避免误选文件直接覆盖当前设置。
