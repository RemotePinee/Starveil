<?php
/**
 * 支付回调。
 *
 * Epay 期望纯文本 `success`，其它通道按 JSON 回。验签失败一律非成功响应，
 * 让对方重试——验签返回 false 时反而继续走成功分支。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Service\Payment;

final class PaymentController extends BaseController
{
    public function getNotify(): void
    {
        $this->handle();
    }

    public function postNotify(): void
    {
        $this->handle();
    }

    private function handle(): void
    {
        $channel = $this->input->str('pay_type', $this->input->str('channel'));
        $params = array_merge($_GET, $_POST);
        $result = (new Payment())->notify($channel, $params);

        if ($channel === 'epay') {
            echo $result['code'] === 200 ? 'success' : 'fail';
            return;
        }
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }
}
