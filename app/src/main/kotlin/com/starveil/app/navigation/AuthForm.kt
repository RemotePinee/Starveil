package com.starveil.app.navigation

/**
 * 登录覆盖层里可以展开的整页表单。
 *
 * 这两个页面**不是 NavHost 目的地**：它们和登录锁屏、登录弹层一样画在 NavHost 之上。
 * 之前它们是路由页，点「注册」会先撤掉覆盖层、再等 NavHost 切页，中间那一两帧露出的
 * 是底下的首页——表现出来就是"点一下先闪了个首页"。放进同一个覆盖层之后，
 * NavHost 全程停在用户原来那一页，物理上不可能闪。
 */
internal enum class AuthForm {

    /** 注册（`register_modes` 决定是手机号还是账号注册）。 */
    REGISTER,

    /** 找回密码。 */
    RESET,
}
