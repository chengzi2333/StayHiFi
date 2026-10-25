package com.chengzi.stayhifi.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QqGroupTest {
    @Test
    fun groupCardUriUsesApprovedGroupAndQqPackage() {
        assertEquals("1131911367", QqGroup.GROUP_NUMBER)
        assertEquals("com.tencent.mobileqq", QqGroup.QQ_PACKAGE)
        assertEquals(
            "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                "&uin=1131911367&card_type=group&source=qrcode",
            QqGroup.groupCardUri(),
        )
    }
}
