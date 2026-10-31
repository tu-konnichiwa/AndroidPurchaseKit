package com.tukonnichiwa.purchasekit

import android.text.TextUtils
import org.junit.rules.ExternalResource
import org.mockito.MockedStatic
import org.mockito.Mockito

/** The real Billing SDK calls this Android utility when parsing JSON or constructing flow params. */
class AndroidTextRule : ExternalResource() {
    private lateinit var mock: MockedStatic<TextUtils>
    override fun before() {
        mock = Mockito.mockStatic(TextUtils::class.java)
        mock.`when`<Boolean> { TextUtils.isEmpty(Mockito.any()) }.thenAnswer {
            it.getArgument<CharSequence?>(0).isNullOrEmpty()
        }
    }
    override fun after() { mock.close() }
}
