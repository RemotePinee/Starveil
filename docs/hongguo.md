# 红果短剧 CENC 链路：采集端、服务端与客户端

本文描述一条完整的加密短剧链路：**采集端从上游取「地址 + 密钥派生串」→ 把稳定中转地址写进片库 →
星幕下发地址与开关 → 客户端本地解密播放**。目标读者是要自己写采集端的人，读完应当能实现一个
能被星幕直接播放的最小采集服务。

文中所有行为以当前代码为准，涉及的文件在正文里逐个点名。凡是代码里找不到依据的推断，一律标注
「未验证」，不写成结论。

| 角色 | 代码位置 |
|---|---|
| 采集中转（参考实现） | `E:/Kinova/tools/hema-collector`（`api/relay.py`、`platforms/hongguo/*`） |
| 片库 API（本仓库） | `server/starveilapi`（`src/Repo/Vods.php`、`src/Support/Setting.php`） |
| 客户端解密（本仓库） | `core/hongguo`（6 个文件）、`feature/player`（`HongguoPlaying.kt` 等） |

---

## 1. 这条链路在做什么

一句话：**采集端只拿元数据（地址 + `spade_a`），解密发生在设备上，视频字节始终是「客户端 → 中转 → CDN」
这条路径，服务端一行解密代码都没有。**

纯文本流程：

```
上游红果 App 接口
  POST /novel/player/multi_video_model/v1/          ← 必须带已注册设备身份
        │  video_list[i].main_url    （明文或 base64，CDN 直链）
        │  video_list[i].encrypt_info.spade_a / kid
        ▼
采集端（hema-collector）
  ├─ device_register 注册匿名设备 → device_id / iid / klink_egdi，落盘复用
  ├─ 采集时批量取材料（一次最多 15 个 vid）
  ├─ 落库 chapters(platform, book_id, chapter_id, index, url, spade_a, kid, url_ok, fetched_at)
  └─ 输出 vod_play_url：https://<relay>/hongguo/<book_id>/<index>.mp4
        │
        ▼
星幕 API（server/starveilapi）
  详情接口 vod_play_list[].urls[].url 原样下发中转地址
  同一层级下发 player_info.hongguo / player_info.hongguo_match（总开关 + 识别规则）
        │
        ▼
Android 客户端（feature/player + core/hongguo）
  HongguoGateDataSource.open(url)
    ├─ 开关关 / 规则不命中 → 原样透传（普通 mp4、m3u8、直播不受影响）
    └─ 命中 → GET <同一个地址>?d=1
          ← {main_url, spade_a, kid, expires_in}
          ├─ SpadeKey.derive(spade_a)          → 16 字节 AES-128 key
          ├─ Range 拉 800KB（必要时 2MB）头部   → Mp4SampleTable.parse
          └─ HongguoCencDataSource 包住上游 DataSource：读一段、解一段
                │
                ├─ 播放字节：仍是 <中转地址>，中转 302 到 CDN（带 Range）
                └─ main_url：只用来下载头部、解析 moov
```

必须记住的三件事：

1. **服务端不下载、不解析、不解密密文。** 服务端给的是地址与派生串；解密在 `core/hongguo` 里完成。
2. **`main_url` 不是播放地址。** 客户端只拿它下载 moov；真正播放时仍然打开中转地址，
   由中转 302 到 CDN。所以 302 分支必须始终可用，且跟随之后的 CDN 请求要保留 `Range`，
   不能把 302 改成 200 全量返回。
   采集端 `api/relay.py` 顶部注释写的是「客户端拿到 main_url 后直连 CDN」，这与星幕的实际实现不符，
   以客户端代码为准。
3. **上游从 2026-10-27 起要求「注册过的设备身份」。** 现造的 `device_id` 会拿到 HTTP 200 + 零字节，
   看起来像网络故障。注册流程见第 3.1 节。

---

## 2. 服务端接口契约

### 2.1 先分清：哪部分在 `starveilapi`，哪部分在采集端

`server/starveilapi` 里**没有**材料接口，也没有解密相关代码。它对红果只做两件事：

- 把 `vod_play_url` 里的中转地址原样放进详情响应（`Vods::playSources()`）；
- 把后台配置的 `player_info.hongguo` / `player_info.hongguo_match` 一起下发。

材料接口（`?d=1`）在中转服务里，也就是采集端。这一点和旧文档的「插件只提供一个开关值」一致，
但旧文档没写材料接口长什么样，本文第 2.3 节补上。

### 2.2 中转播放：`GET /{platform}/{book_id}/{index}[.{ext}]`

由采集端 `api/relay.py` 处理，路径规则 `_PATTERN`（`relay.py:27`）：

```
^/([a-zA-Z][a-zA-Z0-9_\-]{0,31})/(\d{1,32})/(\d{1,6})(?:\.\w{1,5})?$
```

| 段 | 取值 | 说明 |
|---|---|---|
| `platform` | 已注册平台名 | 红果固定 `hongguo`；单平台部署时也接受配置里的 `relay_prefix` |
| `book_id` | 1–32 位数字 | 红果的 `series_id`，必须**全是数字** |
| `index` | 1–6 位数字 | 分集序号，从 **1** 开始 |
| 扩展名 | 可选 | 采集端输出 `.mp4`，让播放器把地址认成直链 |

查询串不参与匹配，也不会传给 CDN。响应：

| 情况 | 响应 |
|---|---|
| 地址新鲜（`url_ok = 1` 且 `now - fetched_at < url_fresh_seconds`） | `302 Location: <CDN 直链>`，`Cache-Control: no-store` |
| 地址旧但 HEAD 复核通过 | 同上 |
| 地址失效（`url_ok = 0`）/ 缺 `spade_a` | 先向游重采当前集；成功则 302，失败则 502 |
| 从未探测（`url_ok = -1`） | 先 HEAD 探测；可用则 302 并把 `url_ok` 置 1，不可用才重采 |
| 平台 / 剧 / 集不存在 | `404` + `{"ok": false, "error": {...}}` |

**Range 必须能透传。** 客户端播放时带 `Range` 请求中转地址，中转回 302，播放器跟随到 CDN；
CDN 看到的是原始 `Range`。中转自己不需要处理分片，但**不能**把 302 换成 200 全量响应，
否则拖动与边下边解都会失效。

### 2.3 材料接口：`GET /{platform}/{book_id}/{index}[.{ext}]?d=1`

这是星幕客户端**唯一**使用的红果接口。判据是查询串里 `d=1`（`relay.py:138-145`），
命中后不重定向，直接返回 JSON。

**请求**

- 方法：`GET`（`HEAD` 也走同一分支，只是不写 body）
- 请求头：无强制要求。若采集端配置了 `security.allowed_referers`，则按 Referer 子串白名单校验
  （`relay.py:56-71`）。空 Referer 一律放行——播放器直连时不带 Referer。
