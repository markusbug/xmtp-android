package org.xmtp.android.library

import com.google.protobuf.kotlin.toByteStringUtf8
import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmtp.android.library.codecs.Attachment
import org.xmtp.android.library.codecs.AttachmentCodec
import org.xmtp.android.library.codecs.ContentTypeAttachment
import org.xmtp.android.library.messages.walletAddress

class AttachmentTest {
    @Test
    fun testCanUseAttachmentCodec() {
        val attachment = Attachment(
            filename = "test.txt",
            mimeType = "text/plain",
            data = "hello world".toByteStringUtf8(),
        )

        Client.register(codec = AttachmentCodec())

        val fixtures = fixtures()
        val aliceClient = fixtures.aliceClient
        val aliceConversation =
            aliceClient.conversations.newConversation(fixtures.bob.walletAddress)

        aliceConversation.send(
            content = attachment,
            options = SendOptions(contentType = ContentTypeAttachment),
        )
        val messages = aliceConversation.messages()
        assertEquals(messages.size, 1)
        if (messages.size == 1) {
            val content: Attachment? = messages[0].content()
            assertEquals("test.txt", content?.filename)
            assertEquals("text/plain", content?.mimeType)
            assertEquals("hello world".toByteStringUtf8(), content?.data)
        }
    }
}
