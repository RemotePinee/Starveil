#!/usr/bin/env bash
# 插件后台自检：逐页渲染一遍，确认没有 SQL/模板错误。
#
# 用法（站点主机上）：
#   SITE=/path/to/maccms bash tests/admin.sh
# 或用容器：
#   SITE=/var/www/html PHP_CMD="docker exec starveil-web php" bash tests/admin.sh
set -u

SITE="${SITE:-.}"
PHP_CMD="${PHP_CMD:-php}"
PASS=0
FAIL=0

for page in dashboard settings cards topics live pay withdraw orders users banner blocks links tabs; do
  OUT="$($PHP_CMD "$SITE/addons/starveilapi/admin.php" "$page" 2>&1)"
  if [ -n "$OUT" ] && ! printf '%s' "$OUT" | grep -qiE 'fatal|exception|class not exist|SQLSTATE'; then
    echo "PASS  $page"
    PASS=$((PASS + 1))
  else
    echo "FAIL  $page <- $(printf '%s' "$OUT" | head -c 200)"
    FAIL=$((FAIL + 1))
  fi
done

# cards 页专项：导出入口 / 作废入口 / 已作废筛选 / 今日核销统计
CARDS="$($PHP_CMD "$SITE/addons/starveilapi/admin.php" cards 2>&1)"
for needle in '导出 CSV' '已作废' '作废' '今日核销'; do
  if printf '%s' "$CARDS" | grep -q "$needle"; then
    echo "PASS  cards 含「$needle」"
    PASS=$((PASS + 1))
  else
    echo "FAIL  cards 缺「$needle」"
    FAIL=$((FAIL + 1))
  fi
done

# 导出直出 CSV：第一行必须是六列表头（BOM 在最前，不影响匹配）
CSV_HEAD="$($PHP_CMD "$SITE/addons/starveilapi/admin.php" cards export 2>&1 | head -1)"
if printf '%s' "$CSV_HEAD" | grep -q '"卡号","类型","面值","状态","使用人","使用时间"'; then
  echo "PASS  cards export 表头"
  PASS=$((PASS + 1))
else
  echo "FAIL  cards export 表头 <- $(printf '%s' "$CSV_HEAD" | head -c 200)"
  FAIL=$((FAIL + 1))
fi

# topics 页专项：选片绑定 / 已绑定影片列表 / 单条移除 / 上移下移
TOPICS="$($PHP_CMD "$SITE/addons/starveilapi/admin.php" topics 2>&1)"
for needle in '绑定影片' '专题列表' '上移' '下移' '移除' '编辑'; do
  if printf '%s' "$TOPICS" | grep -q "$needle"; then
    echo "PASS  topics 含「$needle」"
    PASS=$((PASS + 1))
  else
    echo "FAIL  topics 缺「$needle」"
    FAIL=$((FAIL + 1))
  fi
done

# live 页专项：编辑 / 状态 / 上移下移
LIVE="$($PHP_CMD "$SITE/addons/starveilapi/admin.php" live 2>&1)"
for needle in '添加直播' '上移' '下移' '编辑'; do
  if printf '%s' "$LIVE" | grep -q "$needle"; then
    echo "PASS  live 含「$needle」"
    PASS=$((PASS + 1))
  else
    echo "FAIL  live 缺「$needle」"
    FAIL=$((FAIL + 1))
  fi
done

echo "----"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