- 当 `player_info.issethead`（「需要请求头的地址正则」）命中该地址时，客户端会把配置的
  `player_info.headers`（`Referer`、`User-Agent` 等）带到材料请求上（`HongguoPlaying.kt:179-181`、
  `ParseRules.resolveHeaders`），所以白名单要和片源的请求头配置对得上；没命中就带空头。

**响应 200（扁平 JSON，没有外层包装）**

```json
{
  "main_url": "https://xxx.cdn.example/....mp4?...",
  "spade_a": "krwcy0yMBcFVti37Y7ce51KuGtVLrDb9SYQH/nuHKsh7sTOzsw==",
  "kid": "....",
  "expires_in": 1800
}
```

| 字段 | 类型 | 必填 | 含义与取值范围 |
|---|---|---|---|
| `main_url` | string | **是** | 当前 CDN 直链，必须非空且能直接 `GET`（客户端用它拉 moov）。实测约 6 小时有效（`core/config.py` 的 `hongguo_ttl_hours`） |
| `spade_a` | string | **是** | 密钥派生串，原始字符串，不做二次编码。**空串等价于失败**：客户端日志打「材料里没有 spade_a」并退回透传，画面黑 |
| `kid` | string | 否 | 密钥 ID。客户端**当前不读**，仅采集端落库/对账用 |
| `expires_in` | int | 否 | 秒。取值是 `url_fresh_seconds`（默认 1800），**不是 CDN 直链的真实有效期**。客户端不读，自己硬编码 5 分钟材料缓存 |

客户端解析方式（`HongguoPlaying.kt:126-145`）：

- `JSONObject` 读**顶层**字段，`optString("spade_a")`、`optString("main_url")`。
  返回 `{"code":0,"data":{...}}` 这种包装结构会直接读不到，**必须扁平**。
- 两个字段都要 `isNotBlank()`，否则当作材料失败。
- `kid`、`expires_in` 即使缺失也不影响播放。

**材料响应不含分片表。** 客户端自己下载头部（moov）解析 `stsz`/`stco`/`stsc`/`senc` 得到 sample 表
（第 4.2 节），材料接口只给地址与密钥；服务端既不解析也不下发 sample 表。

响应头：`Content-Type: application/json; charset=utf-8`、`Cache-Control: no-store, no-cache, must-revalidate`；
body 大于 1KB 且客户端声明 `Accept-Encoding: gzip` 时启用 gzip（`api/server.py:send_json`）。

**错误响应**

错误体统一是 `{"ok": false, "error": {"code": "<字符串码>", "msg": "<中文说明>"}}`：

| HTTP | `error.code` | `msg` 实例 | 触发条件 |
|---|---|---|---|
| 403 | `FORBIDDEN` | 来源不在白名单 | Referer 非空且不命中白名单 |
| 404 | `NOT_FOUND` | 平台不存在: xxx | 路径前缀既不是平台名，也不等于单平台时的 `relay_prefix` |
| 404 | `NOT_FOUND` | 剧不存在: 123 | `book_id` 不在库里 |
| 404 | `NOT_FOUND` | 集不存在: 3 | 该剧没有这一集 |
| 502 | `UPSTREAM_UNAVAILABLE` | 源站地址已失效且重采失败，请稍后重试 | 地址失效/缺 `spade_a`，且向游重采也失败 |
| 500 | `INTERNAL` | … | 未捕获异常 |

客户端对非 2xx 的处理是 `fetchMaterialJson()` 返回 `null` → `prepareFor` 返回 `null` →
**退回透传**，表现是黑屏有声，不会弹错误提示（`HongguoPlaying.kt:60-62`）。

### 2.4 备用材料接口：`GET /api/hongguo/play`

采集端还提供一个按 `vid` 查材料的接口（`api/server.py:_hongguo_play`），**星幕不用它**，
但自己做联调时方便。两种入参：

- `?vid=<video_id>`；
- `?series_id=<series_id>&episode=<1 起的集号>`（先用 `episode_vids()` 把集号映射成 vid）。

成功：

```json
{"code": 0, "data": {
  "vid": "...", "main_url": "...", "spade_a": "...", "kid": "...",
  "encrypt_method": "cenc-aes-ctr", "quality": "720p", "expires_in": 21600
}}
```

两个分支的字段略有差异：库里命中时（地址仍新鲜且 `spade_a` 非空）不回上游，`quality` 是空串、
`expires_in` 取 `url_fresh_seconds`（默认 1800）；只有实时回上游的 `material_for()` 分支才返回
真实 `quality` 与 `expires_in = 21600`。

失败：

| HTTP | 响应 | 条件 |
|---|---|---|
| 400 | `{"code":1,"msg":"需要 vid，或 series_id + episode"}` | 参数不全 |
| 400 | `{"code":1,"msg":"该剧还没采到分集，请先跑一次补采集数"}` | 集号映射不到 vid |
| 500 | `{"code":1,"msg":"平台不可用: ..."}` | 平台初始化失败 |
| 502 | `{"code":1,"msg":"没有取到播放材料"}` | 上游取材料失败 |

注意它用的是 `code: 0/1` 语义（0 成功），和 `starveilapi` 的 `code: 200` 是两套约定，不要混。

### 2.5 客户端拿到的开关：`vod_play_list[].player_info`

详情接口是 `GET /icciu_api.php/v1.vod/detail`。`starveilapi` 在 `Vods::playSources()`
（`server/starveilapi/src/Repo/Vods.php:500-535`）里给每条播放源附一份 `player_info`：
附一份 `player_info`：

```json
{
  "parse2": "...", "features": "...", "issethead": "...", "headers": "Referer:...||User-Agent:...",
  "hongguo": "0",
  "hongguo_match": "/hongguo/,/cenc/"
}
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `hongguo` | `"0"` / `"1"` | 总开关，默认 `"0"`。只有 `"1"` 才算开 |
| `hongguo_match` | 逗号分隔子串 | 地址包含任一子串即命中，忽略大小写。空 = 不命中 |

写入链路：后台「解析配置」→ `SettingsSave.php` → `cuican_setting.player_info`（JSON 字符串）→
`Setting::playerInfo()` 补齐默认值 → 详情接口下发 → 客户端 `VodMapper` 转成
`PlayerInfo.hongguoEnabled / hongguoMatch` → `HongguoConfig.parse()`。

`player_info` 是**全站一份**，不按播放源区分（`Vods.php` 注释里写明了这是简化）。
后台两个输入框的文案在 `SettingsPage.php:158-168`：

- 「红果 CENC 解密」：`0=关（默认） 1=开`；
- 「红果识别规则」：`逗号分隔的地址特征，命中即尝试解密。例：/hongguo/,/cenc/`。

客户端的判定（`core/hongguo/HongguoConfig.kt:13-16`）：

```kotlin
fun matches(url: String?): Boolean {
    if (!enabled || url.isNullOrBlank() || patterns.isEmpty()) return false
    return patterns.any { url.contains(it, ignoreCase = true) }
}
```

两个条件同时成立才触发；任一不满足就原样透传。

---

## 3. 采集端要做什么

### 3.1 注册：先拿一个「站方认识」的设备身份

**背景。** 站方从 2026-10-27 起不再接受签名请求，只认它自己注册过的 `device_id` / `iid`。
现造身份会拿到 **HTTP 200 + 零字节 body**——和「接口下线」长得一模一样，这是最费时间的坑
（`platforms/hongguo/device.py:1-8`、`client.py:90-124`）。

**请求**

```
POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
     ?tt_data=a&cronet_version=8d40f833_2026-03-03
     &ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
