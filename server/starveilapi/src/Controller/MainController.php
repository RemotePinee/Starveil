<?php
/** 启动配置。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Repo\Banner;
use Starveil\Api\Repo\Blocks;
use Starveil\Api\Repo\Featured;
use Starveil\Api\Repo\Links;
use Starveil\Api\Repo\PlayerAd;
use Starveil\Api\Repo\Push;
use Starveil\Api\Service\Startup;
use Starveil\Api\Service\Update;

final class MainController extends BaseController
{
    public function getStartup(): void
    {
        Json::ok((new Startup($this->userId()))->payload());
    }

    /**
     * 在线更新：APK 版本信息，后台「版本更新」页维护（值存在 `cuican_setting`）。
     *
     * 以前这里返回的是插件自身版本 `{version:"<插件版本>"}`，与客户端 `VersionDto`
     * （code / name / url / sha256 / force / changelog）对不上，字段全空等于永不提示更新；
     * 现在按客户端契约下发，没配时 code=0，客户端据此跳过。
     */
    public function getVersion(): void
    {
        Json::ok(Update::payload());
    }

    /** 首页轮播。 */
    public function getBanner(): void
    {
        Json::ok(['list' => (new Banner())->list()]);
    }

    /**
     * 编辑精选：首页第一张卡片位。
     *
     * 这一格不读 macCMS 的推荐等级（那样换片要改推荐值），
     * 现在改成插件里的 block(position='featured')，运营可在后台选片、排序、启停。
     * 未配置时返回空 vods，客户端按"无精选"降级。
     */
    public function getFeatured(): void
    {
        Json::ok((new Featured())->payload(Featured::POSITION, $this->typeIdOfRequest()));
    }

    /**
     * 首页「热度榜」：与编辑精选同一套 block 表，position + type_id 定位一组。
     * 分类没配时服务端回退全局（type_id=0）；都为空则返回空 vods，客户端按区块降级。
     */
    public function getHot(): void
    {
        Json::ok((new Featured())->payload(Featured::POSITION_HOT, $this->typeIdOfRequest()));
    }

    /** 首页「猜你喜欢」，同上。 */
    public function getGuess(): void
    {
        Json::ok((new Featured())->payload(Featured::POSITION_GUESS, $this->typeIdOfRequest()));
    }

    /** 请求里的分类；0 = 首页/全局。 */
    private function typeIdOfRequest(): int
    {
        return max($this->input->int('type_id', 0), 0);
    }

    /**
     * 首页专区。`need_vod=1` 时把每个专区的影片一起返回，
     * 与老接口 `v1.main/category` 同名同参数，客户端可以少一次请求。
     */
    public function getCategory(): void
    {
        $withVods = $this->input->int('need_vod', 1) === 1;
        Json::ok(['list' => (new Blocks())->list('home', $withVods)]);
    }

    /** 首页快捷入口。 */
    public function getLinks(): void
    {
        Json::ok(['list' => (new Links())->list()]);
    }

    /**
     * 未读条数：客户端按 >0 冒红点。
     *
     * 与消息列表的 `unread` 同口径；未登录没有阅读记录，整批广播都算未读。
     * 以前返回的是"今天已发条数"——那个数与用户无关，读完也不会消失。
     */
    public function getPush(): void
    {
        Json::ok(['count' => (new Push())->unreadCount($this->userId())]);
    }

    /**
     * 消息中心列表：公告与推送共用一条消息，靠 kind 区分语义、template 区分弹窗样式。
     *
     * 游客也能拉（公告是公开的），`read` 恒为 false；已读标记只对登录用户生效。
     */
    public function getMessages(): void
    {
        $page = max(1, $this->input->int('page', 1));
        // size 上限 50：客户端一页 20，再大 UI 也接不住，还给了一次拉全表的口子
        $size = min(max(1, $this->input->int('size', 20)), 50);
        Json::ok((new Push())->list($this->userId(), $page, $size));
    }

    /**
     * 批量标记已读（`{"ids":[1,2,3]}`）。
     *
     * 必须登录：已读按 user_id 一行一条存，游客没有可写的行；
     * 走 requireLogin 的既有闸门，未登录回 `code=401`（HTTP 仍是 200，见 Json::fail 的注释）。
     */
    public function postMessageRead(): void
    {
        $this->requireLogin();
        (new Push())->markRead($this->userId(), $this->input->intList('ids'));
        // 空对象，不是 null 也不是 []：契约里 data 是 {}，json_encode([]) 会变成数组
        Json::ok((object)[]);
    }

    /**
     * `POST v1.auth/logout`：只清 cookie，**不轮换** `user_random`。
     *
     * 对齐 macCMS `model(User)->logout()`（只把 cookie 置空）：轮换会把同账号的网页
     * 会话一起踢掉，而"退出登录"不该有这种副作用。需要强制下线/改密踢人时用
     * `Auth\Session::revoke()`。
     *
     * 为什么挂在 main 前缀下：`Kernel::ACTION_CONTROLLERS` 把 `auth/postLogout`
     * 分派到这里——登出与启动配置一样是无状态入口，不需要额外控制器。
     */
    public function postLogout(): void
    {
        $this->session->clear();
        Json::ok(null, '已退出');
    }
}
