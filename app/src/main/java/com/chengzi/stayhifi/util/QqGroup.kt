package com.chengzi.stayhifi.util

object QqGroup {
    const val GROUP_NUMBER = "1131911367"
    const val QQ_PACKAGE = "com.tencent.mobileqq"

    fun groupCardUri(): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1" +
            "&uin=$GROUP_NUMBER&card_type=group&source=qrcode"
}