```

请求头（关键几项，完整列表见 `device.py:139-152`）：

| 头 | 值 |
|---|---|
| `Content-Type` | `application/octet-stream;tt-data=a` |
| `log-encode-type` | `gzip` |
| `x-ss-dp` | `8662`（红果的 aid） |
| `x-ss-req-ticket` | 毫秒时间戳 |
| `x-ss-stub` | `MD5(body).hexdigest().upper()` |
| `User-Agent` | `com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)` |

**Body：ttEncrypt 信封**

```
body = "746305100000"                 // 6 字节固定标记
     + random(32)                     // 两个 uuid4 的 bytes 拼起来
     + AES-128-CBC( PKCS7( sha512(gzip(json)) + gzip(json) ) )
```

- 密钥派生：`derived = sha512( sha512(random) + bytes.fromhex(KDF_SALT) )`；
  `key = derived[0:16]`，`iv = derived[16:32]`。
- `json` 是 `{"magic_tag":"ss_app_log","header":{...},"_gen_time":<毫秒>}`。
- `header` 有 50 余个字段（机型、系统、渠道、分辨率、`aid`、`channel`、`custom.dragon_device_type` 等），
  其中每次都要新生成的是 `cdid`、`openudid`、`clientudid`、`req_id`，`apk_first_install_time` 用当前毫秒。
- **固定盐值与整套设备指纹常量不在本文转抄**，以 `device.py:29-32`（`KDF_SALT`）和
  `device.py:54-113`（`_register_header`）为准——这些是长常量，抄进文档只会和代码不同步。

**响应**

```json
{"device_id_str": "...", "install_id_str": "...", "klink_egdi": "..."}
```

- 成功判定：**没有 `code` 字段就当成功**；只有出现且不为 0 才算拒绝（`device.py:158-165`）。
- `device_id` 优先取 `device_id_str`，回退 `device_id`；`iid` 优先 `install_id_str`，回退 `install_id`。
  两个都拿不到就是失败。
- `klink_egdi` 也要存下来。

**之后怎么带上**

后续每个上游请求都在 **query** 里带这三个值（不是 body）：

```
device_id=<注册值>&iid=<注册值>&klink_egdi=<注册值>
```

`app_post()` 会用注册身份覆盖掉默认指纹里的 `device_id` / `iid` / `klink_egdi`
（`client.py:96-106`），其余参数沿用固定的高清档参数表。缺 `klink_egdi` 同样会拿到零字节。

**持久化与失效**

| 项 | 实现 |
|---|---|
| 落盘文件 | `hg_device.json`（可用环境变量 `HG_DEVICE_FILE` 改路径） |
| 内容 | `{"device_id","iid","klink"}` |
| 何时重注册 | 文件缺失或内容不全；或收到 `200 + 空 body` 时调 `invalidate()` 删文件，下次请求自动重注册 |
| 注册是否阻塞请求 | **不阻塞**。`identity()` 只读缓存/文件，缓存为空时起后台线程去注册并立即返回 `None`，本次请求照发不带身份（`device.py:227-246`） |

这个「后台注册、绝不阻塞」的设计不是可选项：首版把注册放在请求路径里，25 秒超时会让所有采集线程
一起卡在锁上，总览接口直接超时（`device.py:230-233` 的注释）。

### 3.2 取材料：地址与密钥从哪来

上游接口（`platforms/hongguo/play.py`）：

```
POST {APP_HD}/novel/player/multi_video_model/v1/?<高清档参数>&_rticket=<毫秒>
```

- `APP_HD = https://api5-normal-sinfonlineb.fqnovel.com`（平板参数，能拿 1080p）；
  手机档 `APP_LOW = https://api5-normal-lq.fqnovel.com` 最高 720p。
- 固定参数表见 `client.py:26-60`（`APP_Q` / `PAD_Q`），其中设备指纹必须长期一致，
  **不要随机化**——红果对设备一致性敏感。

请求体：

```json
{
  "biz_param": {
    "detail_page_version": 0,
    "device_level": 3,
    "disable_digg_stat": false,
    "disable_video_relate_book": false,
    "need_all_video_definition": true,
    "need_mp4_align": false,
    "use_os_player": false,
    "use_server_dns": false,
    "video_platform": 1024
  },
  "dr_scene": "player",
  "mixed_video_id_map": {"1": ["<vid>", "..."]}
}
```

- **批量上限 15 个 vid**，16 个及以上返回空（`play.py:18`）。
- `mixed_video_id_map` 的 key 固定 `"1"`，值是 vid 字符串数组。

响应里每个 vid 对应一个 `video_model`，可能是对象，也可能是 JSON 字符串（需要再 `json.loads`）：

```
data[vid].video_model.video_list[]:
  ├─ main_url        明文 http(s) 或标准 base64；两种形态都要处理
  ├─ definition      新格式清晰度，如 "720p"
  ├─ gear_des_key    老格式，清晰度藏在 "4:1080p" 这样的片段里
  └─ encrypt_info    {spade_a, kid, encryption_method}
```

- `main_url` 解码规则：以 `http` 开头直接用；否则补 `=` 到 4 的倍数后 base64 解码，
  结果仍须以 `http` 开头（`play.py:36-47`）。
- 选档：按 `360p < 480p < 540p < 720p < 1080p` 取有地址的最高档（`pick_best`）。
  取不到任何可用地址就跳过这一集。
- 密钥来自 `encrypt_info.spade_a`，`kid` 一并保存。**同一集所有档位是否共用一份 `encrypt_info`
  未验证**——参考实现只取选中档自己的那份，不要依赖「档位共享」。

**集号 → vid 的映射**：`series_id` 给的是一整部剧，`episode_vids(series_id)` 返回按集序排列的
`vid_list`，第 N 集取下标 `N-1`（`platforms/hongguo/__init__.py:75-81`）。

### 3.3 落库与对外地址

每集写一行 `chapters`（`core/db_core.py:59-77`）：

| 列 | 含义 |
|---|---|
| `platform` | `hongguo` |
| `book_id` | `series_id` |
| `chapter_id` | 上游 `vid`（不是集号） |
| `chapter_index` | 集号，从 1 开始 |
| `chapter_name` | `第N集` |
| `url` | **中转地址**，不是 CDN 直链 |
| `fetched_at` | 取到地址的时间戳（秒） |
| `url_ok` | `1` 有效 / `0` 失效 / `-1` 未探测 |
| `spade_a` / `kid` | 解密材料 |

