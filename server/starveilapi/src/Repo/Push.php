<?php
/**
 * 每日推荐推送（全站广播）。
 *
 * macCMS 没有"给 App 推一条消息"的功能，但插件早就有 cuican_message：
 * `user_id = 0` 表示全站广播，`v1.vod/push` 读的正是这批未读行（status=0）。
 * 所以不新建表，直接把运营发的推送写成一条广播消息，客户端下次启动就能看到。
 *
 * 列名以 install/schema/v2_0_0.sql 为准（msg_id / user_id / title / content /
 * status / created_at）；这里没有 create_time 这一列。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Push
{
    /** user_id=0 即全站广播，与 VodController::getPush 的取数条件一致。 */
    public const BROADCAST_USER_ID = 0;

    /** 未读。getPush 只返回 status=0 的行；打开详情后由消息接口改成 1。 */
    private const UNREAD = 0;

    /** 今天（服务器本地时区）发出的广播条数。 */
    public function count(): int
    {
        return (int)Db::table($this->table())
            ->where('user_id', self::BROADCAST_USER_ID)
            ->where('created_at', '>=', strtotime('today'))
            ->count();
    }

    public function publish(string $title, string $content): void
    {
        Db::table($this->table())->insert([
            'user_id' => self::BROADCAST_USER_ID,
            'title' => mb_substr(trim($title), 0, 128),
            'content' => $content,
            'status' => self::UNREAD,
            'created_at' => time(),
        ]);
    }

    private function table(): string
    {
        return Installer::prefix() . 'cuican_message';
    }
}
