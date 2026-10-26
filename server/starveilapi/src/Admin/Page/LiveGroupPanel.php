<?php
/**
 * 直播页的「分组」子块：分组面板、下拉选择器、以及提交时的分组名解析与登记。
 *
 * 单独一个类只是因为 LivePage 里频道列表 + 导入 + 增删改已经够满；这里搬过来的
 * 代码与原来逐字一致，没有任何行为变化。
 *
 * 分组独立成表后可以「先建组、后导频道」，也能删掉不用的组。频道只在 `gname`
 * 上存名字，所以「用了新名字但没登记」会让分组管理里看不到它，而客户端按频道聚合
 * 又能看到——提交时统一在这里补登记，两边就不会不一致。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Admin\Ui\Badge;
use Starveil\Api\Admin\Ui\Select;
use Starveil\Api\Support\Text;
use think\Db;

final class LiveGroupPanel extends BasePage
{
    /** 分组下拉里"新建"那项的 value。服务端见到它就改读 `{field}_new`。 */
    public const GROUP_NEW = '__new__';

    /** 本次请求内已确认存在的分组名（`表名\0名字` => true），导入时避免重复查库。 */
    private static array $knownGroups = [];

    /**
     * 现有分组名，给下拉当候选。
     *
     * 名单必须先取分组表：运营会「先建空分组、再往里导频道」，只看频道表的
     * `gname` 就永远看不到这些新组（页面里建了组、下拉里却没有，等于选了新建却
     * 无处可选）。频道表里已用但还没登记的名字补在后面，两边不至于互相看不见。
     *
     * @return list<string>
     */
    public static function names(string $prefix): array
    {
        $names = [];
        foreach (Db::table($prefix . 'cuican_zhibo_group')
            ->order('sort asc,group_id asc')
            ->select() as $row) {
            $name = trim((string)($row['name'] ?? ''));
            // 「全部」是客户端聚合出来的概念，不是真实分组，不列进来
            if ($name !== '') {
                $names[$name] = true;
            }
        }
        foreach (Db::table($prefix . 'cuican_zhibo')
            ->field('gname, COUNT(*) AS cnt')
            ->group('gname')
            ->select() as $row) {
            $name = trim((string)($row['gname'] ?? ''));
            if ($name !== '') {
                $names[$name] = true;
            }
        }
        // 分组名可能是纯数字（如「2024」），会被 PHP 当成整数键，统一转回字符串
        return array_map('strval', array_keys($names));
    }

    /**
     * 分组管理面板。
     * 每个分组后面带当前频道数。删除分组＝整组下线：组里的频道会一起被删，
     * 所以按钮上挂一句确认文案（admin.js 见到 `_confirm` 会先问一遍），误点能刹住。
     */
    public function panel(string $prefix): string
    {
        $counts = [];
        foreach (Db::table($prefix . 'cuican_zhibo')
            ->field('gname, COUNT(*) AS cnt')
            ->group('gname')
            ->select() as $row) {
            $counts[trim((string)$row['gname'])] = (int)$row['cnt'];
        }
        $chips = '';
        foreach (Db::table($prefix . 'cuican_zhibo_group')
            ->order('sort asc,group_id asc')
            ->select() as $row) {
            $name = trim((string)($row['name'] ?? ''));
            if ($name === '') {
                continue;
            }
            $used = $counts[$name] ?? 0;
            $confirm = $used > 0
                ? '删除分组「' . $name . '」会连同组内 ' . $used . ' 个频道一起删除，且不可恢复。继续？'
                : '删除分组「' . $name . '」？';
            $chips .= '<span class="sv-inline sv-inline-tight">'
                . Badge::render($used > 0 ? 'ok' : 'off', $name . ' · ' . $used)
                . $this->postButton('groupDelete', '删除', 'danger', [
                    'group_name' => $name,
                    '_confirm' => $confirm,
                ])
                . '</span>';
        }
        if ($chips === '') {
            $chips = '<span class="sv-hint sv-hint-inline">还没有分组。先在左边建一个，导入时就能直接选。</span>';
        }
        return '<div class="sv-card"><div class="sv-section-title">分组</div>'
            . '<form method="post" class="sv-inline">' . $this->csrfField()
            . '<input type="hidden" name="op" value="groupAdd">'
            . '<input type="text" name="group_name" maxlength="64" placeholder="新分组名，如 央视"'
            . ' class="sv-group-input">'
            . '<button class="sv-btn sm" type="submit">添加分组</button>'
            . '</form>'
            . '<div class="sv-inline sv-group-chips">' . $chips . '</div>'
            . '<p class="sv-hint">点「删除」会连同该分组下的频道一起删掉，不可恢复；'
            . '只想把频道挪走，就在「直播列表」里勾选后用「设置分组」转过去。</p>'
            . '</div>';
    }

    /**
     * 批量导入时的分组选择：三个来源的优先级是 这里选的 > M3U 自己的 group-title > 空（未分组）。
     *
     * @param array<int,string> $groups 现有分组（不含「全部」，那是客户端聚合概念）
     */
    public function picker(array $groups): string
    {
        $options = ['' => '不指定（按 M3U 自带分组）'];
        foreach ($groups as $group) {
            $options[(string)$group] = (string)$group;
        }
        $options[self::GROUP_NEW] = '＋ 新建分组…';
        $hint = $groups === []
            ? '目前还没有任何分组。选「＋ 新建分组…」填个名字就会新建；'
                . '不指定则按 M3U 自带的 group-title 分组，仍然没有就归到「未分组」。'
            : '不指定 = 按 M3U 自带的 group-title 分组；选现有分组或新建一个都能直接写进去。';
        return '<label class="sv-field"><span>导入到分组（可选）</span>'
            . Select::render('gname', $options, '', 'js-group-new')
            // display 由 admin.js 切换（选中「＋ 新建分组…」时显示），只能留内联
            . '<input type="text" name="gname_new" id="sv-import-group-new" maxlength="64"'
            . ' placeholder="新分组名" style="display:none;margin-top:6px">'
            . '<em class="sv-hint">' . $hint . '</em></label>';
    }

    /**
     * 用后台统一的自定义下拉（不是浏览器原生 select）：
     * 原生弹层是系统方角 + 系统蓝高亮，和后台其它下拉完全两个风格。
     * 最后一项「＋ 新建分组…」选中后，旁边的输入框才显示。
     *
     * @param array<int,string> $groups 现有分组
     */
    public function select(string $field, string $value, array $groups, string $inputId): string
    {
        $options = ['' => '未分组'];
        foreach ($groups as $group) {
            $options[(string)$group] = (string)$group;
        }
        $options[self::GROUP_NEW] = '＋ 新建分组…';
        // 频道现在的分组名可能已经被删掉了，补一个同名选项，否则下拉会显示成「未分组」
        if ($value !== '' && $value !== self::GROUP_NEW && !isset($options[$value])) {
            $options[$value] = $value;
        }
        return '<label class="sv-field"><span>分组</span>'
            . Select::render($field, $options, $value, 'js-group-new')
            // 同上：display 归 admin.js 管
            . '<input type="text" name="' . self::e($field) . '_new" id="' . self::e($inputId) . '"'
            . ' maxlength="64" placeholder="输入新分组名，如 央视"'
            . ' style="display:none;margin-top:6px">'
            . '<em class="sv-hint">选已有分组直接点；要新建就选「＋ 新建分组…」再填名字。'
            . '选「未分组」＝不给它归类。</em></label>';
    }

    /**
     * 从提交里解析出最终分组名：选了「＋ 新建分组…」就读旁边那个输入框。
     *
     * @param array<string,mixed> $post
     */
    public static function resolve(array $post, string $field = 'gname'): string
    {
        $value = trim((string)($post[$field] ?? ''));
        if ($value === self::GROUP_NEW) {
            $value = trim((string)($post[$field . '_new'] ?? ''));
        }
        return mb_substr($value, 0, 64);
    }

    /**
     * 分组名是否已存在。
     *
     * 刻意在 PHP 里比而不是写成 `WHERE name = ?`：macCMS 老版本把连接字符集设成
     * utf8(mb3)，而插件表是 utf8mb4，某些 MySQL/MariaDB 会直接报
     * 1267 Illegal mix of collations，导入时每条频道都要走一次这个判断，
     * 一次报错整批就白导。分组表只有几行，读回来比在内存里更稳。
     */
    public static function nameExists(string $groupTable, string $name): bool
    {
        foreach (Db::table($groupTable)->field('name')->select() as $row) {
            if (trim((string)($row['name'] ?? '')) === $name) {
                return true;
            }
        }
        return false;
    }

    /** 按名字取一行分组（同样避开 SQL 层的字符串比较）。 */
    public static function findByName(string $groupTable, string $name): ?array
    {
        foreach (Db::table($groupTable)->select() as $row) {
            if (trim((string)($row['name'] ?? '')) === $name) {
                return $row;
            }
        }
        return null;
    }

    /**
     * 保存/批量/导入时把用到的分组名登记进分组表。
     *
     * 导入一份列表会反复调到这里，所以确认过的名字记在静态缓存里：
     * 一份 500 条的源最多查一次库，而不是 500 次。
     */
    public static function remember(string $groupTable, string $name): void
    {
        // 连接字符集装不下的字符（emoji 等）先削掉，否则一条坏名字毁掉整批导入
        $name = Text::forDb(trim($name));
        if ($name === '') {
            return;
        }
        $key = $groupTable . "\0" . $name;
        if (isset(self::$knownGroups[$key])) {
            return;
        }
        if (self::nameExists($groupTable, $name)) {
            self::$knownGroups[$key] = true;
            return;
        }
        Db::table($groupTable)->insert(['name' => $name, 'sort' => 0, 'created_at' => time()]);
        self::$knownGroups[$key] = true;
    }
}
