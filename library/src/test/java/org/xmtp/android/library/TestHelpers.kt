package org.xmtp.android.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.xmtp.android.library.codecs.Fetcher
import org.xmtp.android.library.messages.Envelope
import org.xmtp.android.library.messages.Pagination
import org.xmtp.android.library.messages.PrivateKey
import org.xmtp.android.library.messages.PrivateKeyBuilder
import org.xmtp.android.library.messages.Signature
import org.xmtp.android.library.messages.Topic
import org.xmtp.android.library.messages.walletAddress
import org.xmtp.proto.message.api.v1.MessageApiOuterClass
import java.io.File
import java.net.URL

class TestFetcher : Fetcher {
    override fun fetch(url: URL): ByteArray {
        return File(url.toString().replace("https://", "")).readBytes()
    }
}

class FakeWallet : SigningKey {
    private var privateKey: PrivateKey
    private var privateKeyBuilder: PrivateKeyBuilder

    constructor(key: PrivateKey, builder: PrivateKeyBuilder) {
        privateKey = key
        privateKeyBuilder = builder
    }

    companion object {
        fun generate(): FakeWallet {
            val key = PrivateKeyBuilder()
            return FakeWallet(key.getPrivateKey(), key)
        }
    }

    override val address: String
        get() = privateKey.walletAddress

    override fun sign(data: ByteArray): Signature {
        val signature = privateKeyBuilder.sign(data)
        return signature
    }

    override fun sign(message: String): Signature {
        val signature = privateKeyBuilder.sign(message)
        return signature
    }
}

class FakeStreamHolder {
    private val flow = MutableSharedFlow<Envelope>()
    suspend fun emit(value: Envelope) = flow.emit(value)
    fun counts(): Flow<Envelope> = flow
}

class FakeApiClient : ApiClient {
    override val environment: XMTPEnvironment = XMTPEnvironment.LOCAL
    private var authToken: String? = null
    private val responses: MutableMap<String, List<Envelope>> = mutableMapOf()
    val published: MutableList<Envelope> = mutableListOf()
    var forbiddingQueries = false
    private var stream = FakeStreamHolder()

    fun assertNoPublish(callback: () -> Unit) {
        val oldCount = published.size
        callback()
        assertEquals(oldCount, published.size)
    }

    fun assertNoQuery(callback: () -> Unit) {
        forbiddingQueries = true
        callback()
        forbiddingQueries = false
    }

    fun findPublishedEnvelope(topic: Topic): Envelope? =
        findPublishedEnvelope(topic.description)

    fun findPublishedEnvelope(topic: String): Envelope? {
        for (envelope in published.reversed()) {
            if (envelope.contentTopic == topic) {
                return envelope
            }
        }
        return null
    }

    override fun setAuthToken(token: String) {
        authToken = token
    }

    override suspend fun queryTopics(
        topics: List<Topic>,
        pagination: Pagination?,
    ): MessageApiOuterClass.QueryResponse {
        return query(topics = topics.map { it.description }, pagination)
    }

    suspend fun send(envelope: Envelope) {
        stream.emit(envelope)
    }

    override suspend fun envelopes(
        topics: List<String>,
        pagination: Pagination?,
    ): List<MessageApiOuterClass.Envelope> {
        return query(topics = topics, pagination = pagination).envelopesList
    }

    override suspend fun query(
        topics: List<String>,
        pagination: Pagination?,
        cursor: MessageApiOuterClass.Cursor?,
    ): MessageApiOuterClass.QueryResponse {
        var result: MutableList<Envelope> = mutableListOf()
        for (topic in topics) {
            val response = responses.toMutableMap().remove(topic)
            if (response != null) {
                result.addAll(response)
            }
            result.addAll(
                published.filter {
                    it.contentTopic == topic
                }.reversed()
            )
        }

        val startAt = pagination?.startTime
        if (startAt != null) {
            result = result.filter { it.timestampNs < startAt.time * 1_000_000 }
                .sortedBy { it.timestampNs }.toMutableList()
        }
        val endAt = pagination?.endTime
        if (endAt != null) {
            result = result.filter { it.timestampNs > endAt.time * 1_000_000 }
                .sortedBy { it.timestampNs }.toMutableList()
        }
        val limit = pagination?.limit
        if (limit != null) {
            if (limit == 1) {
                val first = result.firstOrNull()
                if (first != null) {
                    result = mutableListOf(first)
                } else {
                    result = mutableListOf()
                }
            } else {
                result = result.take(limit - 1).toMutableList()
            }
        }

        return QueryResponse.newBuilder().also {
            it.addAllEnvelopes(result)
        }.build()
    }

    override suspend fun publish(envelopes: List<MessageApiOuterClass.Envelope>): MessageApiOuterClass.PublishResponse {
        for (envelope in envelopes) {
            send(envelope)
        }
        published.addAll(envelopes)
        return PublishResponse.newBuilder().build()
    }

    override suspend fun subscribe(topics: List<String>): Flow<Envelope> {
        val env = stream.counts().first()

        if (topics.contains(env.contentTopic)) {
            return flowOf(env)
        }
        return flowOf()
    }
}

data class Fixtures(val aliceAccount: PrivateKeyBuilder, val bobAccount: PrivateKeyBuilder) {
    var fakeApiClient: FakeApiClient = FakeApiClient()
    var alice: PrivateKey = aliceAccount.getPrivateKey()
    var aliceClient: Client = Client().create(account = aliceAccount, apiClient = fakeApiClient)
    var bob: PrivateKey = bobAccount.getPrivateKey()
    var bobClient: Client = Client().create(account = bobAccount, apiClient = fakeApiClient)

    constructor() : this(aliceAccount = PrivateKeyBuilder(), bobAccount = PrivateKeyBuilder())
}

fun fixtures(): Fixtures =
    Fixtures()
