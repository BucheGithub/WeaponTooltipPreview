package wtp.scenario;

/** The weapon cannot be previewed (no hull can mount it...). Expected, so the engine itself is left intact. */
public final class NoPreviewException extends RuntimeException {

    public NoPreviewException(String message) {
        super(message);
    }
}
