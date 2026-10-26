---
name: Bug 报告
about: 客户端或插件的行为和预期不一致
title: '[bug] '
labels: bug
---

## 期望行为

## 实际行为

## 复现步骤

1.
2.
3.

## 环境

- 端：Android 客户端 / 苹果 CMS 插件 / 两端
- 客户端版本（`app/build.gradle.kts` 的 `versionName`，或设置页）：
- Android 版本 / 机型：
- 插件版本（`info.ini` 的 `version`）：
- 站点环境：PHP 版本 / MySQL 版本 / 苹果 CMS 版本

## 证据

- 报错文本（贴原文，不要只写"报错了"）
- 改了客户端时：`./gradlew check` 是否全绿
- 改了插件时：逐文件 `php -l` 是否通过；业务逻辑改动是否本地跑过 `tests/smoke.sh`，PASS/FAIL 多少
- 截图或录屏（如有）

## 不受理的请求

以下内容不要提 issue，见 [DISCLAIMER.md](../../DISCLAIMER.md)：

- 采集配置、片源对接、解析规则
- 防封、绕过高防、规避权利人检测
- 索取可运营的内容源或测试站点
