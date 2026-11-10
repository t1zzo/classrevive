package app;
public class AppException extends Exception {
    private final int code;
    public AppException(String message, int code) { super(message); this.code = code; }
    private int getCode() { return code; }
    public int code() { return getCode(); }
}