`upsert_chapters` 的合并规则要注意：`url`、`fetched_at`、`url_ok` 直接覆盖，
但 `spade_a`、`kid` 只在**新值非空时**覆盖（`core/db.py:88-106`）。这是为了防止一次没带密钥的
重采把好数据抹掉。反过来说，**采集阶段就必须把 `spade_a` 一起落库**，否则后面任何一次只刷新地址的
操作都补不回来（`relay.py:209-225` 的注释说的就是这个）。

**对外地址必须长成客户端认得的形状**（`core/hongguo/HongguoRelay.kt:12`）：

```
/hongguo/<纯数字 book_id>/<纯数字 index>[.ext]
```

- 前缀必须是字面量 `hongguo`：要么平台名就是 `hongguo`（多平台部署走这条），
  要么单平台部署时把 `relay_prefix` 配成 `hongguo`。
- 两段都必须是数字，正则用的是 `(\d+)`。
- 扩展名可选；参考实现统一输出 `.mp4`，让播放器把地址认成直链
  （`api/maccms.py:68-95`）。
- **不要把 `spade_a` 塞进 query**：客户端本来就要打一次 `?d=1`，密钥从 JSON 里拿；
  放在地址上只会让地址变长、还会被日志和 Referer 带出去。

最终写进 `vod_play_url` 的形态（`api/maccms.py:76-84`）：

```
第1集$https://relay.example.com/hongguo/7629637736885914686/1.mp4#第2集$https://.../2.mp4
```

集名用阿拉伯数字，因为 macCMS 的 `sortPlayUrls()` 用 `/\d+/` 做排序 key，中文数字会退化成
用 URL 里的 bookId 当 key，导致所有集撞成一条。

### 3.4 失败与重试的边界

| 位置 | 行为 | 依据 |
|---|---|---|
| 上游 HTTP 非 200 / 空 body | 返回 `None`，不算异常 | `client.py:115-120` |
| 上游 200 + 零字节且本次带过注册身份 | 调 `invalidate()` 丢掉身份，下次重注册 | 同上 |
| 普通重试 | `call(fn, tries=3, delay=1.2)`，退避 `1.2s × 轮次` | `client.py:175-187` |
| 中转重采 | **单次**，不重试；失败直接 502 | `relay.py:208-225` |
| 重采成功但上游仍没给 `spade_a` | 仍然回 200 + 空 `spade_a`，客户端黑屏 | `relay.py:189-194` 读库后原样返回 |
| 地址新鲜窗口 | `url_fresh_seconds`，默认 1800 秒 | `core/config.py:48` |
| 红果 CDN 直链寿命 | 实测约 6 小时；采集端用 `hongguo_ttl_hours = 6` 单独算 TTL | `core/config.py:44-47` |

两条容易踩的边界：

- **材料接口不校验「地址是否真能下载」**，只看库里的 `url_ok` 与时间。`url_ok = 1` 且未过期就直接发。
  所以采集端写库时的 `url_ok` 必须诚实，否则用户会拿到死链。
- **重采是一次外部请求（约 0.6 秒）**，`_material` 会在超过 0.5 秒时打「材料接口偏慢」告警。
  如果你的实现比这慢，先看是不是每次都在打上游。

---

## 4. 密钥派生与解密

### 4.1 `spade_a` → AES-128 密钥

实现：`core/hongguo/SpadeKey.kt:17-49`。`spade_a` 不是标准 base64 的密文，而是一段
**变形过的 base64 + 一轮按位变换**，最后得到 32 个 hex 字符。

逐步：

1. **base64 解码（FFmpeg 风格）**：标准字母表 `A-Za-z0-9+/`；遇到非法字符按「剩余位截断」处理，
   不抛异常；尾部不足 4 个字符时，按已收集到的字符补出 1–2 字节（`SpadeKey.kt:51-84`）。
2. 解码结果长度 `< 3` → 失败。
3. `key0 = b[0] ^ b[1] ^ b[2]`；若 `key0 - 0x30 < 2` → 失败（即要求 `key0 >= 0x32`）。
4. `w22 = len - key0 + 0x2f`；要求 `2 <= w22 <= len - 1`，否则失败。
5. 取 `x21 = b[1 .. 1+w22)`。
6. 逐字节变换：
   ```
   w11 = 0x55, w12 = 0xfa
   for i in 0 until len(x21):
       w14 = (i 偶数 ? w12 : w11)
       if i 奇数: w11 = x21[i] else: w12 = x21[i]
       x21[i] = (0xeb - popcount(i) + (w14 ^ x21[i])) & 0xff
   ```
   注意 `0xeb - popcount(i)` 是**常量减位计数**，不是循环变量；`w11`/`w12` 保存的是**上一轮**的字节。
7. `c0 = x21[0]`：`'0'..'9'` → `c0 - 0x30`；`'a'..'z'` → `c0 - 0x57`（即 `'a'` 对应 10）；其它字符 → 失败。
8. `w9 = w22 - hval`；`w9 < 2` → 失败。
9. `hex = ASCII(x21[1 .. 1+w9-1])`；长度必须**正好 32**，否则失败；每两个 hex 字符转 1 字节，得到 16 字节 key。

自检样本（`SpadeKeyTest.kt`，取自线上 vid `7629639568660106302` 的 480p）：

```
spade_a = krwcy0yMBcFVti37Y7ce51KuGtVLrDb9SYQH/nuHKsh7sTOzsw==
key     = 3a91268aa665e8331d9be9fb6bca867c
```

失败条件汇总：`null` / 空串 / 解码后 < 3 字节 / `key0 - 0x30 < 2` / `w22` 越界 /
首字符不是数字或小写字母 / hex 长度不等于 32。**任何一条命中都返回 `null`，调用方按「材料不可用」
退回透传**，不会抛异常。

> 先把「派生串 → 密钥」的这组对应关系钉成单测，再往下做。派生错了，后面每个 sample 都会解成垃圾，
> 表现和片源损坏完全一样。

### 4.2 分片表：从 moov 里读四张表

实现：`core/hongguo/Mp4SampleTable.kt:57-163`。只处理**普通（非分片）mp4**，红果的结构是
`ftyp + moov + free + mdat`。

遍历路径：`moov` → 每个 `trak` → `mdia` → `minf` → `stbl`，然后在 `stbl` 里找四个 box：

| box | 内容 | 解析方式 |
|---|---|---|
| `stsz` | 每个 sample 的字节数 | `stsz.start+4` 是 `sample_size`；非 0 表示所有 sample 等长；为 0 时 `stsz.start+8` 是 count，后面紧跟 count 个 u32 |
| `stco` | 每个 chunk 的文件偏移 | `stco.start+4` 是 count，后面 count 个 u32 |
| `stsc` | 每 chunk 几个 sample | 每项 12 字节：`first_chunk`、`samples_per_chunk`、`sample_description_index`（第三项忽略） |
| `senc` | 每个 sample 的 IV | `senc.start+4` 是 `sample_count`，之后每条 **8 字节** IV |

