# 测试规范

> 对应 `AGENTS.md` 第 7 节。本文件说明"怎么写测试"，不重复"哪些必须有测试"。

## 1. 分层职责

| 模块 | 测什么 | 不测什么 |
|---|---|---|
| `:core:model` | 纯规则函数：直链判定、试看判定、URL 归一化、线路推进 | 任何 Android/网络依赖 |
| `:core:network` | DTO 反序列化契约、请求路径与参数、签名注入、错误体可读性 | 后端业务规则 |
| `:core:data` | 异常 → `AppError` 的收敛、`data` 为空时的降级 | 真实网络 |
| `:feature:*` | ViewModel 的状态流（`turbine`） | Compose 渲染细节 |
| `:app` | 仅装配验证（可无测试） | — |

## 2. 契约测试（`:core:network`）

规则：

1. 用真实 `OkHttp + Retrofit + kotlinx.serialization`，只把服务端换成 `MockWebServer`。
   不要 mock `StarveilApi`——那样测的是 mock，不是契约。
2. 响应夹具放 `src/test/resources/fixtures/`，**字段名按后端真实返回写**。
   夹具是契约，DTO 是待验证方；解析不过就改 DTO，不要改夹具。
3. 每个夹具至少断言：路径、必填字段、可选字段缺失时的默认值、未知字段被忽略。
4. 错误路径单独成例：HTTP 400 + 业务壳、HTML 响应体（后端方法不存在时的返回）。

参考实现：`core/network/src/test/kotlin/com/starveil/core/network/VodContractTest.kt`。

## 3. 规则测试（`:core:model`）

规则函数必须：

1. 覆盖空串、`null`、非法格式三类边界。
2. 历史上出现过有害分支的（例如把 `httpx://` 当成可播放地址），在测试里显式断言当前处理方式，
   避免以后"顺手改回来"。

参考实现：`core/model/src/test/kotlin/com/starveil/core/model/ParseRulesTest.kt`。

## 4. 仓库测试（`:core:data`）

- 用 `FakeApi`（手写实现接口）而不是 mock 框架：接口只有几个方法时 fake 更直观。
- 必须覆盖：成功映射、业务码 + `data` 为空、`IOException`、`HttpException`（带 JSON 体 / 不带 JSON 体）、
  `SerializationException`。
- 测试方法用 block body：`fun x() { runTest { ... } }`。
  写成表达式体会因为 `runTest` 返回 `TestResult` 触发 JUnit4 的
  `initializationError: Method should be void`。

## 5. 命名与断言

- 测试方法名用反引号包裹的英文句子（`success response is mapped to domain model`）。
  **不要用中文方法名**：反引号里的中文会编出非 ASCII 类名，在本机 Windows 路径编码下
  会让测试类扫描失败。
- 断言优先 JUnit4 原生 `assertEquals / assertTrue / assertNull`，不引第二套断言库。
- 一个测试只验证一个行为，失败信息要能直接指向原因。

## 6. 运行

```bash
./gradlew check                 # 提交前唯一闸门
./gradlew :core:network:test    # 单模块快速反馈
```

`check` 聚合：编译、单元测试、Android Lint、四个规范闸门、detekt。
