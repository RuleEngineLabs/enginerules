package br.com.lucaslima.enginerules.adapter.grpc;

import io.grpc.Status;

final class GrpcStatusMapping {

    private GrpcStatusMapping() {
    }

    static int httpEquivalent(Status.Code code) {
        return switch (code) {
            case OK -> 200;
            case CANCELLED -> 499;
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> 400;
            case UNAUTHENTICATED -> 401;
            case PERMISSION_DENIED -> 403;
            case NOT_FOUND -> 404;
            case ALREADY_EXISTS, ABORTED -> 409;
            case RESOURCE_EXHAUSTED -> 429;
            case UNIMPLEMENTED -> 501;
            case UNAVAILABLE -> 503;
            case DEADLINE_EXCEEDED -> 504;
            case UNKNOWN, INTERNAL, DATA_LOSS -> 500;
        };
    }
}