**`senc` 缺失的轨道整条跳过**——那是没有加密的轨道（音频通常属于这种），这解释了为什么
「黑屏但有声」。四个 box 缺任意一个，该轨道就不产出 sample；所有轨道都没有 sample 时
`parse()` 返回 `null`。

展开算法（`collectTrak`）：

```
si = 0
for ci, chunkOffset in stco:
    off = chunkOffset
    for k in 0 until stsc.perChunk[ci]:
        if si >= stsz.size: break
        if si < ivs.size:  samples.add(Sample(off, stsz[si], ivs[si]))
        off += stsz[si]
        si += 1
```

要点：

- sample 的**文件偏移**由「chunk 起点 + 前面各 sample 长度累加」算出，不单独存储。
- `stsc` 是压缩表：一项管 `[first_chunk, 下一项 first_chunk)` 这一段 chunk，中间缺省按 `1` 处理。
- `senc` 条目少于 sample 数时，超出部分不产出（`si < ivs.size` 判断）。

**实现假设与限制**（照抄前请确认自己的片源满足）：

- 只支持 `stco`（32 位偏移），**不支持 `co64`**；
- `senc` 按「无 override、无 subsample、每条固定 8 字节 IV」解析，不读 `tenc` 的默认 IV/KID；
- 偏移与长度都用 `Int`，单文件不能超过 2GB；
- 只支持非分片 mp4，不认 `moof`/`traf`；
- 不校验 `kid` 与容器里 `tenc` 的 KID 是否一致（客户端根本没读 `kid`）。

解析需要的字节数：moov 实测约 782KB，客户端先请求 **800KB**，失败再补到 **2MB**
（`HongguoPlaying.kt:322-325`）。旧文档说「只需头部几十上百 KB」是错的。

### 4.3 CENC 解密

实现：`core/hongguo/CencSampleDecryptor.kt:26-46`。AES-128-CTR，但计数器块要自己拼：

```
counter_block(k) = nonce(8 字节) || uint64_be(k)      // 共 16 字节
keystream        = AES-128-ECB_encrypt(counter_block(0..n-1), key)
plain[i]         = cipher[i] XOR keystream[blockOffset*16 + skipBytes + i]
```

- `nonce` 就是该 sample 在 `senc` 里的 8 字节 IV。
- 块序号 `k` **以 sample 为起点从 0 开始**，每个 sample 重置；不是整文件连续编号。
- CTR 是可随机访问的流密码，所以支持「从第 `blockOffset` 块的第 `skipBytes` 字节开始解」，
  这是 Range 请求能只解所需片段的前提。
- 解出来与输入等长，无填充、无尾部处理。

已知对应关系（`SpadeKeyTest.kt`，可直接当单测向量）：

```
key    = 3a91268aa665e8331d9be9fb6bca867c
iv     = da9d5b88146dec45
cipher = c66b8d1d00bedf579684f10a1e528df6
plain  = 0000001d40010c02ffff016000000300     // 00 00 00 1d 是 HEVC 长度前缀，40 01 是 VPS
```

> `senc` 里的 IV 必须**逐条读**。代码不假设任何递增规律，也无法验证「看起来像递增只是巧合」
> 这种说法——按表读就不会错。

### 4.4 流式接入：不重建容器

`feature/player/HongguoCencDataSource.kt` 把解密套在上游 `DataSource` 外面：

```kotlin
override fun open(dataSpec: DataSpec): Long {
    position = dataSpec.position
    return upstream.open(dataSpec)
}

override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val n = upstream.read(buffer, offset, length)
    if (n > 0) {
        table.decryptRange(buffer, offset, n, position, key)
        position += n
    }
    return n
}
```

`decryptRange`（`Mp4SampleTable.kt:24-46`）的处理：

1. 用 `[from, from+len)` 与每个 sample 的 `[offset, offset+size)` 求交集；
2. 交集内算 `inSample = segStart - sampleStart`，`block = inSample / 16`，`skip = inSample % 16`；
3. 调 `decryptAt(key, sample.iv, 密文段, block, skip)`，结果原地写回。

因此：**一次 `read()` 跨多个 sample、或只落在某个 sample 中间，都能正确解**；`mdat` 里不属于任何
sample 的字节（chunk 间隙、padding）原样通过。

`moov` 是明文，**不做任何容器改写**：不改 box、不修 chunk offset、不重建索引。
这是「不重建容器」的准确含义——旧文档这一点是对的，但 `docs/architecture.md:53` 仍写着
「重建 moov」，那句是过期的（本次未改该文件）。

### 4.5 客户端缓存

| 层 | 键 | 容量 / TTL | 作用 | 依据 |
|---|---|---|---|---|
| 材料 JSON | 中转地址（去掉 query） | 64 条 / 5 分钟 | 来回滑动不重复请求材料 | `HongguoPlaying.kt:168-192`、`333-350` |
| sample 表 + key | `series/episode` | 24 条 LRU | 同集重进跳过拉材料与下载头部 | `HongguoPlaying.kt:356-367` |
| 磁盘 | `cacheDir/hongguo/<series>/<episode>.{tbl,key}` | 40 集 / 30 分钟 | 进程重启后仍能跳过头部下载 | `HongguoDiskCache.kt`（**当前落盘有缺陷，见下**） |
| 并发去重 | 同上 | 30 秒超时 | 预热线程与 `open()` 同时到，只下载一次 | `HongguoPlaying.kt:96-100` |

磁盘缓存的两个设计后果，排查时必须知道：

- **缓存 key 不含密钥**。服务端换了 `spade_a` 之后，旧表仍在，命中就直接用旧 key 解新分片 →
  黑屏。所以有 30 分钟 TTL，过期删除两个文件（`HongguoDiskCache.kt:20-27`）。
- sample 表的二进制格式是 `SampleTableCodec`：魔数 `HGT1`、版本 1、条数、然后每条
  `{offset(4) size(4) iv(8)}`，一集约 52KB（3251 条）。解码上限 500 万条，格式不对返回 `null`
  按未命中处理。
另外，`VideoCache`（Media3 的分片缓存）位于解密层**之上**，存的是**解密后的明文分片**；
`cacheDir/hongguo/` 存的是索引与密钥。两者不要混为一谈。

