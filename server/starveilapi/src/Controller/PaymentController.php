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
        $params = array_merge($_GET, $_POST);
        $payment = new Payment();
        // 通道 code 的三个来源：路径段（CMS 的 pay_type/epay 形态）、pay_type、网关回传的 type。
        // 都认不出时 Payment 退回订单下单时用的通道，网关才不会只收到一个 404。
        $channel = $payment->resolveChannel(
            [$this->pathChannel(), $this->input->str('pay_type'), $this->input->str('type')],
            (string)($params['out_trade_no'] ?? ''),
        );
        $result = $payment->notify($channel, $params);

        // 认不出通道时也要给网关纯文本：回 404 JSON 它只会无意义地重试
        if ($channel === '' || $channel === 'epay') {
            echo $result['code'] === 200 ? 'success' : 'fail';
            return;
        }
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    /**
     * `.../notify/pay_type/epay` 里的通道 code。
     *
     * 本插件的路由只认 `v1.Payment/notify` 两段（`Kernel::resolve`），多一段走不到这里，
     * 所以正常回调靠查询串；路径段解析留着，路由放宽或换入口时不用再改控制器。
     */
    private function pathChannel(): string
    {
        $path = (string)($_SERVER['PATH_INFO'] ?? '');
        if ($path === '') {
            $path = (string)parse_url((string)($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH);
        }
        $segments = array_values(array_filter(explode('/', $path), static fn (string $s): bool => $s !== ''));
        $last = count($segments) - 1;
        if ($last >= 1
            && in_array(strtolower($segments[$last - 1]), ['pay_type', 'paytype', 'channel', 'type'], true)) {
            return (string)$segments[$last];
        }
        return '';
    }
}
