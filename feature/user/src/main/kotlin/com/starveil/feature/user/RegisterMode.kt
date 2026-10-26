package com.starveil.feature.user

/**
 * 注册方式。
 *
 * 后端没有专门下发这个开关的字段，值由 app 层按运营配置注入，默认 [PHONE_SMS]。
 * 两种方式的差别只在表单字段、以及是否要先发短信验证码。
 */
enum class RegisterMode {

    /** 手机号注册：手机号 + 短信验证码 + 密码 + 确认密码。 */
    PHONE_SMS,

    /** 普通账号注册：账号 + 密码 + 确认密码，不发短信。 */
    ACCOUNT,
}

/** 注册页副标题：两种注册方式的文案必须区分，用户才知道该填什么。 */
internal val RegisterMode.subtitle: String
    get() = when (this) {
        RegisterMode.PHONE_SMS -> "手机号注册，注册后用手机号登录"
        RegisterMode.ACCOUNT -> "设置账号和密码，注册后用它登录"
    }

/** 账号一栏的占位符。 */
internal val RegisterMode.accountPlaceholder: String
    get() = when (this) {
        RegisterMode.PHONE_SMS -> "手机号"
        RegisterMode.ACCOUNT -> "账号"
    }
