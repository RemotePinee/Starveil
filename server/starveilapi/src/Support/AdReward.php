<?php
/**
 * 直投激励视频的配置口径：片源取哪个、看满多少秒算完成。
 *
 * 为什么单开一个类：片源有两个来源（后台上传 / 外链地址），"谁优先"只能有一处定义。
 * `Service\Startup`（把片源下发给客户端播）、`Service\Playback`（can_ad 口径）、
 * `Controller\AdRewardController`（签发会话时判定片源）三处都要这个结论，
 * 各自写一遍 `?:` 迟早漂移成"客户端有地址、服务端判没配"。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class AdReward
{
    /**
     * 外链地址的存储键（后台「激励视频地址」，只填 http/https 的 mp4/webm）。
     */
    public const URL_KEY = 'ad_reward_video';

    /**
     * 上传文件的存储键（后台文件框的实际落库位置）。
     *
     * 与 [self::URL_KEY] 分开而不是像图片那样共用一个键：图片是"选了文件就是要替换外链"，
     * 视频体积大、上传失败率高，共用键会让一次失败的重新上传把原本能播的外链清掉。
     */
    public const UPLOAD_KEY = 'ad_reward_video_upload';

    /** 可播放的绝对地址：上传优先，其次外链；都没配返回空串。 */
    public static function videoUrl(): string
    {
        $upload = trim(Setting::get(self::UPLOAD_KEY, ''));
        if ($upload !== '') {
            return Media::url($upload);
        }
        // 外链也过一遍 Media::url：运营可能填站内相对路径（/upload/ad/xxx.mp4）
        return Media::url(trim(Setting::get(self::URL_KEY, '')));
    }

    /**
     * 看满多少秒算完成：与后台「激励倒计时秒」同一个键 `ad_reward_seconds`，夹在 1..60。
     *
     * 上限 60 而不是 30：客户端倒计时读的是 startup 的 `sdk_excitation.seconds`
     * （那边夹 1..30），而这里判的是**真实播放秒数**；直投片源 15 秒是建议值，
     * 留出余量是因为运营偶尔会放 30 秒以上的素材。60 是兜底——填 3600 等于永远看不完。
     * 两边取同一个设置键，只有运营填 >30 时才会出现"倒计时先到、服务端还不认"，
     * 所以后台提示仍按 1-30 写。
     */
    public static function videoSeconds(): int
    {
        return min(60, max(1, Setting::int('ad_reward_seconds', 5)));
    }
}
