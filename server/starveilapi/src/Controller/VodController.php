<?php
/** 影片：列表 / 详情 / 分类 / 榜单。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Vods;
use think\Db;

final class VodController extends BaseController
{
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

    public function getDetail(): void
    {
        $vodId = $this->input->int('vod_id');
        $detail = (new Vods())->detail($vodId);
        if ($detail === null) {
            Json::fail(404, '影片不存在', 404);
            return;
        }
        Json::ok($detail);
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
