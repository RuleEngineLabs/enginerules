package br.com.lucaslima.enginerules.adapter.grpc;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;

public final class GrpcChannels {

    private GrpcChannels() {
    }

    public static ManagedChannel open(GrpcConnection connection) {
        ChannelCredentials credentials = connection.plaintext()
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.create();
        return Grpc.newChannelBuilder(connection.target(), credentials).build();
    }
}
