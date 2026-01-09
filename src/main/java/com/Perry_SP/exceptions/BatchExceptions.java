package com.Perry_SP.exceptions;

/**
 * Custom runtime exceptions for the service.
 */
public class BatchExceptions {

    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String batchId) {
            super("Batch not found: " + batchId);
        }
    }

    public static class InvalidBatchStateException extends RuntimeException {
        public InvalidBatchStateException(String msg) {
            super(msg);
        }
    }

    public static class ChunkSizeExceededException extends RuntimeException {
        public ChunkSizeExceededException(int size, int max) {
            super("Chunk size exceeded (max=" + max + ", actual=" + size + ")");
        }
    }
}