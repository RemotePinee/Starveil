<?php
/**
 * API 内核：路由解析、白名单、鉴权、统一响应。
 *
 * 用 `$this->{'get'.ucfirst($action)}()` 反射调用，
 * 没有任何白名单——请求方可以探到控制器里所有 get / post / delete 前缀方法，
 * 连 `Test::rechargeagents` 这种送积分的调试端点都能直接打。
 *
 * 把"方法名 → 是否允许"写成显式表，不在表里的一律 404。
 */

declare(strict_types=1);

namespace Starveil\Api;

use Starveil\Api\Auth\Session;
use Starveil\Api\Controller\AuthController;
use Starveil\Api\Controller\LiveController;
use Starveil\Api\Controller\MainController;
use Starveil\Api\Controller\DanmuController;
use Starveil\Api\Controller\PaymentController;
use Starveil\Api\Controller\UserController;
use Starveil\Api\Controller\CommentController;
use Starveil\Api\Controller\GbookController;
use Starveil\Api\Controller\TopicController;
use Starveil\Api\Controller\UploadController;
use Starveil\Api\Controller\VodController;
use Starveil\Api\Controller\ShortPlayController;
use Starveil\Api\Controller\AdSsvController;
use Starveil\Api\Controller\AdRewardController;
use Starveil\Api\Http\Json;
use Starveil\Api\Http\Input;
use Starveil\Api\Support\Setting;

final class Kernel
{
    /** 控制器 → 允许的动作（动作名已带 HTTP 动词前缀）。 */
    private const ROUTES = [
        'main' => [
            'getStartup',
            'getVersion',
            'getBanner',
            'getFeatured',
            'getHot',
            'getGuess',
            'getCategory',
            'getLinks',
            'getPush',
        ],
        'auth' => [
            'postLogin',
            'postRegister',
            'getRegisterSms',
            'getFindpassSms',
            'getFindpass',
            'postDevicelogin',
            'postLogout',
        ],
        'vod' => [
            'getIndex',
            'getDetail',
            'getPlayUrl',
            'getTypes',
            'getVodphb',
            'getVodPhbAll',
            'getVideoprogress',
            'postVideoprogress',
        ],
        'topic' => ['getTopicList', 'getTopicDetail'],
        'zhibo' => ['getIndex'],
        'user' => [
            'getDetail',
            'postIndex',
            'getFavs',
            'postUlog',
            'deleteUlog',
            'postViewseconds',
            'getVideoProgressList',
            'getCheckvodtrysee',
            'getGroups',
            'getPay',
            'getPaytip',
            'getGoldtip',
            'postGoldwithdrawapply',
            'getSign',
            'postSign',
            'getTask',
            'postTask',
            'getShareinfo',
            'postSharescore',
            'getOrder',
            'postOrder',
            'postGroup',
            'postBuy',
            'postBuypopedom',
        ],
        'payment' => ['getNotify', 'postNotify'],
        'gbook' => ['getIndex', 'postIndex'],
        'comment' => ['getIndex', 'postIndex'],
        'danmu' => ['getIndex', 'postIndex'],
        'upload' => ['postUser', 'postWithdraw'],
        'shortplay' => ['postUnlock'],
        // 直投激励视频：会话 / 进度 / 结算，三个都要登录（SSV 回调不要，见 ACTION_CONTROLLERS）
        'ad' => ['getSsv', 'postSsv', 'postSession', 'postProgress', 'postComplete'],
    ];


    private const CONTROLLERS = [
        'main' => MainController::class,
        'auth' => AuthController::class,
        'vod' => VodController::class,
        'topic' => TopicController::class,
        'zhibo' => LiveController::class,
        'user' => UserController::class,
        'payment' => PaymentController::class,
        'gbook' => GbookController::class,
        'comment' => CommentController::class,
        'danmu' => DanmuController::class,
        'upload' => UploadController::class,
        'shortplay' => ShortPlayController::class,
        'ad' => AdSsvController::class,
    ];

