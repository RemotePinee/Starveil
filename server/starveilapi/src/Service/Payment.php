<?php
/**
 * 支付与会员兑换。
 *
 * 五个安全点，全部针对的洞：
 * 1. 金额由服务端按套餐算，客户端只能传套餐编号（表单只收 price，可改成 0.01）；
 * 2. 回调验签失败直接拒绝（旧 用 `=== 0` 判失败，验签返回 false 时反而放行）；
 * 3. 回调按 `order_status=0 → 1` 单向迁移，重复回调幂等，且校验回调金额；
 * 4. 会员开通只认「已支付 + 未开通」的订单：天数据订单快照，`opened_at` 决定谁开，
 * 回调与客户端各调一次也只延长一次；
 * 5. 回调与支付页都只认「下单时那个通道」：`order_pay_type` 与传入通道不一致直接拒绝，
 * 否则知道弱通道密钥就能给强通道的订单伪造成功回调；支付页也一律按订单字段签名，
 * 请求里的 `payment` 可伪造，换通道会把钱收进别的商户号而回调按原通道验签、恒 400。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Orders;
use Starveil\Api\Repo\Users;
use Starveil\Api\Support\MacPay;
use Starveil\Api\Support\Setting;
use think\Db;

final class Payment
{
    /** 后台生成的两种卡密类型（`Admin/Actions::createCards` 只写这两个值）。 */
    private const CARD_TYPE_VIP = 'vip';
    private const CARD_TYPE_GOLD = 'gold';

    public function __construct(
        private Orders $orders = new Orders(),
        private Users $users = new Users(),
        private Membership $membership = new Membership(),
    ) {
    }

    /**
     * 价目表（实现见 `Plans`：下单反查、开通回落、后台展示都用它）。
     *
     * @return array<int,array{id:int,group_id:int,period:string,days:int,price_yuan:float}>
     */
    public function plans(): array
    {
        return (new Plans())->all();
    }

    /**
     * 下单。
     *
     * 客户端只传 `price`（元），不传套餐 id；这里按价格反查套餐，
     * 查不到直接拒绝——绝不能拿客户端传的价格建单（就是这样被改成 0.01 的）。
     *
     * @return array{code:int,msg:string,order?:array}
     */
    public function createOrder(int $userId, float $price, string $channel): array
    {
        if (Setting::int('member_enabled', 1) !== 1) {
            return ['code' => 403, 'msg' => '会员功能未开启'];
        }
        $plan = null;
        foreach ($this->plans() as $candidate) {
            if (abs($candidate['price_yuan'] - $price) < 0.001) {
                $plan = $candidate;
            }
        }
        if ($plan === null || $plan['price_yuan'] <= 0) {
            return ['code' => 400, 'msg' => '套餐不存在'];
        }

        // 老客户端下单只送 price，`postOrder` 拿到的 payment 是空串；硬要通道会让它们
        // 彻底下不了单。空串只在「恰好一条启用通道」时替用户选（没有歧义），
        // 零条或多条都不猜——猜错等于把这笔钱收进别的商户号。
        $channel = trim($channel);
        $channelRow = null;
        if ($channel === '') {
            $usable = $this->enabledChannels();
            if ($usable === []) {
                return ['code' => 400, 'msg' => '未配置支付通道'];
            }
            if (count($usable) > 1) {
                // Json::fail 的 data 固定为 null，可用通道列表塞不进返回体，只给文案
                return ['code' => 400, 'msg' => '请选择支付方式'];
            }
            $channelRow = $usable[0];
            $channel = (string)$channelRow['code'];
        } else {
            $channelRow = $this->channelRow($channel);
        }
        if (!$channelRow) {
            return ['code' => 400, 'msg' => '支付方式未开启'];
        }

        // 秒级时间 + 4 位随机只有 9000 种组合，订单号可枚举；换成 64 位随机十六进制
        $code = 'PAY' . date('Ymd') . bin2hex(random_bytes(8));
        $this->orders->create(
            $userId,
            $code,
            (float)$plan['price_yuan'],
            $channel,
            'vip',
            $plan['id'],
            ['group_id' => $plan['group_id'], 'days' => $plan['days'], 'period' => $plan['period']],
        );

        return [
            'code' => 200,
            'msg' => '下单成功',
            'order' => [
                // 字段与客户端 OrderDto 对齐：order_price 是字符串，order_time 是秒级时间戳
                'user_id' => $userId,
                'order_code' => $code,
                'order_price' => number_format((float)$plan['price_yuan'], 2, '.', ''),
                'order_time' => time(),
                'order_points' => 0,
                'pay_url' => $this->payUrl($channelRow, $code, (float)$plan['price_yuan'], '会员开通'),
            ],
        ];
    }

    /**
     * 支付页跳转地址。
     *
     * 客户端在系统浏览器里打开 `/icciu_api.php/v1.user/pay?payment=..&order_code=..`
     * （`PayRepository.payPageUrl`），这一步没有会话 cookie，所以只凭订单号查——
     * 订单号是随机串，且只用于拼网关地址，不泄露用户信息。
     *
     * 通道**只取订单里的 `order_pay_type`**，请求里的 `payment` 一律不看：那个参数
     * 客户端随手能改，下单选 A、支付页改成 B 会把钱收进 B 的商户号，而回调按订单的
     * A 验签 → 恒 400，钱收了会员没开。下单时落库的字段不会骗人，只能信它。
     *
     * @param string $channel 已废弃：保留只为不改调用方，实现里完全忽略
     *
     * @return array{code:int,msg:string,url?:string}
     */
    public function payPage(string $orderCode, string $channel): array
    {
        $order = $this->orders->byCode($orderCode);
        if (!$order) {
            return ['code' => 404, 'msg' => '订单不存在'];
        }
        if ((int)$order['order_status'] !== 0) {
            return ['code' => 409, 'msg' => '订单已支付'];
        }
        // 停用的通道照查：订单已经落库，运营中途关掉通道不能把已下的单卡死在这里
        $row = $this->channelRow((string)($order['order_pay_type'] ?? ''), true);
        if (!$row) {
            // 绝不回退到请求参数：那等于把上面那个洞重新打开
            return ['code' => 409, 'msg' => '订单支付方式异常，请联系客服'];
        }
        $url = $this->payUrl($row, $orderCode, (float)$order['order_price'], '会员开通');
        if ($url === '') {
            return ['code' => 400, 'msg' => '支付通道配置不完整'];
        }
        return ['code' => 200, 'msg' => 'ok', 'url' => $url];
    }

    public function orderStatus(int $userId, string $code): array
    {
        $order = $this->orders->byCode($code);
        if (!$order || (int)$order['user_id'] !== $userId) {
            return ['code' => 404, 'msg' => '订单不存在'];
        }
        return [
            'code' => 200,
            'msg' => 'ok',
            'order' => [
                // 与客户端 OrderStatusDto 对齐（order_price 必须是字符串）
                'order_id' => (int)$order['order_id'],
                'user_id' => (int)$order['user_id'],
                'order_status' => (int)$order['order_status'],
                'order_code' => (string)$order['order_code'],
                'order_price' => number_format((float)$order['order_price'], 2, '.', ''),
                'order_time' => (int)$order['order_time'],
                'order_points' => (int)$order['order_points'],
                'order_pay_type' => (string)$order['order_pay_type'],
                'order_pay_time' => (int)$order['order_pay_time'],
                'order_remarks' => '',
            ],
        ];
    }

    /**
     * 会员兑换。
     *
     * 只承认「已支付、并且没开通过」的订单，判定与延长都在 `Membership`：
     * 天数取订单快照，客户端传的 group_id/long 不参与时长计算。
     *
     * @return array{code:int,msg:string}
     */
    public function upgrade(int $userId, int $groupId, string $period, ?string $orderCode = null): array
    {
        return $this->membership->upgrade($userId, $groupId, $period, $orderCode);
    }

    /**
     * 卡密兑换。
     *
     * 客户端只提交一个字段 `card_pwd`，所以约定
     * **卡号即卡密**：后台生成的 `card_no` 必须是不可猜的随机串，`card_pwd` 列留空。
     * 这样不会为了兼容单字段而丢掉唯一一次核销机会。
     *
     * 成功时 `data` 带回核销结果（`card_type` / `card_value` / `group_id` / `membership_end`），
     * 客户端据此区分"会员延长 N 天"与"获得 N 金币"；不读新字段的调用方不受影响。
     *
     * @return array{code:int,msg:string,data?:array{card_type:string,card_value:int,group_id:int,membership_end:int}}
     */
    public function buyCard(int $userId, string $cardCode): array
    {
        $table = Installer::prefix() . 'cuican_card';
        // 用户手输的卡密可能带空格或小写，先规范化再查；后台 `card_no` 一律大写十六进制
        $code = strtoupper(trim($cardCode));
        if ($code === '') {
            return ['code' => 400, 'msg' => '请输入卡密'];
        }
        $card = Db::table($table)->where('card_no', $code)->find();
        if (!$card) {
            return ['code' => 400, 'msg' => '卡密无效或已使用'];
        }
        // 已使用 / 已作废都按冲突返回 409，客户端据此提示"换一张"而不是"输错了"
        if ((int)$card['card_status'] !== 0) {
            return [
                'code' => 409,
                'msg' => (int)$card['card_status'] === 2 ? '卡密已作废' : '卡密已被使用',
            ];
        }
        $type = (string)$card['card_type'];
        if ($type !== self::CARD_TYPE_VIP && $type !== self::CARD_TYPE_GOLD) {
            // 只记 card_id：卡号本身是凭据，用户 id 也不进日志（docs/code-standards.md 第 6 节）
            error_log('[starveil] 卡密类型异常 card_id=' . (int)$card['card_id']);
            return ['code' => 400, 'msg' => '卡密类型异常，请联系管理员'];
        }
        // 会员卡在会员关闭期间不可核销：金币卡不受影响，运营仍可用卡密发金币。
        if ($type === self::CARD_TYPE_VIP && Setting::int('member_enabled', 1) !== 1) {
            return ['code' => 403, 'msg' => '会员功能未开启，会员卡暂不可兑换'];
        }
        $affected = Db::table($table)
            ->where('card_id', (int)$card['card_id'])
            ->where('card_status', 0)
            ->update(['card_status' => 1, 'used_by' => $userId, 'used_time' => time()]);
        if ($affected === 0) {
            return ['code' => 409, 'msg' => '卡密已被使用'];
        }
        $value = (int)$card['card_value'];
        if ($type === self::CARD_TYPE_VIP) {
            $this->users->extendMembership($userId, Setting::int('vip_group_id', 3), $value);
        } else {
            $this->users->addGold($userId, $value);
        }
        // 兑换后重查一次 mac_user：客户端要的是"兑换后的最新状态"，不是算出来的
        $user = $this->users->findById($userId);
        $groups = explode(',', (string)($user['group_id'] ?? ''));
        return [
            'code' => 200,
            'msg' => '兑换成功',
            'data' => [
                'card_type' => $type,
                'card_value' => $value,
                // group_id 可能是 "1,3" 这种多组串；extendMembership 把新组追加在末尾
                'group_id' => (int)trim((string)end($groups)),
                // 金币卡不动会员到期时间，固定 0，客户端据此不展示"到期"
                'membership_end' => $type === self::CARD_TYPE_VIP ? (int)($user['user_end_time'] ?? 0) : 0,
            ],
        ];
    }

    /**
     * 单集购买：扣积分并落一条已支付订单，试看接口据此放行。
     *
     * 集号必须跟着落库：订单只写 `target_id`（剧 id）时，买一集等于买下整部，
     * 判定见 `Orders::hasPaidEpisode`。`$episodeNumber = 0` 是留给还不知道集号的
     * 老调用方的退路，此时退回整部判定（与加列前一致）。
     *
     * 扣分与建单在同一事务里：条件扣减扣不动就回滚，绝不留一条没扣到积分的已付订单。
     * 并发双击由两个闸挡住（事务内的用户行锁 + 唯一键兜底），见方法内注释。
     */
    public function buyEpisode(int $userId, int $vodId, int $payType, int $episodeNumber = 0): array
    {
        if ($vodId <= 0) {
            return ['code' => 400, 'msg' => '影片不存在'];
        }
        // 幂等快路径：已买过就直接放行，连事务都不开
        if ($this->orders->hasPaidEpisode($userId, $vodId, $episodeNumber)) {
            return ['code' => 200, 'msg' => '已购买，可直接观看'];
        }
        $price = Setting::int('episode_points', 0);
        // 订单号先生成：收尾要按它精确置已支付，不能靠 like 'EP%' 圈一批
        $code = 'EP' . date('YmdHis') . random_int(1000, 9999);
        Db::startTrans();
        try {
            // 上面那次查询在事务外：并发双击的两个请求都会查到"没买过"，然后各扣一次
            // 积分、各建一张已付单。锁住用户行把「再查一次 → 扣分 → 建单」串行化：
            // 后到的请求在这里等前一个提交，重新查到刚建的已付订单就退出，不扣第二遍。
            Db::name('user')->where('user_id', $userId)->lock(true)->find();
            if ($this->orders->hasPaidEpisode($userId, $vodId, $episodeNumber)) {
                Db::commit();
                return ['code' => 200, 'msg' => '已购买，可直接观看'];
            }
            // 条件扣减：「余额够」与「扣掉」是一条 UPDATE，并发下不会扣成负数
            if ($price > 0 && !$this->users->deductPoints($userId, $price)) {
                Db::rollback();
                return ['code' => 400, 'msg' => '积分不足'];
            }
            // 第二道闸：库里若加了 (user_id,target_type,target_id,episode_number) 唯一键，
            // 撞 1062 说明这集已被并发的另一个请求写进订单，回滚掉刚扣的积分按"已购买"返回
            $orderId = $this->orders->createOnce($userId, $code, 0.0, 'points', 'episode', $vodId, [
                'pay_type' => $payType,
                'points' => $price,
                // 快照进 ulog_data：事后查"这单买的是哪一集"不用猜
                'nid' => max($episodeNumber, 0),
            ], $episodeNumber);
            if ($orderId === null) {
                Db::rollback();
                return ['code' => 200, 'msg' => '已购买，可直接观看'];
            }
            Db::table(Installer::prefix() . 'cuican_order')
                ->where('order_code', $code)
                ->update(['order_status' => 1, 'order_pay_time' => time()]);
            Db::commit();
        } catch (\Throwable $e) {
            Db::rollback();
            return ['code' => 500, 'msg' => '购买失败，请稍后重试'];
        }
        return ['code' => 200, 'msg' => '购买成功'];
    }

    /**
     * 支付回调。
     *
     * 目前只实现 Epay（md5 签名）通道；其它通道在配置齐全前明确报错，
     * 不做"验不过也当成功"的兜底。
     *
     * @return array{code:int,msg:string}
     */
    public function notify(string $channel, array $params): array
    {
        // 不看 status：运营可以在用户下单后停用通道，已经付掉的这一笔必须还能走完
        $row = $this->channelRow($channel, true);
        if (!$row) {
            return ['code' => 404, 'msg' => '通道不存在'];
        }
        $config = json_decode((string)($row['config'] ?? '{}'), true);
        if (!is_array($config) || ($config['driver'] ?? '') !== 'epay') {
            return ['code' => 501, 'msg' => '该通道暂未实现回调验签'];
        }

        if (!$this->verifyEpay($params, (string)($config['key'] ?? ''))) {
            return ['code' => 400, 'msg' => '验签失败'];
        }
        if (($params['trade_status'] ?? '') !== 'TRADE_SUCCESS') {
            return ['code' => 400, 'msg' => '订单未支付'];
        }

        $orderCode = (string)($params['out_trade_no'] ?? '');
        $amount = (float)($params['money'] ?? 0);
        $order = $this->orders->byCode($orderCode);
        if (!$order) {
            return ['code' => 404, 'msg' => '订单不存在'];
        }
        // 验签用的是"传入通道"的密钥：不比对订单所属通道，知道弱通道密钥的人
        // 就能给强通道的订单伪造一条成功回调（多通道并存时的提权路径）
        if ((string)$order['order_pay_type'] !== $channel) {
            return ['code' => 400, 'msg' => '通道不匹配'];
        }

        if (!$this->orders->markPaid($orderCode, $amount)) {
            // 重复回调：只有「已支付 + 金额一致」的单继续往下走，用来补开上次没开成的那笔
            // （网关重试、DB 抖动）；其余一律按"不存在或已处理"拒绝
            if ((int)$order['order_status'] !== 1
                || abs((float)$order['order_price'] - $amount) > 0.001) {
                return ['code' => 400, 'msg' => '金额不符或已处理'];
            }
        }
        try {
            // 开通只做一次：opened_at 的条件更新挡住重复回调，天数据订单快照
            $this->membership->openByOrder($order);
        } catch (\Throwable $e) {
            // 返回失败让网关重试；opened_at 已随事务回滚，下次回调还能开
            return ['code' => 500, 'msg' => '开通失败，请稍后重试'];
        }
        return ['code' => 200, 'msg' => 'ok'];
    }

    /**
     * 从回调请求里定出通道 code。
     *
     * 三个来源按优先级试：路径段（CMS 的 `pay_type/epay` 形态）、`pay_type` 查询参数、
     * 网关自己回传的 `type`。都认不出时退回订单下单时用的通道——网关卡掉查询串、
     * 或只回 `type=alipay` 这种渠道名（不是我们的通道 code）时，支付链仍然通得过去。
     *
     * @param array<int,string> $candidates
     */
    public function resolveChannel(array $candidates, string $orderCode = ''): string
    {
        foreach ($candidates as $candidate) {
            $code = trim((string)$candidate);
            if ($code !== '' && $this->channelRow($code, true) !== null) {
                return $code;
            }
        }
        if ($orderCode !== '') {
            $order = $this->orders->byCode($orderCode);
            $code = trim((string)($order['order_pay_type'] ?? ''));
            if ($code !== '' && $this->channelRow($code, true) !== null) {
                return $code;
            }
        }
        return '';
    }

    /** 站点根地址，用于拼回调；CLI 下为空，由调用方兜底。 */
    private function siteBase(): string
    {
        $host = (string)($_SERVER['HTTP_HOST'] ?? '');
        if ($host === '') {
            return '';
        }
        $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
            || (int)($_SERVER['SERVER_PORT'] ?? 0) === 443;
        return ($https ? 'https://' : 'http://') . $host;
    }

    private function verifyEpay(array $params, string $key): bool
    {
        $sign = (string)($params['sign'] ?? '');
        if ($sign === '' || $key === '') {
            return false;
        }
        // pay_type/channel 是我们自己的路由参数，不属于渠道签名串，必须剔除
        unset($params['sign'], $params['sign_type'], $params['pay_type'], $params['channel']);
        ksort($params);
        $pairs = [];
        foreach ($params as $k => $v) {
            if ($v === '' || is_array($v)) {
                continue;
            }
            $pairs[] = $k . '=' . $v;
        }
        $expected = md5(implode('&', $pairs) . $key);
        return hash_equals($expected, $sign);
    }

    /**
     * 取通道：先查插件自己的表（可覆盖 macCMS 配置），没有则回退到 macCMS 的 Epay 配置。
     *
     * `$includeDisabled = true` 给「订单已经落库」的场景用：回调和支付页必须认已停用的
     * 通道，否则运营在下单后关掉通道，这笔钱付了也永远开不了会员（回调恒 404）。
     * 下单走默认的 false：停用的通道不该再接新单。
     */
    private function channelRow(string $code, bool $includeDisabled = false): ?array
    {
        $code = trim($code);
        if ($code === '') {
            return null;
        }
        $query = Db::table(Installer::prefix() . 'cuican_pay_channel')->where('code', $code);
        if (!$includeDisabled) {
            $query->where('status', 1);
        }
        $row = $query->find();
        if ($row) {
            return $row;
        }
        $mac = MacPay::epayChannel();
        return ($mac !== null && $mac['code'] === $code) ? $mac : null;
    }

    /**
     * 全部启用中的通道，排序与 startup 下发的一致（后台 `sort` 升序）。
     *
     * 插件表里一条都没配时复用 macCMS「在线支付配置」里的 Epay——与 `Startup::payload`
     * 同一口径，否则客户端看到的支付方式和下单能用的通道会对不上。
     *
     * @return array<int,array<string,mixed>>
     */
    private function enabledChannels(): array
    {
        $rows = Db::table(Installer::prefix() . 'cuican_pay_channel')
            ->where('status', 1)
            ->order('sort asc, code asc')
            ->select();
        $rows = is_array($rows) ? array_values($rows) : [];
        if ($rows !== []) {
            return $rows;
        }
        $mac = MacPay::epayChannel();
        return $mac === null ? [] : [$mac];
    }

    /**
     * 拼支付跳转地址。
     *
     * 网关与站点是两个不同的地址：`$gateway` 拼最终跳转，`$site` 只给
     * notify_url / return_url 兜底。两者以前共用一个变量，`$site` 把网关覆盖掉了，
     * pay_url 永远指回本站首页，订单一条都付不出去。
     */
    private function payUrl(array $channel, string $orderCode, float $price, string $subject): string
    {
        $config = json_decode((string)($channel['config'] ?? '{}'), true);
        $config = is_array($config) ? $config : [];
        $gateway = MacPay::submitUrl((string)($config['url'] ?? ''));
        $site = $this->siteBase();
        $pid = (string)($config['pid'] ?? '');
        $key = (string)($config['key'] ?? '');
        if ($gateway === '' || $pid === '' || $key === '') {
            return '';
        }
        $channelCode = (string)($channel['code'] ?? 'epay');
        $params = [
            'pid' => $pid,
            'type' => (string)($config['type'] ?? 'epay'),
            'out_trade_no' => $orderCode,
            // 回调地址必须由服务端生成：后台没填时自动指向插件回调，否则支付后永远回调不到
            'notify_url' => (string)($config['notify_url'] ?? $this->notifyUrl($site, $channelCode)),
            'return_url' => (string)($config['return_url'] ?? ($site === '' ? '' : $site . '/')),
            'name' => $subject,
            'money' => number_format($price, 2, '.', ''),
        ];
        ksort($params);
        $pairs = [];
        foreach ($params as $k => $v) {
            $pairs[] = $k . '=' . $v;
        }
        $params['sign'] = md5(implode('&', $pairs) . $key);
        $params['sign_type'] = 'MD5';
        return $gateway . (str_contains($gateway, '?') ? '&' : '?') . http_build_query($params);
    }

    /**
     * 回调地址：把通道 code 带出去，网关回来时才知道该拿哪个通道的密钥验签。
     *
     * CMS 的形态是路径段（`/index.php/payment/notify/pay_type/epay`），但本插件的路由
     * 只认 `v1.Payment/notify` 两段（`Kernel::resolve`），多一段直接 404，所以通道
     * 放在查询串里；控制器同时认路径段与 `type`，路由将来放宽时这里的 URL 不用改。
     */
    private function notifyUrl(string $site, string $channelCode): string
    {
        if ($site === '') {
            return '';
        }
        return $site . '/icciu_api.php/v1.Payment/notify?pay_type=' . rawurlencode($channelCode);
    }
}
