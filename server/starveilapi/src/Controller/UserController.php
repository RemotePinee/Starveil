<?php
/**
 * 用户中心：资料、收藏、播放记录、试看、订单、会员、卡密、单集购买。
 *
 * 所有写操作都要求登录，且只接受显式字段名—— `postIndex` 直接把
 * 整个 `input()` 交给 `allowField(true)`，用户可以自改 `group_id` 与积分。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Users;
use Starveil\Api\Repo\Vods;
use Starveil\Api\Service\Payment;
use Starveil\Api\Support\Setting;
use Starveil\Api\Service\Playback;
use think\Db;

final class UserController extends BaseController
{
    /** 首页「继续观看」返回条数：横滑一屏够看，多了没人翻。 */
    private const CONTINUE_LIMIT = 10;


    /** 完成即由各自接口发奖、不该出现在「领取」按钮上的任务。 */
    private const AUTO_CLAIM_ACTIONS = ['daily_sign', 'share_vod'];

    /** 客户端历史上只认这三个短 key，对到 macCMS 的 task_action。 */
    private const TASK_KEYS = [
        'sign' => 'daily_sign',
        'watch' => 'watch_vod',
        'share' => 'share_vod',
    ];

    /** task_action -> 客户端短 key；后台新增的任务没有短 key，原样下发。 */
    private static function shortKey(string $action): string
    {
        $short = array_search($action, self::TASK_KEYS, true);

        return $short === false ? $action : (string)$short;
    }

    public function getDetail(): void
    {
        $user = $this->requireLogin();
        $ext = (new Users())->ensureExt((int)$user['user_id']);
        Json::ok([
            'user_id' => (int)$user['user_id'],
            'user_name' => (string)$user['user_name'],
            // macCMS 的昵称是 urlencode 存的，解码后再下发，客户端不该看到 %E6%...
            'user_nick_name' => urldecode((string)$user['user_nick_name']),
            'user_portrait' => (string)$user['user_portrait'],
            'group_id' => (int)$user['group_id'],
            // mac_group.group_name 也是 urlencode 存的（后台显示时解码），同样解码后再下发
            'group_name' => urldecode((string)$user['group_name']),
            'user_end_time' => (int)($user['user_end_time'] ?? 0),
            // 两套字段名都给：user_points / user_gold 与 points / gold 并存。
            // 少给一套就会在对应客户端上显示成 0，用户看到的就是"假的积分金币"。
            'points' => (int)$user['user_points'],
            'user_points' => (int)$user['user_points'],
            'gold' => (string)(int)$ext['gold'],
            'user_gold' => (string)(int)$ext['gold'],
            'view_seconds' => (int)$ext['view_seconds'],
            // 统一 0/1，不直接回布尔：按 Int 解析的客户端遇到布尔会让整包失败
            'is_vip' => (new Playback())->isVip((int)$user['user_id']) ? 1 : 0,
        ]);
    }

    public function postIndex(): void
    {
        $user = $this->requireLogin();
        // 白名单在仓库层再收一次：这里只挑出允许改的两列
        (new Users())->updateAllowed((int)$user['user_id'], [
            'user_nick_name' => $this->input->str('user_nick_name'),
            'user_portrait' => $this->input->str('user_portrait'),
        ]);
        Json::ok(null, '资料已更新');
    }

    public function getFavs(): void
    {
        $user = $this->requireLogin();
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        $table = Installer::prefix() . 'cuican_ulog';

        // 同 Vods::list：TP5.0 下 clone Query 做 count 会污染 bind，必须各建一个
        $total = Db::table($table)->where('user_id', (int)$user['user_id'])->count();
        $rows = Db::table($table)
            ->where('user_id', (int)$user['user_id'])
            ->order('created_at desc')
            ->page($page, $limit)
            ->select();

        $vodIds = array_map(static fn (array $row): int => (int)$row['vod_id'], $rows);

        // 按收藏里的 vod_id 批量取片：固定取"最新 60 部影片"再对收藏行，
        // 老收藏只要不在最新 60 里就整条消失（用户看到收藏越用越少）。
        // 一次 IN 查完，不做逐行查询。
        $vods = $vodIds === [] ? [] : (new Vods())->byIds($vodIds);
        $indexed = [];
        foreach ($vods as $vod) {
            $indexed[(int)$vod['vod_id']] = $vod;
        }

        $list = [];
        foreach ($rows as $row) {
            $vod = $indexed[(int)$row['vod_id']] ?? null;
            if ($vod === null) {
                continue;
            }
            $list[] = [
                'ulog_id' => (int)$row['ulog_id'],
                'vod_id' => (int)$row['vod_id'],
                'title' => $vod['vod_name'],
                'poster' => $vod['vod_pic'],
                'created_at' => (int)$row['created_at'],
            ];
        }
        Json::ok(['total' => (int)$total, 'page' => $page, 'limit' => $limit, 'list' => $list]);
    }

    public function postUlog(): void
    {
        $user = $this->requireLogin();
        $vodId = $this->input->int('id', $this->input->int('vod_id'));
        if ($vodId <= 0) {
            Json::fail(400, '影片不存在');
            return;
        }
        $table = Installer::prefix() . 'cuican_ulog';
        $exists = Db::table($table)->where('user_id', (int)$user['user_id'])->where('vod_id', $vodId)->find();
        if (!$exists) {
            Db::table($table)->insert([
                'user_id' => (int)$user['user_id'],
                'vod_id' => $vodId,
                'created_at' => time(),
            ]);
        }
        Json::ok(null, '已收藏');
    }

    public function deleteUlog(): void
    {
        $user = $this->requireLogin();
        $ids = $this->input->intList('ids');
        if ($ids === []) {
            Json::fail(400, '缺少影片 id');
            return;
        }
        Db::table(Installer::prefix() . 'cuican_ulog')
            ->where('user_id', (int)$user['user_id'])
            ->whereIn('vod_id', $ids)
            ->delete();
        Json::ok(null, '已取消收藏');
    }


    public function postViewseconds(): void
    {
        $user = $this->requireLogin();
        $award = (new Playback())->addViewSeconds((int)$user['user_id'], $this->input->int('view_seconds'));
        Json::ok(['score' => (string)$award]);
    }


    /**
     * 首页「继续观看」：当前用户最近 [CONTINUE_LIMIT] 条播放进度。
     *
     * 与完整观看记录列表的差别：
     * - 一次 join `mac_vod` 取片名/封面，不做逐行查询（列表短、调用频繁）；
     * - 已下架影片（`vod_status != 1`）整条跳过，客户端只拿得到能播的片；
     * - 不分页、不返回 total——首页横滑只展示最近几条。
     */
    public function getVideoProgressList(): void
    {
        $user = $this->requireLogin();
        $rows = Db::table(Installer::prefix() . 'cuican_vlog')
            ->alias('v')
            ->join(Installer::prefix() . 'vod w', 'w.vod_id = v.vod_id')
            // 带类型名：客户端据此分流（短剧 → 竖屏播放器，普通影片 → 详情页）
            ->join(Installer::prefix() . 'type t', 't.type_id = w.type_id', 'LEFT')
            ->where('v.user_id', (int)$user['user_id'])
            ->where('w.vod_status', 1)
            ->field(
                'v.vod_id,v.source_index,v.episode_index,v.progress_sec,v.updated_at,'
                . 'w.vod_name,w.vod_pic,w.vod_class,t.type_name'
            )
            ->order('v.updated_at desc')
            ->limit(self::CONTINUE_LIMIT)
            ->select();

        $list = [];
        foreach ($rows as $row) {
            $list[] = [
                'vod_id' => (int)$row['vod_id'],
                'vod_name' => (string)($row['vod_name'] ?? ''),
                'vod_pic' => (string)($row['vod_pic'] ?? ''),
                'type_name' => (string)($row['type_name'] ?? ''),
                'vod_class' => (string)($row['vod_class'] ?? ''),
                'source_index' => (int)$row['source_index'],
                'episode_index' => (int)$row['episode_index'],
                'progress_sec' => (int)$row['progress_sec'],
                'updated_at' => (int)$row['updated_at'],
            ];
        }
        Json::ok(['list' => $list]);
    }

    /**
     * 会员价目表。
     *
     * 直接回 macCMS 的会员组表（客户端自己按键读 `"3"` 再除以 10 算价）。
     * 不在这里做"套餐"抽象，避免与后台会员组两处维护。
     */
    public function getGroups(): void
    {
        if (Setting::int('member_enabled', 1) !== 1) {
            // 会员关闭时直接回空表：客户端最多显示空态，不会拿到价格去下单
            Json::ok(['list' => []]);
            return;
        }
        $rows = Db::name('group')
            ->where('group_status', 1)
            ->order('group_id asc')
            ->select();
        $list = [];
        foreach ($rows as $row) {
            $list[(string)$row['group_id']] = [
                'group_id' => (int)$row['group_id'],
                'group_name' => (string)$row['group_name'],
                'group_status' => (int)$row['group_status'],
                'group_points_day' => (int)$row['group_points_day'],
                'group_points_week' => (int)$row['group_points_week'],
                'group_points_month' => (int)$row['group_points_month'],
                'group_points_year' => (int)$row['group_points_year'],
            ];
        }
        Json::ok(['list' => $list]);
    }

    /**
     * 支付页。客户端把这个地址交给系统浏览器（无 cookie），
     * 所以不加登录校验，凭随机订单号定位；成功即 302 到网关。
     */
    public function getPay(): void
    {
        $result = (new Payment())->payPage(
            $this->input->str('order_code'),
            $this->input->str('payment'),
        );
        if ($result['code'] !== 200) {
            Json::fail((int)$result['code'], $result['msg']);
            return;
        }
        header('Location: ' . $result['url'], true, 302);
    }

    /** 充值提示：`data.url` + 响应壳 msg（客户端两个都读）。 */
    public function getPaytip(): void
    {
        Json::ok(
            ['url' => Setting::get('pay_tip_url', '')],
            Setting::get('pay_tip_msg', '如需充值请联系客服'),
        );
    }

    /** 金币提现提示：msg 是最低提现额，info 是比例说明。 */
    public function getGoldtip(): void
    {
        $min = Setting::int('withdraw_min_yuan', 20);
        $ratio = Setting::int('gold_per_yuan', 10);
        Json::ok([
            'msg' => '最低提现' . $min . '元',
            'info' => '提现比例' . $ratio . '金币=1元',
        ]);
    }

    public function postGoldwithdrawapply(): void
    {
        $user = $this->requireLogin();
        $amount = $this->input->int('num');
        $channel = $this->input->int('type', 1);
        $account = mb_substr($this->input->str('account'), 0, 100);
        $realName = mb_substr($this->input->str('realname'), 0, 40);
        $qrcode = mb_substr($this->input->str('qrcode'), 0, 255);

        $min = Setting::int('withdraw_min_yuan', 20);
        // 0 元申请没有意义：needGold 会算成 0，等于凭空长出一条提现单
        if ($amount <= 0 || $amount < $min) {
            Json::fail(400, '最低提现' . $min . '元');
            return;
        }
        // 单笔上限：不设上限时一次申请就能把站里的金币全变成待打款，
        // 运营事后才发现也追不回
        $max = Setting::int('withdraw_max_yuan', 0);
        if ($max > 0 && $amount > $max) {
            Json::fail(400, '单笔最多提现' . $max . '元');
            return;
        }
        if (!in_array($channel, [1, 2], true) || $account === '' || $realName === '') {
            Json::fail(400, '请填写收款账号与姓名');
            return;
        }
        // 收款码只接受本插件上传接口产出的相对路径：外链会把后台审核页变成
        // 「点一下就跳第三方」的入口，`..` 则可能被拼成站点外的文件路径。
        if ($qrcode !== '' && (!str_starts_with($qrcode, '/upload/withdraw/') || str_contains($qrcode, '..'))) {
            Json::fail(400, '收款码无效');
            return;
        }
        // 比例为 0 时 needGold 恒为 0，扣不到金币却能换出金额，必须拒绝
        $ratio = Setting::int('gold_per_yuan', 10);
        if ($ratio <= 0) {
            Json::fail(400, '提现比例未配置，请联系客服');
            return;
        }
        $needGold = $amount * $ratio;

        // 余额判断与扣减都交给事务内的条件更新：事务外读到的余额在并发下不可信，
        // 扣不动就回滚，绝不落一条没扣到金币的提现单
        Db::startTrans();
        try {
            if (!(new Users())->deductGold((int)$user['user_id'], $needGold)) {
                Db::rollback();
                Json::fail(400, '金币不足');
                return;
            }
            Db::table(Installer::prefix() . 'cuican_gold_withdraw')->insert([
                'user_id' => (int)$user['user_id'],
                'amount' => $amount,
                'channel' => $channel,
                'account' => $account,
                'realname' => $realName,
                'qrcode' => $qrcode,
                'status' => 0,
                'created_at' => time(),
            ]);
            Db::commit();
        } catch (\Throwable $e) {
            Db::rollback();
            Json::fail(500, '提交失败，请稍后重试');
            return;
        }
        Json::ok(['info' => '提交成功']);
    }

    public function getCheckvodtrysee(): void
    {
        $vodId = $this->input->int('id', $this->input->int('vod_id'));
        // `nid` 就是集号（客户端 checkVodTrySee 的三个 query：id / nid / mid）。
        // 广告解锁按集生效，少了它只能退回整部片的会员判定，解锁记录永远查不到。
        $episode = $this->input->int('nid', $this->input->int('episode_number'));
        $gate = (new Playback())->trySee(
            $this->session->loggedIn() ? $this->userId() : null,
            $vodId,
            $episode,
        );
        Json::ok($gate);
    }

    public function getOrder(): void
    {
        $user = $this->requireLogin();
        $result = (new Payment())->orderStatus((int)$user['user_id'], $this->input->str('order_code'));
        $result['code'] === 200
            ? Json::ok($result['order'], $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    public function postOrder(): void
    {
        $user = $this->requireLogin();
        $result = (new Payment())->createOrder(
            (int)$user['user_id'],
            (float)$this->input->str('price'),
            $this->input->str('payment'),
        );
        $result['code'] === 200
            ? Json::ok($result['order'], $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    public function postGroup(): void
    {
        $user = $this->requireLogin();
        // order_code 是新客户端的行为：支付完成后把它带回来，服务端据此认定
        // "哪一笔钱买了哪一单"。不带时由 Payment 按最近 5 分钟已支付订单兜底。
        $result = (new Payment())->upgrade(
            (int)$user['user_id'],
            $this->input->int('group_id'),
            $this->input->str('long'),
            $this->input->str('order_code'),
        );
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    public function postBuy(): void
    {
        $user = $this->requireLogin();
        $result = (new Payment())->buyCard(
            (int)$user['user_id'],
            $this->input->str('card_pwd'),
        );
        $result['code'] === 200
            // data 透传核销结果（card_type/card_value/group_id/membership_end），不读新字段的调用方忽略
            ? Json::ok($result['data'] ?? null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    public function postBuypopedom(): void
    {
        $user = $this->requireLogin();
        $result = (new Payment())->buyEpisode(
            (int)$user['user_id'],
            $this->input->int('id'),
            $this->input->int('pay_type', 1),
        );
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    /** 签到状态与签到。 */
    public function getSign(): void
    {
        $user = $this->requireLogin();
        $today = date('Y-m-d');
        $row = Db::table(Installer::prefix() . 'cuican_sign')
            ->where('user_id', (int)$user['user_id'])
            ->where('sign_date', $today)
            ->find();
        // points 是账户可用积分（积分页那个大数字），签到能拿多少走 sign_points。
        // 这两个字段以前混在一起用，结果积分页把"签到得 5 分"当成余额显示。
        $balance = (int)Db::name('user')->where('user_id', (int)$user['user_id'])->value('user_points');
        Json::ok([
            'signed' => $row ? 1 : 0,
            'points' => $balance,
            'sign_points' => $row ? (int)$row['points'] : Setting::int('sign_points', 5),
        ]);
    }

    public function postSign(): void
    {
        $user = $this->requireLogin();
        $uid = (int)$user['user_id'];
        $today = date('Y-m-d');
        $table = Installer::prefix() . 'cuican_sign';
        if (Db::table($table)->where('user_id', $uid)->where('sign_date', $today)->find()) {
            Json::ok(['score' => '0'], '今天已经签到过了');
            return;
        }
        $points = Setting::int('sign_points', 5);
        Db::table($table)->insert([
            'user_id' => $uid,
            'sign_date' => $today,
            'points' => $points,
            'created_at' => time(),
        ]);
        (new Users())->addPoints($uid, $points);
        Json::ok(['score' => (string)$points], '签到成功，获得' . $points . '积分');
    }

    /**
     * 任务列表：数据源是 macCMS 的 `mac_task`（后台 基础 → 任务管理），
     * 只取每日任务（`task_type = 1`）里启用的那些，标题/分值/目标次数/排序都以后台为准。
     *
     * 进度优先读 macCMS 自己的 `mac_task_log`（官方 App / H5 会写）；
     * 没有记录时用插件已有的当日数据兜底（签到表 / 累计观看秒数 / 分享标记）。
     */
    public function getTask(): void
    {
        $user = $this->requireLogin();
        $uid = (int)$user['user_id'];
        $list = [];
        foreach ($this->dailyTaskRows() as $row) {
            $state = $this->taskState($uid, $row);
            $list[] = [
                // 客户端只认 sign/watch/share 这三个短 key，后台任务标识原样透传
                'key' => self::shortKey((string)$row['task_action']),
                'title' => (string)$row['task_name'],
                'points' => (int)$row['task_points'],
                'target' => max(1, (int)$row['task_target']),
                'progress' => $state['progress'],
                'done' => $state['done'],
                'claimed' => $state['claimed'],
            ];
        }

        Json::ok(['list' => $list]);
    }

    /** 任务领取：签到、分享由各自接口发奖，其余任务做完就能在这里领。 */
    public function postTask(): void
    {
        $user = $this->requireLogin();
        $uid = (int)$user['user_id'];
        $key = trim($this->input->str('key'));
        // 客户端传的是短 key，先还原成 macCMS 的 task_action 再查任务行
        $action = self::TASK_KEYS[$key] ?? $key;
        foreach ($this->dailyTaskRows() as $row) {
            if ((string)$row['task_action'] === $action) {
                $task = $row;
                break;
            }
        }
        // 签到/分享的奖在 postSign、postSharescore 里已经发过，这里再来一次就是重复加积分
        if ($task === null || in_array($action, self::AUTO_CLAIM_ACTIONS, true)) {
            Json::fail(400, '该任务无需手动领取');
            return;
        }
        $state = $this->taskState($uid, $task);
        if ($state['claimed'] === 1) {
            Json::ok(['score' => '0'], '今天已经领取过了');
            return;
        }
        if ($state['done'] !== 1) {
            Json::fail(400, '任务还没完成');
            return;
        }
        Db::table(Installer::prefix() . 'cuican_setting')->insert(['k' => $this->claimKey($uid, $action), 'v' => '1']);
        $points = (int)$task['task_points'];
        if ($points > 0) {
            (new Users())->addPoints($uid, $points);
        }
        Json::ok(['score' => (string)$points], '领取成功');
    }

    /**
     * 每日任务行：读 `mac_task`，表不存在或全被停用时回落到老的写死三条，
     * 免得后台没建任务就把积分页整块清空。
     *
     * @return array<int,array<string,mixed>>
     */
    private function dailyTaskRows(): array
    {
        try {
            $rows = Db::table(Installer::prefix() . 'task')
                ->where('task_type', 1)
                ->where('task_status', 1)
                ->order('task_sort asc,task_id asc')
                ->select();
        } catch (\Throwable $e) {
            $rows = [];
        }
        if ($rows !== []) {
            return $rows;
        }

        return [
            ['task_action' => 'daily_sign', 'task_name' => '每日签到', 'task_points' => Setting::int('sign_points', 5), 'task_target' => 1],
            ['task_action' => 'watch_vod', 'task_name' => '观看满 30 分钟', 'task_points' => Setting::int('watch_points', 0), 'task_target' => 1],
            ['task_action' => 'share_vod', 'task_name' => '分享一次', 'task_points' => Setting::int('share_points', 0), 'task_target' => 1],
        ];
    }

    /**
     * 一条任务的今日状态。
     *
     * `done` 与 `claimed` 必须分开：done 表示"做完且还没领"，客户端拿它决定按钮能不能点；
     * 签到/分享是接口直接发奖，所以它们完成即视为已领取，按钮不该再出现"领取"。
     *
     * @param array<string,mixed> $task
     * @return array{progress:int,done:int,claimed:int}
     */
    private function taskState(int $uid, array $task): array
    {
        $action = (string)$task['task_action'];
        $target = max(1, (int)$task['task_target']);
        $progress = min($target, $this->taskProgress($uid, $action, $target));
        $completed = $progress >= $target;
        $auto = in_array($action, self::AUTO_CLAIM_ACTIONS, true);
        $claimed = Db::table(Installer::prefix() . 'cuican_setting')
            ->where('k', $this->claimKey($uid, $action))
            ->find() !== null;

        return [
            'progress' => $progress,
            'done' => $completed && !$claimed && !$auto ? 1 : 0,
            'claimed' => ($auto && $completed) || $claimed ? 1 : 0,
        ];
    }

    /** 今日进度：先看 macCMS 的任务日志，没有记录再用插件自己记的数据推算。 */
    private function taskProgress(int $uid, string $action, int $target): int
    {
        $today = date('Y-m-d');
        try {
            $log = Db::table(Installer::prefix() . 'task_log')
                ->where('user_id', $uid)
                ->where('task_action', $action)
                ->where('log_date', $today)
                ->find();
            if ($log !== null) {
                return (int)$log['log_progress'];
            }
        } catch (\Throwable $e) {
            // 没建任务日志表就当没记录，下面走插件自有数据
        }

        switch ($action) {
            case 'daily_sign':
                $signed = Db::table(Installer::prefix() . 'cuican_sign')
                    ->where('user_id', $uid)
                    ->where('sign_date', $today)
                    ->find() !== null;
                return $signed ? $target : 0;
            case 'watch_vod':
                // macCMS 记的是"看了几部"，插件只有累计观看秒数：每满 30 分钟算一部
                $ext = (new Users())->ensureExt($uid);
                return intdiv((int)$ext['view_seconds'], 1800);
            case 'share_vod':
                $shared = Db::table(Installer::prefix() . 'cuican_setting')
                    ->where('k', 'share_score_' . $uid . '_' . date('Ymd'))
                    ->find() !== null;
                return $shared ? $target : 0;
            default:
                return 0;
        }
    }

    /** 领取记录 key：watch_vod 沿用老 key，升级当天老用户不会多领一次。 */
    private function claimKey(int $uid, string $action): string
    {
        if ($action === 'watch_vod') {
            return 'watch_claim_' . $uid . '_' . date('Ymd');
        }

        return 'task_claim_' . $uid . '_' . $action . '_' . date('Ymd');
    }



    /** 分享信息 `v1.user/shareInfo`。 */
    public function getShareinfo(): void
    {
        Json::ok([
            'share_url' => Setting::get('share_url', ''),
            'share_logo' => Setting::get('share_logo', ''),
            'share_title' => Setting::get('share_title', ''),
            'share_description' => Setting::get('share_description', ''),
        ]);
    }

    /** 分享奖励 `v1.user/shareScore`：每天首次有效。 */
    public function postSharescore(): void
    {
        $user = $this->requireLogin();
        $uid = (int)$user['user_id'];
        $points = Setting::int('share_points', 0);
        if ($points <= 0) {
            Json::ok(['score' => '0'], '分享成功');
            return;
        }
        $settings = Db::table(Installer::prefix() . 'cuican_setting');
        $key = 'share_score_' . $uid . '_' . date('Ymd');
        if ($settings->where('k', $key)->find()) {
            Json::ok(['score' => '0'], '今天已经领过分享奖励');
            return;
        }
        $settings->insert(['k' => $key, 'v' => '1']);
        (new Users())->addPoints($uid, $points);
        Json::ok(['score' => (string)$points], '分享成功，获得' . $points . '积分');
    }

}
