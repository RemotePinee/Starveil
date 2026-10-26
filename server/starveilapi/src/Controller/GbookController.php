<?php
/**
 * 留言板。
 *
 * 列表公开、发送需登录；60 秒冷却，单条 300 字。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use think\Db;

final class GbookController extends BaseController
{
    public function getIndex(): void
    {
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        $table = Installer::prefix() . 'cuican_gbook';

        $total = Db::table($table)->where('status', 1)->count();
        $rows = Db::table($table)
            ->where('status', 1)
            ->order('gbook_id desc')
            ->page($page, $limit)
            ->select();

        $list = [];
        foreach ($rows as $row) {
            $nick = Db::name('user')->where('user_id', (int)$row['user_id'])->value('user_nick_name');
            $list[] = [
                'gbook_id' => (int)$row['gbook_id'],
                'content' => (string)$row['content'],
                'user_nick_name' => (string)($nick ?: '游客'),
                'created_at' => (int)$row['created_at'],
            ];
        }
        Json::ok(['total' => (int)$total, 'page' => $page, 'limit' => $limit, 'list' => $list]);
    }

    public function postIndex(): void
    {
        $user = $this->requireLogin();
        $content = mb_substr($this->input->str('content'), 0, 300);
        if ($content === '') {
            Json::fail(400, '请输入留言内容');
            return;
        }
        $table = Installer::prefix() . 'cuican_gbook';
        if (Db::table($table)->where('user_id', (int)$user['user_id'])->where('created_at', '>', time() - 60)->find()) {
            Json::fail(429, '留言太频繁，请稍后再试');
            return;
        }
        Db::table($table)->insert([
            'user_id' => (int)$user['user_id'],
            'content' => $content,
            'status' => 1,
            'created_at' => time(),
        ]);
        Json::ok(null, '留言已提交');
    }
}
