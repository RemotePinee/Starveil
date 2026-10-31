<?php
/**
 * 一次播放地址下发绑定的「谁、哪部剧、哪一集」。
 *
 * 三个值都是签名原料（只参与 `self` 模式的签名串，见 [CdnUrlSigner]）：不带上它们，
 * 一条地址就是一张"任何人都能用、还能换成同一目录下任何一集"的门票——付费用户把地址
 * 贴到群里，所有人在 token TTL 内都能重放，也查不出是谁漏的。
 *
 * 归一成 `int` 而不是带着 `null` 拼串：`null` 与 `0` 会拼出两种不同的串，未登录用户
 * 每次拿到的地址都会与媒体服务的复算结果对不上（表现为"全站播不了"，而不是"少一个功能"）。
 *
 * 为什么放在 `Support` 而不是直接给 `signedUrl()` 加三个形参：`episodeUrl()` 本来就已有
 * 四个参数，再加三个 `int` 全是同类型，`vodId` 与 `nid` 传反了照样能算出合法签名——
 * 那种 bug 不会报错，只会让绑定绑错东西。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class PlayIdentity
{
    /** 未登录（或调用方拿不到身份）时签名串里的固定值：用 0，不用空串。 */
    public const NO_USER = 0;

    public readonly int $userId;

    public readonly int $vodId;

    public readonly int $nid;

    public function __construct(?int $userId, int $vodId, int $nid)
    {
        $this->userId = max(self::NO_USER, (int)$userId);
        $this->vodId = max(0, $vodId);
        $this->nid = max(0, $nid);
    }

    /**
     * 签名串里的绑定段：`|uid|vod|nid`。
     *
     * 分隔符不能省。裸拼（`uid . vod . nid`）会让 `uid=1, vod=9, nid=23` 与
     * `uid=19, vod=2, nid=3` 拼出同一个 `1923`——两串签名相同，绑定等于没做：别人拿
     * 自己那条合法地址改两个数字就能换成别人的剧集。
     */
    public function signaturePart(): string
    {
        return '|' . $this->userId . '|' . $this->vodId . '|' . $this->nid;
    }

    /** URL 上的绑定参数（不含前后连接符），顺序与 [signaturePart] 一致，便于对照排查。 */
    public function queryPart(): string
    {
        return 'uid=' . $this->userId . '&vod=' . $this->vodId . '&nid=' . $this->nid;
    }
}
