package org.xmtp.android.library

import androidx.annotation.VisibleForTesting
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.xmtp.android.library.messages.Topic
import org.xmtp.proto.message.api.v1.MessageApiGrpcKt
import org.xmtp.proto.message.api.v1.MessageApiOuterClass.*
import java.io.Closeable
import java.util.concurrent.TimeUnit

data class ApiClient(val environment: XMTPEnvironment, val secure: Boolean = true) : Closeable {
    companion object {
        @VisibleForTesting
        val AUTHORIZATION_HEADER_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        @VisibleForTesting
        val CLIENT_VERSION_HEADER_KEY: Metadata.Key<String> =
            Metadata.Key.of("X-Client-Version", Metadata.ASCII_STRING_MARSHALLER)

        @VisibleForTesting
        val APP_VERSION_HEADER_KEY: Metadata.Key<String> =
            Metadata.Key.of("X-App-Version", Metadata.ASCII_STRING_MARSHALLER)
    }

    private val channel: ManagedChannel = Grpc.newChannelBuilderForAddress(
        environment.rawValue,
        5556,
        InsecureChannelCredentials.create()
    ).build()

    private val client: MessageApiGrpcKt.MessageApiCoroutineStub =
        MessageApiGrpcKt.MessageApiCoroutineStub(channel)
    private var authToken: String = ""

    fun setAuthToken(token: String): String {
        authToken = token
        return authToken
    }

    suspend fun query(topics: List<Topic>): QueryResponse {
        val request = QueryRequest.newBuilder()
            .addAllContentTopics(topics.map { it.description }).build()

        val headers = Metadata()
        headers.put(AUTHORIZATION_HEADER_KEY, "Bearer $authToken")
       return client.query(request, headers = headers)
    }

    suspend fun publish(envelopes: List<Envelope>): PublishResponse {
        val request = PublishRequest.newBuilder().addAllEnvelopes(envelopes).build()
        val headers = Metadata()
        headers.put(AUTHORIZATION_HEADER_KEY, "Bearer $authToken")
        headers.put(CLIENT_VERSION_HEADER_KEY, Constants().version)
        headers.put(APP_VERSION_HEADER_KEY, Constants().version)
        return client.publish(request, headers = headers)
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}