> **磁盘缓存当前很可能根本没有落盘。** `HongguoDiskCache` 用 `"<series>/<episode>"` 当 tag，
> 拼出来的路径是 `hongguo/<series>/<episode>.key`，但 `save()` 只对 `hongguo/` 这一层做过 `mkdirs`，
> 没有创建 `<series>` 子目录。父目录不存在时 `File.writeBytes` 会抛 `FileNotFoundException`，
> 而 `save()` 外面包着 `runCatching`，异常被吞掉、只返回 `false`。按静态阅读判断，
> 第 4.5 节表格里的磁盘缓存与 `trim()` 都不会真正生效；`prepareFor` 里「磁盘命中」那条日志
> 在现有代码下不会出现。**未在设备上实测**，如果要在客户端修，给它补一行
> `tableFile(...).parentFile?.mkdirs()` 即可（本文按约束不改 `.kt`）。
另外，`VideoCache`（Media3 的分片缓存）位于解密层**之上**，存的是**解密后的明文分片**；
`cacheDir/hongguo/` 存的是索引与密钥。两者不要混为一谈。

---

## 5. 写自己的采集端

下面是一个最小骨架，只覆盖「注册 → 取材料 → 中转材料 / 302」这条主链。用 FastAPI + httpx 表达，
换成任何语言都一样。**标 `# 必须` 的是客户端硬依赖，标 `# 可选` 的可以省。**

```python
# 最小采集端骨架：红果材料中转
import base64, json, time
import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse

app = FastAPI()
client = httpx.Client(timeout=25.0, follow_redirects=True)
STATE = "hg_device.json"          # 可选：注册身份落盘位置

# ── 1. 注册（详见第 3.1 节；固定指纹与盐值以参考实现为准）──
def register_identity() -> dict:
    # body = b"\x74\x63\x05\x10\x00\x00" + random32 + AES_CBC(...)
    # 返回 {"device_id","iid","klink"}
    ...

def identity() -> dict:
    try:
        return json.load(open(STATE, encoding="utf-8"))
    except OSError:
        ident = register_identity()
        json.dump(ident, open(STATE, "w", encoding="utf-8"))
        return ident

# ── 2. 取材料：地址 + spade_a ──
def fetch_materials(vids: list[str]) -> dict[str, dict]:
    out = {}
    for i in range(0, len(vids), 15):                    # 必须：单批最多 15 个 vid
        batch = vids[i:i + 15]
        ident = identity()                               # 必须：query 带注册身份
        params = {"device_id": ident["device_id"], "iid": ident["iid"],
                  "klink_egdi": ident["klink"], "_rticket": str(int(time.time() * 1000))}
        r = client.post("https://api5-normal-sinfonlineb.fqnovel.com"
                        "/novel/player/multi_video_model/v1/", params=params,
                        json={"biz_param": {"detail_page_version": 0, "device_level": 3,
                                            "need_all_video_definition": True,
                                            "use_os_player": False, "use_server_dns": False,
                                            "video_platform": 1024},
                              "dr_scene": "player",
                              "mixed_video_id_map": {"1": batch}})
        if r.status_code != 200 or not r.content:        # 必须：200 + 空 = 身份被拒
            raise RuntimeError("upstream rejected")
        for vid, node in (r.json().get("data") or {}).items():
            model = node.get("video_model")
            if isinstance(model, str):
                model = json.loads(model)
            items = list((model.get("video_list") or {}).values()) \
                if isinstance(model.get("video_list"), dict) else (model.get("video_list") or [])
            best = pick_best(items)                      # 必须：按清晰度挑有地址的最高档
            if best:
                out[str(vid)] = best
    return out

def pick_best(items):
    rank = {"360p": 1, "480p": 2, "540p": 3, "720p": 4, "1080p": 5}
    best, best_rank = None, 0
    for it in items:
        url = decode_url(it.get("main_url"))             # 必须：明文或 base64 两种形态
        gear = it.get("definition") or ""
        if not gear:                                     # 老格式：清晰度藏在 gear_des_key 的 "4:1080p"
            gear = next((p[2:] for p in str(it.get("gear_des_key") or "").split("|")
                         if p.startswith("4:")), "")
        if url and rank.get(gear, 0) > best_rank:
            best, best_rank = it, rank[gear]
    if not best:
        return None
    enc = best.get("encrypt_info") or {}
    return {"main_url": decode_url(best["main_url"]),
            "spade_a": str(enc.get("spade_a") or ""),     # 必须：空 = 客户端黑屏
            "kid": str(enc.get("kid") or "")}             # 可选：客户端不读

def decode_url(text: str) -> str:
    if not text:
        return ""
    if text.startswith("http"):
        return text
    raw = base64.b64decode(text + "=" * (-len(text) % 4)).decode("utf-8", "ignore")
    return raw if raw.startswith("http") else ""

# ── 3. 中转：302 给播放器，?d=1 给材料 ──
@app.get("/hongguo/{book_id}/{index}")
@app.get("/hongguo/{book_id}/{index}.mp4")               # 必须：采集端输出的地址带 .mp4
def relay(book_id: str, index: int, request: Request):
    ch = DB.get(book_id, index)                          # 你的存储：见第 3.3 节的字段
    if not ch:
        raise HTTPException(404, "集不存在")
    if request.query_params.get("d") == "1":             # 必须：只认 d=1
        if not ch.get("spade_a") or not ch.get("url"):
            ch = refresh(book_id, index) or ch           # 重采一次；仍失败则 502
            if not ch.get("spade_a") or not ch.get("url"):
                raise HTTPException(502, "源站地址已失效且重采失败")
        return JSONResponse({                            # 必须：扁平对象，顶层两字段
            "main_url": ch["url"],                       # 必须
            "spade_a": ch["spade_a"],                    # 必须
            "kid": ch.get("kid", ""),                    # 可选
            "expires_in": 1800,                          # 可选
        }, headers={"Cache-Control": "no-store"})
    return RedirectResponse(ch["url"], status_code=302)  # 必须：播放字节仍走这里
```

> 骨架里 `HTTPException` 返回的是 FastAPI 默认的 `{"detail": ...}`。客户端**不解析错误体**，
> 只按非 2xx 处理，所以错误 JSON 的具体形状可以自定；如果要和参考实现一致，用第 2.3 节的
> `{"ok": false, "error": {...}}`。另外参考实现在上游返回空包时是**跳过这一批**而不是抛异常，
> 骨架里为了显眼才改成 `raise`。

接入星幕时还差三步：

1. 把中转地址写进 `vod_play_url`，形态必须是 `/hongguo/<数字>/<数字>.mp4`（第 3.3 节）；
2. 后台「解析配置 → 红果 CENC 解密」设为 `1`；
3. 「红果识别规则」填一个能命中中转地址的子串，例如 `/hongguo/`（或你的中转域名）。

只填规则不打开关，或只打开关不填规则，都不会触发解密（第 2.5 节）。

**必须 vs 可选，一次说清**

