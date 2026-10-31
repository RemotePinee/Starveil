#!/usr/bin/env bash
# 支付回调验签与幂等测试。
#
# 用法：ORDER_CODE=PAY... KEY='<YOUR_EPAY_KEY>' MONEY=15.00 bash tests/notify.sh
# 站点需有一个 code=epay、config.driver=epay 的通道，KEY 与通道里的 key 一致。
set -u

BASE="${BASE:-http://127.0.0.1:18080/icciu_api.php}"
ORDER_CODE="${ORDER_CODE:?需要 ORDER_CODE}"
KEY="${KEY:-<YOUR_EPAY_KEY>}"
MONEY="${MONEY:-15.00}"

sign() {
  local code="$1" money="$2"
  # Epay：扣掉 sign/sign_type 后按 key 升序拼 k=v&... 追加 key 再 md5
  printf 'money=%s&name=%s&out_trade_no=%s&pid=%s&trade_status=%s&type=%s%s' \
    "$money" "会员开通" "$code" "1000" "TRADE_SUCCESS" "alipay" "$KEY" | md5sum | cut -d' ' -f1
}

SIGN="$(sign "$ORDER_CODE" "$MONEY")"
URL="$BASE/v1.Payment/notify?pay_type=epay&out_trade_no=$ORDER_CODE&money=$MONEY&trade_status=TRADE_SUCCESS&pid=1000&type=alipay&name=%E4%BC%9A%E5%91%98%E5%BC%80%E9%80%9A&sign=$SIGN&sign_type=MD5"

echo "== 正确签名 =="
curl -s "$URL"; echo
echo "== 重复回调（幂等，应 fail） =="
curl -s "$URL"; echo
echo "== 错误金额（应 fail） =="
BAD="$(sign "$ORDER_CODE" "0.01")"
curl -s "$BASE/v1.Payment/notify?pay_type=epay&out_trade_no=$ORDER_CODE&money=0.01&trade_status=TRADE_SUCCESS&pid=1000&type=alipay&name=%E4%BC%9A%E5%91%98%E5%BC%80%E9%80%9A&sign=$BAD&sign_type=MD5"; echo
echo "== 错误签名（应 fail） =="
curl -s "$BASE/v1.Payment/notify?pay_type=epay&out_trade_no=$ORDER_CODE&money=$MONEY&trade_status=TRADE_SUCCESS&pid=1000&type=alipay&name=%E4%BC%9A%E5%91%98%E5%BC%80%E9%80%9A&sign=deadbeef&sign_type=MD5"; echo
