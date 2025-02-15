package org.xmtp.android.library

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.crypto.Hash
import org.xmtp.android.library.codecs.TextCodec
import org.xmtp.android.library.messages.ContactBundle
import org.xmtp.android.library.messages.Envelope
import org.xmtp.android.library.messages.EnvelopeBuilder
import org.xmtp.android.library.messages.InvitationV1
import org.xmtp.android.library.messages.InvitationV1ContextBuilder
import org.xmtp.android.library.messages.MessageBuilder
import org.xmtp.android.library.messages.MessageHeaderV2Builder
import org.xmtp.android.library.messages.MessageV1Builder
import org.xmtp.android.library.messages.MessageV2Builder
import org.xmtp.android.library.messages.PrivateKey
import org.xmtp.android.library.messages.PrivateKeyBuilder
import org.xmtp.android.library.messages.SealedInvitationBuilder
import org.xmtp.android.library.messages.SealedInvitationHeaderV1
import org.xmtp.android.library.messages.SignedContentBuilder
import org.xmtp.android.library.messages.Topic
import org.xmtp.android.library.messages.createRandom
import org.xmtp.android.library.messages.getPublicKeyBundle
import org.xmtp.android.library.messages.header
import org.xmtp.android.library.messages.recoverWalletSignerPublicKey
import org.xmtp.android.library.messages.sign
import org.xmtp.android.library.messages.toPublicKeyBundle
import org.xmtp.android.library.messages.toSignedPublicKeyBundle
import org.xmtp.android.library.messages.toV2
import org.xmtp.android.library.messages.walletAddress
import org.xmtp.proto.message.contents.Invitation
import org.xmtp.proto.message.contents.Invitation.InvitationV1.Context
import java.nio.charset.StandardCharsets
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {
    lateinit var fakeApiClient: FakeApiClient
    lateinit var aliceWallet: PrivateKeyBuilder
    lateinit var bobWallet: PrivateKeyBuilder
    lateinit var alice: PrivateKey
    lateinit var aliceClient: Client
    lateinit var bob: PrivateKey
    lateinit var bobClient: Client

    private fun publishLegacyContact(client: Client) {
        val contactBundle = ContactBundle.newBuilder().apply {
            v1Builder.keyBundle = client.privateKeyBundleV1?.toPublicKeyBundle()
        }.build()
        val envelope = Envelope.newBuilder().apply {
            contentTopic = Topic.contact(client.address).description
            timestampNs = (Date().time * 1_000_000)
            message = contactBundle.toByteString()
        }.build()

        client.publish(envelopes = listOf(envelope))
    }

    @Before
    fun setUp() {
        aliceWallet = PrivateKeyBuilder()
        alice = aliceWallet.getPrivateKey()
        bobWallet = PrivateKeyBuilder()
        bob = bobWallet.getPrivateKey()
        fakeApiClient = FakeApiClient()
        aliceClient = Client().create(account = aliceWallet, apiClient = fakeApiClient)
        bobClient = Client().create(account = bobWallet, apiClient = fakeApiClient)
    }

    @Test
    fun testDoesNotAllowConversationWithSelf() {
        val client = Client().create(account = aliceWallet)
        assertThrows("Recipient is sender", XMTPException::class.java) {
            client.conversations.newConversation(alice.walletAddress)
        }
    }

    @Test
    fun testCanInitiateV2Conversation() {
        val existingConversations = aliceClient.conversations.conversations
        assert(existingConversations.isEmpty())
        val conversation = bobClient.conversations.newConversation(alice.walletAddress)
        val aliceInviteMessage =
            fakeApiClient.findPublishedEnvelope(Topic.userInvite(alice.walletAddress))
        val bobInviteMessage =
            fakeApiClient.findPublishedEnvelope(Topic.userInvite(bob.walletAddress))
        assert(aliceInviteMessage != null)
        assert(bobInviteMessage != null)
        assertEquals(conversation.peerAddress, alice.walletAddress)
        val newConversations = aliceClient.conversations.list()
        assertEquals("already had conversations somehow", 1, newConversations.size)
    }

    @Test
    fun testCanFindExistingV1Conversation() {
        val encoder = TextCodec()
        val encodedContent = encoder.encode(content = "hi alice")
        // Get a date that's roughly two weeks ago to test with
        val someTimeAgo = Date(System.currentTimeMillis() - 2_000_000)
        val messageV1 = MessageV1Builder.buildEncode(
            sender = bobClient.privateKeyBundleV1!!,
            recipient = aliceClient.privateKeyBundleV1?.toPublicKeyBundle()!!,
            message = encodedContent.toByteArray(),
            timestamp = someTimeAgo
        )
        // Overwrite contact as legacy
        bobClient.publishUserContact(legacy = true)
        aliceClient.publishUserContact(legacy = true)
        bobClient.publish(
            envelopes = listOf(
                EnvelopeBuilder.buildFromTopic(
                    topic = Topic.userIntro(bob.walletAddress),
                    timestamp = someTimeAgo,
                    message = MessageBuilder.buildFromMessageV1(v1 = messageV1).toByteArray()
                ),
                EnvelopeBuilder.buildFromTopic(
                    topic = Topic.userIntro(alice.walletAddress),
                    timestamp = someTimeAgo,
                    message = MessageBuilder.buildFromMessageV1(v1 = messageV1).toByteArray()
                ),
                EnvelopeBuilder.buildFromTopic(
                    topic = Topic.directMessageV1(
                        bob.walletAddress,
                        alice.walletAddress
                    ),
                    timestamp = someTimeAgo,
                    message = MessageBuilder.buildFromMessageV1(v1 = messageV1).toByteArray()
                )
            )
        )
        var conversation = aliceClient.conversations.newConversation(bob.walletAddress)
        assertEquals(conversation.peerAddress, bob.walletAddress)
        assertEquals(conversation.createdAt, someTimeAgo)
        val existingMessages = fakeApiClient.published.size
        conversation = bobClient.conversations.newConversation(alice.walletAddress)

        assertEquals(
            "published more messages when we shouldn't have",
            existingMessages,
            fakeApiClient.published.size
        )
        assertEquals(conversation.peerAddress, alice.walletAddress)
        assertEquals(conversation.createdAt, someTimeAgo)
    }

    @Test
    fun testCanFindExistingV2Conversation() {
        val existingConversation = bobClient.conversations.newConversation(
            alice.walletAddress,
            context = InvitationV1ContextBuilder.buildFromConversation("http://example.com/2")
        )
        var conversation: Conversation? = null
        fakeApiClient.assertNoPublish {
            conversation = bobClient.conversations.newConversation(
                alice.walletAddress,
                context = InvitationV1ContextBuilder.buildFromConversation("http://example.com/2")
            )
        }
        assertEquals(
            "made new conversation instead of using existing one",
            conversation!!.topic,
            existingConversation.topic
        )
    }

    @Test
    fun testCanLoadV1Messages() {
        // Overwrite contact as legacy so we can get v1
        publishLegacyContact(client = bobClient)
        publishLegacyContact(client = aliceClient)
        val bobConversation = bobClient.conversations.newConversation(aliceWallet.address)
        val aliceConversation = aliceClient.conversations.newConversation(bobWallet.address)

        bobConversation.send(content = "hey alice")
        bobConversation.send(content = "hey alice again")
        val messages = aliceConversation.messages()
        assertEquals(2, messages.size)
        assertEquals("hey alice", messages[1].body)
        assertEquals(bobWallet.address, messages[1].senderAddress)
    }

    @Test
    fun testCanLoadV2Messages() {
        val bobConversation = bobClient.conversations.newConversation(
            aliceWallet.address,
            InvitationV1ContextBuilder.buildFromConversation("hi")
        )

        val aliceConversation = aliceClient.conversations.newConversation(
            bobWallet.address,
            InvitationV1ContextBuilder.buildFromConversation("hi")
        )
        bobConversation.send(content = "hey alice")
        val messages = aliceConversation.messages()
        assertEquals(1, messages.size)
        assertEquals("hey alice", messages[0].body)
        assertEquals(bobWallet.address, messages[0].senderAddress)
    }

    @Test
    fun testVerifiesV2MessageSignature() {
        val aliceConversation = aliceClient.conversations.newConversation(
            bobWallet.address,
            context = InvitationV1ContextBuilder.buildFromConversation(conversationId = "hi")
        )

        val codec = TextCodec()
        val originalContent = codec.encode(content = "hello")
        val tamperedContent = codec.encode(content = "this is a fake")
        val originalPayload = originalContent.toByteArray()
        val tamperedPayload = tamperedContent.toByteArray()
        val date = Date()
        val header = MessageHeaderV2Builder.buildFromTopic(aliceConversation.topic, created = date)
        val headerBytes = header.toByteArray()
        val digest = Hash.sha256(headerBytes + tamperedPayload)
        val preKey = aliceClient.keys?.preKeysList?.get(0)
        val signature = preKey?.sign(digest)
        val bundle = aliceClient.privateKeyBundleV1?.toV2()?.getPublicKeyBundle()
        val signedContent =
            SignedContentBuilder.builderFromPayload(
                payload = originalPayload,
                sender = bundle,
                signature = signature
            )
        val signedBytes = signedContent.toByteArray()
        val ciphertext =
            Crypto.encrypt(
                aliceConversation.keyMaterial!!,
                signedBytes,
                additionalData = headerBytes
            )
        val tamperedMessage =
            MessageV2Builder.buildFromCipherText(headerBytes = headerBytes, ciphertext = ciphertext)
        aliceClient.publish(
            envelopes = listOf(
                EnvelopeBuilder.buildFromString(
                    topic = aliceConversation.topic,
                    timestamp = Date(),
                    message = MessageBuilder.buildFromMessageV2(v2 = tamperedMessage).toByteArray()
                )
            )
        )
        val bobConversation = bobClient.conversations.newConversation(
            aliceWallet.address,
            InvitationV1ContextBuilder.buildFromConversation("hi")
        )
        assertThrows("Invalid signature", XMTPException::class.java) {
            val messages = bobConversation.messages()
        }
    }

    @Test
    fun testCanSendGzipCompressedV1Messages() {
        publishLegacyContact(client = bobClient)
        publishLegacyContact(client = aliceClient)
        val bobConversation = bobClient.conversations.newConversation(aliceWallet.address)
        val aliceConversation = aliceClient.conversations.newConversation(bobWallet.address)
        bobConversation.send(
            text = MutableList(1000) { "A" }.toString(),
            sendOptions = SendOptions(compression = EncodedContentCompression.GZIP)
        )
        val messages = aliceConversation.messages()
        assertEquals(1, messages.size)
        assertEquals(MutableList(1000) { "A" }.toString(), messages[0].content())
    }

    @Test
    fun testCanSendDeflateCompressedV1Messages() {
        publishLegacyContact(client = bobClient)
        publishLegacyContact(client = aliceClient)
        val bobConversation = bobClient.conversations.newConversation(aliceWallet.address)
        val aliceConversation = aliceClient.conversations.newConversation(bobWallet.address)
        bobConversation.send(
            content = MutableList(1000) { "A" }.toString(),
            options = SendOptions(compression = EncodedContentCompression.DEFLATE)
        )
        val messages = aliceConversation.messages()
        assertEquals(1, messages.size)
        assertEquals(MutableList(1000) { "A" }.toString(), messages[0].content())
    }

    @Test
    fun testCanSendGzipCompressedV2Messages() {
        val bobConversation = bobClient.conversations.newConversation(
            aliceWallet.address,
            InvitationV1ContextBuilder.buildFromConversation(conversationId = "hi")
        )
        val aliceConversation = aliceClient.conversations.newConversation(
            bobWallet.address,
            InvitationV1ContextBuilder.buildFromConversation(conversationId = "hi")
        )
        bobConversation.send(
            text = MutableList(1000) { "A" }.toString(),
            sendOptions = SendOptions(compression = EncodedContentCompression.GZIP)
        )
        val messages = aliceConversation.messages()
        assertEquals(1, messages.size)
        assertEquals(MutableList(1000) { "A" }.toString(), messages[0].body)
        assertEquals(bobWallet.address, messages[0].senderAddress)
    }

    @Test
    fun testCanSendDeflateCompressedV2Messages() {
        val bobConversation = bobClient.conversations.newConversation(
            aliceWallet.address,
            InvitationV1ContextBuilder.buildFromConversation(conversationId = "hi")
        )
        val aliceConversation = aliceClient.conversations.newConversation(
            bobWallet.address,
            InvitationV1ContextBuilder.buildFromConversation(conversationId = "hi")
        )
        bobConversation.send(
            content = MutableList(1000) { "A" }.toString(),
            options = SendOptions(compression = EncodedContentCompression.DEFLATE)
        )
        val messages = aliceConversation.messages()
        assertEquals(1, messages.size)
        assertEquals(MutableList(1000) { "A" }.toString(), messages[0].body)
        assertEquals(bobWallet.address, messages[0].senderAddress)
    }

    @Test
    fun testEndToEndConversation() {
        val fakeContactWallet = PrivateKeyBuilder()
        val fakeContactClient = Client().create(account = fakeContactWallet)
        fakeContactClient.publishUserContact()
        val fakeWallet = PrivateKeyBuilder()
        val client = Client().create(account = fakeWallet)
        val contact = client.getUserContact(peerAddress = fakeContactWallet.address)!!
        assertEquals(contact.walletAddress, fakeContactWallet.address)
        val created = Date()
        val invitationContext = Invitation.InvitationV1.Context.newBuilder().also {
            it.conversationId = "https://example.com/1"
        }.build()
        val invitationv1 =
            InvitationV1.newBuilder().build().createRandom(context = invitationContext)
        val senderBundle = client.privateKeyBundleV1?.toV2()
        assertEquals(
            senderBundle?.identityKey?.publicKey?.recoverWalletSignerPublicKey()?.walletAddress,
            fakeWallet.address
        )
        val invitation = SealedInvitationBuilder.buildFromV1(
            sender = client.privateKeyBundleV1!!.toV2(),
            recipient = contact.toSignedPublicKeyBundle(),
            created = created,
            invitation = invitationv1
        )
        val inviteHeader = invitation.v1.header
        assertEquals(inviteHeader.sender.walletAddress, fakeWallet.address)
        assertEquals(inviteHeader.recipient.walletAddress, fakeContactWallet.address)
        val header = SealedInvitationHeaderV1.parseFrom(invitation.v1.headerBytes)
        val conversation =
            ConversationV2.create(client = client, invitation = invitationv1, header = header)
        assertEquals(fakeContactWallet.address, conversation.peerAddress)

        conversation.send(content = "hello world")

        val conversationList = client.conversations.list()
        val recipientConversation = conversationList.lastOrNull()

        val messages = recipientConversation?.messages()
        val message = messages?.firstOrNull()
        if (message != null) {
            assertEquals("hello world", message.body)
        }
    }

    @Test
    fun testCanUseCachedConversation() {
        bobClient.conversations.newConversation(alice.walletAddress)

        fakeApiClient.assertNoQuery {
            bobClient.conversations.newConversation(alice.walletAddress)
        }
    }

    @Test
    fun testCanPaginateV1Messages() {
        // Overwrite contact as legacy so we can get v1
        publishLegacyContact(client = bobClient)
        publishLegacyContact(client = aliceClient)
        val bobConversation = bobClient.conversations.newConversation(alice.walletAddress)
        val aliceConversation = aliceClient.conversations.newConversation(bob.walletAddress)

        val date = Date()
        date.time = date.time - 1000000
        bobConversation.send(text = "hey alice 1", sentAt = date)
        bobConversation.send(text = "hey alice 2")
        bobConversation.send(text = "hey alice 3")
        val messages = aliceConversation.messages(limit = 1)
        assertEquals(1, messages.size)
        assertEquals("hey alice 3", messages[0].body)
        val messages2 = aliceConversation.messages(limit = 1, after = date)
        assertEquals(1, messages2.size)
        assertEquals("hey alice 2", messages2[0].body)
    }

    @Test
    fun testCanPaginateV2Messages() {
        val bobConversation = bobClient.conversations.newConversation(
            alice.walletAddress,
            context = InvitationV1ContextBuilder.buildFromConversation("hi")
        )

        val aliceConversation = aliceClient.conversations.newConversation(
            bob.walletAddress,
            context = InvitationV1ContextBuilder.buildFromConversation("hi")
        )
        val date = Date()
        date.time = date.time - 1000000
        bobConversation.send(text = "hey alice 1", sentAt = date)
        bobConversation.send(text = "hey alice 2")
        bobConversation.send(text = "hey alice 3")
        val messages = aliceConversation.messages(limit = 1)
        assertEquals(1, messages.size)
        assertEquals("hey alice 3", messages[0].body)
        val messages2 = aliceConversation.messages(limit = 1, after = date)
        assertEquals(1, messages2.size)
        assertEquals("hey alice 2", messages2[0].body)
    }

    @Test
    fun testImportV1ConversationFromJS() {
        val jsExportJSONData =
            (""" { "version": "v1", "peerAddress": "0x5DAc8E2B64b8523C11AF3e5A2E087c2EA9003f14", "createdAt": "2022-09-20T09:32:50.329Z" } """).toByteArray(
                StandardCharsets.UTF_8
            )
        val conversation = aliceClient.importConversation(jsExportJSONData)
        assertEquals(conversation.peerAddress, "0x5DAc8E2B64b8523C11AF3e5A2E087c2EA9003f14")
    }

    @Test
    fun testImportV2ConversationFromJS() {
        val jsExportJSONData =
            (""" {"version":"v2","topic":"/xmtp/0/m-2SkdN5Qa0ZmiFI5t3RFbfwIS-OLv5jusqndeenTLvNg/proto","keyMaterial":"ATA1L0O2aTxHmskmlGKCudqfGqwA1H+bad3W/GpGOr8=","peerAddress":"0x436D906d1339fC4E951769b1699051f020373D04","createdAt":"2023-01-26T22:58:45.068Z","context":{"conversationId":"pat/messageid","metadata":{}}} """).toByteArray(
                StandardCharsets.UTF_8
            )
        val conversation = aliceClient.importConversation(jsExportJSONData)
        assertEquals(conversation.peerAddress, "0x436D906d1339fC4E951769b1699051f020373D04")
    }

    @Test
    fun testImportV2ConversationWithNoContextFromJS() {
        val jsExportJSONData =
            (""" {"version":"v2","topic":"/xmtp/0/m-2SkdN5Qa0ZmiFI5t3RFbfwIS-OLv5jusqndeenTLvNg/proto","keyMaterial":"ATA1L0O2aTxHmskmlGKCudqfGqwA1H+bad3W/GpGOr8=","peerAddress":"0x436D906d1339fC4E951769b1699051f020373D04","createdAt":"2023-01-26T22:58:45.068Z"} """).toByteArray(
                StandardCharsets.UTF_8
            )
        val conversation = aliceClient.importConversation(jsExportJSONData)
        assertEquals(conversation.peerAddress, "0x436D906d1339fC4E951769b1699051f020373D04")
    }

    @Test
    fun testCanStreamConversationsV2() = runTest {
        bobClient.conversations.stream().test {
            val conversation = bobClient.conversations.newConversation(alice.walletAddress)
            conversation.send(content = "hi")
            assertEquals("hi", awaitItem().messages(limit = 1).first().body)
            awaitComplete()
        }
    }

    @Test
    fun testStreamingMessagesFromV1Conversation() = runTest {
        // Overwrite contact as legacy
        publishLegacyContact(client = bobClient)
        publishLegacyContact(client = aliceClient)
        val conversation = aliceClient.conversations.newConversation(bob.walletAddress)
        conversation.streamMessages().test {
            val encoder = TextCodec()
            val encodedContent = encoder.encode(content = "hi alice")
            // Stream a message
            fakeApiClient.send(
                envelope = EnvelopeBuilder.buildFromString(
                    topic = conversation.topic,
                    timestamp = Date(),
                    message = MessageBuilder.buildFromMessageV1(
                        v1 = MessageV1Builder.buildEncode(
                            sender = bobClient.privateKeyBundleV1!!,
                            recipient = aliceClient.privateKeyBundleV1!!.toPublicKeyBundle(),
                            message = encodedContent.toByteArray(),
                            timestamp = Date()
                        )
                    ).toByteArray()
                )
            )
            assertEquals("hi alice", awaitItem().encodedContent.content.toStringUtf8())
            awaitComplete()
        }
    }

    @Test
    fun testStreamingMessagesFromV2Conversations() = runTest {
        val conversation = aliceClient.conversations.newConversation(bob.walletAddress)
        conversation.streamMessages().test {
            val encoder = TextCodec()
            val encodedContent = encoder.encode(content = "hi alice")
            // Stream a message
            fakeApiClient.send(
                envelope = EnvelopeBuilder.buildFromString(
                    topic = conversation.topic,
                    timestamp = Date(),
                    message = MessageBuilder.buildFromMessageV2(
                        v2 = MessageV2Builder.buildEncode(
                            client = bobClient,
                            encodedContent,
                            topic = conversation.topic,
                            keyMaterial = conversation.keyMaterial!!
                        )
                    ).toByteArray()
                )
            )
            assertEquals("hi alice", awaitItem().encodedContent.content.toStringUtf8())
            awaitComplete()
        }
    }

    @Test
    fun testV2RejectsSpoofedContactBundles() {
        val topic = "/xmtp/0/m-Gdb7oj5nNdfZ3MJFLAcS4WTABgr6al1hePy6JV1-QUE/proto"
        val envelopeMessage =
            com.google.crypto.tink.subtle.Base64.decode("Er0ECkcIwNruhKLgkKUXEjsveG10cC8wL20tR2RiN29qNW5OZGZaM01KRkxBY1M0V1RBQmdyNmFsMWhlUHk2SlYxLVFVRS9wcm90bxLxAwruAwognstLoG6LWgiBRsWuBOt+tYNJz+CqCj9zq6hYymLoak8SDFsVSy+cVAII0/r3sxq7A/GCOrVtKH6J+4ggfUuI5lDkFPJ8G5DHlysCfRyFMcQDIG/2SFUqSILAlpTNbeTC9eSI2hUjcnlpH9+ncFcBu8StGfmilVGfiADru2fGdThiQ+VYturqLIJQXCHO2DkvbbUOg9xI66E4Hj41R9vE8yRGeZ/eRGRLRm06HftwSQgzAYf2AukbvjNx/k+xCMqti49Qtv9AjzxVnwttLiA/9O+GDcOsiB1RQzbZZzaDjQ/nLDTF6K4vKI4rS9QwzTJqnoCdp0SbMZFf+KVZpq3VWnMGkMxLW5Fr6gMvKny1e1LAtUJSIclI/1xPXu5nsKd4IyzGb2ZQFXFQ/BVL9Z4CeOZTsjZLGTOGS75xzzGHDtKohcl79+0lgIhAuSWSLDa2+o2OYT0fAjChp+qqxXcisAyrD5FB6c9spXKfoDZsqMV/bnCg3+udIuNtk7zBk7jdTDMkofEtE3hyIm8d3ycmxKYOakDPqeo+Nk1hQ0ogxI8Z7cEoS2ovi9+rGBMwREzltUkTVR3BKvgV2EOADxxTWo7y8WRwWxQ+O6mYPACsiFNqjX5Nvah5lRjihphQldJfyVOG8Rgf4UwkFxmI")
        val keyMaterial =
            com.google.crypto.tink.subtle.Base64.decode("R0BBM5OPftNEuavH/991IKyJ1UqsgdEG4SrdxlIG2ZY=")

        val conversation = ConversationV2(
            topic = topic,
            keyMaterial = keyMaterial,
            context = Context.newBuilder().build(),
            peerAddress = "0x2f25e33D7146602Ec08D43c1D6B1b65fc151A677",
            client = aliceClient,
            header = Invitation.SealedInvitationHeaderV1.newBuilder().build()
        )
        val envelope = EnvelopeBuilder.buildFromString(
            topic = topic,
            timestamp = Date(),
            message = envelopeMessage
        )
        assertThrows("pre-key not signed by identity key", XMTPException::class.java) {
            conversation.decodeEnvelope(envelope)
        }
    }
}