    /**
     * 同一个路由前缀下，按动作分派到不同控制器。
     *
     * `v1.ad/*` 里有两类调用方：广告平台的服务器（SSV 回调，没有 cookie、不能登录）
     * 和我们的客户端（直投会话，必须登录）。放在同一个类里，登录闸门迟早顺手盖到回调上，
     * 平台的 2 秒超时一过这次发奖就没了；分成两个类、按动作挑，各自的闸门互不干扰。
     */
    private const ACTION_CONTROLLERS = [
        'ad' => [
            'postSession' => AdRewardController::class,
            'postProgress' => AdRewardController::class,
            'postComplete' => AdRewardController::class,
        ],
        // 登出只清 cookie、不轮换 user_random，与「无状态入口」同一类，放 MainController；
        // 见 MainController::postLogout() 的注释。
        'auth' => [
            'postLogout' => MainController::class,
        ],
    ];

    /** 控制器名 + 动作 → 类：先查分派表，再回落到"一个前缀一个类"。 */
    private static function controllerOf(string $controller, string $action): string
    {
        return self::ACTION_CONTROLLERS[$controller][$action]
            ?? self::CONTROLLERS[$controller];
    }

    public static function run(): void
    {
        self::cors();
        try {
            [$controller, $action] = self::resolve();
            if ($controller === null) {
                Json::fail(404, '接口不存在');
                return;
            }
            $class = self::controllerOf($controller, $action);
            $instance = new $class(Input::capture(), Session::current());
            $instance->{$action}();
        } catch (\InvalidArgumentException $e) {
            Json::fail(400, $e->getMessage());
        } catch (\Throwable $e) {
            // 不把堆栈与 SQL 回给客户端；需要排查时看服务端日志
            Json::fail(500, '服务器错误');
        }
    }

    /** @return array{0:?string,1:string} */
    private static function resolve(): array
    {
        $verb = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));
        $prefix = match ($verb) {
            'GET' => 'get',
            'POST' => 'post',
            'DELETE' => 'delete',
            'PUT' => 'put',
            default => null,
        };
        if ($prefix === null) {
            return [null, ''];
        }

        $path = self::path();
        // 形如 v1.vod/detail；不带动作时按 index 处理
        if (!preg_match('#^v1\.([A-Za-z]+)(?:/([A-Za-z0-9_]+))?$#', $path, $m)) {
            return [null, ''];
        }
        $controller = strtolower($m[1]);
        $rawAction = $m[2] ?? 'index';
        // 路径用驼峰（vodPhbAll），统一按驼峰拼动词前缀后再比对白名单
        $action = $prefix . ucfirst($rawAction);

        $allowed = self::ROUTES[$controller] ?? [];
        foreach ($allowed as $candidate) {
            if (strcasecmp($candidate, $action) === 0) {
                return [$controller, $candidate];
            }
        }
        return [null, ''];
    }

    /** 允许 `/icciu_api.php/v1.vod/detail` 与重写后的 `/v1.vod/detail` 两种形态。 */
    private static function path(): string
    {
        $pathInfo = (string)($_SERVER['PATH_INFO'] ?? '');
        if ($pathInfo === '') {
            $uri = (string)($_SERVER['REQUEST_URI'] ?? '');
            $uri = explode('?', $uri, 2)[0];
            $script = (string)($_SERVER['SCRIPT_NAME'] ?? '');
            if ($script !== '' && str_starts_with($uri, $script)) {
                $uri = substr($uri, strlen($script));
            }
            $pathInfo = $uri;
        }
        return trim($pathInfo, '/');
    }

    /**
     * CORS：只回显白名单内的 Origin。
     *
     * 无条件 `Allow-Origin: *` 且带 `Allow-Credentials: true`，
     * 等于允许任意站点带 cookie 调接口。
     */
    private static function cors(): void
    {
        $origin = (string)($_SERVER['HTTP_ORIGIN'] ?? '');
        if ($origin === '') {
            return;
        }
        $allowed = Setting::json('allowed_origins', []);
        if (in_array($origin, $allowed, true)) {
            header('Access-Control-Allow-Origin: ' . $origin);
            header('Access-Control-Allow-Credentials: true');
            header('Vary: Origin');
        }
        header('Access-Control-Allow-Methods: GET,POST,DELETE,OPTIONS');
        header('Access-Control-Allow-Headers: Content-Type,X-Requested-With');
        if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
            http_response_code(204);
            exit;
        }
    }
}
