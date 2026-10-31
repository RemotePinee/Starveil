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

final class MainController extends BaseController
{
    public function getStartup(): void
    {
        Json::ok((new Startup($this->userId()))->payload());
    }

    /**
     * 插件版本：读 `info.ini` 的 `version`，读不到再退到 macCMS 的 `config('version')`。
     *
     * `Kernel::ROUTES` 一直放行 `getVersion`，但类里以前没有这个方法——路由先通过，
     * 再 `$instance->getVersion()` 触发 Error，被兜成 `{"code":500}`，README 承诺的
     * 接口实际不可用。
     */
    public function getVersion(): void
    {
        $version = '';
        $ini = @parse_ini_file(dirname(__DIR__, 2) . '/info.ini', false, INI_SCANNER_RAW);
        if (is_array($ini)) {
            $version = trim((string)($ini['version'] ?? ''));
        }
        if ($version === '') {
            $version = trim((string)config('version'));
        }
        Json::ok(['version' => $version]);
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

    /** 推送条数：客户端按 >0 冒红点，未配置时返回 0。 */
    public function getPush(): void
    {
        Json::ok(['count' => (new Push())->count()]);
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
