<?php
/** 影片：列表 / 详情 / 分类 / 榜单。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Vods;
use Starveil\Api\Service\Playback;
use Starveil\Api\Support\Setting;
use think\Db;

final class VodController extends BaseController
{
    /** 播放地址的签名有效期（秒）：够一次起播与重试，不给人拿去做长期分发。 */
    private const PLAY_URL_TTL = 300;

    /**
     * 列表 + 片库筛选。
     *
     * 参数名与 macCMS 前台逐字同名（`mac_param_url()`），
     * 客户端照着 `v1.vod/types` 下发的 `extend` 原样回传即可：
     * `year` / `area` / `lang` 是逗号可分隔的多值，`class` 是扩展分类名，
     * `by` + `order` 是排序（服务端有白名单，见 Vods::orderOf）。
     *
     * `class` 兼容两种值：纯数字仍按老契约走 FIND_IN_SET，其余按 macCMS 的
     * 扩展分类名 LIKE 匹配 —— 详见 Repo\Vods::applyClassFilter()。
     */
    public function getIndex(): void
    {
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);

        Json::ok((new Vods())->listFiltered([
            'type' => $this->input->int('type'),
            'class' => $this->input->str('class'),
            'level' => $this->input->int('level'),
            'wd' => $this->input->str('wd'),
            'year' => $this->input->str('year'),
            'area' => $this->input->str('area'),
            'lang' => $this->input->str('lang'),
            'by' => $this->input->str('by'),
            'order' => $this->input->str('order'),
        ], $page, $limit));
    }

    /**
     * 影片详情。
     *
     * 未登录也能看元信息（简介、分类、集名），但**只有放行态才带播放地址**：
     * 非会员 / 未买这一集时 `urls[].url` 一律为空，播放器要走 [getPlayUrl]。
     */
    public function getDetail(): void
    {
        $vodId = $this->input->int('vod_id');
        $userId = $this->userId();
        $detail = (new Vods())->detail($vodId, $userId > 0 ? $userId : null);
        if ($detail === null) {
            Json::fail(404, '影片不存在', 404);
            return;
        }
        Json::ok($detail);
    }

    /**
     * `GET v1.vod/playUrl?vod_id&nid&sid` —— 过闸门后下发单集地址。
     * 详情在非放行态下把地址清空，播放器只能从这里取：每开一集都是一次鉴权，
     * 会员到期、订单退款、后台关掉权益之后，重新进详情/开播就按最新权益判
     * （已经拿到的第三方直链没法回收，这是没有转码/网关时的固有边界）。
     * `sid` 不传时取第一个命中的源（与详情里的 sid 同序，从 1 开始）。
     */
    public function getPlayUrl(): void
    {
        $vodId = $this->input->int('vod_id');
        $nid = $this->input->int('nid');
        $sid = $this->input->int('sid');
        if ($vodId <= 0 || $nid <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        $userId = $this->userId();
        $detail = (new Vods())->detail($vodId, $userId > 0 ? $userId : null);
        if ($detail === null) {
            Json::fail(404, '影片不存在', 404);
            return;
        }

        // 能不能下发这一集只看**按集闸门**：它内部已经把影片级条件全算过一遍
        //（付费墙关闭 / 会员 / 会员体系未配 / 本集已购买 / 本集看完广告），只会比影片级更严。
        //
        // 不能再拿 `$detail['play_locked']` 参与判定：付费墙开 + CDN 开时它是 0
        //（试看态照常下发地址），把它当"已解锁"就会让试看态从这里换到 exper=0 的完整地址，
        // 付费墙等于被这一个接口绕过去。
        $episode = (new Playback())->trySee($userId > 0 ? $userId : null, $vodId, $nid);
        if ((int)$episode['status'] !== 0) {
            if ($userId <= 0) {
                Json::fail(401, '请先登录', 401);
            } else {
                Json::fail(403, '尚未解锁该集', 403);
            }
            return;
        }

        $url = (new Vods())->playUrl($vodId, $sid, $nid);
        if ($url === '') {
            Json::fail(404, '该集不存在', 404);
            return;
        }

        $expires = time() + self::PLAY_URL_TTL;
        Json::ok([
            'url' => $url,
            'vod_id' => $vodId,
            'nid' => $nid,
            'expires' => $expires,
            'sign' => $this->signPlayUrl($userId, $vodId, $nid, $url, $expires),
        ]);
    }

    /**
     * 播放地址的短时签名：`hmac-sha256(user_id|vod_id|nid|url|expires)`。
     *
     * **签名不能替代闸门**：上面那两行判定才是"能不能拿地址"的唯一依据。这个 sign
     * 是给解析层/边缘节点校验"这条地址是本次下发、且没过期"用的；现在还没有消费方，
     * 接 CDN 鉴权时把同一套算法交给边缘即可（届时 `url` 也要换成带鉴权参数的形式）。
     */
    private function signPlayUrl(int $userId, int $vodId, int $nid, string $url, int $expires): string
    {
        $key = $this->playUrlSecret();
        if ($key === '') {
            return '';
        }
        return hash_hmac('sha256', implode('|', [$userId, $vodId, $nid, $url, $expires]), $key);
    }

    /**
     * 签名密钥：`play_url_secret`，没有就现生成一个写进 `cuican_setting`。
     *
     * 不复用 `session_secret`（它与会话串无关，见 `Auth\Session` 的类注释），
     * 也不复用 `ad_ssv_secret`（那是广告平台共享的，泄露面更大）。
     * 写库失败时返回空串，调用方只回地址不带签名，不影响起播。
     */
    private function playUrlSecret(): string
    {
        $key = Setting::get('play_url_secret');
        if ($key !== '') {
            return $key;
        }
        try {
            $key = bin2hex(random_bytes(32));
            Setting::set('play_url_secret', $key);
        } catch (\Throwable $e) {
            error_log('[starveil] 生成 play_url_secret 失败: ' . $e->getMessage());
            return '';
        }
        return $key;
    }

    public function getTypes(): void
    {
        Json::ok(['list' => (new Vods())->types()]);
    }

    public function getVodphb(): void
    {
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        Json::ok((new Vods())->board(
            $this->input->str('order_by'),
            $this->input->int('type'),
            $page,
            $limit,
        ));
    }

    public function getVodPhbAll(): void
    {
        $limit = min(max(1, $this->input->int('limit', 6)), 20);
        Json::ok((new Vods())->catalog($limit));
    }

    /** 播放进度读取 `v1.vod/videoProgress`。 */
    public function getVideoprogress(): void
    {
        $user = $this->requireLogin();
        $row = Db::table(Installer::prefix() . 'cuican_vlog')
            ->where('user_id', (int)$user['user_id'])
            ->where('vod_id', $this->input->int('vod_id'))
            ->find();
        Json::ok([
            'source_index' => (int)($row['source_index'] ?? 0),
            'episode_index' => (int)($row['episode_index'] ?? 0),
            'progress_sec' => (int)($row['progress_sec'] ?? 0),
        ]);
    }

    /** 播放进度上报，与 addViewLog 同表。 */
    public function postVideoprogress(): void
    {
        $user = $this->requireLogin();
        (new \Starveil\Api\Service\Playback())->saveViewLog(
            (int)$user['user_id'],
            $this->input->int('vod_id'),
            $this->input->int('source_index'),
            $this->input->int('episode_index'),
            $this->input->int('progress'),
        );
        Json::ok(null, '已记录');
    }

}
