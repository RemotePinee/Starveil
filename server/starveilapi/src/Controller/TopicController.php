<?php
/** 专题：列表与详情。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Repo\Topics;

final class TopicController extends BaseController
{
    public function getTopicList(): void
    {
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        Json::ok((new Topics())->list($page, $limit));
    }

    public function getTopicDetail(): void
    {
        $detail = (new Topics())->detail($this->input->str('topic_id'));
        if ($detail === null) {
            Json::fail(404, '专题不存在', 404);
            return;
        }
        Json::ok($detail);
    }
}