| 项 | 级别 | 省掉的后果 |
|---|---|---|
| 材料返回扁平对象（无 `code`/`data` 包装） | 必须 | 客户端读不到字段，全部退回透传 |
| `main_url` 非空、能 GET、支持 Range | 必须 | 拉不到 moov，黑屏 |
| `spade_a` 非空且可派生 | 必须 | 同上 |
| 中转地址含 `/hongguo/<数字>/<数字>` | 必须 | 客户端正则不匹配，不触发解密 |
| 中转 302 指向可用 CDN 地址（跟随后的请求保留 `Range`） | 必须 | 播放、拖动失效（见第 1 节第 2 条） |
| 重采时连 `spade_a` 一起写库 | 必须 | 密钥永远补不回来 |
| `kid` | 可选 | 客户端不读；只影响对账 |
| `expires_in` | 可选 | 客户端不读 |
| `encrypt_method` / `quality` / `vid` | 可选 | 仅 `/api/hongguo/play` 用 |
| 302 分支 | **必须（所有情况）** | 客户端播放字节始终打开中转地址，材料只提供解析 moov 用的 `main_url`；没有 302 就播不了 |

---

## 6. 常见错误

按「现象 → 原因 → 处理」列，全部对应代码里真实会发生的分支。

### 6.1 材料拿不到密钥

**现象**：日志 `材料里没有 spade_a（这集没采过？）`，画面黑、有声音。

**原因**：材料 JSON 里 `spade_a` 缺失或为空串；也可能是采集阶段只写了 `url` 没写 `spade_a`
（`upsert` 对空值不覆盖，所以一次漏采会一直空）。

**处理**：查库该集 `spade_a` 是否为空；空就对该集跑一次 `refresh_materials`，确认上游
`encrypt_info.spade_a` 有值；再确认重采路径把密钥一起写库，而不是只写地址。

### 6.2 材料里没有 `main_url`

**现象**：日志 `材料里没有 main_url`。

**原因**：上游该集 `video_list` 为空，或所有档位的 `main_url` 都解码失败（不是 `http` 开头）。

**处理**：直接打上游看 `video_list`；检查 `main_url` 是明文还是 base64、base64 是否缺 padding。
`pick_best` 会跳过没有可用地址的档位，全跳过就等于没有材料。

### 6.3 `spade_a` 派生失败

**现象**：日志 `spade_a 派生 key 失败`。

**原因**：串被二次编码/转义（例如经过 JSON 字符串再 URL 编码）、上游换了算法、或串本身被截断。

**处理**：拿第 4.1 节的自检样本先验证实现；再对比库里存的原始串与上游返回是否逐字节一致
（注意 `+` `/` `=` 有没有被 URL 编码）。

### 6.4 moov 解析失败

**现象**：日志 `moov 解析失败（stsz/stco/stsc/senc 缺失？）`。

**原因**（按概率）：

1. 片源不是普通 mp4（分片 mp4、或根本不是 mp4）；
2. 该集没加密，轨道里没有 `senc`；
3. moov 超过 2MB，两次 Range 都没覆盖到；
4. 头部下载本身失败（CDN 403、Range 不支持）——这种情况更早会打 `下载头部失败`。

**处理**：用 `curl -r 0-1048575` 把头部拉下来，用 mp4 box 工具看 `moov` 位置与四个 box 是否齐全。
`ffprobe` 能读出编码信息不代表有 `senc`，要直接看 box。

### 6.5 磁盘缓存（如果生效）命中但仍黑屏

**现象**：重进同一集立刻黑屏，日志有 `磁盘命中 <series>/<episode>（跳过下载）`，没有材料请求。

**原因**：30 分钟 TTL 内上游换了 `spade_a`，旧 key 打开新分片。

**处理**：清 `cacheDir/hongguo/` 下对应文件；排查服务端是否在轮换密钥。这是已知取舍
（`HongguoDiskCache.kt:20-27`）。注意按第 4.5 节末尾的判断，当前实现的写入会静默失败，
这条日志正常情况下不会出现——如果它出现了，说明运行的是已修过父目录创建的版本。

### 6.6 透传黑屏

**现象**：日志 `prepare 失败，按透传处理（画面会黑）`，随后播放器正常走时间轴但画面全黑。

**原因**：`prepareFor` 任一环节返回 `null`——材料请求失败、缺字段、派生失败、头部失败、解析失败。
代码的设计是「宁可黑屏也不误伤普通片源」，所以失败不会中断播放。

**处理**：按上面 6.1–6.5 的顺序往上找，不要先怀疑解码器。

### 6.7 材料接口 403

**现象**：HTTP 403，`{"ok":false,"error":{"code":"FORBIDDEN","msg":"来源不在白名单"}}`。

**原因**：采集端配了 `security.allowed_referers`，而客户端带来的 `Referer`
（来自 `player_info.headers`）不在白名单里。

**处理**：把片源实际使用的 Referer 域加进白名单，或清空白名单（空 = 不校验）。
注意空 Referer 是放行的，所以「浏览器能开、App 不能播」通常就是这个配置。

### 6.8 404 集不存在

**现象**：材料或播放 404。

**原因**：`book_id` / `index` 与库不一致；或 `url` 里的 `index` 不是 1 起。

**处理**：核对 `vod_play_url` 里的两段数字与 `chapters` 表；确认 `chapter_index` 从 1 开始。

### 6.9 502 源站不可用

**现象**：材料接口返回 502，`error.code = "UPSTREAM_UNAVAILABLE"`。

**原因**：地址失效或 `spade_a` 为空，且 `refresh_materials` 也失败（上游拒绝、身份被拉黑、限流）。

**处理**：看采集端日志的「重采失败」；确认注册身份还有效（6.10）；必要时手动重采该集。

### 6.10 上游 200 + 零字节

**现象**：所有上游请求都「成功」但 body 为空，采集不到任何材料。

**原因**：设备身份未被站方认可——没注册、注册失效、或该指纹被静默拉黑。

**处理**：删掉 `hg_device.json` 触发重注册；确认每次请求都带了 `device_id` / `iid` / `klink_egdi`
三个值；确认注册返回的三个值都非空。注意这一现象和「网络故障」无法从状态码区分。

### 6.11 地址过期

**现象**：播放到一半失败，或点开就是「播放地址无效」；材料接口本身 200。

**原因**：CDN 直链约 6 小时失效，而材料接口在 `url_fresh_seconds`（1800 秒）内会直接发库里的旧地址。
若采集端长时间没有重采，`url_ok` 仍是 1，就会持续发死链。

**处理**：确认定时任务在按 `hongguo_ttl_hours` 重采；临时手动重采该剧。
判断依据是「材料 200 但 `main_url` 直接 GET 返回 403/404」。

### 6.12 解密正常但仍黑屏有声

**现象**：sample 表解析成功、日志有 `sample 表 N 条，解密就绪`，画面依然黑。

**原因**：设备不支持 H.265 硬解（红果是 HEVC）。这是解码问题，不是解密问题。

**处理**：看解码器是否创建成功；换支持 HEVC 的设备验证。两者症状完全相同，不要混在一起排查。

### 6.13 材料里的 `main_url` 与实际播放的不是同一份文件

**现象**：偶发黑屏，重进又好了；日志显示解析成功。

**原因（推断）**：客户端用材料里的 `main_url` 解析 moov，但播放字节走中转 302，由中转**重新**解析地址。
如果两次拿到的是不同清晰度/不同文件，sample 偏移对不上。

