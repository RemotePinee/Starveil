<?php
/** 直播列表。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Repo\Live;

final class LiveController extends BaseController
{
    public function getIndex(): void
    {
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        // group 为空 = 全部；传了就只看那一组（值来自上一次响应里的 groups[].group）
        $group = trim((string)$this->input->str('group', ''));
        Json::ok((new Live())->list($page, $limit, $group));
    }
}
