<?php
/** 控制器基类：持有输入与会话，统一"必须登录"的闸门。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Auth\Session;
use Starveil\Api\Http\Input;
use Starveil\Api\Http\Json;

abstract class BaseController
{
    public function __construct(
        protected Input $input,
        protected Session $session,
    ) {
    }

    /** 需要登录的接口调用它；未登录直接 401 并终止。 */
    protected function requireLogin(): array
    {
        $user = $this->session->user();
        if ($user === null) {
            Json::fail(401, '请先登录', 401);
            exit;
        }
        return $user;
    }

    protected function userId(): int
    {
        return $this->session->id();
    }
}
