#!/usr/bin/env bash
# 冒烟测试：对着一个已装好本插件的 macCMS 站点跑主链路。
#
# 用法：
#   BASE=http://127.0.0.1:18080/icciu_api.php bash tests/smoke.sh
#
# 约定：
# - 需要能接收 cookie 的 curl；
# - 站点需至少有一条 mac_vod、一个 cuican_topic、一个 cuican_zhibo；
# - 支付回调测试需要 cuican_pay_channel 里有一个 code=epay、driver=epay 的通道，
#   脚本会把它的 key 当作测试密钥（默认 testkey）。
set -u

BASE="${BASE:-http://127.0.0.1:18080/icciu_api.php}"
PHONE="${PHONE:-13900000002}"
PASS="${PASS:-secret123}"
JAR="$(mktemp)"
PASS_COUNT=0
FAIL_COUNT=0

check() {
  local name="$1" expect="$2" body="$3"
  if printf '%s' "$body" | grep -q "$expect"; then
    echo "PASS  $name"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "FAIL  $name  <- $body"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# 扩展正则版：用于需要断言 JSON 结构（数组/字段顺序）的用例。
check_re() {
  local name="$1" expect="$2" body="$3"
  if printf '%s' "$body" | grep -qE "$expect"; then
    echo "PASS  $name"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "FAIL  $name  <- $body"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# 数某个片段出现了几次，用来判断 list 长度。
check_min() {
  local name="$1" min="$2" needle="$3" body="$4" n
  n="$(printf '%s' "$body" | grep -o "$needle" | wc -l | tr -d ' ')"
  if [ "$n" -ge "$min" ]; then
    echo "PASS  $name（$n 条）"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "FAIL  $name（期望 ≥$min 条，实际 $n 条）  <- $body"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# 短剧 SSV 验签用：md5(trans_id|vod_id|episode_number|timestamp|secret)
md5hex() {
  if command -v md5sum >/dev/null 2>&1; then
    printf '%s' "$1" | md5sum | cut -d' ' -f1
  else
    printf '%s' "$1" | openssl dgst -md5 -r | cut -d' ' -f1
  fi
}

echo "== 启动配置 =="
check "startup 返回 code=200" '"code":200' "$(curl -s "$BASE/v1.main/startup")"
check "startup 带 payments 字段" '"payments"' "$(curl -s "$BASE/v1.main/startup")"
STARTUP="$(curl -s "$BASE/v1.main/startup")"
check_re "startup 的 home_modules 为数组且含 banner" '"home_modules":\[[^]]*"banner"' "$STARTUP"
check_re "startup 的 banner_limit 为数字" '"banner_limit":[0-9]+' "$STARTUP"
check_re "startup 的 bottom_tabs 为数组且含 id=home" '"bottom_tabs":\[\{[^]]*"id":"home"' "$STARTUP"
check_re "startup 的 shortplay_type_id 为数字" '"shortplay_type_id":[0-9]+' "$STARTUP"


echo "== 价目表 =="
CATALOG="$(curl -s "$BASE/v1.user/groups")"
check "groups 返回会员组" '"group_points_month"' "$CATALOG"

echo "== 影片 =="
check "列表" '"vod_name"' "$(curl -s "$BASE/v1.vod?page=1&limit=5")"
check "详情含播放源" '"vod_play_list"' "$(curl -s "$BASE/v1.vod/detail?vod_id=1")"
check "分类" '"type_name"' "$(curl -s "$BASE/v1.vod/types")"
check "单榜" '"list"' "$(curl -s "$BASE/v1.vod/vodphb?order_by=vod_hits%20desc&page=1&limit=5")"
check "聚合榜" '"list"' "$(curl -s "$BASE/v1.vod/vodPhbAll")"

echo "== 专题 / 直播 =="
check "专题列表" '"topic_name"' "$(curl -s "$BASE/v1.topic/topicList")"
check "专题详情" '"vod_list"' "$(curl -s "$BASE/v1.topic/topicDetail?topic_id=1")"
check "直播列表" '"name"' "$(curl -s "$BASE/v1.zhibo")"

echo "== 首页内容位 =="
BANNER="$(curl -s "$BASE/v1.main/banner")"
check "banner 返回 code=200" '"code":200' "$BANNER"
check_min "banner list 长度 ≥1" 1 '"pic":' "$BANNER"
check "banner 含 id 字段" '"id":' "$BANNER"
check "banner 含 title 字段" '"title":' "$BANNER"
check "banner 含 pic 字段" '"pic":"http' "$BANNER"
CATEGORY="$(curl -s "$BASE/v1.main/category?need_vod=1")"
check "category 返回 code=200" '"code":200' "$CATEGORY"
check_min "category list 非空" 1 '"layout":' "$CATEGORY"
check "category 含 layout 字段" '"layout":"' "$CATEGORY"
check_re "category 的 vods 为非空数组" '"vods":\[\{' "$CATEGORY"
LINKS="$(curl -s "$BASE/v1.main/links")"
check "links 返回 code=200" '"code":200' "$LINKS"
check_min "links list 非空" 1 '"target_type":' "$LINKS"

echo "== 账号 =="
curl -s -c "$JAR" -X POST "$BASE/v1.auth/register" \
  -d "user_name=$PHONE&user_pwd=$PASS&user_pwd2=$PASS" >/dev/null
LOGIN="$(curl -s -c "$JAR" -X POST "$BASE/v1.auth/login" -d "user_name=$PHONE&user_pwd=$PASS")"
check "登录" '"code":200' "$LOGIN"
check "用户详情" '"user_id"' "$(curl -s -b "$JAR" "$BASE/v1.user/detail")"
check "资料更新" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user" -d 'user_nick_name=测试昵称')"
curl -s -b "$JAR" -X POST "$BASE/v1.user" -d 'group_id=999&user_nick_name=测试昵称' >/dev/null
check "越权字段被忽略（group_id 仍为 1）" '"group_id":1' "$(curl -s -b "$JAR" "$BASE/v1.user/detail")"

echo "== 收藏 =="
check "收藏" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user/ulog" -d 'id=1')"
check "收藏列表" '"vod_id"' "$(curl -s -b "$JAR" "$BASE/v1.user/favs?page=1&limit=20")"
check "取消收藏" '"code":200' "$(curl -s -b "$JAR" -X DELETE "$BASE/v1.user/ulog?ids=1")"

echo "== 播放数据 =="
check "试看" '"trysee"' "$(curl -s -b "$JAR" "$BASE/v1.user/checkVodTrySee?id=1&mid=1&nid=1")"
check "观看时长" '"score"' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user/viewSeconds" -d 'view_seconds=30')"
check "播放记录" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user/addViewLog" -d 'vod_id=1&playSourceIndex=0&urlIndex=0&curProgress=12')"
check "上报播放进度" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.vod/videoProgress" -d 'vod_id=1&source_index=0&episode_index=2&progress=600')"
PROGRESS="$(curl -s -b "$JAR" "$BASE/v1.user/videoProgressList")"
check "继续观看列表" '"code":200' "$PROGRESS"
check_re "继续观看带片名与封面" '"vod_name":"[^"]+","vod_pic":"http' "$PROGRESS"
check_re "继续观看带进度秒数" '"progress_sec":600' "$PROGRESS"
check "未登录继续观看（401）" '"code":401' "$(curl -s "$BASE/v1.user/videoProgressList")"

echo "== 广告位下发 =="
ADS_STARTUP="$(curl -s "$BASE/v1.main/startup")"
check "ads 含 sdk_startup" '"sdk_startup":' "$ADS_STARTUP"
check "ads 含 sdk_banner" '"sdk_banner":' "$ADS_STARTUP"
check "ads 含 sdk_informationflow" '"sdk_informationflow":' "$ADS_STARTUP"
check "ads 含 sdk_excitation" '"sdk_excitation":' "$ADS_STARTUP"
check "ads 含 player_pause" '"player_pause":' "$ADS_STARTUP"
check "广告位带 interval 字段" '"interval":' "$ADS_STARTUP"
check "默认关闭时仍下发 sdk_startup" '"sdk_startup"' "$ADS_STARTUP"
check "默认关闭时广告位 status=0" '"status":0' "$ADS_STARTUP"
check "ads 含 topon 子对象" '"topon":' "$ADS_STARTUP"
check "ads 含 tradplus 子对象" '"tradplus":' "$ADS_STARTUP"
check "topon 透出 app_key" '"app_key":' "$ADS_STARTUP"

echo "== 短剧广告解锁（SSV） =="
SSV_SECRET="${SSV_SECRET:-smoke-ssv-secret}"
SSV_TS="$(date +%s)"
SSV_TRANS="smoke-ssv-$SSV_TS-$$"
SSV_SIGN="$(md5hex "$SSV_TRANS|1|2|$SSV_TS|$SSV_SECRET")"
check "未登录解锁（401）" '"code":401' \
  "$(curl -s -X POST "$BASE/v1.shortplay/unlock" \
    -d "vod_id=1&episode_number=2&trans_id=$SSV_TRANS&timestamp=$SSV_TS&sign=$SSV_SIGN")"
check "错误签名（403）" '"code":403' \
  "$(curl -s -b "$JAR" -X POST "$BASE/v1.shortplay/unlock" \
    -d "vod_id=1&episode_number=2&trans_id=$SSV_TRANS&timestamp=$SSV_TS&sign=00000000000000000000000000000000")"
SSV_OLD_TS=$((SSV_TS - 3600))
check "时间窗过期（403）" '"code":403' \
  "$(curl -s -b "$JAR" -X POST "$BASE/v1.shortplay/unlock" \
    -d "vod_id=1&episode_number=2&trans_id=$SSV_TRANS&timestamp=$SSV_OLD_TS&sign=$(md5hex "$SSV_TRANS|1|2|$SSV_OLD_TS|$SSV_SECRET")")"
UNLOCK="$(curl -s -b "$JAR" -X POST "$BASE/v1.shortplay/unlock" \
  -d "vod_id=1&episode_number=2&trans_id=$SSV_TRANS&timestamp=$SSV_TS&sign=$SSV_SIGN")"
check "正确签名解锁（200）" '"code":200' "$UNLOCK"
check "返回第 2 集地址（来自 seed 的 vod_id=1）" '"url":"https://cdn.example.com/2.m3u8"' "$UNLOCK"
check "首次解锁 unlocked=true" '"unlocked":true' "$UNLOCK"
AGAIN="$(curl -s -b "$JAR" -X POST "$BASE/v1.shortplay/unlock" \
  -d "vod_id=1&episode_number=2&trans_id=$SSV_TRANS&timestamp=$SSV_TS&sign=$SSV_SIGN")"
check "重复 trans_id（200）" '"code":200' "$AGAIN"
check "重复 trans_id 不重复解锁" '"unlocked":false' "$AGAIN"
check "重复 trans_id 仍返回地址" '"url":"https://cdn.example.com/2.m3u8"' "$AGAIN"
SSV_404_TS="$(date +%s)"
SSV_404_TRANS="smoke-ssv-404-$SSV_404_TS-$$"
check "不存在的集（404）" '"code":404' \
  "$(curl -s -b "$JAR" -X POST "$BASE/v1.shortplay/unlock" \
    -d "vod_id=1&episode_number=99&trans_id=$SSV_404_TRANS&timestamp=$SSV_404_TS&sign=$(md5hex "$SSV_404_TRANS|1|99|$SSV_404_TS|$SSV_SECRET")")"

echo "== 弹幕 =="
check "发弹幕" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.danmu" -d 'content=冒烟测试&vod_id=1&at_time=1000&color=%23FF7F00')"
check "拉弹幕" '"content"' "$(curl -s "$BASE/v1.danmu/index?vod_id=1&limit=10&start=0&at_time=0")"


echo "== 评论（与 macCMS 共用 mac_comment） =="
COMMENTS="$(curl -s "$BASE/v1.comment?vod_id=1&page=1&limit=20")"
check "评论列表 200" '"code":200' "$COMMENTS"
check "评论列表含 content 字段" '"content":"' "$COMMENTS"
check_re "评论 total 为数字" '"total":[0-9]+' "$COMMENTS"
check "含演示评论 comment_id=900001（来自 tests/seed_demo.sql）" '"comment_id":900001' "$COMMENTS"
check "游客评论回退 comment_name" '"user_nick_name":"游客"' "$COMMENTS"
check "评论列表字段齐全" '"user_portrait"' "$COMMENTS"
check "未登录发评论（401）" '"code":401' \
  "$(curl -s -X POST "$BASE/v1.comment" -d 'vod_id=1&content=未登录不该发出去')"
# 30 秒冷却按 user_id 算，重跑前先灌 seed（seed 会清掉本测试号的旧评论）。
check "登录发评论（200）" '"code":200' \
  "$(curl -s -b "$JAR" -X POST "$BASE/v1.comment" -d 'vod_id=1&content=冒烟测试评论')"
check "刚发的评论出现在列表" '冒烟测试评论' "$(curl -s "$BASE/v1.comment?vod_id=1&page=1&limit=60")"
check "30 秒内连发（429）" '"code":429' \
  "$(curl -s -b "$JAR" -X POST "$BASE/v1.comment" -d 'vod_id=1&content=连发第二条')"
echo "== 支付 =="
check "充值提示" '"code":200' "$(curl -s -b "$JAR" "$BASE/v1.user/payTip")"
check "金币提示" '"msg"' "$(curl -s -b "$JAR" "$BASE/v1.user/goldTip")"
ORDER="$(curl -s -b "$JAR" -X POST "$BASE/v1.user/order" -d 'price=15&payment=epay')"
check "下单" '"order_code"' "$ORDER"
ORDER_CODE="$(printf '%s' "$ORDER" | sed -n 's/.*"order_code":"\([^"]*\)".*/\1/p')"
check "查单" '"order_status"' "$(curl -s -b "$JAR" "$BASE/v1.user/order?order_code=$ORDER_CODE")"
PAY_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v1.user/pay?payment=epay&order_code=$ORDER_CODE")"
check "支付页跳转（302）" '302' "$PAY_STATUS"

echo "== 卡密（固定卡 CARD-TEST-VOID-1，来自 tests/seed_demo.sql） =="
check "未登录兑换（401）" '"code":401' "$(curl -s -X POST "$BASE/v1.user/buy" -d 'card_pwd=CARD-TEST-VOID-1')"
check "登录兑换会员卡（200）" '"code":200' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user/buy" -d 'card_pwd=CARD-TEST-VOID-1')"
check "同一张卡再兑（409）" '"code":409' "$(curl -s -b "$JAR" -X POST "$BASE/v1.user/buy" -d 'card_pwd=CARD-TEST-VOID-1')"

echo "订单号: $ORDER_CODE（回调测试由 tests/notify.sh 完成）"
echo "----"
echo "PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
rm -f "$JAR"
[ "$FAIL_COUNT" -eq 0 ]