**处理**：保证材料接口返回的地址与 302 落点是同一档、同一文件；不要在中转里按请求随机换档。
这是「客户端不直接用 `main_url` 播放」带来的固有约束（见第 1 节第 2 条）。

---

## 7. 未验证与已知限制

以下是代码里**没有**证据、或实现明确不支持的项。写实现时不要把它们当结论：

- **同一集各档位共用一份 `encrypt_info`**：参考实现只取选中档自己的那份，未验证是否共享。
- **`senc` 里的 IV 是否递增**：代码逐条读表，不依赖也不验证递增规律。
- **音频轨是否明文**：从「没有 `senc` 的轨道被跳过」推断音频通常明文，未单独验证。
- **`kid` 与容器 `tenc` KID 的一致性校验**：客户端未实现，采集端只是透传。
- **上游限流/配额**：参考实现里没有针对红果的配额逻辑，实际阈值未验证。
- **`expires_in` 与 CDN 真实有效期不一致**：中转材料返回 `url_fresh_seconds`（1800），
  `/api/hongguo/play` 返回 `EXPIRE_SECONDS`（21600），而实测直链约 6 小时。三者含义不同，
  客户端不读这个字段。
- **`docs/architecture.md:53` 仍写「重建 moov」**：实际实现不重建容器，该句过期（本次按约束未改）。
- **`server/starveilapi/README.md` 的 `player_info` 说明只列了 4 个字段**，
  实际还有 `hongguo` / `hongguo_match`（同样未改，属于另一份文档）。
- **磁盘缓存的落盘路径缺父目录**：`HongguoDiskCache.save()` 用 `"<series>/<episode>"` 当 tag，
  但只创建了 `hongguo/` 这一层目录，写入 `hongguo/<series>/<episode>.key` 会因父目录不存在失败，
  异常被 `runCatching` 吞掉。静态阅读判断磁盘缓存实际不生效；未在设备上实测，详见第 4.5 节。

---

## 8. 字段 / 接口变更记录

相对旧版 `docs/hongguo.md` 的变化。旧文档的定位是「只讲原理、不给做法」，本文改为可实施文档，
所以变化分两类：**事实修正** 与 **定位调整**。

### 8.1 事实修正（旧文档与代码不符）

| # | 旧文档说法 | 当前代码事实 | 依据 |
|---|---|---|---|
| 1 | 解析只需「头部那几十上百 KB」 | moov 实测约 782KB；客户端先拉 800KB，失败补到 2MB | `HongguoPlaying.kt:194-205`、`322-325` |
| 2 | 排查第 4 步「密钥 ID 与容器里记录的是否一致」 | 客户端不读 `kid`，也不校验容器 KID | 全仓库 `.kt` 无 `kid` 读取；材料解析只取 `spade_a`/`main_url` |
| 3 | 「地址有时效，密钥没有，密钥可以缓存」 | 密钥会变：磁盘缓存注释明确写到服务端换 `spade_a` 的场景，因此才有 30 分钟 TTL | `HongguoDiskCache.kt:20-27` |
| 4 | 「插件就是苹果CMS 的后台插件，把 `vod_play_from` 原样下发」 | 实际是 `starveilapi` 独立 API：把 `vod_play_from/url` 还原成 `vod_play_list[]`，并附全站一份 `player_info` | `Vods.php:500-535` |
| 5 | 排查第 5 步「`mdat` 首字节」 | 代码里没有这一步，属于人工排查手段，不是程序行为 | `HongguoPlaying.kt` 无 mdat 读取 |
| 6 | 「IV 看起来像每条递增只是巧合」 | 代码逐条读 `senc`，不依赖递增；该说法无法从代码验证 | `Mp4SampleTable.readIvs` |
| 7 | 第 3 节称采集侧「不开源、不提供部署方式」 | 现有参考实现 `hema-collector`，本文按它写契约 | `E:/Kinova/tools/hema-collector` |
| 8 | 「同一集的所有清晰度档位共用一份加密信息」 | 参考实现只取选中档自己的 `encrypt_info`，无法确认是否共享；已降级为未验证 | `platforms/hongguo/play.py:106-125` |
| 9 | 「容器里没有广域 DRM 的授权描述框」 | 代码只找 `senc`/`stsz`/`stco`/`stsc`，从不检查 `pssh`，无法确认有无 | `Mp4SampleTable.collectTrak` |
| 10 | 第 7 节称「采集端的 `sample_table` 相关代码是死代码」 | 这条链路已整体删除：`chapters.sample_table` 列、`_save_async()`、`_sample_table()`、`platforms/hongguo/mp4.py` 全部移除；客户端自己下载头部解析 moov 得到分片表，材料接口不涉及 | `hema-collector` 2026-10-31 清理后 `grep -rn "sample_table"` 零命中 |

### 8.2 新增内容（旧文档完全没有）

| 新增项 | 说明 |
|---|---|
| **上游要求注册设备身份** | 2026-10-27 起只认注册过的 `device_id` / `iid` / `klink_egdi`；未注册表现为 200 + 零字节。这是本次最重要的新增，旧文档完全没有 |
| **材料接口 `?d=1` 的完整契约** | 路径、扁平 JSON、四个字段、错误码、Referer 白名单 |
| **备用接口 `/api/hongguo/play`** | 入参、`code:0/1` 语义、错误码 |
| **`starveilapi` 下发的 `player_info.hongguo` / `hongguo_match`** | 字段取值、写入链路、客户端匹配规则 |
| **中转 URL 的硬性形状** | `/hongguo/<数字>/<数字>[.mp4]`，以及「两段必须是数字」的原因 |
| **播放字节仍走中转 302** | `main_url` 只用于解析 moov；与采集端注释的说法相反 |
| **`spade_a` 派生算法的完整步骤与自检样本** | 含全部失败条件、`0xeb - popcount(i)` 这类易错点 |
| **sample 表解析的四张 box 与实现限制** | `stco` only、无 `co64`、无 subsample、非分片 mp4 |
| **CENC 解密的已知向量** | key/iv/cipher/plain 四元组，可直接当单测 |
| **最小采集端骨架** | 覆盖注册、取材料、材料响应、302 |
| **13 条按代码分支整理的常见错误** | 每条含现象/原因/处理 |
| **未验证清单** | 明确区分「代码事实」与「推断」 |

### 8.3 保留未变的部分

- 客户端解密、服务端不碰密文的分工；
- 开关默认关闭、规则为空即不命中（`HongguoConfig.matches`）；
- 逐 sample 解密、按 sample 边界切段、不重建容器；
- 三层缓存的设计（材料 / sample 表 / 磁盘）与并发去重；磁盘那一层的落盘当前有缺陷，见第 4.5 节；
- 解密后仍是 HEVC，解码能力是独立问题；
- 「合规风险自负」的定位不变：这条链路的作用是让第三方播放器能播加密片源，使用后果与本文档无关。
