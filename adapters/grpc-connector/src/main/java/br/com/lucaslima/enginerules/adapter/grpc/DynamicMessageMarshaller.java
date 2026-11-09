package br.com.lucaslima.enginerules.adapter.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;

import io.grpc.MethodDescriptor.Marshaller;

final class DynamicMessageMarshaller implements Marshaller<DynamicMessage> {

    private final Descriptor type;

    DynamicMessageMarshaller(Descriptor type) {
        this.type = type;
    }

    @Override
    public InputStream stream(DynamicMessage value) {
        return value.toByteString().newInput();
    }

    @Override
    public DynamicMessage parse(InputStream stream) {
        try {
            return DynamicMessage.parseFrom(type, stream);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot decode " + type.getFullName(), failure);
        }
    }
}
