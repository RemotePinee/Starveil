<?php
/**
 * 影片数据访问：列表 / 详情 / 分类 / 榜单。
 *
 * macCMS 把播放地址压在两列里：`vod_play_from`（源，`$$$` 分隔）与
 * `vod_play_url`（每源内 `#` 分集、`$` 分名称与地址）。客户端要的是结构化
 * `vod_play_list[]`，这里负责还原，且**保持 sid 从 1 开始**（客户端用 sid 定位线路）。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Service\Playback;
use Starveil\Api\Support\Setting;
use think\Db;
use think\db\Query;

final class Vods
{
    private const LIST_FIELDS = 'vod_id,vod_name,vod_pic,vod_remarks,vod_year,vod_area,'
        . 'vod_score,vod_blurb,type_id,vod_hits,vod_time';

    /**
     * 片库筛选项：`mac_type.type_extend` 里与列表页筛选对应的四个键。
     *
     * macCMS 的 `Extend` 是一个键一个方法：area→、lang→165、
     * class→215、year→271，前台筛选栏也只认这四个名字。
     */
    private const EXTEND_KEYS = ['area', 'year', 'class', 'lang'];

    /**
     * 排序字段白名单，照 macCMS `Vod::listCacheData()` 的校验表，
     * 排序时拼成 `vod_<by> <order>`。
     *
     * 比源站少两个值：`duration` 得先换算成秒数再排（ 的
     * buildDurationOrderExpr），`rnd` 是 ORDER BY RAND()——大表代价不可控，插件不提供。
     */
    private const ORDER_FIELDS = ['id', 'time', 'time_add', 'score', 'hits', 'hits_day',
        'hits_week', 'hits_month', 'up', 'down', 'level'];

    /** macCMS 列表页默认排序：`$by='time'`、`$order='desc'`。 */
    private const ORDER_BY_DEFAULT = 'time';

    /** 单个筛选参数最多收几个值，防止一长串逗号拼出超长 IN / LIKE 组。 */
    private const FILTER_VALUE_LIMIT = 32;

    /**
     * 老签名：只认 type/class/level/wd（收藏、播放记录按 id 兜底这类内部调用在用）。
     *
     * 片库筛选走 {@see listFiltered()}；这里保持签名不变，免得改调用方。
     */
    public function list(int $typeId, int $classId, int $level, string $keyword, int $page, int $limit): array
    {
        $filter = ['type' => $typeId, 'level' => $level, 'wd' => $keyword];
        if ($classId > 0) {
            // 纯数字才会走老语义（FIND_IN_SET），别把 0 传下去变成"匹配不到任何行"
            $filter['class'] = (string)$classId;
        }
        return $this->listFiltered($filter, $page, $limit);
    }

    /**
     * 列表（带片库筛选）。
     *
     * `$filter` 的键与 macCMS 前台参数同名（`mac_param_url()`）：
     * type / class / level / wd / year / area / lang / by / order。
     * 用数组而不是一长串形参：count 与 select 各要建一次 Query（见 filteredQuery），
     * 十个参数写两遍，错一个就得对着 SQL 猜。
     *
     * @param array<string,mixed> $filter
     * @return array{total:int,page:int,limit:int,list:array<int,array<string,mixed>>}
     */
    public function listFiltered(array $filter, int $page, int $limit): array
    {
        // 不要 clone 同一个 Query 做 count：TP5.0 的 Query 克隆是浅拷贝，
        // count 会改动 options/bind，接着执行主查询就会报 "Invalid parameter number"。
        $total = $this->filteredQuery($filter)->count();
        $rows = $this->filteredQuery($filter)
            ->field(self::LIST_FIELDS)
            ->order($this->orderOf($filter))
            ->page($page, $limit)
            ->select();

        // 分类名一次批量取，别在 toDto 里逐行查（一页 20 行就是 20 次 SELECT）
        $typeNames = $this->typeNameMap($rows);

        return [
            'total' => (int)$total,
            'page' => $page,
            'limit' => $limit,
            'list' => array_map(fn (array $row): array => $this->toDto($row, $typeNames), $rows),
        ];
    }

    /** 每次返回全新的 Query，避免 count 与 select 共用同一个可变对象。 */
    private function filteredQuery(array $filter): Query
    {
        $query = Db::name('vod')->where('vod_status', 1);

        $typeId = (int)($filter['type'] ?? 0);
        if ($typeId > 0) {
            // 父分类要连子分类一起查：短剧这类结构是「父分类只挂几部、内容全在子分类」，
            // 只写 `type_id = 父` 会返回一个接近空的列表，客户端看起来就是"这个分类没内容"。
            $query->whereIn('type_id', $this->typeIdsWithChildren($typeId));
        }

        $this->applyClassFilter($query, (string)($filter['class'] ?? ''));

        // 仅为兼容保留：`level=9` 取首页轮播、`level=8` 取分类页。
        // 新客户端的首页推荐（轮播/精选/热度榜/猜你喜欢/专区）全部走插件自己的表，
        // 不读 macCMS 的推荐字段，运营改影片的"推荐等级"不会影响新首页。
        $level = (int)($filter['level'] ?? 0);
        if ($level > 0) {
            $query->where('vod_level', $level);
        }

        $keyword = (string)($filter['wd'] ?? '');
        if ($keyword !== '') {
            $query->whereLike('vod_name', '%' . $keyword . '%');
        }

        // 三个"枚举字段"筛选：macCMS 一律 `['in', explode(',', 参数)]`。
        // vod_year / vod_area / vod_lang 是 varchar，一行只存一个值，IN 就是源站语义。
        foreach (['year' => 'vod_year', 'area' => 'vod_area', 'lang' => 'vod_lang'] as $key => $column) {
            $values = $this->filterValues($key, (string)($filter[$key] ?? ''));
            if ($values !== []) {
                $query->whereIn($column, $values);
            }
        }

        return $query;
    }

    /**
     * 一个分类 + 它的直属子分类。
     *
     * 只为「父分类」多一次查询：`mac_vod.type_id` 永远指向末级分类，父分类本身往往只有
     * 少数几部（或者一部都没有），只按父分类匹配就会把一个有几百部内容的分类显示成空。
     * 没有子分类时返回只含自己的数组，SQL 与改造前等价。
     *
     * @return int[]
     */
    private function typeIdsWithChildren(int $typeId): array
    {
        $children = Db::name('type')->where('type_pid', $typeId)->column('type_id');
        if (!$children) {
            return [$typeId];
        }
        return array_merge([$typeId], array_map('intval', $children));
    }

    /**
     * 扩展分类筛选：同一个 `class` 参数承载两种语义。
     *
     * - 纯数字 → 老契约。接口从一开始把 `class` 当整数、用 FIND_IN_SET 匹配 `vod_class`，
     * 已发布的客户端传的是数字串，这条路径逐字保留。
     * - 其它 → macCMS 前台的 `class` 语义："扩展分类名"。 收 `class`，
     * / 530-532 用 `['like', mac_like_arr($class), 'OR']` 匹配，
     * 而 mac_like_arr把逗号串拆成 `%值%` 并按 OR 相连。
     * 所以必须是 LIKE：`vod_class` 存的是 "喜剧,动作" 这种逗号串
     * （install.sql:1104 varchar(255)），等值比较一条都命中不了。
     */
    private function applyClassFilter(Query $query, string $class): void
    {
        $class = trim($class);
        if ($class === '') {
            return;
        }
        if (ctype_digit($class)) {
            $query->whereRaw('FIND_IN_SET(:classId, vod_class)', ['classId' => (int)$class]);
            return;
        }

        $patterns = array_map(
            static fn (string $name): string => '%' . $name . '%',
            $this->limitValues($this->csvTokens($class)),
        );
        if ($patterns === []) {
            return;
        }
        // 用"整条 where 数组"塞进去，和 macCMS 传 $where 的写法一致：
        // Builder::parseWhereItem 会把 ['like', [...], 'OR'] 编成
        // (vod_class LIKE ? OR vod_class LIKE ?)，OR 落在括号内，不会串到外层 AND 链上。
        $query->where(['vod_class' => ['like', $patterns, 'OR']]);
    }

    /**
     * 排序：`by` / `order` 都过白名单，客户端字符串不会进 SQL。
     *
     * 默认 time + desc，与 macCMS 列表页一致（、567）。末尾的
     * `vod_id desc` 只为并列时稳定分页，源站是裸 `vod_time desc`。
     */
    private function orderOf(array $filter): string
    {
        $by = (string)($filter['by'] ?? '');
        if (!in_array($by, self::ORDER_FIELDS, true)) {
            $by = self::ORDER_BY_DEFAULT;
        }

        $order = strtolower((string)($filter['order'] ?? ''));
        if (!in_array($order, ['asc', 'desc'], true)) {
            $order = 'desc';
        }

        $sql = 'vod_' . $by . ' ' . $order;
        return $by === 'id' ? $sql : $sql . ',vod_id desc';
    }

    /**
     * 单个筛选参数 → IN 用的值列表。
     *
     * `year` 额外支持 macCMS 的区间写法：`2010-2018` 展开成每年一个值。
     *
     * @return array<int,string>
     */
    private function filterValues(string $key, string $raw): array
    {
        $raw = trim($raw);
        if ($raw === '') {
            return [];
        }
        if ($key === 'year') {
            $range = $this->yearRange($raw);
            if ($range !== []) {
                return $range;
            }
        }
        return $this->limitValues($this->csvTokens($raw));
    }

    /**
     * `2010-2018` → `['2010', …, '2018']`；不是区间就返回空数组。
     *
     * @return array<int,string>
     */
    private function yearRange(string $raw): array
    {
        if (preg_match('/^(\d{4})\s*-\s*(\d{4})$/', $raw, $matches) !== 1) {
            return [];
        }
        $start = (int)$matches[1];
        $end = (int)$matches[2];
        if ($start > $end) {
            [$start, $end] = [$end, $start];
        }
        // 先截断再展开，别为 `1000-9999` 这种输入先造九千个元素
        $end = min($end, $start + self::FILTER_VALUE_LIMIT - 1);

        $years = [];
        for ($year = $start; $year <= $end; $year++) {
            $years[] = (string)$year;
        }
        return $years;
    }

    /**
     * 逗号串 → 去空白、去空项、去重后的值列表（顺序不变）。
     *
     * @return array<int,string>
     */
    private function csvTokens(string $raw): array
    {
        $tokens = [];
        foreach (explode(',', $raw) as $token) {
            $token = trim($token);
            if ($token !== '' && !in_array($token, $tokens, true)) {
                $tokens[] = $token;
            }
        }
        return $tokens;
    }

    /**
     * 截断到 FILTER_VALUE_LIMIT 个。
     *
     * @param array<int,string> $values
     * @return array<int,string>
     */
    private function limitValues(array $values): array
    {
        return array_slice($values, 0, self::FILTER_VALUE_LIMIT);
    }

    /** 按 id 批量取（专题详情用），保持传入顺序无意义，按 id 去重即可。 */
    public function byIds(array $vodIds): array
    {
        $ids = array_values(array_unique(array_filter($vodIds, static fn (int $id): bool => $id > 0)));
        if ($ids === []) {
            return [];
        }
        $rows = Db::name('vod')
            ->where('vod_status', 1)
            ->whereIn('vod_id', $ids)
            ->field(self::LIST_FIELDS)
            ->select();
        $typeNames = $this->typeNameMap($rows);
        return array_map(fn (array $row): array => $this->toDto($row, $typeNames), $rows);
    }

    /**
     * 详情。
     *
     * `$userId` 决定播放地址是否下发：未登录传 null。放行态原样返回直链；
     * 命中非放行态（需会员 / 需积分 / 未购 / 未广告解锁）时把每条 `urls[].url` 清空，
     * 只留 `nid` / `name`，并给出 `trysee`（秒）与 `play_locked`。
     *
     * 为什么不用 CMS 的 `check_user_popedom()`：Kernel 统一按 `new $class(Input, Session)`
     * 建控制器，而 `\app\common\controller\All` 的构造器不收参数，还要 `request()` 与模板
     * 状态（它的子类 `app\api\controller\Base` 甚至会把"站点关闭"直接 503）。继承等于把
     * 每个控制器的入口契约换掉。这里改用与 App 同一套真相：`Service\Playback`，判定细节
     * 见 [playGate]；集名与 `trysee` 秒数照样下发，客户端据此画"试看 / 解锁"引导。
     */
    public function detail(int $vodId, ?int $userId = null): ?array
    {
        $row = Db::name('vod')->where('vod_id', $vodId)->where('vod_status', 1)->find();
        if (!$row) {
            return null;
        }
        $dto = $this->toDto($row, $this->typeNameMap([$row]));
        $dto['vod_content'] = (string)($row['vod_content'] ?? '');
        $gate = $this->playGate($row, $userId);
        $sources = $this->playSources($row);
        if (!$gate['allowed']) {
            // 非放行态只回集号与集名：地址一旦下发，付费墙就只剩前端在把关。
            foreach ($sources as $si => $source) {
                foreach ($source['urls'] as $ui => $_) {
                    $sources[$si]['urls'][$ui]['url'] = '';
                }
            }
        }
        $dto['vod_play_list'] = $sources;
        $dto['play_locked'] = $gate['allowed'] ? 0 : 1;
        $dto['trysee'] = $gate['trysee'];

        return $dto;
    }

    /**
     * 这行影片这次能不能拿播放地址。
     *
     * 判定只用**一套真相**：`Service\Playback::trySee()`。它内部依次看免费档、
     * 广告解锁记录、会员等级、单集已购，与 `v1.user/checkVodTrySee` 下发给客户端的是
     * 同一个函数——详情页说"能播"，这里就必须放；说"要开通"，这里就不能给地址。
     *
     * 为什么**不**顺手把 `mac_vod.vod_points_play` 也当闸门：那是 macCMS 自己的
     * `mac_ulog` 积分体系，插件没有对应的购买入口（`cuican_order` 与它不互通）。
     * 拿它在这里硬拦，运营只要给影片填过播放积分，App 就会把内容锁死、而客户端连
     * "购买"按钮都渲染不出来（`can_points` 来自插件自己的 `episode_points`）。
     * 要让 App 也收费，正确做法是后台设 `trysee_status=2` + `episode_points`。
     *
     * `trysee` 统一换成秒；`vod_trysee > 0` 时覆盖插件设置，与 CMS `label_vod_play` 同序。
     *
     * @param array<string,mixed> $row
     * @return array{allowed:bool,trysee:int}
     */
    private function playGate(array $row, ?int $userId): array
    {
        $gate = (new Playback())->trySee($userId, (int)$row['vod_id']);

        $minutes = (int)($row['vod_trysee'] ?? 0);
        if ($minutes <= 0) {
            $minutes = (int)$gate['trysee'];
        }

        return ['allowed' => $gate['status'] === 0, 'trysee' => max($minutes, 0) * 60];
    }

    public function types(): array
    {
        $rows = Db::name('type')
            ->field('type_id,type_name,type_pid,type_extend')
            ->where('type_status', 1)
            ->where('type_mid', 1)
            ->order('type_sort asc,type_id asc')
            ->select();
        return array_map(fn (array $row): array => [
            'type_id' => (int)$row['type_id'],
            'type_name' => (string)$row['type_name'],
            'type_pid' => (int)$row['type_pid'],
            'extend' => $this->extendOf($row['type_extend'] ?? ''),
        ], $rows);
    }

    /**
     * `mac_type.type_extend` → 列表页筛选项。
     *
     * 源码里是两层结构，别只解一层：
     * 1. 整列是 **JSON 字符串**——列定义 `text`（install.sql:913），保存时
     * `json_encode`，进缓存/取详情时 `json_decode`（、244）。
     * 2. 每个键的值是 **逗号串**——`Extend::areaData()` 取到后直接
     * `explode(',', $type_extend['area'])`，
     * lang / class / year 同（、215、271 往下各自 explode(',')）。
     *
     * 键缺失、JSON 解不出来、值是空串，一律给空数组：筛选栏空着，不影响列表本身。
     *
     * 注：后台 `Type::extend()`还会做"父分类 → 全站 vod_extend_*"
     * 的兜底，那是**后台编辑页**的显示逻辑；列表页的 `Extend::*Data()` 不做这层兜底，
     * 按列表页走，只读本分类自己存下来的值。
     *
     * @return array{area:array<int,string>,year:array<int,string>,class:array<int,string>,lang:array<int,string>}
     */
    private function extendOf(mixed $raw): array
    {
        $decoded = is_array($raw) ? $raw : json_decode((string)$raw, true);
        $extend = [];
        foreach (self::EXTEND_KEYS as $key) {
            $extend[$key] = is_array($decoded) && array_key_exists($key, $decoded)
                ? $this->extendTokens($decoded[$key])
                : [];
        }
        return $extend;
    }

    /**
     * type_extend 的单个值 → 值列表。
     *
     * 正常是逗号串；多语言回填（`mac_content_lang_overlay`）可能塞成数组，
     * 见 ，两种都吃。
     *
     * @return array<int,string>
     */
    private function extendTokens(mixed $value): array
    {
        if (is_array($value)) {
            $parts = [];
            foreach ($value as $item) {
                // 只收标量：脏数据里套数组时宁可丢掉这一项，也不要在 (string) 上抛 Notice
                if (is_scalar($item)) {
                    $parts[] = (string)$item;
                }
            }
            return $this->limitValues($this->csvTokens(implode(',', $parts)));
        }
        return $this->limitValues($this->csvTokens((string)$value));
    }

    /** 单榜：`order_by` 由服务端白名单决定，不接受客户端任意拼接（直接拼 SQL）。 */
    public function board(string $orderBy, int $typeId, int $page, int $limit): array
    {
        $allowed = ['vod_hits desc', 'vod_hits_month desc', 'vod_hits_week desc', 'vod_hits_day desc', 'vod_score desc'];
        $order = in_array($orderBy, $allowed, true) ? $orderBy : $allowed[0];

        $query = Db::name('vod')->where('vod_status', 1);
        if ($typeId > 0) {
            $query->whereIn('type_id', $this->typeIdsWithChildren($typeId));
        }
        $rows = $query->field(self::LIST_FIELDS)
            ->order($order . ',vod_id desc')
            ->page($page, $limit)
            ->select();

        $typeNames = $this->typeNameMap($rows);
        return ['list' => array_map(fn (array $row): array => $this->toDto($row, $typeNames), $rows)];
    }

    /** 全量榜单：按分类聚合，给客户端做"分类横滑 + 每组榜单"。 */
    public function catalog(int $limitPerType): array
    {
        // mac_type 没有 type_img 列，封面叫 type_pic（type_logo 是 logo）
        $types = Db::name('type')
            ->field('type_id,type_name,type_pic')
            ->where('type_status', 1)
            ->where('type_mid', 1)
            // 后台建过但没填名字的分类：客户端拿到就是一条没有文字的标签，
            // 先挡掉，别让它出现在榜单分类里
            ->where('type_name', '<>', '')
            ->order('type_sort asc')
            ->limit(20)
            ->select();
        $groups = [];
        foreach ($types as $type) {
            $rows = Db::name('vod')
                ->where('vod_status', 1)
                ->where('type_id', (int)$type['type_id'])
                ->field(self::LIST_FIELDS)
                ->order('vod_hits desc,vod_id desc')
                ->limit($limitPerType)
                ->select();
            if ($rows === []) {
                continue;
            }
            $groups[] = [
                // 字段名必须是 vod_type_*：客户端契约按这个前缀解析，
                // 写成 type_* 会被当成缺字段 -> typeId=0 + 空名字，界面上就是一条没有文字的标签
                'vod_type_id' => (int)$type['type_id'],
                'vod_type_name' => (string)$type['type_name'],
                'vod_type_img' => (string)($type['type_pic'] ?? ''),
                // 分组本身就是按 type_id 查的，分类名现成，不再单独查一次
                'vod_list' => array_map(
                    fn (array $row): array => $this->toDto($row, [(int)$type['type_id'] => (string)$type['type_name']]),
                    $rows,
                ),
            ];
        }
        return ['list' => $groups];
    }

    /**
     * mac_vod 行 → 客户端 VodDto。字段名与后端一致（snake_case）。
     *
     * `$typeNames` 是 type_id => type_name 的映射，由调用方按整批行查一次后传入；
     * 不传时 `type_name` 为空串（类型 id 为 0 或分类已删也是空串）。
     */
    public function toDto(array $row, array $typeNames = []): array
    {
        $typeId = (int)($row['type_id'] ?? 0);

        return [
            'vod_id' => (int)$row['vod_id'],
            'vod_name' => (string)$row['vod_name'],
            'vod_pic' => (string)($row['vod_pic'] ?? ''),
            'vod_remarks' => (string)($row['vod_remarks'] ?? ''),
            'vod_year' => (string)($row['vod_year'] ?? ''),
            'vod_area' => (string)($row['vod_area'] ?? ''),
            'vod_score' => (string)($row['vod_score'] ?? ''),
            'vod_blurb' => (string)($row['vod_blurb'] ?? ''),
            'type_id' => $typeId,
            'type_name' => (string)($typeNames[$typeId] ?? ''),
            'vod_play_list' => [],
        ];
    }

    /**
     * 一批 mac_vod 行涉及的分类名，一条 SQL 取回。
     *
     * `column('type_name', 'type_id')` 返回 `[type_id => type_name]`；
     * type_id <= 0 的行不进 IN 列表，查不到自然落到空串。
     *
     * @param array<int,array> $rows
     * @return array<int,string>
     */
    private function typeNameMap(array $rows): array
    {
        $ids = [];
        foreach ($rows as $row) {
            $typeId = (int)($row['type_id'] ?? 0);
            if ($typeId > 0) {
                $ids[$typeId] = true;
            }
        }
        if ($ids === []) {
            return [];
        }
        $map = Db::name('type')->whereIn('type_id', array_keys($ids))->column('type_name', 'type_id');

        return array_map(static fn ($name): string => (string)$name, $map);
    }

    /**
     * `vod_play_from` / `vod_play_url` → 播放源（不含 player_info）。
     *
     * 详情与 `v1.vod/playUrl` 共用这一份解析：`$$$` 分源、`#` 分集、`$` 分名称与地址，
     * 三层拆分只在这里写，控制器不要再抄一遍（抄了迟早有一处对不上）。
     *
     * @param array<string,mixed> $row
     * @return array<int,array{sid:int,from:string,urls:array<int,array{nid:int,name:string,url:string}>}>
     */
    private function playGroups(array $row): array
    {
        $froms = array_values(array_filter(explode('$$$', (string)($row['vod_play_from'] ?? ''))));
        $urls = array_values(array_filter(explode('$$$', (string)($row['vod_play_url'] ?? ''))));

        $groups = [];
        foreach ($froms as $index => $from) {
            $episodes = [];
            $raw = (string)($urls[$index] ?? '');
            foreach (array_filter(explode('#', $raw)) as $nid => $item) {
                $parts = explode('$', $item, 2);
                $episodes[] = [
                    'nid' => $nid + 1,
                    'name' => trim($parts[0]) !== '' ? trim($parts[0]) : '第' . ($nid + 1) . '集',
                    'url' => trim($parts[1] ?? ''),
                ];
            }
            $groups[] = ['sid' => $index + 1, 'from' => (string)$from, 'urls' => $episodes];
        }

        return $groups;
    }

    /**
     * 播放源还原。
     *
     * 结构：`[{sid,from,url_count,urls:[{nid,name,url}],player_info}]`。
     * `player_info` 是全站配置（解析站模板、直链正则、请求头），存在 setting 里，
     * 历史版本是后端按源下发，这里简化为全站一份——要按源区分再加列。
     *
     * @param array<string,mixed> $row
     * @return array<int,array<string,mixed>>
     */
    private function playSources(array $row): array
    {
        $playerInfo = Setting::playerInfo();

        $sources = [];
        foreach ($this->playGroups($row) as $group) {
            $sources[] = [
                'sid' => (int)$group['sid'],
                'from' => (string)$group['from'],
                'url_count' => count($group['urls']),
                'urls' => $group['urls'],
                'player_info' => [
                    'parse2' => (string)($playerInfo['parse2'] ?? ''),
                    'features' => (string)($playerInfo['features'] ?? ''),
                    'issethead' => (string)($playerInfo['issethead'] ?? ''),
                    'headers' => (string)($playerInfo['headers'] ?? ''),
                    'hongguo' => (string)($playerInfo['hongguo'] ?? '0'),
                    'hongguo_match' => (string)($playerInfo['hongguo_match'] ?? ''),
                ],
            ];
        }
        return $sources;
    }

    /**
     * 取某源第 N 集的原始地址；`$sid <= 0` 表示不限源（取第一个命中的源）。
     *
     * **只做定位，不做权限判断**：调用方必须先过 [detail] 的闸门。
     */
    public function playUrl(int $vodId, int $sid, int $nid): string
    {
        if ($vodId <= 0 || $nid <= 0) {
            return '';
        }
        $row = Db::name('vod')
            ->where('vod_id', $vodId)
            ->where('vod_status', 1)
            ->field('vod_id,vod_play_from,vod_play_url')
            ->find();
        if (!$row) {
            return '';
        }
        foreach ($this->playGroups($row) as $group) {
            if ($sid > 0 && (int)$group['sid'] !== $sid) {
                continue;
            }
            foreach ($group['urls'] as $episode) {
                if ((int)$episode['nid'] === $nid) {
                    return (string)$episode['url'];
                }
            }
            if ($sid > 0) {
                // 指定源里没有这一集：上层传错了 sid，别去别的源猜一个地址出来
                break;
            }
        }
        return '';
    }
}
