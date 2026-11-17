package app.gamenative.ui.screen.wow.auth

import app.gamenative.BuildConfig

/** Present known protocol errors locally without changing the BGS exchange or exposing payloads. */
internal fun bgsErrorText(error: BgsException, chinaEdition: Boolean = BuildConfig.CHINA_EDITION): String {
    val english = error.message ?: "Battle.net sign-in failed."
    if (!chinaEdition) return english
    return when (english) {
        "The installed client or device identity is unavailable." -> "无法读取已安装的客户端或设备信息。"
        "Battle.net requires an unsupported sign-in challenge." -> "战网要求的登录验证方式暂不受支持。"
        "Battle.net returned an unsupported sign-in address." -> "战网返回了不受支持的登录地址。"
        "The browser sign-in ticket is invalid." -> "网页登录凭据无效，请重新登录。"
        "Battle.net did not accept the sign-in. Sign in again." -> "战网未接受此次登录，请重新登录。"
        "The Battle.net sign-in attempt is no longer active." -> "此次战网登录已结束，请重新登录。"
        "The Battle.net connection ended. Check your connection and try again." -> "战网连接已断开，请检查网络后重试。"
        "Battle.net closed the sign-in connection. Try again." -> "战网关闭了登录连接，请重试。"
        "Battle.net's login queue took too long. Try again later." -> "战网登录排队超时，请稍后重试。"
        "Battle.net did not respond in time. Try again." -> "战网响应超时，请重试。"
        "Battle.net did not offer a browser sign-in." -> "战网未提供网页登录，请重试。"
        "Battle.net is not accepting this game build yet. Try again later." -> "战网暂未接受此游戏版本，请稍后重试。"
        "Battle.net returned an invalid protocol message." -> "战网返回了无效响应，请重试。"
        else -> error.status?.let { "战网拒绝了此次登录（状态码 $it）。" } ?: "战网登录失败，请重试。"
    }
}
